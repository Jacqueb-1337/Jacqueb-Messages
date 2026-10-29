# Theme Studio v0.9.5

Changes:
- Remove old bottom/end FloatingActionButton gravity, margins, and inset handling from the live inbox. Set its X/Y coordinates after layout using a shared normalized placement function, and reapply on size changes.
- Theme Studio's Inbox preview now places the draggable new-message button on top of the mock inbox instead of a separate 180dp region.
- Replace the scrollable preview with an auto-fit frame that measures natural full-width content then scales it uniformly to fit the available height, for both Inbox and Conversation. Drag deltas are adjusted for preview scale.
- Remove an unused import of Fossify's sideload warning check. MainActivity already explicitly avoided invoking the upstream originality check; a displayed warning on an installed build requires device verification.
- Preserve all saved themes/geometry settings and SMS/MMS data.

Validation: Gradle :app:assembleDebug completed successfully (foss and gplay flavors), git diff --check passed. Physical-device screenshots and direct taps are not yet validated.
