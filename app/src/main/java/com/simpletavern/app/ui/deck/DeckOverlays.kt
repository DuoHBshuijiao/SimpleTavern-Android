package com.simpletavern.app.ui.deck

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpletavern.app.ui.setup.PresetDraft
import com.simpletavern.app.ui.setup.Protocols
import com.simpletavern.app.ui.setup.SetupViewModel
import com.simpletavern.app.ui.card.CardFrame
import com.simpletavern.app.ui.card.Suit
import com.simpletavern.app.ui.card.illuminated
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type
import com.simpletavern.core.model.ChatRole

/** A card blown up to the whole screen. */
@Composable
internal fun FullCard(background: Boolean = true, content: @Composable () -> Unit) {
    val t = LocalTable.current
    Box(
        Modifier
            .fillMaxSize()
            .then(if (background) Modifier.background(t.table) else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(10.dp),
    ) {
        CardFrame(Modifier.fillMaxSize()) { content() }
    }
}

/** A playing card brought up to fill the stage, its page laid on the face. Taps don't fall through it. */
@Composable
internal fun FaceCard(content: @Composable () -> Unit) {
    CardFrame(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {}) { content() }
}

@Composable
internal fun CardHead(left: String, onLeft: () -> Unit, rank: String, suit: Suit) {
    val t = LocalTable.current
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 26.dp, top = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(left, style = Type.pill.copy(color = t.goldDeep), modifier = Modifier.clickable(onClick = onLeft).padding(6.dp))
        Spacer(Modifier.weight(1f))
        BasicText(rank, style = Type.index.copy(color = t.ink, fontSize = 14.sp))
        BasicText(" ${suit.glyph}", style = Type.index.copy(color = if (suit.red) t.red else t.ink, fontSize = 14.sp))
    }
}

@Composable
internal fun ReaderCard(
    card: DeckCard,
    rank: String,
    isNewest: Boolean,
    generating: Boolean,
    onSwipe: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val t = LocalTable.current
    val suit = if (card.role == ChatRole.user) Suit.Spade else Suit.Heart
    FullCard(background = false) {
        Column(Modifier.fillMaxSize()) {
            CardHead("‹ 收回牌堆", onClose, "No.$rank", suit)
            Column(Modifier.weight(1f).padding(start = 32.dp, end = 30.dp, top = 18.dp)) {
                BasicText(card.who, style = Type.who.copy(color = t.goldDeep, fontSize = 15.sp))
                Box(Modifier.weight(1f).padding(top = 10.dp).verticalScroll(rememberScrollState())) {
                    BasicText(illuminated(card.text.ifBlank { "……" }, t.red), style = Type.bodyLarge.copy(color = t.ink))
                }
            }
            val canRedraw = isNewest && card.role == ChatRole.assistant && !generating && !card.live
            if (card.candidateCount > 1 || canRedraw) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 26.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (card.candidateCount > 1) {
                        Pill("‹", { onSwipe(-1) })
                        BasicText("${card.candidateIndex + 1} / ${card.candidateCount}", style = Type.pill.copy(color = t.goldDeep))
                    }
                    val atLast = card.candidateIndex >= card.candidateCount - 1
                    when {
                        !atLast -> Pill("›", { onSwipe(1) })
                        canRedraw -> Pill("重抽 ↻", { onSwipe(1) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditorCard(
    rank: String,
    draft: String,
    onDraft: (String) -> Unit,
    onCollapse: () -> Unit,
    onSend: () -> Unit,
) {
    val t = LocalTable.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    FullCard(background = false) {
        Column(Modifier.fillMaxSize().imePadding()) {
            CardHead("‹ 收起", onCollapse, "No.$rank", Suit.Spade)
            Column(Modifier.weight(1f).padding(start = 32.dp, end = 30.dp, top = 18.dp)) {
                BasicText("你 ❦ 新牌", style = Type.who.copy(color = t.goldDeep, fontSize = 15.sp))
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = Type.bodyLarge.copy(color = t.ink),
                    cursorBrush = SolidColor(t.red),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) BasicText("写下这一张牌…", style = Type.bodyLarge.copy(color = t.ink.copy(alpha = 0.35f)))
                        inner()
                    },
                )
            }
            Row(Modifier.fillMaxWidth().padding(end = 28.dp, bottom = 24.dp), horizontalArrangement = Arrangement.End) {
                Pill("出牌 ♠", onSend, filled = true)
            }
        }
    }
}

/**
 * The settings card: model presets (the only place connections and keys are set), which preset the open
 * session plays on, and bringing desktop data over.
 */
@Composable
internal fun SettingsCard(
    chatId: String?,
    onClose: () -> Unit,
    setup: SetupViewModel = viewModel(),
) {
    val t = LocalTable.current
    val imp by setup.import.collectAsStateWithLifecycle()
    val presets by setup.presets.collectAsStateWithLifecycle()
    val keys by setup.keyStatus.collectAsStateWithLifecycle()
    val chat by setup.chat.collectAsStateWithLifecycle()
    val line by setup.line.collectAsStateWithLifecycle()
    val busy by setup.busy.collectAsStateWithLifecycle()
    val pickZip = rememberZipPicker(setup)
    var editing by remember { mutableStateOf<PresetDraft?>(null) }
    LaunchedEffect(chatId) { setup.loadChat(chatId); setup.clearLine() }

    FaceCard {
        Column(Modifier.fillMaxSize()) {
            CardHead("‹ 收起", onClose, "设置", Suit.Heart)
            Column(
                Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 30.dp, end = 28.dp, top = 14.dp, bottom = 26.dp),
            ) {
                SectionTitle("模型预设")
                Note("连接只在这里设：协议、地址、模型和 API Key。Key 加密存在本机，不会随数据迁移。")
                presets.forEach { p ->
                    val open = editing?.id == p.id
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { editing = if (open) null else setup.draftOf(p) }, verticalAlignment = Alignment.CenterVertically) {
                            val inUse = chat.chatId != null && chat.presetId == p.id
                            BasicText(if (inUse) "♠ " else "◇ ", style = Type.body.copy(color = if (inUse) t.red else t.goldDeep, fontSize = 15.sp))
                            Column(Modifier.weight(1f)) {
                                BasicText(p.name, style = Type.body.copy(color = t.ink, fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val proto = Protocols.firstOrNull { it.id == p.protocol }?.label ?: p.protocol
                                BasicText("$proto · ${p.model.ifBlank { "未设模型" }}", style = Type.sub.copy(color = t.ink.copy(alpha = 0.6f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            BasicText(if (keys[p.id] == true) "Key ✓" else "缺 Key", style = Type.sub.copy(color = if (keys[p.id] == true) t.goldDeep else t.red))
                        }
                        if (open) {
                            PresetEditor(
                                draft = editing!!,
                                onDraft = { editing = it },
                                hasKey = keys[p.id] == true,
                                busy = busy,
                                onSave = { setup.savePreset(it) { editing = null } },
                                onTest = { setup.testPreset(p.id) },
                                onDelete = { setup.deletePreset(p); editing = null },
                                onUseHere = if (chat.chatId != null && chat.presetId != p.id) ({ setup.useForChat(p.id) }) else null,
                            )
                        }
                    }
                }
                if (editing?.id == null && editing != null) {
                    PresetEditor(
                        draft = editing!!,
                        onDraft = { editing = it },
                        hasKey = false,
                        busy = busy,
                        onSave = { setup.savePreset(it) { id -> editing = null; if (chat.chatId != null && chat.presetId == null) setup.useForChat(id) } },
                        onTest = null,
                        onDelete = null,
                        onUseHere = null,
                    )
                } else {
                    AddRow("＋ 新建预设") { setup.clearLine(); editing = PresetDraft() }
                }
                line?.let { Status(it.text, it.failed) }

                SectionTitle("牌局来源", Modifier.padding(top = 28.dp))
                Note("在桌面端「设置 → 备份 → 包含全部聊天记录」导出 ZIP，传到手机上，再从这里选它。重复导入会合并，不会覆盖你在手机上改过的内容。")
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (imp.running) "导入中…" else "选择 ZIP ♣", { if (!imp.running) pickZip() }, filled = !imp.running)
                }
                imp.line?.let { Status(it, imp.failed) }
            }
        }
    }
}

@Composable
private fun PresetEditor(
    draft: PresetDraft,
    onDraft: (PresetDraft) -> Unit,
    hasKey: Boolean,
    busy: Boolean,
    onSave: (PresetDraft) -> Unit,
    onTest: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onUseHere: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp)) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Protocols.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { p ->
                        Pill(p.label, {
                            val keepBase = draft.baseUrl.isNotBlank() && Protocols.none { it.base == draft.baseUrl }
                            onDraft(draft.copy(protocol = p.id, baseUrl = if (keepBase) draft.baseUrl else p.base))
                        }, filled = draft.protocol == p.id)
                    }
                }
            }
        }
        Input(draft.baseUrl, { onDraft(draft.copy(baseUrl = it)) }, "地址，如 https://api.deepseek.com", keyboard = KeyboardType.Uri)
        Input(draft.model, { onDraft(draft.copy(model = it)) }, "模型 ID，如 deepseek-chat")
        KeyField(draft.key, { onDraft(draft.copy(key = it)) }, Modifier.padding(top = 8.dp), if (hasKey) "已存 Key，留空则不改" else "API Key")
        Input(draft.name, { onDraft(draft.copy(name = it)) }, "名称，可留空")
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (busy) "…" else "存下", { onSave(draft) }, filled = true)
            if (onTest != null) Pill("测试连接", onTest)
            if (onUseHere != null) Pill("这一局用它", onUseHere)
            if (onDelete != null) Pill("删除", onDelete)
        }
        if (onTest != null) Note("测试会发一次真实请求，可能产生少量费用。")
    }
}

@Composable
internal fun AddRow(text: String, onClick: () -> Unit) {
    val t = LocalTable.current
    BasicText(
        text,
        style = Type.pill.copy(color = t.goldDeep),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
internal fun Input(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.padding(top = 8.dp),
    lines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    style: androidx.compose.ui.text.TextStyle? = null,
) {
    val t = LocalTable.current
    val s = style ?: Type.body.copy(color = t.ink, fontSize = 15.sp)
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = lines == 1,
        minLines = lines,
        textStyle = s,
        cursorBrush = SolidColor(t.red),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .border(1.dp, t.gold.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) BasicText(placeholder, style = s.copy(color = t.ink.copy(alpha = 0.35f)))
                inner()
            }
        },
    )
}

/** Opens the system file picker for a desktop export and hands the pick to the importer. */
@Composable
internal fun rememberZipPicker(setup: SetupViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setup.importArchive(uri)
    }
    return { launcher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val t = LocalTable.current
    BasicText("❦ $text", style = Type.who.copy(color = t.goldDeep, fontSize = 15.sp), modifier = modifier)
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    val t = LocalTable.current
    BasicText(text, style = Type.body.copy(color = t.ink.copy(alpha = 0.7f), fontSize = 14.sp), modifier = modifier.padding(top = 6.dp))
}

@Composable
internal fun Status(text: String, failed: Boolean) {
    val t = LocalTable.current
    BasicText(text, style = Type.sub.copy(color = if (failed) t.red else t.goldDeep, fontSize = 14.sp), modifier = Modifier.padding(top = 10.dp))
}

@Composable
internal fun Field(label: String, value: String, warn: Boolean = false) {
    val t = LocalTable.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        BasicText(label, style = Type.sub.copy(color = t.goldDeep, fontSize = 14.sp), modifier = Modifier.width(52.dp))
        BasicText(
            value,
            style = Type.body.copy(color = if (warn) t.red else t.ink, fontSize = 14.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun KeyField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "粘贴 API Key") {
    val t = LocalTable.current
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = Type.body.copy(color = t.ink, fontSize = 15.sp),
        cursorBrush = SolidColor(t.red),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .border(1.dp, t.gold, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) BasicText(placeholder, style = Type.body.copy(color = t.ink.copy(alpha = 0.35f), fontSize = 15.sp))
                inner()
            }
        },
    )
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
