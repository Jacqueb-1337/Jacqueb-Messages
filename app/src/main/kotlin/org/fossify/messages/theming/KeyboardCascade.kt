package org.fossify.messages.theming

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.exp
import kotlin.math.sin

/** A cascading impact on the actual visible message rows, not the window.
 * Starting at the composer / bottom message, every row gets a slightly delayed
 * damped spring impulse. The same surface works for a RecyclerView or a
 * LinearLayout, and consecutive keyboard transitions remain interruptible. */
object KeyboardCascade {
    private val running = java.util.WeakHashMap<ViewGroup, ValueAnimator>()

    fun play(container: ViewGroup, opening: Boolean, extras: List<View> = emptyList()) {
        running.remove(container)?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !container.isAttachedToWindow) return
        val ordered = ArrayList<View>()
        extras.filterTo(ordered) { it.isAttachedToWindow && it.visibility == View.VISIBLE }
        val scroller = container.parent as? ScrollView
        val visibleTop = scroller?.scrollY ?: 0
        val visibleBottom = visibleTop + (scroller?.height ?: container.height)
        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index)
            if (child.isAttachedToWindow && child.visibility == View.VISIBLE &&
                child.bottom > visibleTop && child.top < visibleBottom) {
                ordered.add(child)
            }
            if (ordered.size >= 18) break
        }
        if (ordered.isEmpty()) return
        val amplitude = container.resources.displayMetrics.density * 19f * if (opening) -1f else 1f
        ordered.forEach { it.animate().cancel(); it.translationY = 0f }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L
            addUpdateListener {
                val elapsed = it.animatedFraction
                ordered.forEachIndexed { index, child ->
                    if (!child.isAttachedToWindow) return@forEachIndexed
                    val delay = (index * .048f).coerceAtMost(.35f)
                    val t = ((elapsed - delay) / (1f - delay)).coerceIn(0f, 1f)
                    val bounce = sin(t * Math.PI * 4.2).toFloat() * exp(-6.3f * t)
                    child.translationY = amplitude * bounce
                    // Brief connected squash/extension gives weight to the wave.
                    child.pivotY = if (opening) child.height.toFloat() else 0f
                    child.scaleY = 1f - .012f * exp(-7f * t) *
                        kotlin.math.cos(t * Math.PI * 4.2).toFloat()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ordered.forEach { child -> child.translationY = 0f; child.scaleY = 1f }
                    if (running[container] === this@apply) running.remove(container)
                }
            })
        }
        running[container] = animator
        animator.start()
    }
}
