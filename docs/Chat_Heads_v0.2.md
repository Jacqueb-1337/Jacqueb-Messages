# Floating chat heads — second implementation checkpoint

Date: October 28, 2026

## Build

Android `assembleFossDebug` passes with the new overlay implementation. APK has independent app ID `me.jacqueb.messages.debug` and is not a system/OEM chat-bubble API implementation. No physical phone or emulator currently connected via ADB.

## Changes from the first prototype

- Added user-controlled **Automatically show heads for new messages** switch, off by default, separate from enabling overlay permission.
- SMS and MMS receivers now optionally dispatch accepted incoming messages to the chat-head foreground service. The normal notification is always preserved. Background-start restrictions are caught without interfering with SMS/MMS receiver success.
- The floating conversation uses an indexed, bounded Room query for 80 recent non-recycled, non-scheduled messages, including MMS metadata and attachment thumbnails. It falls back to SMS provider if local cache is empty.
- Group MMS threads disable direct single-number sending in the overlay; send/open directs to the full multi-recipient thread instead.
- Drag velocity now uses **screen-space MotionEvent coordinates**. An overlay moving with the pointer otherwise causes local velocity tracking to report near-zero or incorrect velocities and destroys the sling gesture.
- Contact avatars are asynchronously retrieved from the Contacts provider when READ_CONTACTS is granted; letter circles remain as fallback.
- Head position spring uses recovered `(T=40,F=7)` with recovered 0.3 rest thresholds. Attraction spring uses 0.005 thresholds, and on-down touch pressure scale is 0.91, returning to 1.0 on move/up.
- No proprietary Messenger code or graphics are redistributed. Underlying SMS backend remains the existing Fossify derivative subject to GPLv3.

## Known gaps before Messenger-like parity

- The original fling-speed threshold is recovered: resource `0x7f1a0069` = **500dp**, converted with `getDimensionPixelSize`. The service now compares velocity against `dp(500)` px/sec.
- Full drag and dismiss choreography, spring-driven close-target travel, automatic head ordering/stack overlap, and multi-head physics require controlled on-device comparison.
- Keyboard IME resize and focus behaviors require real Android 16 testing.
- Media editor, MMS compose, multi-SIM selection and group-message sending remain in the full thread, not yet integrated into the overlay.
- Automatic background heads may be suppressed on Android builds that restrict foreground-service starts. A normal notification remains, but there is no guarantee of unattended popup on every OEM/OS configuration.
- A debug build proves code compiles, not SMS/MMS delivery, overlay permission success, notification timing, photo rendering or battery performance.

## Manual acceptance tests

1. Install debug APK alongside the existing SMS app; **do not designate it as default before a message backup**.
2. Before allowing any actual SMS/MMS sending, verify that settings do not enable automatic heads by default.
3. Temporarily set as default SMS app only when safe to test; grant overlay and optionally Contacts permissions, open one test thread, collapse/expand and drag to either edge.
4. Throw the head toward the close target from high and low positions; check activation/dismiss and screen-edge motion in both portrait and landscape.
5. Enable auto incoming heads and have a trusted test sender send an SMS and MMS; verify normal notification remains and overlays appear only when permitted.
6. Test keyboard, photo thumbnails, group MMS, back button, app rotation, screen lock/unlock, battery optimization and service restart.
7. Return to prior default SMS app if not yet production ready.

## Other approved app features

See `Approved_Feature_Scope.md` for all saved user-approved QoL features, opt-in downloaded tiny local semantic-search model, and single-file Google Drive encrypted backup plans. None of those services/models are bundled yet.