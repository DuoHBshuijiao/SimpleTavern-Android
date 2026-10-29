package com.simpletavern.app.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.simpletavern.app.ui.card.CardBack
import com.simpletavern.app.ui.card.CardFace
import com.simpletavern.app.ui.card.Suit
import com.simpletavern.app.ui.theme.Foil
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type
import com.simpletavern.core.model.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import com.simpletavern.app.ui.rack.RackGeometry
import com.simpletavern.app.ui.rack.SessionPlate
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

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
    var frontRect by remember { mutableStateOf<Rect?>(null) }
    var pileRect by remember { mutableStateOf<Rect?>(null) }
    var draft by rememberSaveable(state.chatId) { mutableStateOf("") }
    val pos = remember(state.chatId) { Animatable(0f) }
    val front by remember(state.chatId) { derivedStateOf { pos.value.roundToInt() } }
    val frontIndex = front.coerceIn(0, (n - 1).coerceAtLeast(0))

    // Open on the newest card; later, follow new cards unless the reader has wandered back into history.
    var seen by remember(state.chatId) { mutableIntStateOf(-1) }
    LaunchedEffect(state.chatId, n, ready) {
        if (!ready) return@LaunchedEffect
        val last = (n - 1).coerceAtLeast(0).toFloat()
        if (seen < 0) pos.snapTo(last)
        else if (n != seen && pos.value >= seen - 1.5f) pos.animateTo(last, spring(dampingRatio = 0.8f, stiffness = 180f))
        seen = n
    }

    LaunchedEffect(state.notice) {
        if (state.notice != null) { delay(4_000); onClearNotice() }
    }

    BackHandler(enabled = overlay != Overlay.None || composing) {
        if (overlay != Overlay.None) overlay = Overlay.None else composing = false
    }

    val send: (String) -> Boolean = { text ->
        val ok = onSend(text)
        if (ok) { draft = ""; composing = false; overlay = Overlay.None }
        ok
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            // The chrome around the deck slides in once the cards are dealt.
            Box(Modifier.graphicsLayer { val d = entrance.deal.value; alpha = d.coerceIn(0f, 1f); translationY = -16.dp.toPx() * (1f - d) }) {
                DeckHeader(state.characterName, state.title, if (n > 0) "No.${frontIndex + 1} / $n" else null)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.chatId != null) DeckStage(
                    cards = cards,
                    pos = pos,
                    entrance = entrance,
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
                    onBounds = { f, p -> frontRect = f; pileRect = p },
                )
            }
            Box(Modifier.graphicsLayer { alpha = entrance.deal.value.coerceIn(0f, 1f) }) {
                DeckFooter(state.notice, state.generating, onStop, onClearNotice)
            }
            Box(Modifier.graphicsLayer { val d = entrance.deal.value; alpha = d.coerceIn(0f, 1f); translationY = 40.dp.toPx() * (1f - d) }) {
                DeckTabs(onMenu = onMenu, onSettings = onSettings)
            }
        }

        EdgeGlow(state.generating)

        LiftOverlay(overlay == Overlay.Reader, frontRect) {
            val card = cards.getOrNull(readerIndex)
            if (card != null) {
                ReaderCard(
                    card = card,
                    rank = "${readerIndex + 1}",
                    isNewest = readerIndex == n - 1,
                    generating = state.generating,
                    onSwipe = { dir -> onSwipe(card, dir, readerIndex == n - 1) },
                    onClose = { overlay = Overlay.None },
                )
            }
        }
        LiftOverlay(overlay == Overlay.Editor, pileRect) {
            EditorCard(
                rank = "${n + 1}",
                draft = draft,
                onDraft = { draft = it },
                onCollapse = { overlay = Overlay.None },
                onSend = { send(draft) },
            )
        }
    }
}

@Composable
private fun DeckStage(
    cards: List<DeckCard>,
    pos: Animatable<Float, AnimationVector1D>,
    entrance: DeckEntrance,
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
    onBounds: (front: Rect, pile: Rect) -> Unit,
) {
    val n = cards.size
    val maxPos = (n - 1).coerceAtLeast(0).toFloat()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val landed by remember { derivedStateOf { entrance.flight.value >= 1f && entrance.deal.value >= 1f } }
    val arrived by remember { derivedStateOf { entrance.flight.value >= 1f } }
    var stageCenter by remember { mutableStateOf(Offset.Unspecified) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { stageCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
            // While a card flies in from the shelf it may cross the header; afterwards the far edges are clipped.
            .then(if (arrived) Modifier.clipToBounds() else Modifier),
    ) {
        val cardW = (maxWidth - 56.dp).coerceAtMost(420.dp)
        val cardH = minOf(cardW * 1.08f, maxHeight * 0.5f)
        val wPx = with(density) { cardW.toPx() }
        val hPx = with(density) { cardH.toPx() }
        val baseY = with(density) { (maxHeight * -0.06f).toPx() }
        val step = hPx * 0.48f
        LaunchedEffect(stageCenter, wPx, hPx, baseY) {
            if (stageCenter.isSpecified) {
                val c = stageCenter + Offset(0f, baseY)
                onBounds(Rect(c.x - wPx / 2, c.y - hPx / 2, c.x + wPx / 2, c.y + hPx / 2),
                    Rect(c.x - wPx / 2, c.y + hPx * 0.07f, c.x + wPx / 2, c.y + hPx * 0.93f))
            }
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
                if (composing && k < n) d -= 0.7f
                if (composing && k > n) d -= 0.6f
                if (abs(d) > 7.5f) continue
                if (composing && k == n) continue
                val (yf, r) = DeckGeometry.slot(d)
                val isFront = k < n && k == front && !composing
                val flying = k == flyer && !composing
                key(if (k < n) cards[k].key else "back-${k - n}") {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(cardW, cardH)
                            .zIndex(if (flying) 150f else 100f - abs(d) * 10f)
                            .graphicsLayer {
                                val s = if (flying) 1f else entrance.deal.value
                                translationY = baseY + yf * hPx * s
                                if (flying) {
                                    val e = entrance.flight.value
                                    val from = entrance.plateCenter
                                    if (from.isSpecified && stageCenter.isSpecified) {
                                        translationX = (from.x - stageCenter.x) * (1f - e)
                                        translationY += (from.y - stageCenter.y - baseY) * (1f - e)
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
                                if (k == n && !composing) Modifier.pointerInput(Unit) {
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

            if (composing) {
                DrawnCard(
                    rank = "${n + 1}",
                    draft = draft,
                    onDraft = onDraft,
                    onClose = onCloseCompose,
                    onExpand = onExpand,
                    onSend = onSend,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(cardW)
                        .height(cardH * 0.86f)
                        .zIndex(200f)
                        .graphicsLayer { translationY = baseY + hPx * 0.5f },
                )
            }
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

@Composable
private fun DeckHeader(name: String, title: String, position: String?) {
    val t = LocalTable.current
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("❦ SIMPLETAVERN ❦", style = Type.ornament.copy(color = t.goldDeep))
        BasicText(name.ifBlank { " " }, style = Type.title.copy(color = t.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(title.takeIf { it.isNotBlank() }, position).joinToString(" · ")
        BasicText(sub.ifBlank { " " }, style = Type.sub.copy(color = t.textSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Rule(Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    val t = LocalTable.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, t.gold))))
        BasicText("◆", style = Type.ornament.copy(color = t.gold, fontSize = 9.sp), modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(t.gold, Color.Transparent))))
    }
}

@Composable
private fun DeckFooter(notice: String?, generating: Boolean, onStop: () -> Unit, onClear: () -> Unit) {
    val t = LocalTable.current
    Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        when {
            notice != null -> BasicText(
                notice,
                style = Type.sub.copy(color = t.red),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onClear),
            )
            generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("落笔中…", style = Type.sub.copy(color = t.textSoft))
                Spacer(Modifier.width(12.dp))
                Pill("停 ■", onStop)
            }
        }
    }
}

@Composable
private fun DeckTabs(onMenu: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tab("菜单", "menu", Modifier.weight(1f), onMenu)
        Tab("设置", "setting", Modifier.weight(1f), onSettings)
    }
}

@Composable
private fun Tab(label: String, latin: String, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTable.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val lift by animateFloatAsState(if (pressed) 1f else 0f, tween(180), label = "tab")
    val top = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    val inner = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    Box(
        modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationX = lerp(16f, 4f, lift) * DeckGeometry.TILT
                translationY = -6.dp.toPx() * lift
                cameraDistance = 3f
            }
            .clip(top)
            .background(Foil)
            .padding(start = 2.dp, end = 2.dp, top = 2.dp)
            .clip(inner)
            .background(t.paper)
            .clickable(src, indication = null, onClick = onClick)
            .padding(top = 11.dp, bottom = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            buildAnnotatedString {
                append(label)
                withStyle(SpanStyle(fontSize = 11.sp, fontStyle = FontStyle.Italic)) { append("  $latin") }
            },
            style = Type.tab.copy(color = t.goldDeep),
        )
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
