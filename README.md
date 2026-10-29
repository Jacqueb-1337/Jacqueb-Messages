# Jacqueb Messages

**Jacqueb Messages** is an independent, open-source Android SMS/MMS messaging app built from [Fossify Messages](https://github.com/FossifyOrg/Messages), with customized themes, floating chat heads, conversation animations, and persistent drafts shared between chat heads and the main conversation screen.

## Features

- SMS and MMS conversations, groups, search, and contact integration
- Floating chat heads with expanded conversation panels
- Conversation customization, theme editing, and saved theme presets
- Per-thread notification sound and vibration previews
- SMS/MMS download and retry handling for pending carrier messages
- Persistent unsent text drafts shared between the full app and chat heads
- Fluid conversation transitions and keyboard animations

## Install

Check [Releases](../../releases) for the latest APK. Developer preview APKs are **debug-signed**, not production-signed or Play Store releases. Android may require permission to install an APK from your chosen file manager or browser. Only use official project release assets.

**Important:** SMS/MMS functionality requires Android SMS permissions and selection as the default messaging app. Carrier-dependent MMS downloads and some system overlay behavior may vary by device and Android version. Back up your messages before using a preview build as your daily messenger.

## Build from source

Requirements: Android SDK, JDK, and the Gradle wrapper included in this repository.

```bash
./gradlew :app:assembleFossDebug
```

On Windows, use `gradlew.bat`. The APK is produced under `app/build/outputs/apk/foss/debug/`. `local.properties`, local signing keys, credentials, build outputs and editor settings are intentionally excluded from source control.

## Status

This is a development preview, not a stable production release. Features have been tested on Android emulator and some real devices, but Android ROM behavior, carrier MMS and notifications need broader testing. Report bugs with reproduction steps, Android version and relevant sanitized logs. Never share SMS content, contacts, credentials or authentication tokens in bug reports.

## Credits and licensing

This project is a modified independent fork of **Fossify Messages**. It is not endorsed by or affiliated with the Fossify organization. The original copyright notices remain with their respective holders.

The source remains licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE). Any redistribution of modified builds must follow the license terms.
