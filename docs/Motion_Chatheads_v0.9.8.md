# v0.9.8: Fluid motion and chat-head interactions

## Motion system
- Native Android shared-element transition from inbox conversation row to the full thread, with rounded clipping and entrance reveal, plus reverse transition on Back.
- Spring-style row/button presses, unread count bounce, RecyclerView item changes, Theme Studio page and dialog entrances, color-swatch touches, thread sending controls, and incoming floating message reveals.
- Android's system Animator duration-scale and reduced/disabled animations are respected. Anime.js is not embedded in this native app.

## Chat heads
- Separate fullscreen touch-through follower drawing overlay and compact touchable lead window now use measured screen coordinates to align on Android with nonstandard insets.
- Consistent collapsed head opacity while keeping non-touchable overlays under Android touch-obscuring limits.
- Every collapsed follower integrates its own spring against the preceding follower's newest position, rather than all directly following the leader.
- Collapsed stack dragged onto X clears all heads and visual overlays, resetting springs and retaining SMS/MMS data.
- Expanded heads are independently draggable with a temporary visual overlay and can reorder horizontally; siblings animate into new slots, and the selected conversation pointer follows its head.
- Expanded head dragged onto X removes only that head, leaves other heads and the expanded panel available, and switches to a surviving thread if the removed head was selected.
- Dismiss target detection now takes pointer coordinates as well as on-screen target/window coordinates to reduce stale-frame miss offsets.
- Existing header X continues to remove the selected expanded conversation, not all.

## Theme Studio
- HSV wheel, brightness, alpha, swatches and an editable synchronized hex field (supports standard 6- and 8-digit hex) for theme elements and bubble/text colors.
- One UI inbox synthetic preview improved, reduced conversation-preview compression, and separate bubble background/text color controls.
- This remains a synthetic preview rather than the actual production RecyclerView.

## QA
- Android 16 emulator: installed debug build, opened real Telephony SMS thread, Back returned to inbox without fatal exception.
- Added three test SMS conversations through emulator, observed four expanded heads, reordered by dragging, dismissed an individual expanded head with X, then collapsed three remaining heads and dragged stack to X; overlay window count fell to zero.
- Gradle build passes. Tests are visual smoke tests, not a full QA matrix; real OnePlus crDroid device behavior still needs confirmation.