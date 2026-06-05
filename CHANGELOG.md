# Changelog

## Unreleased

- Reframed the app as an Android TV / Google TV AirPlay receiver.
- Bumped the app to `compileSdk 34` and `targetSdk 34`.
- Kept `arm64-v8a` and `armeabi-v7a` native output so Play bundles include 64-bit libraries while current 32-bit Android TV devices remain installable.
- Added Android App Bundle split configuration for Play release output.
- Added 16 KB native page-size linker alignment.
- Required Leanback support and forced landscape orientation for app activities.
- Replaced the placeholder TV launcher banner with an intentional vector banner.
- Added receiver quality profiles, screen-fit modes, audio sync preference storage, security modes, guest mode, idle clock, reduce motion, frame-rate matching, and visualizer preference scaffolding.
- Added `FIRST_RUN` and `PAIRING` receiver states plus last disconnect reason in session stats.
- Removed touch-only playback gestures from the activity control path.
- Added D-pad playback overlay actions for Stop, Screen Fit, Diagnostics, and Traffic.
- Hid network details from the default ready screen.
- Added a direct one-time first-run setup launch before returning to the ready screen.
- Migrated first-run onboarding to a Compose D-pad flow using a conservative AndroidX TV foundation dependency.
- Added a burn-in-conscious ready-screen clock with optional motion reduction.
- Added persisted trusted/blocked sender list storage and management.
- Added PIN-screen scaffolding and explicit native-pairing limitations while keeping DNS-SD on the working no-PIN path.
- Kept Open as the enforced default security mode until native PIN verification can make PIN modes real.
- Added a dedicated audio-only screen with route display and optional low-cost visualizer.
- Added generic MediaSession metadata for audio-only sessions.
- Added API 30+ surface frame-rate hint support.
- Added Audio Stable buffer tuning.
- Added audio-route UI refresh callbacks.
- Added diagnostics suggestions and a restart discovery action.
- Updated README and docs for Android TV release posture, compatibility limits, and native PIN pairing limitations.
