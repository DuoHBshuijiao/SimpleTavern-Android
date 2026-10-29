package com.simpletavern.app.ui.deck

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpletavern.app.ui.theme.Foil
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.TablePalette
import com.simpletavern.app.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The draw card, peeked at like a hole card: pinched along its whole near edge and rolled up toward the camera.
 *
 * Lengths are in card heights, measured from the far (top) edge, in the card's own plane before the pile tilt.
 * The far [HINGE] stays flat on the pile; the next stretch wraps a cylinder of [RADIUS]; the rest stays flat and
 * ends square to the camera, face toward the player, once the roll reaches [MAX] = 180° minus the pile tilt.
 */
internal object Curl {
    const val HINGE = 0.2f
    const val RADIUS = 0.1f
    /** Pile tilt while writing, degrees; the same as the first draw-pile slot. */
    const val PILE_TILT = 52f
    const val MAX = 180f - PILE_TILT
    private val maxRad = MAX * PI.toFloat() / 180f
    /** The writing face: the flat stretch after the roll. */
    val LIFTED = 1f - HINGE - RADIUS * maxRad
    /** Pile card centre while writing, in card heights below the front slot. */
    const val PILE_Y = 0.45f

    /** Point at distance [s] along the card for roll angle [phi] (radians): (y, z), z toward the card back's side. */
    fun point(s: Float, phi: Float): Pair<Float, Float> {
        val y0 = -0.5f + HINGE
        if (s <= HINGE) return (-0.5f + s) to 0f
        val u = s - HINGE
        val arc = RADIUS * phi
        if (u <= arc) {
            val th = u / RADIUS
            return (y0 + RADIUS * sin(th)) to (RADIUS * (1f - cos(th)))
        }
        val ye = y0 + RADIUS * sin(phi)
        val ze = RADIUS * (1f - cos(phi))
        val rest = u - arc
        return (ye + rest * cos(phi)) to (ze + rest * sin(phi))
    }

    /** Local angle of the card surface at [s], radians. */
    fun angle(s: Float, phi: Float): Float = if (s <= HINGE) 0f else minOf((s - HINGE) / RADIUS, phi)

    fun phi(progress: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        return maxRad * x * x * (3f - 2f * x)
    }
}

/**
 * Projects card-plane points to the screen the way a RenderNode with rotationX and cameraDistance does:
 * the card turns about its own centre, the camera sits on that centre's axis.
 */
internal class CurlProjector(
    val center: Offset,
    val wPx: Float,
    val hPx: Float,
    tiltDeg: Float,
    /** Camera distance in pixels (RenderNode cameraDistance × 72). */
    val camera: Float,
) {
    private val ca = cos(tiltDeg * PI.toFloat() / 180f)
    private val sa = sin(tiltDeg * PI.toFloat() / 180f)

    /** Screen position of the card point at x (pixels from centre) and (y, z) in card heights. */
    fun project(x: Float, y: Float, z: Float): Offset {
        // Bottom tips toward the camera: +y goes to (cos, sin) in (y', z'); the back's normal +z goes to (-sin, cos).
        val yy = (y * ca - z * sa) * hPx
        val zz = (y * sa + z * ca) * hPx
        val k = camera / (camera - zz)
        return Offset(center.x + x * k, center.y + yy * k)
    }

    private val tilt = tiltDeg * PI.toFloat() / 180f

    /** Whether the card back faces the camera where the surface lies at local angle [theta]. */
    fun backVisible(theta: Float): Boolean = cos(theta + tilt) > 0f

    /** Screen rect of the writing face once fully rolled: it is square to the camera, so a plain rect. */
    fun faceRect(): Rect {
        val phi = Curl.phi(1f)
        val (y1, z1) = Curl.point(1f, phi)
        val (y0, z0) = Curl.point(1f - Curl.LIFTED, phi)
        val top = project(-wPx / 2, y1, z1)
        val bottom = project(wPx / 2, y0, z0)
        return Rect(top.x, top.y, bottom.x, bottom.y)
    }
}

/** The rolling card itself: far part, roll and (until the roll completes) the writing face, as textured strips. */
@Composable
internal fun CurlCanvas(progress: () -> Float, projector: () -> CurlProjector?, rank: String, modifier: Modifier = Modifier) {
    val t = LocalTable.current
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer()
    val p0 = projector() ?: return
    val textures = remember(p0.wPx, p0.hPx, t, rank) { CurlTextures.make(p0.wPx, p0.hPx, t, density, dir, measurer, rank) }
    val matrix = remember { Matrix() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val shade = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val src = remember { FloatArray(8) }
    val dst = remember { FloatArray(8) }

    Canvas(modifier) {
        val pr = projector() ?: return@Canvas
        val c = progress()
        if (c <= 0f) return@Canvas
        val phi = Curl.phi(c)
        val arc = Curl.RADIUS * phi
        // Far part, the roll in fine strips, then the lifted part (drawn by the live face once fully rolled).
        val cuts = ArrayList<Float>(24)
        cuts += 0f
        cuts += Curl.HINGE
        val steps = 14
        for (i in 1..steps) cuts += Curl.HINGE + arc * i / steps
        if (c < 1f) cuts += 1f
        val w = pr.wPx
        val h = pr.hPx
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (i in 0 until cuts.size - 1) {
                val a = cuts[i]
                val b = cuts[i + 1]
                if (b - a < 1e-4f) continue
                val (ya, za) = Curl.point(a, phi)
                val (yb, zb) = Curl.point(b, phi)
                val tl = pr.project(-w / 2, ya, za)
                val tr = pr.project(w / 2, ya, za)
                val br = pr.project(w / 2, yb, zb)
                val bl = pr.project(-w / 2, yb, zb)
                dst[0] = tl.x; dst[1] = tl.y; dst[2] = tr.x; dst[3] = tr.y
                dst[4] = br.x; dst[5] = br.y; dst[6] = bl.x; dst[7] = bl.y
                val theta = Curl.angle((a + b) / 2, phi)
                val back = pr.backVisible(theta)
                // The face texture is laid out for reading with the near edge at the top.
                val sa = if (back) a * h else (1f - a) * h
                val sb = if (back) b * h else (1f - b) * h
                src[0] = 0f; src[1] = sa; src[2] = w; src[3] = sa
                src[4] = w; src[5] = sb; src[6] = 0f; src[7] = sb
                if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) continue
                nc.save()
                nc.concat(matrix)
                val top = minOf(sa, sb)
                val bottom = maxOf(sa, sb)
                // A hair of overlap hides seams between strips.
                nc.clipRect(RectF(0f, top - 0.6f, w, bottom + 0.6f))
                nc.drawBitmap((if (back) textures.back else textures.face).asAndroidBitmap(), 0f, 0f, paint)
                // Light falls from above: the roll darkens as it turns away, the inside of the curl most.
                val turn = cos(theta + PI.toFloat() * Curl.PILE_TILT / 180f)
                val dark = if (back) (1f - turn).coerceIn(0f, 1f) * 0.45f else (1f + turn).coerceIn(0f, 1f) * 0.35f
                if (dark > 0.01f) {
                    shade.color = Color.Black.copy(alpha = dark).toArgb()
                    nc.drawRect(RectF(0f, top, w, bottom), shade)
                }
                nc.restore()
            }
        }
    }
}

private class CurlTextures(val back: ImageBitmap, val face: ImageBitmap) {
    companion object {
        fun make(w: Float, h: Float, t: TablePalette, density: Density, dir: LayoutDirection, measurer: TextMeasurer, rank: String): CurlTextures {
            fun paint(block: DrawScope.() -> Unit): ImageBitmap {
                val bmp = ImageBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
                CanvasDrawScope().draw(density, dir, GCanvas(bmp), Size(w, h), block)
                return bmp
            }
            val back = paint {
                val r = 14.dp.toPx()
                drawRoundRect(Foil, cornerRadius = CornerRadius(r))
                val inset = 2.dp.toPx()
                drawRoundRect(t.cloth, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(r - inset))
                clipRect(inset, inset, size.width - inset, size.height - inset) {
                    val step = 9.dp.toPx() * 1.414f
                    val line = t.gold.copy(alpha = 0.4f)
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(line, Offset(x, 0f), Offset(x + size.height, size.height), 1.dp.toPx())
                        drawLine(line, Offset(x + size.height, 0f), Offset(x, size.height), 1.dp.toPx())
                        x += step
                    }
                }
                val mark = measurer.measure("❦", Type.title.copy(fontSize = 54.sp, color = t.gold))
                drawText(mark, topLeft = Offset((size.width - mark.size.width) / 2, (size.height - mark.size.height) / 2))
            }
            val face = paint { drawWritingFace(t, measurer, rank, placeholder = true) }
            return CurlTextures(back, face)
        }
    }
}

/** Paper, foil and corner index of the writing face, laid out with the card's near edge at the top. */
private fun DrawScope.drawWritingFace(t: TablePalette, measurer: TextMeasurer, rank: String, placeholder: Boolean) {
    val r = 14.dp.toPx()
    drawRoundRect(Foil, cornerRadius = CornerRadius(r))
    val inset = 2.dp.toPx()
    drawRoundRect(t.paper, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(r - inset))
    val rule = 7.dp.toPx()
    drawRoundRect(t.gold.copy(alpha = 0.85f), Offset(rule, rule), Size(size.width - 2 * rule, size.height - 2 * rule),
        CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
    val idx = measurer.measure(rank, Type.index.copy(color = t.ink))
    drawText(idx, topLeft = Offset(15.dp.toPx(), 13.dp.toPx()))
    val suit = measurer.measure("♠", Type.index.copy(fontSize = 16.sp, color = t.ink))
    drawText(suit, topLeft = Offset(15.dp.toPx() + (idx.size.width - suit.size.width) / 2f, 13.dp.toPx() + idx.size.height))
    val who = measurer.measure("你 ❦ 新牌", Type.who.copy(color = t.goldDeep))
    drawText(who, topLeft = Offset(42.dp.toPx(), 22.dp.toPx()))
    if (placeholder) {
        val ph = measurer.measure("写下这一张牌…", Type.body.copy(color = t.ink.copy(alpha = 0.35f)))
        drawText(ph, topLeft = Offset(42.dp.toPx(), 22.dp.toPx() + who.size.height + 6.dp.toPx()))
    }
}

/**
 * The live writing face, laid exactly over where the rolled card ends. Press it down and flick it up to play
 * the card; ✕ rolls it back onto the pile.
 */
@Composable
internal fun WritingFace(
    rect: () -> Rect?,
    stageOrigin: () -> Offset,
    cardW: Float,
    cardH: Float,
    rank: String,
    draft: String,
    onDraft: (String) -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onSend: (String) -> Boolean,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val t = LocalTable.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fly = remember { Animatable(0f) }
    var pressed by remember { mutableFloatStateOf(0f) }
    val maxPress = with(density) { 48.dp.toPx() }
    val flyAway = with(density) { 900.dp.toPx() }
    val throwState = rememberDraggableState { delta ->
        pressed = if (delta > 0) (pressed + delta * 0.5f).coerceAtMost(maxPress) else pressed + delta
        scope.launch { fly.snapTo(pressed) }
    }
    val r = rect() ?: return
    // Laid out at full card scale, then shrunk to the projected size.
    val heightPx = Curl.LIFTED * cardH
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    Box(
        modifier
            .graphicsLayer {
                val rr = rect() ?: r
                val o = stageOrigin()
                val s = rr.width / cardW
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = rr.left - o.x
                translationY = rr.top - o.y + fly.value
                scaleX = s
                scaleY = s
                alpha = 1f - (-fly.value / flyAway * 1.6f).coerceIn(0f, 1f)
            }
            .layoutSize(cardW, heightPx)
            .clip(shape)
            .background(Foil)
            .padding(start = 2.dp, end = 2.dp, top = 2.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(t.paper)
            .drawBehind {
                val inset = 7.dp.toPx()
                drawRoundRect(t.gold.copy(alpha = 0.85f), Offset(inset, inset), Size(size.width - 2 * inset, size.height),
                    CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
            .draggable(
                state = throwState,
                orientation = Orientation.Vertical,
                onDragStarted = { pressed = fly.value },
                onDragStopped = { v ->
                    if (v < -1400f && draft.isNotBlank()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        fly.animateTo(-flyAway, tween(280))
                        if (!onSend(draft)) fly.animateTo(0f, spring(dampingRatio = 0.7f))
                    } else {
                        fly.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
                    }
                    pressed = 0f
                },
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 15.dp, top = 13.dp, end = 22.dp, bottom = 12.dp)) {
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(rank, style = Type.index.copy(color = t.ink))
                    BasicText("♠", style = Type.index.copy(fontSize = 16.sp, color = t.ink))
                }
                Column(Modifier.padding(start = 12.dp, top = 9.dp).weight(1f)) {
                    BasicText("你 ❦ 新牌", style = Type.who.copy(color = t.goldDeep))
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraft,
                        textStyle = Type.body.copy(color = t.ink),
                        cursorBrush = SolidColor(t.red),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).focusRequester(focus),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) BasicText("写下这一张牌…", style = Type.body.copy(color = t.ink.copy(alpha = 0.35f)))
                            inner()
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("✕", onClose)
                Spacer(Modifier.weight(1f))
                Pill("⤢ 全屏", onExpand)
                Pill("出牌 ♠", { onSend(draft) }, filled = true)
            }
        }
    }
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); runCatching { focus.requestFocus() } }
}

/** Lays a box out at an exact pixel size regardless of the parent's constraints. */
internal fun Modifier.layoutSize(w: Float, h: Float): Modifier = layout { measurable, _ ->
    val pw = w.toInt().coerceAtLeast(1)
    val ph = h.toInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(pw, ph))
    layout(pw, ph) { placeable.place(0, 0) }
}
