package com.simpletavern.app.ui.deck

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Where a card sits in the pulled-up deck.
 *
 * d is the card's distance from the front card: negative = older (above), positive = newer / draw pile (below).
 * Upper cards tip their top toward the camera, lower cards tip their bottom toward it; past three slots
 * they are nearly edge-on, which reads as the thin card edges of the sketch.
 */
object DeckGeometry {
    /** Flip this if a device renders the tilt backwards (Compose rotationX: positive = top edge away). */
    const val TILT = 1f

    /**
     * Perspective camera distance for tilted cards, multiplied by the screen density where used.
     * Smaller = stronger perspective; 14 puts the camera about three and a half card widths away, as in the prototype.
     */
    const val CAMERA = 14f

    /** Poker proportions, 63 × 88 mm. */
    const val RATIO = 63f / 88f

    /** Where the front card's centre sits, as a fraction of the screen height. */
    const val FRONT_Y = 0.42f

    /** Card size for a screen. It depends on the whole screen only, so the keyboard never resizes cards. */
    fun cardSize(screenW: Dp, screenH: Dp): DpSize {
        var w = minOf(screenW * 0.74f, 380.dp)
        var h = w / RATIO
        if (h > screenH * 0.5f) { h = screenH * 0.5f; w = h * RATIO }
        return DpSize(w, h)
    }

    /** Pixel distance of a RenderNode camera set with cameraDistance = [value]: that many inches at 72 px each. */
    fun cameraPx(value: Float): Float = value * 72f

    private val ys = floatArrayOf(0f, 0.83f, 1.33f, 1.56f)
    private val rs = floatArrayOf(0f, 52f, 70f, 84f)

    /** @return vertical offset in card heights, and signed tilt in degrees. */
    fun slot(d: Float): Pair<Float, Float> {
        val a = abs(d)
        val s = if (d < 0) -1f else 1f
        if (a >= 3f) return s * (1.56f + (a - 3f) * 0.03f) to s * 84f
        val i = a.toInt()
        val t = a - i
        return s * (ys[i] + (ys[i + 1] - ys[i]) * t) to s * (rs[i] + (rs[i + 1] - rs[i]) * t)
    }

    fun dim(d: Float): Float = minOf(0.5f, abs(d) * 0.09f)

    /** Rubber-band past the ends of the deck. */
    fun elastic(v: Float, max: Float): Float = when {
        v < 0f -> v * 0.3f
        v > max -> max + (v - max) * 0.3f
        else -> v
    }
}

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
