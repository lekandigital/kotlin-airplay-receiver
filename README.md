# Receiver

![Icon](docs/icon-256.png)

Receiver is a small Android AirPlay receiver tailored for the Lenovo ThinkSmart View. It was custom made for the device and the project background is described in [this Tao of Mac post](https://taoofmac.com/space/blog/2023/04/22/1330).

The application starts listening as soon as it is launched. There is no in-app control interface: start it from the Android launcher and stop it through the system app switcher or device controls.

## What It Does

- Advertises itself on the local network as an AirPlay/RAOP target using the Android device name.
- Receives H.264 screen mirroring and renders it to a centered proportional `SurfaceView`.
- Optionally receives audio, decodes AAC in the native RAOP stack, and plays PCM through `AudioTrack`.
- Keeps the UI intentionally minimal: a startup status line and display wake policy picker are shown while waiting, then the app behaves like a dedicated receiver. A transparent traffic monitor can be pulled in from the top-right corner when needed.

## Runtime Controls

Receiver starts listening immediately, but the waiting screen exposes one display policy choice before a sender connects:

- `OS default`: lets Android manage dimming, sleep, and screensaver behavior.
- `Always awake`: keeps the display on while Receiver is active.
- `Wake on activity`: lets the display sleep, then wakes Receiver for major visual activity such as a new stream after idle or H.264 key/config frames.

The selected display policy is remembered on the device.

During playback, drag in from the top-right corner to reveal the transparent traffic monitor. Tap the monitor to hide it. The monitor shows recent media bandwidth with adaptive `b/s`, `kb/s`, or `Mb/s` labels plus Receiver's local packet-to-render/write latency; it is a diagnostic overlay, not an end-to-end AirPlay latency measurement.

Audio is off by default because Receiver prioritizes minimum video latency. Enable `Accept audio` before connecting only when audio is needed; when it is off, Receiver advertises reduced audio capability and rejects the sender's audio setup while keeping mirroring available. When audio is accepted and a stream is active, swipe vertically from the right edge to adjust Android media volume; a visible blue volume bar is shown on the waiting screen and briefly as a vertical on-video indicator during swipe changes.

When the sender stops or disconnects from a mirrored stream, Receiver exits and lets Android return to the previous/system screen.

## Target Device

Receiver is built around the Lenovo ThinkSmart View and its Android 8.1 runtime. The UI is tuned for the device's 8-inch 1280x800 WVA touchscreen, while the media decoder keeps the mirrored H.264 stream at 1280x720 and renders it into a centered 16:9 surface without distortion.

It may run on other Android devices, but that is not the design target.

## Release APKs

The project is a Kotlin Android application with a native C/C++ streaming stack. Release APKs are built by GitHub Actions.

The workflow in `.github/workflows/android.yml` installs the expected Android toolchain, builds the release APK on every push, pull request, or manual run, and uploads `Receiver-release.apk` as a downloadable workflow artifact. Tag pushes also create a GitHub Release and attach the APK as a release asset.

Release APKs are signed with v1 and v2 APK signatures enabled for Android 8.1 compatibility. If signing secrets are configured in GitHub Actions, CI uses that stable release key; otherwise it falls back to Android debug signing for ad hoc sideloading. If Android still says "App not installed" after a signing-key change, uninstall the previous `io.carmo.airplay.receiver` build first and then install the new APK.

The local Gradle wrapper is kept only so Actions can run a reproducible build from the repository. Actions uses Gradle's setup action for caching, but the wrapper still defines the Gradle version.

## Project Layout

- `app/src/main/kotlin/io/carmo/airplay/receiver/` contains the Kotlin application code.
- `app/src/main/java/com/apple/dnssd/` contains the Java DNS-SD compatibility bindings used for Bonjour registration.
- `app/src/main/cpp/` contains the native AirPlay, RAOP, mirroring, AAC, crypto, and JNI code.
- `docs/architecture.md` documents the runtime architecture and data flow.
- `docs/performance.md` documents the Android 8.1 performance assumptions and tuning decisions.
- `docs/vendor-audit.md` documents the retained vendored code and the clutter removed from upstream drops.

## Optional Stable Signing

For upgrade-safe sideloaded releases, configure these GitHub repository secrets before tagging a release:

- `RECEIVER_RELEASE_KEYSTORE_BASE64`: base64-encoded PKCS#12 or JKS keystore.
- `RECEIVER_RELEASE_KEYSTORE_PASSWORD`: keystore password.
- `RECEIVER_RELEASE_KEY_ALIAS`: signing key alias.
- `RECEIVER_RELEASE_KEY_PASSWORD`: signing key password.
- `RECEIVER_RELEASE_KEYSTORE_TYPE`: optional, defaults to `pkcs12`.

## License

Receiver is distributed under GPLv3 because the retained native Playfair component is GPLv3. Third-party code keeps its original notices in the vendored source directories; see `docs/vendor-audit.md`.

## App Identity

- App name: `Receiver`
- Android application id: `io.carmo.airplay.receiver`
- Minimum Android version: Android 8.1/API 27
- Target Android version: Android 8.1/API 27
