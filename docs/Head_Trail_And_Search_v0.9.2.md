# Chat-head trailing bounds and search-toolbar correction — v0.9.2

Android versionCode 31, versionName 0.9.2-head-bounds.

## Clipping diagnosis
FrameLayout.clipChildren=false cannot prevent Android's WindowManager from clipping to the WINDOW's 79dp-by-79dp rectangle. The follower springs were computed in screen coordinates, but child avatar views were drawn outside their containing overlay and visibly clipped.

## Implementation
- Keep the leading 79dp head window unchanged and touchable.
- Add an independent transparent non-focusable FLAG_NOT_TOUCHABLE follower visual-only TYPE_APPLICATION_OVERLAY window over the display.
- Create this decorative layer before the touch window so the leading head draws above the followers.
- Mirror follower avatar visuals into this layer; position mirrors from originals' screen coordinates each spring frame. The originals remain attached to the stable head window, hidden only while mirrors appear.
- On expand, restore the originals and hide the follower layer. On collapse, show the follower visuals before narrowing the window and continuously reposition them. No new touchable areas and no change to RK4 spring presets.
- Set the visual-only overlay window alpha to 0.76, below Android's 0.8 obscuring-opacity threshold for touch pass-through of untrusted overlays. Followers are slightly translucent. System security settings remain authoritative.
- Dispose visual-only layer on clear, removal of last head, or service teardown.

## Search toolbar
The gray search field's overflow slot had the nearly black samsung_surface toolbar color, producing the top-right black rectangle. A rounded full toolbar background now uses gray (#373737 dark, #F1F1F3 light).

## Validation
Android FossDebug compilation passes; device testing required to verify animation, touch pass-through during gaming and search toolbar visual behavior. The mirrored follower views are independent implementation, no proprietary Messenger source or assets reused.
