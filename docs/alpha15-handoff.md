# Alpha 15: New message composer responsiveness

Version: 0.11.0-alpha.15 (code 69).

- Removed the heavyweight Android shared-element transition from the inbox + button to the full-screen contact picker. The picker now uses a lightweight activity fade and a local content entrance, avoiding a gray full-screen shared-element snapshot and costly return transition.
- Defer showing the input method until the new screen's first frame, reducing competition between initial window layout and keyboard resizing.
- Added process-local ComposeContactsCache. The inbox prefetches device contacts in the background after its first draw; opening the composer shows any cached contacts immediately while requesting a fresh provider snapshot asynchronously. Concurrent queries share the same pending load. Contact permission is checked before reusing cached data.
- Removed RecyclerView's row-by-row `layoutAnimation`, which could make the list appear to load progressively after its data was already ready.
- Reduced the post-composer inbox refresh delay from 420 ms to 220 ms, after the lightweight activity fade.

Validation: Signed FossRelease build succeeded; APK package, version and signing certificate verified against alpha.14. Installed over the existing emulator app without clearing data. New conversation opened both directly and from the New message (+) button; Back returned to the inbox. A screenshot confirmed the picker rendered normally instead of a blank gray view. The emulator has no saved contacts, so real contact-provider latency and improvement are not benchmarked. On a real device with hundreds of contacts, compare the first cold opening, a second opening while the app process remains alive, and Back to the inbox. The first opening may still wait on ContactsProvider if compose is opened before prefetch has completed.
