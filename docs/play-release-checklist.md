# Play Store Release Checklist

## Build And Signing

- Confirm `applicationId` is `com.snupai.tripbook`.
- Confirm release version is ready in `app/build.gradle.kts`.
- Build `./gradlew :app:bundleRelease`.
- Enroll the app in Play App Signing and upload a signed AAB.
- Increment `versionCode` for every Play upload.

## Policy And Store Content

- Add `https://snupai.github.io/tripbook/privacy-policy/` to Play Console and the store listing.
- Complete Data safety as local-first location data with no developer account collection, no ads, no analytics, and no hosted trip sync. Account for map tile/network requests in the privacy policy and Play disclosures.
- Complete the background location declaration for Bluetooth-triggered and motion-triggered trip recording.
- Complete the physical activity permission declaration for optional motion auto-recording.
- Complete the foreground service declarations for trip recording and persistent Bluetooth/motion drive detection with ongoing notifications.
- Disclose internet access for map tile loading in the store listing/privacy policy.
- Complete content rating, target audience, app category, and app access instructions.
- Prepare screenshots showing the trip screen, permission state, place rules, trip review list, vehicles, rates, work hours, and PDF/CSV export.

## Automated Checks

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebugAndroidTest`
- `./gradlew :app:connectedDebugAndroidTest` on a device or emulator
- `./gradlew :app:lintDebug`
- `./gradlew :app:bundleRelease`

## Manual QA

- First launch with all permissions denied.
- Manual trip start/stop with foreground location only.
- Background location disclosure and opt-in.
- Auto-record off by default on fresh install.
- Auto-record enabled after configured vehicle Bluetooth, disclosure, background location, Bluetooth, and notification permissions.
- Motion auto-record enabled after disclosure, location, background location, physical activity, and notification permissions.
- Bluetooth-only auto-record works when motion auto-record is off.
- Persistent auto-record notification remains visible while waiting for configured vehicle Bluetooth or driving motion.
- Bluetooth connect/disconnect with a configured paired vehicle device.
- Motion enter/exit with Bluetooth disabled.
- Bluetooth and motion enabled together without duplicate starts or incorrect source stops.
- App swiped away while auto-record is enabled, then configured vehicle Bluetooth connect/disconnect.
- Device reboot/package update with auto-record enabled.
- Duplicate Bluetooth connect/disconnect events.
- App backgrounding while a trip is active.
- Process death or service restart while a trip is active.
- Device Location off while starting manual and automatic tracking.
- PDF save/share and CSV save/share.
- Trip review filters and quick category review.
- Work-hours categorization.
- Vehicles, odometer readings, and per-category custom rates.
- Pick place on map, save place rule, then verify trip category matching still works.
- Dark mode, large font, rotation, and small-screen layout.

## Closed Test

- If using a personal Play Console account created after 2023-11-13, run a closed test with at least 12 opted-in testers for 14 continuous days before requesting production access.
- Collect tester feedback on permissions, trip accuracy, export, Bluetooth behavior, and motion auto-recording.
- Fix Play pre-launch report crashes, ANRs, and policy warnings before production rollout.
