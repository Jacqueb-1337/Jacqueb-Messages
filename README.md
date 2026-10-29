# Jacqueb Messages

**Messages** is an independent, open-source Android SMS/MMS messaging app maintained as the **Jacqueb Messages** project built from [Fossify Messages](https://github.com/FossifyOrg/Messages), with customized themes, floating chat heads, conversation animations, and persistent drafts shared between chat heads and the main conversation screen.

## Features

- SMS and MMS conversations, groups, search, and contact integration
- Floating chat heads with expanded conversation panels
- Conversation customization, theme editing, and saved theme presets
- Per-thread notification sound and vibration previews
- SMS/MMS download and retry handling for pending carrier messages
- Persistent unsent text drafts shared between the full app and chat heads
- Fluid conversation transitions and keyboard animations

## Install

Check [Releases](../../releases) for the latest APK. Official `com.jacqueb.messages` APKs beginning with v0.10.0 are signed with the permanent Jacqueb Messages release key. Development APKs use a separate `.dev` package and cannot update official installations. Android may require permission to install an APK from your chosen file manager or browser. Only use official project release assets.

**Important:** SMS/MMS functionality requires Android SMS permissions and selection as the default messaging app. Carrier-dependent MMS downloads and some system overlay behavior may vary by device and Android version. Back up your messages before using a preview build as your daily messenger.

## Build from source

Requirements: Android SDK, JDK, and the Gradle wrapper included in this repository.

```bash
./gradlew :app:assembleFossDebug
```

This creates the separate `com.jacqueb.messages.dev` development build. Production builds require the **same privately stored release keystore** on every release and use `:app:assembleFossRelease`. The private signing files are never committed or shared.

On Windows, use `gradlew.bat`. The APK is produced under `app/build/outputs/apk/foss/debug/`. `local.properties`, local signing keys, credentials, build outputs and editor settings are intentionally excluded from source control.

## Status

This is a development preview, not a stable production release. Features have been tested on Android emulator and some real devices, but Android ROM behavior, carrier MMS and notifications need broader testing. Report bugs with reproduction steps, Android version and relevant sanitized logs. Never share SMS content, contacts, credentials or authentication tokens in bug reports.

## Credits and licensing

This project is a modified independent fork of **Fossify Messages**. It is not endorsed by or affiliated with the Fossify organization. The original copyright notices remain with their respective holders.

The source remains licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE). Any redistribution of modified builds must follow the license terms.

## Moving from the old app

The final legacy-package build is **v0.9.20**, with Android ID `me.jacqueb.messages.debug`. Upgrade the old app to v0.9.20, then open Settings → Export and choose **Preferences**, saving the JSON somewhere you can find it. You may also export your SMS/MMS separately. Install the new `com.jacqueb.messages` APK, then use Settings → Import to restore your preferences. Existing Android SMS/MMS usually live in the system provider and can be displayed once the new app is made the default SMS app. Do not delete the old installation before confirming restoration. Custom audio file permissions may need to be selected again.

## Updates

Settings → About & Updates offers a Stable/Beta/Alpha update channel, automatic checking toggle, and manual Check for Updates. Update details show each applicable GitHub release between the installed and target version. Downloads have a progress bar and pause/resume; all APKs must match the installed package ID and signer, and Android asks before installing. Automatic checking does not silently install software.

Release format and signing procedures are documented in [docs/RELEASING.md](docs/RELEASING.md).

The launcher label and Android app name are **Messages**; **Jacqueb Messages** is the project/repository name.

## Safe (alpha)

Safe allows hiding chosen conversations from the Messages inbox and search, locking access with Android biometrics/screen lock or an app PIN/pattern plus a mandatory separate backup method. It blocks screen captures and recent-app previews while its screens are open. Notifications can be disabled or limited to a generic alert. **Safe hides only the app interface:** carrier SMS/MMS remains in the shared Android Telephony provider and may be visible to other messaging apps with permission, carrier services or system backups. Safe credentials and private conversation membership are excluded from preferences exports. FIDO2 hardware security keys and cloud-backed passkey sync are not included in this alpha.
