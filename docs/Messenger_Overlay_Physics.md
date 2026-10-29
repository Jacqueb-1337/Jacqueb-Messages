# Messenger 584.1.0.61.98 overlay chat heads: reverse-engineering reference

Original APK: com.facebook.orca 584.1.0.61.98, inspected via REA and JADX. Decompiled vendor code is retained only as research reference under D:\Projects\SamsungMessagesReference\messenger_reference. No proprietary Meta implementation or graphics have been incorporated into the distributable app.

## Architecture directly confirmed from the APK

- Manifest requests android.permission.SYSTEM_ALERT_WINDOW, and its ChatHeadService is a foreground service with a specialUse subtype called chathead.
- ChatHeadsMiniView is a ViewGroup. In onLayout, separate children are offset by **5 dp**; the offset changes sign for left/right dock positions.
- ChatHeadsFullView extends OverlayLayout and contains BubbleView, a dismissal target, conversation content container and custom keyboard-aware layout.
- The expanded chat is a separate overlay surface, NOT Android's native Notification.BubbleMetadata.

## Exact spring equations extracted from X.5pu and X.4nS

Acceleration of position x, with velocity v and target q:

    dx/dt = v
    dv/dt = k*(q - x) - c*v

Converted designer coefficients (T is tension; F is friction):

    k = 3.62*(T - 30) + 194
    c = 3.0*(F - 8) + 25

Integration uses fixed **1 millisecond RK4 substeps**. Each incoming frame delta is capped at 64ms, then accumulated. After full millisecond steps, fractional remainder is interpolated from a prior simulated state. Default stop thresholds are 0.0001 for both |v| and |q-x|, but individual springs override them (e.g. 0.005 or 0.3). Optional overshoot clamping exists.

Observed presets:

| Component | Tension T | Friction F | k | c |
| --- | ---: | ---: | ---: | ---: |
| X.RUt.A08: moving an individual head | 40 | 7 | 230.2 | 22 |
| X.RUt.A09: moving attached heads | 220 | 15 | 881.8 | 46 |
| X.RV0.A0C: head press scale | 200 | 7 | 809.4 | 22 |
| X.RV0.A0B: head alpha | 40 | 7 | 230.2 | 22 |
| BubbleView.A0S: expand/appear | 150 | 12 | 628.4 | 37 |
| BubbleView.A0R: collapse/disappear | 65 | 8.5 | 320.7 | 26.5 |
| ChatHeadCloseTargetView.A0O: target position | 40 | 7 | 230.2 | 22 |
| ChatHeadCloseTargetView.A0N: target scale | 150 | 9 | 628.4 | 28 |

The independently authored MessengerSpring.kt implements the recovered coefficient transformation, fixed 1ms RK4 stage arithmetic, accumulation, interpolation and rest check.

## Magnetic/attraction calculations recovered from X.RTA.A0J

Let P be the center of the dragged head and C the target center. The attraction spring supplies fraction lambda; the target visual position is the interpolated center:

    X = P.x + lambda * (C.x - P.x)
    Y = P.y + lambda * (C.y - P.y)

X and Y are converted to integer pixel positions before subtracting half the moving head width and height. Completion is distance-to-target squared:

    (X - C.x)^2 + (Y - C.y)^2 <= (30dp)^2

**Important:** confirmed source resource 0x7f1a003e (dimen.2) is **30dp**. It is not 54dp. This resource is the catch radius.

The broader activation region from ChatHeadCloseTargetView.A0e is:

    abs(headX - screenWidth/2) < 100dp
    headY >= screenHeight - 130dp

The corresponding resources are 0x7f1a0067 = 200dp and 0x7f1a007c = 130dp.

Target follow motion in ChatHeadCloseTargetView.A02 uses:

    targetShiftX = (pointerX - viewWidth/2) * 0.1
    targetShiftY = max(-25dp, (pointerY - RPB.A00(view)) * 0.1)

It uses 40/7 position springs and 150/9 scale springs. Its emphasis target switches between 0.7 (outside activation) and 1.0 (inside activation). Further examination is needed to fully resolve RPB.A00's coordinate meaning and animation synchronization.

## Sling-to-dismiss recovered from X.RTA.A1I

Input position (rawX, rawY), velocity (vx, vy) in px/sec, screen size (W,H):

    speed = sqrt(vx*vx + vy*vy)
    require speed >= getDimensionPixelSize(0x7f1a0069) // 500 dp converted to px
    require vy > 0
    projectedX = rawX + vx * ((H - rawY) / vy)
    caught = abs(projectedX - W/2) < 90dp

The projected catch corridor is **180dp wide** (resource 0x7f1a002c). REA traced RTA.A07 to RTA.A1U, which loads resource 0x7f1a0069 = **500dp** via getDimensionPixelSize. Thus speed threshold is precisely **500 * density** px/sec; the prototype now uses this actual threshold.

This is a constant-velocity straight-line projection, not gravitational acceleration. 'Gravity' in Messenger chat heads largely refers to the spring-controlled attraction toward the dismiss target.

## Dock position storage from X.RTA.A0m

Right/left side is stored as a discrete 1/0 depending on whether the head is at the rightmost legal X. The vertical dock coordinate is normalized:

    yFraction = clamp(y / (screenHeight - headHeight), 0, 1)

This enables a relative position across screen sizes.

## App implementation status

Working project: D:\Projects\SamsungMessagesApp
- ChatHeadsSettingsActivity.kt: user opt-in and manual selecting SMS threads
- FloatingChatHeadsService.kt: overlay foreground service, stack, drag, spring edge snap, dismiss zone, gravity blend, sling trajectory
- OverlayConversationPanel.kt: focusable floating conversation reading SMS via Telephony provider and sending through Fossify's existing SMS/MMS messaging engine
- MessengerSpring.kt: independent RK4 physics engine
- Main application menu: Chat heads entry

The Android debug APK compiles, but pixel-perfect runtime validation and several features remain incomplete: contact avatar artwork, automatic notification-driven head opening, exact overlay coordinate offsets, view growth/shrink behavior, target movement synchronization, multi-SIM/group-MMS parity, keyboard transitions, and other motion-controller timing details. Never claim a complete Messenger recreation without runtime comparison.

The modified application is based on Fossify Messages (GPLv3). Respect that license if distributed. Meta's proprietary binary resources, source, and brand assets are not reused.