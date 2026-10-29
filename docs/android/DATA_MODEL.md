# 数据需求、映射与决策

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="data-requirements"></a>

## Android 数据需求与存储选型基线

日期：2026-09-30（Asia/Hong_Kong）
状态：Kotlin + Compose、纯 Kotlin 业务核心、SQLite + Room 已选定；第一版必须具备可用记忆与完整多沙箱；禁止外部目录挂载，仅手工导入外部文件；工具支持全自动或分类逐项审批。2026-10-01 混合来源导入已进入实现并通过编译，真实包运行仍待验证，详见 STATUS.md。
关联聊天：`01a0efec-5a8b-7460-bfbb-f9521977d136`。

团队执行入口：[STATUS.md](STATUS.md)。最新用户决定优先于早期调查及旧版措辞。

### 1. 用户已明确的约束

- 暂停 Demo 的继续修改。前端细节等第一版实际使用后迭代。
- 本轮交给 Cursor 团队的工作仅限 Android 业务核心、存储迁移、记忆、沙箱、工具与后台宿主；不开发或修改任何前端，包括原生 Compose 页面、审批界面及现有 Demo。界面和实际渲染验收留给后续 Codex 阶段，底层仍需交付完整可调用接口与运行证据。
- 现有电脑端数据必须能够无缝导入 Android App；现有内置聊天助手的工作区文件暂列迁移待办，不属于当前导入验收范围。
- Android 新特性可以扩展数据，电脑端不必立即兼容这些新数据。
- 电脑端继续用 JSON 保存大部分数据；Android 可以采用 SQLite 等技术，性能优先。
- 第一版必须支持 RikkaHub 数据导入，使用户可以尽快导入已有内容并开始聊天。
- 用户使用官方 RikkaHub；具体版本待补充。调查样本版本不代表用户正在使用的版本。
- 现有桌面工作区是内置聊天助手 agent 使用的区域。
- Android 第一版必须具备多个隔离沙箱，每个沙箱自带完整 shell 和文件读取、写入、修改工具；桌面端后续补齐此功能。不得将完整多沙箱延期到第一版之后。
- 最新决定撤销此前外部目录挂载方向：禁止挂载外部目录，只允许用户手工导入所需外部文件，复制到指定沙箱或附件存储后使用；不持续链接、同步或回写外部源文件。
- 记忆是第一版必备核心功能：既有记忆必须能导入、查看、修改、继续参与上下文，并支持后续更新。不能以只保留字段、显示静态内容或暂缓实现代替可用记忆；完整迁移目标还包括自动总结、记忆锚点、MVU 状态和知识图谱的实际运行链路。
- 工具执行支持全自动，或分别指定 shell、写入、修改需要审批；需要审批的每个调用均排队由用户逐项确认，不能批量代批或自动放行。具体默认值与其他细节交由实施团队决定并记录。
- Android 采用原生方案；当前不追求 PC 与 Android 跨端统一体验，暂不考虑 iOS。Flutter 跨平台界面、Vue + Capacitor/Tauri 的 WebView 界面不再作为当前候选。
- Android 业务核心不复用现有 Python，不采用 Chaquopy/Python 嵌入核心路线。
- 用户接受推荐选型：Kotlin + Jetpack Compose 原生界面、纯 Kotlin 业务核心、SQLite + Room 结构化存储。不再继续并列比较 Java、ObjectBox 等主方案；底层模块仅在有具体需求时评估 Rust/C++。
- 沙箱隔离目标已确定：文件与运行环境独立，普通未 root 手机可用；不要求接近 Docker 的进程、网络和资源系统级强隔离。
- 用户认可会话与沙箱关联方向：沙箱独立存在，可跨会话复用；会话选择当前沙箱并可切换，普通聊天可不挂沙箱。
- shell 的工作目录不等于文件访问边界：模型不能通过离开工作目录访问其他沙箱。文件访问范围限于自身运行环境和已导入该沙箱的文件；隔离机制需要验证，不能仅依赖当前目录或提示词限制。

#### 原生方向与剩余选型

原生 Android 方向及 Kotlin + Jetpack Compose 已选定。围绕 Android 的交互、文件访问和任务生命周期设计，现有 Vue 界面不作为 Android 的界面实现。桌面数据兼容继续成立，桌面沙箱功能补齐仍是后续待办，不要求现在统一界面或同步迁移桌面架构。

Python 复用路线已由用户排除。现有 Python 源码可以作为业务行为的参考，Android 使用 Kotlin 重新实现对应规则与导入适配。SQLite + Room 已选定；shell 运行环境和沙箱实现仍需单独确定。上述选型决定不代表已完成构建、运行或性能验证。

用户报告：在骁龙 8 Elite 实机使用 RikkaHub 时，上万字符的流式思考内容会明显降低当前界面帧率；切换会话、后台接收但不显示时性能恢复。这是用户体验观察，尚未取得实机性能追踪数据，不能归因为 Compose 的固有限制。长思考的流式显示、历史滚动与会话切换应在原生方案中提前验证；接收消息与界面刷新分离、刷新合并、稳定内容缓存及消息内部按可见范围处理列为设计重点。

用户另报告：RikkaHub 的模型可使用 shell 离开沙箱工作目录并看到其他沙箱内容，是否可访问软件之外的目录尚不清楚。该现象尚未在本项目中复现，不作为已确认的上游漏洞。Android 新沙箱必须验证工作目录与环境根目录的文件访问边界；主程序私有数据及其他环境不应因同属 App 就暴露给 shell。PRoot 或目录映射方案不能仅凭改变路径就被认定已达到此要求。外部文件只允许手工导入副本。

#### 已确定的后台与并发行为

用户已确认下列期望行为；并非已实现或真机验证的运行保证：

- 生成与 shell 任务：切换会话、切后台、锁屏后继续，提供通知中的停止入口；安卓系统和厂商策略下的可靠性须真机验证。
- 会话并发：不同会话可并行，同一会话一次只运行一个生成任务。资源并发上限由后续测量决定。
- 沙箱并发：同一沙箱也允许多个任务同时执行，包括来自多个会话的模型 shell 与文件操作；不得默认改为沙箱级串行队列。每个任务分别管理进程、工作目录、环境变量、输出与取消状态，文件系统由该沙箱内的任务共享。文件写入冲突仍需设计，不能通过禁止同沙箱并发替代这一需求；不同沙箱之间不建立共享目录挂载。

任务需要持有确定的会话与沙箱引用；用户切换当前会话或沙箱不能把已运行任务重定向到另一个环境。“后台继续”不表示进程退出后也能维持原连接；进程退出后的处理按下节约定执行。

#### 已确定的停止、中断与文件改动展示

- 用户手动停止：保留已生成并保存的内容及任务日志，标记“已停止”。
- 应用意外退出或进程被杀：保留已保存的消息和任务日志，重新打开后标记未完成任务“已中断”。不得将已持久化部分冒充完整结果。
- 已执行的文件修改保留，不自动回滚，也不自动重放 shell 命令或续跑旧任务。
- 针对停止或中断任务，不提供重试、恢复或继续执行按钮。是否继续、从哪里开始，由用户下一回合提示词决定；下一回合按新的任务执行，不隐式恢复旧命令队列。
- 用户能够查看本轮涉及的文件改动，包括文件路径以及新增、修改、删除、重命名等变化类型。此要求同时覆盖专用文件工具与 shell 产生的文件变化，不能仅显示模型声称修改了哪些文件。
- 任务状态、已记录的执行结果与文件改动摘要可供下一回合查看，使模型根据用户提示词及实际文件状态决定下一步。中断导致的未确认执行结果或不完整改动记录需要明确标识，不能假定操作成功或文件未变。

文件改动的采集与持久化机制尚待设计。同一沙箱允许并发任务，改动归属需要区分可确认的任务记录与共享环境的实际变化，不能把其他任务的改动无依据地归到当前任务。该展示要求不意味着每个 shell 操作均可自动撤销。

#### 已确定的工具审批与文件导入

- 全自动模式：在已定义的工具权限和沙箱边界内自动执行。
- 自定义模式：shell、写入、修改分别选择自动或需要审批；文件删除、重命名、覆盖等变更归入明确的变更类别，不得成为未受策略约束的入口。
- 每个需审批的调用显示所属会话、沙箱、工具、命令或变更内容，逐项批准或拒绝；未批准之前不得启动调用或产生其副作用。不以“同意本轮全部”替代逐项确认。
- 审批绑定该次具体调用。参数、目标或编辑依据变化时重新确认；重复点击不能重复执行。同一会话按顺序展示待审批项，其他会话仍可推进；审批排队不等于整个沙箱串行运行。
- 后台遇到需审批调用时进入等待状态，通知可引导回 App；停止或进程退出后不自动执行待审批项。已停止或中断任务仍由下一回合新提示词决定后续。
- 文件导入是用户主动选择文件并复制；导入后源文件变化不自动同步，沙箱内修改不回写源文件。不得用持久目录授权、符号链接或后台同步变相实现外部挂载。

这里的兼容义务是“读取既有桌面数据”，不是双向实时同步，也没有要求两个平台的物理存储格式一致。双向回写、同步冲突解决、让桌面立即支持 Android 新功能均未列为第一版要求。

#### 工作区与沙箱的范围澄清

用户补充：桌面现有工作区服务于内置聊天助手，不具备上述多沙箱模型。Android 多沙箱是新增能力，后续再补齐桌面端。不能把迁移现有目录视为已经实现沙箱。

现有桌面工作区文件已由 DesktopImport.importWorkspace 导入：文本转为独立助手工作区文件，ingest/二进制保留为托管资产，工作区助手历史独立转换。路径、文件类型和大小等不适用项逐条写入导入报告。迁移目录不代表实现桌面端多沙箱。

### 2. 当前已核实的事实

#### SimpleTavern 桌面数据

2026-10-29 更新：`backup_settings(scope="with_chats")` 打包设置、Persona 与角色头像、角色、世界书、`chats/` 下的全部普通文件（含图片、附件、记忆与 KG）、助手设置与全局历史、`ai_workspace/` 和 `usage/`（含 JSONL）。`basic` 仍只导出设置与 Persona 头像；`with_characters` 不包含聊天、工作区或用量。

Android 用户选择 `with_chats` ZIP 即可迁移这些内容；旧版导出包已经缺失的媒体无法由导入器恢复，须用更新后的桌面端重新导出。API Key 不写入 Android 凭据库，迁移后单独配置。备份期间应避免继续修改桌面数据；ZIP 导出不是数据库级一致性快照。完整真实包和设备端验收仍待执行。

`backend/app/storage.py` 还定义了聊天图片、长期记忆、知识图谱、AI 工作区、Assistant 设置与历史、字体、页面背景等持久化路径；`backend/app/services/usage_ledger.py` 定义了用量 JSONL。应建立完整清单，分别确定结构化转换、原文件保留、明确延期项和可重建缓存，不以文件扩展名决定是否迁移。

`Chat` / `ChatMessage` 的现有字段包括稳定 ID、群聊成员、会话覆盖设置、分叉来源、推理内容、工具记录、生成用量和部分候选版本数据。迁移不能只提取消息正文。没有保存到磁盘的临时 UI 状态无法从数据包恢复。

#### 官方 RikkaHub

本轮核对官方标签 **2.5.5**，固定源码提交为 `6c903feba7e6b65f518b320a88ae29efebae77c4`。

- `BackupManager` 写入 `settings.json`；按导出选项加入数据库与文件。文件范围包含 upload、skills、fonts。数据库/文件没有勾选时，包里可能只有设置。
- `DatabaseBackup` 定义数据库归档名称为 `rikka_hub.db`。当前实现用 SQLite 快照导出；旧备份兼容涉及 WAL，不应假设所有包都只有单个数据库文件。
- 此标签的 Room schema 为 **25**；消息节点有独立表，节点内的消息仍是 JSON。会话表还保留 `nodes` 字段，导入器需按来源 schema 判断实际消息来源，避免读空字段或重复导入。
- `MessageNode` / `MessageNodeEntity` 包含节点 ID、消息候选列表、顺序与选中项。`Conversation` 还引用助手、会话提示词、模式注入、世界书与工作区等数据。

这些是源码检查结果，不是实际备份导入成功的证据。2026-10-01 已执行 Android 构建；当前没有用户备份样本，未进行真机验证。

### 3. 已选定的存储方案

**以 SQLite + Room 作为 Android 的主要结构化存储；JSON 保留为兼容输入、可移植导出和源数据保留格式。**

- 会话、消息节点、候选版本、当前选中版本、记忆及引用关系采用独立实体；长历史按窗口读取，单条编辑只更新相关记录。
- 高频筛选和排序字段使用列与索引。角色、世界书条目、提供商、模型和提示词等复杂配置可以组合结构化索引字段与 JSON 配置体，避免第一版过度拆表。
- 图片、音频和文档附件保存在 App 文件目录；数据库记录附件 ID、相对路径与来源关系，导入时重写源 App 的绝对路径或 URI。新增沙箱的根目录、手工导入文件、shell 运行环境和工具作用范围需要单独设计，不能直接沿用附件存储模型。来源路径仅供追溯，不作为持续访问外部文件的句柄。
- 保存未识别字段和原始配置，用于追溯和后续适配；正常聊天从转换后的实体读取。仅保留原文不等于已支持对应功能。
- 小型应用偏好可使用 DataStore；消息历史放入数据库。[Android DataStore 文档](https://developer.android.com/topic/libraries/architecture/datastore)说明了其适用边界。
- 使用 Room 管理 SQLite、查询与 schema 升级。[Android Room 文档](https://developer.android.com/training/data-storage/room)说明了其 SQLite 抽象和迁移支持。

**SQLite + Room、Kotlin 与 Compose 已选定，Python 核心复用已排除**。既有 `ANDROID_HOST_FEASIBILITY.md` 的 Python 共享核心路线是先前调查，已不属于当前候选。各模块应通过 repository 边界访问同一个存储服务，明确数据库写入和 schema 迁移的责任方。

先前比较已结束：Room、SQLDelight 在 Android 上仍使用 SQLite，不是独立的替代引擎；ObjectBox 和 Couchbase Lite 保留为调查参考，不属于当前实施方案。MMKV 等键值存储不承担完整会话数据库职责。

RikkaHub 备份输入仍需要按来源 schema 读取其 SQLite 数据库。输入格式与 App 自己的表结构不必一致。选定方案须验证消息分页、跨实体引用、中文检索、schema 升级、备份可检查性和真实设备读写表现，不以供应商宣传的通用速度排序代替测量。

性能优先意味着验证长历史分页、索引、增量保存、附件加载和导入峰值内存。不能仅凭换成数据库就宣称已经更快；数据库也不会自动解决消息渲染或上下文裁剪成本。

### 4. 两套导入适配器，一个 Android 数据模型

输入路径：

1. SimpleTavern 桌面数据包 → 桌面格式识别与字段转换。
2. 官方 RikkaHub 备份 → 备份版本识别、数据库查询、嵌套消息 JSON 和设置转换。
3. 两者进入同一套导入计划 → 引用与附件检查 → 暂存 → Android repository 提交。

导入源数据库只在暂存副本上读取或处理，不能直接替换 App 正在使用的数据库。App 自己的数据库 schema 应独立演进。

SimpleTavern 第一版迁移范围要求覆盖角色、Persona、世界书、所有持久化会话及消息、长期记忆、群聊成员与配置、会话覆盖、分叉关系、普通聊天附件，以及模型与提示词配置。Assistant 配置与历史、知识图谱、用量等持久化数据也须列入清单，不可静默遗漏；其可操作程度需与第一版功能逐项对照。现有内置聊天助手的工作区文件导入为明确延期项，不纳入当前验收。

RikkaHub 第一版优先打通“导入 → 找到原来的助手与会话 → 查看历史及附件 → 使用原模型配置继续聊天”。来源已有的候选版本与选中项必须保留。助手、提示词、模型参数、记忆、世界书等需建立明确映射；复杂工具或工作区能力如果尚未实现，保留数据并标注具体限制，不能冒充完整功能兼容。

重复导入采用来源命名空间、源实体 ID 与内容变化记录进行判定；同一份包重复导入不产生副本，源数据变化时不得覆盖用户在 App 中的新编辑。引用 ID 冲突时统一映射，避免混连角色、消息或助手。

数据库与附件需要统一的导入批次状态。失败后保留旧数据，重启可识别未完成批次；不能仅靠数据库事务声称文件也具有原子性。导入完成报告需区分已转换、已保留但不可用、缺失及失败的数据。

#### 4.1 用户补充：桌面与 RikkaHub 自动合并并存

2026-09-30 用户明确要求：来自 desktop 和 RikkaHub 的导入自动合并并存。用户将实际混用两种来源，按同一资料库的混合导入场景验收。以下为验收契约；2026-10-01 已实现第一批导入改动并通过编译，完成范围与剩余缺口见 STATUS.md，尚未完成真实包运行验收：

- 两种来源进入同一个 Android 资料库，先导桌面再导 RikkaHub、反向导入以及交替追加均可；已有 Android 本地内容继续保留，不清库、不切换成另一套独占资料库。
- 合并指自动整合资料集合和可用配置。同名角色、助手、会话或同文本消息不据此认定为同一实体；不同来源 ID 相同也分别映射，不把两段历史自动拼成一段会话。显示名称可以相同，来源身份与内部引用必须清楚。
- 同一来源、同一实体的重复导入去重；来源命名空间独立于导入批次、文件名和本次随机 ID。跨来源隔离与同来源幂等必须同时成立。无法可靠判定同源时保留不同来源记录并报告，不猜测覆盖本地内容。
- 各自的角色/助手、Persona、世界书、会话、候选、记忆、MVU/KG、模型预设和附件引用随所属数据一起保留；源 ID 冲突时一致重映射，不能把前一来源的消息、记忆或文件迁给后一来源。
- 模型/提供商/提示词配置应转换成可并存的独立记录；后导入的 `settings.json` 不得整体替换已有全局设置。已有全局选择和本地偏好保持不变；导入会话按自身关联配置解析。凭据是否迁移按单独的安全策略处理，不能通过合并原文绕过排除策略。
- 来源内容更新时保护 App 内新编辑；冲突保留可追溯记录并报告。导入取消、失败、重启不能破坏另一来源或本地已有内容。
- 正常混合导入无需用户手工改 ID、整理目录、选择保留哪个来源或逐项处理同名。报告提供每个来源的新增、已存在、冲突保留、缺失与失败数量，不能把自动并存实现成静默覆盖。

### 5. 第一版的验收依据

- 桌面：当前约定范围内的数据量和引用关系可核对，普通聊天图片与附件可打开，原有主要会话可以继续使用；缺失项必须明示。现有工作区文件导入单列待办；完成当前范围不能宣称所有桌面数据均已迁移。来源包缺少当前范围内的数据时，不报告完整迁移成功。
- RikkaHub：用用户实际版本的官方备份验证助手、会话、消息顺序、所有候选版本、选中项、提示词与模型引用；同时覆盖仅设置和完整备份两种情况。
- 重复导入同一包无重复；ID 冲突、缺附件、损坏数据、导入中断不会破坏已有内容。
- 桌面与 RikkaHub 混合导入自动合并并存：双向导入顺序及交替重复导入，均保留两个来源和本地内容；同名/同 ID 不误合并，消息、记忆、配置、附件不串用，后导入的设置不替换先前全局设置。
- 第一版记忆：导入的记忆实际参与下一轮上下文；用户可查看、编辑及更新记忆，自动总结与锚点行为可核对，重启后保留，独立会话不串用。MVU 与知识图谱迁移按团队执行文档完成运行闭环，不能仅保留原始 JSON。
- 第一版多沙箱：至少两个独立环境的文件、依赖与 shell 状态分别管理；每个环境均可使用完整 shell 和读写改工具，手工导入文件可访问，重启后持久数据仍在。验证离开工作目录、绝对路径与符号链接等情形下不能访问其他沙箱、外部目录或主程序私有业务数据；必要运行时文件的访问须单独记录边界。此验收不等同于进程、网络和资源的系统级强隔离。
- 工具审批：全自动与分类审批均实际执行；需审批调用逐项排队、未经批准无副作用、拒绝不执行、重复确认不重复执行、停止或中断后不继续消费旧审批。
- 停止与中断：保留已保存结果及实际文件修改，状态准确；用户能看到文件改动。重新打开后不重放命令、不自动续跑，也不出现中断任务的重试/恢复按钮；下一回合仅按用户新提示词启动新任务。
- 用合成的长历史验证打开会话、翻页、单条编辑保存、检索和导入内存。长思考流还应覆盖展开、折叠、后台接收、切换会话和用户向上阅读，记录帧耗时与主线程工作。记录设备、数据量与测量结果，再制定性能门槛。
- 至少完成一次真机“导入后继续聊天”的验证，才声称第一版达到可用状态。

### 6. 下一步基础工作

第一版范围已确定包含数据导入、可用聊天、可用记忆和完整多沙箱；完整执行目标见团队执行文档，不能只交付聊天后再补记忆或沙箱。基础工作按下列顺序推进：

1. 沙箱边界已确定为独立文件与运行环境、普通未 root 手机可用。基于此评估 App 内的 PRoot 与独立 rootfs 等运行方案；具体实现仍需验证现代 Android 下的可执行文件运行、进程管理和性能，尚未锁定或证明可用。
2. 会话与沙箱关系已获用户认可；禁止外部目录挂载，仅手工导入文件。细化文件复制及 agent 工具与用户手动 shell 的环境关系；必须验证 shell 文件访问边界，避免以当前目录冒充隔离。
3. 后台与锁屏继续、不同会话并行、同会话单生成及同沙箱多任务并行已确定。停止/中断后保留结果与文件改动，不提供重试入口，下一回合提示词决定后续操作；落实分类逐项审批、文件变化采集与同沙箱共享文件的冲突处理。
4. 建立桌面完整数据清单与 RikkaHub 字段映射，补齐用户 RikkaHub 版本；确定消息节点、候选版本、引用关系、附件、沙箱元数据、任务日志与文件改动记录的 Room 实体。
5. Cursor 在无前端条件下验证导入后带记忆生成、长思考接收与持久化、多沙箱 shell 与读写改工具及逐项审批，并继续完成团队执行文档中的完整底层目标。Codex 后续负责原生界面、长思考流式渲染与最终交互验收；纵向验证只是内部阶段，Demo 视觉迭代继续暂停。

#### 工作区与沙箱待办

- Android 第一版必备：设计并实现多个隔离沙箱、手工文件导入、每个沙箱的完整 shell 和读写改工具及分类审批；明确会话与沙箱的绑定、切换和持久化方式。禁止外部目录挂载。
- 数据迁移：设计现有内置聊天助手工作区文件的导入方式，届时决定导入后的归属与引用映射。
- 桌面补齐：Android 沙箱能力形成后，为桌面实现对应功能及新数据兼容。

### 源码与资料

- 本地：`backend/app/routes/import_export.py`、`backend/app/storage.py`、`backend/app/schemas.py`、`backend/app/services/usage_ledger.py`、`ANDROID_HOST_FEASIBILITY.md`。
- [RikkaHub 2.5.5 BackupManager](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt)
- [RikkaHub 2.5.5 DatabaseBackup](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/sync/DatabaseBackup.kt)
- [RikkaHub schema 25](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/schemas/me.rerere.rikkahub.data.db.AppDatabase/25.json)
- [RikkaHub Conversation / MessageNode](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)
- [RikkaHub MessageNodeEntity](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/db/entity/MessageNodeEntity.kt)
- [Compose 与 Kotlin](https://developer.android.com/develop/ui/compose/kotlin)、[Android NDK](https://developer.android.com/ndk/guides)、[.NET for Android](https://learn.microsoft.com/en-us/dotnet/android/)
- [ObjectBox 查询](https://docs.objectbox.io/queries)、[ObjectBox 关系](https://docs.objectbox.io/relations)、[Couchbase Lite](https://docs.couchbase.com/couchbase-lite/current/android/quickstart.html)、[LiteCore](https://github.com/couchbase/couchbase-lite-core)
- [SQLDelight](https://sqldelight.github.io/sqldelight/latest/)、[MMKV](https://github.com/Tencent/MMKV)

<a id="data-mapping"></a>

## DATA_MAPPING — 桌面 / RikkaHub → Android

2026-10-10 桌面候选思考：普通聊天与助手历史已有 greetingVariants → 节点候选映射，本批修正其 reasoning/duration 归属。存在逐候选数组时只读取本槽，缺槽或空值保持 null；数组缺失/null 时只有选中候选沿当前单值字段回退，不能把选中项的思考复制给其它候选。助手历史同时补上 reasoningDurationSec；逐槽耗时沿桌面正值清理，单值耗时保留有限非负数。候选 ID、选中索引、正文、媒体、原 JSON 和三方合并保护不改；尚未用真实来源包执行导入。

2026-10-10 模型连接覆盖：RikkaHub固定来源 Model.providerOverwrite 的普通三类与 Google Vertex IAM/Express 完整连接转换到原模型预设，普通提供商也共用canonical默认值/路径，保留稳定ID/三方并存/本地修改，不合并父连接字段。OpenAI实际生成路径可用；替代模型单独配置加密凭据，IAM需给实际模型预设配置服务账号，历史usage归属跟随来源声明，原数据保留。未知类型和含query的路径未适配，旧未转换记录须明确再导入；两个Luna实际编码本批，最终普通离线编译通过42秒，详见 [PROVIDERS.md#rikka-provider-override](PROVIDERS.md#rikka-provider-override)。

2026-10-10 RikkaHub 用量：带 usage 的历史助手候选进入原账本，四类 token 缺失保持未知、费用未知；同来源消息UUID去重，原字段脱敏保留，原三方合并保护本地修改/删除。模型只按备份引用映射，无时区时间按导入设备时区并在报告说明。不是完整请求/费用历史，不伪造本地生命周期；普通离线构建31秒通过，真实包未运行，见 [ACCOUNTING.md#usage-accounting](ACCOUNTING.md#usage-accounting)。

2026-10-10 桌面用量：`usage/*.jsonl` v1进入原usage_events和通用分页/汇总，event/request/chat/message按原来源映射、三方合并及本地保护；保存原历史费用，不按当前价格重算。JSON索引整体保留，repair队列/raw-only记录不计费或重放；缺usage目录明确报告，JSONL原文逐记录保留。两个Luna实际编码mapper和存储，主代理接原导入流/事务，无新schema，真实包未验证，见 [ACCOUNTING.md#usage-accounting](ACCOUNTING.md#usage-accounting)。

2026-10-09 ZIP预检：Android `preflightImport(Uri)` 已接SAF/管道输入并复用原只读预检结果 `source/entries/notes`；Luna本批将File预检限制为最多200,000个ZIP条目、逐项响应协程取消，保留首个settings JSON与native `BACKUP_META`检测，settings经32,000,000字节有界UTF-8读取后沿用Jsonx解析，非object验证失败。未增加递归schema/路径图审计；预检不证明整包可导入或全兼容，实际导入仍检查附件与引用并提示native走原生恢复。Desktop/Rikka自动合并并存、本地保护不变；SAF/管道及账号/设备未验收。

2026-10-08 正则：RikkaHub regexes 映射 Kotlin engine 与用户/助手范围、visualOnly、思考/全部上下文/开场控制，保存角色及助手 canonical 规则，原结构保留；导入旧聊天候选增已处理标记，不重写历史。角色规则字符串、配置与全局 canonical 库按 ID/字段三方合并，旧基线补同样默认、未知字段保留，删除/省略不清库。原 raw regexes 等其他数组仍整体合并。请求/预览接线与全部片段/版本限制见 [CHAT.md#content-regex](CHAT.md#content-regex)。

2026-10-07 记忆：数据库 MemoryEntity/memory 转 source_memory / memories 映射的 ImportedMemoryLibrary（AssistantConfig.settingsJson），来源行 ID 为键，assistantSourceId 保留原归属，__global__ 独立。enableMemory/useGlobalMemory 在请求时选池，缺表不清数据；明确再导入三方合并退旧聊天未修改副本。assistant/source_settings/source_memory 的 settingsJson 含旧基线展开后逐字段合并，数组仍整体；记忆按行/字段合并保留源删除/本地删除身份，不启动迁移，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 创建：RikkaHub AssistantConfig.settingsJson 增 typed chatDefaults（ChatCreationDefaults），复用导入控制转换并托管 presetMessages 的附件；已有导入聊天不追加开场。普通新聊天按角色精确来源映射继承默认，Desktop 使用同来源 default-connection，RikkaHub 使用对应助手配置。messageTemplate 同步进入新导入会话 overrides，按原时间只加工请求；明确再次导入沿原并存/本地保护，不启动迁移。settingsJson 后续已接文首的逐字段合并，详细契约及其他配置/native 余项见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 会话：RikkaHub2.5.5的allowConversationSystemPrompt、allowConversationPromptInjection与contextMessageLimit映射typed提示开关/character或session集合/阶梯裁剪；空会话集合不回落角色。Desktop/本地缺字段沿默认true/merge/null，旧基线默认一致，明确再导入保护本地修改，无启动改写。请求去掉80/200隐藏窗口，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 世界书：RikkaHub lorebook条目与modeInjection的扫描/常驻、priority、position、injectDepth与role进入typed entriesJson并实际注入，原结构保留extraJson。Desktop整书默认不变，entries按稳定ID逐字段三方合并，来源并存和本地删除保护不变。旧条目明确再导入，无启动迁移；全部跨版本会话覆盖/模板仍待补，见 [CHAT.md#worldbook-injections](CHAT.md#worldbook-injections)。

2026-10-07 当前补充：桌面workspace/ingest已转隔离文本/资产、独立历史与消息附件，草稿只合并不发布，旧整项deferred是历史状态，见 [ASSISTANT.md#assistant-workspace](ASSISTANT.md#assistant-workspace)。桌面sessionSystemPromptMode=override新导入映射为Android override_global，Rikka/本地override角色覆盖不改；knowledgeGraphBeforeLastRole进入typed overrides。旧数据不在启动转换，明确再导入仍按原三方并存保护，见 [CHAT.md#chat-context](CHAT.md#chat-context)。

2026-10-07 助手主线：desktop 全局/主会话侧 assistant_chat.json 转为独立助手会话与消息候选，typed binding 保存于 overridesJson，原原文保留；工作区/ingest 附件仍单独 deferred。desktop 助手 presetId 重映射，RikkaHub AssistantConfig 接已知 controls 与 chatModelId 的 model 预设，原角色映射及 ImportMerge 本地编辑保护不变。见 [ASSISTANT.md#assistant-chat](ASSISTANT.md#assistant-chat)。不在启动时迁移旧 assistant_messages/source_blobs。

第四十批接 desktop promptCache/旧 anthropicPromptCache、群成员 Fast 和 RikkaHub 已知 native 缓存/档位 customBodies，共享标准化与三方合并保护不变。冻结模型快照只查询，不写入用户配置或价格；缓存公开 key 不哈希、不从密钥派生。已映射与拒绝项见 [PROVIDERS.md#model-fast-cache](PROVIDERS.md#model-fast-cache)，旧数据不 startup 重写。

第三十九批接桌面 generated/overlay 离线目录合并（39/50 源条目、最终 50）、getProviderCatalog、canonical/legacyId/最长同 host/path 匹配、公开参数模板、同 origin protocolPaths、API-version 和运行时 query_key。标准完整端点不重复拼接，预览返目录 ID/实际鉴权/版本；原值和 query 编码密钥纳入脱敏。未知自定义连接不冒充目录；OAuth/Bedrock 变体仍 Unsupported。Room/format/依赖、来源合并并存/本地保护/中断不重发不变。详见 [目录与路由契约](PROVIDERS.md#provider-catalog-routing)，完整 models/prices/Fast/cache/OAuth/cloud 等及后端全清单继续。

第三十八批 RikkaHub 新导入助手 customHeaders 接 paramsJson，模型头由 selectedModel 参与覆盖；Desktop headers/customHeaders 按配置读取。敏感值移除但名字保留待补加密引用占位，不自动导入密钥、不静默绕开缺值。来源映射/三方合并/本地保护不变，旧数据不启动重写。静态 auth/有限 auto 和目录/云/OAuth 余项见 [PROVIDERS.md#provider-http-options](PROVIDERS.md#provider-http-options)。

第三十七批模型/会话已知生成字段别名与四协议原生控制接通，RikkaHub 模型/助手 customBodies 只转换明确生成字段及声明 REASONING 的已知档位，未知 body/header/连接覆盖保留并 deferred。原模型/助手配置脱敏保留、三方合并/本地修改保护不变；来源默认/维护模型按 source_settings 映射读取，不取首个助手、不覆盖 global。具体字段、固定参考版本和仍未适配项见 [PROVIDERS.md#provider-generation-params](PROVIDERS.md#provider-generation-params)，不在启动时重写已导入参数。

### 统一目标模型

Room 实体见 `core/data/.../Entities.kt`。消息为 `message_nodes` + `message_candidates`（候选与选中分离）。

2026-10-01 实现：`id_maps(namespace, sourceId) -> targetId` 的表结构不变，namespace 编码稳定来源及实体类别，sourceId 编码必要的父级作用域。默认来源为 `desktop` / `rikka_hub`，可选 libraryId 用于隔离同类型多资料库；批次 ID 不参与实体身份。消息与节点统一映射，缺可靠源 ID 的记录独立保留并报告。旧随机批次记录保留，不猜测合并。合并基线存入 `source_blobs`，详见 STATUS.md。

目标契约（2026-09-30 用户补充）：桌面与 RikkaHub 自动合并并存于同一资料库；使用独立于批次的稳定来源作用域及含实体类别/必要父级作用域的映射键。两来源同名或同 ID 不互相覆盖，同来源重复导入去重，本地编辑受保护。原始设置转换成可并存的模型/助手/提示词配置，不整体覆盖已有全局设置。详见 `DATA_MODEL.md#data-requirements` 第 4.1 节。

### 桌面 SimpleTavern

第二十六批新导入把 overrides.tts 的 presetId/preprocessPresetId、voiceByCharacterId/voiceByPersonaId 键重映射，音色/模型值不改，extraJson 仍保留来源表示；三方合并/本地修改保护不变。旧已导入配置不在启动时批量重写。TTS 来源启用开关按 source_settings/settings 映射读取，不混到独立助手/global，完整契约及 RikkaHub TTS 尚未适配范围见 [SPEECH.md#tts](SPEECH.md#tts)。

| 源 | 位置 | 目标 | 损失/备注 |
|----|------|------|-----------|
| settings | `settings.json` | 独立来源配置 / source_blobs | 剔除凭据字段，不替换全局设置 |
| presets | 设置内 apiPresets / llm 或 presets/ | model_presets | 会话 presetId 重映射；统一 ProviderConfig 解析待接线 |
| characters | `characters/*.json` | `characters` | 头像路径改相对引用 |
| personas | `personas/*.json` 或设置内 | `personas` | |
| world books | `world_books` / `worldbooks` | `world_books` | entries JSON 列 |
| chats | `chats/<character>/<id>/chat.json` 或旧版 JSON | `chats` + nodes/candidates | `greetingVariants` → 多候选，保留对应思考与选中 |
| long memory | `chat_memory.json`、overrides 或文本旁路文件 | `long_term_memories` | 本地编辑优先 |
| MVU | `stateVariables` | `mvu_states` | |
| KG | `knowledge_graph.json` | `knowledge_graphs` | |
| images | `chats/<id>/images` | `attachments` + filesDir | |
| assistant settings/history | 文件 | assistant / source_blobs | 历史可 retained |
| usage | `usage/*.jsonl` | usage_events + source_blobs | v1历史事件映射/冻结费用可查询；索引、修复队列及不支持记录仅保留，不重放，见USAGE_ACCOUNTING.md |
| AI workspace files | `ai_workspace/**` | **deferred** | 明确延期 |

旧「仅设置」ZIP：报告缺 chats/附件/记忆，不得称完整成功。

### 官方 RikkaHub（样本 2.5.5 / schema 25）

| 源 | 目标 | 备注 |
|----|------|------|
| `settings.json` | 独立来源配置、角色、助手、模型预设、世界书 | 剔除凭据；不替换全局设置 |
| `rikka_hub.db`（暂存副本只读） | chats/messages/记忆 | 检查实际列名；schema 25 使用 conversation_id/node_index/select_index |
| `nodes` 遗留字段 | nodes/candidates | 无独立节点表时转换 JSON |
| 助手候选 `usage` | usage_events + source_blobs | 原消息UUID去重，四类token/未知费用可查询；无时区时间解释及源请求账本限制见 [ACCOUNTING.md#usage-accounting](ACCOUNTING.md#usage-accounting) |
| upload/ | attachments | URI→相对路径 |
| assistant `Avatar.Image(url)` | characters.avatarRelativePath | 仅从 upload/文件名复制；远程/其它 URI 与 Emoji/Dummy 保留原配置，不获取或生成图片；见 [CHAT.md#avatars](CHAT.md#avatars) |
| skills/fonts | deferred 报告 | 尚未安装或保留文件，不执行包内脚本 |
| 凭据 | missing | 需界面重配 |

用户实际 RikkaHub 版本：**未验证**。

### 通用正确性

- 同包重复导入、混合来源并存、本地编辑保护与失败保留旧数据已进入实现并通过编译；未运行真实包，不标记验收通过。
- 批次使用 Room 事务和新附件独立目录，状态包含 `running/cancelled/failed/done/interrupted`；旧批次数据保留。原生备份恢复待实现，不计入本轮完成范围。
- Zip-slip 拒绝。

<a id="decisions"></a>

## Android 核心决策记录

2026-10-06 用户决定：已发现的内部重复全量验证、重复解析、假任务报价依赖和固定警告耦合，先写入 [STATUS.md](STATUS.md)，后续单独排查清理；现在不改实现或删校验，不阻断当前后端主线。后续须区分真实外部边界与已验证内部流转，不以“内部函数”为由一概取消保护。

第四十七批：账户缓存枚举每页都是新显式 LIST 任务，不自动翻页/重试；参数和连接保持一致、分页 token 仅运行时且登记后一次消费，恢复不续查。新 GEMINI_CACHE_LIST kind 在本地资源历史 SQL 排除，不把列表项当归属/孤儿证书，不计创建/存储费；完整归属/清理/索引/结算继续，见 [GEMINI_CACHE.md#gemini-cache-listing](GEMINI_CACHE.md#gemini-cache-listing)。

第四十六批：资源状态按持久因果前沿投影，不按时钟/UUID 猜最新，不新增哈希或重放索引。管理登记前沿并在初始事务再比较；修改/清理只用当前最新确认和完整私有连接，结果未知先明确 GET。只删除用户明确启动的新 DELETE 目标，不后台清理或改凭据执行旧操作。native 检查循环和部分包外已验证祖先；紧凑索引/孤儿全清理/存活结算仍未完成，见 [GEMINI_CACHE.md#gemini-cache-lifecycle](GEMINI_CACHE.md#gemini-cache-lifecycle)。

第四十五批：CREATE 价格独立于生成/缓存读取价，开始冻结且 user/catalog CAS 分开，仅明确线性固定费/token 创建费/token·小时存储率。缺失不是免费，歧义不选第一条，历史不重算。请求 TTL 预测单独存 projectedCost/汇总，不伪装 provider 扣款，GET/延时/删除不再算创建。实际区间与完整账单/价目维度仍需实现，见 [GEMINI_CACHE.md#gemini-cache-pricing](GEMINI_CACHE.md#gemini-cache-pricing)。

第四十四批：缓存操作使用独立 gemini_cache 事件，与 task 同事务提交/CAS，不混聊天 tokens 或以 TTL/模型参考价猜费用。新 tracked 记录缺一边拒绝，旧 task-only 不补账；恢复保留未知字段/冻结终态成本，epoch 不作为历史费用事实，部分覆盖保护包外原可核实配对/来源/父引用。详见 GEMINI_CACHE.md#gemini-cache-accounting。

第四十三批：显式缓存意图独立于 generationConfig，按新请求的冻结 owner/原凭据快照和原前缀逐值匹配；未命中新建独立记录化操作，不重建失败请求。整流 pin/gate 禁止引用使用期间管理，父取消等待子任务终态；没有稳定前缀时不创建，不当作上游错误降级。使用实际绑定解析用量，存储费另计，通用缓存价格/账目仍待补。详见 GEMINI_CACHE.md#gemini-cache-generation。

第四十二批：缓存操作复用任务 payload 保存有界公开证据，正文/密钥/私有绑定不持久化；发送前耐久记录失败不发 HTTP，旧操作启动/恢复只中断，跨 epoch 修改旧资源必须先明确新 GET。恢复只改生命周期字段、未知 JSON 保留；未知创建/存储费用不当零，通用账目和自动聊天缓存仍待补。详见 GEMINI_CACHE.md#gemini-cache-operations。

第四十批：模型目录为冻结只读参考，未知/跨提供商歧义不选模型，价格不自动覆盖用户规则；Fast 保持同模型，无重试/降级；实际服务档位缺失时未知费用。缓存 key 采用公开来源隔离 ID，不做哈希或密钥派生；明确 unsupported 不悄悄转换 TTL/断点。详细边界 PROVIDERS.md#model-fast-cache。

第三十九批接桌面 generated/overlay 离线目录合并（39/50 源条目、最终 50）、getProviderCatalog、canonical/legacyId/最长同 host/path 匹配、公开参数模板、同 origin protocolPaths、API-version 和运行时 query_key。标准完整端点不重复拼接，预览返目录 ID/实际鉴权/版本；原值和 query 编码密钥纳入脱敏。未知自定义连接不冒充目录；OAuth/Bedrock 变体仍 Unsupported。Room/format/依赖、来源合并并存/本地保护/中断不重发不变。详见 [目录与路由契约](PROVIDERS.md#provider-catalog-routing)，完整 models/prices/Fast/cache/OAuth/cloud 等及后端全清单继续。

第三十八批自定义 HTTP 头与 generation extra 分离；敏感值须引用加密存储，多凭据统一脱敏、不入账目。auto 纯选择、显式协议/模型不改、origin 不换、不 HTTP 探测/失败重发，未适配模板/OAuth 明确拒绝。来源敏感头保留缺值占位、本地保护不变，见 PROVIDERS.md#provider-http-options。

日期：2026-09-30

补充：2026-10-05。旧默认值为当时起点，当前状态以 STATUS.md / STATUS.md 为准，不能将未验证的目录围栏当作完整 shell 隔离。

### 当前补充决定

- 第三十七批生成控制按协议明确构造，不把任意源 body/headers 当请求扩展、非法数值不变成默认。支持字段在解析及 HTTP 前验证，失败不删字段重试或换模型。来源默认值必须按 source_settings 映射或唯一来源读取，禁止靠助手遍历顺序；RikkaHub 已知模型/助手控制转换依旧三方合并，未知项 retained/deferred，剩余自动协议/鉴权/缓存/全参数继续，见 PROVIDERS.md#provider-generation-params。

- 第三十六批恢复费用按历史请求 eventId 查找而非假定主键；分叉可在同来源共享原冻结费用，创建 task/candidate 不是当前分叉身份。包外原可核实引用只允许保留同一完整 usage/cost/来源证明，覆盖改坏整次拒绝；旧不明历史 warning，不造费用。生成活跃标记独立解析并只改 interrupted，保留 opaque/未知 JSON，不重新估价或续接。完整边界见 ACCOUNTING.md#generation-usage-restore。

- 第三十五批普通模型账目对应实际调用而不是业务成功提交，聊天/总结/状态维护逐请求 prepared/running/终态，结束先独立保存，再应用消息/记忆/双状态；事务回滚不抹供应商账目，旧补记批量路径移除，requestId 和活跃工具/原生 continuation 语义不改。开始冻结价，流/终态同一有界 metadata 与解析字符串查 key，未知不补零；native 可得创建关系/用途校验，缺历史只 warning、只中断不重发。所有真实上游/进程/数据库耐久性和完整后端仍继续，见 ACCOUNTING.md#token-request-accounting。

- 第三十四批预处理不再只在请求结束插入账本，改同一普通 token 行 prepared/running/终态与发送前回调；先提交再执行 HTTP，失败不发送，httpAttempted 不等于服务器接收/收费。完整旧 JSON/空 cost/身份 CAS 与本地赋值共短 NonCancellable；价格仍开始冻结。startup 仅规范新 unfinished、native 逻辑键归一化，均只 interrupted/unknown 而不重发；无标记旧记录历史保留。通用分页显式 lifecycle/坏关系未知，普通其他 LLM 尚未迁移，真实进程/磁盘/发票及整个后端继续，见 SPEECH.md#speech-preprocessing。

- 第三十三批 preprocessEnabled 是显式合成自动 LLM 阶段的授权，单独标签开关不能触发付费调用；冻结同来源双选择，不回退原稿/借其他凭据。缓存与绑定保存实际朗读稿，判断消息编辑仍用原正文。两阶段分账用 preprocessingRequestId 关联，成功处理后的停止/合成失败不抹 LLM 账目；native 保护旧可验证关系，不依据当前价格重算。整个串联 shared gate，短 Room 事务外网络，前置行/来源检查与提交复查不阻止编辑；不承诺所有失败在收费前。普通 LLM 最终记录的发送/进程死亡缺口保持明确，所有其他后端要求继续，见 SPEECH.md#speech-preprocessing。

- 第三十二批（历史起点，串联见第三十三批）预处理作为显式 LLM 调用返回独立稿，不改原文/立即合成，同来源预设、严格完整结果/不回退原文。token 费用与合成分开、开始冻结规则，供应商完成不因本地 JSON 失败被抹掉；最终记录模式仍非完整发送/崩溃账本。当时会话开关自动串联待补。源码确认 HTTP 库仅关闭连接重试不够，默认四类发送路径共用每 Call 防 follow-up 重发守卫，不把库重发当用户新请求/第二收费调用；无实际请求/测试。全边界见 SPEECH.md#speech-preprocessing。

- 第三十一批目录管理与合成分离，缺文本/音色/模型或未启用 TTS 不妨碍明确目录读取；合成禁用仍有效。默认离线，explicit fetch/源凭据/官方同源/安全逐项状态，配置、桌面静态、远端保留 origin，不自动保存/挑选/试听/克隆/计合成费。模型目录不等于可合成证明，SF audio 可含非 TTS，GLM 音色是桌面契约待实证，未知接口 unsupported；不以返回本地目录掩盖云端失败。完整覆盖/能力和设备仍继续，见 SPEECH.md#speech-directory。

- 第三十批显式恢复覆盖不授权重绑包外原有效语音引用；以原正文/绑定、完整资产/冻结选择、来源/归属/旧原稿证明/可得账目及字节比较保护，发现影响整次阻断，不隐式解绑/重建/续发。已损坏/失效关系只历史警告，普通定价/启用/控制改动仍可恢复，同来源多引用不强制复制音频。原列读取先预算，整次扫描超量明确失败，不哈希；代码/编译不是完整其他引用或 IO/持久性/设备证据，见 NATIVE_BACKUP_V1.md。

- 第二十九批 TTS 使用独立 _kind=tts/tts: provider 账本，不伪造 token 或实际发票；费率由明确 user/catalog 规则 CAS 提供，按次/输入 code points/供应商报告字符，调用开始冻结，调价不重算历史。直接与后台合成都写事件（直接入口仍无前台宿主/音频保存），缓存发布/引用操作不作为另一次收费。HTTP 前记 running，短 NonCancellable 条件提交+shared lease 防快照截断，网络可取消；启动/恢复只中断旧事件。完整来源价表/其他计费维度/账单与 TTS/整个后端仍未完成，见 ACCOUNTING.md#speech-accounting。

- 第二十八批延后绑定是局部引用操作，不授权再次合成。新 v2 asset 冻结实际朗读稿/来源，v2 binding 把朗读稿与绑定正文分开；允许显式独立稿，同来源多消息共享音频，旧 ID 前提保护替换/解绑。任务 payload 因此保存用户朗读文本（128 KiB 预算），并非第二十七批的不存正文状态；JSON/普通备份不加密，toString 脱敏不等于内容脱敏。消息总行读写 256 KiB；旧稿缺证明不猜也不重发，候选恢复校验可得稿/来源，包外保留关联和全部 TTS 能力继续审计，见 SPEECH.md#tts。

- 第二十七批语音任务为显式新请求，UI 不订阅也由 core/前台宿主承载，停止/启动/资料恢复不重发。UUID 音频用既有 tasks payload+attachments 目录，不加表/哈希去重；消息正文完整相等才绑定 _tts，附件/绑定/成功同库，NonCancellable 只覆盖发布决策短段，库存和淘汰仍可取消。每来源按限额从旧到新清缓存，普通附件/正文不清；丢缓存不授权联网。私有清理 marker/identity 只用于本机定向启动处理，原生恢复剥离设备/inode/待清理权限。真实 IO/后台/备份/目录/克隆/预处理/费用与全后端仍需完成，见 SPEECH.md#tts。

- 第二十六批 TTS 先接可调用的四云端非流式合成，显式来源 settings/预设/模型/音色、加密凭据，不第一条兜底/跨来源借 key。桌面新导入重映射 TTS 引用，既有合并和本地保护不动；预处理/情绪注入未实现就报错，不静默略过。短事务外联网，caller-owned bytes，不自动播放/写缓存/任务或免费入账；响应有界、可取消，无跳转/自动重试/旧请求重放。音频头检查不是哈希或可播放证明，整套缓存/挂消息/任务/目录/克隆/费用和完整后端仍待补，见 SPEECH.md#tts。

- 第二十五批独立搜索复用通用 usage_events 表但以 _kind=web_search/version 和 web-search: provider 区分，不制造 token 或实际账单。调用准备时冻结显式按次/credit 费率，调价不改已结束事件；一行以完整原 JSON/未结束成本条件更新，不哈希，启动/资料恢复只终止，不重发。网络不持 Room 事务，搜索生命周期 shared lease 拒绝并发原生 snapshot/restore；普通写/搜索仍并行。Tavily include_usage 默认 true（显式 false 保留），缺 metering/币种/失败收费未知，其他全部计费/来源/助手/TTS 范围不缩减，见 ACCOUNTING.md#search-accounting。

- 第二十四批账户状态默认离线，显式刷新才查询；网络不持 Room/data gate，逐提供方返回安全结果并支持取消。余额/credits 仅是供应商报告，不改价格/账单，不猜币种或把缺值当零。Brave 不探测未支持的账户接口；其当前结果存储条款与已持久化工具结果存在兼容缺口，附状态 warning 并保留既有数据/行为，授权与保存策略待后续明确。不存在充值、自动轮询或旧请求重发，Room v2 不变。

- 第二十三批搜索是可调用客户端与生成循环，不仅保存配置。独立 Tavily/博查/Brave 固定官方 HTTPS、无跳转/自动连接重试/回退；来源助手与 global 独立配置/CAS，密钥只来自明确加密引用。开启标志只授权该请求的搜索，不授权沙箱文件/命令；原生按 Responses/Claude/Gemini 分流，server tool 不交本地执行，pause_turn 只在当前活跃任务中有界下一新请求，停止/中断后仍不续发。引用保留、未知搜索费不算零；余额/独立计费/全能力和普通助手/TTS 仍待补，见 ACCOUNTING.md#web-search。

- 第二十二批成本账本只用明确的用户/catalog 费率与十进制金额，不猜缺失 token、币种或最新价格；按官方协议区分含缓存输入和非含缓存输入、思考输出及缓存 TTL。供应商费用保留，本地估算仅对照；事件同库提交后冻结，调价不重算旧行。规则 user/catalog 分区 CAS、有界 rowid/epoch 分页与分币种汇总不改 Room v2。没有价格下载、真实账单、全模态或设备证据，普通助手/搜索/TTS 与完整后端继续待补；见 ACCOUNTING.md#usage-accounting。

- 第二十一批共享递归 FileObserver 是文件变化能力，不是隔离替代物。回调只 trySend，采集器持独立 shared gate 并在终态停止/join；全部原始事件/元数据差异均不猜作者，移动无 cookie 不配成确定重命名，报告始终 complete=false。分页为有界预览且明确截断，cursor 为进程/恢复内 rowid 边界，原生恢复/重启需刷新；不改 Room v2/25 表、不续接旧任务。isolatedProcess 权限和 Android 10+ exec 限制已查官方文档，真实 rootfs/运行器仍缺，不能把目录/路径围栏或事件记录当作完成。

- 第二十批其余生成/总结/状态 pump/worker/sandbox 采用 ATOMIC 握手和 WorkerStopSignals，原因先记录/工作先取消，再写 STOPPING；宿主停止所有 worker/pump，成功事务固定 COMPLETED。手工 FILE_COPY 是用户选择 File/SAF/跨沙箱副本的新任务，不是绕过模型审批；独立输入快照，不覆盖目标、不持久挂载/回写。发布前记录 inode 意图，Room 成功/事件或启动核对 marker 决定精确私有清理；库提交失败后不擦可能已编辑的目标，只启动核对元数据、终止旧任务、不重做复制。完整磁盘/并发/设备与行预算仍审计，不把同库事务当 FS 强事务。
- 第十九批来源 File/SAF 在登记返回前交接打开的描述符，后台复制有界私有输入后才解压/合并；支持真实 asset offset/length 与非阻塞管道/socket，不猜 URI 路径、不长期留 URI/擅自获取持久权限。取描述符还不属于已登记任务，取消/超时由提供方 CancellationSignal；忽略取消的提供方线程不是已解决的隔离问题。临时输入结束/启动精确清理，不作为旧任务续接凭据。BACKUP/RESTORE 同样 ATOMIC 握手，避免 LAZY 首次调度前取消跳过 finally，完整全任务异常审计仍继续。
- 第十八批组合链接按展开后分量解析检查，不能以 normalize 当完整沙箱围栏；包内和受影响保留图越界拒绝，悬空/循环元数据保留并 warning。旧任务等终止采用包内/原生命周期最大时间，报告检查时间仍取现在；重复资料恢复不重开旧执行，早期墙钟归一化的不同旧行不自动改。
- 框架 Context 数据路径可有系统别名，StoragePaths 仅规范框架 app data 前缀；files/st/附件/沙箱等子根必须为非链接目录，不通过 canonicalizing 用户根绕过检查。既有相对路径/ID 保留，无物理移动或 schema 迁移；设备与多用户行为尚未验收。
- 来源 IMPORT 接 core-scope worker/前台/通知停止，taskId=batchId，来源合并并存规则不动。ATOMIC 启动握手保障早停收尾；实体/映射/报告/任务成功同库事务，小字段 marker 状态作文件清理权威，提交返回时的取消不能擦成功报告或已提交附件。先清本次未提交附件再清 staging，链接不跟随、清理失败不冒成功；未提交残留锁 gate，已提交只清临时。手工复制/输入适配/其余任务异常与设备证明仍待补。
- 第十七批 NativeRestoreMode 默认 ADD_MISSING，只有明确 REPLACE_INCLUDED 才覆盖包内同身份行/托管叶项/凭据，不清库或剪枝包外数据。DB INSERT/UPDATE，不用删除型 REPLACE；跨会话/助手身份和破坏保留消息关联仍阻断。原 inode hard link 加独立完整旧字节是撤销前提，不用哈希；目录权限有撤销意图，文件撤销完成标记保留至日志/副本清理同步后，发布后的文件/凭据必须存在且一致。desktop/Rikka 自动合并并存不变。
- 第十六批真正恢复以新 RESTORE 任务进入，默认追加缺失项/复用相同项，差异拒绝；第十七批新增上述显式覆盖，不偷偷缩成只读检查或默认策略。用户行、native_restore/done marker 与成功任务同库事务；文件/凭据撤销只凭真实提交状态决定，不凭进度重放。加密 undo 不入 Room，文件日志先记录自有 inode；失败留记录、未提交恢复不完整时本进程写入屏障锁住，重启先处理，不能让后续恢复复用待撤销对象。已提交只清理，不回滚成功结果。
- RESTORE 清理保留宿主 handoff，pending handoff 参与有界 CPU wake lock；停止先取消 Room 再等待 STOPPING。仅普通编译，不宣称实际恢复、恶意 shell 隔离、组合链接/目录竞争、WAL/加密偏好/设备断电持久性已证明，不做哈希/冒烟/单测。
- 第十五批新增 inspectNativeRestore，独占只读检查与专属候选库，不直接写入正式数据。记录/文件/凭据分别给新增/相同/冲突，保留无关本地数据，覆盖必须在后续提交 API 明确选择；desktop/Rikka 自动合并并存不变。历史 tombstone/旧身份只警告，不重新建对象；旧任务/审批在候选数据终止，不执行或续跑。检查不是提交 token，也不是完整恢复。
- 原生 TEXT 采用 UTF-8 字节分块读取，不再用遇 NUL 提前结束的 length(TEXT)。候选 SQLite 写事务固定专属线程，JSON 输入在分配前限量、严格 UTF-8/重复键/深度/列/类型/行数；不新增哈希/冒烟/单测。精确临时候选库清理不触碰活文件；恢复提交/凭据撤销与跨崩溃事务继续实施。
- 第十四批改用原生 v1/schema v2 的全表 JSONL/文件清单快照，数据 gate 保持普通写并发，备份只在无其他任务/写操作时独占。BACKUP 任务先接宿主；长 Room 快照先取消再等 STOPPING 写事务，避免锁住取消。默认原子无覆盖发布，只有显式 overwrite 才替换已选 ZIP。
- 凭据默认不出包，显式包含须独立密码并 AES-256-GCM 加密；不复制 Keystore 文件、不存密码到任务。没有内容哈希/校验功能。用户正文/文件可能含私密信息，普通 ZIP 不承诺全包加密。可重建运行时尚无实现，选项 true 明确 Unsupported，不伪报包含。
- preflightNativeBackup 只检结构/schema/路径/清单，不是恢复成功证明。来源导入提前拒绝 native；完整恢复行/引用/冲突、文件/凭据事务及跨崩溃判定继续实施。
- 第十三批为全部变更文件工具捕获完整原始字节/结构前提，不用哈希；审批展示分页清单与 Base64 原始块，前提 20000 项/256 MiB，超过明确失败。覆盖写入归 EDIT，create_file 仅新建；前提变化拒绝并使批准项 SUPERSEDED，不再次执行旧 call ID。edit 可用自动完整前提，显式 expected 仍检查，取代上一批“必须由模型带 expected”的起点。
- 路径及祖先/后代操作互斥，不串行整个沙箱；审批等待不持有路径锁。临时字节副本只随调用存活、结束与重启清理，不用于恢复/重放。当前未隔离 shell 仍能竞争文件/链接与私有目录，完整安全边界未建立。
- shell timeout 同样覆盖启动准备和身份握手，修正固定 5 秒握手绕过较短任务期限的问题；未作真机运行证明。
- 第十二批独立 sandbox shell/file 工具同样登记 TaskStore 并走审批/前台宿主，旧原始 shell/write 门面不能绕过。无会话的独立任务不共用沙箱级串行队列，工具目标固定为任务初始沙箱。call ID 不可改参数重新执行；该批显式 expected 方案已由第十三批自动完整前提补齐。
- 用非阻塞 FIFO 和有限内存尾环替代 stdout/stderr 顺序全量读取，独立 setsid 握手后才执行命令，清理进程组和已观察后代；不依赖新 Process.pid/隐藏反射。数据行和输出预算明确，设备与恶意脱离边界仍未验证。
- 元数据观察不能冒充完整文件变化日志，重叠归属未知、缺口明确；不用哈希。系统 shell 不具备 rootfs 文件隔离，shellIsolation=false；实际隔离仍待补齐。
- 第十一批将 runtime:host 接为现有生成/总结/状态任务的平台宿主。登记前确认前台提升、Room Flow 驱动通知、task-specific PendingIntent 直接停止单任务；不依赖 App 动态广播接收器，也不因停止一个任务停全部服务。
- dataSync 尊重平台启动和时间限制，START_NOT_STICKY；超时/销毁中断全部已登记活动任务并保留第一停止原因。CPU wake lock 有有限超时且只在运行/清理持有，不以服务存在冒充设备后台保证。独立沙箱/import/backup 的全任务登记尚未完成。
- core 的 get/create 共用进程单例，避免前台服务拿到另一组无法停止的 worker。旧 create 名字保留兼容，但不再开独立 scope。
- 实际工作区为 E:/SimpleTavern/SimpleTavern-Android，桌面源码只读参考 E:/SimpleTavern/SimpleTavern-Desktop，不依赖旧 D: 路径。
- 当前 Room schema v2，仅在 v1 上增加正文规则影子/提取表，迁移不删库。
- 四协议客户端函数使用同一规范化声明、审批/执行和任务取消；仅在 HTTP 边界转换原生消息。原生 reasoning/签名状态放在既有生成元数据 JSON，不把它当作可执行工具或凭据，也不做哈希/签名验证。
- Responses 请求 store=false，客户端保存与回传完整支持的输出项；Anthropic/Gemini 保留各自内容块/parts 的原位置。不伪造或跨模型复用 opaque 状态；已执行且正文/模型/协议变化的旧工具组按惰性历史保留。
- 需求明确中断后保留并终止，不重试/恢复/续接旧任务；是否继续由新提示词创建新任务。此前“断点续接待补”不是本目标，不实施恢复按钮或自动续跑。
- 后端收尾不缩成当前已编译部分：后台宿主、完整备份、沙箱真实边界、其余提供商/助手/搜索/成本/TTS 仍在范围内；无设备/凭据时继续实现可做部分，运行验收单独标记缺证据。

### 事实

- 业务核心语言：Kotlin；结构化存储：SQLite + Room（schema v1）。
- 工程位于仓库根目录多模块 Gradle（非 `android/` 子目录），与 README 说明一致。
- Cursor 本轮不实现 Compose 业务界面；`app` 仅含 DEBUG 诊断宿主与前台服务。
- 不引入仓库内自动化测试框架（无 `src/test` 业务测试套件）。
- 桌面 Python 核心不复用；行为参考 `D:\SimpleTavern\SimpleTavern-master`。

### 假设与选定默认值

- `minSdk=26`，`compileSdk/targetSdk=35`，Java/Kotlin 17。
- 审批默认策略：`custom`，shell/write/edit 均需逐项审批；`mode=auto` 为全自动。
- 同会话仅一个前台生成任务；同沙箱允许多任务并发。
- 沙箱 v1 隔离：独立目录 + canonical path 围栏 + cwd/环境变量约束；**未捆绑 PRoot/rootfs**。更强隔离待设备验证。
- 备份在有非终态任务时失败关闭（不复制热库文件冒充一致性快照）；导出以 JSON 快照 + 附件/沙箱用户文件为主。
- Token 估算策略：`chars/4`，不可用精确 tokenizer 时写入上下文诊断说明。
- 凭据使用 EncryptedSharedPreferences；导入不迁移明文密钥。
- RikkaHub 按可获取的 schema 25 样本启发式读表；用户实际版本未验证。

### 明确不做 / 延期

- Compose 聊天/设置/审批 UI（Codex）。
- 桌面内置助手工作区文件导入（报告 `deferred`）。
- Docker 级进程/网络/资源隔离。
- 外部目录挂载与双向同步。
- 本地大模型推理、新插件市场。
