# Alpha 13 handoff

Version: 0.11.0-alpha.13, code 67. Branch: feature/encrypted-safe-and-keyboard-recoil.

# Safe receipt recovery and private conversation fixes

- Fixed encrypted SMS incorrectly showing Failed after successful sending. Android detaches the broadcast result when goAsync is called; the result is now captured first. Existing affected messages show Status unavailable because their original outcome cannot be recovered reliably. Actual new sending failures still show Failed.
- Opening an unlocked Safe clears its private-message notification and launcher badge without requiring a notification tap. Viewing an encrypted conversation marks only the displayed snapshot read; later arrivals remain unread. Unrelated notifications remain intact.
- Chat heads, normal conversations and Safe now share keyboard rules: sentence capitalization, normal text suggestions, multiline input, and the configured Send on Enter behavior.
- Safe now uses the same themed send control as the main conversation screen.
- Tap a private image to open a full-screen viewer with pinch zoom, pan and double-tap zoom. Decrypted images stay in memory, without disk caching or external viewer exports; the viewer closes with the private screen.
- Incoming SMS and MMS finish normal reception first. A separate background job encrypts and verifies the archive before removing the exact shared-database records. Failed encryption or deletion retains the original or verified recovery copy and retries automatically, including while Safe is locked.
- Every message in an encrypted Safe conversation now has a storage indicator: closed lock means encrypted and no matching shared-database copy remains; open lock means an unencrypted/pending copy remains, or removal cannot be verified.
- Recovery uses exponential backoff, startup recovery and periodic checks. A new receipt requests a fresh attempt. Android controls scheduling, so retry timing is not exact. There is a brief pending period in the SMS database after normal reception.
- Conversations placed in Safe without encryption also show an open lock on every message.
- Background migration leaves queued sends, provider drafts and scheduled app jobs intact. It does not interrupt receiving or compress stored attachments.

Validation: Android emulator regression coverage includes real success/failure PendingIntent callbacks, multipart status, encrypted write/deletion interruption, locked incoming recovery, pending lock accuracy, protected queued sends/drafts, authenticated image viewing and the themed send button. No real carrier messages were sent during testing.

Implementation notes:
- SafeSmsSentReceiver captures resultCode before goAsync; new private sends have send_result_version 2. Old completed failed statuses are displayed as unknown rather than rewritten as successful.
- SmsReceiver and MmsReceiver finish their existing persistence/cache/notification work, record receipt completion, then schedule independent SafeTransferWorker jobs. Scheduler failures are caught. Encryption failure cannot prevent original persistence.
- Workers serialize archive changes through SafeVault. Exact provider rows and MMS bytes are verified before deletion. A partial capture keeps phase capturing, enabling idempotent retries. Queued SMS, unfinished MMS and drafts remain in the provider. Scheduled Room jobs are not scrubbed.
- Receipt tracking never takes the vault mutation lock. Fresh provider rows require completion metadata; crash recovery has a five-minute grace from first observation, independent of sender timestamps. Active receiving defers transfer rather than waiting for encryption.
- WorkManager has exponential backoff starting at 30 seconds and a 15-minute periodic recovery job, plus startup and new-receipt attempts. Receipt preferences contain row identifiers/times only, not message content or recipients.
- readVisible reconciles authenticated records against provider rows and conservatively uses an open lock if verification fails. Transient preview provider URIs are never persisted in the archive.
- SafeImageViewer uses FLAG_SECURE, in-memory Glide loading with disk/memory cache disabled, bounded pinch/pan and double-tap zoom. Parent onStop dismisses it and clears private UI.

Final emulator checks passed: composer rules, private send callbacks, encrypted SMS/MMS capture and interrupted restore, incoming transfer failure/retry and truthful locks, private badge clearing/read acknowledgement, secure image expansion, themed send control, main keyboard physics, overlay IME geometry and cached chat-head reopening. Final MMS suite also passed compression/cancel, original retention and actual retry-label interaction.

The emulator intermittently failed to show the overlay IME during instrumentation startup; a fresh run passed with 10 opening and 13 closing frames and the bottom tracking within 3 pixels. This is recorded rather than hidden. No geometry source was changed in this release.

Release build passed with version code 67. Delivery verification is recorded below after publication.

Safe aliases contain provider/archive IDs only and stop matching when the archive is removed. SMS/MMS resolve membership after persistence, before alerts; failures preserve normal receipt. Regression checks cover association, restored-alias expiry and open-lock rendering on unencrypted Safe rows.

## Delivery confirmed

- Published prerelease: https://github.com/Jacqueb-1337/Jacqueb-Messages/releases/tag/v0.11.0-alpha.13
- Source/tag target: d9f621a2011705f077460388fcd3daabe6482208.
- APK: Jacqueb-Messages-v0.11.0-alpha.13-release.apk, 71,914,115 bytes.
- SHA-256: 02cd9c7bf3b3374ddc2b24b68df9143558b3b46c123b1396fc1fd99e80f94a5e. GitHub's asset digest and the actual phone download match.
- Original production signing certificate verified against alpha.12. Package com.jacqueb.messages, version 0.11.0-alpha.13, code 67.
- Signed production APK updated and launched successfully on the emulator; no AndroidRuntime error on launch. Emulator default SMS role restored to production and hardware-keyboard IME override reset.
- Exact published APK downloaded, verified and installed successfully on the physical phone. Package manager confirms version 67 / alpha.13. App data was preserved.
- No real carrier SMS/MMS were sent during verification.
