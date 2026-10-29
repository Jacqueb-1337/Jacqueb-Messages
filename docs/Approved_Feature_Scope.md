# Approved scope: Samsung Messages replacement

Updated October 2026 from the user’s explicit confirmation and messaging-app Brain entries.

## Product requirements

- Independent default SMS/MMS Android app, using Samsung Messages 16.1.10.54 as a UI/behavior reference, cleaner and customizable.
- Preserve Samsung Messages capabilities wherever Android/carrier APIs allow them.
- Reuse existing stable, license-compatible SMS/MMS transport rather than reinventing the telecom foundation.
- Add Facebook Messenger-style **overlaid chat heads**, independent of Android native bubbles, with faithful interactions and exact motion mathematics where verifiably obtainable. Optional user permission.
- No desktop/web texting at this stage.
- **Absolutely no AI features except semantic search**, using an opt-in, separately downloaded, small local model from Settings. No AI model bundled by default. The initial download requires explicit user action. No hosted processing of message contents or queries.
- Fast and battery-efficient semantic search on old devices as well as current devices. Ordinary keyword search always available.

## User-approved QoL features

These were previously only brainstorm items; the user explicitly approved **all** on October 27/28, 2026.

- Delayed/queued SMS send with **Undo Send** only while unsent, never falsely claim remote deletion.
- Reply-later reminders and read-later markings.
- Pin individual messages; custom ordering of pinned conversations.
- Advanced search with filters (contact, date, phrase, media, link, read/unread, category), plus optional semantic relevance.
- Select and copy portions of message text and multiple messages.
- Deterministic recognized actions (dates, maps, phone numbers, addresses) without AI.
- Custom per-chat colors, themes, bubble shapes, fonts, backgrounds, and animations.
- User-configurable swipe actions and touch gestures.
- Multiple inbox folders/categories; rule-driven organization, without AI.
- Time-based, conditional and recurring muting, per-thread notifications.
- Private/locked conversations, optional biometrics/PIN.
- Scheduled and recurring SMS; templates and quick responses.
- Rule-based workflows and non-AI automation.
- Encrypted backups and reliable partial/full restore.
- Drafts, media gallery, personal notes linked to threads.
- Rules/keyword spam filtering, blocked numbers/phrases, OTP lifecycle cleanup.
- Notification history where platform policy and permissions permit.
- Various Samsung parity features: conversation categories, trash/recycle bin, starred messages, blocked phrases, single/dual-SIM display, per-thread sounds, attachments, group MMS, message delivery indicators, web previews, accessibility, themes, dark mode, search, scheduled messages.
- Messenger overlay heads: collapse/expand, stack switching, drag/reposition, remembered dock position, spring edge snap, gravitational dismiss attraction, trajectory-based sling-to-close, bubble reveal/disappear, long-term performance.

## Backup design

Approved goal: local encrypted backups and auto-backup of a single file to Google Drive with Google authorization only, **no separate app user account**.

Implementation proposals from Brain to evaluate:
- Google Drive least-privilege OAuth drive.file scope and a single user-visible Messages.backup resource, overwritten by file ID rather than appending copies.
- WorkManager opportunistic daily Wi-Fi backup plus explicit Back Up Now.
- Compression and client-side authenticated encryption with a portable key/recovery mechanism; never depend exclusively on the old device's hardware-bound keystore for the backup decryption key.
- Store SMS/MMS text, media, folders, pinned metadata, themes, settings, and user-written notes as permitted, provide attachment exclusions and selective restore.
- Atomic/recoverable update with last-known-good backups and interrupted-upload handling.
- Do NOT store generated semantic embeddings; reindex after restore.

## Semantic model design

Non-negotiable user requirement: opt-in, small downloadable local-only embedding model and speedy performance on older Android phones.

Still experimental engineering choices from Brain (not an approved particular model):
- Candidate MiniLM-L3-v2, compare quality with MiniLM-L6-v2; quantized ONNX Mobile CPU inference.
- Precompute per-message or contextual chunk embeddings offline and incrementally when messages change.
- Combine SQLite FTS5 exact/keyword rankings with optional semantic similarity, always prioritize literal exact matches.
- Quantized int8 vectors around 384 bytes/message for 384-dimensional embeddings (excluding metadata/index overhead); avoid loading all into RAM on weak hardware.
- CPU/ARM NEON improvements, cached embeddings, background WorkManager indexing, battery/temperature throttling, query debounce and cancellation.
- User controls: opt in, download, index, pause, resume, rebuild, delete index, delete downloaded model and fully disable.
- No automatic upload of chats or searches; benchmark the model's actual package size, query latency, memory footprint and CPU demand on older Android before committing.

## Scope and validation status

All above features are approved **targets**, NOT claims of implementation. As of this checkpoint the app is a buildable independent Fossify-based Android project with an early Messenger-style overlay chat-head implementation and the recovered spring mechanics. Most of these other features, including the optional semantic model and Google Drive integration, are still future implementation work. Carrier RCS remains outside ordinary third-party app APIs unless supported by an authorized carrier/OEM integration. Developer must not promise unavailable features.

Distribute only with appropriate open-source license compliance. Do not copy proprietary Samsung/Meta assets or bytecode into the shipped codebase.
