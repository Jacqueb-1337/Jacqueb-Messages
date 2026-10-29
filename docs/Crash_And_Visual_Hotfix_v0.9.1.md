# Samsung-inspired Messages v0.9.1 crash hotfix

Date: 2026-10-28

## Crash fix
An on-device trace from crDroid reported IllegalArgumentException from coerceIn(), with maximum 216px less than minimum 237px, in FloatingChatHeadsService.animateCollapseInPlace. A single expanded chat head could have a narrower expanded layout than the 79dp collapsed avatar. The width animation now ensures the upper bound is never below the collapsed avatar width. The expanded-width calculation likewise has a minimum avatar width.

## Additional UI corrections
- Inbox conversation preview gets a 14dp gone-margin when the draft label is not present, restoring padding from the avatar.
- The top-right black square is still under investigation; no unverified rendering workaround is claimed as a fix.
- Tapping an inbox contact avatar opens a contact-specific quick-action dialog with Open conversation, Open Chat Head, and Call (for individual contact threads); groups omit Call. Calling uses ACTION_DIAL, which requires an additional user action to dial.

## Unread-state correction
- The inbox hides empty unread bubbles when the unread count is zero, and uses consistent read state for bold names and previews.
- The bottom Conversations badge uses the same unread-conversation condition, rather than counting stale unread flags.
- On returning to the inbox from a conversation, the app reconciles the local read flags against Android's SMS and MMS providers. It only changes local flags when both queries succeed and the app remains the default SMS app, avoiding accidental bulk read changes on permission errors.

## Validation
Gradle :app:assembleFossDebug completed successfully. Version 0.9.1-hotfix, code 30.
On-device reproduction and screenshot verification remain necessary. Chat-head animation still isn't established as pixel-perfect Messenger.
Existing SMS/MMS transport and persistent local message data were not modified by the hotfix.
