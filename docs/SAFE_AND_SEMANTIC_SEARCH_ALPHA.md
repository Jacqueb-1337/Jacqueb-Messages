# Safe vault and optional semantic search

Status: **development branch only; not approved for stable release.**

## Safe vault

- SMS-only conversations can be explicitly migrated from Android's shared SMS provider to an app-private AES-256-GCM archive backed by Android Keystore.
- Migration verifies the encrypted archive before removing provider records. On success, it also deletes unencrypted Room conversation/message cache entries.
- Safe's private viewer reads encrypted archive without restoring provider records.
- Restore re-inserts messages and retains the archive until verification.
- Incoming SMS from an archived sender is routed to the vault by this app's default-SMS receiver; on failure it falls back to normal delivery.
- MMS, group MMS, and attachments cannot yet be migrated safely.
- Safe archives cannot survive Android Keystore loss, app data deletion, or reinstall. Restore before changing/wiping phones.
- Storage isolation is not end-to-end encryption. System/carrier and other privileged delivery observers may still encounter incoming SMS. Existing third-party copies, notification history, and deleted database remnants require separate audit.
- Legacy UI-only Safe entries have an explicit opt-in migration action.

### Required privacy QA before stable release

1. Archive and restore synthetic SMS threads with timestamps, direction, read states, and SIM metadata.
2. Simulate failure after encryption, during provider deletion, and during restoration. Verify no lost/duplicated messages and archive recovery.
3. Receive SMS during archive/restore, while locked, after reboot, and from international-format numbers.
4. Check shared provider, app cache, notifications, chat heads, backups, screenshots, and semantic vectors for sensitive traces.
5. Confirm MMS migration fails closed without deleting anything.
6. Run physical-device tests using backed-up dummy SMS, never irreplaceable personal messages.

## Semantic search

- Entirely optional. Keyword search always works without a model.
- Prompt on first and later searches until enabled or dismissed; choosing Not now postpones until the next search session.
- Download and choose Fast (MiniLM L3) or Accurate (MiniLM L6), with model SHA-256 verification and removal controls.
- All inference and message vector indexing run locally. No message or query data is sent to the download source.
- WordPiece tokenization, warm CPU ONNX session, incremental WorkManager indexing, private SQLite quantized embeddings.
- Excludes Safe, scheduled, and recycled messages; exact keyword matches appear ahead of semantic results.
- Disabling clears vectors; downloaded model can be deleted separately.

### Verified and pending

- Foss debug build compiles and installs on an Android emulator.
- Theme Studio color popover Reset and system navigation insets inspected.
- Both Fast and Accurate engines downloaded with SHA-256 verification and separately indexed 37 emulator test messages. A live search returned semantic candidates.
- Real-device ranking quality, speed on older phones, download interruption/retry, Safe migration recovery, and the large ONNX Runtime APK footprint still need validation and optimization.
- Do not publish a stable release based solely on these checks.
