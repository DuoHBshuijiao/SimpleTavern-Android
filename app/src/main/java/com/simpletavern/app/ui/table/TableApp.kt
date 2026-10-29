package com.simpletavern.app.ui.table

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpletavern.app.ui.deck.DeckEntrance
import com.simpletavern.app.ui.deck.DeckScreen
import com.simpletavern.app.ui.deck.DeckViewModel
import com.simpletavern.app.ui.deck.DotVeil
import com.simpletavern.app.ui.deck.Pill
import com.simpletavern.app.ui.deck.SettingsCard
import com.simpletavern.app.ui.deck.rememberZipPicker
import com.simpletavern.app.ui.rack.RackCamera
import com.simpletavern.app.ui.rack.RackGeometry
import com.simpletavern.app.ui.rack.RackScreen
import com.simpletavern.app.ui.rack.RackViewModel
import com.simpletavern.app.ui.rack.Shelf
import com.simpletavern.app.ui.setup.SetupViewModel
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Scene { Rack, Deck }

/** Which character card is on the table: a new one (null id) or an existing one. */
private data class CharacterSheet(val id: String?)

/**
 * The whole table: the shelf at home, a deck drawn out of it, and the cards brought up over both
 * (a character card, settings). Every move between them is one continuous piece of card handling.
 */
@Composable
fun TableApp() {
    val t = LocalTable.current
    val deckVm: DeckViewModel = viewModel()
    val rackVm: RackViewModel = viewModel()
    val setupVm: SetupViewModel = viewModel()
    val deck by deckVm.state.collectAsStateWithLifecycle()
    val rack by rackVm.state.collectAsStateWithLifecycle()
    val imp by setupVm.import.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val camera = remember { RackCamera() }
    val entrance = remember { DeckEntrance() }
    val settings = remember { Reveal() }
    val characterCard = remember { Reveal() }
    var scene by remember { mutableStateOf(Scene.Rack) }
    var sheet by remember { mutableStateOf<CharacterSheet?>(null) }
    var pivot by remember { mutableStateOf(Offset.Unspecified) }
    var moving by remember { mutableStateOf(false) }
    val settingsOpen by remember { derivedStateOf { settings.open.targetValue > 0f && settings.leave.targetValue < 1f } }

    LaunchedEffect(rack.notice) { if (rack.notice != null) { delay(4_000); rackVm.clearNotice() } }

    fun openSettings() { scope.launch { settings.show(1400) } }
    fun closeSettings() { scope.launch { settings.hide(ms = 680, backMs = 500) } }
    fun openCharacter(id: String?) {
        sheet = CharacterSheet(id)
        scope.launch { characterCard.show(1700) }
    }
    fun closeCharacter() { scope.launch { characterCard.rewind(1000); sheet = null } }

    /** Pull a session out of the shelf: the plate lifts, turns over in flight and the deck fans out around it. */
    fun draw(chatId: String, title: String, index: Int, hue: Float) {
        if (moving) return
        moving = true
        deckVm.openChat(chatId)
        entrance.plateCenter = pivot
        entrance.plateTitle = title
        entrance.plateIndex = index
        entrance.plateHue = hue
        scope.launch {
            entrance.flight.snapTo(0f)
            entrance.deal.snapTo(0f)
            scene = Scene.Deck
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            coroutineScope {
                launch { entrance.flight.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 95f)) }
                launch {
                    delay(560)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    entrance.deal.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 120f))
                }
            }
            moving = false
        }
    }

    /** Gather the deck back into one card and lay it on its plate, wherever the session now sits on the shelf. */
    fun putBack() {
        if (moving) return
        moving = true
        scope.launch {
            val shelf = Shelf(rack.columns)
            val at = deck.chatId?.let { shelf.locate(it) }
            if (at != null) {
                camera.snapTo(at.first, at.second)
                val column = rack.columns[at.first]
                val k = at.second - RackGeometry.TOP
                entrance.plateTitle = column.sessions[k].title
                entrance.plateIndex = k
                entrance.plateHue = column.hue
            }
            entrance.plateCenter = pivot
            entrance.deal.animateTo(0f, tween(240))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            entrance.flight.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 140f))
            scene = Scene.Rack
            moving = false
        }
    }

    val rackShown by remember { derivedStateOf { scene == Scene.Rack || entrance.flight.value < 1f || entrance.deal.value < 1f } }

    BackHandler(enabled = scene == Scene.Deck && !moving && sheet == null && !settingsOpen) { putBack() }

    Box(Modifier.fillMaxSize().background(t.table)) {
        DotVeil()

        if (rackShown) {
            RackScreen(
                state = rack,
                camera = camera,
                away = { if (scene == Scene.Deck) entrance.flight.value else 0f },
                hiddenChatId = if (scene == Scene.Deck) deck.chatId else null,
                onOpen = { s ->
                    val at = Shelf(rack.columns).locate(s.id)
                    val column = at?.let { rack.columns[it.first] }
                    draw(s.id, s.title, (at?.second ?: RackGeometry.TOP) - RackGeometry.TOP, column?.hue ?: 0f)
                },
                onNewSession = { characterId ->
                    val hue = rack.columns.firstOrNull { it.characterId == characterId }?.hue ?: 0f
                    rackVm.newSession(
                        characterId,
                        onCreated = { id -> draw(id, "新对话", 0, hue) },
                        // No connection yet: settings is where presets are made.
                        onNeedsPreset = { openSettings() },
                    )
                },
                onNewCharacter = { openCharacter(null) },
                onSettings = { openSettings() },
                onPivot = { pivot = it },
                onEditCharacter = { openCharacter(it) },
                onDelete = { rackVm.deleteSession(it) },
                emptyExtras = {
                    val pick = rememberZipPicker(setupVm)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("写一张新角色 ♥", { openCharacter(null) }, filled = true)
                        Pill(if (imp.running) "导入中…" else "导入桌面 ZIP ♣", { if (!imp.running) pick() })
                    }
                    imp.line?.let {
                        BasicText(it, style = Type.sub.copy(color = if (imp.failed) t.red else t.textSoft), modifier = Modifier.padding(top = 8.dp))
                    }
                },
            )
        }

        if (scene == Scene.Deck) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - characterCard.open.value.coerceIn(0f, 1f) }) {
                DeckScreen(
                    state = deck,
                    onSend = deckVm::send,
                    onStop = deckVm::stop,
                    onSwipe = deckVm::swipe,
                    onClearNotice = deckVm::clearNotice,
                    onMenu = { putBack() },
                    onSettings = { openSettings() },
                    entrance = entrance,
                )
            }
        }

        BackHandler(enabled = sheet != null) { closeCharacter() }
        DropReveal(characterCard) {
            val s = sheet
            if (s != null) CharacterFace(characterId = s.id, onClose = { closeCharacter() }, onSaved = { closeCharacter() })
        }

        BackHandler(enabled = settingsOpen) { closeSettings() }
        CascadeReveal(settings) {
            SettingsCard(chatId = if (scene == Scene.Deck) deck.chatId else null, onClose = { closeSettings() })
        }
    }
}
