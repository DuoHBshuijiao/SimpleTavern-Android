package com.simpletavern.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.simpletavern.app.ui.card.Suit
import com.simpletavern.app.ui.deck.CardHead
import com.simpletavern.app.ui.deck.DeckCard
import com.simpletavern.app.ui.deck.DeckEntrance
import com.simpletavern.app.ui.deck.DeckScreen
import com.simpletavern.app.ui.deck.DeckUiState
import com.simpletavern.app.ui.deck.DotVeil
import com.simpletavern.app.ui.deck.FaceCard
import com.simpletavern.app.ui.rack.RackCamera
import com.simpletavern.app.ui.rack.RackColumn
import com.simpletavern.app.ui.rack.RackGeometry
import com.simpletavern.app.ui.rack.RackScreen
import com.simpletavern.app.ui.rack.RackSession
import com.simpletavern.app.ui.rack.RackUiState
import com.simpletavern.app.ui.rack.Recency
import com.simpletavern.app.ui.table.CascadeReveal
import com.simpletavern.app.ui.table.DropReveal
import com.simpletavern.app.ui.table.Reveal
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.TavernTheme
import com.simpletavern.app.ui.theme.Type
import com.simpletavern.core.model.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the table's scenes and the frames of its transitions to PNGs for review:
 * ./gradlew :app:testDebugUnitTest, then look in app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h873dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Composable
    private fun Table(content: @Composable () -> Unit) {
        TavernTheme {
            val t = LocalTable.current
            Box(Modifier.fillMaxSize().background(t.table)) {
                DotVeil()
                content()
            }
        }
    }

    @Test
    fun deck() {
        compose.setContent {
            Table { DeckScreen(Samples.deck, { true }, {}, { _, _, _ -> }, {}, {}, {}) }
        }
        shot("deck")
    }

    /** Tapping the front card lifts it into the full-screen reader. */
    @Test
    fun pickUpCard() {
        compose.setContent {
            Table { DeckScreen(Samples.deck, { true }, {}, { _, _, _ -> }, {}, {}, {}) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * 0.42f)) }
        listOf(48L, 96L, 160L, 900L).forEach { ms ->
            compose.mainClock.advanceTimeBy(ms)
            shot("read_$ms")
        }
        compose.mainClock.autoAdvance = true
    }

    /** Tapping the face-down pile rolls its near edge up into the writing face. */
    @Test
    fun rollUpToWrite() {
        compose.setContent {
            Table { DeckScreen(Samples.deck, { true }, {}, { _, _, _ -> }, {}, {}, {}) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * 0.79f)) }
        listOf(16L, 32L, 32L, 80L, 150L, 150L, 400L).forEachIndexed { i, ms ->
            compose.mainClock.advanceTimeBy(ms)
            shot("write_$i")
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun rack() {
        val camera = RackCamera()
        runBlocking { camera.snapTo(1, RackGeometry.TOP) }
        compose.setContent {
            Table { RackScreen(Samples.rack, camera, { 0f }, null, {}, {}, {}, {}) }
        }
        shot("rack")
    }

    /** Taps land on the right plate through the tipped, turned shelf. */
    @Test
    fun rackTapHitsFocusedPlate() {
        val camera = RackCamera()
        runBlocking { camera.snapTo(1, RackGeometry.TOP) }
        var opened: String? = null
        var pivot = Offset.Zero
        compose.setContent {
            Table { RackScreen(Samples.rack, camera, { 0f }, null, { opened = it.id }, {}, {}, {}, onPivot = { pivot = it }) }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(pivot) }
        compose.waitForIdle()
        org.junit.Assert.assertEquals("s1-0", opened)
        // A tap on the plate one session further down focuses it instead of opening.
        opened = null
        compose.onRoot().performTouchInput { click(pivot + Offset(80f * density, 110f * density)) }
        compose.waitForIdle()
        org.junit.Assert.assertNull(opened)
        org.junit.Assert.assertEquals(RackGeometry.TOP + 1f, camera.y.value, 0.01f)
    }

    @Test
    fun rackEmpty() {
        val camera = RackCamera()
        compose.setContent {
            Table { RackScreen(RackUiState(loaded = true), camera, { 0f }, null, {}, {}, {}, {}) }
        }
        shot("rack_empty")
    }

    /** The plate lifting off the shelf, turning over, landing, and the deck fanning out. */
    @Test
    fun drawDeck() {
        val camera = RackCamera()
        runBlocking { camera.snapTo(1, RackGeometry.TOP) }
        val entrance = DeckEntrance()
        entrance.plateTitle = Samples.rack.columns[1].sessions[0].title
        entrance.plateIndex = 0
        entrance.plateHue = Samples.rack.columns[1].hue
        compose.setContent {
            Table {
                RackScreen(Samples.rack, camera, { entrance.flight.value }, "s1-0", {}, {}, {}, {}, onPivot = { entrance.plateCenter = it })
                DeckScreen(Samples.deck, { true }, {}, { _, _, _ -> }, {}, {}, {}, entrance)
            }
        }
        val frames = listOf(0f to 0f, 0.25f to 0f, 0.45f to 0f, 0.65f to 0f, 1f to 0f, 1f to 0.4f, 1f to 1f)
        frames.forEachIndexed { i, (f, d) ->
            compose.runOnIdle { runBlocking { entrance.flight.snapTo(f); entrance.deal.snapTo(d) } }
            compose.waitForIdle()
            shot("draw_${i}_f${(f * 100).toInt()}_d${(d * 100).toInt()}")
        }
    }

    @Composable
    private fun FakeFace(title: String) {
        FaceCard {
            Column {
                CardHead("‹ 收起", {}, title, Suit.Heart)
                BasicText("❦ 模型预设", style = Type.who, modifier = Modifier.padding(32.dp))
            }
        }
    }

    /** Settings: a cascade from the upper packet into a pile, the top card turned up; then flipped back and dropped. */
    @Test
    fun cascade() {
        val reveal = Reveal()
        compose.setContent {
            Table {
                RackScreen(Samples.rack, remember { RackCamera() }, { 0f }, null, {}, {}, {}, {})
                CascadeReveal(reveal) { FakeFace("设置") }
            }
        }
        listOf(0.08f, 0.22f, 0.36f, 0.5f, 0.64f, 0.78f, 0.9f, 1f).forEach { o ->
            compose.runOnIdle { runBlocking { reveal.open.snapTo(o) } }
            compose.waitForIdle()
            shot("cascade_${(o * 100).toInt()}")
        }
        listOf(0.2f, 0.4f, 0.6f, 0.85f).forEach { l ->
            compose.runOnIdle { runBlocking { reveal.leave.snapTo(l) } }
            compose.waitForIdle()
            shot("cascade_leave_${(l * 100).toInt()}")
        }
    }

    /** A new character: the deck drops onto the table, the camera pushes in, the top card turns over. */
    @Test
    fun dropDeck() {
        val reveal = Reveal()
        compose.setContent {
            Table {
                RackScreen(RackUiState(loaded = true), remember { RackCamera() }, { 0f }, null, {}, {}, {}, {})
                DropReveal(reveal) { FakeFace("新角色") }
            }
        }
        listOf(0.1f, 0.25f, 0.38f, 0.46f, 0.6f, 0.72f, 0.86f, 1f).forEach { o ->
            compose.runOnIdle { runBlocking { reveal.open.snapTo(o) } }
            compose.waitForIdle()
            shot("drop_${(o * 100).toInt()}")
        }
    }

    /** Long-pressing a session lifts it over a hidden drawer and asks before discarding. */
    @Test
    fun discardDrawer() {
        val camera = RackCamera()
        runBlocking { camera.snapTo(1, RackGeometry.TOP) }
        var pivot = Offset.Zero
        compose.setContent {
            Table { RackScreen(Samples.rack, camera, { 0f }, null, {}, {}, {}, {}, onPivot = { pivot = it }) }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { longClick(pivot) }
        compose.waitForIdle()
        shot("discard")
    }
}

object Samples {
    private val lines = listOf(
        ChatRole.assistant to "雨下了一整夜。她推开酒馆的门，把湿透的斗篷挂在炉边，“老规矩，一杯热的。”",
        ChatRole.user to "我把杯子推过去：“今晚不太平，城门那边又封了。”",
        ChatRole.assistant to "她没急着喝，指尖在杯沿绕了一圈。“封门是做给外人看的。真正的事，从来不走城门。”",
        ChatRole.user to "“那走哪里？”我压低声音。",
        ChatRole.assistant to "她笑了，从袖中摸出一张牌，背面朝上推到你面前。“走这里。翻开它之前，先想清楚——翻开了，就回不去了。”",
    )

    val deck = DeckUiState(
        loaded = true,
        chatId = "s1-0",
        title = "雨夜的牌局",
        characterName = "薇洛",
        cards = lines.mapIndexed { i, (role, text) ->
            DeckCard("k$i", "n$i", role, if (role == ChatRole.user) "你" else "薇洛", text,
                candidateIndex = 0, candidateCount = if (i == lines.lastIndex) 3 else 1, live = false, thinking = false)
        },
        cardsFor = "s1-0",
    )

    private val titles = listOf("雨夜的牌局", "旧港之后", "未寄出的信", "灯塔值夜", "半山茶馆", "回声", "雪线以上")

    val rack = RackUiState(
        loaded = true,
        columns = listOf("林白" to 20f, "薇洛" to 340f, "灰鸦" to 210f, "渡" to 150f).mapIndexed { i, (name, hue) ->
            val count = listOf(2, 4, 1, 3)[i]
            RackColumn("c$i", name, hue, List(count) { k ->
                RackSession("s$i-$k", titles[(i * 2 + k) % titles.size],
                    when (k) { 0 -> Recency.Warm; 1 -> Recency.Cool; else -> Recency.None }, null)
            })
        },
    )
}
