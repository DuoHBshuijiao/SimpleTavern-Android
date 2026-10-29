package com.simpletavern.app.ui.deck

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.simpletavern.app.ui.theme.LocalTable
import kotlin.math.PI
import kotlin.math.sin

/**
 * A card picked up off the deck: the full-screen card grows out of the card that was tapped,
 * tipping toward the camera as it rises, and settles back onto it when put down.
 * [from] is that card's bounds in root coordinates; without it the card rises from the middle.
 */
@Composable
internal fun LiftOverlay(visible: Boolean, from: Rect?, content: @Composable () -> Unit) {
    val t = LocalTable.current
    val lift = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            shown = true
            lift.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f))
        } else if (shown) {
            lift.animateTo(0f, tween(260))
            shown = false
        }
    }
    if (!shown && !visible) return

    var origin by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = Rect(it.positionInRoot(), it.size.toSize()) }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = lift.value.coerceIn(0f, 1f) }.background(t.table))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val a = lift.value
                    val r = from ?: Rect(origin.center.x - origin.width * 0.4f, origin.center.y - origin.height * 0.25f,
                        origin.center.x + origin.width * 0.4f, origin.center.y + origin.height * 0.25f)
                    val w = origin.width.coerceAtLeast(1f)
                    val h = origin.height.coerceAtLeast(1f)
                    scaleX = r.width / w + (1f - r.width / w) * a
                    scaleY = r.height / h + (1f - r.height / h) * a
                    translationX = (r.center.x - origin.center.x) * (1f - a)
                    translationY = (r.center.y - origin.center.y) * (1f - a)
                    rotationX = -10f * sin(PI.toFloat() * a.coerceIn(0f, 1f))
                    cameraDistance = 12f * density
                },
        ) { content() }
    }
}

private fun androidx.compose.ui.unit.IntSize.toSize() = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())
