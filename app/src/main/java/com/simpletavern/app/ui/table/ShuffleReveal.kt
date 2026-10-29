package com.simpletavern.app.ui.table

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.simpletavern.app.ui.card.CardBack
import com.simpletavern.app.ui.deck.DeckGeometry
import com.simpletavern.app.ui.theme.LocalTable
import kotlin.math.PI
import kotlin.math.sin

/**
 * A card brought to the front with some ceremony and put away again. [open] runs the entrance 0..1;
 * [leave] runs the exit 0..1 from a fully open card. Both are read only in draw/layer lambdas, so the
 * animation never recomposes the page on the card.
 */
@Stable
class Reveal {
    val open = Animatable(0f)
    val leave = Animatable(0f)

    val visible: Boolean get() = open.value > 0f

    suspend fun show(ms: Int) {
        leave.snapTo(0f)
        open.animateTo(1f, tween(((1f - open.value) * ms).toInt(), easing = LinearEasing))
    }

    /** Exit by its own choreography when fully open; mid-entrance it simply plays back. */
    suspend fun hide(ms: Int, backMs: Int) {
        if (open.value >= 1f) {
            leave.animateTo(1f, tween(ms, easing = LinearEasing))
            open.snapTo(0f)
            leave.snapTo(0f)
        } else {
            open.animateTo(0f, tween((open.value * backMs).toInt(), easing = LinearEasing))
        }
    }

    /** Plays the entrance backwards to close. */
    suspend fun rewind(ms: Int) {
        leave.snapTo(0f)
        open.animateTo(0f, tween((open.value * ms).toInt(), easing = LinearEasing))
    }
}

internal fun smooth(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun jitter(m: Int): Float = (((m * 37 + 11) % 11) - 5) / 5f

/** Geometry shared by both entrances: small cards for the piles, and one large face card to end on. */
private class Stage(val w: Float, val h: Float, val cardW: Float, val faceW: Float, val centerY: Float, val dp: Float) {
    val faceH = faceW / DeckGeometry.RATIO
    /** Scale that turns the face-sized layer into a pile card. */
    val small = cardW / faceW
}

@Composable
private fun StageBox(content: @Composable Stage.() -> Unit) {
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val dp = density.density
        val top = bars.getTop(density).toFloat()
        val bottom = bars.getBottom(density).toFloat()
        // The face card fills the width, as long as it also fits between the system bars.
        val faceW = minOf(w - 24 * dp, (h - top - bottom - 24 * dp) * DeckGeometry.RATIO)
        val stage = Stage(w, h, cardW = minOf(w * 0.44f, 200 * dp), faceW = faceW, centerY = top + (h - top - bottom) / 2f, dp = dp)
        stage.content()
    }
}

/** A face-sized card layer centred on the stage; transforms scale it down to a pile card. */
@Composable
private fun Stage.CardLayer(z: Float, pose: GraphicsLayerScope.() -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(z)
            .graphicsLayer {
                translationY = centerY - h / 2f
                pose()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(with(density) { faceW.toDp() }, with(density) { faceH.toDp() })) { content() }
    }
}

private const val Cascade = 12

/** Card m of the cascade starts to fall here, and falls for [FallTime]. */
private fun releaseOf(m: Int) = 0.1f + m * 0.034f
private const val FallTime = 0.17f
private const val SquareAt = 0.63f
private const val TurnAt = 0.7f

/**
 * Settings open like a cascade between two hands: a packet held high lets its cards drop one after another,
 * flapping as they fall, into a pile below; the pile squares up and its top card is lifted and turned over to
 * show the page. Closing turns the page back face down, and the whole pile drops off the table.
 */
@Composable
fun CascadeReveal(reveal: Reveal, face: @Composable () -> Unit) {
    val t = LocalTable.current
    val haptic = LocalHapticFeedback.current
    val shown by remember { derivedStateOf { reveal.open.value > 0f } }
    if (!shown) return

    val landed by remember { derivedStateOf { (0 until Cascade).count { reveal.open.value >= releaseOf(it) + FallTime } } }
    LaunchedEffect(landed) {
        if (landed in 1..Cascade && reveal.open.isRunning && reveal.open.targetValue > reveal.open.value)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val faceUp by remember { derivedStateOf { turnOf(reveal) >= 0.5f } }
    LaunchedEffect(faceUp) { if (faceUp && reveal.open.isRunning) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    StageBox {
        // The table cloth slides over whatever was there.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = smooth(reveal.open.value / 0.14f) * (1f - smooth((reveal.leave.value - 0.45f) / 0.55f)) }
                .background(t.table)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        )
        for (m in 0 until Cascade - 1) {
            CardLayer(m.toFloat(), pose = { cascadePose(m, reveal, this@StageBox) }) { CardBack(Modifier.fillMaxSize(), mark = false) }
        }
        // The top card: last to fall, then lifted to full size and turned face up.
        CardLayer(Cascade.toFloat(), pose = {
            val r = turnOf(reveal)
            if (r <= 0f) {
                cascadePose(Cascade - 1, reveal, this@StageBox)
            } else {
                val s = mix(small, 1f, r) * (1f + 0.04f * sin(PI.toFloat() * r))
                scaleX = s; scaleY = s
                translationY += mix(stackY(Cascade - 1), 0f, r) + dropOf(reveal)
                val a = 180f * (1f - r)
                rotationY = if (a > 90f) a - 180f else a
                cameraDistance = 14f * density
            }
        }) {
            if (faceUp) face() else CardBack(Modifier.fillMaxSize())
        }
    }
}

/** Turn of the top card: 0 face down on the pile, 1 face up at full size. Leaving runs it back first. */
private fun turnOf(r: Reveal): Float =
    if (r.leave.value > 0f) 1f - smooth(r.leave.value / 0.42f) else smooth((r.open.value - TurnAt) / (1f - TurnAt))

/** The whole pile falling off the table on the way out. */
private fun Stage.dropOf(r: Reveal): Float {
    val x = ((r.leave.value - 0.36f) / 0.64f).coerceIn(0f, 1f)
    return x * x * h * 1.15f
}

/** Where card m rests in the lower pile, relative to the stage centre. */
private fun Stage.stackY(m: Int) = h * 0.06f - m * 1.5f * dp

/** Pose of cascade card m: held in the upper packet, falling and flapping, resting in the lower pile, gone on exit. */
private fun GraphicsLayerScope.cascadePose(m: Int, r: Reveal, s: Stage) {
    val o = r.open.value
    val upperY = -s.h * 0.30f + (Cascade - 1 - m) * 1.3f * s.dp
    val arrive = smooth(o / 0.1f)
    val held = mix(-s.h * 0.9f, upperY, arrive)
    val u = ((o - releaseOf(m)) / FallTime).coerceIn(0f, 1f)
    val q = smooth((o - SquareAt) / (TurnAt - SquareAt))
    val sink = smooth((o - TurnAt) / (1f - TurnAt))
    val restX = jitter(m) * 6f * s.dp * (1f - q)
    // Gravity: slow off the packet, fast into the pile.
    val fall = u * u
    translationX += mix(0f, restX, u)
    translationY += mix(held, s.stackY(m), fall) + s.dropOf(r) + 24f * s.dp * sink
    rotationX = 50f * (1f - u) - 38f * sin(PI.toFloat() * u)
    rotationZ = mix(-3f, jitter(m + 3) * 3f * (1f - q), u) + 6f * (s.dropOf(r) / s.h)
    cameraDistance = 12f * s.dp
    val sc = s.small * (1f - 0.06f * sink)
    scaleX = sc
    scaleY = sc
    alpha = if (r.leave.value > 0f) 1f else 1f - 0.6f * sink
}

private const val Drop = 10
private const val FallFrom = 0.12f
private const val LandAt = 0.44f
private const val PushFrom = 0.5f
private const val PushTo = 0.82f

/**
 * A new character arrives as a deck dropped onto the table: everything else fades, the deck falls from above and
 * lands with a bump, the camera pushes in until its top card fills the view, and that card turns over to be the
 * character card. Closing plays the whole thing back: the card turns down, the camera pulls out, the deck rises away.
 */
@Composable
fun DropReveal(reveal: Reveal, face: @Composable () -> Unit) {
    val t = LocalTable.current
    val haptic = LocalHapticFeedback.current
    val shown by remember { derivedStateOf { reveal.open.value > 0f } }
    if (!shown) return
    val down by remember { derivedStateOf { reveal.open.value >= LandAt } }
    LaunchedEffect(down) { if (down && reveal.open.isRunning && reveal.open.targetValue > reveal.open.value) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val faceUp by remember { derivedStateOf { smooth((reveal.open.value - PushTo) / (1f - PushTo)) >= 0.5f } }
    LaunchedEffect(faceUp) { if (faceUp && reveal.open.isRunning) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    StageBox {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = smooth(reveal.open.value / 0.18f) }
                .background(t.table)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        )
        for (m in 0 until Drop) {
            val top = m == Drop - 1
            CardLayer(m.toFloat(), pose = { dropPose(m, reveal.open.value, this@StageBox, top) }) {
                if (top && faceUp) face() else CardBack(Modifier.fillMaxSize(), mark = top)
            }
        }
    }
}

private fun GraphicsLayerScope.dropPose(m: Int, o: Float, s: Stage, top: Boolean) {
    // Fall: from above the screen, accelerating, onto the table.
    val f = ((o - FallFrom) / (LandAt - FallFrom)).coerceIn(0f, 1f)
    val fallY = mix(-s.h * 1.1f, 0f, f * f)
    // The bump of landing: a short squash and a scatter that settles.
    val b = ((o - LandAt) / 0.08f).coerceIn(0f, 1f)
    val bump = if (o >= LandAt) sin(PI.toFloat() * b) else 0f
    // Push-in: the camera comes down square over the deck until its top card fills the view.
    val k = smooth((o - PushFrom) / (PushTo - PushFrom))
    val turn = smooth((o - PushTo) / (1f - PushTo))
    val stack = -m * 1.6f * s.dp
    translationX += jitter(m) * 3f * s.dp * bump * (1f - k)
    translationY += fallY + stack * (1f - k) + s.h * 0.04f * (1f - k)
    rotationX = 46f * (1f - k)
    rotationZ = (-12f + jitter(m + 5) * 1.5f * bump) * (1f - k)
    cameraDistance = 14f * s.dp
    val grow = if (top) mix(s.small, 1f, k) else mix(s.small, s.small * 2.6f, k)
    scaleX = grow * (1f + 0.02f * bump)
    scaleY = grow * (1f - 0.03f * bump)
    alpha = if (o < FallFrom) 0f else if (top) 1f else 1f - k
    if (top) {
        val a = 180f * (1f - turn)
        rotationY = if (turn <= 0f) 0f else if (a > 90f) a - 180f else a
    }
}
