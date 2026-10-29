# Safe and keyboard repair, alpha.10

## Report and cause

The device crash at 2026-10-29 13:51:45 was an uncaught IllegalArgumentException in SafeStore.move. Safe's two-minute authentication window could expire while a Move confirmation remained visible. Pressing Move then hit a require(unlocked()) assertion.

The keyboard effect was independent of the actual resize: OverlayConversationPanel waited for IME completion or a 380 ms fallback, then KeyboardCascade translated and scaled individual message rows for 560 ms. ThreadActivity also triggered this ripple from a global layout observer.

## Changes

- SafeStore.move rejects invalid or unauthenticated requests without throwing or changing membership. Successful commits remain the source of truth; notification cleanup failures cannot turn a committed move into a reported failure.
- Safe confirms authentication again when a Move dialog is accepted. Expiration hides list content and private conversation content. Returning directly from a private thread to its Safe list preserves the current session.
- Safe uses the same Commons SecurityDialog as app password protection. If app protection is enabled, Safe uses its current credential and type. Existing independent Safe locks remain available for one-time verified migration. New Safe-only locks use the shared framework.
- The pinned Commons credential dialog is synchronously marked FLAG_SECURE before drawing; inability to secure it closes Safe.
- Safe uses themed conversation rows, message previews, contextual Move to inbox, toolbar lock/privacy actions, and system-bar insets. Background query results are discarded after rerenders and session expiration.
- API 30+ floating panels retain a stable window with ADJUST_NOTHING. Their bottom padding follows WindowInsetsAnimation.onProgress values, subtracting system-bar space already excluded by WindowManager. The card top stays fixed throughout resizing.
- A small native spring transfers landing velocity to the complete card and pointer. It is cancelled on interruption, removal, or Genie animation and respects disabled system animations.
- Older Android versions retain native adjustResize. The independent row ripple was removed from both UI surfaces and its implementation deleted.

## Validation

Foss debug, Android test APK, and signed Foss release builds passed. The release package is com.jacqueb.messages, versionCode 64, versionName 0.11.0-alpha.10, with the same signing certificate as alpha.9. Android 16 emulator runtime checks passed: locked/expired/retry/idempotent/invalid-ID moves, preservation of existing memberships, actual expired Move confirmation, screenshot-protected authentication, and 15 opening / 17 closing IME frames with fixed card top and bottom error no greater than 3 physical pixels.

The instrumentation runner uses real Android preferences and the real Safe UI. It checks locked/expired/repeated moves, preservation of existing memberships, actual expired dialog confirmation, screenshot protection on authentication, and frame-by-frame top/bottom positions during opening and closing of the real keyboard. Test preferences are restored afterward. Safe screenshot inspection verified menu glyphs, contrast, previews and system-bar spacing.

## Remaining limitations

Safe storage still uses Android's shared SMS provider. This change does not deliver the unfinished encrypted vault or remove plaintext provider records. Other permitted SMS apps can still access those messages. Physical-device motion and carrier SMS/MMS behavior remain unverified for this build.
