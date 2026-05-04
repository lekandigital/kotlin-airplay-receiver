# Receiver Performance Notes

Receiver targets the Lenovo ThinkSmart View running Android 8.1/API 27 on an 8-inch 1280x800 WVA touchscreen. The performance goal is to keep receiver latency predictable on older hardware while letting the operator choose how aggressively Receiver keeps the display awake.

## Android 8.1 Constraints

Android 8.1 is old enough that the app avoids newer platform APIs and keeps `minSdk` at 27. The media path uses APIs available on the ThinkSmart View:

- `SurfaceView` for direct proportional rendering
- `MediaCodec` for H.264 decode
- `AudioTrack.Builder` and low-latency performance mode for PCM output
- JNI for the native RAOP, mirroring, AAC, crypto, and DNS-SD stack

The UI layer is intentionally static after launch. The pre-connection controls sit on one centered pane and disappear when streaming begins. There are no animations, polling widgets, or settings controls competing with decode and audio playback during a stream.

The app runs a small foreground service while active so Android treats Receiver as a foreground task. The startup screen exposes two stream resolution modes:

- `720p`: advertise 1280x720 and avoid extra decode/downscale work.
- `1080p`: advertise 1920x1080 and downscale through the display surface.

The startup screen also exposes three display policies:

- `OS default`: no receiver wake lock or keep-screen-on window flag.
- `Always awake`: sets `FLAG_KEEP_SCREEN_ON` and holds a screen wake lock while Receiver is active.
- `Wake on activity`: allows normal display sleep, then briefly wakes and brings Receiver forward when major video activity arrives.

Major video activity means the first video frame after an idle period, an H.264 IDR frame, or SPS/PPS stream configuration data.

The optional traffic monitor is hidden by default and rendered as a transparent overlay. It charts recent media throughput with adaptive `b/s`, `kb/s`, or `Mb/s` labels plus receiver-side latency from Kotlin packet receipt to the audio write or video render handoff. These latency numbers measure Receiver's local pipeline, not sender-to-display wall-clock latency, because the AirPlay timestamps available here are stream-relative.

The traffic monitor is intentionally modest:

- Throughput is counted from decoded media bytes crossing into Kotlin, not raw encrypted network bytes.
- Latency starts when Kotlin receives a direct media buffer from JNI.
- Audio latency stops when `AudioTrack.write` is called.
- Video latency stops when `MediaCodec.releaseOutputBuffer(..., true)` hands the newest decoded output frame to the display surface.
- The chart uses 30 rolling half-second samples, plots only completed samples, and avoids opaque backgrounds.

The startup pane remains visible for the first four rendered video outputs. This adds only a few frame intervals at stream start, but lets the hardware surface replace stale or partially decoded startup contents before the centered controls disappear.

Audio is disabled by default and can be enabled before a sender connects. In the default mode Receiver strips audio format details from `/info` and rejects the native audio `SETUP` request. The Kotlin-side audio drop path remains as a safety fallback if a client retries or races with a setting change.

Receiver exits on explicit stream teardown, when the mirror media socket closes and no fresh media arrives during a short grace window, or when an established stream goes media-idle for 20 seconds. This avoids leaving the appliance in a stale receiver state after the sender stops mirroring and also releases wake locks, foreground notification state, media players, and DNS-SD registrations through the normal activity destroy path. Receiver does not exit merely because the RTSP control socket closes, since some senders recycle that socket while a long-running mirror stream is still active.

Native mirror sessions are kept in a small active-session registry after RTSP control reconnects. This lets Receiver keep a constant stream alive while still joining mirror and timing threads during teardown, and caps malformed mirror payload sizes before they can grow native buffers unexpectedly.

## Display And Decode

The ThinkSmart View panel is 1280x800. AirPlay mirroring commonly arrives as a 1280x720 H.264 stream, so Receiver defaults to 720p mode, configures the decoder for 1280x720, and centers a 16:9 render surface on the 16:10 panel. On the target display this yields a 1280x720 video area with black bars above and below instead of vertical stretching.

This keeps the decoder format aligned with the stream instead of pretending the incoming video is 1280x800. It also avoids CPU-side scaling in Kotlin. The optional 1080p mode advertises 1920x1080 through `/info`, configures `MediaCodec` for 1920x1080, and relies on `SurfaceView`/hardware composition to downscale into the visible 16:9 view. Receiver does not force an oversized `SurfaceHolder` buffer for 1080p because that can destabilize the older Android 8.1 display stack on the ThinkSmart View. That mode may improve source-side quality, but it is expected to cost more decode bandwidth and should be tested against the latency chart on the actual device. The video queue has been restored to the `0.2.12` low-latency shape: a tiny queue preserves codec config, keeps only the freshest pending video input, and drains decoder output to render the newest available frame.

## Hot Path

The hot path is:

1. Native socket and protocol handling receives encrypted audio/video data.
2. Native code decrypts and normalizes packets.
3. JNI copies packet payloads into native-owned direct buffers and passes `ByteBuffer` views into Kotlin.
4. Kotlin queues the direct buffer wrapper on the relevant playback thread.
5. `VideoPlayer` copies video into `MediaCodec` input buffers; `AudioPlayer` writes direct PCM buffers to `AudioTrack`.
6. The playback thread frees the native buffer as soon as the packet is written, decoded, or dropped.

The Kotlin side does not parse protocol state in the hot path. It only receives decoded packet boundaries and hands them to Android playback primitives.

## Latency Controls

Receiver prefers dropping stale media over building delay:

- Video uses a tiny fixed-size `ArrayBlockingQueue` that preserves codec config while replacing pending video input with only the newest packet before enqueueing.
- After fresh H.264 codec config, video waits for an IDR frame before queueing non-config frame data.
- Decoded video output is drained aggressively; if several decoded frames are waiting, stale output buffers are discarded and only the newest one is rendered.
- The same H.264 scan used for display wake decisions looks only for start codes and NAL types, avoiding deeper parsing in the hot path.
- Traffic monitor aggregation is cheap enough to stay enabled, but the chart is only redrawn while the overlay is visible.
- Audio uses a fixed-size `ArrayBlockingQueue` capped at 32 PCM packets, prebuffers 8 packets before playback starts, and trims backlog to 24 pending packets before enqueueing.
- The native RAOP audio reorder buffer is capped at 64 packets, limiting how long playback can stall while waiting for a missing packet or resend.
- Playback threads request Android's urgent display/audio thread priorities.
- The H.264 decoder is configured with API 27-compatible priority and operating-rate hints.
- Audio writes use blocking `AudioTrack` writes and a 4x platform buffer to reduce choppiness on the ThinkSmart View without letting optional audio build as much local latency as the earlier larger buffer.
- Local volume control changes Android's media stream volume, so it follows the device's system audio path instead of only scaling the app's `AudioTrack`.
- Decode and playback threads poll with short timeouts so shutdown is responsive.
- Frame-level logging is behind `DEBUG_FRAMES = false`.

These choices are deliberate for an appliance receiver. If the device cannot keep up for a moment, recovering to live playback is better than playing an increasingly delayed stream.

## Native Boundary

JNI still requires copying payloads because the native RAOP/mirroring buffers are not owned by the Android playback queues after the callback returns. The bridge now copies into native-owned direct buffers instead of JVM arrays, which reduces Java heap churn and lets audio write through `AudioTrack.write(ByteBuffer, ...)`.

Video still needs one copy into `MediaCodec` input buffers. Removing that would require a deeper native media path or a decoder integration that owns input buffers directly. That should only be done after profiling on the actual ThinkSmart View.

## Operational Guidance

For best results on the target device:

- Pick the stream resolution and display policy on the startup screen before connecting; both choices are remembered locally.
- Leave `Accept audio` checked to play audio; clear it before connecting to reject audio setup from the sender.
- Swipe vertically from the right edge to adjust local audio volume when audio is accepted.
- Drag in from the top-right corner to show the traffic monitor; tap the monitor to hide it.
- Expect Receiver to close when the sender disconnects; relaunch it from Android when the device should listen again.
- Interpret traffic monitor latency as local receiver pressure. It is useful for spotting queue/decode stalls, but not for comparing sender capture or network delay.
- The active receiver notification is expected while the app is running.
- Use a stable wired or strong Wi-Fi network.
- Avoid enabling verbose media logging during real playback.
- Prefer release builds for device testing.
- Profile on the ThinkSmart View itself before making larger native or decoder changes.

Useful checks on-device are dropped-frame behavior, audio drift, startup time to DNS-SD visibility, and whether reconnecting repeatedly leaves native sessions behind.
