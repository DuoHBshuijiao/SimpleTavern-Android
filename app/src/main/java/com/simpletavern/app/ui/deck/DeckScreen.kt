package com.simpletavern.app.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.simpletavern.app.ui.card.CardBack
import com.simpletavern.app.ui.card.CardFace
import com.simpletavern.app.ui.card.CardFrame
import com.simpletavern.app.ui.card.Suit
import com.simpletavern.app.ui.rack.RackGeometry
import com.simpletavern.app.ui.rack.SessionPlate
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type
import com.simpletavern.core.model.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class Overlay { None, Reader, Editor }

/**
 * How the deck arrives from the shelf. [flight]: 0 = still a plate on the shelf, 1 = landed in the middle,
 * turning face up on the way. [deal]: 0 = every card hidden behind the front one, 1 = fanned into the column.
 */
@Stable
class DeckEntrance {
    val flight = Animatable(1f)
    val deal = Animatable(1f)
    /** Root position of the plate the deck was drawn from. */
    var plateCenter by mutableStateOf(Offset.Unspecified)
    var plateTitle by mutableStateOf("")
    var plateIndex by mutableIntStateOf(0)
    var plateHue by mutableFloatStateOf(0f)
}

/**
 * One session as a deck of cards drawn over the whole screen. The title floats over the top of the scene,
 * the two cards held at the bottom are menu and setting, and the face-down card under the front one is
 * rolled up to write the next message.
 */
@Composable
fun DeckScreen(
    state: DeckUiState,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onSwipe: (DeckCard, Int, Boolean) -> Unit,
    onClearNotice: () -> Unit,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    entrance: DeckEntrance = remember { DeckEntrance() },
) {
    val ready = state.cardsFor == state.chatId
    val cards = if (ready) state.cards else emptyList()
    val n = cards.size
    val scope = rememberCoroutineScope()

    var composing by remember(state.chatId) { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var readerIndex by remember { mutableIntStateOf(-1) }
    var draft by rememberSaveable(state.chatId) { mutableStateOf("") }
    val pos = remember(state.chatId) { Animatable(0f) }
    val curl = remember(state.chatId) { Animatable(0f) }
    val front by remember(state.chatId) { derivedStateOf { pos.value.roundToInt() } }
    val frontIndex = front.coerceIn(0, (n - 1).coerceAtLeast(0))
    var bounds by remember { mutableStateOf(StageBounds()) }

    // Open on the newest card; later, follow new cards unless the reader has wandered back into history.
    var seen by remember(state.chatId) { mutableIntStateOf(-1) }
    LaunchedEffect(state.chatId, n, ready) {
        if (!ready) return@LaunchedEffect
        val last = (n - 1).coerceAtLeast(0).toFloat()
        if (seen < 0) pos.snapTo(last)
        else if (n != seen && pos.value >= seen - 1.5f) pos.animateTo(last, spring(dampingRatio = 0.8f, stiffness = 180f))
        seen = n
    }
    LaunchedEffect(composing) {
        if (composing) curl.animateTo(1f, tween(640, easing = FastOutSlowInEasing))
        else curl.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) { delay(4_000); onClearNotice() }
    }

    BackHandler(enabled = overlay != Overlay.None || composing) {
        if (overlay != Overlay.None) overlay = Overlay.None else composing = false
    }

    val send: (String) -> Boolean = { text ->
        val ok = onSend(text)
        if (ok) {
            draft = ""
            overlay = Overlay.None
            // The played card has left the table; the pile underneath is simply flat again.
            composing = false
            scope.launch { curl.snapTo(0f) }
        }
        ok
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sized from the tallest the screen has been, so the keyboard never squeezes the cards.
        var tallest by remember { mutableStateOf(0.dp) }
        if (maxHeight > tallest) tallest = maxHeight
        val card = DeckGeometry.cardSize(maxWidth, tallest)

        DeckStage(
            cards = cards,
            pos = pos,
            curl = curl,
            entrance = entrance,
            cardW = card.width,
            cardH = card.height,
            screenH = tallest,
            composing = composing,
            draft = draft,
            onDraft = { draft = it },
            onOpenCompose = { composing = true },
            onCloseCompose = { composing = false },
            onExpand = { overlay = Overlay.Editor },
            onSend = send,
            onRead = { k -> readerIndex = k; overlay = Overlay.Reader },
            onSwipe = onSwipe,
            onSeek = { k -> scope.launch { pos.animateTo(k.toFloat(), spring(dampingRatio = 0.82f, stiffness = 220f)) } },
            onBounds = { bounds = it },
        )

        DeckHeader(
            name = state.characterName,
            title = state.title,
            position = if (n > 0) "${frontIndex + 1} / $n" else null,
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                val d = entrance.deal.value
                alpha = d.coerceIn(0f, 1f) * (1f - curl.value.coerceIn(0f, 1f))
                translationY = -16.dp.toPx() * (1f - d)
            },
        )

        HandCards(onMenu = onMenu, onSettings = onSettings, deal = { entrance.deal.value }, curl = { curl.value })

        DeckStatus(
            notice = state.notice,
            generating = state.generating,
            onStop = onStop,
            onClear = onClearNotice,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = HandPeek + 14.dp)
                .graphicsLayer { alpha = entrance.deal.value.coerceIn(0f, 1f) },
        )
    }

    EdgeGlow(state.generating)

    LiftOverlay(overlay == Overlay.Reader, bounds.front) {
        val c = cards.getOrNull(readerIndex)
        if (c != null) {
            ReaderCard(
                card = c,
                rank = "${readerIndex + 1}",
                isNewest = readerIndex == n - 1,
                generating = state.generating,
                onSwipe = { dir -> onSwipe(c, dir, readerIndex == n - 1) },
                onClose = { overlay = Overlay.None },
            )
        }
    }
    LiftOverlay(overlay == Overlay.Editor, bounds.face) {
        EditorCard(
            rank = "${n + 1}",
            draft = draft,
            onDraft = { draft = it },
            onCollapse = { overlay = Overlay.None },
            onSend = { send(draft) },
        )
    }
}

/** Root-coordinate bounds of the front card and the writing face, so full-screen cards can grow out of them. */
internal data class StageBounds(val front: Rect? = null, val face: Rect? = null)

@Composable
private fun DeckStage(
    cards: List<DeckCard>,
    pos: Animatable<Float, AnimationVector1D>,
    curl: Animatable<Float, AnimationVector1D>,
    entrance: DeckEntrance,
    cardW: Dp,
    cardH: Dp,
    screenH: Dp,
    composing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onOpenCompose: () -> Unit,
    onCloseCompose: () -> Unit,
    onExpand: () -> Unit,
    onSend: (String) -> Boolean,
    onRead: (Int) -> Unit,
    onSwipe: (DeckCard, Int, Boolean) -> Unit,
    onSeek: (Int) -> Unit,
    onBounds: (StageBounds) -> Unit,
) {
    val n = cards.size
    val maxPos = (n - 1).coerceAtLeast(0).toFloat()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val focus = remember { FocusRequester() }

    val landed by remember { derivedStateOf { entrance.flight.value >= 1f && entrance.deal.value >= 1f } }
    val rolling by remember { derivedStateOf { curl.value > 0f } }
    val rolled by remember { derivedStateOf { curl.value >= 1f } }
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    var stageW by remember { mutableFloatStateOf(0f) }

    val wPx = with(density) { cardW.toPx() }
    val hPx = with(density) { cardH.toPx() }
    val frontY = with(density) { screenH.toPx() } * DeckGeometry.FRONT_Y
    val step = hPx * 0.42f
    val camera = DeckGeometry.cameraPx(DeckGeometry.CAMERA * density.density)

    /** The draw pile's pose: its first slot, sliding up a little and settling at the writing tilt as it rolls. */
    fun pileProjector(): CurlProjector? {
        if (stageW <= 0f) return null
        val c = curl.value.coerceIn(0f, 1f)
        val (yf, r) = DeckGeometry.slot(n - pos.value)
        val y = frontY + hPx * (yf + (Curl.PILE_Y - yf) * c)
        val tilt = r + (Curl.PILE_TILT - r) * c
        return CurlProjector(Offset(stageW / 2f, y), wPx, hPx, tilt, camera)
    }
    /** How far the stage rises so the writing face clears the keyboard. */
    fun imeLift(): Float {
        val face = pileProjector()?.let { CurlProjector(it.center, wPx, hPx, Curl.PILE_TILT, camera).faceRect() } ?: return 0f
        val keyboardTop = with(density) { screenH.toPx() } - ime.getBottom(density)
        return minOf(0f, keyboardTop - with(density) { 12.dp.toPx() } - face.bottom)
    }

    LaunchedEffect(origin, stageW, wPx, hPx, frontY, n) {
        if (!origin.isSpecified || stageW <= 0f) return@LaunchedEffect
        val cx = origin.x + stageW / 2f
        val frontRect = Rect(cx - wPx / 2, origin.y + frontY - hPx / 2, cx + wPx / 2, origin.y + frontY + hPx / 2)
        val face = pileProjector()?.let { CurlProjector(Offset(it.center.x, frontY + Curl.PILE_Y * hPx), wPx, hPx, Curl.PILE_TILT, camera).faceRect() }
        onBounds(StageBounds(frontRect, face?.translate(origin)))
    }

    val front by remember(n) { derivedStateOf { pos.value.roundToInt().coerceIn(0, n.coerceAtLeast(1) - 1) } }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(front) {
        if (settled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        settled = true
    }

    var raw by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta ->
        raw -= delta / step
        scope.launch { pos.snapTo(DeckGeometry.elastic(raw, maxPos)) }
    }

    val tapCard: (Int) -> Unit = { k ->
        when {
            composing -> onCloseCompose()
            k < n && k == front -> onRead(k)
            k < n -> onSeek(k)
            n == 0 || front == n - 1 -> onOpenCompose()
            else -> onSeek(n - 1)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot(); stageW = it.size.width.toFloat() }
            .graphicsLayer { translationY = imeLift() * curl.value.coerceIn(0f, 1f) }
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                enabled = !composing && landed,
                onDragStarted = { pos.stop(); raw = pos.value },
                onDragStopped = { v ->
                    val projected = (-v / step * 0.18f).coerceIn(-2f, 2f)
                    val target = (pos.value + projected).roundToInt().coerceIn(0, maxPos.toInt())
                    pos.animateTo(target.toFloat(), spring(dampingRatio = 0.82f, stiffness = 220f), initialVelocity = -v / step)
                },
            ),
    ) {
        val p = pos.value
        // The card in the middle is the one that flew in from the shelf (the draw pile when the deck is empty).
        val flyer = if (n > 0) front else n
        for (k in 0..n + 3) {
            var d = k - p
            // While writing, the conversation slides up out of the way and the piles below follow the rolled card.
            val c = curl.value.coerceIn(0f, 1f)
            if (k < n) d -= 0.9f * c
            if (k > n) d -= 0.4f * c
            if (abs(d) > 7.5f) continue
            if (k == n && rolling) continue
            val (yf, r) = DeckGeometry.slot(d)
            val isFront = k < n && k == front && !composing
            val flying = k == flyer && !rolling
            key(if (k < n) cards[k].key else "back-${k - n}") {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(cardW, cardH)
                        .zIndex(if (flying) 150f else 100f - abs(d) * 10f)
                        .graphicsLayer {
                            val s = if (flying) 1f else entrance.deal.value
                            translationY = frontY - hPx / 2 + yf * hPx * s
                            if (flying) {
                                val e = entrance.flight.value
                                val from = entrance.plateCenter
                                if (from.isSpecified && origin.isSpecified) {
                                    translationX = (from.x - origin.x - stageW / 2f) * (1f - e)
                                    translationY += (from.y - origin.y - frontY) * (1f - e)
                                }
                                translationY -= 28.dp.toPx() * sin(PI.toFloat() * e.coerceIn(0f, 1f))
                            }
                        }
                        .then(if (flying) Modifier.flightPose(entrance, wPx, hPx) else Modifier)
                        .graphicsLayer {
                            val s = if (flying) 1f else entrance.deal.value
                            // Dealt cards slide out from behind the front card already tipped, so their faces never sweep the screen.
                            rotationX = r * DeckGeometry.TILT
                            cameraDistance = DeckGeometry.CAMERA * this.density
                            if (!flying) {
                                alpha = s.coerceIn(0f, 1f)
                                scaleX = 0.94f + 0.06f * s
                                scaleY = 0.94f + 0.06f * s
                            }
                        }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { if (landed) tapCard(k) }
                        .then(
                            if (k == n) Modifier.pointerInput(n, front) {
                                var pulled = 0f
                                detectVerticalDragGestures(onDragStart = { pulled = 0f }) { change, dy ->
                                    pulled += dy
                                    change.consume()
                                    if (pulled > 24.dp.toPx() && (n == 0 || front == n - 1)) { pulled = -1e9f; onOpenCompose() }
                                }
                            } else Modifier,
                        ),
                ) {
                    if (flying && entrance.flight.value < 1f && plateSide(entrance.flight.value)) {
                        ShelfFace(entrance, wPx, hPx)
                    } else if (k < n) {
                        MessageCard(
                            card = cards[k],
                            rank = "${k + 1}",
                            age = if (k == n - 1) "刚刚" else "${n - 1 - k} 张前",
                            isFront = isFront,
                            onSwipe = { dir -> onSwipe(cards[k], dir, k == n - 1) },
                        )
                    } else {
                        CardBack(Modifier.fillMaxSize(), mark = k == n)
                    }
                    val dim = DeckGeometry.dim(d)
                    if (dim > 0.01f) {
                        Box(Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = dim)))
                    }
                }
            }
        }

        if (rolling) {
            CurlCanvas(
                progress = { curl.value },
                projector = ::pileProjector,
                rank = "${n + 1}",
                modifier = Modifier.fillMaxSize().zIndex(160f),
            )
        }
        if (rolled && composing) {
            WritingFace(
                rect = { pileProjector()?.faceRect() },
                stageOrigin = { Offset.Zero },
                cardW = wPx,
                cardH = hPx,
                rank = "${n + 1}",
                draft = draft,
                onDraft = onDraft,
                onClose = onCloseCompose,
                onExpand = onExpand,
                onSend = onSend,
                focus = focus,
                modifier = Modifier.zIndex(170f),
            )
        }
    }
}

@Composable
private fun MessageCard(card: DeckCard, rank: String, age: String, isFront: Boolean, onSwipe: (Int) -> Unit) {
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    var dx by remember(card.key) { mutableFloatStateOf(0f) }
    val suit = if (card.role == ChatRole.user) Suit.Spade else Suit.Heart
    val ghosts = (card.candidateCount - 1).coerceIn(0, 2)
    val status = when {
        card.thinking -> "思索中"
        card.live -> "落笔中"
        else -> age
    }
    val pick = if (card.candidateCount > 1) " · ${card.candidateIndex + 1}/${card.candidateCount}" else ""

    Box(Modifier.fillMaxSize()) {
        for (i in ghosts downTo 1) {
            CardBack(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = i * 6.dp.toPx()
                        translationY = i * 5.dp.toPx()
                    },
                mark = false,
            )
        }
        CardFace(
            rank = rank,
            suit = suit,
            who = "${card.who} ❦ $status$pick",
            body = card.text,
            maxLines = 11,
            placeholder = if (card.live) "……" else "（空白的牌）",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = dx * 0.6f
                    rotationZ = dx * 0.004f
                }
                .then(
                    if (isFront && !card.live) Modifier.pointerInput(card.key) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (abs(dx) > threshold) onSwipe(if (dx < 0) 1 else -1)
                                dx = 0f
                            },
                            onDragCancel = { dx = 0f },
                        ) { change, amount ->
                            change.consume()
                            dx += amount
                        }
                    } else Modifier,
                ),
        )
    }
}

/** Character and session, floating over the scene on a faint veil of the table colour. */
@Composable
private fun DeckHeader(name: String, title: String, position: String?, modifier: Modifier) {
    val t = LocalTable.current
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to t.tableEdge.copy(alpha = 0.92f), 0.55f to t.tableEdge.copy(alpha = 0.55f), 1f to Color.Transparent))
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(name.ifBlank { " " }, style = Type.title.copy(color = t.text, fontSize = 19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(title.takeIf { it.isNotBlank() }, position).joinToString(" · ")
        BasicText(sub.ifBlank { " " }, style = Type.sub.copy(color = t.textSoft, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeckStatus(notice: String?, generating: Boolean, onStop: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    val t = LocalTable.current
    if (notice == null && !generating) return
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(t.tableEdge.copy(alpha = 0.82f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            notice != null -> BasicText(
                notice,
                style = Type.sub.copy(color = t.red),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onClear),
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("落笔中…", style = Type.sub.copy(color = t.textSoft))
                Spacer(Modifier.width(12.dp))
                Pill("停 ■", onStop)
            }
        }
    }
}

/** How much of the two held cards shows above the bottom edge (plus the navigation bar). */
private val HandPeek = 92.dp

/**
 * Menu and setting as two cards held in one hand: fanned a little from a pivot below the screen, overlapping in
 * the middle, drawn over the deck. Pressing one lifts it out of the hand.
 */
@Composable
private fun BoxScope.HandCards(onMenu: () -> Unit, onSettings: () -> Unit, deal: () -> Float, curl: () -> Float) {
    val bar = WindowInsets.navigationBars
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        val w = (maxWidth * 0.36f).coerceAtMost(170.dp)
        val h = w / DeckGeometry.RATIO
        val peek = HandPeek + with(density) { bar.getBottom(density).toDp() }
        HandCard("菜单", "menu", Suit.Spade, -1f, w, h, peek, deal, curl, onMenu)
        HandCard("设置", "setting", Suit.Heart, 1f, w, h, peek, deal, curl, onSettings)
    }
}

@Composable
private fun BoxScope.HandCard(
    label: String,
    latin: String,
    suit: Suit,
    side: Float,
    w: Dp,
    h: Dp,
    peek: Dp,
    deal: () -> Float,
    curl: () -> Float,
    onClick: () -> Unit,
) {
    val t = LocalTable.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val lift by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "hand")
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .layout { m, _ ->
                // The cards hang below the screen edge; only the top part shows.
                val pw = w.roundToPx()
                val ph = h.roundToPx()
                val placeable = m.measure(Constraints.fixed(pw, ph))
                layout(pw, peek.roundToPx()) { placeable.place(0, 0) }
            }
            .zIndex(if (side > 0) 2f else 1f)
            .graphicsLayer {
                val dl = deal().coerceIn(0f, 1f)
                val c = curl().coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1.6f)
                rotationZ = side * (8f + 4f * lift)
                translationX = side * w.toPx() * 0.24f
                translationY = -lift * 16.dp.toPx() + (1f - dl) * h.toPx() * 0.7f + c * 120.dp.toPx()
                alpha = dl
            }
            .clickable(src, indication = null, onClick = onClick),
    ) {
        CardFrame(Modifier.size(w, h)) {
            Column(Modifier.align(Alignment.TopStart).padding(start = 13.dp, top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(label.toList().joinToString("\n"), style = Type.title.copy(color = t.ink, fontSize = 17.sp, lineHeight = 20.sp, letterSpacing = 0.sp))
                BasicText(suit.glyph, style = Type.index.copy(fontSize = 15.sp, color = if (suit.red) t.red else t.ink))
            }
            BasicText(
                latin,
                style = Type.pill.copy(color = t.goldDeep, fontSize = 15.sp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp, start = 18.dp),
            )
        }
    }
}

/** Faint gold dots that thicken toward the edges of the table. */
@Composable
internal fun DotVeil() {
    val t = LocalTable.current
    Canvas(Modifier.fillMaxSize()) {
        val stepPx = 26.dp.toPx()
        val r = 1.5.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height * 0.45f
        var y = stepPx / 2
        while (y < size.height) {
            var x = stepPx / 2
            while (x < size.width) {
                val nx = (x - cx) / (size.width / 2f)
                val ny = (y - cy) / (size.height / 2f)
                val dn = kotlin.math.sqrt(nx * nx + ny * ny)
                val a = ((dn - 0.28f) / 0.64f).coerceIn(0f, 1f) * 0.16f
                if (a > 0.005f) drawCircle(t.gold.copy(alpha = a), r, Offset(x, y))
                x += stepPx
            }
            y += stepPx
        }
    }
}

/** While a reply is being written, a soft gold light breathes along the screen edge. */
@Composable
private fun EdgeGlow(active: Boolean) {
    val t = LocalTable.current
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(600), label = "glow")
    if (shown <= 0.01f) return
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.fillMaxSize()) {
        val a = shown * pulse
        val layers = 8
        for (i in 0 until layers) {
            val inset = i * 3.dp.toPx()
            drawRoundRect(
                color = t.gold.copy(alpha = 0.32f * a * (1f - i / layers.toFloat())),
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = CornerRadius(28.dp.toPx()),
                style = Stroke(3.dp.toPx()),
            )
        }
    }
}

/** Turn-over progress of the drawn card, degrees 0..180: the shelf face shows until 90, the deck face after. */
private fun flipAngle(e: Float): Float {
    val x = ((e - 0.18f) / 0.55f).coerceIn(0f, 1f)
    return 180f * x * x * (3f - 2f * x)
}

private fun plateSide(e: Float) = flipAngle(e) <= 90f

private val PlateLift = 1f + RackGeometry.FOCUS_LIFT

/**
 * The drawn card's pose between the shelf and the deck: tipped and turned like the shelf plane at the start,
 * shrunk to plate size, turning face up halfway, swelling a little toward the camera in mid-air.
 */
private fun Modifier.flightPose(entrance: DeckEntrance, wPx: Float, hPx: Float): Modifier = this
    .graphicsLayer {
        val e = entrance.flight.value
        rotationX = RackGeometry.TILT * (1f - e)
        cameraDistance = RackGeometry.CAMERA * density
    }
    .graphicsLayer { rotationZ = RackGeometry.TURN * (1f - entrance.flight.value) }
    .graphicsLayer {
        val e = entrance.flight.value
        val a = flipAngle(e)
        rotationY = if (a <= 90f) a else a - 180f
        cameraDistance = 12f * density
        val bump = 1f + 0.06f * sin(PI.toFloat() * e.coerceIn(0f, 1f))
        // The shelf face lays itself out at plate size; the deck face is full size and scaled down instead.
        val pw = RackGeometry.PlateW.toPx() * PlateLift
        val ph = RackGeometry.PlateH.toPx() * PlateLift
        scaleX = bump * if (a > 90f) lerpFree(pw / wPx, 1f, e) else 1f
        scaleY = bump * if (a > 90f) lerpFree(ph / hPx, 1f, e) else 1f
    }

private fun lerpFree(a: Float, b: Float, t: Float) = a + (b - a) * t

/** The session plate as it looked on the shelf, growing toward card size while it flies. */
@Composable
private fun BoxScope.ShelfFace(entrance: DeckEntrance, wPx: Float, hPx: Float) {
    SessionPlate(
        title = entrance.plateTitle,
        index = entrance.plateIndex,
        hue = entrance.plateHue,
        glow = null,
        focused = { true },
        modifier = Modifier.align(Alignment.Center).layout { measurable, _ ->
            val e = entrance.flight.value
            val w = lerpFree(RackGeometry.PlateW.toPx() * PlateLift, wPx, e).roundToInt().coerceAtLeast(1)
            val h = lerpFree(RackGeometry.PlateH.toPx() * PlateLift, hPx, e).roundToInt().coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(w, h) { placeable.place(0, 0) }
        },
    )
}
