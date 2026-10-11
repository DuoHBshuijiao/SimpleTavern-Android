# 聊天、上下文与媒体

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="chat-context"></a>

## 普通聊天与群聊上下文

2026-10-08 正则后续：来源 Kotlin 规则的范围/仅显示/思考、普通/独立助手上下文和工具续轮接线，导入历史避免二次替换；canonical 规则集合已按 ID/字段合并。工具续轮新助手消息也按原时间套模板，下文全部正则待补由此局部更新；全片段/版本、巨大历史和原生请求仍未证明，见 [CHAT.md#content-regex](CHAT.md#content-regex)。

### 来源助手记忆与配置逐字段合并（2026-10-07）

RikkaHub 数据库记忆现在转为 ImportedMemoryLibrary，保存在同来源的独立 AssistantConfig 中，使用 source_memory / memories 精确映射；条目按来源行 ID 保存 assistantSourceId/content。没有新增表或把记忆复制进每个新聊天。enableMemory=false 不注入，true 时 useGlobalMemory=true 只读该来源 __global__ 池，否则只读对应助手自己的池，两池不合并。普通新旧聊天及独立助手在请求时读取；群聊仅使用与会话同来源的当前出场角色记忆。用户本地会话长期记忆仍单独存在，源助手记忆放在 AssistantMemory 段，参与原 token 估算。

明确再导入且数据库实际包含记忆表时，旧转换留下的每聊天记忆副本通过原三方合并退为空正文，避免重复注入；本地修改/删除仍保留，无来源基线的本地记忆不碰。缺记忆表或 settings-only 导入不清旧副本，没有启动迁移。记忆来源行删除不清本地库；本地行删除在来源重加后仍不复活，基线保留已知身份。此保留策略是合并并存，不宣称双向同步。

assistant/source_settings/source_memory 的 settingsJson 现在展开 JSON 对象参与逐字段三方合并，最后仍保存原字符串格式；旧字符串基线同样展开。本地改单个字段不再阻止其他源字段更新，嵌套 chatDefaults 控制也可独立更新。数组仍作为整体（预设消息/正文规则等未改为逐条合并）。记忆库按行 ID/字段合并，native 映射目标表已接 source_memory；完整恢复/设备行为仍未实证。

固定依据：[RikkaHub 2.5.5 记忆归属](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt)、[生成注入](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt)。注入段并非上游提示文字的逐字复刻；近期聊天引用、所有来源正文正则/工具/MCP/工作区等完整配置仍待补。

### 来源角色新建聊天、预设消息与消息模板（2026-10-07）

普通 createChat 现在在同一事务内继承角色的精确 sourceNamespace，但新会话没有来源 chat ID。Desktop 读取同来源的 default-connection 映射；RikkaHub 按原助手 ID 查对应 AssistantConfig.chatDefaults，复制模型预设/生成参数、提示启用、世界书集合模式、条数限制与 messageTemplate，不使用别的来源或本地默认配置。群聊目前使用主角色的创建默认。旧配置缺 chatDefaults 时提示明确再次导入，不启动迁移或猜测配置；原导入合并/并存和本地修改保护不变。

RikkaHub presetMessages 通过既有消息/附件转换保存为 ChatCreationDefaults.initialMessages，仅在明确新建普通聊天或独立助手聊天时复制为新节点/候选 ID；已有导入聊天不重复追加。托管附件复用注册文件，不下载远端附件。预设保留原正文、时间、推理和惰性工具记录，但清除可执行 toolCalls/toolCallId 与 generationMetadata，不重放工具。普通角色 firstMessage 仅在没有预设消息时作为开场。会话与预设消息同事务保存，返回消息写入后的 updatedAt。独立助手仍使用自己的配置/绑定，不自动注入主聊天状态。

ChatOverrides.messageTemplate=null 表示普通聊天不启用；独立助手 null 继承配置。新 RikkaHub 导入默认 {{ message }}。请求构建使用 Pebble 4.1.1 的内存模板加载器，定义仅来自同 sourceNamespace 的助手配置，可用条件/循环及引入其他同来源模板；不提供磁盘/网络加载器或应用服务对象。变量为 message、role、time、date，时间来自原消息 createdAt，按设备时区输出 HH:mm:ss / yyyy-MM-dd，不使用生成时刻。输出不作 HTML 转义，单次上限 1,000,000 字符；编译/执行失败明确报错。

模板只加工本次请求的历史文本，合成提示与独立工具结果不加工，工具参数、附件字节和保存原文不变。token 估算使用渲染后的文本；世界书仍扫描裁剪后保留的、模板加工前正文。精确时间地区格式、所有媒体/内联工具和上游顺序仍未逐项验证。来源助手记忆及配置字段合并见文首；全部正则和其他配置的创建继承、数组逐条合并、全部 native 关联与真实模型/设备仍待补，不能据普通编译认定全后端完成。

固定参考：[RikkaHub 2.5.5 模板转换](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/TemplateTransformer.kt)、[Pebble 内存加载器](https://pebbletemplates.io/wiki/guide/installation/)。本批新增这一个实际模板依赖，无新 Room schema/任务引擎/测试套件；费用与内部清理后置。

2026-10-07 后续补齐：sessionSystemPromptEnabled、worldBookSelectionMode与contextMessageLimit已经进入typed overrides，普通/助手80/200隐藏历史窗口已经移除，以下以本节更新为准。

### 会话覆盖与完整选中历史

sessionSystemPromptEnabled默认true，false保留提示原文但不注入；RikkaHub新导入取助手allowConversationSystemPrompt（缺字段false）。启用而提示为空仍回落角色提示。worldBookSelectionMode默认merge（Desktop/本地并集），character只选角色挂载，session只选会话挂载，空会话列表不回落角色。全局/sessionChatIds独立激活沿原规则，不清资料库。RikkaHub助手allowConversationPromptInjection默认false映射character，true映射session，会话列表仍保存。

Core setWorldBookSelectionMode(chatId, mode, expectedUpdatedAt)和助手chat_worldbook_selection_set共用原审批/更新时间比较；状态读取返回模式。Mappers与chat导入基线补同样的true/merge默认，明确typed值优先。旧数据只在明确再导入时合并新控制，不启动扫描改写。

普通/独立助手都读取全部选中候选，不读取未选中的候选，不再隐藏截断80/200节点。普通锚点可为节点或任一候选ID，切换候选后仍定位原节点，keepBefore按选中历史回退；锚点不存在或属于其他聊天沿原行为从头读取。助手也按节点锚点定位，不读取主聊天。

contextMessageLimit与token预算独立。普通null/0不限制条数；助手null继承配置contextMessageLimit，显式0关闭继承。RikkaHub新导入取助手contextMessageLimit（缺字段0），不误用旧contextMessageSize。正值采用50%回落的阶梯起点，无工具时limit20在20/21/29/30条历史分别保留20/11/19/10条；台阶内起点稳定，无新持久状态。Core工具结果跟随assistant，不单独计逻辑条数；起点为带toolCalls的assistant时回到此前user，后续继续按完整call/result组装配。安全回退可能超过条数阈值。

其后仍按chars/4预算保留最近完整组并重匹配世界书；context_size=0关闭估算裁剪，不能关闭显式contextMessageLimit或锚点。没有精确tokenizer、严格token硬上限或任意规模性能保证；当前全部选中历史一次读取，巨量历史分页/超长行仍待优化。只改请求，不写历史、不重放旧工具或任务。

固定参考官方2.5.5提交6c903feba7e6b65f518b320a88ae29efebae77c4，标签API已确认：[助手字段](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)、[注入集合](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt)、[生成顺序](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt)、[条数算法](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/ui/Message.kt)。用户实际版本/包未验证，不从master变化推断其他版本兼容。

RikkaHub逐条扫描也排除Core的system/tool结果行，工具结果正文不冒充源UIMessage的Text；bottom/at_depth按非tool逻辑消息数定位，结果行不占深度，插入仍避开完整调用/结果组。源内联工具记录目前惰性保留，全部原生工具结构与四协议线上行为仍未证明。

此处历史余项中的新建聊天默认、预设消息、模板及来源助手记忆已由文首实现更新；其他版本集合字段、其余配置继承、内联工具记录/所有来源正则细节、巨量历史分页/tokenizer、真实四协议位置/native/设备及全部后端清单仍待补。费用和内部清理后置。

主线补齐：2026-10-07。代码与普通构建证据，不是完整模型、设备或后端验收。

### 提示与来源

普通聊天、群聊和插话共用 ContextBuilder。按出场角色取姓名/systemPrompt/description/personality/scenario/exampleDialogue 和角色世界书；成员 includePersonality/includeScenario 继续生效。示例为 ExampleDialogue 提示块，不作为数据库历史或新工具调用。

prompts.globalSystem/globalPrefill/globalPrefillEnabled 从本聊天精确来源设置读取，本地聊天取本地设置，不借另一来源助手。选择另一来源模型预设不改变角色侧全局提示。pureAiMode 会话值优先，未设沿用来源设置；纯AI不注入persona，原用户正文转换system，附件仍保留用户媒体输入。

sessionSystemPromptMode=append 追加会话提示；override 保持原 Android/RikkaHub 角色覆盖语义，非空会话提示替换全局/角色主体但保留身份/persona/记忆/状态；新 override_global 只替换全局提示。桌面源 override 在新导入中映射为 override_global，匹配桌面当前生成逻辑。空提示不清空角色/全局。旧导入不在启动改写，明确再导入按原基线合并新映射。

globalPrefill 默认启用，只有非空配置才加请求末尾 assistant 消息；不创建候选，也不由 Core 强加到保存回答。保存模型实际输出，不保证上游一定支持续写或不会回显。独立助手与维护上下文不注入普通聊天全局prefill。

### 占位符与记忆

仅替换精确 `{{char}}` / `{{user}}`，无任意模板执行。提示/世界书/历史在请求构建中渲染，历史角色取消息 characterId 姓名，用户优先 senderName、再persona/默认用户；工具结果、工具参数、原生签名与附件字节不改。

不写数据库历史，与桌面当前历史替换落库不同。原生历史正文发生变化时继续由既有 ProtocolMessages 匹配策略处理，不伪造签名、不执行旧调用。正式 LongTermMemory 存在就取正式值，明确清空的空字符串不回落旧记忆；无正式记录才取 overrides.longTermMemory。

### 群聊与状态位置

保留桌面实际字段含义：groupSystemAlwaysAtBottom=true 名字虽含Bottom，实际将整段system放首部；false 则在已含世界书的保留历史中按groupSystemInjectDepth从末尾计数插入。0在末尾，负值归0；身份约束随后作为请求user追加，prefill再追加。身份约束不落库。

世界书、群聊system、KG共享插入方法：落在tool结果内部时退到assistant调用之前，完整call/result不拆开。其他成员工具往返仍作为带成员名的惰性正文，不变成当前角色调用。

MVU开关仍由承载角色/群聊显式覆盖决定。regex模式保留原系统状态表；directive模式将表附到最终最后assistant（含prefill）内容后。无assistant时Android以独立首部system保留状态，与桌面“无锚点则不注入”不同；不改变维护任务。

KG支持legacy（最后assistant后）、before_system/after_system（首个system内容前后，无system则首部独立system）、depth（越过连续首部system后按最终请求末尾计数）、before_last（指定角色最后一条前）。新增typed knowledgeGraphBeforeLastRole，默认assistant，支持assistant/system/user。未知位置/角色回落legacy/assistant，缺指定锚点不注入，返回injectedKg=false和knowledge_graph_anchor_missing诊断。

旧聊天JSON尚未有typed knowledgeGraphBeforeLastRole时，从已保留extraJson读取这个已知来源字段；明确typed值优先，不覆盖本地编辑。原导入chat基线以相同规则解读，避免把新暴露的默认值误判成本地修改，后续源角色更新仍可三方合并。仅是读取/明确再次导入的兼容，不在启动扫描改写所有记录。

所有状态追加只改请求，原数据不落库。上游可能将多段system合并/提升到顶层，尤其Anthropic/Gemini；本轮不宣称四协议线上位置完全等价。

### 长度与预览

context_size（兼容contextSize）优先会话paramsJson，再成员选择的预设/会话预设顶层配置，再预设generationDefaults或其来源默认值；无值用原24000。成员影响预设选择，未添加不存在的成员context_size字段。预览不解析凭据/联网，明确缺预设仍报错。

0关闭估算token裁剪；已取消80/200隐藏窗口，按上述全部选中历史、显式锚点和可选条数裁剪取历史。先预留system/世界书/身份/prefill/状态/KG成本，按完整消息组保留最近历史，最后按保留历史重匹配世界书。仍使用chars/4，强制块/最新一组可超预算并记录诊断，不是精确tokenizer或严格硬上限。

Core buildContext(chatId, characterIdOverride=null) 可预览出场角色；独立助手继续自身上下文，不接受角色替换。公开预览的正文计算在Default dispatcher，普通生成使用同一builder。无新schema/依赖/任务、旧调用恢复或重放。

后续已补RikkaHub逐条扫描、常驻、优先级和五种注入位置/角色，Desktop整书策略保留，条目合并/编辑入口同步，见 [CHAT.md#worldbook-injections](CHAT.md#worldbook-injections)。全部选中历史和固定版本会话覆盖本批已接，剩余范围见本文顶部，不以普通构建宣称完整后端验收。费用细项和内部清理后置，无哈希、冒烟或新增测试。

本批同步修正新RikkaHub导入：constantActive和modeInjection转换为显式始终匹配的 `(?s:.*)`，默认非大小写敏感关键词/正则带 `(?i:...)`，而不是依赖全局IGNORE_CASE或把空触发词当常驻。没有关键词且非常驻仍不匹配，caseSensitive仍保留显式区分。后续已增加typed常驻与逐条扫描/位置。已导入旧条目需明确再次导入，按原基线/本地修改保护合并；旧版本布局与全部来源行为仍待补。

<a id="message-editing"></a>

## 消息追加与完整字段编辑

### 创建时的成员与开场白

`createChat` 的末尾可选 `firstMessageCharacterId: String? = null`、`useOpeningMessage: Boolean = !isGroup || firstMessageCharacterId != null`。单聊默认沿主角色开场白；群聊默认没有开场白，指定成员 ID 即启用该成员的开场白，也可明确 true 使用主角色。`openingMessageIndex` 沿所选角色的主开场白/候选列表，不启用时不读取该列表。明确 false 与成员开场 ID 不能同时传入。

群聊成员保持传入顺序去重保存；主角色不在列表时才补到开头，已在列表则不改变其发言顺序。所选开场角色必须属于这份成员列表，持久消息的 characterId 是真正发言的成员，不总是主角色；单聊只能选择其主角色。成员选择与聊天/消息创建沿原事务，不产生模型或工具任务。

普通角色开场白将所有启用、渲染后非空的文案一次保存到同一个消息节点的候选列表；用原列表索引映射选中项，不按正文反推，因此重复文案不会选错。创建后沿原 `selectCandidate` 切换，不新增开场专用接口。各候选按创建时的开场角色与 Persona 冻结 `{{char}}`/`{{user}}`；角色库原文和旧聊天不回填。索引 0 仍保留原列表的空主开场槽，若选中项渲染后为空则不创建开场节点；群聊默认不开场，Rikka 来源预置消息不展开为角色开场候选。

群聊不复制主角色的单人来源预置消息（包括 Rikka chatDefaults.initialMessages）；单聊仍保留这些来源消息，useOpeningMessage=false 只关闭普通角色开场白，不删除来源预置消息。来源预置消息存在时，原单聊非零 openingMessageIndex 冲突规则仍保留。群聊状态初始化按下节显式 MVU 预设处理，已有聊天不回填。

### 创建时的群聊规则与 MVU 预设

群聊按已规范化成员顺序读取可用角色，将正文规则保存为会话快照。沿桌面两步 first-wins：先按去首尾空白的 pattern/action/replacement 三元组去重、空 pattern 跳过，再赋顺序并按 ID 保留首条；其余规则字段不变，不重命名 ID，不使用哈希。缺失成员跳过规则合并，明确选中缺失的开场/MVU 成员仍 NotFound。角色库原文不改，原来源全局规则优先和原规则限额保留；合并不执行规则或新增重复校验，MVU 关闭也不关闭正文规则。

`createChat` 末尾增加 `groupMvuPreset: String = "off"` 和 `groupMvuPresetCharacterId: String? = null`，仅群聊使用：

| 预设 | 创建行为 |
| --- | --- |
| `off` | 显式关闭 groupMvuEnabled，不复制主角色初始状态；成员规则仍合并 |
| `inherit_member` | 指定群成员须有初始表、非空指令或提取规则之一；复制其模式/指令/非空初始表并启用 |
| `fork_session` | 沿桌面创建语义复制所选成员配置并启用，不要求该成员已具备 MVU 数据；不是复制另一已有会话的历史 |

两个启用预设都保存 groupMvuAnchorCharacterId/groupMvuTemplateCharacterId；所选 ID 必须属于群成员且记录存在，不借另一角色。成员 mvuEnabled 不决定能否选择，空表不初始化正式状态。选择、规则、聊天、开场及状态沿原创建事务一起提交；普通单聊仍沿其角色初始化，已有聊天不会因本批默认 off 被关闭。

历史重建初始表来自模板成员，未指定模板时兼容锚定成员，再缺时沿主角色；模式/指令仍以会话覆盖优先。上下文只在实际需要 MVU 模式 fallback 时查询模板，不为已冻结模式或关闭/空状态重复查角色。模板字段为既有 overrides JSON 的可选字段，无新表或迁移；桌面导入映射为 Native 角色 ID，原生恢复复用已有角色关系检查，删除角色只清除匹配的锚定/模板绑定。不会在模板改名或编辑时自动重置正式状态，也不新增重放。

### 创建时的占位符与纯 AI 模式

`createChat` 最后还可传 `pureAiMode: Boolean? = null`；显式 true/false 保存为该会话覆盖，null 保留来源 chatDefaults 的覆盖或继承其来源设置，Native 会话继承本地全局设置。不借其它来源的纯 AI 设置。

实际启用纯 AI 时不绑定 Persona，也不读取或校验被忽略的 Persona ID；普通模式仍沿前述显式/来源默认选择，至多查询所选 Persona 一次。开场白按实际开场成员名和已选 Persona 名替换精确 `{{char}}` / `{{user}}`，缺名字分别用“角色”/“用户”，纯 AI 一律用“用户”。沿原 ContextBuilder 替换方法，去首尾空白后保存，空结果不追加；没有新模板系统。已保存的普通开场正文不会因后续改名/换 Persona 自动重写。

`characterOpeningMessages` 保持主开场白在索引0（包括空文本），候选跳过显式 `chip=false`；缺字段仍启用，原扩展 JSON 不改写。角色库原文、旧会话、Rikka 单聊来源预置消息不做本批占位符回填；上下文仍沿原动态替换规则。

### Core 入口

`appendMessage(chatId, candidate, parentNodeId=null): MessageNode` 沿原 ChatRepository 追加一个手工消息节点，不启动模型或工具，不创建生成任务。候选 ID 和创建时间由调用方提供，已有 ID 不会被覆盖；父节点仍须属于同一会话。普通生成入口与单会话生成限制不变。

`editSelected(nodeId, edited: MessageCandidate): MessageNode` 编辑已有选中候选；`editSelectedAndTruncate(nodeId, edited: MessageCandidate)` 在同一 Room 事务内编辑并删除其后历史。原 String 重载继续只改正文，不改变其它字段。

传入的是完整的可编辑字段快照，不是 JSON patch。通常先读取当前候选再 `copy`，以保留不想改动的字段；空列表清空图片/附件引用，nullable 字段的 null 清空对应值。例如：

```kotlin
val selected = core.getMessageNode(nodeId).selected ?: error("No selected candidate")
core.editSelected(nodeId, selected.copy(content = "修改后的正文", reasoningContent = null))
```

### 编辑范围

可编辑字段为 role、content、reasoningContent、reasoningDurationSec、images、attachments、characterId、senderPersonaId、senderName、senderAvatar。思考时长允许 null 或非负有限值。

候选 ID 必须仍是当前选中候选，否则 Conflict；不会悄悄编辑另一候选。同一候选的编辑按提交顺序覆盖所提供的可编辑字段，不新增通用版本锁。ID、节点/会话/候选索引、创建时间、工具调用与记录、用量、生成元数据及 extraJson 沿仓储当前记录保留；修改正文不会改写原请求或费用事实，也不丢失来源未知字段。其它候选与选中索引不变。

role、正文或 characterId 变化时，将该候选原正则记录标为 stale，保留诊断而不继续消费旧依据。仅改思考、媒体或发送者显示快照不让正文规则失效。长期记忆正文、MVU/图谱及已发生的工具副作用不会自动历史回滚。已有流式写入会检查候选内容是否被本地改动，不用新的生成内容覆盖本地编辑。

### 附件

新引用须先通过 importChatAttachment 或既有托管资产入口取得 ID。Core 按 ID 取托管记录，不把传入 relativePath/sourceUri 当作新的文件来源；沿原接口每次最多解析20个不同的新附件 ID，modelVisible 可显式保留或修改。

编辑时，已在当前选中候选中的附件沿用原引用，即使来源包缺文件也不妨碍编辑正文/思考；不要求重读全部旧文件。移除引用不物理删除共享文件、语音缓存、费用或来源映射。追加消息和编辑都不会挂载、同步或回写外部文件。

### 用户输入的 Persona 快照

2026-10-10 新会话选择：createChat 增加可选 userPersonaId（Native ID）和 useDefaultPersona=true。非纯 AI 时显式 ID 优先；未给 ID 时默认继承角色所属来源的 selectedPersonaId，useDefaultPersona=false 则创建无 Persona 会话。Native 角色读取本地全局设置；Desktop 等非 Rikka 来源读取自己的 namespace 设置并用原 persona ID map 转成本地 ID，不借其它来源或全局的选择；Rikka 只沿自己的 chatDefaults，旧配置缺该字段为 null。选择和创建在原 Room 事务内完成，非纯 AI 至多查询一次选中记录；非纯 AI 的显式 ID 已不存在时 NotFound，来源默认映射缺失或已被本地删除则不绑定、不复活、不选另一 Persona。纯 AI 的不绑定行为见上节；全局/来源选择和旧聊天不被重写。

2026-10-10：正常生成有新用户输入时，任务登记前从当前会话固定 senderPersonaId、senderName、senderAvatar，以及本次创建时间。worker 仍在进入 RUNNING、确认未停止后按原时机追加输入，不在执行时重新选 Persona；群聊只保存一次，重新生成或无新输入不追加用户消息。快照先在内存固定，不新增恢复/重放机制，也不把“刚登记任务”当作用户消息已经持久化。

身份选择复用上下文的当前 userPersonaId 与按来源配置解析的纯 AI 开关；无 Persona、缺失 Persona 或纯 AI 模式用固定“用户”名称、空 Persona/头像。纯 AI 的持久消息角色及上下文转换/媒体策略不变。不选择 Persona 时不读整份来源设置；已有 build 调用传入其已解析的纯 AI 值，不重复读取设置。

手工 appendMessage 的 user 消息若三个 sender 字段都未提供，也填当前快照；显式提供任何 sender 字段则保留调用方数据，不与当前 Persona 拼接另一身份，调用方应提供所需完整快照。其它角色不自动套用用户身份，编辑接口仍可明确修改/清空快照字段。

以后切换 Persona、编辑名称或头像不会修改这些已保存字段。ContextBuilder 的历史 {{user}} 替换、MemorySummaryService 的来源标签和 StateHistorySources 已读取原消息的快照，不只是多保存未消费字段。当前 Persona 对新请求其它上下文的影响仍沿原业务，不冻结全部会话配置；旧无快照消息/已导入历史不猜测回填，不增加表或绑定新审批。

2026-10-10 群聊发言归属：构建群聊请求时，原 user 消息按已保存 senderName 加 `[姓名]: `，无名称沿原当前用户名回退；单聊、独立助手、原生 system、assistant/tool 及其他成员历史不套用户标签。纯 AI 下由 user 转成 system 的片段也带标签，单独的附件补助 user 片段不重复标记。只在请求副本中加前缀，再用原模板渲染、按最终文本估算预算；世界书匹配仍看未加标签正文，不因姓名框架额外触发。姓名只经与 sourceCreatedAt 同类的 transient 中间字段传递，不新增存储列/重复快照；最新优先裁剪和保留工具往返策略不改，也不去重用户自己写在正文里的姓名前缀。参考为 Desktop generate.py 的 group_mode 用户分支。

### 长历史正文检索

`searchMessages(chatId, query)` 保留原入口和首批 50 条；新增 `(chatId, query, limit, offset=0)` 可逐页读取后续命中，不再只能获取前 50 条。排序为消息顺序、候选顺序和候选 ID，包含未选中候选，沿原 `List<MessageCandidate>` 返回，不新增搜索 DTO、全文索引或数据库版本。查询去首尾空白；空白查询直接返回空列表，`%`、`_`、反斜线按正文字符匹配，limit 必须为正、offset 非负。

用命中项的 ID 调用 `getMessageNodeForCandidate`，即可获取节点 ID、orderIndex、全部候选和当前选中项；读取不会切换候选或改写消息。检索只覆盖正文，不覆盖思考、附件内容或工具原始字段，也不作模型调用。offset 是普通 SQL 分页，不是跨编辑的冻结快照；历史变化后重新查询，不新增游标/版本框架。真实长历史性能仍须设备验证。

### 范围与证据

Desktop 参考为 schemas.py 的 AppendMessageRequest/UpdateMessageRequest 及 routes/chats.py 的 append_message/update_message/save_message_and_truncate。Android 用原节点/候选操作管理多版本，不直接接受桌面 greetingVariants 数组更新协议；已保存的来源字段继续保留。

本功能没有新表、迁移、任务框架、哈希或新增测试。Luna 实际编码仓储字段编辑与截断重载，主代理接 Core 和原托管附件查询。构建结果见 [VERIFICATION.md](VERIFICATION.md)，编译不代表设备上的编辑、再导入或恢复已验收。

<a id="message-regeneration"></a>

## 消息重新生成

### Core 入口

`startRegeneration(chatId, nodeId, webSearch=WebSearchOptions())` 使用会话当前连接；显式 `ProviderConfig` 重载使用调用方指定连接。目标必须属于该会话，且其选中候选为 assistant。接口返回新生成任务快照，复用 `observeGeneration(chatId)`、任务状态、停止、工具审批与用量账本。

普通 `startGeneration(chatId, userText=null, ...)` 仍创建新的 assistant 节点。重新生成不是手工 `addCandidate`，也不是 `editSelectedAndTruncate`。

### 上下文与临时输出

启动时固定目标节点快照和当时位于目标之后的节点 ID。普通聊天与助手上下文均限制在目标前一 `orderIndex`，不包含目标或其后历史；若 `contextStart` 锚点晚于该边界，仅在本次请求的 session 副本中清除此锚点和配套 keep-before，不写回会话。长期记忆正文、MVU 和知识图谱不回滚。

生成期间，assistant 最终回复和工具往返先按普通追加布局写入临时节点，流式事件照常发送。群聊只重新生成原发言成员，且该角色启动时及执行时仍须是当前成员；助手会话沿用助手自己的历史和工具配置。

### 成功提交

提交成功终态时，在一个 Room 事务内再次核对目标节点、顺序及启动时选中的候选，并确认临时最终候选仍属于本次生成且没有被添加其它候选。旧目标候选保留；最终新候选移入目标节点并设为选中。此次生成产生的中间 assistant/tool 节点移到目标之前，保持原协议顺序；消息和正则队列中的节点顺序同步调整。事务只删除启动时捕获的旧尾部节点 ID，不删除任务期间另行新增的消息；终态事件在事务提交后发布。

被删除尾部所指的锚点沿既有清理规则处理；长期记忆正文保留，不自动还原为过去版本。指向旧目标候选或目标节点的记忆/状态覆盖锚点失效；旧候选的正则诊断保留，后续查询/消费按当前选中版本判定有效性；指向旧候选的 `contextStart` 改为目标节点。共享附件、来源映射/基线、历史任务与费用不随尾部消息删除。

### 冲突、失败与停止

若目标被编辑、改选、移动或删除，或临时最终节点新增了其它候选，合并报告 `Conflict` 或 `NotFound` 并以 `FAILED` 结束任务。合并不改动仍存在的原目标和启动时旧尾部，也不复活已被调用方删除的记录；已收到的最终输出已先独立保存，仍留在临时追加布局。不会重发模型请求或工具。

停止、中断、模型失败或未收到成功终态时不合并目标、不清理旧尾部；已保存的临时输出及已发生的工具副作用仍按普通任务规则保留。该流程不撤销共享文件改动、费用或来源基线。

成功的 `StreamEvent.Done.assistantMessageId` 是稳定候选 ID。终态后调用 `getMessageNode(nodeId)` 读取目标节点及新选中候选。

### 证据边界

普通离线 `:app:assembleDebug` 与 `assembleBackendRelease` 已通过（2m31s，673 tasks，102 executed、571 up-to-date）。这是编译和静态证据；真实设备、模型服务、工具执行及冲突/停止的运行行为尚未验收。本说明不代表数据库迁移或新框架；本功能未新增迁移。

<a id="chat-copy"></a>

## 完整分支与单聊转群聊

### Core 入口

| 入口 | 历史与状态 |
| --- | --- |
| `forkChat(sourceChatId, forkAtMessageId, newTitle=null)` | 原硬分叉：到指定节点，清除记忆/MVU/图谱/覆盖锚点；语义不变 |
| `branchChat(sourceChatId, newTitle=null)` | 全部节点/候选及正式记忆/MVU/图谱，保持类型和配置；允许空历史 |
| `promoteChatToGroup(sourceChatId, memberIds, ...)` | 普通单聊全部历史复制为群聊，源单聊与正式状态保留，不加开场白 |

branch 默认标题为原名加“-新分支”，明确标题去首尾空白后优先。promote 默认沿原名；已是群聊或独立助手会话返回 Validation。主角色补到首位，去重后至少两个成员，明确成员须存在；memberSettings 的键只能属于成员列表。

promote 可选 newTitle=null、memberSettings=emptyMap()、pureAiMode=null、userPersonaId=null、useDefaultPersona=true、groupSystemInjectDepth=5、groupSystemAlwaysAtBottom=true。成员正文规则沿原合并方法生成会话快照。不同于新建群聊默认 off，转换沿桌面启用 MVU，锚定/模板为原主角色，正式表来自源会话当前快照，不重新套初始表。

转换的 Persona/纯 AI 沿原角色自己的来源默认及明确参数，不借其他来源。纯 AI 不绑定 Persona；普通模式的明确缺失 ID 返回 NotFound，默认已删除则不绑定。旧消息姓名/头像快照不回填。

### 复制边界

原源会话锁和 Room 事务一起复制配置、消息及正式状态。实际活跃生成须先停止；副本旧 queued/running/awaiting_tools/stopping 元数据改 interrupted，不把部分历史排除或当成正在执行。源记录不改，停止/失败/中断的已保存内容可复制，不恢复旧请求。

新会话/节点/候选独立 ID，全部候选、选中项、父节点、媒体、工具历史、时间和发言人快照保留；assistant 缺角色 ID 补原主角色。生成元数据的 chatId/messageId 指向新行，opaque 来源 JSON 保持 provenance。sourceNamespace 保留，sourceId 置空，不冒充旧导入身份，通过原 forkedFrom 字段记录来源。

预设、世界书、沙箱、托管附件共享原明确引用，不复制物理文件、环境或密钥。上下文锚点按节点优先映射；总结/维护/记忆锚点按候选优先映射，无法映射的 live 锚点清空。KG 只映射已知消息来源，其他时间/文字保留，实体 ID/关系端点保持图内身份。

有正式 Memory/MVU/KG 行才复制，新行沿原 store 版本策略从1开始，正文、表和图谱独立保存。Android 同时复制正式 KG 快照以保留可用上下文。summary job、MVU 日志、regex 队列/记录、任务、审批、进程和费用账本不复制；旧工具往返、用量/费用显示只是历史，不发起工具或重复扣款。后续仅按新任务执行。

无新表、迁移、复制框架、哈希、冒烟或单测。构建证据见 [VERIFICATION.md](VERIFICATION.md)，实际设备、长历史、原生恢复及模型行为未验收。

<a id="conversation-reference"></a>

## 按需引用来源聊天

2026-10-10 近期排序修正：原时间字符串可以是带偏移 ISO 时间，也可以是来源无时区 LocalDateTime。近期列表将排序键各解析一次，后者沿消息模板/用量导入使用当前设备时区；返回的 updatedAt 原文和数据库不改写，置顶、来源/助手范围、ID平局顺序与原条数限制不变。不可解析时间明确报 Validation，不默认为现在/epoch或静默跳过。这不是恢复原设备时区的证明；没有新增表、日期框架、启动迁移、真实数据库操作或测试。

2026-10-08。补齐 RikkaHub `enableRecentChatsReference` 对应的主要查询链路；普通角色与独立助手共用后端，不增加任务引擎、表或 FTS 索引。未运行真实模型、备份导入或设备。

### Core 接口

- `listRecentAssistantConversations(assistantId, limit=10)`：1..30 条本助手会话，返回 id/title/updatedAt/pinned，置顶优先，其次最近更新、ID。普通聊天以准确来源角色映射匹配，独立聊天以 assistant binding 匹配；同助手的分叉和新建聊天也可查询。
- `searchAssistantConversations(assistantId, query, limit=15, offset=0)`：1..50 条，搜索同一导入来源全部助手的当前选中用户/助手正文。返回 chatId/messageId/orderIndex/title/snippet/updatedAt、hasMore 和 nextOffset。
- `readAssistantConversation(assistantId, chatId, afterOrder=-1, limit=100)`：1..200 条选中原文；返回 chatId/title、消息 id/orderIndex/role/content、hasMore 和 nextAfterOrder。可用搜索结果位置定位一页，不返回工具调用参数或执行旧记录。

接口为 RikkaHub 来源配置服务；不跨越其他导入 namespace，不搜索 Desktop 或无来源的本地历史。Core 是明确读取接口，不受工具开关限制；模型只在配置开关开启时取得工具。置顶读现有来源 extraJson 的 isPinned/is_pinned，旧 SQLite 布尔整数兼容，无启动迁移。

### 工具与请求

普通角色当前配置（群聊按实际发言者）和独立助手在 enableRecentChatsReference=true 时声明 `recent_chats`、`conversation_search`、`conversation_read`；记忆工具开关与历史引用开关互不依赖。工具统一归 READ，走原权限/审批、工具结果持久化及生成续轮；执行前核对来源和当前开关。旧历史只是资料，不重放调用，也不自动注入其他会话或主聊天全文。

recent_chats 保留来源名称和 id/title/last_chat，并补充 pinned；last_chat 当前为完整更新时间字符串，不模拟来源 UI 的本地日期。conversation_search 返回分页对象和稳定 Android 身份，而非来源原始 UUID 数组；conversation_read 为新增读取能力。改参数或关闭配置后下一请求按新配置构建工具。

### 搜索和分页约定

query 为空白分隔的字面关键词，须全部命中；中文支持连续子串。所有词为 SQL 绑定值，`%`、`_`、引号不是特殊查询语法。先按首词出现次数、会话更新时间、会话 ID、消息顺序排序；片段为首词附近至多 320 字符，`[匹配]` 标记供模型理解。大小写匹配沿 SQLite lower（主要 ASCII）语义；不是 RikkaHub FTS 的 tokenizer/BM25/查询操作符逐字实现。查询直接访问现有数据库，不加载全部历史正文到 Kotlin，也不以最近 N 条窗口替代搜索。offset 为当前结果分页，不宣称并发编辑期间的冻结快照。

读取使用原文，不套消息模板或仅显示正则；不带思考、附件字节或工具参数。每页正文 JSON 预算 800,000 字节，工具总输出沿用 1,000,000 字节限制；预算缩短页时游标按实际返回的最后一条推进，不静默跳过未返回消息。单条超过预算明确失败，巨大单条分片仍待补。近期列表只读来源会话元数据，当前在 Kotlin 解析绑定与置顶排序，超大元数据规模性能待设备验证。

来源依据固定官方 [ConversationTools.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt) 和 [ChatToolFactory.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt)。来源 recent 按助手过滤，search 未传助手过滤；本地因此近期限助手、搜索限来源而不限助手，不混进其他导入来源。

<a id="content-regex"></a>

## 正文正则与消息投影

更新：2026-10-08。已接导入、请求与可调用预览接口；编译证据不等于实机或上游验收。

### 两套语法与来源

Desktop/本地规则继续使用原 RE2/J 语法、替换/移除/提取队列，默认行为不变。RikkaHub regexes 转为 ContentRegexRule，角色卡 contentRegexRulesJson 与独立助手配置 contentRegexRules 同时保存；原定义仍保留，不安装或执行其他来源技能。规则 ID 使用来源助手作用域的稳定映射。

RikkaHub 使用 Kotlin Regex 原始 pattern 与 replace 语法，不套 RE2 子集、JS 字面量解析、隐式 DOTALL/MULTILINE 或桌面替换字符串解释。条件前瞻/后顾、捕获引用和命名组由 Kotlin 引擎处理；错误保留单条原文并继续其他规则，可由预览结果读取，不回退到另一种引擎。成功编译的 Kotlin pattern 有界缓存，不在每个字符增量重新编译。

| ContentRegexRule 字段 | 旧默认 | RikkaHub 转换 |
| --- | --- | --- |
| engine | re2 | kotlin，action=replace / matchMode=global |
| roles | user、assistant | affectingScope，空集不执行 |
| visualOnly | false | 来源原值 |
| applyToReasoning | false | true，仅助手思考内容 |
| scanAllHistory | false | true，不使用普通规则的近期深度 |
| skipOpeningMessage | true | false，开场也可处理 |

普通会话按已有全局库→会话/角色规则优先级执行；来源全局库读取精确 settings 映射。独立助手只读自己的配置/会话规则，不自动使用普通聊天全局库。会话启用覆盖仍按规则 ID 生效，已有增改/删除与助手规则工具可编辑新字段。

### 请求和预览

普通和独立助手上下文均取原候选生成只读正文投影，再进入占位符/模板和既有估算裁剪。仅显示的 Kotlin 规则在普通处理全部完成后作用于显示投影，不进入模型请求或提取队列。工具续轮的新助手正文也先作正则投影，再按原发送时间套消息模板；工具参数、结果、附件字节与原生 providerTurn 不改。含原生签名的完整请求等价性仍需上游验证。

Core previewContentRegex(chatId, text, role=assistant) 可分别预览用户/助手正文。新增 previewMessageContentRegex(chatId, messageId) 返回 MessageContentProjection：content、displayContent、reasoningContent、displayReasoningContent、errors。它是当前规则预览，不施加请求的历史锚点/深度；可读未选候选及流式检查点，不写库或排队提取。pageMessages/增量事件仍返回原始数据，界面应明确使用投影接口显示，不直接覆盖原文。

明确新导入的 RikkaHub 聊天候选保留 rikkaRegexProcessed 标记；原正文/思考与保留的来源字段一致时，非显示规则不再次处理，防止非幂等替换叠加。编辑后的正文可重新投影。预设消息不是旧聊天历史，不加这个已处理标记。旧数据通过明确再导入合并新规则/标记，不启动重写；本地 extraJson 编辑可能阻止新标记合并，应保留并单独核对。多 Text/Reasoning 片段边界、内联工具和所有版本仍未逐项适配。

### 自动合并与存储

角色规则字符串、独立助手 canonical 规则及来源全局规则库展开为数组，按稳定 ID/字段三方合并，最后仍存原 JSON 字符串/配置格式。旧规则基线补相同 typed 默认，未知字段保留。本地改单条不冻结整组，新增/修改/删除受保护；来源删除或省略字段不清本地库，基线记住已知 ID，来源重加不复活本地删除。原始 regexes/预设消息等其他数组仍整体合并，不宣称全数组逐条同步。

没有新 Room 表、版本、外部依赖、任务引擎或自动重放。队列 outputJson 保存处理阶段/角色用于避免复用错误投影；当前请求按自身历史窗口重新计算，不借用另一扫描深度的队列正文。native 已允许既有 empty 队列状态，完整关联/恢复行为尚未实证。

沿原引擎的规则数量、字符/执行和输出预算；不是任意长度或硬实时隔离保证。正常原文保存/请求投影与来源在提交时直接替换的物理存储不同，不能据此宣称逐阶段存储完全一致。UI实际渲染、巨大正文、全部来源布局/正则/原生媒体和真实四协议仍待核对。没有新增测试、冒烟或哈希；内部防御性清理与费用细项后置。

固定依据：[RikkaHub 2.5.5 规则与替换](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)、[用户提交/编辑](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt)、[输出正文/思考](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/RegexOutputTransformer.kt)。参考版本不是用户实际备份版本证明。

<a id="worldbook-injections"></a>

## 世界书逐条注入与合并

2026-10-07 后续：已去掉80/200隐藏历史窗口，并接固定官方2.5.5的会话system/注入集合替换开关与阶梯条数限制。Desktop/本地仍默认并集，RikkaHub character/session选择保留空列表语义。逐条扫描排除system/tool结果，bottom/at_depth不让Core结果行占深度。下文相应延期/窗口/计数描述为本批前状态；其他版本、模板/presetMessages、新来源聊天默认、全部原生工具结构/巨量历史分页及真实运行仍待补，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07：代码实现与普通离线构建，不是实际来源包、模型或设备验收。

### 生效字段

WorldBookEntry 新增 scanDepth、constantActive、priority、position、injectDepth、role，存于现有 entriesJson，不新增 Room 表、版本、依赖或任务引擎。Core 原 typed 世界书读写和助手 worldbook_get 返回这些字段。

Desktop/本地旧条目默认 scanDepth=null、position=null：继续采用会话挂载/来源设置的整书扫描深度，按 orderIndex 合并为带标题的 system 块，整书挂载插入深度不变。来源默认扫描深度为0时原有全激活行为保留。

RikkaHub 新导入逐条 scanDepth 默认4，只扫描保留历史中的非system消息；0为空扫描窗口，不等于Desktop的全激活。constantActive 无需匹配；普通关键词转义为正则，useRegex保留原模式，caseSensitive控制大小写。模式注入明确常驻。禁用书/条目不注入，无触发词且非常驻不匹配。逐条匹配不额外开启MULTILINE；旧Desktop正则继续原MULTILINE。

RikkaHub priority 默认0、injectDepth默认4、position默认after_system_prompt；role为assistant时映射assistant，其余来源值按上游转换规则映射user。正文在请求中做既有占位符替换，不加Desktop标题，不写历史。

### 位置与顺序

触发内容按priority降序稳定排序，同位置/深度内同role合并。支持：

- before_system_prompt / after_system_prompt：在首条system正文前/后合并，不生成user/assistant；没有system则新建首条system。
- top_of_chat：第一条user之前，没有user则末尾。
- bottom_of_chat：最终已有列表的最后一条消息之前，不误称“总是最后user之前”。
- at_depth：从末尾数injectDepth，至少1；按深度从大到小逐组插入，后组基于已插入的列表计数。

Desktop整书与群聊system布局先装配，再执行这些位置；群聊身份约束、全局prefill和状态/KG沿原顺序处理。插入位置不会拆assistant-call/tool-result，也不插在user与带toolCalls的assistant之间。未知位置跳过并返回unsupported_worldbook_position_skipped诊断，不悄悄换到另一位置。

预算仍是chars/4，先预留触发文本，再裁剪完整历史组，最后以实际保留历史重匹配。80节点窗口、纯AI转换和Core分离tool结果会影响可扫描条数；不是RikkaHub全消息结构或精确tokenizer等价。未做真实四协议位置验证。

### 编辑

助手条目add/update接受新字段，沿原开关、审批和expectedUpdatedAt处理。未传保留原值；scanDepth=-1恢复继承，0为空扫描，1..500逐条扫描；position空串恢复整书位置；显式位置仅接受上述五种；role接受system/user/assistant；injectDepth为1..500。constantActive和priority可单独修改。无新权限层或草稿引擎。

### 自动合并并存

Desktop与RikkaHub继续原独立namespace/稳定映射，不因同名或同来源ID相互覆盖。

世界书entries不再作为原子数组：按稳定条目ID三方合并每个字段。本地修改A不阻止B的来源更新；冲突字段本地优先，来源新增条目保留，本地新增条目保留，本地删条目不复活，来源删条目不清本地资料库。未本地重排时沿新来源顺序并追加保留的本地条目；明确本地重排保留本地顺序并追加来源新增项，orderIndex本身继续字段合并。

来源输入重复条目ID在导入处拒绝，避免按ID合并丢数据。世界书基线解码补齐新字段默认值，避免把旧数据缺字段误判成本地编辑。基线保留曾出现但现来源已移除的条目身份/最后来源值，确保本地删除的条目经历源删除再重加也不复活；当前原始来源结构仍在extraJson。无需新表、哈希或内部通用合并框架，其他JSON数组仍采用原有策略。

已导入旧条目需用户明确再次导入，按原基线保护本地编辑；不在启动时扫描改写用户数据，不自动导入/恢复/发布或重放工具。

### 来源与余项

字段与注入算法核对自RikkaHub官方仓库 [Assistant.kt](https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt) 和 [PromptInjectionTransformer.kt](https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt)。master会变化，用户实际包版本未验证。

仍待补：助手/会话注入集合覆盖开关的跨版本行为、全部模板/presetMessages与消息数限制、全历史窗口、RikkaHub内联tool结构的逐条计数、完整来源/native关联和真实上游/设备。当前角色挂载与会话挂载仍按Android并集激活，不宣称RikkaHub各版本覆盖开关已适配。费用与内部重复防御清理后置，无新增测试、冒烟或文件哈希。

<a id="multimodal-input"></a>

## 聊天附件与多模态输入

2026-10-09 Bedrock：默认 native Anthropic 沿原内联编码；明确 Converse 已接图片/PDF/文本与工具结果媒体，合并后 user 文档必须有非空文本、数量与文件大小遵守该协议限制。两者不调用其他供应商 Files API，大文件/S3/音视频仍未接，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-08 大文件：原复制上限增至256 MiB，超过内联预算的已支持文件及列明Office改为请求内磁盘上传；四协议按实际格式发送file_id/fileData，成功/失败/停止释放本次远端临时文件。以下6 MiB/不自动上传描述是原内联批范围；当前上传、状态、预算及缓存边界见 [PROVIDERS.md#provider-files](PROVIDERS.md#provider-files)。生成音频已另接，见 [CHAT.md#generated-audio](CHAT.md#generated-audio)。

2026-10-08 后续：Gemini/Responses生成完整图片已保存到assistant候选，沿选中历史还原原生或注明助手来源的媒体；不再一律忽略assistant附件，新增getChatAttachmentContent复用原读取器。生成音视频/其他提供方、大文件/native/实际运行仍缺，见 [CHAT.md#generated-media](CHAT.md#generated-media)。

2026-10-08 工具媒体追加：MCP inline媒体已转托管tool附件；普通/独立助手及群聊选中历史、模型工具续轮已接，不再一律忽略tool附件。协议原生结果与工具来源part的差异、modelVisible及预算见 [ASSISTANT.md#tool-media](ASSISTANT.md#tool-media)。

2026-10-08 追加音视频：Chat Completions接WAV/MP3的input_audio，Gemini Generate Content接内联音频/视频；普通/群聊/独立助手共用原托管附件、选中历史和工具续轮。Responses/Claude不支持的音视频在发送前明确失败，不丢弃/转写/换模型。图片/PDF/文本为2026-10-06原主线，内部清理仍后置。

### 可调用入口

- `importChatAttachment(chatId, source: File, filename=source.name, mimeType="application/octet-stream")`：复制用户选定文件，返回 `AttachmentRef`。未提供 MIME 时按扩展名推断。
- `importChatAttachment(chatId, source: Uri, filename, mimeType)`：读取已有授权的 content URI 并复制。不获取持久权限、不保存 URI、不挂载或同步原文件。
- 单聊/群聊的 `startGeneration` / `startGroupGeneration` 全部重载新增末尾可选参数 `attachmentIds: List<String> = emptyList()`。已有调用保持兼容；允许 `userText=null` 的纯附件消息。
- 独立助手 `startAssistantGeneration` 也使用原 `attachmentIds`，音视频无需另一套生成或附件接口。

先复制附件，再把返回的 ID 传给生成入口。消息中的 ID/路径来自附件表；通过现有 images/attachments JSON 持久化，不改 Room schema。复制成功后即使未发送，也保留该附件，不自动删除用户已导入的文件。

### 主链路

新用户消息保存附件引用；ContextBuilder 从选中历史用户候选的 images/attachments 取得引用，并保留原上下文窗口/工具分组策略。MessageAttachmentReader 按附件 ID 读取托管文件，在一个说话者的上下文准备中复用已读取内容；工具后续轮次沿用这份请求内存内容。群聊下一成员重新准备其选中上下文。

路径来自附件表，而不是打开消息里的 sourceUri、远端 URL 或原始导入路径。读取检查托管根范围、非链接叶项及普通文件。没有把这些检查称为恶意 shell 下的强隔离。

ContextMessage 保留可序列化引用，运行时 ContextMedia 为 transient；Base64/文件全文不写入 ContextMessage 序列化、任务 payload 或备份。附件文件仍由现有附件/备份机制管理。

| 输入 | Chat Completions | Responses | Claude Messages | Gemini Generate Content |
| --- | --- | --- | --- | --- |
| JPEG/PNG/WebP/GIF | image_url 数据 URL | input_image 数据 URL | image/base64 source | inlineData |
| PDF | file/file_data | input_file/file_data | document/base64 source | inlineData |
| UTF-8 文本/代码 | text part | input_text part | text block | text part |
| WAV/MP3音频 | input_audio，base64+wav/mp3 | Unsupported | Unsupported | inlineData |
| 其他已列音频/视频 | Unsupported | Unsupported | Unsupported | inlineData |

音频接WAV/MPEG、AIFF/AAC/OGG/FLAC、M4A/L16/Opus/ALAW/MULAW/WebM；视频接MP4/MPEG/QuickTime/AVI/FLV/MPG/WebM/WMV/3GPP。实际采用提供方和模型既有配置，不根据文档示例升级模型。类型兼容不保证容器内部编解码器或该模型可用；不转码、不提取帧、不生成替代文字。未知布局的原始音频仍由远端格式支持决定。

`AttachmentMimeTypes` 共用常见容器别名和扩展名映射：audio/mp3→audio/mpeg，audio/x-wav→audio/wav，audio/mp4/x-m4a→audio/m4a，video/x-msvideo→video/avi等。明确MIME优先；无MIME的webm按视频处理，纯音频webm须明确audio/webm。导入与手动复制共用扩展名映射，不重写已导入记录，不改变来源三方合并/并存；历史行在读取时只规范化别名。

正文与附件组合发送。不自动上传到提供商 Files API、不换模型、不降级重发。模型或兼容代理是否支持对应媒体仍由实际配置/上游决定，协议格式适配不能当作所有模型都支持的证明。OpenAI 两种编码依据 OpenAI Docs 官方格式。

### 当前限制

- 单文件复制/二进制读取最多 6 MiB，文本读取最多 1 MiB；空文件拒绝。
- 一个选中上下文最多 20 个附件出现次数；同 ID 复用读取结果，但重复出现仍计请求体积。原始读取总量最多 12 MiB，编码后的附件内容最多 16 MiB。超过限制明确失败，不静默丢附件。
- 文本支持 `text/*`、JSON/XML/JavaScript/YAML/TOML，必须严格UTF-8；二进制扩展为表内图片、PDF与上述音视频，按实际协议编码；其余MIME明确Unsupported。未增加文件读取预算或远端上传。
- pureAiMode 保持文字进入 system，附件另外进入 user 媒体消息，不向只接受文字的 systemInstruction 塞二进制。
- tool历史附件已接；其他非user/tool候选仍不发送，诊断标记 `non_user_attachments_not_sent`。图片/音视频生成输出、Office本地抽取、大文件上传/生命周期、全部工具媒体格式仍待补。Chat Completions音频输入使用user媒体part，工具来源明确标注，不放assistant/system字段。显式Gemini缓存仍有原独立预算，不宣称全部媒体缓存链已覆盖。
- 字符/4 上下文估算仍只估文字，诊断标记 `media_tokens_not_in_text_estimate`；不能据此保证多模态 token 预算。
- 小附件复制是可等待的 IO 接口，并非新持久任务；取消检查在读取块之间，content 提供方阻塞调用尚无完整隔离。没有新增 UI。

这里只记录实现范围；普通编译不代表实际模型、完整导入包或设备运行验证。没有执行账号调用、哈希校验、冒烟或新增单测。

音视频字段依据OpenAI Docs：[Chat Completions音频字段](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[音频聊天指南](https://developers.openai.com/api/docs/guides/audio-chat-completions)；Gemini沿本工程Generate Content协议：[内联音频](https://ai.google.dev/gemini-api/docs/generate-content/audio)、[内联视频](https://ai.google.dev/gemini-api/docs/generate-content/video-understanding)。不因最新文档推荐Interactions而替换用户原协议。

Claude当前适配只编码已有图片/PDF/文本，不将新音视频误标为image；参考 [Messages字段](https://platform.claude.com/docs/en/api/typescript/messages/create)。未尝试container上传或自动工具转写。

官方格式参考：[OpenAI 图像输入](https://developers.openai.com/api/docs/guides/images-vision)、[OpenAI 文件输入](https://developers.openai.com/api/docs/guides/file-inputs)、[Claude 图像输入](https://platform.claude.com/docs/en/build-with-claude/vision)、[Claude PDF 输入](https://platform.claude.com/docs/en/build-with-claude/pdf-support)、[Gemini Content/Part/Blob](https://ai.google.dev/api/generate-content)。

<a id="generated-media"></a>

## 模型生成图片

2026-10-08 后续音频：Chat/Gemini完整音频、字节分块合并/媒体历史和明确附件播放已接，见 [CHAT.md#generated-audio](CHAT.md#generated-audio)。下文为图片批记录；其他音频/视频、实时播放、远端文件/native/实际运行余项仍在。

2026-10-08：Gemini inlineData、Responses image_generation_call 完整图片接原普通/群聊/独立助手生成、当前assistant候选附件、选中历史和续聊；不再只保留base64或拒绝未知输出。无新表、框架、依赖、哈希、冒烟、单测或前端；来源合并并存不变。

### 配置

Gemini参数支持 responseModalities（别名response_modalities）和 imageConfig（别名image_config，aspectRatio/imageSize），也可放原generationConfig内。例如 `{"responseModalities":["TEXT","IMAGE"],"imageConfig":{"aspectRatio":"1:1"}}`。当前输出选择TEXT/IMAGE；选定模型是否支持由上游验证，不换模型/协议。

Responses显式设置image_generation对象才安装原生生图工具，例如 `{"image_generation":{"output_format":"png"}}`。支持model/action/quality/size/background/output_format/output_compression/partial_images/moderation，格式默认PNG也支持JPEG/WebP，不代选模型。它不是本地function，不伪造本地审批/工具结果。额外账单标记additional_tool_billing，现有主模型token成本不冒充完整生图价格。

### 保存与续聊

成功完整图片存新generated_托管文件，附件行/候选引用/绑定的原生元数据一次Room事务提交；提交后在不可取消段同步当前消息快照，防止已提交引用被旧快照覆盖。文件写入前后可取消，失败未提交文件清理；已提交引用保留，不重发远端请求。中间预览不作为正式结果保存。

MessageCandidate.images 和 ProviderTurn.mediaAttachmentIds 存引用；contentJson仅存本地媒体索引、原响应元数据/顺序/签名，不重复保存base64。Done.media只请求内暂存、不序列化。原消息观察/历史读取取得引用，新增getChatAttachmentContent(id)复用原有界读取器返回ContextMedia（图片base64或文本）；原附件复制到沙箱/工作区仍可使用ID。

同模型/协议从已准备附件还原原生数据，Gemini完整part与thoughtSignature不改；thought图片modelVisible=false，仅用于该原生历史，不作为跨协议补充图片。切换模型/协议或编辑/投影正文后，不复用旧签名；图片以注明助手来源的user媒体补充，整组本地工具结果仍先完成。其他群成员沿既有成员归属历史携带非thought图片。

单图6MiB、响应合计12MiB/最多20个；后续继续原20次附件/12MiB原字节/16MiB编码预算，文本原生元数据4MiB不变。LLM SSE单行/事件16MiB、总data字符64Mi，普通JSON仍16MiB，容纳完整图及Responses重复最终输出；不完整响应不作为成功图片保存。摘要/状态维护/TTS预处理要求文本，不静默丢媒体。

### 余项

生成音视频、其他图片提供方/独立Images API、远端URL/大文件上传、预览显示、完整native导出/恢复ID映射和真实模型/设备仍缺。普通编译不代表账号开通或实际调用已验证。图片参数仅后端，未改UI。

协议依据：OpenAI Docs的[Responses生图结果](https://developers.openai.com/api/docs/guides/tools-image-generation)及[图片格式](https://developers.openai.com/api/docs/guides/image-generation)、[Gemini图片及签名](https://ai.google.dev/gemini-api/docs/generate-content/image-generation?hl=en)。保留用户原模型/协议，外部文档不是执行用户资料的授权。

<a id="generated-audio"></a>

## 生成音频与聊天附件播放

2026-10-08 大文件播放补齐：startChatAttachmentPlayback改为从托管文件按需读取，支持当前本地256 MiB附件上限；编码格式交磁盘MediaDataSource，PCM只用有界小块。原getChatAttachmentContent仍为6 MiB内联内容接口，不再用它中转播放；生成输出本身6 MiB/12 MiB预算不变。停止、焦点和恢复不重播沿原任务，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

2026-10-08：Chat Completions message/delta.audio、Gemini inlineData音频接原三种聊天生成、assistant候选托管附件和历史，复用图片批的保存/提交链；不自动播放、转写、换模型或重发。无新表、依赖、任务框架、哈希、冒烟、单测或前端，来源合并并存不变。

### 参数

Chat Completions显式设置modalities及audio，例如 `{"modalities":["text","audio"],"audio":{"voice":"alloy","format":"wav"}}`。支持WAV/AAC/MP3/FLAC/Opus/PCM16和字符串音色或voice.id；型号/流式格式可用性由选定上游验证，不自动改格式。音频各data块单独base64解码再拼字节，不能拼带padding的base64字符串。转录保留在元数据，只有无普通正文/拒绝正文时作为助手正文；id/expires_at保留但不作为重启续跑或自动远端引用。

Gemini支持responseModalities中的AUDIO、speechConfig及responseFormat.audio（mimeType/sampleRate），沿原generationConfig；兼容根snake_case别名。speechConfig转发voiceConfig/multiSpeakerVoiceConfig/languageCode，不代选新模型/音色。请求的AUDIO_WAV/L16/MULAW/ALAW及实际返回MIME决定容器；不把新版WAV误当旧PCM。Chat的response_format同时按该协议保留text/json_object/json_schema形状，不把Gemini音频格式发成Chat字段。

### 保存与续聊

音频绑定MessageCandidate.attachments；仍用ProviderTurn.mediaAttachmentIds和内部索引，不把字节放入正文/元数据。Gemini流式音频及无头编码按编码/布局顺序合并为完整附件；每个原part保存该附件的offset/size，续聊还原原字节和签名，原生重组仅每附件解码一次。非流式完整容器直接保存。Gemini PCM按MIME或明确请求采样率保存rate/channels，当前官方单声道16bit LE；其他模型/协议不冒用其默认布局。

ContextMedia新增sourceMimeType，原读取器仍按规范MIME决定支持类型，原始音频参数在Gemini补充媒体中保留。Chat对已存WAV/MP3沿现有input_audio续聊；其他不支持类型明确提示附件保存但未解读，不假转写。Chat暂不自动复用短期audio.id，完整ID引用续聊仍是余项。摘要/状态维护/TTS预处理继续只接文本。

每个完整文件6MiB、响应媒体12MiB/20件，后续原上下文预算不扩大；SSE/普通JSON沿图片批预算。PCM中途断开不作为成功音频保存，完整结果按原Room事务/取消提交保存，不增加合成任务或语音计费事件冒充聊天生成。

### 明确播放

新增 `startChatAttachmentPlayback(attachmentId, pcmFormat?, playbackSpeed=1)`，沿原SPEECH_PLAYBACK宿主、音频焦点、速度、通知停止/stopTask、等待与观察；进度mode=attachment并保存attachmentId。WAV/MP3/AAC/FLAC/Opus/OGG/M4A/WebM交原播放器，实际容器/设备支持不伪造成功。PCM交原AudioTrack：Gemini已报告布局可直接用，Chat/导入未报告布局需明确PcmPlaybackFormat，不把Realtime/TTS的采样率套到Chat；明确覆盖与已报告布局冲突会失败。其他原始编码目前不自动解码播放。

### 余项

边生成边播放、音频ID完整原生续聊、更多编解码/布局、生成视频/Live会话、大文件上传/远端URL、整套native映射（含播放attachmentId）与真实账户/设备仍缺。普通编译只证明构建，无实际模型调用/声音播放或用户资料导入/恢复。

协议依据：OpenAI Docs的[Chat音频结果](https://developers.openai.com/api/docs/guides/audio-chat-completions)、[音频流事件](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)、[请求参数](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)，以及[Gemini原协议音频容器/布局](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation?hl=en)。

<a id="avatars"></a>

## 角色与 Persona 头像

Core 提供以下接口，不要求创建聊天，也不上传网络：

- `getPersona(id)`：读取 Persona，与原 `getCharacter` 对称。
- `setCharacterAvatar(characterId, source: Uri, filename, mimeType)`、`setPersonaAvatar(personaId, source: Uri, filename, mimeType)`：复制用户授权的 `content://` 图片并绑定，返回托管 `AttachmentRef`。
- `clearCharacterAvatar(characterId)`、`clearPersonaAvatar(personaId)`：取消绑定，返回更新后的角色／Persona。
- `getAvatarAttachment(relativePath)`：按角色、Persona 或历史消息中的相对引用读取托管图片元数据。
- `getAvatarFile(relativePath)`：供应用内部图片加载器读取，不把图片整体转成 base64。返回文件不持有后续生命周期的锁，加载器自行处理文件失效；不是外部挂载接口。

新选择图片支持声明的 JPEG、PNG、GIF、WebP MIME，`image/jpg` 正常化为 JPEG；空或通用 MIME 时按这几种扩展名补齐。最多 16 MiB 且不得为空，不解码、嗅探、转码或提取 PNG 内的角色卡。原导入图片不另加 16 MiB 限制，元数据读取须有托管附件记录与图片 MIME，文件读取另须文件存在。

沿用原附件复制和 Room 事务：文件先复制，附件记录与头像字段同事务发布；发布时重读当前对象，仅改头像路径与更新时间，保留同时发生的其它字段编辑。若复制期间头像路径已变或对象删除，拒绝发布，原绑定保留，并清理本次未发布文件。取消绑定不物理删除旧图，它可能仍被其它卡片、历史消息或导入基线引用；没有新增垃圾回收框架、表或任务。

桌面导入原头像路径继续可读。RikkaHub 图片头像按固定 2.5.5 的 `Avatar.Image(url)` 转换：仅从备份 `upload/<文件名>` 复制，不打开源设备的绝对路径。沿用原来源隔离、稳定附件映射和字段合并；本地替换或清除头像按原合并规则保留。远程／其它 URI、Emoji 和 Dummy 只保留原配置，不自动联网或生成位图；文件缺失由原导入报告说明。未运行真实备份或图片选择器。

格式依据只读核对的官方 [Avatar.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/model/Avatar.kt) 与 [BackupManager.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt)，不是用户实际版本或设备运行证明。

<a id="character-card-import"></a>

## 单张角色卡导入与开场白

Core 提供显式 `content://` 文件入口，不改变 Desktop/RikkaHub 整包自动合并：

- `previewCharacterCardImport(source: Uri)` 返回调用方持有的 `CharacterCardImportDraft`；读取 `.preview` 获得角色、可选世界书、开场白、来源格式、是否有 PNG 头像及警告。预览不发布角色、世界书或附件。
- `commitCharacterCardImport(draft, character=draft.preview.character, worldBook=draft.preview.worldBook, avatarFilename="character.png")` 保存；可编辑角色/世界书后提交，传 `worldBook=null` 排除内嵌世界书。
- `importCharacterCard(source: Uri, avatarFilename="character.png")` 是明确的一步导入。
- `characterOpeningMessages(characterId)` 返回主开场白及候选；`createChat` 将启用且渲染后非空的普通开场白批量保存为同一节点的候选，用 `openingMessageIndex=0` 按原索引选择，之后可沿 `selectCandidate` 切换。索引 0 始终是主开场白，包括空文本；选中空项时不创建开场节点。单聊默认启用且不覆盖 RikkaHub presetMessages；群聊默认无开场，可用 `firstMessageCharacterId` 选择成员，并按该成员列表取索引；`useOpeningMessage=false` 关闭普通开场。群聊不复制单人来源预置消息，详细组合见 [CHAT.md#message-editing](CHAT.md#message-editing)。
- 候选跳过显式 chip=false，其余沿原非空文本列表；保存普通开场前按实际开场角色与当前 Persona 替换精确 `{{char}}`/`{{user}}`，纯 AI 用“用户”。createChat 的 pureAiMode 可明确覆盖或沿原来源继承；角色原文、旧聊天和来源预置消息不回填。

支持 ST v1 平铺 JSON、v2/v3 的 data 对象，以及 PNG tEXt 中的 ccv3/chara 数据。ccv3 优先，坏候选跳过后继续 chara；可读取明文 JSON 或 Base64。输入最多16 MiB，JSON正文最多4 MiB；只做字节范围、UTF-8 和格式解析，不做哈希或 PNG CRC，不解码图片、解压 zTXt/iTXt 或执行脚本。

字段映射沿桌面普通导入：描述/性格/场景、first_mes、mes_example、system_prompt、alternate_greetings 与内嵌 character_book。没有性格及场景时描述作为性格；世界书关键字/正则、常驻和排序进入现有上下文链。候选开场白存入原 extraJson 的 extraFirstMessageEntries，因此桌面导入已有的同字段也可选择。其它扩展保留原 JSON；regex_scripts/tavern_helper 只报告 unsupported_extension_retained，不自动转换、启用 MVU 或执行 JavaScript，不把普通导入称为完整 ST 扩展/MVU 兼容。

草稿保留预览时的 PNG 字节，确认不重开文档提供方；没有 pending ID、TTL、后台线程、表或新导入任务。预览读取在 IO、解析和开场白列表读取在 Default，纯外部文件预览不占业务数据 gate；发布才持原共享 gate 和事务。它只在调用方内存中存在，进程结束后不恢复；释放引用即可释放字节。每次确认创建独立本地角色与世界书/条目 ID，不覆盖已有数据，重复确认也会新增独立条目。角色关联本次世界书，不保留输入卡中的外部 ID、路径或来源命名空间；PNG原图成为托管头像，JSON 内的 avatar/URL/data URI 不自动读取。世界书不自动全局启用。

PNG 的附件、角色和世界书沿原复制回调同事务发布；JSON 的角色与世界书同事务保存。新建聊天仍沿原聊天事务与上下文链，没有新 schema、依赖、前端、哈希、冒烟或单测。普通源码编译不能证明真实卡片、选择器、图片解码、备份或设备可用性。

<a id="library-removal"></a>

## 资料库、聊天、助手与模型预设删除

### 2026-10-10 完整字段编辑

Core 已补 `editSelected` / `editSelectedAndTruncate` 的 MessageCandidate 重载，提供正文、思考、图片/附件、角色和发言人快照编辑；原 String 重载仍只改正文。旧候选/工具记录/请求用量/来源原文保留，截断仍沿原事务和批量删除。附件只改引用，不物理删除共享文件；详情见 [CHAT.md#message-editing](CHAT.md#message-editing)。下方“仅正文、不提供全部字段”的说明是早期 String 接口范围，不是当前没有其它字段编辑。

### 2026-10-10 编辑并原子截断后续历史

Core 新增 `truncateMessagesAfter(nodeId): Long`，保留指定节点及其全部候选，删除同会话中 orderIndex 更大的节点，返回删除节点数；`editSelectedAndTruncate(nodeId, content): MessageNode` 把当前选中候选正文编辑和上述截断放在同一 Room 事务，返回编辑后的节点。其它候选保持，缺失节点/选中项沿原 NotFound；不是删除整个会话，也不发起生成。桌面参考为 chats.py 的 save_message_and_truncate；本接口按 Android 既有 editSelected 正文编辑契约，不宣称提供桌面 UpdateMessageRequest 的全部字段编辑。

每批仅取100个节点及候选ID，按索引查询后续节点，再批量删除候选、节点和关联正则记录；不一次加载整段聊天正文，不做逐条重复 JSON 解析或文件删除。整个编辑/截断仍统一提交，批次不是对调用方可见的部分提交。其余会话、独立分叉、当前节点和更早消息保持；沿原本地删除保护保留来源映射/基线。仅被删除项的当前锚点由上轮同一实现清理，记忆正文、共享附件/语音缓存、任务/事件和账目历史不物理删除或自动重建，不自动停止/重放任务。

普通 editSelected 的正文确实变化时，同时将该候选旧正则提取记录标为 stale 并递增 revision，保留旧诊断而不把它作为可消费的新来源；正文未变化不失效。删除的消息仍删除对应队列记录，以避免有效队列指向不存在的候选。无新表、迁移、哈希、通用删除框架或测试；Luna Curie 实际编码六个小型 DAO 查询，主代理接仓储/Core，构建证据见 [VERIFICATION.md](VERIFICATION.md)。

历史说明（本段记录早期批次，当前契约已更新）：当时重新生成尚未接入。现由独立 `startRegeneration` 按目标节点截断请求上下文，并仅在成功时原子合并候选和清理启动时捕获的旧尾部；停止、失败和冲突保留原历史。当前完整语义见 [CHAT.md#message-regeneration](CHAT.md#message-regeneration)。普通 `startGeneration(..., userText=null)` 仍追加节点；`editSelectedAndTruncate` 也仍是独立编辑接口。

### 2026-10-10 单消息与候选删除

Core 新增 `deleteMessageNode(nodeId)` 与 `deleteCandidate(nodeId, candidateId): MessageNode?`，沿原数据 gate 和 ChatRepository 的 Room 事务执行，普通聊天及使用同一消息存储的助手会话均可调用。桌面参考为 chats.py 的 delete_message 和 assistant.py 的 delete_assistant_message；Android 按已有节点/候选模型实现，不把删除单条消息冒充删除整个会话。

删除节点会移除全部候选及对应正则提取队列，将同会话直接子节点接到被删节点的原父节点；保留其它消息的顺序值，不删除后续消息或其它分叉。删除单候选按 ID 操作并重排该节点的 candidateIndex，原选中候选仍存在时保持选中；若删的是选中项，优先选择其后相邻候选，没有后项则选前项。最后一个候选删除后移除节点并返回 null，否则返回更新后的节点。不存在的节点或不属于该节点的候选返回 NotFound。

只清除确实指向被删节点/候选的 contextStartMessageId、lastAutoMemorySummaryAfterMessageId、lastStateMaintenanceAfterMessageId；清上下文起点时同步清配套 keepBefore。长期记忆正文不丢弃或重写，仅在其覆盖锚点被删时置空并递增版本，不伪造新的覆盖范围。若希望总结内容也反映删除，应显式重建记忆；此接口不发起总结、模型请求或状态维护。其它会话、角色、知识图谱及已有状态内容不自动改写。

共享附件/语音缓存的字节、历史任务/事件/费用、来源映射/导入基线仍保留，不做垃圾回收或自动停止其它任务。原 streaming 候选更新检查已删除/已改内容，原总结/状态/正则提交仍核对当前来源；删除不新增重放/恢复机制。来源节点或候选有既有导入基线时，再导入沿原本地删除保护，不复活已删除项。native 恢复对附件/历史任务的消息引用原本允许历史缺失，当前有效消息/父链接/锚点/正则队列仍须一致；本批未执行真实导入或恢复。

Luna Huygens 实际编写 MessageDao 的节点删除/子节点重接及 ContentRegexDao 的两种定点删除查询；主代理接仓储和 Core。无新表、迁移、删除框架、哈希、冒烟或单测，构建与实际环境证据另记 [VERIFICATION.md](VERIFICATION.md)。

### 2026-10-10 助手配置删除

`deleteAssistantConfig(id, expectedUpdatedAt, deleteRelatedChats=true)` 返回删除的独立助手会话 ID。缺失返回 NotFound，读取后配置更新时间变化返回 Conflict。默认只连带删除 `overrides.assistant.assistantId == id` 的独立助手会话；传 false 时，有此类会话就返回冲突。绑定的普通主聊天不属于助手，不删除。复用原 ChatRepository 的消息/规则、总结/记忆、MVU/KG 和日志清理，在同一 Room 事务内清除旧 assistant_messages 和配置行；中途冲突回滚。

已有非终态会话任务由原聊天删除检查拒绝；当前使用该配置的语音合成、播放、设计或克隆任务也返回含 taskId 的冲突。不自动停止或等待，不引入删除 worker、表、迁移或通用引用扫描。Luna 编写 ConfigDao.deleteAssistant，主代理接资料库与 Core。

RikkaHub 角色按原来源映射依赖成对的助手配置，若对应角色仍在，返回含 characterId 的 Conflict，调用方先通过 deleteCharacter 明确处理角色及其聊天，再删配置；不因删除配置静默删除资料库角色。source_settings/source_memory 映射的行是共享容器，不允许作为单个助手删除。保留全局与个人来源记忆库、技能、工作区、沙箱、共享附件/头像、缓存、凭据、历史任务/用量及来源映射/基线。后续导入沿原本地删除保护，不复活删除的来源配置或会话。只有本地 default 配置仍沿原 createAssistantChat 的明确创建行为：不存在时创建空配置，不恢复旧配置/会话。

普通离线构建通过1分31秒，KSP 删除 SQL、runtime Core JAR 和 APK 中的新入口可见；未执行真实记录删除、导入/恢复、账户或设备操作，不做哈希、冒烟或新增单测。共享来源容器整源移除与文件垃圾回收不属于这个单助手删除接口；整体后端验收仍未完成。

### 2026-10-10 模型预设删除

`deletePreset(id, expectedUpdatedAt)` 经 Core 原数据 gate 和 Room 事务删除未被当前选择使用的模型/语音预设。缺失返回 NotFound，更新时间变化返回 Conflict。Luna 编写原 ConfigDao 的删除 SQL，主代理接资料库业务与 Core，不新增表、删除任务或垃圾回收引擎。

当前会话的 presetId、群成员 presetId、ttsJson 中的 presetId/preprocessPresetId，以及助手/来源设置的同类选择、chatDefaults.overrides 都会阻止删除，冲突提供 chatId 或 assistantId；全局设置占用也会阻止。同来源的 Desktop default-connection 与 llm、RikkaHub chatModelId 和 selectedTTSProviderId（明确 null 时沿原首项规则）按既有运行时选择语义检查。调用方应通过原 saveChat/saveAssistantConfig/saveSettingsJson 明确改选或解除选择，再删除；不会自动选另一提供商，也不会重写所有配置。

只检查已实现的选择字段，不在消息、未知原始 JSON、旧账本或整个字符串中猜测引用；不可解析的历史配置不当作有效的当前选择。保留源映射和基线，明确再导入沿原本地删除保护，不自动复活预设。历史消息、任务、费用快照、缓存、共享凭据和来源原文均不删除，不自动停止任务、注销 OAuth、撤销云资源或重发请求。其它模型预设里的 sourceProviderId/credentialId 仅是现有凭据选择名，不依赖被删的预设行，所指共享凭据也仍保留。

本批未删除任何真实用户记录、执行导入/恢复或运行设备；没有哈希校验、冒烟或新增单测。独立助手配置删除及共用来源设置/记忆库的管理不在本批伪称完成，原有资料库接口保持。

### 原有角色、Persona 与聊天删除

`deleteCharacter(id, expectedUpdatedAt, deleteRelatedChats=true)` 返回连带删除的主聊天 ID 列表。默认沿桌面角色删除语义删除 `characterId == id` 的主聊天；传 false 时，仍有主聊天就返回冲突，不留下无法继续聊天的主角色引用。在其它群聊仅移除该角色的成员、成员覆盖与 MVU 锚角色引用，不删除群聊或其历史；群聊不足两个成员时保留为群聊，需明确补齐成员后生成，不自动转换会话类型。

`deletePersona(id, expectedUpdatedAt)` 删除 Persona，并将使用它的会话 `userPersonaId` 清空。两项接口使用先读到的 `updatedAt`，改动或缺失明确报错。取消头像绑定和删除卡片均保留共享附件，不自动删除世界书、模型预设、来源助手配置、技能库或沙箱。

原 `deleteChat(id)` 与角色级连带删除共用 `ChatRepository.deleteSession`：同事务删除消息节点/候选、正文规则记录、长期记忆、总结记录、MVU、KG 和状态工作日志；保留历史任务、用量账本、来源映射/合并基线与共享附件。独立助手若绑定了这个主聊天，只清除其 `targetChatId`，保留助手会话及历史，不改绑到其它聊天。分叉来源 ID 作为历史溯源保留。

删除或解绑时，命中受影响会话已有的非终态任务记录则返回冲突，包括排队、等待审批与停止中；不会自动停止、续跑或重发。操作走原数据 gate 和 Room 事务，后续关联处理失败回滚本次结构化变更。没有新增删除 worker、任务、表、依赖、哈希或垃圾回收框架；这不是所有并发启动/断电/native 恢复的完整证明。

导入映射和基线不删除，因此原按字段三方合并继续保留本地卡片/Persona/主聊天删除，以及其它群聊成员与 Persona 解绑。重新导入不是撤销删除接口；没有执行真实用户数据删除或导入。

实现依据：桌面 [角色删除路由](E:/SimpleTavern/SimpleTavern-Desktop/backend/app/routes/characters.py:140) 和 `storage.delete_character`，Android 以原结构化存储清理关联并保留共享文件。普通离线构建通过1分34秒；真实设备、备份与并发表现尚未运行验证。
