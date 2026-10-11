# 沙箱与文件观察

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="sandbox-runtime"></a>

## 独立 Linux 沙箱主链路

2026-10-10 同会话的独立任务：ToolRuntime 的执行Mutex由sessionId改为taskId，避免两个startShell/startSandboxTask虽是独立任务却在同一chat上整段串行。同一任务的工具仍保序；审批sessionId不改，批准/用户问答仍检查原会话FIFO首项，待审批查询按SQLite登记顺序rowid读取，不新增schema/序列字段。停止沿原taskId/进程取消及缓存清理，执行队列随任务清理。同会话单生成限制、文件路径短锁和未知变化归属不变。源码接线不等于设备并发/取消行为已验证。

2026-10-10 就绪判断：Luna 将 `SandboxRuntime.status` 的 `/bin/sh` 检查接到原 `GuestPaths.resolve`，绝对链接目标从当前 guest 根目录起算，不再由宿主 `File.isFile` 直接跟随。缺失文件、循环链接或无效祖先返回未初始化；其它异常仍传播。原精确 VERSION marker、安装碰撞规则、启动参数与公开 DTO 不变，没有增加兼容层或自动重装。此判断供运行时查询、安装跳过与 shell/stdio 启动共用；不等于真实设备启动或隔离验证。

2026-10-09 末尾链接文件操作：原 delete_file/rename_file 不再先展开末尾链接；读取/编辑仍沿原跟随目标，路径祖先仍按 guest 解析。删除用 lstat 确认普通文件或链接后沿原 File.delete，悬空/循环末尾链接作为对象可删；重命名移动源链接本身，原“目的地已存在即拒绝”的契约保留，悬空目的地链接也算已存在。Directory rename 的原审批快照可记录内部链接的原 target，不递归进链接目标。原 PathOperationLocks、activity/data lease、审批分类/停止/文件事件沿用，无新工具、表或框架；不是完整 POSIX rename 覆盖语义、链接创建/读取 Core API 或 TOCTOU/内核隔离证明。首次构建发现 Os.unlink 不属于 Android 公共 API；改用原 File.delete 后普通离线编译/打包成功57秒，证据见 [VERIFICATION.md](VERIFICATION.md)。

2026-10-09 安装/创建失败边界：rootfs 先在随机 staging 解包并预写完成 marker，发布前检查顶层碰撞，marker 最后发布。普通进程内发布错误会逆序撤回已移动条目；遇首个撤回失败即停止，保留该 staging，并以 `stagingDirectory`/`remainingEntries` 报告目录及仍留在 root 的发布项。marker 撤回失败时不再撤回 base；不自动重试或覆盖。新沙箱登记成功后即使调用取消也保留根目录；登记前失败只清理本次新建根目录。进程被杀/断电、文件系统持续失败仍可能留下部分内容；无崩溃恢复，尚无实际设备验证。

2026-10-08 MCP stdio：新增请求内SandboxStdioProcess，使用原ShellRunner进程组握手/跟踪/回收与同一PRoot根，保留stdin/stdout/stderr管道用于MCP。连接持activity/data lease，关闭释放文件事件窗口与原私有控制目录；stdio窗口不归属虚构任务，FileObservationReport.taskId可空，已有shell任务仍返回真实ID。没有新任务引擎、依赖安装、宿主目录绑定或设备执行，范围见 [ASSISTANT.md#mcp](ASSISTANT.md#mcp)。

2026-10-06：已接 Kotlin 实现、arm64 运行库/基础系统打包；尚未实际执行设备。PRoot 是用户态路径虚拟化，不是内核级进程/网络/资源隔离；`isolationVerified` 仍为 false。

### 已实现

- APK 离线携带 PRoot 5.1.107.96、64 位 loader、talloc/shmem 和 Alpine 3.24.2 aarch64 minirootfs。可执行文件由 Android 安装器提取到 nativeLibraryDir，不从可写用户目录直接 exec。
- 新建沙箱先安装独立可写根目录，再登记数据库。每个环境分别保留 `/home`、`/tmp`、`/imports`、系统文件和软件包；没有共享写入的 rootfs 或依赖目录。`apk add` 等由明确 shell 命令执行，建环境本身不下载软件包。
- shell 明确使用 PRoot `-r`/`-w /home`，不使用自动绑定宿主的 `-R`/`-S`。只绑定 `/dev/null`、`/dev/zero`、`/dev/random`、`/dev/urandom` 四个设备叶节点，不绑定宿主 `/proc`、`/sys`、应用数据、其他沙箱或外部目录。
- guest 通过 `env -i` 运行 `/bin/sh -c`，PATH/HOME/TMPDIR 固定到 guest；不携带应用凭据环境。宿主 Android shell 仅执行既有进程身份握手并 `exec` 固定参数向量，不执行用户命令；不存在 host-shell fallback。
- 同沙箱并行任务、输出截尾/FIFO、超时/停止与进程组/已观察后代清理保留；新增 PRoot `--kill-on-exit`。这仍不证明所有脱离后代的实际取消行为。
- bundled rootfs 安装只解析它需要的普通文件/目录/链接；初始绝对 guest 链接转为根内相对链接，保持文件工具和现有备份路径规则可用。
- 2026-10-09：`GuestPaths` 按路径组件执行 `lstat`/`readlink`，展开链接后再处理 `..`；绝对链接从 guest root 起算，最多展开 40 个链接以阻止循环。`resolveInSandbox` 与 `withExportStream` 共用解析；目录列表、文件读取、现有文件写入/编辑及本批沙箱导出来源和沙箱间复制跟随根内实际目标。dangling 链接不能成为可读的实际文件，循环链接解析失败。此解析不消除 TOCTOU，也不证明内核隔离或完整 guest/POSIX 语义。

### Core 入口

`createSandbox(name)` 创建并安装环境；`getSandboxRuntimeStatus(id)` 为 IO 本地查询，返回版本、ABI、bundled、initialized 与未验证隔离的 warnings。`initializeSandboxRuntime(id)` 是显式 IO 写入，用于尚未初始化的旧环境；已有初始化标记则不重复安装。

旧环境安装要求没有活动文件/shell 操作或非终态任务，且不覆盖已有 top-level 条目；已有空 base 目录可以沿用，冲突要求另建沙箱。用户已有文件不自动迁移/覆盖，也不自动安装旧环境或续接旧任务。旧沙箱若在进程终止、断电或文件系统持续失败期间遇到多项发布，仍可能留下部分 base；不提供崩溃恢复或自动修复。

`runShell`/SHELL 工具没有新协议：继续通过现有任务/审批链路调用。未初始化环境报 Unsupported，不退回 Android shell。运行库当前只提供 arm64-v8a；未提供 x86/32 位或远端运行时下载。

### 文件、备份与构建

文件工具与 shell 使用同一个沙箱根目录；`home/example.txt` 对应 guest `/home/example.txt`。根内绝对及相对链接按 guest 路径解析。`validateCopyTarget` 用于复制目的地时仍拒绝 linked ancestors，技能复制的目的地校验不放宽。delete/rename 仅对最终名称不跟随链接，路径祖先仍展开；delete 不能删实际目录，rename 不覆盖已有目标。审批 premise 沿原框架记录链接 inode/mode 及原 readlink 文本，manifest 的 kind=symlink/target 与 followFinalLink 明确显示对象语义，只有普通文件提供字节读取；原字节快照不新增哈希或另建验证系统。目录移动可以包含链接而不扫描其目标，尚不提供链接创建/读取 Core API。native v1/schema 2 将沙箱内绝对链接写成根内相对 metadata，恢复时的 live-link 比较及 merged lookup 也做同样转换；外来 raw 绝对链接仍由原规则拒绝，不改 live link。above-root 相对链接拒绝；dangling/cycle 不能作为实际可读文件，但最终名称可直接删除/移动。

现有 `includeSandboxUserFiles` 会包括已初始化 guest 文件和依赖；可执行 PRoot/loader 仍由目标 APK 提供。`includeRebuildableRuntimes=true` 仍 Unsupported，不把 APK 运行库声称为 portable runtime export。备份清单已更新为 proot-alpine/版本/bundledRootfs，shellIsolation 仍 false。

准备脚本和原始输入见 [第三方来源](../../third_party/sandbox/README.md)。AGP 当前会把 source asset `.tar.gz` 展开并去掉 `.gz`；APK 实际路径为 `assets/sandbox/alpine-arm64.tar`，安装代码读取此 TAR 流。Android packaging 使用 legacy native extraction 并保留四个 ELF 文件，不需要本机 NDK。包内 ELF 元数据的 LOAD 对齐均为 16 KiB，但这不是 16 KiB 设备执行证明。

后续主要缺口：真实 TOCTOU/内核隔离边界、真实 Android 启动/软件安装/网络/取消行为，完整 guest/POSIX 兼容，进程终止或持续文件系统故障后的安装留存/恢复，以及外部分发所需完整源码/许可证材料。本批末尾链接操作普通离线构建成功57秒，仅证明编译和打包；无用户文件删除/移动、设备/APK 执行、软件包安装、哈希校验、冒烟或新增单测。

逐组件链接展开和40次链接限制参考 [Linux pathname resolution](https://man7.org/linux/man-pages/man7/path_resolution.7.html)；最终链接对象操作对照 [unlink](https://man7.org/linux/man-pages/man2/unlink.2.html)、[rename](https://man7.org/linux/man-pages/man2/rename.2.html) 及 [Android Os](https://developer.android.com/reference/android/system/Os)。应用仍拒绝根外路径及重命名覆盖，不冒充完整 Linux 系统调用语义。

<a id="sandbox-file-observation"></a>

## 沙箱文件事件与分页契约

2026-10-03，第二十一批。只做正常实现、代码检查和 Debug 编译，未在设备运行。它补充文件变化记录，不建立完整 shell/rootfs 隔离，不恢复旧任务。

### 采集与生命周期

SandboxManager 在 shell 开始前接入 SandboxFileEvents，同一个沙箱的并行 shell 共享一套递归 FileObserver；只串行管理窗口/监听器，不串行执行 shell。目录监听器保留强引用，回调线程只做名称验证和容量 2048 的非阻塞入队，不执行数据库或文件 I/O。独立 IO worker 顺序处理事件，在命令退出/取消后写结束标记；最后一个窗口关闭才停止监听，并取消/join worker。worker 用 ATOMIC 启动，避免首次调度前取消泄漏租约。采集器另持 shared data gate，启动/停止错误也在 finally 关闭；不会把数据库写入脱离到已释放的原生快照租约之后。

观察 CREATE、MODIFY、CLOSE_WRITE、ATTRIB、DELETE、MOVED_FROM/TO、DELETE_SELF/MOVE_SELF，不监听读/打开事件。每个目录单独附加监听，创建/移入目录会递归补挂，期间的瞬时变化可能丢失；每约 5 秒尝试补挂遗漏目录。lstat 检查根 dev/inode、逐级目录和路径预算，最终链接只采集链接自身元数据、不进入目标；这些路径检查仍有竞争窗口，不是 openat/虚拟机的真实隔离证明。

原始行 attribution=event，元数据差异 attribution=observed，两者 taskId 都为空，即使当时只有一个 shell 也不据时间猜作者。专用文件工具的 confirmed 记录不改。窗口起止 UNKNOWN/incomplete 标记才绑定 taskId。原始事件/差异行都 incomplete=true；CREATE/DELETE 表示收到该类通知，并非内容和身份已独立确认。beforeSummary 是之前缓存的元数据，afterSummary 明确标为处理时采样，不是操作瞬间的字节快照。事件和前后差异可重复，不按内容去重、不计算哈希。

FileObserver 不提供重命名 cookie，移动事件记录 UNKNOWN 和事件掩码，不猜成确定 RENAMED。监听注册没有成功返回值；平台队列溢出、启动/停止边缘、目录补挂、路径竞争和归属仍不能保证。应用回调队列满会计数，预算或扫描错误进入 uncertainties；窗口报告 complete 始终为 false，不能把无事件当无变化。进程死亡丢失未落盘队列，启动不续接旧监听/命令，持久起始 UNKNOWN 仍明确不完整。

正常 ShellResult.fileObservation 包含 session/window 起止、fromCursor、throughSequence、窗口期间共享采集计数和缺口列表；独立 shell 任务会将它写入 resultJson（不重复正文输出）。外层取消导致结果无法返回时，起止行仍尝试在 NonCancellable 收尾落盘，结束行保留起始序号/epoch、计数和缺口；结束行自身 sequence 是结束边界。数据库失败时不伪造完成报告，任务走现有失败/中断分支；开始标记不会成为完整成功证据。

### 预算

- 每套活动采集器：1024 个目录监听器、4096 次总挂载尝试、20000 缓存节点、8 MiB 估算元数据缓存、200000 条原始事件，超过记缺口、不无限追加。
- 每次目录扫描：20000 节点、2 MiB 路径队列；相对路径 8192 UTF-8 字节、128 组件、组件 255 字节。初始化约 5 秒、补挂约 1 秒的协作式检查预算。
- 原有前后元数据扫描改用惰性 DirectoryStream，不一次 listFiles 全目录；同样限制 20000 节点/2 MiB 队列/8 MiB 结果，约 10 秒协作式预算。任一前后扫描不完整时，不从缺席项推断 CREATED/DELETED，只记共同项差异和缺口。
- 采集控制/收尾队列约 10 秒等待期限，过期报 Sandbox 错误并取消/join，不遗留独立写入。单次阻塞文件系统/SQLite 调用及 join 没有硬实时上界，设备性能尚未证明。
- shell/FILE_COPY 终态汇总只读至多 1000 个 ID、COUNT/EXISTS，不加载全部变化正文；异常大 ID 拒绝。其他业务表/旧全文接口的完整预算审计仍未完成。

### 分页

`fileChangesPage(sandboxId, cursor=null, limit=100, throughSequence=null)` 每页 1..200 项，多读一项判断 hasMore。首次将当前最大序号作为 throughSequence；后续传回这个值可固定上边界。不传则读取此刻的最新边界。返回 nextCursor、throughSequence、hasMore；观察窗口用 report.fromCursor/report.throughSequence 查询，但这个时间窗口内的行不能等同该任务所写。

sequence 使用当前 SQLite 文件的 rowid，不增加业务表/不升级 Room v2。cursor 绑定 sandboxId、FileChangeStore 实例随机 epoch 和最后真实 native_restore/done marker ID；进程/实例重建或原生恢复后旧 cursor 抛 Conflict，必须重新请求首页。它不是跨重启/恢复/未来 VACUUM/删除型更新可移植的业务序号，也不是任务恢复检查点；通过备份保留的历史报告只用于显示，旧 cursor 不可继续。

分页是有界预览：SQL 截取 path 前 2048 个字符、before/afterSummary 前 1024 个字符，truncatedFields 指出截断，不允许用预览路径执行操作或把预览当完整旧内容。截断判断比较 BLOB 字节长度，含 NUL 的历史字符串也不悄悄假装完整。身份/类型/时间/归属字段总计超过 4096 字节的行明确 Validation，不截断 ID 后冒充原记录。Room 在同一事务读 epoch、边界、预算和页面；旧 fileChangesForTask 全文接口保留兼容，不推荐用于长记录，其他全文/导入/恢复大行审计继续。

数据库表/原生 v1 25 表契约不变，变化记录按现有表导出，不导出原生监听器或承诺旧 rowid/cursor 稳定。没有设备、完整事件、平台溢出、后台/锁屏或低磁盘运行证据。

依据：[FileObserver API](https://developer.android.com/reference/android/os/FileObserver) 说明独立回调线程、强引用及停止后迟到回调；[isolatedProcess 服务权限](https://developer.android.com/guide/topics/manifest/service-element) 与 [Android 10 可写目录执行限制](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission) 是继续设计真实运行时的约束，不是当前已有隔离实现。没有下载/打包 rootfs、PRoot 或 native 运行器，独立 UID/工作目录/监听器都不能单独冒充完成要求。
