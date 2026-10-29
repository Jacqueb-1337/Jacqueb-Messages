package org.fossify.messages.chatheads

import kotlin.math.abs
import kotlin.math.min

/**
 * Independent reimplementation of the physical mathematics extracted from
 * Messenger 584.1.0.61.98's obfuscated spring classes X.5pu / X.4nS.
 *
 * F = k (target - x) - c velocity
 * k = (designerTension - 30) * 3.62 + 194
 * c = (designerFriction - 8) * 3 + 25
 *
 * The original uses a fixed 0.001 second fourth-order Runge-Kutta integration,
 * a 0.064 second cap on a single frame delta, accumulator interpolation,
 * and rest tolerances of 1e-4 for both displacement and velocity.
 *
 * These math parameters were verified in decompiled shipped code. This class
 * does not claim the separate fling/edge decisions in Messenger use identical settings.
 */
class MessengerSpring(
    designerTension: Double = 150.0,
    designerFriction: Double = 9.0,
    private val restDisplacement: Double = 1.0e-4,
    private val restVelocity: Double = 1.0e-4,
) {
    val tension = (designerTension - 30.0) * 3.62 + 194.0
    val friction = (designerFriction - 8.0) * 3.0 + 25.0

    var target: Double = 0.0
        private set
    var value: Double = 0.0
        private set
    var velocity: Double = 0.0
        private set

    private var previousPosition = 0.0
    private var previousVelocity = 0.0
    private var integrationPosition = 0.0
    private var integrationVelocity = 0.0
    private var accumulator = 0.0

    fun jump(to: Double) {
        target = to
        value = to
        velocity = 0.0
        integrationPosition = to
        integrationVelocity = 0.0
        previousPosition = to
        previousVelocity = 0.0
        accumulator = 0.0
    }

    fun animateTo(to: Double) {
        target = to
    }

    fun impartVelocity(pixelsPerSecond: Double) {
        velocity = pixelsPerSecond
    }

    /**
     * Advance simulation by elapsed wall-clock seconds; matches the original
     * 1ms RK4 substeps including its previous/temp state handling.
     */
    fun update(deltaSeconds: Double): Double {
        if (atRest()) return value
        accumulator += min(0.064, deltaSeconds.coerceAtLeast(0.0))
        val h = 0.001
        var x = value
        var v = velocity
        var tempX = integrationPosition
        var tempV = integrationVelocity
        while (accumulator >= h) {
            accumulator -= h
            if (accumulator < h) {
                previousPosition = x
                previousVelocity = v
            }

            val a1 = (target - tempX) * tension - friction * v
            val bVelocity = v + a1 * h * 0.5
            val a2 = (target - (x + v * h * 0.5)) * tension - friction * bVelocity
            val cVelocity = v + a2 * h * 0.5
            val a3 = (target - (x + bVelocity * h * 0.5)) * tension - friction * cVelocity
            tempX = x + cVelocity * h
            tempV = v + a3 * h

            x += (v + 2.0 * (bVelocity + cVelocity) + tempV) * (h / 6.0)
            v += (a1 + 2.0 * (a2 + a3) +
                ((target - tempX) * tension - friction * tempV)) * (h / 6.0)
        }
        integrationPosition = tempX
        integrationVelocity = tempV
        if (accumulator > 0.0) {
            val ratio = accumulator / h
            x = x * ratio + previousPosition * (1.0 - ratio)
            v = v * ratio + previousVelocity * (1.0 - ratio)
        }
        value = x
        velocity = v
        if (atRest()) {
            value = target
            velocity = 0.0
        }
        return value
    }

    fun atRest() = abs(velocity) <= restVelocity && abs(target - value) <= restDisplacement
}