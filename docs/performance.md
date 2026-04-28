# Receiver Performance Notes

Receiver targets the Lenovo ThinkSmart View running Android 8.1/API 27 on an 8-inch 1280x800 WVA touchscreen. The performance goal is not to add more UI or background behavior; it is to keep receiver latency predictable on older hardware.

## Android 8.1 Constraints

Android 8.1 is old enough that the app avoids newer platform APIs and keeps `minSdk` at 27. The media path uses APIs available on the ThinkSmart View:

- `SurfaceView` for direct proportional rendering
- `MediaCodec` for H.264 decode
- `AudioTrack.Builder` and low-latency performance mode for PCM output
- JNI for the native RAOP, mirroring, AAC, crypto, and DNS-SD stack

The UI layer is intentionally static after launch. There are no animations, timers, progress bars, polling widgets, or settings controls competing with decode and audio playback.

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

- Video uses a small fixed-size `ArrayBlockingQueue` capped at 6 frames; when full, the oldest frame is dropped.
- Audio uses a small fixed-size `ArrayBlockingQueue` capped at 24 PCM packets; when full, the oldest PCM packet is dropped.
- Decode and playback threads poll with short timeouts so shutdown is responsive.
- Frame-level logging is behind `DEBUG_FRAMES = false`.

These choices are deliberate for an appliance receiver. If the device cannot keep up for a moment, recovering to live playback is better than playing an increasingly delayed stream.

## Native Boundary

JNI still requires copying payloads because the native RAOP/mirroring buffers are not owned by the Android playback queues after the callback returns. The bridge now copies into native-owned direct buffers instead of JVM arrays, which reduces Java heap churn and lets audio write through `AudioTrack.write(ByteBuffer, ...)`.

Video still needs one copy into `MediaCodec` input buffers. Removing that would require a deeper native media path or a decoder integration that owns input buffers directly. That should only be done after profiling on the actual ThinkSmart View.

## Operational Guidance

For best results on the target device:

- Keep the app in the foreground while receiving.
- Use a stable wired or strong Wi-Fi network.
- Avoid enabling verbose media logging during real playback.
- Prefer release builds for device testing.
- Profile on the ThinkSmart View itself before making larger native or decoder changes.

Useful checks on-device are dropped-frame behavior, audio drift, startup time to DNS-SD visibility, and whether reconnecting repeatedly leaves native sessions behind.
