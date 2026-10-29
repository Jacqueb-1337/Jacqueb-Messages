## v0.10.4 - Remove inherited false counterfeit-app warning

- Fixed the recurrent incorrect "You are using a fake version" dialog on our legitimate `com.jacqueb.messages` release.
- Root cause: Fossify Commons 7.1.3 checks its original `org.fossify.` package namespace and randomly warns on other namespaces, even if they are properly signed independent forks. The earlier launch-count workaround could not stop the random condition.
- Pinned our own reproducible Commons AAR variant, with a brand-specific adjustment limited to the three affected package-namespace checks. The remaining Commons implementation, dependencies and Android package/signing verification are unchanged.
- Removed the fragile launch-counter workaround; the app retains its normal app-run counters and existing settings.
- Added a verifiable patch-generation script and source attribution for the GPL-derived Commons library.

## v0.10.3 - Animated conversation pictures

- Per-conversation profile pictures now accept animated GIFs without converting them into static PNGs or losing frames and timing.
- Custom GIFs animate in conversation-list avatars, the full/expanded/docked chat-head avatars, and the picture preview in conversation settings.
- Static JPEG/PNG photos retain the established crop/resize workflow; switching between a GIF and a static photo removes the obsolete file.
- Animated images are retained in app-private storage so they continue to work after restarts. Files larger than 8 MB or GIF canvases over 2048 pixels are rejected to bound resource use.
- Preferences exports and imports now include original animated conversation avatars (backup size limit raised to 64 MB). Legacy PNG backups still import unchanged.
- Notification icons use a static first frame because Android does not support animated notification bitmaps.

## v0.10.2 - Conversation cutout and reliable update checks

- Opening and closing a main conversation now animates only a rounded clipping mask. Message contents, toolbar and composer remain at their original scale and position while the inbox underneath is revealed.
- Automatic update checking is scheduled by Android about every six hours even while the app is backgrounded, subject to Android battery/network scheduling.
- Successful checks, network errors and update availability are recorded and shown in Settings and the update center; a foreground automatic check also briefly displays its result.
- A new eligible version sends a system notification when notification permission is enabled, opening the full release-notes screen. Routine up-to-date checks do not generate system notifications.
- A failed attempt no longer starts the six-hour success cooldown; foreground attempts back off for twenty minutes after failures.
- Keeps the official Messages app name, com.jacqueb.messages package, and production signing key.

## v0.10.1 - Inbox and messaging defaults

- Inbox conversation rows place the Draft indicator to the right of the preview, instead of before the text.
- Draft text defaults to orange (#FF8A00), distinct from the normal theme accent.
- Theme Studio adds a Draft indicator text color control, accessible by tapping the Draft label in the inbox preview.
- The Draft indicator stays visible beside long, truncated message previews.
- New installs default to group MMS (replies stay in one group conversation), 2 MB MMS image resizing, and enabled Trash for deleted messages. Existing saved choices remain unchanged.
- Trash is now always accessible directly in the main three-dot menu.
- This release keeps the Messages app name, com.jacqueb.messages package ID, and existing production signing key.

## v0.10.0 - Messages (Jacqueb Messages project; new package)

- Permanent Android package ID `com.jacqueb.messages`, with clean Android version names.
- New full-screen update center with stable, beta, and alpha channels.
- Optional automatic update checking, manual Check for Updates, and visible release history.
- Download progress and pause/resume, APK package and signing verification, Android-confirmed installation.
- Automatic recognition and restore of v0.9.20 preferences exports.

## v0.9.20 - Migration bridge (legacy package)

- Final build for existing `me.jacqueb.messages.debug` users.
- Export dialog now chooses Messages or Preferences.
- Preferences export includes themes, conversation alerts, chat heads, shared drafts, and conversation photos, without security credentials.
- Import detects message arrays or Jacqueb preferences automatically and confirms before applying preferences.
- Normalized Android-visible version to 0.9.20.
- Important: export Preferences before installing the new `com.jacqueb.messages` app.

## v0.9.19 - Seamless conversation return

- Shortened main conversation exit transition from 290 ms to 170 ms.
- Kept the underlying inbox visible throughout return animation by limiting window transparency to the conversation Activity.
- Removed the opaque gray activity background exposed during the old shrink transition.

## v0.9.18 - Jacqueb Messages

- Renamed app to Jacqueb Messages and prepared independent public source repository.
- Includes the shared persistent drafts, keyboard cascade, system-bar layout, chat-head controls and MMS handling developed through v0.9.17.
- Development preview with debug-signed APK.

# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.10.2] - 2026-10-21
### Changed
- Updated translations

## [1.10.1] - 2026-10-10
### Changed
- Updated translations

### Fixed
- Fixed startup crash on Android 11 and below

## [1.10.0] - 2026-10-10
### Added
- Added inline audio player for MMS attachments ([#261])

### Changed
- Improved error messages when a message fails to send ([#315])
- Updated translations

### Fixed
- Fixed exposed action receivers for security ([#834])
- Fixed group message creation on Xiaomi devices ([#41])
- Fixed incorrect SMS/MMS deletion from notifications in rare cases
- Fixed MMS images stuck on "Sending" when sent together ([#185])
- Partially fixed MMS image sending failures ([#45])

## [1.9.1] - 2026-07-19
### Changed
- Updated translations

### Fixed
- Fixed missing private contact names in message list and threads ([#829])

## [1.9.0] - 2026-07-12
### Added
- Added a group message format choice on the first group send ([#52])

### Changed
- Updated translations

### Fixed
- Partially fixed issue with sending MMS images ([#45])
- Fixed slow loading of the conversation list ([#234])

## [1.8.1] - 2026-07-09
### Changed
- Updated translations

### Fixed
- Fixed messages being sent to the wrong contact ([#615])
- Fixed incomplete message exports ([#713])
- Fixed crash when viewing older messages
- Other stability improvements

## [1.8.0] - 2026-01-30
### Added
- Added support for custom fonts
- Added "Copy number to clipboard" option inside chat overflow menu ([#651])

### Changed
- Improved multi-message copy formatting with timestamps and sender names
- Updated translations

### Fixed
- Fixed missing notifications in some cases ([#159])
- Fixed incorrect blocking of MMS messages in some rare cases ([#644])
- Fixed issue with importing alphanumeric blocked numbers ([#282])
- Fixed issue where scheduled messages were not sent after a reboot or app updates ([#641])

## [1.7.0] - 2025-12-16
### Added
- Ability to select and copy multiple text messages at once ([#600])

### Changed
- Updated translations

### Deprecated
- Deprecated the recycle bin feature ([#290])

### Fixed
- Fixed new conversation shortcut ([#416])
- Fixed blocking MMS messages from unknown numbers ([#610])

## [1.6.0] - 2025-10-29
### Changed
- Compatibility updates for Android 15 & 16
- Calling now works directly without launching dialpad ([#562])
- Search bar is now pinned to the top when scrolling
- Updated translations

### Fixed
- Fixed freezing when sending messages ([#574])

## [1.5.0] - 2025-10-18
### Added
- Unread badge count for conversations ([#177])

### Changed
- Optimized loading messages in conversations ([#234])
- Updated conversation item design to be more compact ([#376])
- Pin/unpin actions now always show as action buttons in the menu ([#561])
- Updated translations

### Fixed
- Fixed position reset when opening attachments in conversations ([#82])
- Fixed automatic scroll to searched message in conversations ([#350])
- Fixed non-standard text and avatar sizes in list items
- Fixed "Mark as read" not working in some cases ([#264])

## [1.4.0] - 2025-10-12
### Added
- Ability to save multiple attachments ([#75])
- Ability to select numbers that aren't starred when starting a new conversation ([#153])

### Changed
- Reordered menu options throughout the app
- Updated translations

### Fixed
- Fixed keyword blocking for MMS messages ([#99])
- Fixed contact number selection when adding members to a group ([#456])
- Fixed a glitch in pattern lock after incorrect attempts
- Fixed disabled send button when sending images without text ([#165])

## [1.3.0] - 2025-09-09
### Added
- Option to keep conversations archived ([#334])

### Changed
- Updated translations

## [1.2.3] - 2025-08-21
### Changed
- Updated translations

### Fixed
- Fixed stale/missing notification badge on some devices

## [1.2.2] - 2025-08-01
### Changed
- Updated translations

### Fixed
- Fixed inability to view messages when there is no SIM card ([#461])

## [1.2.1] - 2025-06-17
### Changed
- Preference category labels now use sentence case
- Updated translations

## [1.2.0] - 2025-06-04
### Added
- Conversation shortcuts ([#209])

### Changed
- Updated translations

## [1.1.7] - 2025-04-01
### Changed
- Added more translations

### Fixed
- Fixed incorrect cursor position when reopening the app ([#349])
- Fixed scrolling issue on conversation details screen ([#359])

## [1.1.6] - 2025-03-24
### Changed
- Other minor fixes and improvements
- Added more translations

### Removed
- Removed storage permission requirement ([#309])

### Fixed
- Fixed crash when viewing messages
- Fixes incorrect author name in group messages ([#180])

## [1.1.5] - 2025-02-02
### Changed
- Added more translations

### Fixed
- Fixed issue with third party intents ([#294])
- Fixed toast error when receiving MMS messages ([#287])
- Fixed RTL layout issue in threads ([#279])

## [1.1.4] - 2025-01-23
### Changed
- Added more translations

### Fixed
- Fixed issue with forwarding messages ([#288])

## [1.1.3] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.2] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.1] - 2025-01-04
### Changed
- Improved third party SMS/MMS intent parsing ([#217], [#243])
- Modified short code check to exclude emails ([#115])
- Other minor bug fixes and improvements
- Added more translations

### Fixed
- Fixed issue with messages draft deletion ([#13])
- Fixed multiple toast errors for MMS messages ([#70], [#262])
- Fixed some layout issues in message thread ([#135])

## [1.1.0] - 2024-12-27
### Changed
- Replaced checkboxes with switches
- Improved app lock logic and interface
- Other minor bug fixes and improvements
- Added more translations

### Removed
- Removed support for Android 7 and older versions

### Fixed
- Fixed various issues related to importing/exporting messages
- Fixed keyword blocking for MMS messages
- Fixed issue with messages draft deletion

## [1.0.1] - 2024-02-09
### Changed
- Minor bug fixes and improvements
- Added some translations

## [1.0.0] - 2024-01-24
### Added
- Initial release

[#13]: https://github.com/FossifyOrg/Messages/issues/13
[#41]: https://github.com/FossifyOrg/Messages/issues/41
[#45]: https://github.com/FossifyOrg/Messages/issues/45
[#52]: https://github.com/FossifyOrg/Messages/issues/52
[#70]: https://github.com/FossifyOrg/Messages/issues/70
[#75]: https://github.com/FossifyOrg/Messages/issues/75
[#82]: https://github.com/FossifyOrg/Messages/issues/82
[#99]: https://github.com/FossifyOrg/Messages/issues/99
[#115]: https://github.com/FossifyOrg/Messages/issues/115
[#135]: https://github.com/FossifyOrg/Messages/issues/135
[#153]: https://github.com/FossifyOrg/Messages/issues/153
[#159]: https://github.com/FossifyOrg/Messages/issues/159
[#165]: https://github.com/FossifyOrg/Messages/issues/165
[#177]: https://github.com/FossifyOrg/Messages/issues/177
[#180]: https://github.com/FossifyOrg/Messages/issues/180
[#185]: https://github.com/FossifyOrg/Messages/issues/185
[#209]: https://github.com/FossifyOrg/Messages/issues/209
[#217]: https://github.com/FossifyOrg/Messages/issues/217
[#225]: https://github.com/FossifyOrg/Messages/issues/225
[#234]: https://github.com/FossifyOrg/Messages/issues/234
[#243]: https://github.com/FossifyOrg/Messages/issues/243
[#261]: https://github.com/FossifyOrg/Messages/issues/261
[#262]: https://github.com/FossifyOrg/Messages/issues/262
[#264]: https://github.com/FossifyOrg/Messages/issues/264
[#274]: https://github.com/FossifyOrg/Messages/issues/274
[#279]: https://github.com/FossifyOrg/Messages/issues/279
[#282]: https://github.com/FossifyOrg/Messages/issues/282
[#287]: https://github.com/FossifyOrg/Messages/issues/287
[#288]: https://github.com/FossifyOrg/Messages/issues/288
[#290]: https://github.com/FossifyOrg/Messages/issues/290
[#294]: https://github.com/FossifyOrg/Messages/issues/294
[#309]: https://github.com/FossifyOrg/Messages/issues/309
[#315]: https://github.com/FossifyOrg/Messages/issues/315
[#334]: https://github.com/FossifyOrg/Messages/issues/334
[#349]: https://github.com/FossifyOrg/Messages/issues/349
[#350]: https://github.com/FossifyOrg/Messages/issues/350
[#359]: https://github.com/FossifyOrg/Messages/issues/359
[#376]: https://github.com/FossifyOrg/Messages/issues/376
[#416]: https://github.com/FossifyOrg/Messages/issues/416
[#456]: https://github.com/FossifyOrg/Messages/issues/456
[#461]: https://github.com/FossifyOrg/Messages/issues/461
[#561]: https://github.com/FossifyOrg/Messages/issues/561
[#562]: https://github.com/FossifyOrg/Messages/issues/562
[#574]: https://github.com/FossifyOrg/Messages/issues/574
[#600]: https://github.com/FossifyOrg/Messages/issues/600
[#610]: https://github.com/FossifyOrg/Messages/issues/610
[#615]: https://github.com/FossifyOrg/Messages/issues/615
[#641]: https://github.com/FossifyOrg/Messages/issues/641
[#644]: https://github.com/FossifyOrg/Messages/issues/644
[#651]: https://github.com/FossifyOrg/Messages/issues/651
[#713]: https://github.com/FossifyOrg/Messages/issues/713
[#829]: https://github.com/FossifyOrg/Messages/issues/829
[#834]: https://github.com/FossifyOrg/Messages/issues/834

[Unreleased]: https://github.com/FossifyOrg/Messages/compare/1.10.2...HEAD
[1.10.2]: https://github.com/FossifyOrg/Messages/compare/1.10.1...1.10.2
[1.10.1]: https://github.com/FossifyOrg/Messages/compare/1.10.0...1.10.1
[1.10.0]: https://github.com/FossifyOrg/Messages/compare/1.9.1...1.10.0
[1.9.1]: https://github.com/FossifyOrg/Messages/compare/1.9.0...1.9.1
[1.9.0]: https://github.com/FossifyOrg/Messages/compare/1.8.1...1.9.0
[1.8.1]: https://github.com/FossifyOrg/Messages/compare/1.8.0...1.8.1
[1.8.0]: https://github.com/FossifyOrg/Messages/compare/1.7.0...1.8.0
[1.7.0]: https://github.com/FossifyOrg/Messages/compare/1.6.0...1.7.0
[1.6.0]: https://github.com/FossifyOrg/Messages/compare/1.5.0...1.6.0
[1.5.0]: https://github.com/FossifyOrg/Messages/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/FossifyOrg/Messages/compare/1.3.0...1.4.0
[1.3.0]: https://github.com/FossifyOrg/Messages/compare/1.2.3...1.3.0
[1.2.3]: https://github.com/FossifyOrg/Messages/compare/1.2.2...1.2.3
[1.2.2]: https://github.com/FossifyOrg/Messages/compare/1.2.1...1.2.2
[1.2.1]: https://github.com/FossifyOrg/Messages/compare/1.2.0...1.2.1
[1.2.0]: https://github.com/FossifyOrg/Messages/compare/1.1.7...1.2.0
[1.1.7]: https://github.com/FossifyOrg/Messages/compare/1.1.6...1.1.7
[1.1.6]: https://github.com/FossifyOrg/Messages/compare/1.1.5...1.1.6
[1.1.5]: https://github.com/FossifyOrg/Messages/compare/1.1.4...1.1.5
[1.1.4]: https://github.com/FossifyOrg/Messages/compare/1.1.3...1.1.4
[1.1.3]: https://github.com/FossifyOrg/Messages/compare/1.1.2...1.1.3
[1.1.2]: https://github.com/FossifyOrg/Messages/compare/1.1.1...1.1.2
[1.1.1]: https://github.com/FossifyOrg/Messages/compare/1.1.0...1.1.1
[1.1.0]: https://github.com/FossifyOrg/Messages/compare/1.0.1...1.1.0
[1.0.1]: https://github.com/FossifyOrg/Messages/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/FossifyOrg/Messages/releases/tag/1.0.0
