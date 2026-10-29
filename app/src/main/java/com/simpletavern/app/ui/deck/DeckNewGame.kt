package com.simpletavern.app.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpletavern.app.ui.card.Suit
import com.simpletavern.app.ui.setup.CharacterDraft
import com.simpletavern.app.ui.setup.NewGameViewModel
import com.simpletavern.app.ui.setup.PresetDraft
import com.simpletavern.app.ui.setup.Protocols
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type

/** Where the new-game card opens: the overview, or straight into writing a character or setting up a model. */
enum class NewGamePage { Pick, Character, Preset }

/** Interim new-chat flow: pick (or write) a character, pick (or set up) a model, deal. */
@Composable
internal fun NewGameCard(
    onClose: () -> Unit,
    onStarted: (String) -> Unit,
    startPage: NewGamePage = NewGamePage.Pick,
    initialCharacterId: String? = null,
    vm: NewGameViewModel = viewModel(),
) {
    val characters by vm.characters.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val line by vm.line.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(startPage) }
    var characterId by rememberSaveable { mutableStateOf(initialCharacterId) }
    var presetId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var character by remember { mutableStateOf(CharacterDraft()) }
    var preset by remember { mutableStateOf(PresetDraft()) }

    // With only one choice on the shelf, it is the choice.
    LaunchedEffect(characters) {
        if (characters.isNotEmpty() && characters.none { it.id == characterId }) characterId = characters.singleOrNull()?.id
    }
    LaunchedEffect(presets) {
        if (presets.isNotEmpty() && presets.none { it.id == presetId }) presetId = presets.singleOrNull()?.id
    }
    LaunchedEffect(Unit) { vm.clearLine() }

    BackHandler(enabled = page != NewGamePage.Pick) { page = NewGamePage.Pick; vm.clearLine() }

    FullCard {
        Column(Modifier.fillMaxSize()) {
            when (page) {
                NewGamePage.Pick -> {
                    CardHead("‹ 回到牌堆", onClose, "新局", Suit.Spade)
                    Scroll(Modifier.weight(1f)) {
                        SectionTitle("角色")
                        if (characters.isEmpty()) Note("牌盒里还没有角色。")
                        characters.forEach { c ->
                            Choice(c.name.ifBlank { "无名" }, c.id == characterId) { characterId = c.id }
                        }
                        AddRow("＋ 写一张新角色") { character = CharacterDraft(); vm.clearLine(); page = NewGamePage.Character }

                        SectionTitle("模型", Modifier.padding(top = 24.dp))
                        if (presets.isEmpty()) Note("还没有模型预设。")
                        presets.forEach { p ->
                            Choice(p.name.ifBlank { "未命名预设" }, p.id == presetId) { presetId = p.id }
                        }
                        AddRow("＋ 新建模型预设") { preset = PresetDraft(); vm.clearLine(); page = NewGamePage.Preset }
                        presetId?.let { id ->
                            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Pill("测试连接", { vm.testPreset(id) })
                            }
                            Note("测试会发一次真实请求，可能产生少量费用。")
                        }

                        SectionTitle("会话名", Modifier.padding(top = 24.dp))
                        Input(title, { title = it }, "新对话", Modifier.padding(top = 8.dp))

                        line?.let { Status(it.text, it.failed) }
                    }
                    val ready = characterId != null && presetId != null && !busy
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 24.dp, top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Pill(
                            if (busy) "发牌中…" else "开局 ♠",
                            { if (ready) vm.start(characterId!!, presetId!!, title, onStarted) },
                            filled = ready,
                        )
                    }
                }
                NewGamePage.Character -> {
                    CardHead("‹ 返回", { page = NewGamePage.Pick; vm.clearLine() }, "新角色", Suit.Heart)
                    Scroll(Modifier.weight(1f)) {
                        Labeled("名字") { Input(character.name, { character = character.copy(name = it) }, "角色的名字") }
                        Labeled("设定") { Input(character.description, { character = character.copy(description = it) }, "外貌、身份、背景……", lines = 5) }
                        Labeled("性格") { Input(character.personality, { character = character.copy(personality = it) }, "可留空", lines = 3) }
                        Labeled("情景") { Input(character.scenario, { character = character.copy(scenario = it) }, "可留空", lines = 3) }
                        Labeled("开场白") { Input(character.firstMessage, { character = character.copy(firstMessage = it) }, "新会话的第一张牌，可留空", lines = 4) }
                        line?.let { Status(it.text, it.failed) }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 24.dp, top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Pill("收进牌盒 ♥", { vm.saveCharacter(character) { id -> characterId = id; page = NewGamePage.Pick } }, filled = !busy)
                    }
                }
                NewGamePage.Preset -> {
                    CardHead("‹ 返回", { page = NewGamePage.Pick; vm.clearLine() }, "模型", Suit.Heart)
                    Scroll(Modifier.weight(1f)) {
                        Labeled("协议") {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Protocols.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        pair.forEach { p ->
                                            Pill(p.label, {
                                                val keepBase = preset.baseUrl.isNotBlank() && Protocols.none { it.base == preset.baseUrl }
                                                preset = preset.copy(protocol = p.id, baseUrl = if (keepBase) preset.baseUrl else p.base)
                                            }, filled = preset.protocol == p.id)
                                        }
                                    }
                                }
                            }
                        }
                        Labeled("地址") { Input(preset.baseUrl, { preset = preset.copy(baseUrl = it) }, "https://…", keyboard = KeyboardType.Uri) }
                        Labeled("模型") { Input(preset.model, { preset = preset.copy(model = it) }, "模型 ID，例如 deepseek-chat") }
                        Labeled("API Key") { KeyField(preset.key, { preset = preset.copy(key = it) }, Modifier.padding(top = 8.dp)) }
                        Labeled("名称") { Input(preset.name, { preset = preset.copy(name = it) }, "可留空，默认用模型名") }
                        Note("地址只填到域名或版本号即可，比如 https://api.deepseek.com，后面的 /chat/completions 会自动补上。", Modifier.padding(top = 12.dp))
                        line?.let { Status(it.text, it.failed) }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 24.dp, top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Pill("存下预设 ♥", { vm.savePreset(preset) { id -> presetId = id; preset = PresetDraft(); page = NewGamePage.Pick } }, filled = !busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun Scroll(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 30.dp, top = 18.dp, bottom = 12.dp),
    ) { content() }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        SectionTitle(label)
        content()
    }
}

@Composable
private fun Choice(text: String, on: Boolean, onPick: () -> Unit) {
    val t = LocalTable.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(if (on) "♠ " else "   ", style = Type.body.copy(color = t.red))
        BasicText(
            text,
            style = Type.body.copy(color = if (on) t.ink else t.ink.copy(alpha = 0.7f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    val t = LocalTable.current
    BasicText(
        text,
        style = Type.pill.copy(color = t.goldDeep),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
private fun Input(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.padding(top = 8.dp),
    lines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val t = LocalTable.current
    val style = Type.body.copy(color = t.ink, fontSize = 15.sp)
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = lines == 1,
        minLines = lines,
        textStyle = style,
        cursorBrush = SolidColor(t.red),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .border(1.dp, t.gold, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = t.ink.copy(alpha = 0.35f)))
                inner()
            }
        },
    )
}
