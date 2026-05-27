# Tripbook

Tripbook is an Android trip log for recording vehicle trips on a phone. The app is designed for personal trip recording without accounts or hosted sync. Trip data stays local; network access is used for map tiles when picking saved places.

The installed app label is `Tripbook`, and the Android application ID is `com.snupai.tripbook`.

## Features

- Start and stop local trip recording from the app.
- Track active trips with a foreground location service.
- Pick place rules on a map for common trip start or end locations.
- Optionally keep a foreground auto-record service running so the app can detect configured vehicle Bluetooth devices or Google Play Services driving-motion transitions in the background after the user accepts the in-app background location disclosure.
- Review completed trips, quickly classify common categories, and maintain custom notes.
- Configure work-hours auto-categorization, vehicles, odometer readings, and custom per-category distance rates.
- Export date-range trip reports as CSV or PDF files/share sheet payloads.
- Keep trip data local by default; map picking loads external map tiles.

## Project Structure

- `app/` - Android application module.
- `app/src/main/java/com/snupai/tripbook/` - Java source for UI, trip tracking, storage, Bluetooth/motion detection, reports, and notifications.
- `app/src/test/` - JVM tests for pure Java helpers.
- `app/src/androidTest/` - Instrumented tests for Android-backed storage behavior.
- `docs/` - Play Store release checklist and privacy policy draft.
- `app/src/main/AndroidManifest.xml` - App components, features, and permissions.
- `gradle/wrapper/` - Checked-in Gradle wrapper used for reproducible builds.

## Requirements

- Android Studio or Android SDK command-line tools.
- JDK 17 or newer.
- Android SDK Platform 36.
- A configured SDK path through Android Studio, `ANDROID_HOME`, or `local.properties`.

## Build

Build the debug APK from the project root:

```sh
./gradlew :app:assembleDebug
```

Run Android lint:

```sh
./gradlew :app:lintDebug
```

Run local JVM tests:

```sh
./gradlew :app:testDebugUnitTest
```

Build the instrumented test APK:

```sh
./gradlew :app:assembleDebugAndroidTest
```

Build the release app bundle for Play upload:

```sh
./gradlew :app:bundleRelease
```

Open the project in Android Studio and launch the `app` configuration on an emulator or device running Android 8.0/API 26 or newer.

## Privacy

Trip data is intended to remain on the device. Android backup is disabled with `android:allowBackup="false"` so trip records stay local by default. The map picker uses network access to load map tiles.

The Play Store privacy policy is hosted at:

```txt
https://snupai.github.io/tripbook/privacy-policy/
```

The source text is in `docs/privacy-policy.md`, and the GitHub Pages HTML version is in `docs/privacy-policy/index.html`.

## Permission Notes

- Foreground location: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_LOCATION` support active trip tracking while a foreground service notification is shown.
- Background location: `ACCESS_BACKGROUND_LOCATION` must be requested separately on modern Android versions. The app now shows a prominent disclosure before opening this permission flow.
- Notifications: `POST_NOTIFICATIONS` is needed on Android 13+ so the foreground tracking notification can be shown normally.
- Bluetooth auto-recording: `BLUETOOTH_CONNECT` is used on Android 12+ for paired-device connection state. `FOREGROUND_SERVICE_CONNECTED_DEVICE` supports the persistent drive-detection notification. Legacy `BLUETOOTH` and `BLUETOOTH_ADMIN` permissions are limited to Android 11/API 30 and below. Bluetooth auto-record is off by default and only becomes available after disclosure acceptance, at least one vehicle Bluetooth device, location, background location, Bluetooth, and notification permissions.
- Motion auto-recording: `ACTIVITY_RECOGNITION` is used on Android 10+ for Google Play Services driving-motion transitions. Motion auto-record is optional and can be left off if the user wants Bluetooth-only auto-recording.
- Internet: `INTERNET` supports the place map picker. It is not used for account sync or trip upload.

## Testing Notes

Before release, manually cover first launch permissions, notification approval on Android 13+, physical activity approval on Android 10+, background location opt-in, start/stop trip flows, map place picking, PDF/CSV report export, vehicle/rate/work-hours settings, app backgrounding, process death recovery, Bluetooth connect/disconnect behavior with a paired vehicle device, and motion auto-recording. The release checklist in `docs/play-release-checklist.md` tracks Play Console, policy, and QA work that cannot be fully automated from this repo.
