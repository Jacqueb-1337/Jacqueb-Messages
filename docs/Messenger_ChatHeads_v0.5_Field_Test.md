# Messenger-style chat heads v0.5 — release notes and test plan
2026-10-28

## Reverse-engineered behavior used
The independent implementation is informed by Messenger 584.1.0.61.98 APK analysis (not copied proprietary code/assets). Confirmed Messenger layout/controller mapping: `RTA`, collapsed `RaE`, expanded `RV3`, RK4 springs `RUt`, and `RaK` flyout.

- Collapsed heads are newest-first with a 5dp trailing stack offset.
- Expanding fans individual heads newest/right to oldest/left, with 68dp horizontal separation from the inspected Messenger layout resources. Tapping a different head changes the selected conversation; tapping the selected head collapses the panel and springs the row back to the saved dock.
- Incoming heads enter from a 78dp edge offset with spring motion and recovered initial velocity constants 1000dp/s horizontal and −1500dp/s vertical. A separate, side-aware five-second flyout is displayed, including a custom drawn directional speech-bubble pointer. A locked or sleeping screen suppresses the visual flyout.
- The head stack and overlay are separate Windows Manager overlays; the selected head is indicated when expanded. A spring-following stack-collapse is implemented.
- Incoming SMS/MMS events are delivered directly to an existing opted-in foreground service, and when the app cannot start that service in background, a notification remains available.

## Notification correction (user clarification)
**Do not hide message notifications.** For incoming messages accepted by the active chat-head service:
- Always post the real SMS/MMS notification to the shade and lock screen; keep the message text and existing lock-screen privacy setting.
- Use a dedicated DEFAULT-importance channel (rather than HIGH) with a normal notification sound and vibration; this avoids requesting a heads-up banner while gaming so the flyout is the visual alert.
- Keep ordinary high-importance notifications as fallback if the overlay cannot accept an incoming head.
- Channel settings are user-configurable. Android and OEM behavior can override presentation. Per-conversation custom ringtones from the original HIGH-priority channel may require separate configuration for this new channel. Do not claim that a DEFAULT channel gives a universal heads-up suppression guarantee.
- The app's ongoing low-importance foreground-service notification remains while automatic chat heads are enabled; Android requires a visible notification for the foreground service.

## Known limits requiring device QA
- Build success does not prove correct gesture choreography or runtime window ordering on crDroid 16.
- SMS/MMS receipt, sender information, foreground service launch constraints and lock-screen presentation need testing with real incoming test messages and notification preferences.
- The 350ms flyout appearance delay, 228–240dp flyout width, graphics, and some animation choreography are our original design decisions rather than proven identical Messenger timing/geometry.
- Opening the overlay may not mark the thread read until the full conversation is opened; confirm intended read-state semantics.
- Test expanded multiple-head interactions, rotation, IME resizing, dragging in landscape, lock-screen privacy, screen-off incoming behavior and long-press Open Chat Head.
- Back up messages before relying on this development build as the sole SMS handler.

## Proven build
`gradlew :app:assembleFossDebug --offline --console=plain` completed successfully on the connected Windows PC.

Source uses the Fossify Messages GPLv3-derived transport. No Messenger proprietary code, assets, or resources are included.
