Keyboard motion, retained chat heads, and encrypted Safe

- Conversation layout now follows each animated keyboard inset in the main screen and chat heads. Message recoil is driven by keyboard travel velocity while it moves, then settles naturally. Direction changes continue the same spring state.
- Minimize/reopen retains the conversation panel, rendered messages, draft, and scroll position. Reordering heads, repeated head intents, and normal UI backgrounding keep this cache. Closing a head or actual memory pressure releases it.
- Safe now stores SMS, MMS metadata, and attachments using Android Keystore AES-256-GCM. Encrypted writes are read back and authenticated before exact source rows are removed from Android's messaging database. Restore verifies public rows and attachments before encrypted cleanup.
- Interrupted moves retain a recoverable encrypted archive. Retries preserve identical duplicate messages and repair incomplete MMS restores. Authentication expiry returns an error rather than crashing.
- Incoming private SMS is encrypted directly, including while Safe is locked. Private SMS replies and drafts remain encrypted. Safe continues using the existing app authentication measures.

Existing Safe conversations: choose Encrypt messages from the conversation menu once. Existing entries are explicitly marked until encrypted.

Limits: downloaded incoming MMS currently passes through Android's provider before being absorbed into Safe. Group replies and sending MMS require restoring the conversation to the inbox. Encrypted files are bound to this installation's device key: restore conversations before clearing app data, uninstalling, or changing phones. Live carrier sending was not tested.

Validation: Android 16 emulator instrumentation passed SMS duplicates, MMS image round trips, interruption before/after deletion and during restore, authenticated corruption rejection, incoming SMS while locked, and post-restore arrival routing. Main screen message springs were measured during keyboard travel. Overlay opening and closing tracked actual animated IME geometry within 3 pixels. Reopen preserved the same panel/message view objects and draft, including the UI-hidden callback. Signed APK identity and in-place update smoke test verified separately.

Delivery completed: GitHub prerelease v0.11.0-alpha.11, versionCode 65. Installed and confirmed on owner's phone. APK SHA256: 7f6a5d568c7ce6af07b5f7762df091581ef42dc7aaabd880ee2930ac3e96c2b7. Production commit 5141585; test baseline correction a138bd1. Branch: feature/encrypted-safe-and-keyboard-recoil.

Final regression log: __vault_final_device_test.log. Build log: __alpha11_final_build.log. Overlay opening 8 and closing 7 sampled frames, geometry within 3px. Main recoil 10 driven frames. Cached reopen preserved panel, message views and draft including UI-hidden callback. Test must await rendered messages and hide keyboard before opening measurement; arbitrary startup sleeps caused false failures. Default-app chooser blocks overlays; force-stop release package and permission controller when testing dev package, and grant SMS role to dev. Restore release SMS role afterward (completed).

No remaining release work. Remaining product work: remove transient incoming MMS provider exposure; group and MMS sending inside encrypted Safe; backup/export recovery for device-bound vault; physical-device subjective motion tuning and live carrier sending validation. Existing Safe chats need explicit Encrypt messages. Never clear app data/uninstall before restoring vault chats.

Source map: safe/SafeVault.kt contains authenticated archive storage and recovery; safe/SafeConversationActivity.kt private reading/replies; SafeStore.kt transfers; receivers/SmsReceiver.kt and MmsReceiver.kt incoming routing; theming/ReactiveKeyboardMotion.kt velocity-driven springs; activities/ThreadActivity.kt main insets; chatheads/OverlayConversationPanel.kt overlay insets and FloatingChatHeadsService.kt cached panels. All paths under app/src/main/kotlin/org/fossify/messages. Tests: app/src/androidTest/java/org/fossify/messages/SafeRegressionInstrumentation.kt.
