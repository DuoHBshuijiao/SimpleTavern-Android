# 前端基础门面接口

五类入口均在 `SimpleTavernCore`，返回的模型位于 `core:model`。公共 Flow 在收集时等待进程初始化；suspend 方法可从主线程调用。

## 可观察列表

```kotlin
fun observeCharacters(): Flow<List<CharacterCard>>
fun observePersonas(): Flow<List<UserPersona>>
fun observePresets(): Flow<List<ModelPreset>>
fun observeWorldBooks(): Flow<List<WorldBook>>
fun observeChats(): Flow<List<ChatSession>>
fun observeMessages(chatId: String, limit: Int = 100, offset: Int = 0): Flow<List<MessageNode>>
```

Flow 是冷流，首次收集返回当前值；后续由 Room 表变化驱动，无轮询。角色、Persona、预设与世界书按名称/ID 排序，会话按更新时间倒序。消息与 `pageMessages` 同为最新在前；limit 为 1..500，offset 非负。消息节点包含全部候选和 selectedIndex，正文编辑、增加候选和切换选中项都会触发更新；节点与候选在同一事务读取。取消页面订阅不停止生成任务。

## 类型化设置

```kotlin
suspend fun getSettings(): AppSettings
suspend fun saveSettings(settings: AppSettings): AppSettings
fun observeSettings(): Flow<AppSettings>
```

`AppSettings` 包含 Persona 选择、主题、纯 AI/流式开关、世界书扫描深度，以及 `LlmSettings`、`GenerationDefaults`、`PromptSettings`。常用配置可直接通过 Kotlin 属性编辑：

```kotlin
val settings = core.getSettings()
core.saveSettings(settings.copy(
    generationDefaults = settings.generationDefaults.copy(contextSize = null, maxTokens = 4096)
))
```

contextSize 为 null 或 0 时不按 token 裁剪；maxTokens 非空时必须为正数。未知的全局与嵌套字段由各级 extensions 保留，旧版 top-level 连接配置不会被空 llm 对象遮蔽。保存返回持久化后的类型化值。当前接口读取原生 global 设置，不混用桌面/RikkaHub 来源设置；`getSettingsJson` / `saveSettingsJson` 仍可处理其他扩展配置。凭据通过现有凭据接口配置，`LlmSettings` 只定义 credentialId 引用。

## 测试预设连接

```kotlin
suspend fun testConnection(presetId: String): ConnectionTestResult
```

调用代表明确发起联网测试：按这个预设解析模型、端点、鉴权和自定义头，发送一次 32 输出 token 上限的小生成请求，30 秒超时，可取消。不会尝试其他预设、协议或自动重试。保留选定协议/流式方式和 maxTokenField；不带聊天历史、工具、搜索、Fast 或缓存创建设置。提供商正常返回输出长度截止帧也算连通，不因小额度耗尽把可用连接判成失败。

结果包含 success、latencyMs、protocol、model、可得 usage，以及经过收敛的 errorCode/message。HTTP 错误只返回状态码，不返回任意上游响应体或密钥。调用者取消继续抛 CancellationException；本地超时返回 timeout 结果。测试消耗进入现有用量账本，purpose 为 connection_test，scope 为 preset:ID；不建立聊天、消息或任务。测试请求可能产生提供商费用。

## 独立导入世界书文件

```kotlin
suspend fun importWorldBook(source: Uri, filename: String = "世界书.json"): WorldBookImportResult
suspend fun importWorldBook(source: File): WorldBookImportResult
```

URI 必须是用户授权的 content URI。支持 UTF-8（含 BOM）SimpleTavern 世界书 JSON 和 SillyTavern lorebook 的 entries 数组/字典，最多 4 MiB、10000 条。SimpleTavern 条目控制按既有类型读取；SillyTavern 支持常用关键词、常驻/禁用、顺序、AND_ANY 次关键词及部分插入位置，未支持的位置保留来源并返回 warning，未支持的次关键词逻辑明确失败。不是完整 SillyTavern 扩展运行时。

每次明确导入产生新的本地书和条目 ID，保存原始 JSON 到 extraJson，不覆盖已有书、不自动全局激活或绑定会话/角色。结果返回 worldBook、sourceFormat、warnings。JSON、UTF-8、大小、类型或条目控制验证失败时不写入半本书。ZIP 数据迁移仍使用原来的来源导入入口。

## 把世界书挂到角色

```kotlin
suspend fun attachWorldBookToCharacter(characterId: String, worldBookId: String, expectedUpdatedAt: String): CharacterCard
suspend fun detachWorldBookFromCharacter(characterId: String, worldBookId: String, expectedUpdatedAt: String): CharacterCard
```

传入最近读取的角色 updatedAt；过期返回 Conflict，缺少角色或待挂载世界书返回 NotFound。在同一事务检查和保存，返回更新后的角色。同一书不会重复加入；已挂载/已卸载状态下的重复调用在版本匹配时返回原值。显式挂载的本地世界书也可供导入角色使用，最终注入仍遵循会话的 merge/character/session 选择模式。

## 验证

在原 21 项回归基础上新增 29 项：设置 codec 6、连接测试 6、真实 Room 集成 9、独立世界书解码 8。共 50 项 Android 测试全部通过，`:app:assembleDebug` 成功。Room 测试运行于 Robolectric 4.14.1 / SDK 28，使用实际 Room 查询与失效通知；模拟 HTTP 测试不调用付费上游。

```powershell
.\gradlew.bat :core:import:testDebugUnitTest :core:memory:testDebugUnitTest :core:api:testDebugUnitTest :core:llm:testDebugUnitTest :core:conversation:testDebugUnitTest :app:assembleDebug --offline --no-daemon --console=plain
```

首次运行需下载 Robolectric/Android 测试运行时，依赖齐全后可使用上述离线命令。真实提供商连通性、真机文件选择器与完整产品 UI 仍需在对应环境验收。
