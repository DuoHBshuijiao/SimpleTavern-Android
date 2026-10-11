# 语音

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="tts"></a>

## 云端语音合成后端（第二十六至三十五批）

2026-10-09 已有本地网关：glm_local/qwen3_local/omnivoice_local 的 WAV 合成、托管参考音频、原合成/缓存/播放/未知费用接线已实现；不启动桌面模型或读取桌面路径，地址/第二网关须明确，网络仍限 HTTPS/原回环 HTTP。普通离线构建成功73秒，不是网关/设备验收，见 [SPEECH.md#local-tts-gateways](SPEECH.md#local-tts-gateways)。

2026-10-09 追加：MiniMax/GLM/SiliconFlow 参考音频上传与克隆已接原创建任务链，显式托管音频输入、不自动下载试听或改预设，GLM契约仍未获官方实证；下文“克隆全未接”为历史状态，本批边界见 [SPEECH.md#voice-clone](SPEECH.md#voice-clone)。

2026-10-09 追加：MiniMax 音色设计接原来源选择、独立任务/通知停止、托管 MP3 试听与未知费用账本；不自动改预设或播放。下文“语音设计全未接”为历史状态，参考音频克隆仍待补；本批契约/编译边界见 [SPEECH.md#voice-design](SPEECH.md#voice-design)。

2026-10-09 更新：整稿预处理一次后分段已编译通过，逐段预处理为下文历史状态，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。本批追加ElevenLabs音色分页/模型目录，仍只在明确fetch时读取，不合成/克隆或改预设；本批编译/账户边界见 [SPEECH.md#speech-directory](SPEECH.md#speech-directory)。

2026-10-08 长稿/流式追加：云端播放按已有单次限额拆段，二进制及Qwen/MiMo/火山SSE接增量播放器和原任务/缓存。MiniMax/Gemini当前JSON仍收完，预处理仍逐段，段间无缝/媒体/型号/native/设备仍未完成，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

2026-10-08 六家追加：Qwen/xAI/Fish Audio/Step/MiMo/Volcengine 已接实际请求及来源播放工具，SSE 音频收集/取消、格式和字段转换沿原合成/缓存；不是只有保留 metadata。范围见 [SPEECH.md#speech-source-adapters](SPEECH.md#speech-source-adapters)。

2026-10-08 新增来源适配：OpenAI/Groq/Gemini/ElevenLabs 已接实际合成及来源播放工具，公开 SpeechRequest 参数与三项播放 API 的 playbackSpeed 已补；默认来源播放速度已应用。下述四家/仅保留来源等文字是此前批次记录，当前准确范围见 [SPEECH.md#speech-source-adapters](SPEECH.md#speech-source-adapters)。

2026-10-08 后续播放实现：系统 TTS、已有缓存音频与合成后播放现已接实际设备输出、原后台任务和 mediaPlayback 宿主，新增播放 Core 接口/独立停止及来源 text_to_speech；RikkaHub System/MiniMax 与原明确云端预设已接，其他来源、播放参数和实际设备仍待补。下述“未播放”描述是此前云端合成接口自身的契约，不是说新增播放接口没有实现，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

2026-10-08 主线核对：固定 RikkaHub TextToSpeechTool 要求按所选 TTS 提供方实际后台播放并立即返回，现有本工程四家云端合成/缓存/绑定并不实现播放。本批只读确认 speech 模块 TTSManager 路由包括 System、OpenAI、Gemini、MiniMax、Qwen、Groq、XAI、MiMo、Step、ElevenLabs、FishAudio、Volcengine；并核对 TTSProviderSetting 的系统 rate/pitch 等字段。不能以默认系统引擎替换已选云端服务，也不把四家接口当完整来源兼容。实际音频播放、系统引擎、来源提供方选择转换及 text_to_speech 仍是后续主线，日历/屏幕时间本批已接，见 [ASSISTANT.md#device-tools](ASSISTANT.md#device-tools)。来源依据：[固定 TextToSpeechTool](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TextToSpeechTool.kt)、[固定 TTSManager](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/speech/src/main/java/me/rerere/tts/provider/TTSManager.kt)、[固定 TTSProviderSetting](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt)。未实际执行语音。

更新：2026-10-05。已实现四云端非流式调用与显式后台合成任务、UUID 缓存、启动时可选消息挂接、延后绑定/解绑与独立用量/显式估算账本；不代表整套桌面 TTS、设备后台或真实上游已验收。desktop/RikkaHub 自动合并并存不变，没有内容哈希、冒烟或单测。

### 公开入口与选择

第三十四批预处理普通 token 账目改同一行 prepared/running/终态与四协议发送前回调；第三十五批聊天/总结/状态维护当前调用点也已接同类账目，账本独立于业务应用收尾，见 ACCOUNTING.md#token-request-accounting。startup/native 只中断 unfinished，旧无标记历史兼容，分页可空 tokenRequestLifecycle。不补发失败请求，本地发送标记不是服务器接收/实际费用或进程/磁盘耐久性证明。

第三十二批独立 `previewSpeechPreprocessing` / `preprocessSpeech(SpeechPreprocessRequest)`，第三十三批会话 preprocessEnabled→LLM→合成已接。同来源两套选择固定、原正文不动、完整稿/取消与失败不回退，两个独立账本以 preprocessingRequestId 关联。后台保存实际 spokenText、preprocessing 阶段/已处理 UUID，合成前与提交时复查目标正文/来源/行预算，native 增预处理关系与保留账目保护。独立 preprocessSpeech 本身仍不合成/挂接。默认每 Call 防 follow-up 重发共用于语音/目录/搜索/LLM。范围、最终记录/发送与崩溃局限见 [SPEECH.md#speech-preprocessing](SPEECH.md#speech-preprocessing)。

第三十一批增加独立 `getSpeechDirectory(SpeechDirectoryRequest)`：默认本地，明确 fetchVoices/fetchModels 才查询选中来源官方同源端点，不要求朗读稿/音色/model，也不要求启用 TTS；合成仍须启用。配置、桌面静态及远端条目 origin 分开、失败/未支持/缺凭据状态分开，不自动保存/选取/下载试听/克隆或记合成账本。MiniMax/GLM/SiliconFlow 音色与 OpenRouter/SiliconFlow 模型已接当前有界适配，GLM 是桌面契约待实证，SF 音频模型不证明 TTS，OR 云端音色仍未支持。准确范围、预算与官方资料见 [SPEECH.md#speech-directory](SPEECH.md#speech-directory)。

`previewSpeech(SpeechRequest)` 只读本地配置，返回 SpeechSelection：实际预设、来源助手、provider、model、voiceId、完整合成 endpoint（字段名 baseUrl）、format、credentialId 与 hasCredential。不发请求；hasCredential 仅代表加密存储存在，不保证账户有效。该入口也检查来源 ttsEnabled 与合成参数，不是绕过禁用的账户探测。

`synthesizeSpeech(SpeechRequest)` 显式发送一次新合成，返回 SpeechAudio：音频 bytes、provider/model/voiceId/format、可确定的 sampleRate、MiniMax 可得的 reportedUsageCharacters、warnings 与 requestId。调用者拥有 bytes；不自动落音频文件、改消息或播放，不创建 TaskStore/前台任务，不承诺锁屏/进程死亡后继续。第二十九批开始直接合成与后台合成都记独立 TTS 事件/估算，缺费用保持未知，见 [ACCOUNTING.md#speech-accounting](ACCOUNTING.md#speech-accounting)。请求和结果 toString 不输出文本/音频。JSON 序列化 SpeechRequest 仍含文本，调用方不要写入普通日志。

预设取 request.presetId，其次会话 overrides.ttsJson.presetId；不选第一条。预设 configJson 的 presetKind 必须为 tts 或旧 minimax，ttsProvider 明确选择（旧 minimax 才有兼容缺省）。credentialId > sourceProviderId > 当前预设 ID，密钥只来自 setProviderCredential 的加密存储，拒绝配置中的非空 apiKey/api_key/token。

有 chatId 时要求预设与会话 sourceNamespace 相同。显式 assistantId 必须存在且同来源；否则先按现有 source_settings/settings 身份映射定位来源 settings，映射失效明确失败，不借全局或另一来源。没有该映射的历史来源只有一个助手时才可选择，否则须显式指定。原生无来源预设默认 global；所有作用域须 ttsEnabled 为 true，缺失/false 为禁用，非布尔值报配置错误。

模型取请求 > 会话 model > 预设 model > 唯一 models 字符串，不猜云端默认。音色取请求 > 当前显式 characterId 的 voiceByCharacterId > 当前显式 personaId 的 voiceByPersonaId；缺失报错，不选第一音色。preprocessEnabled=true 才执行自动预处理，injectEmotionTags 单独开启而预处理关闭明确 Validation；标签目前限明确 MiniMax 2.8 型号。preview 的 SpeechSelection 增冻结 preprocessing；直接 SpeechAudio 增实际 spokenText/预处理 ID，合成 requestId 保持独立。自动朗读/间隔不由直接合成入口驱动。

桌面新导入把 tts.presetId/preprocessPresetId、voiceByCharacterId/voiceByPersonaId 的键按原有来源映射重写，音色值、模型和未知控制字段不改，extraJson 保留来源表示。仍走三方合并/本地编辑保护。旧已导入 raw ttsJson 不在启动时批量重写；重导入是否更新取决于既有基线/本地改动，必要时显式选择 Android 预设/音色。RikkaHub 独立设置不覆盖桌面/global；其原生 TTS 配置格式转换尚未适配，不把保存原始设置声称为可合成。

### 显式后台任务与缓存（第二十七批）

`startSpeechTask(request, messageCandidateId = null)` 创建新 SPEECH_SYNTHESIS task。注册前解析来源/凭据和校验输入，TaskStore 等待前台宿主提升；HTTP/文件工作在 core scope，不依赖 UI 订阅。最多 16 个本进程排队/运行任务，与直接入口共用 2 个合成 HTTP slot。`getSpeechTaskProgress` 为持久阶段，`observeSpeechTasks` 仅最新通知（不是全部任务历史），全活动列表仍用 observeActiveTasks。`awaitSpeechTask` 等该任务后返回 SpeechAsset，不取消 worker；UI 不等待也可继续运行。

`stopTask` 和逐任务通知停止已分流到语音 worker，宿主中断先记原因/cancel HTTP 再写 STOPPING。ATOMIC+ready 握手确保首次调度前取消也收尾；任务排队即持 shared gate lease，拒绝原生快照/恢复穿过音频/消息中间态，但普通数据写入/其他网络仍并行，不持长 Room 事务。清理保留 host handoff。前台类型沿用既有 dataSync，平台限额/后台启动/锁屏/厂商/杀进程行为没有设备证据，不承诺无限后台或重启续发。

若要在启动时挂消息，request.chatId 与 messageCandidateId 必须真实存在，消息 content 必须与 request.text 完整相等；开始、合成前和提交读取字节预算/比较原正文，并拒绝目标候选元数据仍为 running/awaiting_tools，不使用哈希。发布时只更新当前 extraJson._tts，保留其他字段、角色、候选选择和原正文，保存 v2 SpeechMessageBinding(assetId, sourceText, messageText)：sourceText 是实际朗读稿，messageText 是启动原正文，预处理时二者可不同。不接受尚未持久化的 local_* 临时消息或默默忽略挂接失败。正文变更/消息删除时整次挂接提交失败，旧绑定不改，新音频不作为成功资产保留；上游请求可能已收费，不自动再次合成。启动时仍以本次显式请求替换绑定；下面的延后绑定则要求旧引用前提。

文件先在 speech_staging/<taskId>.part 独立创建，O_EXCL/O_NOFOLLOW、逐段写与 fsync；记录生成文件 device/inode/size 的 publishing 进度后，在 attachments/tts-<taskId>.<format> 无覆盖 link。提交前后复查目录、类型/身份/长度，attachment 行（id=tts:<taskId>）、消息绑定和任务 SUCCEEDED 同一短 Room 事务。缓存预检/目录扫描/淘汰可取消，不占停止锁；只有最后发布决策/短提交 NonCancellable，成功数据库提交优先于该分界后的停止。使用的公开文件操作依据 [Android Os 文档](https://developer.android.com/reference/android/system/Os)，没有借用隐藏 unlink/O_DIRECTORY API。

Task payload 是 SpeechAsset 元数据目录，attachments 行是已提交音频标记；不新建业务表，Room v2/25 表不变。第二十八批新资产为 v2，任务 payload 保存实际发送的 spokenText 和冻结 sourceNamespace，以便重启后延后绑定时有真实朗读稿；不保存 HTTP body、密钥或音频字节。可选消息绑定保存实际朗读稿 sourceText 和绑定时正文 messageText，普通备份不是文本加密；这些文本可能包含用户写入的敏感内容。SpeechAsset/Binding/BindRequest 的 toString 脱敏不意味着 JSON 序列化脱敏。SpeechAsset 的字符用量仍只是报告值，不是账本/费用。直接 synthesizeSpeech 不新增这种持久记录。

`getSpeechAsset(assetId)` 查询可用元数据，`readSpeechAudio(assetId)` 在缓存锁内用 O_NOFOLLOW 打开、fstat 类型/长度、至多 16 MiB 分段读取并检查提前结束/多余字节。`getMessageSpeech(chatId, messageCandidateId)` 在同一短事务内核对当前正文、绑定与缓存；正文须等于 messageText（旧 v1 为 sourceText），v2 资产还须同来源且朗读稿相等。编辑、淘汰或缺文件返回 null，格式/元数据异常仍报错。不把缓存缺失当自动请求授权，不按文本哈希/相同文字隐式去重。缓存文件不是长期内容附件，直接入口 synthesizeSpeech 仍不保存。

`getSpeechCacheStats(assistantId = null)` / `clearSpeechCache(assistantId = null)` 按冻结来源助手作用域操作，null 仅 global，不是清所有来源。统计 files/bytes 是已登记资产数/声明字节，missingFiles 单列缺文件；不是整个附件目录物理占用。清理只处理成功 TTS task 与 tts: 附件共同证明的 UUID 文件，先验证完整目标集合，保留普通/来源附件、消息正文和历史任务。清理/淘汰只删除音频和对应附件目录行，历史 _tts 可保留为失效引用，getter 返回缓存缺失。清理可取消/IO 失败可能部分完成，重试清理不会重发语音。

新任务冻结来源 settings.ttsAudioCacheLimitMb（整数 10..10000 MiB，缺省 200）；发布前按 createdAt 的 Instant 从旧到新淘汰本作用域，确保登记字节加新音频不超限，单条音频超限拒绝，不截断。不是每 30 秒桌面巡检/LRU：更改限额在下次新任务发布应用，现有缓存不由本批定时清理；多个来源各有独立限额，不把它当全应用硬物理磁盘配额。库存分页 200、每行 payload 128 KiB、整次至多 100000 行/64 MiB payload，超预算明确失败，不拿部分目录做成功统计/清理。消息正文+extras+generation metadata 总读取和绑定后写入均至多 256 KiB；已选资产可直接读取，不依赖全库存扫描。

失败/取消只撤掉本次未提交且身份一致的链接和 private stage，已提交音频保留。清理异常锁 shared gate，须启动恢复先解决；成功任务仍保持成功，pending 清理标记不假装合成失败或授权重发。正常清理后去掉 inode/设备日志，启动只扫描非终态或明确 cleanup pending/required 的 TTS task，不遍历全部历史。旧任务只终止/清理，不联网、不续发。

原生包可携带已提交附件文件、tasks 元数据（包括 v2 spokenText）和消息 _tts，完整可读缓存须同时包含这些表/文件；排除任务/附件的部分包可能只保留历史引用。private stage 和 HTTP closure 不出包。恢复严格检查 TTS payload 版本/预算/身份/作用域/完整附件关系，清除包内 device/inode 与清理标记；未成功音频引用也移除，不用另一设备的旧 inode 删除当前文件。候选消息的 _tts 校验 v1/v2、总行预算和可得 task/朗读稿/来源，正文后来编辑只 warning，允许同来源分叉/多个消息引用同资产，不要求引用消息等于资产创建消息。已淘汰缓存缺引用只 warning，不重建旧音频。第三十批补上包外原有效语音引用受任务/附件/会话/原消息/助手预设/账目/文件覆盖影响的守卫，详见 [NATIVE_BACKUP_V1.md](NATIVE_BACKUP_V1.md)。旧 APK 不认识新 task enum/v2 asset/binding 会拒绝相应包，不声称全版本兼容。

这些是代码/普通编译证据，不是文件系统强隔离、全字节真实性、断电原子性、WAL/目录 fsync 或实际后台运行证明；现有未隔离 shell/公开低层 repository 仍不能作为恶意并发边界。

### 覆盖恢复与保留引用（第三十批）

REPLACE_INCLUDED 不授权把包外消息的原有效 _tts 指到另一资产/冻结选择、稿、来源或账目。NativeSpeechRestoreGuard 分页检查旧逻辑可用引用与合并后关系，v1 已有的原消息朗读稿/来源证明也保护；同来源分叉/共享音频照常。旧编辑失效/淘汰/不可读元数据为历史 warning，不自行修复。扫描先 SQL 校验 ID/256 KiB 总行，至多 100000 行/64 MiB 文本读取，超限整次失败而非部分成功。

当包内缓存文件会改变旧有效引用所指的普通、声明长度音频时，用现有完整字节/模式比较阻断，未添加哈希；缺失/异常旧文件不冒充有效文件证明。包内 task 的完整附件字段、v2 创建来源与可得 requestId 账目/成本关系也核对，不把可读目录当可播放/实际账单证据。检查/真正恢复复用，细则见 NATIVE_BACKUP_V1.md，全部其余引用/版本/真实 IO/持久性继续审计。

### 延后绑定与解绑（第二十八批）

`bindSpeechAsset(SpeechBindRequest(assetId, chatId, messageCandidateId, expectedMessageContent, expectedAssetId = null))` 只读已有成功缓存并写消息引用，不发 HTTP/创建任务/复制音频。expectedMessageContent 完整比较当前正文，至多 128 KiB UTF-8；拒绝仍在生成的目标。actual spokenText 取资产冻结记录，不由调用者伪造或拿目标正文代替，允许调用者显式把独立翻译/朗读稿音频绑定到另一正文；这不实现自动翻译/预处理。目标须与资产冻结来源相同，创建时的 attachment/chat/candidate 所有者不改，因此同一音频可被多条同来源消息引用。

expectedAssetId 缺省 null 只创建空绑定；替换必须提供实际旧资产 ID。已是同资产/同朗读稿/同正文则幂等返回。Room 内检查前提，以完整旧正文/extraJson CAS 更新，只改 _tts；不改正文、角色、候选选择或其他 extras。缓存清理和绑定共用 mutex，资料恢复用 data gate 阻挡，未隔离 shell 不属于这些锁的恶意写入防线。

`getSpeechMessageBinding(chatId, messageCandidateId)` 返回当前存储引用，即使已失效也返回，便于明确替换/移除；与仅返回可用资产的 getMessageSpeech 不同。`unbindSpeechAsset(chatId, messageCandidateId, expectedAssetId)` 比较旧 ID 后仅删除 _tts，无绑定返回 false，另一个资产引用则 Conflict；不删除共享缓存/其他消息引用/历史任务。两种写入都先检查新总行预算，避免提交后无法读取。

旧 v1 缓存可继续读取。延后绑定旧音频仅能从原始持久消息 _tts 证明朗读稿且原始来源会话仍可得；没有原挂接、被替换或原消息删除时明确 tts_transcript_unavailable/NotFound/Unsupported，不猜正文，不为缺证据再次合成。旧 v1 无冻结来源，原有 getter 保留旧读语义，不能据此宣称历史所有跨来源引用都已追溯证明。同来源分叉保留 UUID 引用；缓存仍是共享可淘汰资产，不变成每条消息永久副本，完整分叉/设备证据继续待补。

### 独立用量与费用（第二十九批）

直接 synthesizeSpeech 与后台 prepared.synthesize 共用 SpeechUsageStore，复用 usage_events(_kind=tts)/app_settings(speech_pricing_v1)，不加表。prepared/running/终态是一行、完整旧 JSON 条件更新，成功或失败记与缓存是否发布分开；上游成功但本地挂接失败仍保留合成账目，不重发。账本只存字符数/发送参数/范围/时间/状态/可得报告字符，不存正文/key/audio bytes。

getSpeechPricingRules/replaceSpeechPricingRules/installSpeechPricingCatalog 是显式 user/catalog 与 epoch/revision CAS；支持按次/输入 Unicode code points/供应商报告字符的明确费率，模型/来源/作用域/参数/有效时间精确匹配。没有内置实时价表，估算不是发票，不拿未知量补零/token；非成功的按次/输入计价未知。通用 usageEventsPage/usageSummary 可按 kind=tts，字符和 token 分列，多币种分开，成本异常不计入已知汇总。返回音频和新 asset 保留 requestId，缓存读取/绑定/清理不新增账。

合成事件生命周期 shared gate 防原生快照/恢复截断，短事务外 HTTP；直接合成现在也写库/持 lease，但不创建前台宿主。启动/资料恢复只标旧未结束事件 interrupted 并核对预算/成本/身份，不重发合成。完整语义与未实现来源价表/其余计费维度/真实账单见 [ACCOUNTING.md#speech-accounting](ACCOUNTING.md#speech-accounting)。

### 当前传输和参数

固定官方 HTTPS/443、Bearer、无 URL 凭据/query/fragment；允许对应基础路径或完整合成路径，不执行自定义 headers、代理地址、下载音频 URL、重定向、自动重试或失败换提供方。

| provider | POST 路径 | 本批请求/响应 |
| --- | --- | --- |
| minimax | /v1/t2a_v2 | model/text、stream=false、hex 输出、voice_setting 与 audio_setting；严格 JSON 成功码/结束状态、hex 音频、可得字符用量/采样率 |
| glm | /api/paas/v4/audio/speech | model/input/voice、wav、speed/volume；二进制响应 |
| openrouter | /api/v1/audio/speech | model/input/voice、显式 response_format/speed；二进制响应，模型能力仍由上游决定 |
| siliconflow | /v1/audio/speech | model/input/voice、stream=false、response_format/speed，可选 sample_rate/gain；二进制响应 |

默认来源地址沿用桌面：MiniMax api.minimaxi.com、GLM open.bigmodel.cn、OpenRouter openrouter.ai、硅基流动 api.siliconflow.cn。MiniMax 也允许显式选择当前官方 api.minimax.cn 和国际 api.minimax.io；不自动迁移旧地址或跨区重试。

MiniMax 协议按实际查阅的[同步合成文档](https://platform.minimax.cn/docs/api-reference/speech-t2a-http)和[国际站文档](https://platform.minimax.io/docs/api-reference/speech-t2a-http)核对；硅基流动按[Audio Speech API](https://docs.siliconflow.cn/docs/api/audio-speech-post)核对；OpenRouter 按其[官方 TTS 教程](https://openrouter.ai/blog/tutorials/text-to-speech/)核对。GLM 官方页面本次无法获取，按桌面 backend/app/services/tts_platform.py 的实际请求实现兼容，不声称当前官方字段/账户已验证。未执行文档中的示例或真实合成。

文本非空，至多 10000 Unicode code points/40000 UTF-8 字节；MiniMax 要求少于 10000，GLM 至多 1024，超限明确拒绝、不截断。拒绝非法 UTF-16 和除换行/回车/tab 外的控制字符。model/voice 非空、至多 512 UTF-16 字符且无控制字符。speed 为 MiniMax/GLM 0.5..2、另两条适配器 0.25..4；上游具体模型未必支持全部控制。

format 缺省 GLM wav，其他 mp3，始终显式传递。适配器接受 MiniMax mp3/wav/flac/pcm，GLM wav，硅基流动 mp3/opus/wav/pcm，OpenRouter mp3/opus/wav/pcm/flac/aac；这不是全部模型能力保证。volume 0.01..10，只向 MiniMax/GLM 转发；pitch -12..12、emotion 仅 MiniMax，其余非默认控制明确拒绝。gain 只支持硅基流动 -10..10 dB。GLM/OpenRouter 显式 sampleRate 未支持；硅基流动按 format 校验指定采样率。MiniMax 当前请求默认 sample_rate=32000，其完整格式/采样率/模型能力矩阵仍待补。

### 预算、响应与取消

会话 overrides/来源 settings 先 SQL 查 UTF-8 字节预算，各至多 256 KiB；ttsJson/预设配置至多 64 KiB，Room 事务内解析严格 JSON。短 data gate/事务固定配置和凭据，结束后才准备账本并联网；请求期间不持 Room 事务，但事件 shared lease 防原生快照/恢复穿过未结束账目。普通配置更改不切换已准备请求的来源，也不能撤回已经发给上游的请求。

并发合成至多 2；connect/read/write/call 超时分别 15/180/30/240 秒，超时从实际调用计算，等待 slot 可取消。协程取消主动 call.cancel，网络读和 hex 解码分段检查取消；响应关闭，不重试。尚未证明真机取消、同步大 JSON 解析耗时或上游停止收费。

二进制音频至多 16 MiB；MiniMax JSON 至多 34078720 字节，解码后也至多 16 MiB。未知长度也逐段限量，超限失败，不把截断音频当成功。MiniMax 严格 UTF-8/重复键/深度/状态/偶数 hex；其他拒绝 JSON/非音频 content-type、可识别 MIME 与请求格式不符、空体与明显不符的文件头。原始 PCM 无 magic，额外拒绝完整合法 JSON 对象/数组，仍标 raw_pcm_layout_unverified。文件头只作格式合理性检查，不是完整解码/可播放证明，不使用哈希。

采样率仅取合法 WAV fmt 或 MiniMax 报告；两者矛盾拒绝，未知保持 null/sample_rate_unreported，不以请求或桌面硬编码频率冒充响应。Ogg 头不证明一定是 Opus，MP3/AAC/FLAC/WAV 头不证明完整容器/解码通过。完整 codec/PCM 布局验证仍待接真实播放/设备。

StError.Speech 的安全 code 区分 disabled/not_configured/auth/quota/redirect/http/provider/timeout/network/invalid_response/format_mismatch/response_too_large；不返回 raw 响应、服务端错误正文、密钥或带 URL 的传输异常。配置/来源错用 Validation/Conflict/NotFound/Unsupported，不伪装上游成功。取消传播 CancellationException。

### 仍须补齐

其余音色/模型目录和账户能力、上传/克隆/设计/删除、云端完整参数/分段/流式、预处理完整情绪参数/所有模型标签/发送与崩溃账目、自动朗读；实时限额巡检和缓存/全部备份（含覆盖对保留引用的影响）/分叉/来源行为及异常运行审计；TTS 来源价表/全部 token/时长/订阅/克隆等计费维度和真实账单（字符不等于 token，未知费用不等于免费）；RikkaHub 原生 TTS 映射、来源全部版本和真实包；完整音频解码/播放、后台/通知/取消/恢复/持久性和设备验证。桌面 Windows 本地 GLM/Qwen3/OmniVoice 网关不能直接当 Android 运行器，本批明确不启动或下载它们。整个后端仍在实施中。

<a id="speech-source-adapters"></a>

## 来源云端语音适配

2026-10-10：明确 Gemini 3.8 两种TTS的播放回调已接原 Generate Content 流式接口，instructions 可映射风格；Luna 编写小型块解析器，原鉴权/来源模型/完整合成/缓存不换。首轮审批额度限制未执行，下一轮正常放行后普通离线编译通过1分11秒；真实设备仍未验证，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

后续更新：Qwen、xAI、Fish Audio、Step、MiMo、Volcengine 的请求与来源播放接线也已实现，固定 RikkaHub 的12种提供方（含 System）均有对应路由。二进制及Qwen/MiMo/火山SSE增量播放、长稿限额分段随后接入；2026-10-10原 MiniMax MP3 播放回调也接SSE，普通合成/其它格式仍保持原行为，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)；不代表全部格式/模型变体、无缝或真实设备验收。

2026-10-08：新增 OpenAI、Groq、Gemini、ElevenLabs 的实际请求适配，接现有直接合成、后台合成、缓存与合成后播放。RikkaHub 的所选提供方可从普通角色、群成员及独立助手的 text_to_speech 调用；不是只保留导入字段。原 System/MiniMax 与桌面四家链路不替换。

| 提供方 | 请求与音频 | 来源参数 |
| --- | --- | --- |
| OpenAI | /audio/speech，Bearer；原始二进制 | model、voice，默认 MP3；SpeechRequest 可显式 instructions，tts-1/tts-1-hd 不支持该字段 |
| Groq | /audio/speech，Bearer；WAV | model、voice；Orpheus 单次最多200字符，不截断或自动拆分/补发 |
| Gemini | /models/{model}:generateContent，x-goog-api-key；JSON inlineData；明确3.8播放回调使用 :streamGenerateContent?alt=sse | model、voiceName；预览保留 prebuiltVoiceConfig，3.8 使用 voice 与可选 speech_metadata.style；实时24kHz PCM16LE mono，完整结果包成 WAV，已有 WAV 不重复包头 |
| ElevenLabs | /v1/text-to-speech/{voiceId}，xi-api-key；mp3_44100_128 | model、voiceId、stability、similarityBoost；不把稳定度当成语速 |
| Qwen | /services/audio/tts/SpeechSynthesizer，Bearer + X-DashScope-SSE；output.audio.data | workspace baseUrl、model/voice、format/sampleRate，默认 WAV/24000；业务空间占位符须由用户填写 |
| xAI | /tts，Bearer；MP3 | voiceId、language；该接口无 model 参数，xai-tts 是内部端点描述，不冒充指定云端模型 |
| Fish Audio | /v1/tts，Bearer + model 请求头；二进制 | referenceId（可为空）、temperature/topP、speed、format、chunkLength/normalize/latency |
| Step | /v1/audio/speech，Bearer；二进制 | model/voice、responseFormat→response_format、sampleRate→sample_rate、speed/volume/instruction；单次文本1000字符 |
| MiMo | /chat/completions，api-key；delta.audio.data SSE | model/voice；assistant 正文、可选 user 风格指令；收集24kHz mono PCM16LE后封装 WAV |
| Volcengine | /api/v3/tts/unidirectional/sse，X-Api-Key/Resource-Id/Request-Id | model 字段表示真实 resourceId、speaker、speechRate（-50..100）；data音频与20000000结束码 |

模型与音色沿本来源的预设/原字段读取，省略时使用固定 RikkaHub 来源的默认值，不自动迁移到最新模型。新四家允许明确配置的代理 baseUrl，不套旧四家的官方域名名单；OpenAI/Groq 已含 /audio/speech 时不重复追加。Gemini/ElevenLabs baseUrl 为服务根地址，具体模型/音色在请求路径中加入。Android 现有明文 HTTP 策略仍只允许 loopback。

新六家也允许明确代理地址，并避免重复追加已完整的路径；Step/Fish 的 /v1 根可直接使用。Step 官方现行 REST 文档与固定来源实现的 camelCase 请求不同，实际发送按官方 snake_case，导入字段名称仍保留。不会在请求失败后试另一种 schema 或自动重发。

SpeechRequest 增 providerOptions，配置合并顺序 sourceTTS→预设 providerOptions→会话 providerOptions→请求 providerOptions；只取对应提供方的已实现字段，不把整个对象任意覆盖 HTTP body、鉴权或任务控制。sourceTTS 默认/原始字段保留；source referenceId/speaker 归入 voiceId，火山 resourceId 归入 model，来源其余格式与 instruction 转换进原预设。Fish 未指定参考音色时 voiceId 为空表示未选择，不捏造音色 ID；是否可接受由实际上游决定。

Qwen、MiMo、Volcengine 共用有界 SSE 音频收集与原 HTTP 取消链，支持多行 data、最后事件无换行/空行，错误不吞掉。Qwen 以 finish_reason=stop、火山以20000000完成；MiMo 处理 finish_reason/[DONE]，也接受其来源约定的正常 HTTP EOF。不自动重连/重发，不下载返回的任意音频 URL。Qwen 的可得 usage.characters 保留在原账目，其他缺用量/费用仍未知。云端播放入口增量交付音频，完整结果仍沿原链提交缓存；MiMo实时PCM/最终WAV的区别见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

来源 Qwen/Step/Fish 的 PCM 播放使用该适配明确的16位小端 mono布局与配置采样率，Fish 默认44100（Opus48000）、Qwen/Step默认24000。直接 Core 播放原始 PCM 仍需显式 PcmPlaybackFormat；明确 ttsJson 也可提供 pcmFormat。公开其余格式由设备真实解码决定，不换格式假报成功。

导入将 voice/voiceName 归到 voiceId，并保留脱敏 sourceTTS 与 ElevenLabs 参数；身份、三方合并、本地修改/删除保护、Desktop/RikkaHub 并存不变。旧已导入预设可从保留的 sourceTTS 读取字段；根本没有 TTS 预设映射的旧资料须明确再导入。凭据只从原加密接口 setProviderCredential 读取，以原生 TTS 预设 ID 关联，不导入明文 API key。

SpeechRequest 增 instructions、stability、similarityBoost；SpeechResolver、使用记录、启动时中断、预处理与任务/native 元数据的提供方集合同步。缺价格或上游用量仍保持未知，不新增自动费用查询。新四家的远端音色/模型目录本批未接，明确 fetch 时返回 unsupported，不误发旧供应商目录请求。

### 播放速度

startSystemSpeech、startSpeechPlayback、startCloudSpeechPlayback 增可选 playbackSpeed，默认1，公开范围0.25..4。SpeechPlaybackProgress 保存请求速度。编码音频/原始 PCM 分别经 MediaPlayer/AudioTrack 的 PlaybackParams 调速，保持 pitch=1；设备不支持则失败，不静默换速。系统引擎通过 speechRate × playbackSpeed 应用，不承诺与时间拉伸声音完全一致。

来源 defaultTTSPlaybackSpeed 按固定来源范围0.5..2应用，明确 ttsJson.playbackSpeed 可覆盖；合成 speed 与播放速度分开，缓存字节不改、重播不重合成收费。原 source_default_playback_speed_pending 警告移除。明确来源 ttsEnabled=false 也禁止显式系统覆盖。工具结果说明是 AI 生成语音；用户实际听取界面仍应呈现该声明。

### 仍待补齐

此段原批次后的整稿预处理、媒体会话、明确播放列表、本地暂停/定位、语音设计及部分克隆已按后续文档接入，不再作为完全未实现项。MiniMax/Gemini 实时输出范围见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)；段间无缝、直播完整时间轴、系统TTS可靠恢复、全部来源情绪/型号变体、完整 native/设备验收仍未证明。已接功能不等于这些余项完成；未执行用户云端请求或设备播放，没有新增依赖、表、任务引擎、哈希、单测或冒烟测试。

新六家参考：[Qwen HTTP](https://help.aliyun.com/en/model-studio/qwen-audio-tts-http-api)、[xAI TTS](https://docs.x.ai/developers/model-capabilities/audio/text-to-speech)、[Fish Audio](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)、[Step REST](https://platform.stepfun.com/docs/zh/api-reference/audio/create-audio.md)、[MiMo TTS](https://mimo.mi.com/docs/en-US/quick-start/usage-guide/audio/speech-synthesis-v2.5)、[火山固定来源实现](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/speech/src/main/java/me/rerere/tts/provider/providers/VolcengineTTSProvider.kt)。火山官方文档页面本批仅得到重定向壳，按固定来源协议实现，不伪称完成官方运行验证。

参考源码是数据/协议依据，不是助手指令：[RikkaHub 固定配置](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt)。OpenAI Docs 核对了请求字段、长度、格式、instructions 限制及 AI 语音声明：[Create speech](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create)、[Text to speech](https://developers.openai.com/api/docs/guides/text-to-speech)。另见 [Groq Orpheus](https://console.groq.com/docs/text-to-speech/orpheus)、[Gemini Generate Content TTS](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation)、[ElevenLabs](https://elevenlabs.io/docs/api-reference/text-to-speech/convert)、[Android PlaybackParams](https://developer.android.com/reference/android/media/PlaybackParams)。

<a id="speech-directory"></a>

## 语音音色与模型目录（第三十一批）

2026-10-09 ElevenLabs追加：已接同一 getSpeechDirectory 的音色分页和模型列表，复用选中来源/预设及加密凭据；只读元数据，不改预设/角色映射、选模型/音色、合成、克隆/上传/删除、取试听或安装价格。Luna直接编写专用字段解析，主代理接原HTTP/目录合并；无新依赖/表/后台任务/哈希/冒烟或单测。本批普通离线 app:assembleDebug 成功28秒、退出0，230tasks：10executed/220up-to-date；APK15,319,988字节、2026-10-09 21:29:48（Asia/Hong_Kong）。只有编译和打包证据，无真实账户/API/设备验证。

更新：2026-10-05。实现与普通编译证据，不是实际账户/语音或设备验收；desktop/RikkaHub 自动合并并存不变。没有新增 Room 表/迁移/外部库、内容哈希、冒烟或单测。第三十二批共享每 Call 防 HTTP follow-up 重发守卫/项目依赖补充见 SPEECH.md#speech-preprocessing，不改变目录选择或自动保存政策。

### 入口与来源

`getSpeechDirectory(SpeechDirectoryRequest(presetId?, chatId?, assistantId?, fetchVoices = false, fetchModels = false))` 默认只读本地。两个 fetch 开关分别授权一次目录读取，明确分页的提供方按返回token续页；不启动合成、上传/克隆/删除音色、试听下载、任务、播放或修改配置/缓存/角色映射。返回版本 1 的 SpeechDirectory，包括 selection、voices、models、voiceFetch/modelFetch 与安全 warning codes。

选择与合成共享 SpeechResolver.selectBase：明确 presetId 或会话 ttsJson.presetId，无第一条兜底；会话/预设与助手设置同 sourceNamespace。导入来源优先 source_settings 的精确映射，缺映射只有唯一来源助手时才可用，否则必须明确 assistantId；映射失效不借 global/另一来源。presetKind 与官方 HTTPS origin、encrypted credentialId 选择规则不变，配置中的非空明文 apiKey/api_key/token 明确拒绝。

目录不要求朗读稿、model、voiceId、预处理或缓存参数，也不要求来源启用 TTS；selection.enabled 只是冻结的开关状态。它是管理读取，并不绕过合成 ttsEnabled 检查。selection.baseUrl 仍为规范化的完整合成 endpoint，只用其同源固定目录路径；hasCredential 是准备时存在性，不是账户有效/余额/模型授权证明。来源身份/配置/settings 的原读取预算保持，返回身份不得含选中密钥。短 IO/Room/data gate 固定配置和加密凭据，结束事务后才联网；网络不持 Room 或整个 data gate，返回的是准备时快照，之后配置修改不追改这次结果。

### 当前覆盖

| 提供方 | fetchVoices | fetchModels |
| --- | --- | --- |
| MiniMax | 同选中 region/host 的 POST /v1/get_voice，voice_type=all；system_voice/voice_cloning/voice_generation 分类，有限兼容 data 包装及旧 voice_list | unsupported；只返回预设配置模型，不猜官方默认/模型列表地址 |
| GLM | GET /api/paas/v4/voice/list，voice_list 与 voice/voice_name/voice_type，有限兼容 data 包装；桌面代码契约，明确待官方/实际账户实证 | unsupported；不猜模型端点 |
| OpenRouter | unsupported；配置 voiceCatalog 可读，不将聊天音频声线或未知 supported_voices 结构当作完整 TTS 音色接口 | GET /api/v1/models?output_modalities=speech，仅 architecture.output_modalities 明示 speech 才标 provider_reported_speech，否则 unverified |
| SiliconFlow | GET /v1/audio/voice/list，results 中 uri/customName/model；只标用户 private 音色，不回传参考 text/下载 URL | GET /v1/models?type=audio，标 audio_unclassified；可含识别等非 TTS，不能保证可合成 |
| ElevenLabs | GET /v2/voices，page_size=100、include_total_count=false，按 has_more/next_page_token 续页；voice_id/name/category 保留，未报分类为unclassified | GET /v1/models直接根数组，model_id/name保留；can_do_text_to_speech明确true为provider_reported_speech、false为not_tts、未报为unverified |

ElevenLabs目录与原合成一样用xi-api-key，不发送Bearer；只取所选合成endpoint的同origin固定目录路径，明确代理仍按原配置，不探测或换host。音色至多100页/总10000条、每页沿原8MiB输入；官方说明首页可能包含超过page_size的默认音色，不据此拒绝。仅has_more=true且有合法next_page_token才续页，重复游标/超量/后续页失败均使voiceFetch失败，不发布不完整远端目录；不按total_count猜页数，也不持久游标或重启续读。模型列表保留非TTS/未知能力项而不猜成可合成。字段与分页依据：[官方List voices](https://elevenlabs.io/docs/api-reference/voices/search)、[List models](https://elevenlabs.io/docs/api-reference/models/list)。目录不是服务器原子快照或账户/模型/音色实际合成许可证明；不回传samples、preview_url、克隆描述或价格。

MiniMax 返回的未激活克隆/生成音色可能不在目录，缺失不代表应删除已配置条目；当前类别缺报也给独立 warning。接口分类依据[官方 Get Voice 文档](https://platform.minimax.io/docs/api-reference/voice-management-get)。历史 api.minimaxi.com 默认与显式 api.minimax.cn/io 均沿用已有合成 host 选择，不跨区域探测或借 key。

SiliconFlow 远端只覆盖用户定义音色，模型列表音频分类不能证明 TTS 能力，分别依据[官方音色列表](https://api-docs.siliconflow.cn/docs/api/audio-voice-list-get)及[模型列表](https://api-docs.siliconflow.cn/docs/api/models-get)。仅预设已明确配置 `FunAudioLLM/CosyVoice2-0.5B` 时，补桌面相同的八条 alex/anna/bella/benjamin/charles/claire/david/diana 静态目录；origin=desktop_builtin、model 固定、始终警告未实时验证，不补其他模型、不凭该目录自动选模型/音色。

OpenRouter 模型过滤采用[官方模型目录参数](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)；不把音频输出聊天模型和 speech 混为一谈，不读取其价表自动装入成本规则，也不假设模型列表证明 key/地区/路由可用。supported_voices 当前不解析，仍需明确字段与完整音色契约。GLM 目录来自只读桌面 `backend/app/services/tts_platform.py`，本轮检索官方文档及索引未找到该 API；结果持续携带 `glm_voice_list_desktop_contract_unverified`，不声称官方文档已确认或账户已跑通。

配置目录读取 model/models 字符串以及 voiceCatalog 的 voiceId/name/voiceType/可选 model；origin=configured，能力 unverified。不回传原始 config、未知额外字段、promptText/promptAudioPath/instruction，也不从角色/Persona 映射猜所有可选声线。未知导入字段仍留在原数据，本接口不重写它们。桌面 cloud OpenRouter 原实现未查询音色，其 voiceCatalog HTTP-free 代码属于本地网关，不据此声称桌面 cloud 有官方音色接口。

### 结果、失败与预算

所有条目有 origin，配置/桌面静态/远端可并存，云端不覆盖配置或自动保存目录。这是一次结果组合，不是新导入算法。相同 origin/voiceId/model/voiceType 的完全相同条目可折叠，冲突名称报错误；同模型远端重复项有矛盾也拒绝，不先到先得。名称/ID 不截断、不删改用户标识，空白、控制、非法 Unicode、预算超限或含选中密钥明确拒绝。

voiceFetch/modelFetch.state 为 not_requested / completed / unsupported / failed。只有 completed 带 fetchedAt；远端真实返回空数组是成功空目录，缺数组/错误结构不是成功空目录。支持端点但缺 key 为 failed/tts_not_configured，坏 key 为 tts_invalid_credential；未支持优先 unsupported，不为确认失败发请求。HTTP/Auth/Quota/Redirect/Timeout/Network/JSON/超量各返回安全 errorCode，不输出上游正文、message、密钥或 URL 参数。单一远端查询失败保留本地及另一个成功目录，但失败状态保持；取消传播整个调用，不被包装成局部成功。配置/来源错误提前失败，不返回貌似正常的部分本地数据。

每个配置数组最多 1000 项、预设 JSON 64 KiB、来源 settings/会话覆盖 256 KiB；每个云端响应最多 8 MiB（分段读时检查），每类远端最多 10000 项，重复键/过深 JSON/错误 UTF-8 明确失败。ID/model/type 至多 512 个 UTF-16 单元且 2048 UTF-8 字节，名称 4096 UTF-8 字节；不保存试听 URL 或参考文本。远端两个开关按顺序调用，目录服务最多两个同时 HTTP，15s 连接/写、30s 读、60s单HTTP调用超时（不是整次分页时限）；可取消队列与主动 call.cancel，无重定向、自动重试、失败换厂商、分页猜测或下载。ElevenLabs游标只在本次调用内，至多8192 UTF-8字节且无控制字符，不返回给普通目录结果。

目录不生成 `_kind=tts` 合成用量事件，不把一次读取伪造成合成或已知零费用；并未验证厂商目录收费政策。直接调用由调用者管理生命周期，非前台任务；进程死亡不继续旧请求。无真实账户请求/音色或包导入恢复/缓存清理绑定/设备运行；不把编译称为 API 实证。

### 继续范围

OpenRouter 完整音色及 supported_voices、MiniMax/GLM 模型目录、全部区域/版本/分页/权限与配置写入/刷新 CAS、真实上游、克隆/上传/删除/预处理/全部控制参数/价格/RikkaHub 原生格式仍待补。本地大型语音模型/网关不在云端目录适配范围；原设置保存不代表已能运行。其余缓存/任务/恢复关联、全异常/IO/断电/设备与完整后端目标见 STATUS.md，不能按本批编译把目标标完成。

<a id="speech-preprocessing"></a>

## 语音预处理与显式合成流水线（第三十二至三十五批）

2026-10-09 长稿播放更新：仅 `startCloudSpeechPlayback` 用整稿预处理一次后分段。LLM 事件归原父 SPEECH_PLAYBACK，父进度记录 requestId；合成子任务通过新增默认null的 SpeechSelection.preprocessingTaskId 指向此归属，并共享原 preprocessingRequestId，不新增事件或重复预处理费用。长稿处理稿仍10000码点/40000字节，跳过GLM/MiniMax的单次语音长度拒绝后由原分段器拆成合规请求；所有旧独立/单次接口、账目/取消及输出约束保持。原生证明扩展该精确归属，tts_preprocess用途允许播放任务，其它用途不变；无新表/新任务种类或重放。此前审批额度问题已解除，本次普通离线编译/打包成功77秒；实际质量/设备/完整native仍无证据。见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

2026-10-07 OAuth 主线更新：明确预处理 LLM 预设可用 Copilot/Codex 登录记录；选中原来源的预设/模型，不借另一来源账号。准备/预览读取缓存 token 不刷新，实际 process 前解析当前凭据并按需刷新，再进入原 tools-disabled/用量/文本处理链路；下游语音 API-key 独立。兼容请求参数与未实测边界见 [PROVIDERS.md#codex-oauth](PROVIDERS.md#codex-oauth) 和 [PROVIDERS.md#provider-oauth](PROVIDERS.md#provider-oauth)。完整 native/上游仍未验收。

更新：2026-10-05。独立整理/可选翻译与会话开关驱动的预处理→合成已接，只有普通编译和代码证据，不是实际转换质量、收费或设备验收。desktop/RikkaHub 自动合并并存不变，不做哈希校验、冒烟或单测集。

### API 与选择

`previewSpeechPreprocessing(SpeechPreprocessRequest)` 只读/验证配置与准备条件，不执行 HTTP/用量记录；缺启用开关、必要预设/model/凭据等明确失败，并非不完整配置的宽松状态查询。`preprocessSpeech(request)` 显式执行一次新的 LLM 请求，返回版本 1 的 SpeechPreprocessResult：requestId、processedText、冻结 selection、可得 UsageTokens、CostSnapshot、warnings。不合成/播放/存音频、改消息、绑定/清缓存或启动后台任务；不会自动再次执行失败或中断的旧调用。

| 请求参数 | 选择规则 |
| --- | --- |
| text | 明确源稿，保持原始字符串送入 JSON，不读/改消息正文，不对其取内容哈希 |
| presetId/chatId/assistantId | 复用 SpeechResolver.selectBase 的 TTS 来源/预设/助手设置选择，须来源 ttsEnabled=true；不选第一来源或 global 兜底 |
| llmPresetId | 请求 > 会话 preprocessPresetId，必须同 TTS sourceNamespace，存在且不是 TTS 预设 |
| llmModel | 请求 > 会话 preprocessModel > LLM 预设明确 model/defaultModel/selectedModel.modelId > 唯一 models 项（string 或 modelId）；多项不挑第一条 |
| speechModel | 请求 > 会话 model > TTS 预设明确 model/defaultModel/selectedModel.modelId > 唯一 models 项；明确下游模型用于限额/标签条件，不自动合成 |
| targetLanguage | 请求 > 会话 preprocessTargetLanguage；明确空字符串关闭语言提示，空/不具体语言不要求翻译 |
| injectEmotionTags | 请求可覆盖会话布尔开关；null 继承，缺失 false |
| maxOutputTokens | 默认 16384，允许 256..32768，明确发往协议对应输出限额字段，溢出/未完整结束不截断成成功 |

独立预处理不需要会话 preprocessEnabled=true：调用本身是授权；来源总开关仍受约束。来源 LLM 凭据取 credentialId > sourceProviderId > 该 LLM 预设 ID，只从加密存储读取，不借 TTS key/另一来源连接或全局默认。LLM 预设 JSON 至多 64 KiB，来源 settings/会话 overrides 仍原 256 KiB/ttsJson 64 KiB 预算。secret 不进入 request/result/账本，选中凭据出现在输入或返回 metadata/文本时拒绝；toString 不输出稿或配置，JSON 结果仍包含用户朗读稿，调用方不得当日志写出。

LLM 复用 ProviderResolver 的协议/authStyle/baseUrl 验证，但走新 explicit 工厂，不扫描所有来源助手或混入第一条 generationDefaults。当前四协议与固定鉴权、温度 0.2/输出限额/明确 reportedCostCurrency；不透传任意 headers、tools 或 raw secrets，不执行 auto/OAuth/云模板/完整参数。配置明文 apiKey/api_key/token 非空拒绝；非空 headers/customHeaders 明确 unsupported。只有明确 allowUnauthenticated=true 才可无 key。LLM baseUrl 沿用既有明确 HTTP(S) 配置，无 URL credentials/query/fragment；与云端 TTS 固定官方 origin 不同，HTTP 地址的传输保密性并未由加密凭据存储保证，调用者须自行选择可信 HTTPS/本地服务。

### 处理与输出

系统规则让模型将源稿作为数据，清理 Markdown/布局/转义噪声/不适合朗读的控制字符、保留意义和语气；仅目标提示指向具体语言时翻译。源稿/语言/标签开关由 JSON 编码送入单条 user 内容，不拼入系统指令。禁用工具与原生搜索；任何意外工具调用被拒绝，不执行工具或接续推理。模型语义/语言/情绪正确性不能由 JSON 检查证明，结果明确 warning。

情绪注入是文本插入 interjection，不等同 SpeechRequest.emotion 的 voice_setting 属性。当前仅 MiniMax speech-2.8-hd/turbo 允许开启，提示词限定[官方同步合成文档的插入标签及模型范围](https://platform.minimax.io/docs/api-reference/speech-t2a-http)。其他模型/提供方开启则在网络前 Unsupported，不猜兼容列表或删掉用户要求；已有源文本中的括号并不自动视为标签或删除，模型也可能不完全遵守指令，完整标签/意义与音频实证仍待补。

消费现有四协议 stream，但只拼正常正文，不泄露/保存 reasoning 或 providerTurn。须完整 Done、ok 且无 continuation；截断、工具、断连/错误不是有效稿。返回纯 JSON 或完整单个 json/JSON Markdown 围栏，严格 UTF-8 字符串/JSON、无重复键，顶层恰好 processed_text 或兼容 processedText 一个 string 字段；不从前后说明中猜第一个 JSON、不接受两个字段/额外字段/数字/null/空结果，不静默回退原文。最终稿只做边缘 trim，不截断，其他整理/翻译由模型完成。

输入有效 Unicode、至多 40 KiB UTF-8/10000 code points，可包含待整理控制字符，非空；正文累计至多 131072 UTF-16 单元、解析 JSON 至多 128 KiB UTF-8；客户端还有 SSE line/event/整流预算。结果须合法朗读稿、至多 40 KiB/10000 code points，无不允许控制；GLM 1024，MiniMax 少于 10000 的当前合成限额也检查。ID 1024 UTF-8 字节，model 512 UTF-16 单元/2048 UTF-8 字节，语言提示 256 UTF-8 字节，不删改/哈希身份。

服务最多两次并发，排队可取消；处理阶段 240s 超时，现有网络取消 watcher 主动 call.cancel。用户取消传播，内部超时返回安全 tts_preprocess_timeout；配置/来源错误提前失败，网络/provider/输出/工具/账目失败不带 raw 上游正文/异常 URL/key。不存在失败换预设或再次尝试。独立调用由调用者负责生命周期，不创建前台宿主；第三十三批后台串联复用已有语音任务宿主，进程被杀后同样不会继续。

### 会话自动串联（第三十三批）

`previewSpeech` / `synthesizeSpeech` / `startSpeechTask` 现在读取会话 `preprocessEnabled`。true 时同一配置事务固定 SpeechSelection.preprocessing（TTS/LLM 两套预设、模型、来源、协议、凭据身份与语言/标签/输出限额），不运行时换配置、借另一来源或重新解析默认值。preview 不联网；实际调用先校验合成端点/凭据/非文本控制，再执行一次独立 LLM 处理，完整结果和其账目保存成功后才按 processedText 准备一次合成。任何阶段失败/取消/输出无效不回退原文、不重复处理、不切换提供方；取消边界也不承诺厂商停止收费。

仅 `preprocessEnabled=true` 授权自动 LLM 阶段；`injectEmotionTags=true` 而预处理 false 明确配置错误，不让标签开关单独触发付费调用。桌面 ChatPage 同样只在 preprocessEnabled 时调用 LLM，但会忽略关闭状态下残留的标签开关；Android 此处保持显式错误，不能宣称全部设置行为完全相同。自动路径沿用上述明确同来源 LLM 选择，缺必要配置拒绝；未实现自动朗读、UI 播放或所有模型标签。

原稿先按预处理 40 KiB/10000 code points 限制，合成文本限制检查处理后的稿，因而原稿超过 GLM 1024 并非必然提前失败；结果超限仍在合成前拒绝，不截断。直接结果 SpeechAudio 增 spokenText/preprocessingRequestId，requestId 是另一笔合成事件 UUID；不开预处理时 spokenText 是显式原稿、预处理 ID 为 null。直接调用不落文件/改消息/建任务，整个串联持 shared gate（非 Room 网络事务），原生快照/覆盖不能插在两个阶段之间。

后台任务增加 preprocessing 阶段，LLM 成功后先保存 preprocessingRequestId，再检查仍未停止及目标消息。合成前只读复查冻结来源、原正文/生成终态、绑定稿与正文合并后的 256 KiB 行预算；普通不预处理任务也做这项检查。实际发布再复查并 CAS，处理期间允许编辑，不持长消息事务。SpeechAsset.spokenText 与绑定 sourceText 是真实处理稿，绑定 messageText 是显式原正文，不能拿译稿去判断原消息是否编辑；消息正文/其他 extras 不改。task checkpoint/初始 payload 验证共享元数据契约与 128 KiB 预算。磁盘、并发编辑和完整最终 asset payload 等仍可能在调用后失败，前置检查不是“所有失败都发生在收费前”的保证。

LLM 普通 token context 增可空 taskId/messageCandidateId，purpose=tts_preprocess；TTS SpeechCallContext、progress、asset 用同一个 preprocessingRequestId 关联这笔普通事件，合成 requestId 独立，不把两次调用计成一笔 token 或双重计算 TTS 字符。处理成功但后续停止/正文变更/合成失败，已完成的 LLM 记录保留；合成未发送不伪造合成费用。读取已有缓存返回实际稿/两个 ID，但不新建事件。

原生候选检查冻结预处理选择和 UUID，存在的预处理事件须匹配 provider/model/协议/用途/来源/作用域/task/chat/candidate/时间与合法冻结费用；缺历史事件仅 warning、不重建。REPLACE_INCLUDED 对包外有效缓存的原可验证预处理 usage/cost 增不可替换保护，并检查冻结 LLM 预设移源；普通配置/价格变更不重算历史。逐列沿用 usage 64 KiB/cost 32 KiB、保留扫描 64 MiB/100000 行预算，无哈希、HTTP、凭据读取或新表。详见 NATIVE_BACKUP_V1.md；这是历史元数据关系，不证明稿的语义、发送或实际发票。

### 用量与生命周期边界

一次执行使用新的 UUID requestId，写普通 token usage_events，不制造 `_kind=tts` 语音合成事件。UsageRecordContext 加兼容可空 purpose=tts_preprocess、scope 与 sourceNamespace；记录协议、模型、可得计数、首正文延迟/总耗时及终态，不存源稿、处理稿、reasoning、providerTurn、body 或 key。不可信/超量/含凭据的供应商用量 metadata 省略为未知并给 pricing warning，不把未知当零。

UsageStore.prepareCost 冻结本次开始的规则/时间，结束不读取新价格，调价不改已保存费用；规则非法保持未知原因。不下载实时价表/查询发票。供应商完整返回但本地 JSON/稿验证失败，账本仍 completed：它描述供应商调用，不把已经可能收费的调用抹掉；结果入口仍失败。其他失败/取消按捕获状态与可得报告记录，不证明厂商免收费用。安全错误含 requestId，不授权自动重发。

第三十二/三十三批曾沿用普通 LLM 最终记录模式；第三十四批改用下述持久生命周期，旧没有标记的完成记录保持历史，不补造发送证据。进程存活时，执行/记录仍持 shared data gate lease，HTTP 不持 Room；短 NonCancellable 条件更新，防原生快照/覆盖穿过处理。排队取消/准备失败不产生新调用记录，开始执行且 prepared 已提交后即使本地构造/取消失败也保留终态。最终保存失败给 tts_preprocess_accounting_failed（原失败/取消则保留，并附安全 suppressed 错误），已有 unfinished 行留待启动中断，不再请求模型。

### 持久请求生命周期（第三十四批）

普通 token envelope 增 `_requestLifecycle`（TokenRequestLifecycle v1：httpAttempted/attemptedAt/endedAt/errorCode），用途仍 purpose=tts_preprocess，不新建 `_kind=tts` 事件、表或计费维度。UsageStore.beginTokenRequest 在发送前插入一个 prepared 行，身份/usage/context 预算与协议/来源/时间先验证，costJson 为空而非零。开始冻结 UsageCostPlan 留在本进程；不把密钥、输入/输出、请求 body、可执行配置或重放句柄存进生命周期。

LlmClient.stream 默认空 beforeExecute 回调，四协议都在各自 URL/JSON/headers/Call 构造完成后、唯一 call.execute 前调用；先检查取消，回调短事务提交 running/httpAttempted=true/attemptedAt，再检查取消后才执行 HTTP。回调或数据库失败不执行请求；TokenUsageRequest 拒绝第二次发送回调，默认 HTTP 防 follow-up 守卫仍保护同一 Call。第三十五批普通生成/总结/状态的当前全部业务调用点也已接持久回调，账本独立于业务应用事务，范围见 ACCOUNTING.md#token-request-accounting；低层自定义 LlmClient 调用并不自动创建账本。

同一行的完整旧 usageJson、空 cost 与全部身份/provider/model/chat/createdAt 是更新前提；NonCancellable 提交与本地快照赋值一起保护，不出现提交后取消导致用旧前提重复写。成功供应商 Done 记 completed（即使后续稿解析失败），其余按失败/取消；结束时间不早于开始/发送标记，耗时独立用单调时钟。已越过本地发送边界的可得 token/报告金额按开始冻结规则定价；未越过则费用 unknown/request_not_sent，不假装零或实际账单。超量/含密钥/负计数/空或控制字符模型、越界费用字符串 metadata 省略为未知警告；其余金额/币种仍按 PricingEngine 合法性判断，不让无效供应商金额冒充已知付款。

httpAttempted **只证明本地提交了发送前边界**：取消可能紧接其后，连接可能失败，服务器接受/收费、最终响应以及进程/磁盘/断电耐久性仍无实证。最终提交失败仍返回错误，不自动再次执行；kill 时可能只有 prepared/running 行，可得完整费用未知，不从当前价格重建旧请求。

启动只选取规范序列化头的、costJson 为空且三身份相同的行；第三十五批支持普通既有生成/维护/分段 ID，预处理仍 UUID。SQL header 是减少无关历史扫描的索引，不是成功/真实性判据，随后完整严格 JSON/生命周期/预算验证。每页 200，身份总和 4 KiB、usage 64 KiB/cost 32 KiB，最多 100000 行/64 MiB，总预算超限失败。新生请求统一规范序列化；原生恢复解析逻辑键，不依赖头/键顺序/转义，稳定归一化 unfinished 为 interrupted/native_restore_interrupted/unknown。启动标 interrupted/process_interrupted；没有 HTTP/凭据读取/重试，未隔离 shell/公开原始写入不是恶意边界。

usageEventsPage 返回兼容可空 tokenRequestLifecycle，旧记录 null；生命周期不一致记 invalid_token_request_lifecycle，费用不纳入已知金额，prepared/running 原地更新不是分页不可变快照。原生候选验证完整新生命周期及历史 task/message/assistant 引用，可读缓存的预处理关联要求新的完成事件确有本地发送标记；原有无标记完成事件仍历史兼容，保留覆盖守卫比较完整 usage/cost（包含生命周期）。无当前价格重算，不修改 desktop/RikkaHub 合并并存。

### 共享 HTTP 防重发补充

核对[OkHttp 4.12.0 官方源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)发现 retryOnConnectionFailure=false 仅挡连接恢复/部分 408，503 的 Retry-After=0 和部分 421 follow-up 仍可能再次发送。本批为默认 LlmClient、SpeechService、SpeechDirectoryService、WebSearchService 增 NoHttpReplayInterceptor：每个 Call 的弱引用计数只准第一次 network exchange，库 follow-up 进入网络链时在发送请求前拒绝，不记第二账目或自动新请求。LLM 默认也关闭连接恢复重试，重定向仍关闭。core:tools 新增对 core:llm 的项目依赖以共用守卫，外部库版本不变。

这是现有发送路径的代码守卫，不是连接/DNS/代理握手的隔离，也不是实际上游实验；未测试厂商状态码/费用。自定义传入 LlmClient 的 HTTP client 不自动继承默认 builder，调用者必须保留相同策略；新独立显式调用创建新 Call，允许由用户主动发起，不继续旧请求。

### 继续范围

独立稿/会话串联与当前全部业务 LLM 调用的持久生命周期已接，不代表自动朗读/所有桌面能力或实际运行通过。服务器接收/完整账单/真实进程死亡与磁盘持久性仍待补，成功 LLM 与本地后续失败记录保留也不等于厂商账单完整。全提供商标签/提示模板/预处理参数/质量、来源版本/RikkaHub 格式、最终大行/并发/全部 native 关系和真实恢复、其他目录/克隆/费用与后端全清单继续，见 STATUS.md。

<a id="speech-streaming"></a>

## 长稿分段与流式播放

### 2026-10-10 Gemini 3.8 增量输出

有播放回调且明确选择 gemini-3.8-flash-tts / gemini-3.8-flash-lite-tts 时，从原冻结请求派生 :streamGenerateContent?alt=sse，并明确请求 AUDIO_L16/24000Hz；保留 Generate Content、原鉴权/baseUrl、会计、任务、缓存和取消链，不新增 Interactions 会话协议。Luna 实际编写小型音频块解析器，主代理接原 SSE 读取与播放回调。3.8 的 instructions 映射为 speech_metadata.style；固定来源默认2.5、其它旧模型和无回调合成保持原完整响应，不自动升级或失败后重发。

24kHz 单声道 PCM16LE 块交原 AudioTrack，完整 PCM 只在原合成成功后封装一份 WAV 供缓存/重播。STOP 才标完整，DONE/EOF不伪称完成；非STOP终止和晚到错误沿原失败链，缺用量/价格仍未知。不下载音频URI或给每块添加WAV头。协议依据官方 [Generate Content TTS](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation)；该兼容接口也支持3.8，无需切另一套协议。

首轮普通离线编译在启动前被自动审批服务额度限制拒绝，命令未执行；下一轮同一审批方式正常放行，app:assembleDebug 成功1分11秒、退出0，tools Kotlin实际重编，runtime JAR可见新解析器与PCM请求字段。源码/编译接线不等于真实账户、流式起声、取消或设备验收。没有哈希、冒烟、新单测、依赖、表、播放器/任务框架或前端。

### 2026-10-10 MiniMax MP3 增量输出

原云端播放在有音频回调且 MiniMax 格式为默认/明确 mp3 时，执行前从同一冻结请求派生 SSE 请求：stream=true、output_format=hex、exclude_aggregated_audio=true。没有回调的普通合成、其它格式与提供方仍走原路径，不自动换格式/模型或重发。Luna 修改原 SpeechService，主代理核对原预处理、分段合成子任务及容量8的播放通道均传递同一回调，不增加播放器、任务类型、表或依赖。

沿已有 SSE 行读取/取消/音频上限处理 MiniMax 响应；status=1 的 hex 音频逐块交付，status=2 才标完整，末尾聚合音频忽略避免重复。仅 [DONE] 或 EOF 不当作完成，无音频/缺完成状态按原失败链收尾。可得 usage_characters、audio_sample_rate 进入原最终结果；缺失仍未知，MP3块上的声明采样率不作为设备或提供方实际出声证明。完整拼接字节在原合成任务成功后提交缓存；晚到错误使本段/父播放失败，不重播已听部分，也不另发合成请求。

该批只补 MP3；后续Gemini3.8接线及编译状态见本页顶部，MiniMax其它格式与旧Gemini模型保持原完整响应。没有验证真实账户、服务或设备缓冲/起声。协议参数依据官方 [MiniMax HTTP TTS](https://platform.minimax.io/docs/api-reference/speech-t2a-http)，不使用 WebSocket 或另开音频URL请求。首轮普通离线编译发现一处多余闭合括号，修正后最终44秒通过；没有哈希、冒烟或新增单测，只有编译/静态证据。

2026-10-08：云端播放已接分段和增量音频输出，复用原合成任务、用量、缓存和前台宿主；来源普通聊天、群成员和独立助手共用此入口。Desktop/RikkaHub 导入合并并存不变。

2026-10-09：云端长稿改为整稿预处理一次，再按提供方限额拆最终稿；下文逐段预处理的历史实现已替换。此前构建被审批服务额度限制挡在执行前；本次按正常审批执行普通离线 app:assembleDebug 成功77秒、退出0。尚无真实云端或设备运行证据，详见 [VERIFICATION.md](VERIFICATION.md)。

### 长稿与进度

`startCloudSpeechPlayback` 在原10000 Unicode码点、40000 UTF-8字节预算内按已有单次校验拆段：OpenAI4096、GLM1024、Step1000、MiniMax9999、Groq Orpheus200，其余10000。这是当前适配范围，不保证全部远端模型接受该长度。优先在后半段句末标点/换行断开，不切断代理对，纯空白片段不产生任务。

开始前在同一配置读取事务固定整稿、来源、预设、语音凭据和预处理连接。原 SPEECH_PLAYBACK worker 在事务结束后才执行：有 preprocessEnabled 时整理/翻译整稿一次，完成原 LLM 账目后拆最终稿；未启用则直接拆原稿。之后逐段创建原合成子任务、输出和播放。全部播放且缓存提交成功才完成父任务。进度的 `segmentCount=0` 表示尚未得到最终分段，准备后为实际段数；零起始 `segmentIndex`、`synthesisTaskIds`、`assetIds` 沿原行为，资产列表只记已播放且提交成功的段。不同段不合并编码文件、不自动挂接消息；停止/失败不执行后续段、不重发，之前成功缓存和用量保留。

整稿仍限10000码点/40000 UTF-8字节，不因翻译扩大预算。处理稿可超过一个 TTS 请求的长度，按原 SpeechSegments/模型限额拆分，每段再由原 SpeechService.prepare 检查；不截断、不重复翻译或改正文。父进度保存唯一 preprocessingRequestId，所有合成段共享它，selection.preprocessingTaskId 指向父播放任务，原生账目证明据此读取同一事件；旧字段默认null仍指本合成任务。只有一笔整稿 LLM 账目，各段 TTS 字符/请求仍独立，不复制预处理费用。直接 `preprocessSpeech`/`synthesizeSpeech`/`startSpeechTask` 仍是原单次接口，没有隐式分段。

### 增量输出

- 二进制响应逐块交给播放器，已有 Content-Type 格式不符校验提前到输出前。
- Qwen/Volcengine SSE逐事件解码并输出；MiMo的24000Hz单声道PCM16LE直接写AudioTrack，原缓存结果仍封装WAV。
- 编码音频通过增量MediaDataSource接MediaPlayer；PCM通过AudioTrack边收边写，保留跨块的不完整采样帧。已知PCM布局可直接用，未知仍须显式 `PcmPlaybackFormat`。
- MiniMax默认/明确MP3的播放回调已接SSE增量；Gemini明确3.8两种TTS的PCM回调已接且通过普通编译。普通无回调合成、MiniMax其它格式和旧Gemini仍须收完，不声称全部型号/格式或真实设备已边生成边播。

每段容量8的channel满时等待，不丢音频/无限排队；仍受原单次音频预算约束，无第二次HTTP、外部URL下载、自动重连或换格式。播放器退出先关闭数据源唤醒读取再release；父任务收尾停止仍活跃的子任务并取消channel。晚到的传输/缓存错误使父任务失败，不重播已听到的部分，不记整稿完成。

### 边界

整稿处理后拆段源码已接并通过普通离线编译/打包，原暂停意图/本地定位、媒体会话和明确托管列表按后续实现沿用。段间预取/无缝、直播跨段定位/全稿时间轴、所有流式型号和完整native关系仍未完成。真实起声取决于设备解码器缓冲，phase=playing不证明用户听到声音。没有调用实际供应商、账号或设备，后台/取消/解码与起声仍需设备证据。无新依赖/表/任务引擎/哈希/单测/冒烟/应用前端。

接口依据：[Android MediaDataSource](https://developer.android.com/reference/android/media/MediaDataSource)、[Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)。请求范围见 [SPEECH.md#speech-source-adapters](SPEECH.md#speech-source-adapters)，宿主见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。

<a id="speech-playback"></a>

## 设备朗读与语音播放

2026-10-09 音色试听：新增 `startSpeechVoicePreviewPlayback(voiceTaskId, playbackSpeed=1)`，仅接受成功完成的声音设计/克隆任务。设计托管试听附件直接复用；克隆/设计返回的远端 URL 每次启动单独 GET，最多 20,000,000 字节，再沿原 `SPEECH_PLAYBACK` 设备输出。不重新发送设计、克隆或合成请求；不保证上游/CDN没有其他费用；不向 CDN 发送 provider 凭据，不接任意 URL/API，不自动启动、重启或原生恢复。进度 `mode=voice_preview`，含 `voiceCreationTaskId`、`downloadedBytes`、`attachmentId`；保存附件可用 `startChatAttachmentPlayback` 复播，远端每次 startPreview 都重新 GET且不改创建记录。暂停/停止沿原播放流程。URL过期、拒绝重定向、不支持 MIME 或设备解码失败会明确失败；真机未验证。GLM 可能无 URL且桌面契约未验证，SiliconFlow 不返回试听 URL，无可用试听时接口明确报错。

2026-10-09 长稿更新：`startCloudSpeechPlayback` 固定一次选择，有预处理时在原播放 worker 中整理/翻译整稿一次，再拆最终稿；不逐段重调 LLM。父进度新增可空 preprocessingRequestId，segmentCount=0 表示尚未确定最终分段，完成准备后为实际段数。合成段沿原任务/流式播放/缓存和字符账目，共享父预处理事件且不重复计费用；selection.preprocessingTaskId 默认null保留旧记录归属。暂停意图和停止链不变，单次合成/独立预处理接口不自动分段。此前审批额度问题已解除，本次正常审批的普通离线编译/打包成功77秒；无真实云端或设备证明，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

### 托管音频播放列表（2026-10-09）

新增startSpeechPlaylist(items, playbackSpeed=1)、skipSpeechPlaybackTo(taskId, itemIndex, positionMs=0)、nextSpeechPlaybackItem及previousSpeechPlaybackItem，复用原SPEECH_PLAYBACK、设备输出/焦点、后台宿主、观察/等待与stopTask。SpeechPlaylistItem(source=CACHED/ATTACHMENT, id, pcmFormat?)只能引用成功且仍在缓存的语音资产或已有托管音频附件，不接任意路径/URL、正文或新合成请求。一次1..100项，可重复引用，按所选顺序播放；速度范围与原接口相同。没有另开队列任务/播放器引擎，也不调用供应商或新增计费。

整组准备固定实际文件/格式与缓存引用，不把全部音频读取到内存；编码沿原MediaDataSource，PCM沿64KiB随机读取，原PCM布局要求不变。失败不登记部分列表任务，准备取消会释放已取得引用。缓存项在整个列表生命周期临时pin，原自动淘汰/显式clear跳过它们；clear结果增retainedAssets默认0，表示本次保留的缓存资产数。缓存全部被保留导致空间不足时，新合成明确失败，不删除排队音频；结束/停止/失败后释放，不新增表或持久pin。

progress增playlist和completedPlaylistIndices（旧记录默认空），mode=playlist，segmentIndex/Count表示当前项/总项，位置与时长仍是当前音频而非整列表累加值。assetId/attachmentId表示当前项，assetIds仅记录已完整播放过的缓存ID；混合来源列表允许明确选取不同托管ID，不借用模型配置或凭据，缓存项沿其原source/assistant，附件不推测助手归属。SUCCEEDED表示列表按用户切换后到末项完成，不声称被跳过的每项均完整播放；completedPlaylistIndices保留实际完整结束的索引。

next/previous按命令消费时当前项计算，越过首尾明确失败、不循环；skip按0起始索引跳指定项并在其毫秒位置开始，可明确重播本列表中的旧项。先取消并释放原项decoder，再准备下一项；接口等待下一项就绪并完成初始定位，而非仅入队即报完成，连续新切换可使尚未就绪的前次切换以superseded失败。暂停意图跨项保持，原seek仍只定位当前音频。取消未消费命令不切换；已开始的切换不是回滚事务，整体stop会结束当前输出/待命令并释放全部引用。文件缺失/解码/焦点失败终止列表，不跳过错误或重新合成。

列表仅在本进程运行，任务payload不含音频/正文/文件路径/播放器句柄；重启与native恢复沿原中断规则，不从旧列表重播。系统媒体会话/通知的上一项、下一项和队列索引控制接原taskId，仍用通用标题，不提供媒体浏览目录或应用页面。系统TTS/本次云端增量流不被冒充为可跳转列表；可以明确把已成功缓存的云端分段assetId组成列表。没有自动发现/新增编辑队列、全稿总时长定位、无缝转场、系统TTS可靠恢复或旧播放恢复。

普通离线编译，未播放用户音频、运行设备/耳机或调用云端。无新表/依赖/任务框架/哈希/冒烟/单测，完整native关系及真实设备仍待验证。媒体回调依据：[MediaSession.Callback](https://developer.android.com/reference/android/media/session/MediaSession.Callback)。

2026-10-08 媒体会话：CoreForegroundService新增原生MediaSession/MediaStyle通知，暂停、恢复、定位及停止沿原Core/播放任务，锁屏/耳机/系统控制不另建播放器。按AudioPlaybackState开放动作，停止提供标准动作及Android 13自定义槽位；任务结束、宿主退出释放会话，不提供旧音频重播或媒体浏览目录。只显示“语音播放”、段数与状态，不发布正文、人物名、源路径/URI或密钥。

2026-10-08 播放控制：新增pauseSpeechPlayback/resumeSpeechPlayback/seekSpeechPlayback，沿原播放任务控制真实MediaPlayer/AudioTrack，不重新合成或创建替代播放任务。SpeechPlaybackProgress新增playback（AudioPlaybackState），报告ready/paused/canPause/canSeek、实际位置与可得时长；原观察每500ms更新运行态，不每次写Room，终态保留最后位置而不恢复播放器。下文PCM两个读取块为旧实现，现改为单64 KiB随机读取块以支持定位。

2026-10-08 大音频：startChatAttachmentPlayback现在按托管ID解析文件，单文件最多256 MiB，不经getChatAttachmentContent/6 MiB内联读取，也不把完整音频转Base64/字节数组。编码音频用磁盘MediaDataSource供原MediaPlayer按需读取；PCM用64 KiB读取块、两个有界缓冲块和原AudioTrack写入器，采样率/声道有报告的部分均须与明确选择相符。文件引用仅存在本次运行输入，进度仍只存attachmentId/mode=attachment；准备、排队和播放共享原数据操作lease，停止/焦点/速度/通知/等待链不变，原生恢复不重播。

2026-10-08 后续：新增startChatAttachmentPlayback，生成/导入的托管音频沿原设备播放器/PCM、宿主、速度、停止、等待与观察；进度mode=attachment保存attachmentId。不是重新合成或自动播放，PCM布局及格式/映射余项见 [CHAT.md#generated-audio](CHAT.md#generated-audio)。

2026-10-08 后续更新：云端播放按当前提供方单次限额拆段，二进制及Qwen/MiMo/火山SSE音频接增量输出；不再一律等缓存提交才播放。进度增加段数/索引/全部子任务及成功资产。MiniMax/Gemini当前JSON仍收完，段间非无缝、预处理仍逐段，见 [SPEECH.md#speech-streaming](SPEECH.md#speech-streaming)。

后续更新：OpenAI/Groq/Gemini/ElevenLabs/Qwen/xAI/Fish Audio/Step/MiMo/Volcengine 的来源云端合成和默认播放速度已接，参数、格式与明确余项见 [SPEECH.md#speech-source-adapters](SPEECH.md#speech-source-adapters)。

2026-10-08：系统 TTS、已登记缓存音频、现有云端合成后播放已接实际 Android 输出与原 TaskStore/后台宿主；新增 SPEECH_PLAYBACK 任务种类而非另一套任务引擎。`text_to_speech(text)` 接普通来源角色、群聊实际成员和独立助手的 localTools `tts`，WRITE 分类沿原自动/自定义策略；生成立即收到独立播放任务 ID，不把排队或合成成功冒充播放成功。

### Core 接口

- `startSystemSpeech(SystemSpeechRequest)`：自然文本、speechRate/pitch、可选语言、固定 chatId/assistantId。使用设备所选默认系统 TTS 引擎，初始化后按平台长度拆分 UTF-16 文本，不切断代理对，逐段入队；最后一段 onDone 才完成。无系统引擎/语言失败，不下载安装语言或换成云端。
- `startSpeechPlayback(assetId, pcmFormat?)`：只读已有成功合成、已登记且仍在缓存的托管音频，捕获字节后播放；不接受 URL、任意路径或外部 URI。新任务不改原合成状态或重新收费，不自动挂接消息。
- `startChatAttachmentPlayback(attachmentId, pcmFormat?, playbackSpeed=1)`：明确播放生成/导入的托管音频文件，最多256 MiB，文件从原附件表解析而非用户路径/URL；既有PCM实际布局要求不变，不重新合成、转写或收费。getChatAttachmentContent仍为原内联读取，二者预算不混用。
- `startSpeechVoicePreviewPlayback(voiceTaskId, playbackSpeed=1)`：对成功完成的声音设计/克隆任务显式播放试听。设计托管附件直接复用；远端返回 URL 通过无凭据、HTTPS-only 下载，限制 20,000,000 字节及支持的音频 MIME，再进入原设备输出。进度为 `mode=voice_preview`，含创建任务 ID、下载字节和附件 ID；重复启动远端试听会重新 GET，已保存附件可改用 `startChatAttachmentPlayback`。不重做创建/合成，不自动启动/恢复，不接受任意 URL/API；GLM 缺 URL或 SiliconFlow 无 URL时返回明确不可用。真机解码未验证。
- `startCloudSpeechPlayback(SpeechRequest, pcmFormat?)`：调用时固定各段的原 SpeechResolver 选择/凭据/预处理快照，后台通过原 SpeechTaskService 顺序创建合成子任务，增量音频交给播放器，同时沿原链提交缓存；关联当前及全部子任务/成功资产，停止未完成播放时停止仍活跃的子任务，不重发、不换提供方。
- `getSpeechPlaybackProgress` / `awaitSpeechPlayback` / `observeSpeechPlayback`：返回 SpeechPlaybackProgress（mode/phase、assetId/synthesisTaskId、来源/助手、errorCode），不保存正文、音频字节或重放闭包。await 返回终态进度，不把失败转换为成功音频。
- `pauseSpeechPlayback(taskId)` / `resumeSpeechPlayback(taskId)` / `seekSpeechPlayback(taskId, positionMs)`：操作正在运行的原播放任务并返回当前进度。负位置拒绝，超过时长定位到末尾附近；未就绪/不支持/结束明确失败，不自动换源或重新播放。
- 原 `stopTask(taskId)`：停止指定播放并释放系统引擎/播放器/AudioTrack 与音频焦点。通知显示独立“语音播放”任务和原停止入口。生成已结束不自动停止独立播放；用户可单独停止播放任务。

同一时间只接受一个设备朗读任务，新任务不自动抢占或排队持有未知时长的朗读稿；明确托管音频列表在同一个任务内顺序播放，不是多个任务排队。普通聊天、沙箱与其他后台任务仍可并行，不因此引入沙箱/会话串行限制。用户切换聊天不会改变已登记的 chatId 或音频输入。

### 暂停与定位范围

| 实际输出 | 暂停/恢复 | 定位 |
| --- | --- | --- |
| 本地托管文件或缓存编码音频 | MediaPlayer原输出 | 等OnSeekComplete，报告实际位置 |
| 本地文件或缓存16位PCM | AudioTrack原输出 | 暂停/flush、帧对齐、重新读取 |
| 云端本次增量编码/PCM | 原输出，暂停意图跨段保持 | 不支持；完整合成后可播放缓存定位 |
| 系统TextToSpeech | 不支持可靠恢复；可原stopTask停止 | 不支持毫秒定位 |

canPause/canSeek按当前输出报告，不假装系统TTS/增量流可定位。云端准备或段间无实际decoder时ready=false，仍可暂停/恢复意图，下一段沿用，不在换段时擅自恢复；位置和时长是当前段，不是整个长稿累加时长。暂停保留原任务/宿主、数据lease、焦点及合成子任务，不暂停网络/合成计费；原缓冲/请求超时限制仍适用，长时间暂停可能因原合成失败而结束。无自动重发、恢复或再次收费。

PCM使用非阻塞有界写入，缓冲满或暂停时短暂让出，停止可正常取消，不因暂停堵在WRITE_BLOCKING；本地PCM仅64 KiB磁盘块，不把256 MiB文件读入内存。定位清除未播放队列和旧读块，时长/位置按实际采样帧计算，不按墙钟或播放速度猜测。编码定位等待平台回调，上限10秒，精度由设备/格式决定；并发控制和输出释放沿同一运行态锁，结束不留下可重启的播放器句柄。

### 实际输出与宿主

媒体会话只绑定当前非终态SPEECH_PLAYBACK，通知带原会话token；元数据报告可得的当前段时长，PlaybackState用实际位置、播放速度和monotonic时间，不把未知位置冒充0。准备为buffering、暂停为paused、真实输出为playing，系统TTS仍不宣告pause/seek能力。位置更新不反复重发通知，仅状态/能力/段切换更新动作；Android 13及以上根据PlaybackState渲染，旧系统沿MediaStyle紧凑动作。停止既有常规ACTION_STOP也有自定义停止，界面具体布局仍由系统决定。

回调创建时捕获原taskId，通知PendingIntent以任务/动作数据URI区分，FLAG_IMMUTABLE；旧会话/通知不能控制后来任务。动作最终由Core检查原任务/实际播放器能力，异常只记类型，不把“收到命令”作为已播放证据。未新增媒体按钮receiver/导出服务/MediaBrowserService、播放恢复或应用Compose页面；setMediaButtonReceiver(null)不让旧按钮重启进程去朗读旧稿。平台媒体会话不可用时保留原任务/停止通知，本次宿主不重复尝试媒体扩展；不因该扩展失败停止其他后台任务。

系统朗读使用 TextToSpeech，编码音频使用 MediaPlayer，原始 PCM 使用 AudioTrack；音频格式是否能解码由实际 Android 设备决定，不假报所有编解码器可用。现有云端原始 PCM 带 raw_pcm_layout_unverified，所以必须由调用者提供真实的 `PcmPlaybackFormat(sampleRate, channels)`，仅支持16位小端 mono/stereo；不能只凭文件扩展名猜布局。

获取 speech 音频焦点后播放；拒绝焦点则失败，丢失焦点立即结束当前播放，不自动恢复/重播。初始化/准备超时标为失败，不误标用户手动停止。播放器完成/系统最后一段完成后才标 SUCCEEDED；收到“任务已启动”或 phase=playing 只是调用阶段，不证明用户实际听见声音。

现有后台宿主增加 mediaPlayback 类型和 FOREGROUND_SERVICE_MEDIA_PLAYBACK 声明，首次注册或从 dataSync 增加播放时明确提升相应类型，再允许音频操作；同时有合成/生成等任务时两种类型并存。类型在该宿主生命周期内保留并集，直到服务空闲停止，不宣称免除系统的 dataSync 时限。Android 11 TTS service 可见性声明同步。停止、宿主失败或系统中断保留任务结果，不重放正文或旧音频；重启按既有任务中断清理处理。

### 来源选择与导入

明确会话 overrides.ttsJson 优先，其次助手 settingsJson.ttsJson：`provider=system` 使用系统 speechRate/pitch/language；否则使用明确 presetId/model/voiceId/format/speed，走现有同来源云端解析。没有明确覆盖时只读本来源 source settings（本地无来源才读 global），以 selectedTTSProviderId/ttsProviders 路由，不借另一来源配置或凭据。

RikkaHub 固定版本内置系统 ID `026a01a2-c3a0-4fd5-8075-80e03bdef200` 可在备份省略默认 SystemTTS 时按上游默认重建，且只有选中的正是该 ID 才重建，不用系统引擎替代另一个缺失/未支持提供方。System 的 speechRate/pitch 已应用；MiniMax 使用本来源已转换 TTS preset 的 model/voiceId/speed（缺省按固定来源默认），凭据仍需用原加密接口另配。

RikkaImport 新增 ttsProviders → 来源隔离的预设 metadata，身份为同来源 preset 的 `tts:<源id>`，沿原三方合并保护本地修改/删除，不改全局选择；System/MiniMax/OpenAI/Groq/Gemini/ElevenLabs 标转换，未实现的提供方标保留且明确仍缺 runtime adapter。sourceTTS 原始字段脱敏保留，凭据 ID 为对应原生预设 ID。旧资料若没有 TTS 预设映射需明确再次导入；不在启动时静默补迁移。RikkaHub 本身没有 ttsEnabled 总开关，转换预设在此字段缺失时按来源语义启用，明确 false 仍禁用。

### 尚未完成

固定来源12种提供方与明确选择的原有四家云端配置已有对应请求/播放路由；增量输出、限额拆段和整稿预处理一次后再拆段源码已接，MiniMax/Gemini当前JSON仍完整接收后输出，段间无缝未实现。编码/PCM常用暂停、本地定位、媒体会话及托管音频列表/按项跳转已接；系统TTS可靠恢复、云端直播跨段定位/全稿时间轴、完整情绪引导/模型变体和native关系仍待补；媒体浏览/Android Auto目录与旧播放恢复未实现。本批普通离线编译/打包通过，不代表完整来源兼容或后端完成。

正常 Debug 构建，无哈希、单测或冒烟，未新增应用界面。没有实际播放设备声音、调用用户云端凭据、模型或语音供应商，也未导入/恢复用户资料；后台/锁屏/焦点、真实解码与语音引擎生命周期需要设备运行证据。

媒体接口依据：[Android MediaSession](https://developer.android.com/reference/android/media/session/MediaSession)、[MediaStyle](https://developer.android.com/reference/android/app/Notification.MediaStyle)、[Android 13基于PlaybackState的控件](https://developer.android.com/about/versions/13/behavior-changes-13#playback-controls)。本批只接系统通知传输，不新增应用页面；未运行设备或连接真实耳机。

接口依据：[固定 PreferencesStore.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt)、[固定 TextToSpeechTool](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TextToSpeechTool.kt)、[固定 TTSProviderSetting](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt)、[Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)、[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Android 音频焦点](https://developer.android.com/media/optimize/audio-focus)。源码作为协议参考，不是本工程助手指令。

<a id="local-tts-gateways"></a>

## 已有本地 TTS 网关

2026-10-09：Android 增加 glm_local、qwen3_local、omnivoice_local 的桌面网关契约适配，复用原 SpeechResolver、SpeechService HTTP/用量、SPEECH_SYNTHESIS、缓存、分段与设备播放。不下载模型、不启动 Windows/Python/CUDA 服务，也不自动探测主机或改写导入来源。

### 调用与字段

继续使用 previewSpeech、synthesizeSpeech、startSpeechTask 或 startCloudSpeechPlayback，明确选择 presetId/voiceId。预设 presetKind=tts，ttsProvider 为上述值，baseUrl 为实际网关地址；没有地址则报错，不猜桌面机器。模型字段只记配置值或 glm/qwen3/omnivoice-local-gateway 路由描述符，不给服务端挑选或加载模型。WAV 是唯一输出；Qwen/Omni不发送采样率选择，实际采样率从返回 WAV 读取。

| 提供方 | 请求 | 参考音频/参数 |
| --- | --- | --- |
| GLM | POST /api/v1/tts，multipart | 必须 promptAttachmentId 和 promptText；发送 input_text、prompt_text、sample_rate（默认24000）、seed（默认42）、use_cache（默认true）、prompt_audio。没有桌面 JSON 路径重试。 |
| Qwen3 | POST /v1/tts/custom_voice，JSON | 无参考音频时发送 text、speaker、language（默认Auto）、instruct。 |
| Qwen3 参考合成 | POST /v1/tts/voice_clone，multipart | 必须 promptAttachmentId、promptText 和明确 voiceCloneBaseUrl；发送 text、language、ref_text、x_vector_only=false、ref_audio。不猜第二端口，克隆模式有指令则明确拒绝。 |
| OmniVoice | POST /v1/tts，JSON | text、speed、可选language；有托管参考音频时 ref_audio_base64/可选ref_text优先，否则使用instruct。 |

providerOptions 可显式指定上述 camelCase 字段。选中的 voiceCatalog 条目提供 promptAttachmentId、promptText、promptAudioPath、instruction 默认值，顶层桌面语言配置转成 language；原 providerOptions、会话覆盖和明确请求优先。request.instructions 优先于 instruction。GLM/Qwen 合成速度只能1，Omni可0.25..4；播放速度仍由原播放器单独控制。

promptAttachmentId 只能指向应用已托管音频，最多20,000,000字节。GLM/Qwen流式 multipart；Omni按其网关契约进行有界 Base64 JSON 编码。桌面 promptAudioPath 只保留为迁移提示，缺托管 ID 时明确失败，绝不打开桌面路径或擅自改为另一种声音。参考音频解析/编码在 Room 事务外，任务数据 lease 保留原文件引用生命周期；任务 payload 不带音频或文件句柄。

### 网络与生命周期

新增 `getSpeechGatewayHealth(SpeechGatewayHealthRequest(presetId=...))`，不需要朗读文本、voiceId 或启用 TTS。沿原来源/助手/会话选择冻结地址和可选凭据，Room/gate 准备结束后才发 GET /health；不启动进程、清理显存、合成、持久保存健康结果或新增合成账本。

GLM/Omni只查主网关；Qwen默认还查已配置的 voiceCloneBaseUrl，`includeVoiceCloneGateway=false` 可明确跳过。没有第二网关则 warning，不猜第二端口。checks 分 synthesis/voice_clone，state 为 reachable、http_error、network_error、timeout、cleartext_blocked；HTTP状态可得时保留，错误仅安全 code，不返回原响应正文/服务路径。reachable 表示本次所有已检查 /health 均按桌面契约返回200，不是模型加载、音色或合成可用证明；未配置/未检查克隆网关有单独 warning。连接/读5秒、单Call10秒，不重定向或重试；取消关闭原Call，不能被包装成“健康查询失败”的正常返回。当前没有运行这项查询。

不要求 API key；若显式存了凭据，沿原加密库/Bearer，不生成空 Authorization。网关请求复用原两个槽位、取消 call.cancel、无重定向/重试/失败换路由；连接15秒、写120秒、读600秒、整 Call900秒，返回音频沿原有界读取与 WAV 检查。长稿可沿原整稿预处理一次后分段，本地模型不开放 MiniMax 专属情绪标签。

网络安全配置未放宽：HTTPS 可用，HTTP 仅原 localhost/127.0.0.1/::1 例外。手机 localhost 不是桌面电脑；LAN HTTP 在当前 Android 配置下仍被禁止，需要 HTTPS 网关，不能用本批代码声称任意 LAN HTTP 已可用。导入中旧桌面托管开关、仓库路径/端口不会启动任何进程。

沿原 tts 账本记录请求/输入字符/实际结果，费用 unknown 不等于免费，也不把客户端 model 描述符当服务端模型或账单证明。重启/native只中断，不重发。范围以桌面 backend/app/services/tts_platform.py 的三个 LocalTtsPlatform 与 routes/tts.py 为依据；结果持续标 local_gateway_desktop_contract_unverified。没有运行真实网关、音频、账户或设备；构建记录见 VERIFICATION.md。完整后端目标仍未完成。

<a id="voice-clone"></a>

## 参考音频上传与音色克隆

2026-10-09。MiniMax、GLM、SiliconFlow 三路已接原音色创建 worker；三个 Luna 实际编写共用 DTO/账本展示与提供方字段适配，主代理接来源选择、共享 HTTP、任务和恢复。普通离线 app:assembleDebug 首次成功99秒，最后统一任务结果说明后再次成功33秒、退出0，230tasks：6executed/224up-to-date；只有编译/打包证据，没有真实账户、音频上传、播放或设备验证。无新表、迁移、依赖、前端、哈希、冒烟或单测。

### 调用

`startSpeechVoiceClone(SpeechVoiceCloneRequest(sourceAttachmentId, voiceId, ...))` 创建独立 `SPEECH_VOICE_CLONE`。来源音频和可选 `promptAttachmentId` 都是已复制到应用托管 attachments 的明确 ID，不接受任意文件路径、源 URI 或外部挂载。沿原来源/会话/助手选择、TTS 开关、加密凭据与同 origin；不从其他来源借凭据或自动选第一条。

`getSpeechVoiceCloneProgress`、`awaitSpeechVoiceClone` 和 `observeSpeechVoiceClone` 查询持久进度、成功结果与最新克隆状态，原 `stopTask`/通知停止不变。克隆和设计共用原 worker、shared data lease、宿主与取消处理，分别有 kind、观察流和用量；旧设计 DTO 名通过 typealias 保留，原序列化字段和默认值保留，新字段放末尾。

结果含提供方实际音色 `voiceId`、`voiceType`、可得 `remotePreviewUrl` 与安全 warnings；进度保留可得上传文件 ID、角色及本地请求发起数。MiniMax 以已获成功响应的请求 voiceId 为 ID，GLM 必须返回 `voice`，SiliconFlow 必须返回 `uri`，不把自定义名称猜作后三者的服务端 ID。成功完成的设计/克隆任务可显式调用 `startSpeechVoicePreviewPlayback`：复用设计托管试听附件，或下载创建结果 URL 后走原设备播放；不自动播放或重做创建/合成。CDN 不带 provider 凭据，不保证上游/CDN没有其他费用；URL可能临时/带签名，结果 `toString` 不打印 URL。试听接线的当前构建记录见 [VERIFICATION.md](VERIFICATION.md)，上方99/33秒是此前克隆批次的历史构建。

### 提供方映射

| 提供方 | 已接请求与返回 |
| --- | --- |
| MiniMax | `/v1/files/upload` 的 `voice_clone` 源文件；明确提示音频用 `prompt_audio`，成功上传后登记远端 ID；`/v1/voice_clone` 的整数 file_id、voice_id、可选 clone_prompt、text/model/language_boost、三 boolean、text_validation/accuracy。返回 `demo_audio` 只作为试听链接。 |
| GLM | 桌面同源 `/api/paas/v4/files`，purpose=`voice-clone-input`；随后 `/api/paas/v4/voice/clone`，model、voice_name、明确 input、file_id、可选参考 text。不追加目录回查；携带 `glm_voice_clone_desktop_contract_unverified`。 |
| SiliconFlow | `/v1/uploads/audio/voice` 一次 multipart，file/model/customName/text，实际 `uri` 即音色 ID。不是 MiniMax 两阶段协议，不额外合成试听。 |

MiniMax 的 voiceId 按官方字符/长度规则；源和提示音频只收 mp3/m4a/wav，文件不超过 20,000,000 字节。预览文本最多 1,000 码点，有文本须选模型（明确请求或同预设 model/唯一 models）；提示音频与 promptText 成对。textValidation 最多 200 码点，accuracy 明确且有限 0..1，并须伴随 textValidation。客户端不做 ASR/音频时长、哈希或转码，时长/内容/权限/重复名称由上游决定，不截断文本。

GLM 默认路由模型标识 `glm-tts-clone`，明确请求可覆盖；须明确最多 1,024 码点的试听文本，不添加桌面的默认问候语，参考文本最多 1,000。SiliconFlow 必须有模型和参考文本，明确模型或原同预设唯一配置；拒绝预览文本及不支持的 MiniMax 专属参数，不静默丢弃。共用本地文本/身份和文件预算；其他厂商实际限制仍由上游判断，不能凭文件扩展名宣称音频可用。

### 生命周期与费用

HTTP两个克隆槽位，沿原无重定向/重试和 NoHttpReplayInterceptor；15秒连接、60秒写、30秒读、120秒单 Call，JSON响应最多1MiB。文件流式上传，不把整个音频装入任务 payload；取消主动 call.cancel，每个下一请求前登记状态并检查停止。来源已由用户复制到托管存储，不回写或删除原附件。

`requestSent`/`requestCount` 是 execute 前登记的本地意图，不证明服务器收到、收费或完整上传；`uploadedFiles` 只是可得成功响应。失败/超时/停止可能留下部分远端文件或音色；不自动删除、重发、激活或补上传。已经确认的返回 ID 在本地后续失败时保留；不能确认的结果标不完整，不宣称云端已回滚。

独立 `speech_voice_clone` 通用账本，和任务登记/checkpoint/终态同事务；汇总单列克隆事件及提交的文本码点数（包括明确预览、参考、提示、校验文本），不是提供方确认的计费字符。费用 unknown，不混设计、普通 TTS、LLM 或播放，也不借网页单价自动安装价格。

重启与 native 恢复只标 interrupted，不恢复原文件上传/POST/URL下载。原任务/账目/附件表导出及本批基础归属、kind检查同步，未知字段保留；这不是完整包外覆盖保护、WAL/断电或真实恢复证明。远端创建与本地记录没有跨系统原子事务。

### 依据与余项

字段移植桌面 `backend/app/services/tts_platform.py` 与 `/routes/tts.py`，并核对 [MiniMax上传](https://platform.minimax.io/docs/api-reference/file-management-upload)、[MiniMax克隆](https://platform.minimax.io/docs/api-reference/voice-cloning-clone)、[SiliconFlow上传](https://api-docs.siliconflow.cn/docs/api/uploads-audio-voice-post)。本批搜索未取得 GLM 对应官方接口文档，按桌面契约实现，不冒充官方/账户确认。

其他提供方克隆/设计/音色删除、上游音频/权限/费用/取消/期限与完整 native/设备证据继续；试听下载与设备播放的真机解码/行为尚未验证，不按本批可编译把 [完整后端目标](STATUS.md) 标完成。

<a id="voice-design"></a>

## MiniMax 音色设计

2026-10-09 后续：原 worker 现共用设计/克隆的 Creation DTO，旧设计名称为兼容别名、原字段默认值不变，设计仍有独立 kind/观察流/账目。新增三路参考音频克隆契约见 [SPEECH.md#voice-clone](SPEECH.md#voice-clone)，不改变原设计自动播放/配置政策。

2026-10-09。桌面 `/api/tts/design` 对应业务已接；两个 Luna 分别编写 DTO/账本展示和 HTTP，主代理接原来源解析、任务与试听保存。最终普通离线 app:assembleDebug 成功26秒、退出0，230tasks：10executed/220up-to-date；只有编译/打包证据，没有真实账号或设备验证。无新表、迁移、依赖、前端、哈希、冒烟或单测。

### 接口

`startSpeechVoiceDesign(SpeechVoiceDesignRequest(prompt, previewText, presetId?, chatId?, assistantId?, voiceId?))` 返回独立 `SPEECH_VOICE_DESIGN` 任务。`getSpeechVoiceDesignProgress`、`awaitSpeechVoiceDesign` 和 `observeSpeechVoiceDesign` 分别查持久进度、等待成功结果和观察最新状态。停止沿原 `stopTask`、通知和平台中断；取消等待者不隐式停止 core-scope worker。

沿原 SpeechResolver 的明确预设/来源/助手/会话及加密凭据，来源须启用 TTS。无需先选合成 model/voice，不执行预处理。短 Room 读取冻结选择后才联网，任务持原 shared data lease，与备份/恢复协调，不持网络期间 Room 事务。

只支持 MiniMax，同所选合成 endpoint 的 origin 请求 `POST /v1/voice_design`，Bearer 鉴权。不换区域/主机；发送描述、最多 500 Unicode 码点且 2,000 UTF-8 字节的试听文本及可选 `voice_id`，描述最多 8 KiB。超限拒绝，不沿桌面的静默截断。不发送当前官方页面未确认的 `aigc_watermark`。

结果保留 `voice_id`；可选 `trial_audio` 十六进制 MP3 保存为托管 `previewAttachment`。可显式用其 ID 调用原 `startChatAttachmentPlayback` 试听；默认不播放、不改预设/目录/角色或 Persona 映射，也不删除云端音色。不把设计试听伪造成普通合成 `SpeechAsset` 或合成模型。

### 生命周期和用量

复用原前台宿主、WorkerStopSignals、任务停止与平台中断；活跃 worker 最多 16 个，HTTP 两个槽位，15 秒连接/写、30 秒读、60 秒单 Call。禁重定向/重试，沿原 NoHttpReplayInterceptor。JSON 最多 16 MiB，音频最多 8 MiB，无第二次下载、回查或自动合成激活。

描述和文本只留本次 private session，不进任务 payload/普通日志。`requestSent` 是 execute 前登记的发起意图，不证明服务器收到或收费；失败/超时/停止可能已有远端副作用，不猜结果、不重发。已收到的音色 ID 在本地试听保存失败或停止后仍留进度。

独立 `speech_voice_design` 账本和任务登记/checkpoint/终态同 Room 事务。通用分页支持 kind 过滤，汇总单列事件数和提交的试听字数，不混 LLM token、TTS 合成或播放次数。费用保持 unknown，不借合成价表、网页单价或零价。

文件写完后，attachments、任务成功及账目终态同事务登记；失败清理本次未发表文件，重启只处理中断记录及本次残留预览，不续跑或删除云资源。远端请求、文件与 Room 不是跨系统原子事务，未证明断电/WAL 全部耐久性。

native 导出沿原任务/用量/附件表，预检识别新 kind、归属与可得试听引用；活跃记录恢复为 interrupted、费用 unknown，未知字段保留，不调用上游。仅本批基本接线，不宣称完整 native 关联/覆盖保护或实际恢复验收。

### 依据与余项

字段、设计 ID 与本地试听移植桌面 `backend/app/services/tts_platform.py` 和 `backend/app/routes/tts.py`；请求/响应和预览限制核对 [MiniMax 官方 Voice Design](https://platform.minimax.io/docs/api-reference/voice-design-design)。官方说明试听会收费，本批未发送真实请求。

参考音频上传/克隆、其他提供方设计、声线激活/期限、真实云端/收费/取消/设备与全部参数仍继续，完整目标未完成，见 [STATUS.md](STATUS.md)。
