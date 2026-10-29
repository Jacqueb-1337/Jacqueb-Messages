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

    /** Window cutout reveal. The conversation layout never changes scale or
     * position: only a rounded outline clips what is visible, exposing the
     * already-rendered conversation list in the translucent Activity below. */
    private fun threadMask(surface: View, origin: android.graphics.Rect,
                           opening: Boolean, onEnd: () -> Unit = {}) {
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0) {
            onEnd()
            return
        }
        surface.animate().cancel()
        surface.scaleX = 1f
        surface.scaleY = 1f
        surface.translationX = 0f
        surface.translationY = 0f
        surface.alpha = 1f
        val screen = IntArray(2)
        surface.getLocationOnScreen(screen)
        val width = surface.width
        val height = surface.height
        val start = android.graphics.Rect(
            (origin.left - screen[0]).coerceIn(0, width - 1),
            (origin.top - screen[1]).coerceIn(0, height - 1),
            (origin.right - screen[0]).coerceIn(1, width),
            (origin.bottom - screen[1]).coerceIn(1, height)
        )
        if (start.right <= start.left) start.right = (start.left + 1).coerceAtMost(width)
        if (start.bottom <= start.top) start.bottom = (start.top + 1).coerceAtMost(height)
        val full = android.graphics.Rect(0, 0, width, height)
        val radius = dp(surface, 18f)
        var fraction = if (opening) 0f else 1f
        surface.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                fun blend(a: Int, b: Int): Int = (a + (b - a) * fraction).toInt()
                val left = blend(start.left, full.left)
                val top = blend(start.top, full.top)
                val right = blend(start.right, full.right).coerceAtLeast(left + 1)
                val bottom = blend(start.bottom, full.bottom).coerceAtLeast(top + 1)
                outline.setRoundRect(left, top, right, bottom, radius * (1f - fraction))
            }
        }
        surface.clipToOutline = true
        surface.invalidateOutline()
        ValueAnimator.ofFloat(if (opening) 0f else 1f,
                              if (opening) 1f else 0f).apply {
            duration = if (opening) 230L else 175L
            interpolator = if (opening) PathInterpolator(.18f, .68f, .22f, 1f)
                           else PathInterpolator(.38f, .02f, .76f, .48f)
            addUpdateListener {
                fraction = it.animatedValue as Float
                surface.invalidateOutline()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (opening) {
                        surface.clipToOutline = false
                        surface.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        surface.invalidateOutline()
                    }
                    onEnd()
                }
            })
            start()
        }
    }

    fun threadEnter(activity: Activity, surface: View, origin: android.graphics.Rect?) {
        if (!enabled() || origin == null || origin.isEmpty) return
        // Do not show one fully-unmasked frame before the first layout pass.
        surface.alpha = 0f
        surface.post {
            if (surface.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed)
                threadMask(surface, origin, opening = true)
        }
    }

    fun threadExit(surface: View, origin: android.graphics.Rect?, finish: () -> Unit) {
        if (!enabled() || origin == null || origin.isEmpty || !surface.isAttachedToWindow) {
            finish()
            return
        }
        threadMask(surface, origin, opening = false, onEnd = finish)
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
