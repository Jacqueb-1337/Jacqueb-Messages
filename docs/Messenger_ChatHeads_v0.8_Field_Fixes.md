# Messages v0.8 — overlay navigation, input and collapse fixes

2026-10-28 · Android debug build versionCode 28.

## Reported on-device problems (v0.7)
1. A legacy Fossify "fake version" warning appeared during normal messaging in this explicitly rebranded app.
2. Selecting **Open Chat Head** from a long-pressed message/conversation opened the chat-head Settings Activity unnecessarily, often leaving it visible afterward.
3. Collapsing the chat-head strip moved the heads unnaturally far left in the final frame, then suddenly docked them; the collapsed head sometimes stopped responding to taps.
4. The settings header sat beneath the status bar on the edge-to-edge display.

## Changes
- Removed the original Fossify-specific `checkAppSideloading()` call from the independently branded fork's MainActivity. This check was designed for Fossify-branded binaries and incorrectly accused an authorized GPL-derived fork of masquerading as Fossify. This is **not** a claim of official Fossify endorsement; the GPL attribution/source obligations remain in force. No SMS verification, security permissions, or signature enforcement was otherwise bypassed.
- `ChatHeadsSettingsActivity.openThread()` now starts `FloatingChatHeadsService.ACTION_ADD` directly when overlay permission is already granted, and moves the current app task behind the floating head. Settings remains a permission/setup fallback only. Explicit opening of another head while a conversation is already expanded selects that head.
- The fallback settings screen is dismissed after opening its pending head and its root receives status/navigation-bar insets so the title does not render under the Android clock.
- Collapsed avatars explicitly accept touch events and forward them to the existing drag/tap handler; expanded avatars retain their individual selection click listeners.
- `animateCollapseInPlace()` replaces the old closing path that resized the expanded WindowManager window from hundreds of dp to 79dp only after animation. The replacement animates **window width continuously** with the real spring and each avatar's **absolute screen coordinate** with its own linked spring. Moving the window origin and local child translation in one frame avoids the visible ~200dp horizontal jump. No additional head views are constructed or swapped, and collapse completes once the springs settle instead of waiting for a fixed 540ms cap.
- Existing dark composer, unread count, no-heads-up channel, and live SMS provider refresh remain from v0.7.

## Testing required
Compilation is a **source-level** validation only. Validate on the user's actual OnePlus 12R/crDroid: long-press Open Chat Head without Settings, then expand/collapse repeatedly; tap the collapsed head immediately after motion finishes; drag/throw and check persistent view. Also verify status-bar padding and absence of the outdated Fossify check. No blanket claim of pixel-perfect Messenger parity. This is a development build; maintain backups before switching the phone's default SMS app.
