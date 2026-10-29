# v0.9.9 - Theme Studio parity, group chatheads and conversation customization

- Theme Studio inbox and conversation previews render a full-size viewport. Both use the surface / inset rounded card / timestamp / icon / divider palette; message list scrolls independently and the composer remains anchored, matching production ThreadActivity.
- Actual ThreadActivity now uses the same themed rounded card and inset as MainActivity instead of a separate black surface. Thread toolbar text/background, composer, outgoing/incoming bubbles and selected rows now follow the shared theme.
- MainActivity re-applies all FAB styling (color/icon, corners, size, X/Y position) after resume/focus, preventing restored host Material state from replacing custom theme values.
- Row selection appearance applies to read and unread rows, and resets when recycled.
- Themed default avatar/group icon bitmaps and corner radii apply to production inbox rows; real contact photos and per-conversation picture overrides take precedence.
- The conversation-details screen includes local conversation picture, sound, vibration pattern and enable/disable controls, accessible by tapping the thread title and the profile-icon popup. Group and individual threads both supported.
- Notification large icon and per-conversation channels now use these overrides, with a custom vibration playback path for suppressed heads-up notifications.
- Floating group-thread composer resolves all participants and uses the normal MMS-aware messaging pipeline instead of disabling group replies. Actual multi-recipient carrier-send remains untested without a cellular test phone.
- Floating heads prefer per-thread pictures, use shared avatar styling and dock slightly cropped at the side for a Messenger-style appearance.
- Android does not provide guaranteed third-party ordering beneath Messenger overlays. This version does not claim such an ordering guarantee.
- QA caveats: the Theme Studio preview still uses sample data rather than the live message database. Real-device verification of theme controls and MMS transport recommended.
## Emulator acceptance checks
- Android 16 emulator: changed Theme Studio FAB from teal to #FFA20405 and verified live inbox shows the exact red on exiting editor and after a full app process restart.
- The legacy custom FAB reported red via Android backgroundTintList but kept drawing teal. Replaced it with an AppCompatImageButton using the shared themed drawable for background, radius, size and icon color.
- Inspected inbox and conversation Theme Studio preview screens after repairing UTF-8 mojibake and button contrast. Full-sized inset rounded card appears in both.
- Per-conversation notification customization and group MMS send paths compile, but cellular delivery and real-device vibration/sound effects remain unverified.
