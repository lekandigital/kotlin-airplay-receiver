# AirPlay Receiver

![Icon](docs/icon-256.png)

AirPlay Receiver is an Android TV / Google TV app that makes a TV discoverable as an AirPlay and RAOP receiver on the local network. It is designed for D-pad remotes, leanback launchers, and couch-distance readability.

The native RAOP, mirroring, FairPlay, AAC, and H.264 stack is preserved. The Kotlin layer focuses on TV app behavior: foreground-service lifetime, discovery, the ready screen, playback surface management, settings, diagnostics, and user-facing controls.

## What It Does

- Advertises the TV on the local network as an AirPlay / RAOP target.
- Receives H.264 screen mirroring and renders it to a `SurfaceView`.
- Receives AirPlay audio, decodes AAC in the native stack, and plays PCM through `AudioTrack`.
- Keeps the TV discoverable after disconnect by returning to the ready screen by default.
- Provides remote-first settings for receiver name, quality profile, screen fit, audio sync, display behavior, security mode, discovery restart, diagnostics, and receiver identity reset.
- Shows a first-run setup flow for receiver name, security mode, quality profile, and connection instructions.
- Shows a dedicated audio-only screen with metadata placeholders and an optional low-cost spectrum visualizer.

## Target Devices

This app targets Android TV and Google TV devices such as NVIDIA Shield, Chromecast with Google TV, and TV sets running Android TV / Google TV.

- Leanback launcher support is required in the manifest.
- Pointer input is not required for the primary interaction model.
- All core controls are reachable with D-pad, Select, Back, Home, and the remote volume keys.
- The app is landscape-only.

## Runtime Controls

The ready screen shows a burn-in-conscious clock, receiver name, `Ready to AirPlay`, a short connection hint, the current quality profile, and the security mode. It does not show IP addresses, receiver IDs, sender history, or other diagnostics by default.

Press Select on the ready screen to open settings. The ready screen also has explicit Settings and Help buttons for D-pad focus.

During video playback, press Select to show the playback overlay. The overlay includes the receiver name, resolution, quality profile, screen fit, audio route, runtime state, and quick actions:

- Stop: disconnect the current stream and return to ready.
- Screen fit: cycle Fit, Fill, and Stretch.
- Diagnostics: open the diagnostics screen.
- Traffic: show or hide the diagnostic traffic monitor.

Volume uses the Android media volume keys on the remote. Diagnostic controls live in the playback overlay.

## Quality Profiles

Quality profiles map onto the existing advertised stream-size plumbing:

- Auto: detect the display and choose 720p or 1080p.
- Low Latency: advertise 720p and keep latency conservative.
- Balanced: advertise 1080p with moderate defaults.
- Best Quality: advertise 1080p for the cleanest source stream.
- Compatibility: advertise 720p for older or problematic senders.
- Audio Stable: advertise 720p and prioritize audio robustness.

Frame-rate matching is exposed as a setting and applies an API 30+ `Surface.setFrameRate()` hint when enabled. The current media path uses a 60 fps fixed-source hint until sender frame-rate detection is added.

## Security Modes

The settings screen includes these security modes:

- Open - no pairing required. This is the currently enforced behavior.
- PIN for new devices. Planned future default once native PIN verification is implemented.
- PIN every session. Planned.
- Trusted devices only. Planned.

PIN and trusted-only modes are UI scaffolding until the native stack can enforce AirPlay pairing. Discovery intentionally remains compatible with the working receiver path (`pw=false`) until native PIN verification and stable sender identifiers are implemented.

Trusted devices, blocked devices, guest mode, and takeover protection are represented in the settings model. Trust and block lists are persisted and manageable, but they are not enforced until native pairing identifiers are available.

## Known Limits

This project does not claim universal AirPlay compatibility. AirPlay behavior depends on sender device, app, OS version, network configuration, and the native protocol stack.

Known practical limits:

- DRM-protected or app-restricted streams may not play.
- Some senders may negotiate protocol features this receiver does not yet implement.
- Guest Wi-Fi, VPNs, multicast filtering, and client isolation can prevent discovery.
- PIN pairing UI is scaffolded, but native PIN verification is still required for real enforcement.
- Metadata and album art for audio-only playback depend on native metadata forwarding; the current TV screen shows generic AirPlay audio metadata.

## Build And Release

The app builds as a Kotlin Android application with a native C/C++ stack through CMake and the Android NDK.

Current release posture:

- Minimum SDK: Android 8.1 / API 27.
- Target SDK: API 34.
- ABI: `arm64-v8a` and `armeabi-v7a`.
- The AAB includes 64-bit native libraries for Play compliance while retaining 32-bit output for Android TV devices that still ship a 32-bit userspace.
- Compose is enabled for the onboarding flow with a conservative AndroidX TV foundation dependency; the playback and settings screens remain on the existing View/XML path for now.
- Native shared libraries are linked with `-Wl,-z,max-page-size=16384` for 16 KB page-size readiness.
- Android App Bundle output is supported through Gradle's `bundleRelease` task.
- APK output remains available through `assembleRelease` for local testing and sideloading.

Release signing reads `signing.properties` when present and falls back to debug signing for local ad hoc builds. Do not commit keystores or signing secrets.

## Project Layout

- `app/src/main/kotlin/io/carmo/airplay/receiver/` contains the Kotlin application code.
- `app/src/main/java/com/apple/dnssd/` contains legacy Java DNS-SD compatibility bindings.
- `app/src/main/cpp/` contains the native AirPlay, RAOP, mirroring, AAC, crypto, and JNI code.
- `docs/architecture.md` documents runtime architecture and data flow.
- `docs/performance.md` documents Android TV performance assumptions and tuning decisions.
- `docs/vendor-audit.md` documents retained vendored code and license notes.

## License

AirPlay Receiver is distributed under GPLv3 because the retained native Playfair component is GPLv3. Third-party code keeps its original notices in the vendored source directories; see `docs/vendor-audit.md`.

## App Identity

- App name: `AirPlay Receiver`
- Android application id: `io.carmo.airplay.receiver`
- Minimum Android version: Android 8.1 / API 27
- Target Android version: API 34
