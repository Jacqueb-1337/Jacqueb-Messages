# Samsung Messages replacement — approved feature gap audit
Updated 28 October 2026, after v0.6 code changes.

## Evidence and scope
This compares `docs/Approved_Feature_Scope.md`, the user's Brain approvals, and inspected Kotlin source. 'Present' means source-level support, **not** carrier/device end-to-end confirmation. Third-party OEM/carrier integrations may not be replicable.

## Present as inherited Fossify functionality (not new features completed by this project)
- Core standalone SMS/MMS send/receive, default SMS role, basic conversation list/thread view and basic search.
- A one-time scheduled SMS dialog and scheduling receiver (not recurring scheduling).
- Ordinary drafts and basic blocked keywords/number support.
- Local import/export and backup models for messages/MMS (not an encrypted single-file Google Drive backup).
- Recycle bin, archived conversations, simple themes/dark mode and standard notification handling.

## Custom implementation in the current project
- Messenger-inspired TYPE_APPLICATION_OVERLAY chat-head foreground service with optional automatic incoming heads.
- Chat-head stack, expanded fan/selection, draggable dock and persistence, fling/close-target movement and five-second directional message flyout.
- Independently implemented spring engine with RK4 coefficients recovered from shipped Messenger artifact.
- Exact *verified haptic trigger*: Messenger class `X.Ra9.A01` calls `performHapticFeedback(0)` when `RTA.A0J` changes into the capture state `C0OD.A01`. `RTA.A0J` compares squared target distance with the 30dp capture radius. Our v0.6 triggers Android `HapticFeedbackConstants.LONG_PRESS` only on entry, honoring system haptic settings.
- Right/left positioning, selected-head indicator, long-press context action Open Chat Head, in-overlay SMS send, MMS photo thumbnails and safe handoff for group/MMS full composer.
- Separate incoming message channel preserving notification shade/lock-screen content, sound and vibration but without requesting intrusive heads-up popup when overlay successfully handles event.
- Local conversation ordering based on last actual SMS/MMS timestamp; unread-state cache reconciliation.
- Source-level v0.6 fixes: shorter return animation with no collapsed-head reconstruction, themed composer, IME resize/insets, immediate expanded-head refresh on incoming and bounded periodic refresh while panel visible. **Not yet verified on user's phone.**

## Approved but not yet implemented — all grouped by deliverable

### Search and optional semantic model
- Advanced combined filters: contact, date range, phrases, media/link type, read state, category.
- Tiny **optional** separately downloaded local embedding model and semantic search, indexed incrementally and optimized for older CPUs. No AI except this optional search. No model is bundled.
- Model/index settings: download, opt in, progress, pause/resume, rebuild, remove model and index; power/thermal budget.

### Sending, composing, text actions
- Delayed queued sending with a true Undo Send before network transmission.
- Recurring SMS scheduling and saved message templates/quick responses (single scheduled SMS already exists).
- Selective copy of partial text and multi-message selections/copy.
- Deterministic, non-AI actions for dates, addresses, maps, phone numbers, event/calendar suggestions.
- Better composer media picker/edit/preview and share workflows directly within chat-head overlay. The current panel delegates these to full conversation.

### Conversation workflow
- Reply-later reminders and Read Later.
- Individual message pinning and custom ordering of pinned conversations.
- Custom swipe/gesture shortcuts.
- User-defined folders/categories and deterministic automatic filing rules.
- Timed/conditional/recurring mute controls.
- Locked/private conversations with optional biometric/PIN authentication.
- Personal notes linked to threads and dedicated per-conversation media/link galleries.
- Rule-based notification/categorization automations without AI.
- Spam/keyword filtering rules beyond current basic block-list support, OTP auto-cleanup and optional notification history.

### Appearance and Samsung feature parity
- Exact Samsung Messages visual fidelity remains incomplete: inbox, search, full composer, conversation layout, animations, settings hierarchy, bubble shapes/spacings and status decorations. Current core UI still derives from Fossify.
- Per-chat color themes, wallpapers/backgrounds, bubble shapes, typography/font selection, custom motion/animations.
- Samsung-style conversation categories, starred messages, rich link previews, delivery/status indicators, nuanced dual-SIM UI and per-conversation sound controls require audit/implementation.
- Full group MMS compose inside overlay, attachments preview/edit and accurate system keyboard resize.
- Pixel-level Messenger fidelity (timing, linked-head physics, overlays, IME focus, entrance/exit, multi-head lifecycles) **not yet achieved or device-verified**. Other Messenger haptic triggers are unverified; avoid invented waveform constants.

### Backup and restore
- User-visible encrypted portable local snapshots (existing basic export is not this feature).
- Google Drive OAuth without separate app account; automatic upload to **one** user-owned file updated in place rather than many backups.
- Background WorkManager schedule/manual backup controls; Wi-Fi preferences, recovery key and cryptographic verification, interrupted-upload recovery.
- Selective restore, attachments exclusions, settings/themes/folders/pins/notes metadata; last-known-good snapshot and data validation.

### Deliberately deferred or outside normal APIs
- PC/web companion texting: user explicitly postponed.
- Carrier/OEM RCS without an authorized third-party interface: do not promise it as functional.
- All general AI (smart reply, summaries, suggestions, cloud processing): explicitly prohibited except opted-in local semantic search.

## Engineering QA remaining
The v0.6 build passing Gradle does not validate IME repositioning, real incoming SMS/MMS delivery, foreground service survival, haptic perceptual equivalence, latest-message refresh, notification channel behavior, group safety, custom ROM window ordering or battery impact on the OnePlus phone. These require independent device-side observations.
