package com.simpletavern.app.ui.deck

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.simpletavern.app.ui.card.CardBack
import com.simpletavern.app.ui.card.CardFrame
import com.simpletavern.app.ui.card.CornerIndex
import com.simpletavern.app.ui.card.Suit
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The card being drawn for a new message.
 *
 * The far part stays face-down in the pile as a small tab; the near part is pinched and curled up
 * toward the camera on a hinge, turning face-up as the writing surface. Press it down and flick it
 * up to play the card; ✕ puts it back.
 */
@Composable
fun DrawnCard(
    rank: String,
    draft: String,
    onDraft: (String) -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onSend: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val flip = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    var pressed by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        flip.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        runCatching { focus.requestFocus() }
    }

    val maxPress = with(density) { 56.dp.toPx() }
    val flyAway = with(density) { 800.dp.toPx() }
    val throwState = rememberDraggableState { delta ->
        pressed = if (delta > 0) (pressed + delta * 0.5f).coerceAtMost(maxPress) else pressed + delta
        scope.launch { fly.snapTo(pressed) }
    }

    Column(modifier) {
        // Far part: still lying in the pile.
        CardBack(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    rotationX = 58f * DeckGeometry.TILT
                    cameraDistance = DeckGeometry.CAMERA * this.density
                },
            mark = false,
        )
        // Near part: curls up on a hinge at its top edge.
        val p = flip.value
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    cameraDistance = DeckGeometry.CAMERA * this.density
                    rotationX = DeckGeometry.TILT * if (p < 0.5f) lerp(58f, 90f, p / 0.5f) else lerp(-90f, -6f, (p - 0.5f) / 0.5f)
                    translationY = fly.value
                    alpha = 1f - (-fly.value / (size.height * 1.2f)).coerceIn(0f, 1f)
                }
                .draggable(
                    state = throwState,
                    orientation = Orientation.Vertical,
                    enabled = p >= 1f,
                    onDragStarted = { pressed = fly.value },
                    onDragStopped = { v ->
                        if (v < -1400f && draft.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            fly.animateTo(-flyAway, tween(260))
                            if (!onSend(draft)) fly.animateTo(0f, spring(dampingRatio = 0.7f))
                        } else {
                            fly.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
                        }
                        pressed = 0f
                    },
                ),
        ) {
            if (p < 0.5f) {
                CardBack(Modifier.fillMaxSize(), mark = true)
            } else {
                ComposerFace(rank, draft, onDraft, focus, onClose, onExpand, onSend = { onSend(draft) })
            }
        }
    }
}

@Composable
private fun ComposerFace(
    rank: String,
    draft: String,
    onDraft: (String) -> Unit,
    focus: FocusRequester,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onSend: () -> Unit,
) {
    val t = LocalTable.current
    CardFrame(Modifier.fillMaxSize()) {
        CornerIndex(rank, Suit.Spade, Modifier.align(Alignment.TopStart).padding(start = 15.dp, top = 13.dp))
        Column(Modifier.fillMaxSize().padding(start = 42.dp, top = 22.dp, end = 26.dp, bottom = 18.dp)) {
            BasicText("你 ❦ 新牌", style = Type.who.copy(color = t.goldDeep))
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = Type.body.copy(color = t.ink),
                    cursorBrush = SolidColor(t.red),
                    modifier = Modifier.fillMaxSize().focusRequester(focus),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) BasicText("写下这一张牌…", style = Type.body.copy(color = t.ink.copy(alpha = 0.35f)))
                        inner()
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("✕", onClose)
                Spacer(Modifier.weight(1f))
                Pill("⤢ 全屏", onExpand)
                Pill("出牌 ♠", onSend, filled = true)
            }
        }
    }
}

@Composable
internal fun Pill(text: String, onClick: () -> Unit, filled: Boolean = false, modifier: Modifier = Modifier) {
    val t = LocalTable.current
    val shape = RoundedCornerShape(999.dp)
    BasicText(
        text,
        style = Type.pill.copy(color = if (filled) t.paper else t.goldDeep),
        modifier = modifier
            .clip(shape)
            .then(if (filled) Modifier.background(t.ink) else Modifier.border(1.dp, t.gold, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
