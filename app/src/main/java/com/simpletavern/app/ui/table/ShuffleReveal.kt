package com.simpletavern.app.ui.table

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.simpletavern.app.ui.card.CardBack
import com.simpletavern.app.ui.theme.LocalTable

private const val Cards = 10

/** Card m starts its riffle at this point of the opening, and takes [FlyTime] to land. */
private fun startOf(m: Int) = 0.02f + m * 0.036f
private const val FlyTime = 0.2f
private const val SquareAt = 0.5f
private const val RevealAt = 0.64f

private fun smooth(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Small fixed disorder for the stack, so it looks hand-gathered. */
private fun jitter(m: Int): Float = (((m * 37 + 11) % 11) - 5) / 5f

/**
 * Settings open the way a dealer starts a game: card backs riffle in from both sides and gather in the middle,
 * the stack squares up, and its top card is lifted and turned over to show the page. Closing plays it backwards.
 * [progress] runs 0 (table) to 1 (page fully shown); the caller drives it.
 */
@Composable
fun ShuffleReveal(progress: Animatable<Float, AnimationVector1D>, face: @Composable () -> Unit) {
    val t = LocalTable.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val p by remember { derivedStateOf { progress.value } }
    if (p <= 0f) return

    // A soft tick as each card lands, a firmer one when the top card turns.
    val landed by remember { derivedStateOf { (0 until Cards).count { progress.value >= startOf(it) + FlyTime } } }
    LaunchedEffect(landed) { if (landed in 1..Cards && progress.targetValue > progress.value) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val turned by remember { derivedStateOf { progress.value >= RevealAt + (1f - RevealAt) * 0.5f } }
    LaunchedEffect(turned) { if (turned) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val cardW = (maxWidth - 120.dp).coerceAtMost(300.dp)
        val cardH = cardW * 1.4f
        val cwPx = with(density) { cardW.toPx() }
        val chPx = with(density) { cardH.toPx() }
        // The page fills the screen inside the system bars and a 10dp margin, like every full card.
        val bars = WindowInsets.systemBars
        val margin = with(density) { 20.dp.toPx() }
        val faceW = w - bars.getLeft(density, LayoutDirection.Ltr) - bars.getRight(density, LayoutDirection.Ltr) - margin
        val faceH = h - bars.getTop(density) - bars.getBottom(density) - margin

        // The table slides over whatever was there.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = smooth(progress.value / 0.22f) }
                .background(t.table)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        )

        for (m in 0 until Cards - 1) {
            CardBack(
                Modifier
                    .align(Alignment.Center)
                    .size(cardW, cardH)
                    .zIndex(m.toFloat())
                    .graphicsLayer { riffle(m, progress.value, w, h, density.density) },
                mark = false,
            )
        }

        // The top card: lands last, then is lifted to full size and turned face up.
        val top = Cards - 1
        val faceUp by remember { derivedStateOf { reveal(progress.value) >= 0.5f } }
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(Cards.toFloat())
                .graphicsLayer {
                    val v = progress.value
                    val r = reveal(v)
                    if (r <= 0f) {
                        riffle(top, v, w, h, density.density)
                        scaleX = cwPx / faceW
                        scaleY = chPx / faceH
                    } else {
                        translationY = -top * 1.6f * density.density * (1f - r)
                        scaleX = mix(cwPx / faceW, 1f, r) * (1f + 0.05f * kotlin.math.sin(Math.PI.toFloat() * r))
                        scaleY = mix(chPx / faceH, 1f, r) * (1f + 0.05f * kotlin.math.sin(Math.PI.toFloat() * r))
                        val a = 180f * (1f - r)
                        rotationY = if (a > 90f) a - 180f else a
                        cameraDistance = 14f * density.density
                    }
                },
        ) {
            if (faceUp) {
                face()
            } else {
                CardBack(Modifier.fillMaxSize().systemBarsPadding().padding(10.dp))
            }
        }
    }
}

private fun reveal(p: Float) = smooth((p - RevealAt) / (1f - RevealAt))

/** Pose of riffled card m at progress p: in from its side on an arc, then squared into the stack, then sunk away. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.riffle(m: Int, p: Float, w: Float, h: Float, dp: Float) {
    val side = if (m % 2 == 0) -1f else 1f
    val u = smooth((p - startOf(m)) / FlyTime)
    val q = smooth((p - SquareAt) / (RevealAt - SquareAt))
    val sink = reveal(p)
    val restX = jitter(m) * 7f * dp * (1f - q)
    val restRot = jitter(m + 3) * 5f * (1f - q)
    translationX = mix(side * w * 0.72f, restX, u)
    translationY = mix(h * 0.42f, -m * 1.6f * dp, u) - 60f * dp * kotlin.math.sin(Math.PI.toFloat() * u) + 48f * dp * sink
    rotationZ = mix(side * -38f, restRot, u)
    rotationX = 14f * (1f - u)
    cameraDistance = 12f * dp
    alpha = if (u <= 0f) 0f else 1f - sink
    val s = 1f - 0.08f * sink
    scaleX = s
    scaleY = s
}

/** Opening and closing speeds for [ShuffleReveal]. */
suspend fun Animatable<Float, AnimationVector1D>.shuffleOpen() = animateTo(1f, tween(((1f - value) * 1250).toInt(), easing = LinearEasing))
suspend fun Animatable<Float, AnimationVector1D>.shuffleClose() = animateTo(0f, tween((value * 700).toInt(), easing = LinearEasing))
