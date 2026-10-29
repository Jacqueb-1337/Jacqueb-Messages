# Theme Studio v0.9.6: Inbox parity and conversation preview

- Made mock Inbox layout closer to real inbox: removed fictional Messages header, used search toolbar at top, theme-card inset, live row paddings, 16sp titles, 13sp dates/previews, active/unread/selected visual rules, and row-boundary dividers.
- Only one sample row is highlighted as selected; unread alone no longer implies selection.
- Conversation preview limits excessive gap/padding only inside the demo to avoid reducing every sample message to illegible size. The actual saved theme measurements are not changed. When gap is reduced, the preview explains it.
- Tapping anywhere on a received or sent bubble now opens one editor for bubble fill, text fill and shared corner radius. Separate swatch selectors and hex inputs for each color. The text child no longer captures taps.
- Themes and unsaved edits are preserved.

Verification: Gradle :app:assembleFossDebug passed; signed debug APK version code 35. Device-side screenshot and touch validation still required. Existing app's original widget styles may differ from mock elements beyond these changes.