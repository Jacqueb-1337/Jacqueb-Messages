# Messenger-style chat heads v0.7 — on-device feedback fixes

Date: 2026-10-28

This release responds to the user's screenshots comparing the independent SMS/MMS overlay against Meta Messenger, and the report of a normal heads-up banner appearing simultaneously with the floating chat preview.

## Changes

- **One stable head window across expanded/collapsed states:** The original prototype maintained a 79dp `headView` and a separate expanded `expandedStrip`, then removed the latter to reveal the former. This led to a visible blink, dead interval, and reversed layer ordering during closing. The two fields now refer to the **same** `FrameLayout`/WindowManager view while expanded; the animation resizes and repositions that existing window and its existing individual avatar children. The expanded conversation panel remains separate and focusable.
- **Consistent newest-on-top z-order:** Avatar views are retained by thread ID. They are parented oldest-first and newest-last, with newest last to draw on top. Collapsing changes only translations, never destroys/creates the stack.
- **Collapsed geometry:** Heads overlap horizontally, with a 5dp step and no per-head vertical stair-step. This corresponds to Messenger `RaE.BHS`/linked `RaE.Dpw` where the trailing offset is horizontal and vertical = 0; stack side determines the sign.
- **Linked trailing spring physics:** During dragging, follower heads maintain world-coordinate positions through `MessengerSpring(220,15)`, tracking the preceding head's movement and springing behind it. Spring integration retains the same RK4/1ms stepping that was independently recovered earlier, though precise movement choreography and collision behavior are not device-verified.
- **2D fling/snap:** Carries real screen-coordinate X/Y velocities into `MessengerSpring(40,7)`; Y dissipates through spring damping instead of freezing on touch-up. X permits a modest overshoot beyond the snap edge before returning. The exact choice of Y target/overshoot clamp is original tuning, not asserted identical to Messenger.
- **Improved expanded panel:** Smaller gap under the head strip and a triangular arrow/notch pointing to the selected head, similar to Messenger's selected conversation marker; the panel remains an original interface rather than a copied proprietary drawable.
- **Unread badge:** `badgeView` displays the number of unique currently unread SMS/MMS **conversations with an open head** (not the number of heads). Reads the Telephony provider and observes its changes while the service is alive. Opening that specific conversation marks it read, but opening the inbox alone doesn't.
- **Outgoing messages while head open:** The live SMS provider is now authoritative for recent SMS instead of waiting for the Fossify Room cache to synchronize after opening the full app. Room is still used for MMS attachment previews; combined rows are time-ordered, limited to 80. This fixes the missing sent-message symptom.
- **Heads-up notification:** On-device notification channel inspection showed the old `message_chatheads_no_heads_up_v1` channel had `IMPORTANCE_DEFAULT (3)` and Android still displayed a heads-up banner. A *new* `message_chatheads_no_banner_v2` channel uses `IMPORTANCE_LOW (2)` and skips shortcut promotion when chat-head delivery succeeds; shade and lock-screen notifications are retained. The app plays the channel's chosen notification sound and haptic separately, respecting channel mute, app-wide notification disable, ringer mode, and Do Not Disturb. Normal high-priority notifications remain as a fallback when the overlay isn't running.

## Important test limits

Compiled successfully for FossDebug on Windows, but v0.7 has not been installed or animation-tested on the phone in this development session. The Messenger APK is obfuscated; geometry, spring presets and some haptic triggers are REA verified, but not every animation's end condition and system interaction has been proven identical. The new manual notification alert also needs verification against user-customized channel sound, vibration and DND settings on the actual ROM.

Test: open three heads, expand then collapse, drag and release with high X/Y velocity, ensure the newest stays in front throughout, and confirm badge equals unread conversations rather than number of heads. Test sent messages appearing while full Messages app remains closed. Verify notifications are available in shade/lockscreen and audible/vibrate as configured but do not display heads-up while the head appears.
