# Jacqueb Messages release process

- Official ID: `com.jacqueb.messages`. Never change it after v0.10.0.
- Development APK package: `com.jacqueb.messages.dev`; only official release-signed production APKs can update the real app.
- Increase `VERSION_CODE` for every release and set `VERSION_NAME` to a plain semantic version. The Android About screen uses `BuildConfig.VERSION_NAME`.
- Tags: `v0.10.1` for Stable, `v0.10.2-beta.1` for Beta, `v0.10.3-alpha.1` for Alpha. Beta users also get stable; Alpha users get every channel. A release in Stable must be published without an alpha/beta suffix.
- Release APK filename: `Jacqueb-Messages-v<VERSION>-release.apk`. The updater matches this naming and only trusts official GitHub repository release URLs.
- All future APKs MUST be signed using the same private signing certificate as v0.10.0. Never regenerate the keystore for a future update. Signing materials live outside the Git repository. Keep secure offline backups.
- Build: `./gradlew :app:assembleFossRelease`. Verify package ID, versionCode, versionName, and certificate with Android SDK aapt/apksigner before upload.
- Attach the exact APK to the GitHub Release and write *detailed* release notes for every version. The in-app update screen displays the full notes for every version between current and target. No backend or secret GitHub token is required to check public releases.
- Android requires user approval for application installation. The app checks for updates automatically if enabled, but installation is never silent.
- The final legacy bridge is tag v0.9.20. Users upgrade that legacy app, export Preferences, install the new app and restore. Do not remove the legacy bridge release.

User-facing launcher and About label: `Messages`. The GitHub repository, project attribution and APK artifact naming remain `Jacqueb Messages`.
