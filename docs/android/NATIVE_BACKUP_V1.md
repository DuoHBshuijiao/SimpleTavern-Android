# 原生备份 v1 契约

## 文档 URI 备份输出与结构预检（2026-10-09）

Core新增 `startBackup(destination: Uri, options=BackupOptions(), password=null)` 返回原BACKUP任务，以及 `exportBackup(destination: Uri, options=BackupOptions(), password=null)` 等待成功并返回原URI。原File输出/overwrite参数保留；URI入口是明确替换用户授权文档的字节，没有不覆盖承诺，通常由宿主ACTION_CREATE_DOCUMENT取得目标。`preflightNativeBackup(archive: Uri)` 返回原NativeBackupPreflight，补齐文档选择器输入；原File预检仍可用。

URI备份先沿NativeBackupService的独占数据快照、Room表导出/文件/可选加密凭据、ZIP和原发布逻辑，生成一次私有 `backups/document_exports/<taskId>/archive.zip`。完整ZIP生成之前不打开用户文档，因此生成失败不截断目标。之后快照lease释放，使用与沙箱导出共用的SafDocumentOutput按64 KiB写出；这份ZIP是本次快照输入，不重新查询当前数据/加密，也不加载整包内存或额外再复制一个ZIP。文档提供方延迟不继续占用原独占快照，BACKUP任务仍活跃至写出/收尾。

BackupTaskProgress尾部新增outputUri/documentBytes/documentMayBePartial/documentWriteCompleted/privateArchiveRetained默认值；File备份沿原outputPath，URI结果不披露私有archive路径。rows/files/bytes仍描述生成快照及未压缩内容，documentBytes仅为写出ZIP的字节，不混加。archive_ready不是任务成功；打开可能截断之前持久标记不确定状态，正常写出/flush/close后记录完成，之后任务才可成功。终态停止/中断仍可能带documentWriteCompleted=true，不能据此重发；正常close不证明云端耐久上传。

临时ZIP和目录在本次收尾清理，失败时privateArchiveRetained与cleanup_pending标明保留；已完成的用户文档不被清理错误撤销。启动先沿原operations journal清理临时文件，再只处理有BACKUP+URI任务归属、合法目录和已知archive.zip的私有暂存，保留未知/不可删除内容，不打开或删除外部文档、不重放旧任务；修改已知清理字段时保留任务原生命周期和opaque payload字段。旧恢复/历史标记仍是当时证据，不据此申请URI权限或自动恢复输出。

URI预检使用原AssetFileDescriptor读入及已有restore_inspections暂存/清理，结构预检只持有普通shared lease，可与正常聊天并行，不扫描或提交所有业务行、不解密凭据、不返回可复用恢复计划。完整inspectNativeRestore仍沿原独占检查；真正恢复仍重新读取并完整检查。compressed输入/文档ZIP输出沿原8 GiB+512 MiB上限，解包/快照沿原8 GiB等预算。File接口、native v1、Room版本和Desktop/RikkaHub并存合并都未改。

SafDocumentOutput是两项实际写出共用的provider适配函数，不是另一套任务/权限/恢复引擎；同进程相同URI字面值在实际写出时拒绝并行，但不识别所有provider别名。wt、取消信号、错误关闭、部分文档、无权限自动申请/持久化/重试及不自动删除目标的边界同 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。未写用户文档、备份/预检/恢复或运行设备，仅普通源码检查和Debug构建，无新表、依赖、前端、文件哈希、冒烟或单测；完整native关系、实际提供方/设备和耐久性仍待验证。

## 文档 URI 恢复输入（2026-10-09）

Core的 `inspectNativeRestore(archive: Uri, password?, mode)`、`startNativeRestore(archive: Uri, password?, mode)`、`restoreNativeBackup(archive: Uri, password?, mode)` 已接调用方授权的content URI，原File重载保留。后台启动沿同一RESTORE任务/通知停止/平台中断，等待恢复沿原await；检查仍是调用方可取消的只读操作，不注册后台任务或返回可重用提交计划。`preflightNativeBackup` 仍只有File重载，不把两种检查入口混为一谈。

URI打开复用原SourceArchiveInputs的AssetFileDescriptor：保留实际offset/declaredLength，可读普通文件片段或无seek的provider管道，取消/超时与可靠管道错误沿原实现。随后直接复制到恢复/检查原已拥有的 `archive.zip` 私有暂存目标，不再先做一份restore_inputs快照，也不在复制中持有冲突的shared lease。compressed输入沿原8 GiB+512 MiB预算，解包沿原8 GiB等限额；不是将provider URI转换为外部路径。

提取原逐块描述符复制为内部copyTo，共用原大小/时间/短读及60秒无数据超时逻辑、fd同步，不新增哈希；普通导入/FILE_COPY的私有stage/rename/权限/清理仍在原snapshot外壳内。NativeRestoreProgress尾部追加inputBytes默认0，报告输入阶段实际复制字节，不冒充已恢复的文件bytes；后续检查、文件发布、凭据/Room提交、回滚及进程启动恢复全走原链。输入描述符在复制后或所有失败/停止分支关闭，密码沿原副本清零。

宿主需先取得可读授权；后端不启动选择器、自动申请/持久化授权、修改或删除源文档，也不在进程重启后续读该URI。当前打开动作发生在后台任务登记前，沿原30秒打开超时；provider可能忽略取消，实际兼容和时间表现需设备证据。没有改native v1/Room版本、来源Desktop/RikkaHub合并规则、任务恢复策略或新增表/依赖；未读取用户备份/凭据或运行恢复/检查，没有新增冒烟、单测或哈希。完整native关系、耐久性/异常及实际设备仍未验收。

第四十七批：GEMINI_CACHE_LIST/LIST 对应 kind/action，1 MiB payload/1 MiB+32 KiB usage 预算及 metadata-only listing/页大小/唯一名称/固定 warnings 已知校验；其他资源操作维持 32/64 KiB。可得父 LIST 须同 owner、成功确认/hasNextPage/相同页大小/有效先后，循环检查及 task/ledger 配对涵盖新 kind。包外原有效 LIST 的可验证父页完整 JSON 保留保护增强；恢复保持结果/未知字段、只中断未结束页，不恢复分页 token 或运行时确认。Room v2/25 表/native v1 不变，完整余项继续，见 [GEMINI_CACHE.md#gemini-cache-listing](GEMINI_CACHE.md#gemini-cache-listing)。

第四十六批：缓存 lifecycleVersion/lifecyclePreviousTaskIds 为不可变操作身份，校验 shape/唯一/64 个上限。可得前驱须同 owner/name、已结束且不晚于新操作开始；缺历史 warning。NativeGeminiCacheLifecycleGuard 迭代核对候选与可得 live 祖先循环，100000 节点/64 MiB 读取预算；保留的原有效配对操作还保护可验证祖先完整 JSON（只排除运行时 epoch）不被包内替换改坏。未知字段/前沿/报价保留、恢复只中断不请求远端。Room v2/native v1 不变；全关系/清理/索引仍待补，见 [GEMINI_CACHE.md#gemini-cache-lifecycle](GEMINI_CACHE.md#gemini-cache-lifecycle)。

第四十五批：已知 app_settings 行 gemini_cache_pricing_v1 校验版本/200 条/256 KiB/来源/作用域/有效期/费率。CREATE frozen pricing 必须符合原 owner/开始时间，projectedCost 必须由该快照、确认 totalTokenCount 和原 TTL 得到；不按当前配置重算。任务/账目全 JSON 配对保留报价及未知字段；未结束任务只中断，旧无报价历史不补估算。恢复使编辑 PricingVersion epoch 失效，Room v2/native v1 不变；实际存活/结算仍未完成，见 [GEMINI_CACHE.md#gemini-cache-pricing](GEMINI_CACHE.md#gemini-cache-pricing)。

第四十四批新增 tracked Gemini 缓存任务/独立账目成对校验与稳定中断归一化，保留未知 JSON/终态费用；REPLACE_INCLUDED 保护包外原可核实配对/来源/父缓存引用，epoch 仅作运行时失效，不重发或补造旧账。每页 200/100000 行/64 MiB；只补这些关系，完整链图/旧历史/配置/版本/真实 IO 仍继续，见 [GEMINI_CACHE.md#gemini-cache-accounting](GEMINI_CACHE.md#gemini-cache-accounting)。

第三十六批增加生成消息与费用关联及包外保留引用覆盖保护：按历史 eventId/requestId 查账，新旧主键兼容，冻结费用/用量/来源与可得创建关系核对；同源分叉允许共享原事件，不复制费用或要求等于原创建消息。原可核实关系被覆盖改坏阻断 PRESERVED_GENERATION_USAGE_CHANGED，历史证据不足只 warning。已知运行中生成标记改 interrupted，opaque/未知字段原样保留；只中断不重发，不读当前价格。每页 200/100000 行/64 MiB 总读，生成 metadata 沿用 native 32 MB TEXT 而不是语音 256 KiB；细则见 ACCOUNTING.md#generation-usage-restore，其他 native 关系及实际恢复证据仍继续。

第三十五批普通 token 生命周期用途扩展至 chat_generation/memory_summary/state_maintenance，新 ID 沿用既有生成/维护/分段请求字符串（预处理仍 UUID），scope 支持与行 chatId 一致的 chat: 范围。候选检查新 context 的可得创建消息、task、summary job 同 chat/任务种类与 segment/round/turn 预算，历史缺失 warning、跨归属阻断 TOKEN_REQUEST_MESSAGE_SCOPE_MISMATCH/TASK_SCOPE_MISMATCH/TASK_KIND_MISMATCH/SUMMARY_SCOPE_MISMATCH。稳定归一化/旧无标记历史兼容/只中断不重发不变，已保存供方事件不因业务提交回滚而移除；其余包外成本/生成引用及全部 native 关系仍继续，见 ACCOUNTING.md#token-request-accounting。

第三十四批 token 生命周期：新预处理普通 usage envelope 的 `_requestLifecycle` v1 校验 prepared/running/终态、httpAttempted/attemptedAt/endedAt、协议/用途/源作用域/完整 UUID 身份/合法费用与预算；同逻辑键解析（含转义/不同键顺序），不依赖 startup 规范头索引。未结束事件按稳定包生命周期时间归一化 interrupted/native_restore_interrupted，cost unknown，不执行 HTTP 或依据当前费率补费用。task/message/assistant 缺历史引用 warning；可得预处理完成事件新有标记时须有本地发送证据，旧无标记完成记录不补造。保留覆盖保护比较完整 usage/cost，包含新生命周期。Room v2/25 表/format v1 不变，全版本/真实恢复/进程/断电持久性证据继续待补。

第三十三批预处理关联：SpeechSelection.preprocessing 冻结同来源两套配置，SpeechTaskProgress/SpeechAsset.preprocessingRequestId 与 TTS context 同 UUID，指向普通 purpose=tts_preprocess 事件。候选任务验证选择/UUID、LLM 预设历史引用，以及存在事件的 provider/model/chat/协议/用途/模型/来源/作用域/task/candidate/起始时间与合法冻结 cost，错误 SPEECH_PREPROCESS_USAGE_MISMATCH；事件缺失仅历史 warning，预设已移源为 SPEECH_PREPROCESS_PRESET_SOURCE_HISTORICAL，不拿当前配置重算历史。

REPLACE_INCLUDED 涉及此 UUID 或冻结 LLM 预设时，包外有效缓存另检查预设移源；原有可验证 preprocessing usage/cost 的完整 JSON 关系不得变为不同/不可验证事件，报 PRESERVED_SPEECH_PREPROCESS_USAGE_CHANGED。旧无证明仅 PRESERVED_SPEECH_PREPROCESS_USAGE_UNVERIFIED，不猜费用/稿或补请求。新增逐列读仍记入原 64 MiB/100000 行总预算、usage 64 KiB/cost 32 KiB，检查与真正恢复复用。普通配置/价格变化不影响原冻结证据；这些关系不是语义正确性/HTTP 已发送/发票证明。Room v2/25 表/format v1 和依赖不变，无哈希，完整异常/版本/真实 IO 仍待补。

第三十批恢复保护（当前）：显式 REPLACE_INCLUDED 增加 NativeSpeechRestoreGuard，覆盖任务/附件/会话/原始消息/助手/预设/用量和缓存文件对包外保留消息的影响。只校验和阻断，不改包外消息、不删缓存、不重建音频或重发请求；默认 ADD_MISSING 与 desktop/RikkaHub 合并规则不变。Room v2/25 表/format v1 无变化，没有新依赖。完整条件、预算与局限见本节及 SPEECH.md#tts；只有普通编译/静态证据。

## 包外保留语音引用（第三十批）

触发相关表/tts 文件覆盖时，SQL 以 rowid 每页 200 扫本地非空 extraJson 消息，不用字符串包含 _tts 作最终过滤，避免 JSON 转义键漏查。候选 ID 先限 1024 字节、正文+extras+生成元数据先限 256 KiB，文本按列分块读取；已在包内明确替换的消息交由既有候选校验，不当作保留消息。保留扫描至多 100000 行/64 MiB 文本读取，超限整个检查失败，不拿部分引用清单作成功授权。

原本有效指局部持久关系可证明：绑定 v1/v2 合法、当前正文与绑定相等、SUCCEEDED/done 任务/资产/冻结选择一致、完整 tts: attachment 元数据和 v2 稿/来源一致。不代表音频可播放或底层文件完整性。既有编辑失效/目录淘汰/缺来源/非法 metadata 只历史 warning；当前语音 API 已不可读的大旧行也 warning 并不装入 CursorWindow 猜引用，不把旧问题当本包新损坏。

| 被覆盖项 | 保护边界 |
| --- | --- |
| tasks/attachments | 原有效保留引用不能指向不同 SpeechAsset/冻结 SpeechSelection、非成功任务或不一致附件，报 PRESERVED_SPEECH_ASSET_CHANGED/UNREADABLE |
| 目标会话来源 | 不能把原有效绑定移到另一 sourceNamespace，报 PRESERVED_SPEECH_SOURCE_CHANGED |
| v1 原始会话/消息 | 已有来源和原消息朗读稿证明不能被移源或擦除，报 PRESERVED_SPEECH_ORIGIN_CHANGED/TRANSCRIPT_PROOF_CHANGED；本来无证明不猜稿 |
| 助手/预设来源 | 同 ID 来源变更不得与 v2 冻结来源或可得 v1 原来源矛盾，报 PRESERVED_SPEECH_CONFIGURATION_SOURCE_CHANGED；控制/开关/价格改变不改已生成音频 |
| asset.requestId 的用量 | 原本可验证的完成事件/上下文/字符/格式音色/字节量/冻结费用不能变为另一笔，报 PRESERVED_SPEECH_USAGE_CHANGED；旧未验证账目 warning，不假造费用 |
| 缓存文件/模式 | 若旧逻辑绑定有效，且旧叶项为声明长度的普通文件，包内完整字节或模式不同报 PRESERVED_SPEECH_FILE_CHANGED，显式覆盖也不能暗中换掉保留消息的音频 |

相同资产可有同来源多消息/分叉引用，不要求它们等于创建时候选。保护发现影响时阻断整个预检/RESTORE，不自动解绑；若调用者明确替换消息本身，则新绑定仍受候选关系检查。文件比较复用 compareFile 完整有界字节/身份前后检查，不计算哈希；旧文件已缺失或类型/长度不合不是原本有效文件证明，普通文件冲突/安全父目录规则继续适用。

候选 TTS task 的 attachment 校验补齐 filename/MIME/sourceUri，v2 资产与可得创建会话来源一致。资产带 requestId 时，可得账目须符合完成合成的 provider/model/scope/source/创建 task/chat/message、输入及报告字符/格式音色/音频字节，并有一致成本，错误 SPEECH_USAGE_ASSET_MISMATCH/SPEECH_COST_MISMATCH；部分包账目缺失仍历史 warning，不执行请求补齐。

只读检查与真正 RESTORE 的首次检查、文件发布后再次检查走同一逻辑，仍在既有 exclusive gate/Room 事务中；失败走既有撤销，不是 FS/DB 强事务。未隔离 shell/公开低层 repository 可绕过围栏，全部其他引用/版本/配置/大行/低磁盘/WAL/幂等/断电与设备证明继续缺，不能以本批守卫声称完整后端或音频运行验收。

第二十九批语音账本补充（当前）：Room v2/25 表/format v1 不变，app_settings(speech_pricing_v1) 与 usage_events(_kind=tts/version=1) 随普通行导出；ledger 不存正文/key/body/audio bytes，新缓存 asset 的 requestId 是历史用量关联，不是重发权限。检查定价版本/256 KiB、usage 64 KiB、请求身份/模型/会话/冻结成本与可得 task/message 引用，缺历史引用 warning。prepared/running 按稳定包生命周期改 interrupted/native_restore_interrupted 并记未知费用，不重新合成。旧 APK 不识 _kind=tts 的包不能假定兼容，全版本/保留关联/真实恢复/崩溃持久性证据仍缺，见 ACCOUNTING.md#speech-accounting。

第二十八批 TTS 补充（当前）：Room v2/25 表/format v1 不变，新 SpeechAsset/Binding v2 冻结实际朗读稿/来源及绑定时正文。tasks payload 现在包含 spokenText（128 KiB 预算），消息 _tts 包含 sourceText/messageText；普通备份这些用户文本未加密，不保存 key/HTTP body/audio bytes。候选消息恢复检查绑定版本/256 KiB 总行预算/可得 task kind、朗读稿和来源，正文编辑或缓存已淘汰 warning，稿不一致/跨来源阻断；同来源多消息/分叉共享引用不要求创建候选 ID 相等。包外保留引用受显式覆盖影响的全部关联审计仍待补。旧 v1 可读，无原始朗读稿证明不授权猜稿或重新合成；旧 APK 不识 kind/v2 asset/binding 会拒绝相应包，实际恢复与版本证据未验收，见 SPEECH.md#tts。

第二十七批 TTS 补充（历史）：SPEECH_SYNTHESIS kind 为既有字符串列，Room v2/25 表/format v1 不变。已提交缓存的 tts: attachment 行/tts-UUID 文件、tasks payload 的 SpeechAsset 和消息 extraJson._tts 随相应普通表/文件导出；完整可读缓存须包含 tasks/attachments 和文件，部分选项包可能只保留失效引用。speech_staging 不入包，当时 task 不保存请求正文/key/body，消息绑定仍含实际正文。恢复严格校验 TTS payload 身份/预算/版本及可得附件关系，清除 device/inode/cleanup marker，未成功 asset 引用也清除；不把旧设备日志当删除权限，不重发合成。已淘汰附件只是历史缓存缺失 warning。旧 APK 不识新 kind 会明确拒绝；实际完整备份/设备行为仍未验收，见 SPEECH.md#tts。

第二十六批 TTS 补充：仍为 Room v2/25 表/format v1，仅复用预设/settings/chat overrides/来源映射，新增导入 TTS 引用可随普通行出包。直接合成的 SpeechAudio.bytes、HTTP 会话/密钥快照/请求文本不写业务表、文件或任务，因此没有新音频缓存/账本数据可备份；配置存在不代表音频已备份，也不授权恢复后重发合成。TTS 缓存/消息/任务与备份策略尚待实现，见 SPEECH.md#tts。

2026-10-03 第十八批。已实现导出、结构预检、恢复内容/引用/冲突检查、默认追加与显式包内覆盖的数据库提交、文件/凭据撤销日志和 RESTORE 宿主，新增组合链接图与稳定旧生命周期归一化；仅普通编译，未实际还原或验证崩溃/断电。完整恢复审计仍待补，本文不是完整恢复验收证明，也不修改 desktop/RikkaHub 自动合并并存的来源导入策略。

第十九批补充：BACKUP/RESTORE worker 改为 ATOMIC 就绪握手，注册中宿主中断保留原因，首次调度前取消仍执行收尾/清密码；RESTORE handoff 获取异常也在 try 内收尾，非成功进度以持久任务终态投影。来源 File/SAF 私有输入快照为独立 IMPORT 能力，import_inputs 不导出、不作续接检查点；原生恢复/检查的输入仍是 File，没有把来源 SAF 重载冒充原生 SAF 支持。这些仅编译，未设备运行。

第二十批补充：新 FILE_COPY kind 与其用户数据/变化/任务可以随普通表和沙箱文件导出，但 copy_inputs 私有输入/意图不入包、不用于重放。任务 kind 仍为 schema v2 的字符串列，没有业务表迁移；旧 APK 不认识新 enum 会明确拒绝含此 kind 的原生包，这不是已证明的全部版本兼容。活跃复制及收尾 reservation/gate 拒绝原生快照穿过中间态；复制意图启动只核对元数据而不重新复制。

## 包格式

第二十一批：文件事件/窗口使用既有 file_changes 字段，仍为 Room v2、25 表、原生 format v1。FileChangeCursor 的 rowid/进程 epoch 不具备跨恢复含义，恢复后需重新请求分页；导出的历史报告不授权续接监听/命令，不将监听器或实时队列写入包。

第二十二批：定价规则在既有 app_settings 独立行，成本/请求上下文在既有 usage_events.costJson/usageJson，消息快照在 message JSON；不增加表/列/迁移，随普通记录进入原生包。恢复不会重估历史费用或续发旧请求；定价 CAS 和账本 cursor 的过程/恢复 epoch 失效后须刷新。嵌套版本与所有大行的跨版本恢复仍待完整审计，不能把 25 表已包含这些字段当作运行验收。

第二十三批：搜索独立配置/CAS revision 在 app_settings，查询与有界来源结果/原生引用在既有消息 JSON，实际搜索 key 仍只在加密凭据存储；普通备份不导出 key，显式密码凭据策略不变。内存搜索许可、当前连接/停止监听和 HTTP 请求不作为续接状态导出。仍为 v2/25 表；恢复后 CAS epoch 失效，新消息字段/来源助手关联及跨版本语义仍需完整审计，不重发旧搜索。

第二十四批：账户状态/用量/余额仅返回调用者，不写数据库或账户缓存，原生包不增加账户查询记录/网络连接/重发状态；配置与加密凭据策略不变。查询使用捕获的配置版本，恢复不重启旧账户调用，Room v2/25 表不变。

第二十五批：search_pricing_v1 与独立搜索 usage_events（_kind=web_search/version=1、web-search: provider、选中规则快照/credits/终态 cost）沿用已有配置与数据表导出；无查询正文/key/余额/连接。恢复验证新事件类型/来源/历史关联，将包内 prepared/running 稳定转换为 interrupted/未知费用，绝不重发；结束事件费用不自动重估。Room v2/25 表不变，旧 APK 不认识新语义，跨版本仍须审计而不是仅凭 schema 号宣称兼容。

根 `BACKUP_META.json` 为 NativeBackupManifest：format=`simpletavern-native`、version=1、schemaVersion=2、实际 BackupOptions、创建时间、表/字段/主键/行数描述、文件清单、明确省略的表、凭据策略、环境和边界说明。没有自定义内容哈希或哈希校验。

每表一个 `database/<table>.jsonl`，每行是列名到 SQLite 标量的对象（字符串、整数、有限浮点数、null）。不把 entity JSON 再包成字符串，也不导出热 SQLite/WAL/Keystore 文件。BLOB 与未知 schema 明确不支持；当前 25 张表没有 BLOB 列。schema 中的所有实际业务表须与清单一致，否则拒绝导出，不静默漏新表。

默认 options 包含这 25 张表：

| 集合 | 表 |
| --- | --- |
| 会话与历史 | chats、message_nodes、message_candidates、assistant_messages |
| 配置内容 | characters、personas、world_books、assistants、model_presets、app_settings |
| 记忆/状态/队列 | long_term_memories、memory_summary_jobs、mvu_states、knowledge_graphs、mvu_work_logs、content_regex_records |
| 来源与映射 | import_batches、id_maps、source_blobs |
| 文件/沙箱 | attachments、sandboxes |
| 任务/审批/变化/用量 | tasks、approvals、file_changes、usage_events |

当前导出任务自身不进入 tasks 快照。正式数据版本、候选选择、原生生成元数据、来源 namespace/typed ID map/baseline、助手历史、队列消费与用量字段均按实际列保留，不重新生成 ID。已知配置/来源/诊断 JSON 的凭据键脱敏；正文、思考、正式 MVU/KG、规则数据及用户文件仍可能含私密信息，普通 ZIP 不是全包加密。不能把“默认不导出 CredentialStore”说成“所有用户字符串都不含秘密”。

附件位于 `attachments/...`，沙箱位于 `sandboxes/<id>/...`，均来自托管目录而非旧绝对路径。清单保留空目录、普通文件长度/模式，以及同一沙箱内的相对符号链接；不跟随链接遍历。拒绝附件链接、沙箱根链接、跨沙箱/绝对链接和设备/FIFO 等特殊文件。不下载历史远程头像或 data URI，它们仅随原始数据库字段保留。选中的本地附件、头像、候选 AttachmentRef 或已登记沙箱目录缺失则导出失败，不能静默号称完整。

## 选项定义

- includeConfig：角色/Persona/世界书/助手/模型预设/设置。
- includeMemory：正式记忆、总结记录、MVU/KG、工作日志与正文投影/提取队列。
- includeDatabase：其余会话/消息/助手历史、任务/审批/变化/用量/导入记录等。id_maps/source_blobs 在 includeDatabase 或 includeConfig 时保留。
- includeAttachments：普通托管附件文件。attachments 表在 includeDatabase 或 includeAttachments 时保留。
- includeSandboxUserFiles：沙箱用户目录文件与空目录/相对链接。sandboxes 表在 includeDatabase 或此选项时保留。
- includeCredentials：默认 false；true 必须另外提供 12..1024 字符密码。只把 CredentialStore 的快照放入独立 `credentials/encrypted.json`，不在任务 payload、清单或普通配置 JSON 中存密钥/密码。
- includeRebuildableRuntimes：当前 true 仍报 Unsupported；PRoot/loader 和 Alpine base 已由 APK 携带，但没有 portable runtime export。includeSandboxUserFiles 包含已初始化的 guest 文件/依赖，目标仍需兼容 arm64 APK；manifest 记录 SDK/ABI、proot-alpine/runtime 版本、bundledRootfs=true 和 shellIsolation=false。实际设备/动态链接/完整恢复兼容未验证，见 SANDBOX.md#sandbox-runtime。

关闭部分选项会留下包外引用；manifest 有 omittedTables 与说明，不把选项包当完整迁移。恢复须按明确选择处理这些引用，不猜测创建内容。

凭据使用 AES-256-GCM、随机 16 字节 salt/12 字节 nonce、PBKDF2WithHmacSHA256（选定 600000 次）和固定格式 AAD；恢复原语限制版本、参数与大小，并在密码错误时失败。采用平台密码派生与加密保护，不是额外内容哈希/校验功能。算法依据 [Android Cryptography](https://developer.android.com/privacy-and-security/cryptography) 核对，不指定非 Keystore 提供商。密码在 worker 中只保留独立 CharArray，结束清空；调用方负责自己的原数组。

PBKDF2WithHmacSHA256 的平台支持从 API 26 开始，匹配本工程 minSdk=26，依据 [SecretKeyFactory 算法清单](https://developer.android.com/reference/javax/crypto/SecretKeyFactory)。这不是密码解密或设备运行已经验证的声明。

## 一致性与任务

startBackup 注册 TaskStore/前台宿主，worker 在 core scope 持有；取消 API 等待者不取消已接管任务。stopTask 才停止，通知同样只停该任务，不重启或重放旧操作。

同一实例只有一个备份 worker。数据 gate 的普通写 lease 可并发；备份尝试独占，存在其他任务或进行中的文件/导入/门面写操作时失败，不擅自停其他任务，不复制热库。持有期间新门面写操作/任务登记/沙箱修改报 Conflict，不排队重放。数据库表导出在一个 Room transaction 中完成；托管文件/凭据复制在相同 gate 中完成。UI 必须使用 core 门面，不能调用公开历史 repository/paths 字段绕过约定。任意未隔离 shell/故意脱离进程仍可能竞争文件，不宣称强运行时隔离。

数据库阶段按 rowid 分页 200，长文本每列 CAST AS BLOB 后 substr 65536 字节读取并严格解码 UTF-8，避免整条大行进入 CursorWindow，也避免 length(TEXT)/substr(TEXT) 遇内嵌 NUL 提前结束截断正文。数据库阶段实时进度仅内存 Flow，避免回调重入 Room 快照；阶段边界与文件复制每 500ms 保存 TaskStore payload，保留 STOPPING/第一停止原因。用户停止和平台中断先信号取消快照再等待停止写事务，防止长读事务阻塞取消。进程重启标中断，不继续导出。

先输出同目标目录的专属临时 ZIP，完成 finish/flush/fsync 后才发布。默认 overwrite=false 使用同文件系统硬链接原子发布，不覆盖既有目标；不支持硬链接的外部文件系统会失败，需先导出到 app 的 backups 目录再分享/复制，不能静默降级。显式 overwrite=true 通过 rename 替换用户选定 ZIP。禁止写入 app 活数据目录或 operation staging。小型发布日志原子写入，启动仅清理可识别的自有临时文件/表 staging，保留已发布 ZIP；不做递归删除外部目录。极端崩溃在创建零字节临时文件与记录 created=true 之间，可能留下零字节临时文件；不删除不能证明归属的已有路径。

预算：最多 2000000 行、每 TEXT 字段 8000000 个 SQLite 字符、每 JSONL 行 32000000 UTF-8 字节；最多 200000 文件清单项、manifest 16000000 字节、全包不压缩数据 8 GiB。超过明确失败，不截断数据当成功。需要工作空间保存表 staging 与压缩 ZIP；低磁盘/IO 错误失败并清理自有部分。

## 预检与剩余恢复工作

preflightNativeBackup 只读 ZIP：验证根格式/版本/schema、声明选项与实际表/列、路径/重复/额外条目、文件大小/父目录/相对链接、凭据包与容量。它不声称已检查全部 JSONL 行、引用/冲突、密码或恢复结果。

旧无 format/schema 的部分 BACKUP_META 包明确 Unsupported，不猜成完整包。来源导入优先识别 native marker，并在解压前拒绝；不能因沙箱用户文件里恰有 rikka_hub.db 就误走 Rikka 导入。

## 内容与冲突检查（第十五批）

`inspectNativeRestore(archive, password?)` 在数据 gate 独占、没有活动任务时执行；它是调用方拥有的只读检查，不是前台恢复任务。取消调用会取消检查，finally 关闭候选库、清理自有临时文件并释放 gate。先将输入归档有界复制到专属 `backups/restore_inspections/<id>/archive.zip`，只接受普通非叶链接源文件、核对打开后的文件身份/长度/元数据；压缩输入最多 8 GiB + 512 MiB。系统 shell 不隔离，元数据不是对恶意并发写入的内容证明。

候选记录存入独立 SQLite 文件，DDL 仅从已安装 v2 schema 读取，不执行包内 SQL。不把百万行堆在内存；行输入在分配前限制 32000000 字节，实际解压字节/行数必须与条目/manifest 相符。严格 UTF-8、JSON 重复键/128 层嵌套/Unicode 孤立 surrogate、精确列集合、标量类型、NOT NULL/主键、整型范围/布尔/索引/版本/大小检查；主键字符串至多 4096 UTF-16 单元且不含 NUL。TEXT 按原 8000000 字符预算；普通正文中的 NUL 可保留。重复主键、同节点重复候选序号、同会话重复节点 order 和重复 usage event 拒绝。

在一个 Room 读事务中逐列检查本地同主键记录：缺失记 insert、全部值相同记 identical、不同记 ROW_CONFLICT；没有覆盖/清空/跳过写入。检查候选与节点/会话、父节点顺序、选中索引与连续候选序列（每节点至多 100000，含已有节点接收新候选）、角色/Persona/成员/预设/世界书、消息锚点与选择、正式记忆/MVU/KG/regex 源引用和图内关联、助手、沙箱 rootRelativePath、typed namespace 与 usage 唯一键。对未知 opaque 来源/工具/诊断 JSON 不推断可执行含义，完整桌面行为兼容审计仍未结束。

历史分叉/发送身份/用量/审批/变化/总结引用和已删除源对象的 ID map 可给 warning，保留 tombstone/baseline，不凭旧映射重新创建对象。legacy/无法解释的 typed namespace 只报告 warning，不删除旧映射。部分选项包缺少必需外部引用时给 blocking issue，不猜建数据。候选库内把旧非终态 tasks/summary jobs 改为 INTERRUPTED，待审批改 EXPIRED，running import batch 改 interrupted；仅候选数据变化，不登记或执行旧任务，不启动来源导入。正式 regex pending 是数据队列，不触发 worker。

文件清单逐项检查本地非链接父目录/类型/模式；所有普通包文件都实际分块读取，存在的同长度普通文件逐字节比较（不做哈希），符号链接只比较目标且不跟随。v1 导出和预检一致拒绝 setuid/setgid 或类型不符的模式，目录 sticky 位允许；不是运行时权限隔离证明。已存在目录合并保留未声明本地子项，不把未声明数据视为删除计划。附件/头像/候选文件引用和注册沙箱须在选中清单或未选文件时已有托管数据中可用；历史 remote/data URI 只保留。缺失文件、不同文件、链接父目录等明确报告。

含凭据但没给密码时报告 CREDENTIAL_PASSWORD_REQUIRED。提供密码后仅内存解密比较新增/相同/冲突数量，不返回凭据值/ID、不写文件/Room；最多 20000 个、明文 2000000 字节、ID 至多 4096 单元。密码副本结束清空，调用方负责原数组。加密输入同样严格 JSON/UTF-8；旧 store 和 Keystore 不变。

NativeRestoreInspection 返回各表 insert/identical/conflicting、文件/凭据计数、旧任务终止计数、blocking/warning 总数与最多前 200 项（编码累计最多 1000000 字节），issuesTruncated 标明截断；问题不含记录正文或秘密。结果不是可复用提交 token，提交必须再次独占检查。启动只清理精确白名单检查临时 ZIP/候选库与其 sidecars，不续接检查、不递归删活数据。

平台 SQLite 事务具有当前线程归属，候选库写事务固定专属线程；依据 [SQLiteDatabase API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase) 核对，与 Room 的 coroutine transaction 不混用。未新增依赖、表迁移或 hash/test 功能。

## 无冲突追加恢复（第十六批）

`startNativeRestore(archive, password?)` 注册新的 RESTORE 任务并确认前台宿主；`getNativeRestoreProgress/observeNativeRestoreProgress` 查持久进度，`restoreNativeBackup` 等待同一 worker。取消等待者不取消 worker，停止显式 stopTask。第十六批只提供“插入缺失记录、复用完全相同记录/文件、保留未声明本地数据”模式；不同本地行/文件/权限/凭据给 Conflict，不覆盖。该默认模式在第十七批继续保留，显式覆盖定义如下。

持有同一数据 gate，从自有归档副本建立候选库并做全部现有检查。当前恢复 task/marker ID 不得出现在迁入 tasks/import_batches，且不得有其他活动任务或未处理凭据撤销记录。阶段为 input/inspection/staging_files/publishing_files/credentials/database/done；文件阶段 500ms 检查点，Room 提交阶段不回调重入写进度。候选内容和旧任务终止规则不变，迁入的终止任务永不执行。

1. 所有缺失文件/目录/链接先在 `backups/restores/<taskId>/objects/<index>` 准备；文件 flush/fsync，记录目标、类型/模式、dev/inode/大小的 files.jsonl，最多 200000 项、每项 65536 字节、总 64000000 字节。日志与 staging 各级父目录 fsync 后才有活目录副作用。只准备缺失项，已存在目录不替换/剪枝。新目录先保持 0700，子项发布后才设最终模式。
2. 普通文件/符号链接从自有 inode 硬链接原子无覆盖发布；Linux 不跟随 link 的源 symlink，依据 [link(2)](https://www.man7.org/linux/man-pages/man2/link.2.html)。目录从自有准备目录 rename，发布前再检查目标不存在；gate 阻止正常并发写入，但不能排除未隔离 shell 在检查与 rename 间竞争。每次有限发布 NonCancellable，父目录 fsync；支持的公开接口依据 [Android Os](https://developer.android.com/reference/android/system/Os)，不使用隐藏 unlink/rmdir/O_DIRECTORY。文件系统不支持硬链接或 fsync 时明确失败，不降级为非原子覆盖。
3. 含凭据时将选中 before/after 写进独立 EncryptedSharedPreferences `st_restore_undo`，使用应用 MasterKey 加密，record 编码最多 4500000 字节、20000 ID；before 的 null 显式保存。不在 Room、JSONL 或 ZIP 写明文秘密。加密撤销 .commit 和 shared_prefs 父目录 fsync 后才写 live CredentialStore；凭据冲突拒绝。撤销只接受仍匹配本次 before/after 的值，否则留记录报 Conflict，不擦未知写入。
4. 文件发布/凭据应用后再次内容检查；Room transaction 内再验证全部行/引用，再插入缺失行。所有用户行、`import_batches` 中 source=native_restore/status=done 的提交标记、当前 RESTORE 的 SUCCEEDED/COMPLETED 和 committed=true payload 是同一个事务。Marker report 使用现有 ImportReport 类型，不新增业务表/migration。不同现有行从不 UPDATE/REPLACE；正式版本、typed maps/baselines、queue、附件/沙箱 ID 均不重新生成。成功只能来自这个事务，worker 的 finally 不凭内存布尔伪造成功。

取消/异常及进程启动都查询 Room marker，不猜测旧进度。无 marker：按倒序移除仍匹配本次 dev/inode 的新条目，空目录单项删除，撤销凭据；有 marker：绝不撤销已提交数据，只清理私有临时文件/加密撤销记录。未发布意图不存在即跳过；目录非空、父链接/身份变化或 IO 失败保留日志并报“恢复需要处理”，启动也不开放后续门面写入。不会递归删除用户树、删除相同复用文件、重发网络或重启工具。已提交但清理失败的任务保持 SUCCEEDED，phase=done_cleanup_pending，下次启动只清理；提交后的停止不会回退成功数据。

未提交恢复的撤销或私有清理不完整时，数据 gate 锁住本进程所有正常写/新任务/备份/恢复，防止后续恢复复用仍待撤销的文件或 inode。已经初始化的本进程可查持久报告；重启须先完成恢复处理，失败时初始化不放行。已提交的私有清理失败不撤销用户数据。屏障不是自动重试旧执行，也不提供清除日志/强行放行入口。

RESTORE worker 保留宿主 handoff 至清理结束，pending handoff 也参与有界 CPU wake lock，避免成功行先提交导致宿主提早闲置。宿主中断/通知停止先取消 Room 操作再等 STOPPING 写事务，保留第一停止原因；数据库已提交则成功优先。恢复等待与宿主/CPU 行为只编译，未真机证明。

## 显式包内覆盖（第十七批）

检查/启动/等待恢复 API 均追加 `mode: NativeRestoreMode = ADD_MISSING`，不改变 password 的原位置。调用方明确传 `REPLACE_INCLUDED` 才允许同主键行、同托管路径叶文件/链接、包内同 ID 凭据替换；任务进度和检查报告保存 mode，不因冲突自动改模式。desktop/Rikka 来源导入不使用此参数，继续自动合并并存。

- 未声明的行、凭据、目录子项一律保留，不清空库、不删除整个目录。已有目录只能调整模式并合并子项；目录与叶文件互换仍阻断，避免丢掉未声明子树。特殊文件/链接父目录、缺引用、跨会话节点/候选/regex 身份及跨助手消息身份仍是 blocking，不能靠覆盖模式绕过。
- ROW_CONFLICT/可替换 FILE_CONFLICT/CREDENTIAL_CONFLICT 计数仍保留，显式模式仅将可授权差异变为 warning。发布后检查必须证明每项文件已存在且字节/模式/链接目标一致、每项凭据已存在且值一致；新的差异或丢失阻断，不再作为“允许覆盖”略过。
- Room 单事务先复查有效数据视图，再 INSERT 缺失行、UPDATE 不同的同主键行，相同行不写；不用 SQLite REPLACE 的删除语义。用户行、真实 marker、任务成功仍同事务。rows 统计实际插入与更新行数。失败由 SQLite 回滚旧行，不生成随机新 ID、不改变正式版本/来源映射/baseline，不自动启动队列或旧执行。
- 包外保留节点的父节点顺序、保留会话/记忆的消息锚点和仍有效提取记录的正文/顺序也检查。会使原有效关联失效时报 PRESERVED_NODE_PARENT_CHANGED / PRESERVED_MESSAGE_ANCHOR_CHANGED / PRESERVED_REGEX_SOURCE_CHANGED；不暗中改包外锚点、消费提取或重建记忆。包内 stale 提取的旧源序号不一致只警告，不把它当有效队列。此检查不是完整状态语义/行为兼容证明，唯一 eventId 与连续候选等约束仍不得冲掉包外记录。
- 每个待覆盖叶项保留 `undo/<index>` 的原 inode 硬链接，并为旧普通文件另保存 `premises/<index>` 的完整独立字节副本，链接仅记录原目标、不跟随。旧字节复制后 fsync；不以 inode/长度相同代替全字节前提，也不使用哈希。准备/发布/撤销复查原身份、类型/权限和原字节或链接目标，未知变更留日志报错。创建 undo 硬链接会改变 ctime，稳定性以建链后快照检查；不承诺恢复原 ctime/mtime。
- files.jsonl 新增 create/replace_leaf/directory_mode 和 before 描述，仍兼容十六批旧日志默认 create。日志及 objects/undo/premises/祖先目录同步后才发布路径/权限。覆盖叶项用 rename 原子替换；新叶项继续硬链接无覆盖。目录权限先暂设 0700，子项完成后恢复包内模式（未声明模式时保留旧模式）；可复用但不可写目录也先记录撤销意图。
- 未提交时倒序删除本次新 inode、将原 inode rename/无覆盖 link 回原路径、恢复原目录权限；不会递归擦树或删除未知身份。完整文件撤销后原子发布并同步 `file_undo.done`；后续若私有清理中断，下次只继续清理，避免副本已删后再次撤销。清理先移除副本/日志并同步，再移除完成标记。凭据使用已有加密 before/after，显式模式允许非空旧值，但未知后续值仍拒绝擦除。恢复不完整的 gate 屏障不变。

文件 staging 的新文件总字节与旧完整前提各至多 8 GiB，progress.bytes 为两者累计（至多 16 GiB），另需 ZIP/候选 SQLite/保留旧 inode 的空间；未够空间明确失败并走撤销，不裁剪后成功。文件须可读取/比较；完整权限、文件系统与设备 IO 支持仍需验收。

## 组合链接与稳定旧生命周期（第十八批）

NativeBackupLinkResolver 逐段走路径，遇 symlink 先展开目标、再处理后续 '..'，不以预先 normalize 后仍在根内代替组合图检查。例如沙箱根内 `a -> b/..`、`b -> .` 单独 normalize 都在根内，但展开 b 后 a 的 '..' 实际走出沙箱，明确拒绝。依据 [Linux path_resolution](https://www.man7.org/linux/man-pages/man7/path_resolution.7.html) 核对目录查找/链接展开与 40 次整体解析上限；这是逻辑图检查，不是已证明的 Android 文件系统隔离。

StoragePaths 对框架给出的 app data 前缀规范一次，避免把 `/data/user/0` 等系统别名误判为用户托管链接；files/st 和各托管根仍逐项 lstat 验证非链接目录，不放行重定向的附件/沙箱根。仅路径表示规范，既有相对路径和实际数据不迁移；框架可建别名见 [AOSP Zygote 变更](https://android.googlesource.com/platform/frameworks/base/+/46c7d694aa8f6a2cb08064473c94acd8603339c6%5E2..46c7d694aa8f6a2cb08064473c94acd8603339c6/)。不同 Android/多用户/工作资料与挂载仍须实际设备验证。

导出完成文件清单后、结构预检完成声明校验后检查包内图。恢复内容检查使用 incoming 优先+保留本地类型/目标的有效图，并扫描受影响 sandbox 的已有非链接目录，包含未声明但可能被新目录/链接激活的保留链接。绝不实际跟随链接打开目标；任何图路径走出原沙箱都 blocking（含原本已越界的保留链接，不偷偷修复或删除它）。未涉及的其他沙箱不扫描。悬空、目标中途非目录、循环/超整体解析上限只警告，仍保留原元数据，不以可达性为由删链接。发布后再查当前图；不能抵御之后逃逸 shell 或 mount/链接竞争。

本地扫描至多 200000 项，依赖缓存至多 200000 项，单次 pending 分量至多 16384/总处理 65536；超预算失败，不把部分图当全部。文件、实际权限和主机运行仍有既定独立检查，拓扑 warning 不是文件可用性或强隔离保证。

manifest.createdAt 必须为 Instant。候选非终态 tasks/summary、pending approvals、running import_batches 的终止时间取包内快照与原 createdAt/updatedAt 的最大时间，使用确定规范时间字符串；不再取本次检查的墙钟时间。每次报告 inspectedAt 仍是真实检查时间。重复同包不会只因旧生命周期日期变化制造 ROW_CONFLICT；新增当前 RESTORE task/marker 仍是独立日志，整个数据库并非字节不变。终态记录原样保留，不续跑旧任务、消费队列或发网络。早期按墙钟终止过的同 ID 行可能与新规则不同，默认仍报告差异，不自动改旧行；需要明确选择覆盖。

边界：默认追加仍可能因全局设置差异拒绝，可明确选择包内覆盖，但这不是无条件清空替换或全部恢复验收。恢复期间 gate 不能隔离逃逸系统 shell 或公开历史 repository 绕用；目录 rename 竞争、组合图与真实挂载/文件边界仍在完整沙箱/恢复审计范围。文件日志不是哈希或身份鉴权，安全依赖正常 app 私有存储。Room/WAL、EncryptedSharedPreferences、各设备 fsync 与真正断电持久性尚未证明；marker 判定代码不能替代这些运行证据。不声称跨介质强事务或恶意进程隔离。

下一步仍须完成：所有引用/组合链接/配置/Room 大行、版本/CAS 与恢复幂等性和任务异常分支审计、设备恢复/升级/低磁盘/中断/断电证据、可重建运行时及完整后端其余范围。普通编译/代码核对不等于正式还原验收；没有哈希校验、冒烟或单测，也没有执行实际恢复。
