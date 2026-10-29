# Android Messages v0.4 — field-test corrections (2026-10-28)

This release addresses feedback from screenshots and an incoming scheduled self-SMS field test of v0.3. The user confirmed that the app **was the default SMS/MMS handler during their test**, and the disabled-looking Chat Heads settings screenshot was taken **before** they enabled chat heads. Those were not confirmed causes of the failure.

## Fixes

- **Incorrect thread dates/order:** the telephony Threads table's denormalized date, and a draft date, can differ from the most recent actual message. Conversation synchronization now computes latest non-draft SMS and MMS dates directly from their corresponding providers, normalizes seconds/milliseconds, and uses the resulting actual date for ordering/display.
- **Unread badge temporarily disappears when inbox opens:** `Conversation.areContentsTheSame` omitted the `read` field, so the cache reconciliation could skip read state changes. This comparison now includes `read`. Actual unread SMS/MMS counts override a stale Threads.READ bit, and only an explicit thread-opening or Mark Read action should mark provider messages read. The full on-device reproduction is pending.
- **Dark-theme settings:** ChatHeadsSettingsActivity is rebuilt on the same native SimpleActivity/theme and shared proper app colors, with readable dark mode, standard typography, themed cards/switches and status/nav contrast. Conversation list shows contact titles from the same source as inbox; false hard gate based solely on `getDefaultSmsPackage` was removed.
- **Automatic incoming heads:** AUTO_SHOW is true by default when enabling the master chat-head feature but remains independently disableable. To avoid Android 15/16 cold background FGS start blocks, opening settings with auto heads enabled proactively starts and keeps a low-priority **foreground service notification** armed even when no head is visible. Incoming SMS/MMS events dispatch directly to an already active service. If Android kills the service and blocks cold start, the ordinary notification remains and can be tapped to open the head. Logged failures are diagnostic only; do not log message content.
- **Long press:** `Open Chat Head` is added to both conversation-row and individual message contextual action menus. Opens the selected thread via a user-initiated Activity, which requests overlay permission if missing.
- **Close-all handling:** removing heads clears the overlays but leaves user-opted-in incoming monitoring armed. Disabling the master toggle stops the foreground service.

## Limits and testing

- `assembleFossDebug` compiled successfully. No USB/ADB device was connected through the PC and no automatic on-device end-to-end test was performed.
- Android may still restrict unattended background starts if the foreground service is stopped by the OS; this implementation improves reliability but cannot promise every incoming message will create an overlay under every OS/OEM setting.
- The resident foreground service shows an ongoing low-priority notification while automatic incoming heads are enabled. Disabling automatic heads or the feature stops new incoming overlay handling.
- As a third-party default SMS replacement, features must be thoroughly tested before use as the sole SMS app. Back up messages before switching apps.
- GPLv3 Fossify-derived backend is retained; code here is an independent chat-head implementation.
