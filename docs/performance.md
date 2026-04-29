# Receiver Performance Notes

Receiver targets the Lenovo ThinkSmart View running Android 8.1/API 27 on an 8-inch 1280x800 WVA touchscreen. The performance goal is to keep receiver latency predictable on older hardware while letting the operator choose how aggressively Receiver keeps the display awake.

## Android 8.1 Constraints

Android 8.1 is old enough that the app avoids newer platform APIs and keeps `minSdk` at 27. The media path uses APIs available on the ThinkSmart View:

- `SurfaceView` for direct proportional rendering
- `MediaCodec` for H.264 decode
- `AudioTrack.Builder` and low-latency performance mode for PCM output
- JNI for the native RAOP, mirroring, AAC, crypto, and DNS-SD stack

The UI layer is intentionally static after launch. There are no animations, timers, progress bars, polling widgets, or settings controls competing with decode and audio playback.

The app runs a small foreground service while active so Android treats Receiver as a foreground task. The startup screen exposes three display policies:

- `OS default`: no receiver wake lock or keep-screen-on window flag.
- `Always awake`: sets `FLAG_KEEP_SCREEN_ON` and holds a screen wake lock while Receiver is active.
- `Wake on activity`: allows normal display sleep, then briefly wakes and brings Receiver forward when major video activity arrives.

Major video activity means the first video frame after an idle period, an H.264 IDR frame, or SPS/PPS stream configuration data.

The optional traffic monitor is hidden by default and rendered as a transparent overlay. It charts recent media throughput plus receiver-side latency from Kotlin packet receipt to the audio write or video render handoff. These latency numbers measure Receiver's local pipeline, not sender-to-display wall-clock latency, because the AirPlay timestamps available here are stream-relative.

The traffic monitor is intentionally modest:

- Throughput is counted from decoded media bytes crossing into Kotlin, not raw encrypted network bytes.
- Latency starts when Kotlin receives a direct media buffer from JNI.
- Audio latency stops when `AudioTrack.write` is called.
- Video latency stops when `MediaCodec.releaseOutputBuffer(..., true)` hands the newest decoded output frame to the display surface.
- The chart uses 30 rolling one-second buckets and avoids opaque backgrounds.

## Display And Decode

The ThinkSmart View panel is 1280x800. AirPlay mirroring commonly arrives as a 1280x720 H.264 stream, so Receiver configures the decoder for 1280x720 and centers a 16:9 render surface on the 16:10 panel. On the target display this yields a 1280x720 video area with black bars above and below instead of vertical stretching.

This keeps the decoder format aligned with the stream instead of pretending the incoming video is 1280x800. It also avoids CPU-side scaling in Kotlin.

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

- Video uses a small fixed-size `ArrayBlockingQueue` capped at 2 frames and trims backlog to keep only the newest pending packet before enqueueing.
- Decoded video output is drained aggressively; if several decoded frames are waiting, stale output buffers are discarded and only the newest one is rendered.
- The same H.264 scan used for display wake decisions looks only for start codes and NAL types, avoiding deeper parsing in the hot path.
- Traffic monitor aggregation is cheap enough to stay enabled, but the chart is only redrawn while the overlay is visible.
- Audio uses a small fixed-size `ArrayBlockingQueue` capped at 4 PCM packets and trims backlog to 3 pending packets before enqueueing.
- The native RAOP audio reorder buffer is capped at 64 packets, limiting how long playback can stall while waiting for a missing packet or resend.
- Playback threads request Android's urgent display/audio thread priorities.
- The H.264 decoder is configured with API 27-compatible priority and operating-rate hints.
- Audio writes use non-blocking `AudioTrack` writes so full platform buffers do not grow receiver-side latency.
- Decode and playback threads poll with short timeouts so shutdown is responsive.
- Frame-level logging is behind `DEBUG_FRAMES = false`.

These choices are deliberate for an appliance receiver. If the device cannot keep up for a moment, recovering to live playback is better than playing an increasingly delayed stream.

## Native Boundary

JNI still requires copying payloads because the native RAOP/mirroring buffers are not owned by the Android playback queues after the callback returns. The bridge now copies into native-owned direct buffers instead of JVM arrays, which reduces Java heap churn and lets audio write through `AudioTrack.write(ByteBuffer, ...)`.

Video still needs one copy into `MediaCodec` input buffers. Removing that would require a deeper native media path or a decoder integration that owns input buffers directly. That should only be done after profiling on the actual ThinkSmart View.

## Operational Guidance

For best results on the target device:

- Pick the display policy on the startup screen before connecting; the choice is remembered locally.
- Drag in from the top-right corner to show the traffic monitor; tap the monitor to hide it.
- Interpret traffic monitor latency as local receiver pressure. It is useful for spotting queue/decode stalls, but not for comparing sender capture or network delay.
- The active receiver notification is expected while the app is running.
- Use a stable wired or strong Wi-Fi network.
- Avoid enabling verbose media logging during real playback.
- Prefer release builds for device testing.
- Profile on the ThinkSmart View itself before making larger native or decoder changes.

Useful checks on-device are dropped-frame behavior, audio drift, startup time to DNS-SD visibility, and whether reconnecting repeatedly leaves native sessions behind.
