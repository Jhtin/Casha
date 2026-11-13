# Install the Casha public beta

Current download: **Casha 2.12.1, Build 38**. Requires Android 8.0 / API 26 or newer.

1. Open the [official release page](https://github.com/Jhtin/Casha/releases/tag/v2.12.1-beta.1) on your Android phone.
2. Download **Casha-v2.12.1-build38.apk** from the release assets. The source-code ZIP is for developers and cannot be installed as an app.
3. Open the APK and follow Android's installation prompts. Your phone may ask whether the browser or file manager may install apps from this source. Android or Play Protect may show additional prompts because this is a beta distributed outside Google Play.
4. Open **Casha**. Start with sample records while testing. Manual finance tools and local receipt recognition work without configuring Gemini. Optional Google sign-in and cloud synchronization use the app's Firebase project and require a network connection.
5. If you want Petot AI, enter your own Gemini API key in Settings and read the consent information. Charges and quotas depend on the provider account and key you use.

The APK includes service configuration but no user accounts, financial histories, API keys entered by users, or signing private keys. Source builds use a synthetic Firebase example until the developer supplies their own configuration.

## Existing installations

Android updates require the same application ID and signing certificate. This APK uses `com.example.casha` and the public release certificate. Earlier personal/debug builds use a different certificate and cannot be updated with this APK.

If Android reports an installation conflict, keep your current personal app and data. Use another device or an isolated emulator to try this public beta. Do not uninstall or clear the existing app to work around the conflict: unsynced records and local receipt images could be lost. JSON exports do not include receipt image files.

## Verify the download

The release includes **SHA256SUMS.txt**, containing the APK's SHA-256 checksum. Compare it with the file you downloaded if you want to check its integrity. Only download from the official repository's release assets.

Public release signing-certificate SHA-256:

```text
1A:22:2B:61:56:58:24:FE:C2:1D:EB:08:9D:06:BE:4E:AE:05:64:3F:78:18:59:64:0D:02:90:04:E0:3B:F8:AE
```

This is a beta for testing and feedback. It does not issue loans, move funds, or connect to your bank account. Review every proposed financial action, and keep backups of any records you choose to enter.
