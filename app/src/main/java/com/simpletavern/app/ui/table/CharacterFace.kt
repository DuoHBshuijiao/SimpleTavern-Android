package com.simpletavern.app.ui.table

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpletavern.app.ui.card.Suit
import com.simpletavern.app.ui.deck.AddRow
import com.simpletavern.app.ui.deck.CardHead
import com.simpletavern.app.ui.deck.FaceCard
import com.simpletavern.app.ui.deck.Input
import com.simpletavern.app.ui.deck.Note
import com.simpletavern.app.ui.deck.Pill
import com.simpletavern.app.ui.deck.SectionTitle
import com.simpletavern.app.ui.deck.Status
import com.simpletavern.app.ui.setup.CharacterViewModel
import com.simpletavern.app.ui.theme.Foil
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type

/**
 * The character card itself: portrait and name at the head, then everything that goes into the conversation,
 * in the desktop editor's order. A new card and an existing one are written on the same face.
 */
@Composable
fun CharacterFace(characterId: String?, onClose: () -> Unit, onSaved: (String) -> Unit, vm: CharacterViewModel = viewModel()) {
    val t = LocalTable.current
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(characterId) { vm.open(characterId) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setAvatar(uri) }
    val d = s.draft

    FaceCard {
        Column(Modifier.fillMaxSize().imePadding()) {
            CardHead("‹ 收起", onClose, if (s.id == null) "新角色" else "角色", Suit.Heart)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, end = 26.dp, top = 12.dp, bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(84.dp, 112.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Foil)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(t.cloth)
                            .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val img = s.avatar
                        if (img != null) Image(img, contentDescription = "头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else BasicText("＋\n头像", style = Type.sub.copy(color = t.gold, fontSize = 13.sp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Input(d.name, { vm.edit(d.copy(name = it)) }, "名字", modifier = Modifier,
                            style = Type.title.copy(color = t.ink, fontSize = 22.sp))
                        Input(d.description, { vm.edit(d.copy(description = it)) }, "简介：一两句话", lines = 3)
                    }
                }
                Labeled("性格 / 外貌") { Input(d.personality, { vm.edit(d.copy(personality = it)) }, "详细设定……", lines = 5) }
                Labeled("情景 / 世界观") { Input(d.scenario, { vm.edit(d.copy(scenario = it)) }, "世界背景……", lines = 4) }
                Labeled("系统提示词") { Input(d.systemPrompt, { vm.edit(d.copy(systemPrompt = it)) }, "回复格式要求……", lines = 3) }
                Labeled("首句") {
                    Input(d.firstMessage, { vm.edit(d.copy(firstMessage = it)) }, "新会话的第一张牌……", lines = 4)
                    Note("支持 {{user}} 占位符。")
                }
                Labeled("额外首句") {
                    d.extraFirstMessages.forEachIndexed { i, text ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Input(text, { v -> vm.edit(d.copy(extraFirstMessages = d.extraFirstMessages.toMutableList().also { it[i] = v })) },
                                "另一种开场……", modifier = Modifier.weight(1f).padding(top = 8.dp), lines = 2)
                            Spacer(Modifier.width(8.dp))
                            Pill("✕", { vm.edit(d.copy(extraFirstMessages = d.extraFirstMessages.toMutableList().also { it.removeAt(i) })) })
                        }
                    }
                    AddRow("＋ 再加一种开场") { vm.edit(d.copy(extraFirstMessages = d.extraFirstMessages + "")) }
                }
                Labeled("示例对话") { Input(d.exampleDialogue, { vm.edit(d.copy(exampleDialogue = it)) }, "示例对话……", lines = 4) }
                s.line?.let { Status(it.text, it.failed) }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, bottom = 22.dp, top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Pill(if (s.busy) "…" else if (s.id == null) "收进牌盒 ♥" else "存下 ♥", { vm.save(onSaved) }, filled = true)
            }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        SectionTitle(label)
        content()
    }
}
