# Samsung Messages-inspired UI update — v0.9

2026-10-28

## What changed

Replaced substantially Fossify-styled **inbox**, **conversation rows**, **message bubbles**, **conversation composer**, and **attachment grid** in the GPL-derived independent Android SMS app, while retaining its mature SMS/MMS transport.

- Added original Samsung-inspired day/night palette (separate light and dark colors), rounded conversation-list card, subtly divided rows, larger avatar presentation, and more generous Samsung-style inbox spacing.
- Reworked existing ItemConversation XML (keeping the original generated ViewBinding IDs, selection behavior, drag/reorder, timestamp/preview, and read/unread state).
- Samsung resource-based typographic hierarchy: 16sp contact names, 13sp previews and timestamps; blue unread-count circles. Existing adapter continues to render real contacts, dates and unread counts.
- Added bottom Conversations + Contacts navigation; **Contacts opens the existing contact chooser/new-message screen**, not a separate in-place Contacts tab yet. Incoming/archived/scheduled conversation behavior unchanged.
- Adjusted compose FAB placement above bottom navigation with Samsung-teal background; existing new-conversation action preserved.
- Updated ThreadActivity layout/palette, received/sent bubble shapes (16dp radius with small last corner), received/sent day/night colors, 15sp message body, 36dp contact avatar, 20dp horizontal gutter.
- Rounded gray/charcoal message input with explicit theme-based text/hint colors and 52dp minimum height; teal circular Send control with white paper-plane icon; original sending, SMS/MMS selection and scheduled-message behavior preserved.
- Changed the existing eight-action attachment drawer's visual palette, spacing and label typography while preserving all original actions (photo/video/camera/audio/document/contact/schedule).
- Kept the previous independent v0.8 legacy Messenger chat-head implementation; no change to physics or message-notification behavior in this UI-focused build.
- Changes use independent XML/drawables. **No proprietary Samsung Java code, proprietary app artwork, or fonts were reused.** Fossify GPLv3-derived source licensing obligations remain.

## Important limitations

This is the first substantial visual parity pass, **not** proof of pixel-perfect Samsung Messages. The reference screenshots include additional behavior requiring future work: an actual in-place Contacts tab, a full Samsung-style compose drawer containing Quick Response/Location/Calendar, more precise typography/spacing in date headers and message metadata, refined interactions/animations, per-device system colors, search and settings. See docs/Approved_Feature_Scope.md and docs/Feature_Gap_Audit_2026-10-28.md for the larger user-approved functionality.

Android resource linking and Kotlin compilation passed in the Android Gradle build; **not yet visually or behaviorally tested on the user's phone**. Verify: scrolling/selection in inbox, keyboard resize and send button, dark/light appearance, attachment picker, contact picker, and the unchanged chat-head feature. The package remains me.jacqueb.messages.debug for debug, with Android versionCode 29 so updating v0.8 should be possible without clearing data.
