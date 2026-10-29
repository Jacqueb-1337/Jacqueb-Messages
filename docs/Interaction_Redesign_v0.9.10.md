# v0.9.10 — Fluid interaction architecture, phase 1

## User intent
Not a request for additional spring effects: the entire Android messaging app should behave like a continuous, directly manipulated interface comparable to a polished iOS app. Elements should transform from their sources, return on the reverse route, respond during touch, inherit velocity, and be interruptible. UX must remain usable, accessible, and performant on older devices.

## Implemented now
- Replaced UiMotion's precomputed, fixed-duration pseudo-spring easing on press surfaces with real AndroidX SpringAnimation for X and Y scales. Springs track current presentation position/velocity and retarget without hard snap. Recycled views cancel pending press state; system animator-disabled mode is respected.
- Removed unrelated overshoot/zoom choreography from common dialog presentations, synced chat entrance content rather than staggering all subviews, and reduced unnatural full-screen crossfades.
- Replaced conversation-avatar AlertDialog action lists with native draggable Material BottomSheetDialog menus. Swipe down / outside tap / system Back dismiss the menu, selection triggers the action immediately.
- Replaced Theme Studio theme selection and spacing-option selection with the same draggable sheets.
- Replaced Theme Studio color, bubble and spacing editors with scrollable bottom sheets with fixed Cancel / Apply. Invalid color leaves the editor open; valid values still persist using the existing theme engine. Kept color wheel and hex editing.
- Converted per-conversation picture and vibration actions to the same draggable action sheets.
- New-conversation composer now transitions from the actual "+" button or Contacts-tab origin through a native shared surface, with IME entry scheduled behind the shared transition to avoid cutting across it.
- Tuned the color wheel and form sheet so the palette, hex code, action buttons and sliders remain accessible on smaller viewports.

## Explicitly not completed
- A full single-activity / state-driven navigation redesign (existing SMS/MMS architecture remains intact).
- Interactive gesture-progress-driven navigation on every screen, particularly reversible in-flight edge-back with the underlying inbox visible; integrate official predictive Back APIs without overriding the system gesture or message swipe.
- Message-context press-and-hold lift, blur/depth treatment, reversible swipe reply, gesture-controlled attachments and composer height.
- Unified state machine for chat-head opening, docking, dragging, reordering, collapse, and floating conversation panel across separate WindowManager overlays.
- Full interactive hierarchy and reduced-motion matrix across every Settings, attachment, search, media, and theme surface.
- Performance verification at 60/90/120Hz and tests on the actual OnePlus device.

## Verification
- Offline Gradle compile and Foss debug builds passed during implementation; both Foss and Gplay debug builds passed after editor migration.
- Android 16 emulator: avatar-sheet actions display in draggable bottom sheet; dragged sheet down to dismiss; opening a new conversation from the main "+" button succeeded and keyboard appeared afterward; no fatal exception observed.
- Theme Studio color editor sheet displayed with wheel, opacity and brightness. Initial layout hid Apply/Cancel. Changed to fixed action footer and reduced scrolling height. Following build confirmed visible pinned Apply/Cancel, then wheel reduced to fit hex and swatches in the compact viewport.
- No destructive changes to SMS/MMS data or sending service.
