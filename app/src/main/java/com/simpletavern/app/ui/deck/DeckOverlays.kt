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
            .imePadding()
            .padding(10.dp),
    ) {
        CardFrame(Modifier.fillMaxSize()) { content() }
    }
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
        Column(Modifier.fillMaxSize()) {
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

/** Interim settings: just enough to bring the desktop data over and give its connections a key. The shuffle reveal comes later. */
@Composable
internal fun SettingsCard(
    chatId: String?,
    onClose: () -> Unit,
    background: Boolean = true,
    setup: SetupViewModel = viewModel(),
) {
    val imp by setup.import.collectAsStateWithLifecycle()
    val conn by setup.connection.collectAsStateWithLifecycle()
    val pickZip = rememberZipPicker(setup)
    var key by remember(conn.credentialId) { mutableStateOf("") }
    LaunchedEffect(chatId) { setup.loadConnection(chatId) }

    FullCard(background) {
        Column(Modifier.fillMaxSize()) {
            CardHead("‹ 回到牌堆", onClose, "设置", Suit.Heart)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 32.dp, end = 30.dp, top = 18.dp, bottom = 26.dp),
            ) {
                SectionTitle("牌局来源")
                Note("在桌面端「设置 → 备份 → 包含全部聊天记录」导出 ZIP，传到手机上，再从这里选它。重复导入会合并，不会覆盖你在手机上改过的内容。")
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (imp.running) "导入中…" else "选择 ZIP ♣", { if (!imp.running) pickZip() }, filled = !imp.running)
                }
                imp.line?.let { Status(it, imp.failed) }

                SectionTitle("这局的连接", Modifier.padding(top = 28.dp))
                when {
                    chatId == null -> Note("桌上还没有牌局，导入之后再来配。")
                    conn.loading -> Note("读取中…")
                    conn.credentialId == null -> Unit
                    else -> {
                        Field("预设", conn.presetName)
                        Field("协议", conn.protocol)
                        Field("模型", conn.model.ifBlank { "未设置" })
                        Field("地址", conn.baseUrl.ifBlank { "未设置" })
                        Field("Key", if (conn.hasKey) "已存 ✓" else "未填", warn = !conn.hasKey)
                        Note("API Key 不随桌面数据迁移，需要在这里单独填一次。", Modifier.padding(top = 10.dp))
                        KeyField(key, { key = it }, Modifier.padding(top = 10.dp))
                        Row(
                            Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill("存下 Key", { setup.saveKey(key); key = "" }, filled = key.isNotBlank())
                            Pill(if (conn.testing) "测试中…" else "测试连接", { setup.testConnection() })
                        }
                        Note("测试会发一次真实请求，可能产生少量费用。", Modifier.padding(top = 8.dp))
                    }
                }
                conn.line?.let { Status(it, conn.failed) }
            }
        }
    }
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
internal fun KeyField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
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
                if (value.isEmpty()) BasicText("粘贴 API Key", style = Type.body.copy(color = t.ink.copy(alpha = 0.35f), fontSize = 15.sp))
                inner()
            }
        },
    )
}
