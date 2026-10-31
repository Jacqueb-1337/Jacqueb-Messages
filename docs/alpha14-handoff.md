# Alpha 14: notification action order and avatar framing

Version: 0.11.0-alpha.14 (code 68), built from `feature/encrypted-safe-and-keyboard-recoil`.

- Reordered message notification actions to show **Mark as Read** before **Reply**. The same receivers, pending intents and action eligibility conditions remain in use. Non-replyable short-code messages still offer Mark as Read and Delete.
- Normal contact photos previously went through Glide `circleCropTransform()` before being handed to Android as the notification large icon. Android also applies its own notification icon clipping, which may make photos appear over-zoomed. Notification contacts now use the inbox's `centerCrop()` framing without the extra circular bitmap transform. Per-conversation custom profile photos are unchanged.

Validation: Kotlin editor diagnostics clean on both changed source files. Real-device visual comparison with the same incoming sender image is still required, because OEM notification renderers may size the large icon differently from the inbox. Verify that Mark as Read still clears the unread state and Reply still sends via the existing direct reply receiver.
