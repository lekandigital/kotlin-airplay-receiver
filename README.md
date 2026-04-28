# Receiver

<p align="center">
  <img src="docs/icon-256.png" width="128" height="128" alt="Receiver icon">
</p>

Receiver is a small Android AirPlay receiver tailored for the Lenovo ThinkSmart View. It was custom made for the device and the project background is described in [this Tao of Mac post](https://taoofmac.com/space/blog/2023/04/22/1330).

The application starts listening as soon as it is launched. There is no in-app control interface: start it from the Android launcher and stop it through the system app switcher or device controls.

## What It Does

- Advertises itself on the local network as an AirPlay/RAOP target using the Android device name.
- Receives H.264 screen mirroring and renders it to a full-screen `SurfaceView`.
- Receives audio, decodes AAC in the native RAOP stack, and plays PCM through `AudioTrack`.
- Keeps the UI intentionally blank and full-screen so the device behaves like a dedicated receiver.

## Target Device

Receiver is built around the Lenovo ThinkSmart View and its Android 8.1 runtime. The UI is tuned for the device's 8-inch 1280x800 WVA touchscreen, while the media decoder keeps the mirrored H.264 stream at 1280x720 and lets Android scale it onto the fullscreen surface without distortion.

It may run on other Android devices, but that is not the design target.

## Release APKs

The project is a Kotlin Android application with a native C/C++ streaming stack. Release APKs are built by GitHub Actions.

The workflow in `.github/workflows/android.yml` installs the expected Android toolchain, builds the release APK on every push, pull request, or manual run, and uploads `Receiver-release.apk` as a downloadable workflow artifact.

The local Gradle wrapper is kept only so Actions can run a reproducible build from the repository. Actions uses Gradle's setup action for caching, but the wrapper still defines the Gradle version.

## Project Layout

- `app/src/main/kotlin/io/carmo/airplay/receiver/` contains the Kotlin application code.
- `app/src/main/java/com/apple/dnssd/` contains the Java DNS-SD compatibility bindings used for Bonjour registration.
- `app/src/main/cpp/` contains the native AirPlay, RAOP, mirroring, AAC, crypto, and JNI code.
- `docs/architecture.md` documents the runtime architecture and data flow.
- `docs/performance.md` documents the Android 8.1 performance assumptions and tuning decisions.
- `docs/vendor-audit.md` documents the retained vendored code and the clutter removed from upstream drops.

## License

Receiver is distributed under GPLv3 because the retained native Playfair component is GPLv3. Third-party code keeps its original notices in the vendored source directories; see `docs/vendor-audit.md`.

## App Identity

- App name: `Receiver`
- Android application id: `io.carmo.airplay.receiver`
- Minimum Android version: Android 8.1/API 27
