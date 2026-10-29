package com.simpletavern.app.ui.rack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.simpletavern.app.ui.theme.Foil
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The shelf lies on a table seen from above-right: the plane is turned in place, then tipped away from the camera.
 * Characters run along one diagonal, sessions along the other, which keeps a right thumb on a 45–60° stroke.
 */
object RackGeometry {
    /** Tip of the table plane, degrees; top edge away from the camera. */
    const val TILT = 44f
    /** Turn of the plane in place, degrees. Negative leans the session axis up-right. */
    const val TURN = -27f
    /** RenderNode camera distance for the tipped plane. Smaller = stronger perspective. */
    const val CAMERA = 16f
    /** Where the focused plate sits, as a fraction of the stage height. */
    const val PIVOT_Y = 0.44f
    /** Empty slots above each column's newest session. */
    const val TOP = 1

    val PlateW = 96.dp
    val PlateH = 140.dp
    val StepX = 112.dp
    val StepY = 156.dp

    /** How much the focused plate grows as it rises toward the camera. */
    const val FOCUS_LIFT = 0.16f

    /** Plates near the focus rise toward the camera. */
    fun lift(gx: Float, gy: Float): Float = (1f - hypot(gx, gy) * 1.4f).coerceAtLeast(0f)
}

val WarmGlow = Color(0xFFE08A2E)
val CoolGlow = Color(0xFF8AA0B4)

sealed interface Slot {
    data object Empty : Slot
    data class Session(val column: RackColumn, val session: RackSession, val index: Int) : Slot
    data class NewSession(val column: RackColumn) : Slot
    data object NewCharacter : Slot
}

/** Grid of plates: one column per character plus a last column for writing a new one. */
class Shelf(val columns: List<RackColumn>) {
    val cols = columns.size + 1
    val rows = maxOf(5, RackGeometry.TOP + (columns.maxOfOrNull { it.sessions.size } ?: 0) + 2)

    fun slot(i: Int, j: Int): Slot {
        if (i == columns.size) return if (j == RackGeometry.TOP) Slot.NewCharacter else Slot.Empty
        val c = columns.getOrNull(i) ?: return Slot.Empty
        val k = j - RackGeometry.TOP
        return when {
            k in c.sessions.indices -> Slot.Session(c, c.sessions[k], k)
            k == c.sessions.size -> Slot.NewSession(c)
            else -> Slot.Empty
        }
    }

    /** Nearest plate in the column that does something when tapped. */
    fun actionableRow(i: Int, j: Int): Int {
        if (i == columns.size) return RackGeometry.TOP
        val n = columns.getOrNull(i)?.sessions?.size ?: 0
        return j.coerceIn(RackGeometry.TOP, RackGeometry.TOP + n)
    }

    fun locate(chatId: String): Pair<Int, Int>? {
        columns.forEachIndexed { i, c ->
            val k = c.sessions.indexOfFirst { it.id == chatId }
            if (k >= 0) return i to RackGeometry.TOP + k
        }
        return null
    }
}

/** Where the rack is looking, in plate units. Survives trips into a deck. */
@Stable
class RackCamera {
    val x = Animatable(0f)
    val y = Animatable(RackGeometry.TOP.toFloat())
    var placed by mutableStateOf(false)

    suspend fun snapTo(i: Int, j: Int) { x.snapTo(i.toFloat()); y.snapTo(j.toFloat()); placed = true }

    suspend fun glideTo(i: Int, j: Int) {
        kotlinx.coroutines.coroutineScope {
            launch { x.animateTo(i.toFloat(), spring(dampingRatio = 0.78f, stiffness = 260f)) }
            launch { y.animateTo(j.toFloat(), spring(dampingRatio = 0.78f, stiffness = 260f)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RackScreen(
    state: RackUiState,
    camera: RackCamera,
    away: () -> Float,
    hiddenChatId: String?,
    onOpen: (RackSession) -> Unit,
    onNewSession: (String) -> Unit,
    onNewCharacter: () -> Unit,
    onSettings: () -> Unit,
    onPivot: (Offset) -> Unit = {},
    onEditCharacter: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    emptyExtras: @Composable () -> Unit = {},
) {
    val t = LocalTable.current
    val shelf = remember(state.columns) { Shelf(state.columns) }
    val drawer = remember { HiddenDrawer() }
    // Once a discarded session has left the data, the sessions under it close the gap one by one.
    LaunchedEffect(shelf) { drawer.closeGap(shelf) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    LaunchedEffect(state.loaded) {
        if (state.loaded && !camera.placed) camera.snapTo(0, RackGeometry.TOP)
    }

    val fi by remember(shelf) { derivedStateOf { camera.x.value.roundToInt().coerceIn(0, shelf.cols - 1) } }
    val fj by remember(shelf) { derivedStateOf { camera.y.value.roundToInt().coerceIn(0, shelf.rows - 1) } }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(fi, fj) {
        if (settled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        settled = true
    }
    val focused = shelf.slot(fi, fj)

    var hudTop by remember { mutableFloatStateOf(0f) }
    var stageTop by remember { mutableFloatStateOf(0f) }

    val tap: (Int, Int) -> Unit = { i, j ->
        if (drawer.armed != null) scope.launch { drawer.cancel() }
        else if (i == fi && j == fj) {
            when (val s = shelf.slot(i, j)) {
                is Slot.Session -> onOpen(s.session)
                is Slot.NewSession -> onNewSession(s.column.characterId)
                Slot.NewCharacter -> onNewCharacter()
                Slot.Empty -> scope.launch { camera.glideTo(i, shelf.actionableRow(i, j)) }
            }
        } else scope.launch { camera.glideTo(i, j) }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val at = it.positionInRoot()
                    stageTop = at.y
                    onPivot(at + Offset(it.size.width / 2f, it.size.height * RackGeometry.PIVOT_Y))
                }
                .rackDrag(camera, shelf, onStart = { if (drawer.armed != null) scope.launch { drawer.cancel() } },
                    onBump = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }),
        ) {
            val pivot = Offset(constraints.maxWidth / 2f, constraints.maxHeight * RackGeometry.PIVOT_Y)
            val origin = TransformOrigin(0.5f, RackGeometry.PIVOT_Y)
            val stepX = with(density) { RackGeometry.StepX.toPx() }
            val stepY = with(density) { RackGeometry.StepY.toPx() }
            val plateW = with(density) { RackGeometry.PlateW.toPx() }
            val plateH = with(density) { RackGeometry.PlateH.toPx() }
            // Only plates near the camera are composed.
            val window by remember(shelf) {
                derivedStateOf { camera.x.value.roundToInt() to camera.y.value.roundToInt() }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val a = away()
                        transformOrigin = origin
                        rotationX = RackGeometry.TILT
                        cameraDistance = RackGeometry.CAMERA * density.density
                        scaleX = 1f + 0.06f * a
                        scaleY = 1f + 0.06f * a
                        alpha = 1f - 0.7f * a
                    }
                    .then(if (away() > 0.01f) Modifier.blur((6 * away()).dp) else Modifier),
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { transformOrigin = origin; rotationZ = RackGeometry.TURN }) {
                    val (wx, wy) = window
                    for (i in (wx - 3)..(wx + 3)) {
                        if (i !in 0 until shelf.cols) continue
                        for (j in (wy - 5)..(wy + 5)) {
                            if (j !in 0 until shelf.rows) continue
                            val slot = shelf.slot(i, j)
                            val sessionId = (slot as? Slot.Session)?.session?.id
                            val hidden = sessionId != null && sessionId == hiddenChatId
                            val armedHere = sessionId != null && sessionId == drawer.armed
                            if (armedHere) key("drawer") {
                                DrawerWell(
                                    open = { drawer.open.value },
                                    modifier = Modifier
                                        .size(RackGeometry.PlateW, RackGeometry.PlateH)
                                        .zIndex(0.5f)
                                        .graphicsLayer {
                                            translationX = pivot.x - plateW / 2 + (i - camera.x.value) * stepX
                                            translationY = pivot.y - plateH / 2 + (j - camera.y.value) * stepY
                                        },
                                )
                            }
                            key(sessionId ?: "$i,$j") {
                                Plate(
                                    slot = slot,
                                    focused = { i == fi && j == fj },
                                    modifier = Modifier
                                        .size(RackGeometry.PlateW, RackGeometry.PlateH)
                                        .zIndex(if (i == fi && j == fj) 2f else 1f)
                                        .zIndex(if (armedHere) 3f else 0f)
                                        .graphicsLayer {
                                            val gx = i - camera.x.value
                                            val gy = j - camera.y.value + (sessionId?.let { drawer.shiftOf(it) } ?: 0f)
                                            val k = RackGeometry.lift(gx, gy)
                                            translationX = pivot.x - plateW / 2 + gx * stepX
                                            translationY = pivot.y - plateH / 2 + gy * stepY
                                            var sc = 1f + RackGeometry.FOCUS_LIFT * k
                                            var fade = 1f
                                            if (armedHere) {
                                                // Lifted off its slot and held above the open drawer; on discard it drops in.
                                                val o = drawer.open.value
                                                val sink = drawer.sink.value
                                                translationY -= stepY * 0.46f * o * (1f - sink)
                                                sc *= (1f + 0.1f * o) * (1f - 0.5f * sink)
                                                fade = 1f - sink
                                            }
                                            scaleX = sc
                                            scaleY = sc
                                            shadowElevation = (14.dp.toPx() * k + if (armedHere) 18.dp.toPx() * drawer.open.value else 0f) * fade
                                            shape = RoundedCornerShape(10.dp)
                                            val far = hypot(gx, gy)
                                            alpha = if (hidden && away() > 0.001f || sessionId != null && sessionId in drawer.gone) 0f
                                            else ((4.2f - far) / 2.2f).coerceIn(0f, 1f) * (0.78f + 0.22f * k) * fade
                                        }
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onLongClick = if (sessionId != null) ({
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                scope.launch { camera.glideTo(i, j) }
                                                scope.launch { drawer.arm(sessionId) }
                                            }) else null,
                                            onClick = { tap(i, j) },
                                        ),
                                )
                            }
                        }
                    }
                }
            }

            // A gold leader from the focused plate down to the name plate.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - away() }) {
                if (hudTop <= 0f) return@Canvas
                val y0 = pivot.y + plateH * 0.5f * cos(RackGeometry.TILT * PI.toFloat() / 180f) + 6.dp.toPx()
                val y1 = hudTop - stageTop - 10.dp.toPx()
                if (y1 <= y0) return@Canvas
                val x0 = 24.dp.toPx()
                val ex = maxOf(x0 + 40.dp.toPx(), pivot.x - (y1 - y0))
                val gold = t.gold
                drawCircle(gold, 3.dp.toPx(), Offset(pivot.x, y0))
                drawLine(gold.copy(alpha = 0.8f), Offset(pivot.x, y0), Offset(ex, y1), 1.dp.toPx())
                drawLine(gold.copy(alpha = 0.8f), Offset(ex, y1), Offset(x0, y1), 1.dp.toPx())
            }
        }

        RackTop(
            position = if (state.columns.isEmpty()) null else "%02d / %02d · %d / %d".format(fi + 1, shelf.cols, fj + 1, shelf.rows),
            onSettings = onSettings,
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = 1f - away() },
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer { alpha = 1f - away(); translationY = 24.dp.toPx() * away() }
                .onGloballyPositioned { hudTop = it.positionInRoot().y }
                .padding(start = 24.dp, end = 24.dp, bottom = 18.dp),
        ) {
            val armedSession = drawer.armed?.let { id -> state.columns.firstNotNullOfOrNull { c -> c.sessions.firstOrNull { it.id == id } } }
            if (armedSession != null) {
                DiscardPrompt(
                    title = armedSession.title,
                    onConfirm = {
                        scope.launch {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            drawer.discard(armedSession.id, shelf) { onDelete(armedSession.id) }
                        }
                    },
                    onCancel = { scope.launch { drawer.cancel() } },
                )
            } else {
                Hud(focused, fi, state.columns.isEmpty(), onEditCharacter)
            }
            if (state.columns.isEmpty()) emptyExtras()
            state.notice?.let {
                BasicText(it, style = Type.sub.copy(color = t.red), maxLines = 2, modifier = Modifier.padding(top = 8.dp))
            }
            BasicText(
                "斜着拖：左右换角色 · 上下换会话",
                style = Type.ornament.copy(color = t.textSoft, letterSpacing = 0.12.em),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

/** Drag along the two tipped axes. The first real movement picks one axis; flicks carry up to two plates. */
private fun Modifier.rackDrag(camera: RackCamera, shelf: Shelf, onStart: () -> Unit, onBump: () -> Unit): Modifier = pointerInput(shelf) {
    val r = RackGeometry.TURN * PI.toFloat() / 180f
    val ct = cos(RackGeometry.TILT * PI.toFloat() / 180f)
    val stepX = RackGeometry.StepX.toPx()
    val stepY = RackGeometry.StepY.toPx()
    fun grid(dx: Float, dy: Float): Offset {
        val y1 = dy / ct
        val u = dx * cos(r) + y1 * sin(r)
        val v = -dx * sin(r) + y1 * cos(r)
        return Offset(u / stepX, v / stepY)
    }
    fun elastic(v: Float, max: Float) = when {
        v < 0f -> v * 0.3f
        v > max -> max + (v - max) * 0.3f
        else -> v
    }
    kotlinx.coroutines.coroutineScope {
        var lock: Boolean? = null // true = columns (x), false = rows (y)
        var start = Offset.Zero
        var total = Offset.Zero
        var bumped = false
        val tracker = VelocityTracker()
        val maxX = (shelf.cols - 1).toFloat()
        val maxY = (shelf.rows - 1).toFloat()
        detectDragGestures(
            onDragStart = {
                onStart()
                lock = null; total = Offset.Zero; bumped = false; tracker.resetTracking()
                start = Offset(camera.x.value, camera.y.value)
                launch { camera.x.stop() }; launch { camera.y.stop() }
            },
            onDragEnd = {
                val v = tracker.calculateVelocity()
                val g = grid(v.x, v.y)
                val fx = (-g.x * 0.16f).coerceIn(-2f, 2f)
                val fy = (-g.y * 0.16f).coerceIn(-2f, 2f)
                val tx = (camera.x.value + if (lock == true) fx else 0f).roundToInt().coerceIn(0, maxX.toInt())
                val ty = (camera.y.value + if (lock == false) fy else 0f).roundToInt().coerceIn(0, maxY.toInt())
                launch { camera.x.animateTo(tx.toFloat(), spring(dampingRatio = 0.78f, stiffness = 240f)) }
                launch { camera.y.animateTo(ty.toFloat(), spring(dampingRatio = 0.78f, stiffness = 240f)) }
            },
            onDragCancel = {
                launch { camera.x.animateTo(camera.x.value.roundToInt().coerceIn(0, maxX.toInt()).toFloat()) }
                launch { camera.y.animateTo(camera.y.value.roundToInt().coerceIn(0, maxY.toInt()).toFloat()) }
            },
        ) { change, amount ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            total += amount
            val g = grid(total.x, total.y)
            if (lock == null) lock = abs(g.x) * RackGeometry.StepX.value > abs(g.y) * RackGeometry.StepY.value
            val nx = if (lock == true) elastic(start.x - g.x, maxX) else start.x
            val ny = if (lock == false) elastic(start.y - g.y, maxY) else start.y
            if (!bumped && (nx < -0.05f || nx > maxX + 0.05f || ny < -0.05f || ny > maxY + 0.05f)) { bumped = true; onBump() }
            launch { camera.x.snapTo(nx) }
            launch { camera.y.snapTo(ny) }
        }
    }
}

@Composable
private fun RackTop(position: String?, onSettings: () -> Unit, modifier: Modifier) {
    val t = LocalTable.current
    Row(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("❦ SIMPLETAVERN", style = Type.ornament.copy(color = t.goldDeep))
            BasicText(position ?: "牌架", style = Type.ornament.copy(color = t.textSoft, letterSpacing = 0.18.em), modifier = Modifier.padding(top = 4.dp))
        }
        val shape = RoundedCornerShape(999.dp)
        BasicText(
            "设置  setting",
            style = Type.pill.copy(color = t.goldDeep, fontSize = 13.sp),
            modifier = Modifier.clip(shape).border(1.dp, t.gold, shape).clickable(onClick = onSettings)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Hud(slot: Slot, column: Int, emptyShelf: Boolean, onEditCharacter: (String) -> Unit) {
    val t = LocalTable.current
    val (tag, name, line, dot) = when (slot) {
        is Slot.Session -> Quad("No.%02d ♠".format(column + 1), slot.column.name, slot.session.title, glowOf(slot.session.recency))
        is Slot.NewSession -> Quad("No.%02d ♠".format(column + 1), slot.column.name, "＋ 空位 · 新开一局", null)
        Slot.NewCharacter -> Quad("NEW ♥", if (emptyShelf) "牌架还空着" else "新角色", "＋ 写一张新角色", null)
        Slot.Empty -> Quad("No.%02d ♠".format(column + 1), "", "空位", null)
    }
    val meta = (slot as? Slot.Session)?.column?.sessions?.size ?: (slot as? Slot.NewSession)?.column?.sessions?.size

    // The tag stamps down whenever the focus changes; the name slides up when the character changes.
    val stamp = remember { Animatable(1f) }
    LaunchedEffect(tag, line) { stamp.snapTo(0f); stamp.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f)) }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(name) { rise.snapTo(0f); rise.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 300f)) }

    BasicText(
        tag,
        style = Type.index.copy(color = t.paper, letterSpacing = 0.14.em),
        modifier = Modifier
            .graphicsLayer {
                val s = 1.5f - 0.5f * stamp.value
                scaleX = s; scaleY = s; alpha = stamp.value.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .background(t.ink)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
    val characterId = (slot as? Slot.Session)?.column?.characterId ?: (slot as? Slot.NewSession)?.column?.characterId
    Row(
        Modifier.padding(top = 8.dp).graphicsLayer {
            alpha = rise.value.coerceIn(0f, 1f); translationY = (1f - rise.value) * 10.dp.toPx()
        }.then(if (characterId != null) Modifier.clickable { onEditCharacter(characterId) } else Modifier),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicText(name.ifBlank { " " }, style = Type.title.copy(color = t.text, fontSize = 32.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        if (characterId != null) BasicText("  ✎", style = Type.sub.copy(color = t.goldDeep, fontSize = 15.sp), modifier = Modifier.padding(bottom = 6.dp))
    }
    if (meta != null) {
        BasicText("$meta 局", style = Type.ornament.copy(color = t.textSoft, letterSpacing = 0.18.em), modifier = Modifier.padding(top = 2.dp))
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot ?: t.gold.copy(alpha = 0.5f)))
        Spacer(Modifier.width(8.dp))
        BasicText(line, style = Type.body.copy(color = t.text, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class Quad(val a: String, val b: String, val c: String, val d: Color?)

fun glowOf(r: Recency): Color? = when (r) {
    Recency.Warm -> WarmGlow
    Recency.Cool -> CoolGlow
    Recency.None -> null
}

/** One plate on the shelf. Sessions are small ivory cards with the title set vertically; empty slots are dashed outlines. */
@Composable
fun Plate(slot: Slot, focused: () -> Boolean, modifier: Modifier = Modifier) {
    when (slot) {
        is Slot.Session -> SessionPlate(
            title = slot.session.title,
            index = slot.index,
            hue = slot.column.hue,
            glow = glowOf(slot.session.recency),
            focused = focused,
            modifier = modifier,
        )
        is Slot.NewSession, Slot.NewCharacter -> DashedPlate(modifier, if (slot is Slot.NewCharacter) "新角色" else "新局", focused)
        Slot.Empty -> DashedPlate(modifier, null, focused)
    }
}

/** A session on the shelf: a small ivory card, title set vertically, tinted by its character, glowing by recency. */
@Composable
fun SessionPlate(title: String, index: Int, hue: Float, glow: Color?, focused: () -> Boolean, modifier: Modifier = Modifier) {
    val t = LocalTable.current
    val tint = Color.hsl(hue, 0.48f, 0.62f)
    Box(
        modifier.drawBehind {
            val f = focused()
            val halo = if (f) t.gold else glow
            if (halo != null) {
                val layers = if (f) 9 else 5
                for (n in 0 until layers) {
                    val g = (n + 1) * (if (f) 2.8f else 2.2f).dp.toPx()
                    drawRoundRect(
                        color = halo.copy(alpha = (if (f) 0.42f else 0.12f) * (1f - n / layers.toFloat())),
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(10.dp.toPx() + g),
                        style = Stroke(2.5.dp.toPx()),
                    )
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Foil)
                .padding(1.5.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(t.paper)
                .drawBehind {
                    drawRect(Brush.verticalGradient(0.5f to Color.Transparent, 1f to tint.copy(alpha = 0.32f)))
                    val inset = 5.dp.toPx()
                    drawRoundRect(
                        color = t.gold.copy(alpha = 0.7f),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                        style = Stroke(0.8.dp.toPx()),
                    )
                },
        ) {
            BasicText(
                "No.%02d".format(index + 1),
                style = Type.ornament.copy(color = t.goldDeep, fontSize = 8.sp, letterSpacing = 0.12.em),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 11.dp),
            )
            BasicText(
                vertical(title),
                style = Type.title.copy(color = t.ink, fontSize = 16.sp, lineHeight = 1.2.em, letterSpacing = 0.em),
                modifier = Modifier.align(Alignment.Center).padding(top = 8.dp),
            )
            BasicText(
                if (index == 0) "♥" else "♠",
                style = Type.index.copy(color = if (index == 0) t.red else t.ink, fontSize = 11.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun DashedPlate(modifier: Modifier, label: String?, focused: () -> Boolean) {
    val t = LocalTable.current
    Box(
        modifier.drawBehind {
            val f = focused()
            drawRoundRect(
                color = if (f) t.gold else t.goldDeep.copy(alpha = 0.45f),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(if (f) 2.dp.toPx() else 1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))),
            )
            if (label != null) drawRoundRect(t.paper.copy(alpha = if (f) 0.22f else 0.1f), cornerRadius = CornerRadius(10.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                BasicText("＋", style = Type.title.copy(color = t.goldDeep, fontSize = 30.sp, letterSpacing = 0.em))
                BasicText(label, style = Type.ornament.copy(color = t.goldDeep, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Sets a title top to bottom, one glyph per line; long titles end with a vertical ellipsis. */
fun vertical(title: String, max: Int = 6): String {
    val cps = title.trim().codePoints().toArray().map { String(Character.toChars(it)) }.filter { it.isNotBlank() }
    val shown = if (cps.size > max) cps.take(max - 1) + "︙" else cps
    return shown.joinToString("\n")
}

/**
 * Long-pressing a session opens a hidden drawer in the shelf under it: the plate lifts off and hangs over a dark
 * well. Discarding drops it in, the drawer shuts, and the sessions below slide up into the gap.
 */
@Stable
private class HiddenDrawer {
    var armed by mutableStateOf<String?>(null)
    val open = Animatable(0f)
    val sink = Animatable(0f)
    val gone = mutableStateListOf<String>()
    private val shifts = mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>()
    private var pendingGap: Pair<String, List<String>>? = null

    fun shiftOf(id: String): Float = shifts[id]?.value ?: 0f

    suspend fun arm(id: String) {
        if (armed != null && armed != id) cancel()
        armed = id
        sink.snapTo(0f)
        open.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 260f))
    }

    suspend fun cancel() {
        open.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 400f))
        armed = null
    }

    suspend fun discard(id: String, shelf: Shelf, delete: () -> Unit) {
        val below = shelf.columns.firstOrNull { c -> c.sessions.any { it.id == id } }
            ?.sessions?.map { it.id }?.dropWhile { it != id }?.drop(1).orEmpty()
        sink.animateTo(1f, tween(460))
        gone += id
        pendingGap = id to below
        delete()
        open.animateTo(0f, tween(220))
        armed = null
        sink.snapTo(0f)
    }

    suspend fun closeGap(shelf: Shelf) {
        val (id, below) = pendingGap ?: return
        if (shelf.locate(id) != null) return
        pendingGap = null
        kotlinx.coroutines.coroutineScope {
            below.forEachIndexed { n, b ->
                val a = Animatable(1f)
                shifts[b] = a
                launch {
                    kotlinx.coroutines.delay(70L * n)
                    a.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 220f))
                    shifts.remove(b)
                }
            }
        }
        gone.remove(id)
    }
}

/** The well under a lifted plate: a recess lined in dark cloth, opening from its middle. */
@Composable
private fun DrawerWell(open: () -> Float, modifier: Modifier) {
    val t = LocalTable.current
    Box(
        modifier
            .graphicsLayer { scaleY = open().coerceIn(0f, 1f); alpha = open().coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0E0507), t.cloth.copy(alpha = 0.95f), Color(0xFF0E0507))))
            .border(1.5.dp, t.gold.copy(alpha = 0.8f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("弃\n牌", style = Type.title.copy(color = t.gold.copy(alpha = 0.55f), fontSize = 18.sp, lineHeight = 1.3.em, letterSpacing = 0.em))
    }
}

@Composable
private fun DiscardPrompt(title: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalTable.current
    BasicText("DISCARD ✕", style = Type.index.copy(color = t.paper, letterSpacing = 0.14.em), modifier = Modifier.background(t.red).padding(horizontal = 10.dp, vertical = 5.dp))
    BasicText("弃掉「$title」？", style = Type.title.copy(color = t.text, fontSize = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    BasicText("这一局的所有牌都会删除，不能恢复。", style = Type.sub.copy(color = t.textSoft), modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(999.dp)
        BasicText("确认弃牌", style = Type.pill.copy(color = t.paper), modifier = Modifier.clip(shape).background(t.red).clickable(onClick = onConfirm).padding(horizontal = 14.dp, vertical = 6.dp))
        BasicText("留着", style = Type.pill.copy(color = t.goldDeep), modifier = Modifier.clip(shape).border(1.dp, t.gold, shape).clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 6.dp))
    }
}
