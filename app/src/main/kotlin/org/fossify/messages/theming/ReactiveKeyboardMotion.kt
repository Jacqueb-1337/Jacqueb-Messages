package org.fossify.messages.theming

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import kotlin.math.abs

/** A driven spring, not a clip played after the IME finishes. Pixel travel
 * supplies the force each frame. On reversal the existing position/velocity
 * continue; at rest the driving force vanishes and the same springs settle. */
class ReactiveKeyboardMotion(private val rows: ViewGroup, private val extras: () -> List<View> = { emptyList() }) : Choreographer.FrameCallback {
    private data class Body(var x: Float = 0f, var v: Float = 0f, var weight: Float = 1f)
    private val bodies = LinkedHashMap<View, Body>()
    private val rect = Rect()
    private val density = rows.resources.displayMetrics.density
    private var previousIme: Int? = null
    private var previousInputTime = 0L
    private var inputVelocity = 0f
    private var lastFrame = 0L
    private var scheduled = false
    var drivenFrames = 0; private set
    fun frame(ime: Int) {
        val now = SystemClock.uptimeMillis()
        val old = previousIme
        previousIme = ime
        if (old != null && now - previousInputTime in 1..100) {
            inputVelocity = ((ime-old)*1000f/(now-previousInputTime)).coerceIn(-density*3500f,density*3500f)
            if (ime != old) drivenFrames++
        }
        previousInputTime = now
        if (!ValueAnimator.areAnimatorsEnabled()) { reset(); return }
        var rank = 0
        for (v in extras() + (rows.childCount-1 downTo 0).map { rows.getChildAt(it) }) {
            if (!v.isAttachedToWindow || !v.getGlobalVisibleRect(rect)) continue
            bodies.getOrPut(v) { Body() }.weight = (1f - rank++*.035f).coerceAtLeast(.32f)
            if (rank >= 28) break
        }
        if (!scheduled && bodies.isNotEmpty()) {
            scheduled = true; lastFrame = 0; Choreographer.getInstance().postFrameCallback(this)
        }
    }
    fun finish() { inputVelocity = 0f; previousIme = null }
    override fun doFrame(time: Long) {
        if (!rows.isAttachedToWindow) { reset(); return }
        val dt = if (lastFrame == 0L) 1f/60 else ((time-lastFrame)/1e9f).coerceIn(.001f,.032f)
        lastFrame = time
        if (SystemClock.uptimeMillis()-previousInputTime > 45L) inputVelocity = 0f
        val drive = (inputVelocity*.009f).coerceIn(-density*17,density*17)
        var alive = abs(drive) > .1f
        val iterator = bodies.iterator()
        while (iterator.hasNext()) {
            val (view,b) = iterator.next()
            if (!view.isAttachedToWindow) { view.translationY=0f;iterator.remove();continue }
            val stiffness = 400f + (1f-b.weight)*220f
            val damping = 2f*.62f*kotlin.math.sqrt(stiffness)
            // Small substeps remain stable on slower phones and dropped frames.
            repeat(4) {
                b.v += (stiffness*(drive*b.weight-b.x)-damping*b.v)*dt/4f
                b.x += b.v*dt/4f
            }
            view.translationY = b.x
            alive = alive || abs(b.x)>.15f || abs(b.v)>1f
        }
        if (alive) Choreographer.getInstance().postFrameCallback(this) else reset()
    }
    fun reset() {
        Choreographer.getInstance().removeFrameCallback(this)
        bodies.keys.forEach { it.translationY=0f }
        bodies.clear(); scheduled=false; lastFrame=0; inputVelocity=0f
    }
}
