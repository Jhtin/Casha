# Developing Casha

Current app: **Casha 2.12.1 (Build 38)**. Android package and namespace: `com.example.casha`. Gradle project: `Casha`. Minimum API 26, target API 36, compile platform 37. The Gradle daemon is pinned to JDK 25. Tool versions are pinned in `gradle/libs.versions.toml` and the Gradle wrapper properties.

## Configuration and signing

Use the example Firebase configuration only for local UI builds and CI. For working cloud authentication and synchronization, supply your own ignored `app/google-services.json`. Do not connect automated tests to a personal account or production database.

If no `app/debug.keystore` exists, Gradle uses the ordinary Android debug key. Supplying a private local debug keystore preserves the existing personal signing configuration. No signing material is included here. Do not uninstall or clear a personal installation to resolve a signature mismatch; use an isolated emulator for this public source build.

Normal release builds are unsigned. To opt into production signing, set `CASHA_RELEASE_STORE_FILE`, `CASHA_RELEASE_STORE_PASSWORD`, `CASHA_RELEASE_KEY_ALIAS`, and `CASHA_RELEASE_KEY_PASSWORD` in your environment and pass `-PenableProductionSigning=true`. Store paths are resolved relative to the app module unless absolute. Never commit signing credentials or generated distribution files.

## Checks

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --max-workers=2
```

Run Android UI tests only on a dedicated emulator. Some instrumented workflows create or modify test records. The configuration test checks the supplied Firebase project's initialization; passing with synthetic identifiers does not establish live OAuth or cloud functionality.

```sh
./gradlew connectedDebugAndroidTest --max-workers=2
```

For Firestore rules tests, install Node.js 24.12+ and JDK 21+, then run `npm ci` followed by `npm test` in `firebase/`. The tests run against local emulators using `demo-casha` and never deploy rules.

The optional emulator-backed Android integration runner is Windows-specific and requires `ANDROID_SERIAL=emulator-...`. Build and install the debug app and Android test APK into that emulator before running:

```sh
npx firebase emulators:exec --only firestore,auth --project demo-casha "node run-android-tests.mjs"
```

Run that command inside `firebase/`. The runner rejects physical-device serials. `CASHA_TEST_CLASSES` optionally limits the instrumented test classes.

## Showcase assets

`docs/images/` includes the approved app logo, generated promotional mockups, and a saved demo calendar capture. Marketing artwork must remain described as mockups rather than exact screenshots. Use sample records for new screenshots; exclude real financial records, account identifiers, API keys, device identifiers, and notifications.
