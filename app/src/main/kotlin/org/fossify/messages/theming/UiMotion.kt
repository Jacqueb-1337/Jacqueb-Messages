package org.fossify.messages.theming

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.SharedElementCallback
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeTransform
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.Button
import androidx.core.view.ViewCompat
import org.fossify.messages.R
import kotlin.math.cos
import kotlin.math.exp

/**
 * Shared native Android motion vocabulary. No web runtime or per-frame layout
 * allocations: lightweight transform/opacity work stays on the render thread.
 * All animations respect the system Animator duration scale.
 */
object UiMotion {
    const val THREAD_SURFACE = "thread_surface_shared"
    const val SHARED_THREAD_LAUNCH = "fluid_thread_launch"
    const val THREAD_ORIGIN_X = "thread_origin_x"
    const val THREAD_ORIGIN_Y = "thread_origin_y"
    const val THREAD_ORIGIN_WIDTH = "thread_origin_width"
    const val THREAD_ORIGIN_HEIGHT = "thread_origin_height"
    const val COMPOSE_SURFACE = "compose_surface_shared"
    const val SHARED_COMPOSE_LAUNCH = "fluid_compose_launch"

    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private val soft = PathInterpolator(.22f, .72f, .22f, 1f)
    private const val TAU = 6.283185307179586f

    /**
     * The old implementation passed a precomputed, fixed-duration fake spring
     * as an interpolator. Native SpringAnimation instead retains real position
     * and velocity between interruptions and can immediately retarget.
     */
    private fun force(response: Float, damping: Float) =
        androidx.dynamicanimation.animation.SpringForce().apply {
            stiffness = (TAU / response).let { it * it }
            dampingRatio = damping
        }

    private class PressSprings(view: View) {
        val x = androidx.dynamicanimation.animation.SpringAnimation(
            view, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X)
        val y = androidx.dynamicanimation.animation.SpringAnimation(
            view, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y)
        init {
            x.spring = force(.29f, 1f)
            y.spring = force(.29f, 1f)
        }
        fun target(value: Float) {
            x.animateToFinalPosition(value)
            y.animateToFinalPosition(value)
        }
        fun cancel() { x.cancel(); y.cancel() }
    }
    private val pressTagId: Int = R.id.motion_press_state
    private fun pressState(view: View): PressSprings {
        val existing = view.getTag(pressTagId) as? PressSprings
        return existing ?: PressSprings(view).also { view.setTag(pressTagId, it) }
    }

    fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun press(view: View, pressedScale: Float = .985f) {
        // This visual observer does not consume click, long-click, or scrolling.
        // A pressed target is continuously retargeted from the displayed state.
        view.setOnTouchListener { target, event ->
            if (!enabled()) return@setOnTouchListener false
            val springs = pressState(target)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    springs.target(pressedScale.coerceIn(.92f, 1f))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springs.target(1f)
                MotionEvent.ACTION_MOVE -> {
                    // Cancelling a press by moving beyond its hit target should
                    // not leave a pressed-looking button behind.
                    val outside = event.x < -dp(target, 14f) ||
                        event.y < -dp(target, 14f) ||
                        event.x > target.width + dp(target, 14f) ||
                        event.y > target.height + dp(target, 14f)
                    springs.target(if (outside) 1f else pressedScale)
                }
            }
            false
        }
    }

    fun clear(view: View) {
        (view.getTag(pressTagId) as? PressSprings)?.cancel()
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        view.translationX = 0f
        view.alpha = 1f
    }

    fun enter(view: View, distanceDp: Float = 8f, delay: Long = 0L) {
        if (!enabled() || !view.isAttachedToWindow) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(view, distanceDp.coerceIn(-12f, 12f))
        view.animate().alpha(1f).setStartDelay(delay).setDuration(155L)
            .setInterpolator(soft).start()
        view.postDelayed({
            if (view.isAttachedToWindow) {
                androidx.dynamicanimation.animation.SpringAnimation(
                    view, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y)
                    .apply { spring = force(.30f, 1f) }
                    .animateToFinalPosition(0f)
            }
        }, delay)
    }

    fun bump(view: View) {
        if (!enabled()) return
        // Small impulse, no choreography or second animation that snaps
        // in from the target state.
        val springs = pressState(view)
        springs.x.setStartVelocity(1.8f)
        springs.y.setStartVelocity(1.8f)
        springs.target(1f)
    }

    private fun sharedTransition(timeMs: Long): TransitionSet =
        TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds().apply { resizeClip = true })
            addTransition(ChangeClipBounds())
            addTransition(ChangeTransform())
            duration = timeMs
            interpolator = PathInterpolator(.18f, .70f, .24f, 1f)
        }

    /**
     * Call before setContentView on both activities. Shared surface is clipped
     * during expansion rather than stretching the message text to screen size.
     */
    fun prepareWindow(window: Window, forThread: Boolean) {
        if (!enabled()) return
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        window.sharedElementEnterTransition = sharedTransition(350L)
        window.sharedElementReturnTransition = sharedTransition(330L)
        window.enterTransition = Fade(Fade.IN).apply { duration = 130 }
        window.exitTransition = Fade(Fade.OUT).apply { duration = 115 }
        window.returnTransition = Fade(Fade.OUT).apply { duration = 115 }
        window.reenterTransition = Fade(Fade.IN).apply { duration = 130 }
        window.allowEnterTransitionOverlap = true
        window.allowReturnTransitionOverlap = true
    }

    /**
     * Clip the incoming screen to rounded bounds while the shared
     * conversation surface grows. Reveal children after its expansion begins.
     * When returning, round back toward the original conversation card.
     */
    fun threadSurface(activity: Activity, surface: View, shared: Boolean,
                      name: String = THREAD_SURFACE) {
        if (!shared || !enabled()) return
        ViewCompat.setTransitionName(surface, name)
        val radius = UiTheme.dp(activity, UiTheme.radius(activity, "card_radius")).toFloat()
        val background = GradientDrawable().apply {
            setColor(UiTheme.color(activity, "card"))
            cornerRadius = radius
        }
        surface.background = background
        surface.clipToOutline = true
        var valueAnimator: ValueAnimator? = null
        fun animateRadius(from: Float, to: Float, durationMs: Long) {
            valueAnimator?.cancel()
            valueAnimator = ValueAnimator.ofFloat(from, to).apply {
                duration = durationMs
                interpolator = soft
                addUpdateListener { animator ->
                    background.cornerRadius = animator.animatedValue as Float
                    surface.invalidateOutline()
                }
                start()
            }
        }
        activity.window.sharedElementEnterTransition?.addListener(object: Transition.TransitionListener {
            override fun onTransitionStart(transition: Transition) { animateRadius(radius, 0f, 320) }
            override fun onTransitionEnd(transition: Transition) {
                background.cornerRadius = 0f
                surface.clipToOutline = false
            }
            override fun onTransitionCancel(transition: Transition) {
                valueAnimator?.cancel()
                background.cornerRadius = 0f
                surface.clipToOutline = false
            }
            override fun onTransitionPause(transition: Transition) {}
            override fun onTransitionResume(transition: Transition) {}
        })
        activity.window.sharedElementReturnTransition?.addListener(object: Transition.TransitionListener {
            override fun onTransitionStart(transition: Transition) {
                surface.clipToOutline = true
                animateRadius(0f, radius, 305)
            }
            override fun onTransitionEnd(transition: Transition) { valueAnimator?.cancel() }
            override fun onTransitionCancel(transition: Transition) { valueAnimator?.cancel() }
            override fun onTransitionPause(transition: Transition) {}
            override fun onTransitionResume(transition: Transition) {}
        })
    }

    /** One transform of the complete conversation, from the actual tapped row.
     * This deliberately replaces the incompatible mix of Android shared-element
     * resize, Activity Fade and three staggered child entrance animations. */
    fun threadEnter(activity: Activity, surface: View, origin: android.graphics.Rect?) {
        if (!enabled() || origin == null || origin.isEmpty) return
        surface.alpha = 0f
        surface.post {
            if (!surface.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return@post
            val screen = IntArray(2)
            surface.getLocationOnScreen(screen)
            val w = surface.width.toFloat().coerceAtLeast(1f)
            val h = surface.height.toFloat().coerceAtLeast(1f)
            val targetCenterX = screen[0] + w / 2f
            val targetCenterY = screen[1] + h / 2f
            surface.pivotX = w / 2f
            surface.pivotY = h / 2f
            surface.scaleX = (origin.width().toFloat() / w).coerceIn(.23f, 1f)
            surface.scaleY = (origin.height().toFloat() / h).coerceIn(.08f, 1f)
            surface.translationX = origin.exactCenterX() - targetCenterX
            surface.translationY = origin.exactCenterY() - targetCenterY
            surface.alpha = 1f
            surface.animate().cancel()
            surface.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .setDuration(330L)
                .setInterpolator(PathInterpolator(.16f, .72f, .22f, 1f))
                .start()
        }
    }

    fun threadExit(surface: View, origin: android.graphics.Rect?, finish: () -> Unit) {
        if (!enabled() || origin == null || origin.isEmpty || !surface.isAttachedToWindow) {
            finish()
            return
        }
        surface.animate().cancel()
        val screen = IntArray(2)
        surface.getLocationOnScreen(screen)
        val w = surface.width.toFloat().coerceAtLeast(1f)
        val h = surface.height.toFloat().coerceAtLeast(1f)
        surface.pivotX = w / 2f
        surface.pivotY = h / 2f
        surface.animate()
            .translationX(origin.exactCenterX() - screen[0] - w / 2f)
            .translationY(origin.exactCenterY() - screen[1] - h / 2f)
            .scaleX((origin.width() / w).coerceIn(.23f, 1f))
            .scaleY((origin.height() / h).coerceIn(.08f, 1f))
            .setDuration(170L)
            .setInterpolator(PathInterpolator(.25f, .05f, .60f, 1f))
            .withEndAction(finish).start()
    }

    fun threadReveal(views: List<View>, shared: Boolean) {
        if (!enabled()) return
        // Keep typed drafts, keyboard, and message data untouched.
        views.forEach { child ->
            child.post {
                if (child.isAttachedToWindow) enter(child, 5f, if(shared) 65L else 0L)
            }
        }
    }

    fun dialog(view: View?) {
        val target = view ?: return
        if (!enabled()) return
        // Dialogs use their host dim/blur and just establish legibility.
        // Never bounce the entire window: that makes sheets and keyboards
        // feel detached from the user's touch.
        target.animate().cancel()
        target.alpha = 0f
        target.animate().alpha(1f).setDuration(135L)
            .setInterpolator(soft).start()
    }
}
