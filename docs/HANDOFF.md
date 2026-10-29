# SimpleTavern 交接文档

写给接手本仓库的任何工具或人（Claude Code、Codex 等）。

**分工：后端（`core/*`、`runtime/*`）由 GPT/Codex 负责；Claude 只做安卓前端（`app`）。** 前端工作中发现的后端问题只记录、交给后端，不在前端里顺手修。

## 1. 项目初衷

- 作者 Milton 自用，角色和世界观基本都是原创，不依赖社区角色卡。
- 原来用 JanitorAI，图的是多端云同步。离开的原因：旧 tokenizer 高估 token，实际七八万就按 128K 截断；滚动窗口截断让 API 缓存命中不了；每几十句就得手动总结、开新会话。
- 没有转向 SillyTavern：设置拆得很碎、看不出是否已保存；添加 API 时参数繁多、"测试"总报错；世界书、正则对原创玩法是负担；安卓上要靠 termux。
- 所以自己做：比纯 chatbot 多出角色管理和记忆，其他功能"好用就行"。先写了桌面端（`SimpleTavern-Desktop`：Vue 3 + FastAPI + JSON），边写边玩，前后四五个月。

## 2. 定位

- **手机是主力**，桌面端退居二线。
- **同步暂不做**：把桌面数据一次性完整迁移到手机，之后以手机为准。
- 上下文由作者手动管理，从不开自动总结或自动上下文管理。支持滚动截断和手动锚点截断两种模式；手动锚点保留锚点之前 N 条（N 不含锚点本身，缺省或 0 时不保留），两端一致。
- 缓存命中率**有意**只做全会话汇总，不需要逐轮明细或导出。

## 3. 功能取舍（决定 UI 露出什么）

| 级别 | 功能 |
|---|---|
| 第一期必须有 | 角色编辑（主流程，写卡要顺手）、聊天（流式、停止、重写、候选、编辑、分叉）、Persona、模型预设（**"测试连接"必须由用户手动点按钮发起**，会产生真实费用；只问必填项：协议、端点、key、模型）、记忆查看/编辑、从桌面 ZIP 导入、原生备份与恢复 |
| 要有，但不打磨 | 世界书：导入、挂到角色或会话、做小改。导入后不自动启用，作者习惯进会话后按需手动挂载 |
| 后端保留，UI 暂不露出 | 正则、MVU、KG、TTS、助手/沙箱/MCP、群聊、RikkaHub 导入、订阅登录 |
| 不搬到安卓 | WebGPU 背景 |

## 4. 工程现状

- 仓库：`E:\SimpleTavern\SimpleTavern-Android`。compileSdk/targetSdk 35，minSdk 26，Kotlin 2.0.21，AGP 8.7.3，Gradle 8.11.1。Android SDK 在 `E:/SimpleTavern/.android-sdk`（见 `local.properties`）。
- 后端：`core/*` 8 个模块、`runtime/*` 2 个模块。门面是单例 `SimpleTavernCore.get(context)`，没有 DI 框架。前端用到的接口集中写在 [`docs/android/FRONTEND_API.md`](android/FRONTEND_API.md)，更完整的说明在 `docs/android/CORE_API.md`。50 项 JVM 回归测试通过；真机和真实付费上游都还没验收。
- 流式：`observeGeneration(chatId)` 是 SharedFlow，按会话重放最近 256 条事件（含 Done），新一轮生成会清空旧窗口。正文落库后以 `observeMessages` 为准。
- 正在生成：`observeActiveTasks()` 按 `chatId` 和 `TaskKind.CHAT_GENERATION / GROUP_GENERATION` 过滤；停止用 `stopTask(taskId)`。

## 5. 前端现状

> 2026-10-29 前端暂停，等三维场景技术选型定下来再继续。现状、作者反馈、已知问题和选型备选见 [FRONTEND_STATUS.md](FRONTEND_STATUS.md)。

`app` 已接入 Compose（BOM 2024.09.00 + activity-compose + lifecycle-compose），`MainActivity` 是启动入口。原来的诊断宿主 `DebugHostActivity` 不再出现在桌面图标里，可用 `adb shell am start -n com.simpletavern.app.debug/com.simpletavern.app.DebugHostActivity` 打开。

首页牌架、牌组、设置的洗牌翻牌已能编译（2026-10-29 `:app:assembleDebug` 通过），还没在真机上验收。各场景和转场帧可以在 JVM 上截图检查，见下方"截图检查"。

| 文件（`app/src/main/java/com/simpletavern/app/`） | 内容 |
|---|---|
| `MainActivity.kt` | 入口，edge-to-edge，`TavernTheme { TableApp() }` |
| `ui/table/TableApp.kt` | 总控：牌架（home）与牌组两个场景，新局卡和设置盖在上面。抽牌（牌架→牌组）、收牌（牌组→牌架）、直接发牌（新局后）三种转场都在这里驱动；返回键在牌组里等于收牌 |
| `ui/table/ShuffleReveal.kt` | 两种出场：`CascadeReveal`（设置）——上方一叠牌一张张往下落成一摞（瀑布式，每落一张轻震），顶牌拿起翻开成设置页；退出时设置页翻回牌背缩回，整摞掉出画面。`DropReveal`（角色卡）——界面隐去，一副牌从空中落到桌上，镜头推近，翻开第一张；关闭时倒放。动画值只在图层里读，不重组页面 |
| `ui/table/CharacterFace.kt`、`ui/setup/CharacterViewModel.kt` | 角色卡正面：头像、名字、简介、性格/外貌、情景/世界观、系统提示词、首句、额外首句（存进 `extraJson.extraFirstMessageEntries`，与桌面一致）、示例对话。新建与编辑共用，未知字段原样保留。牌架上点角色名进入编辑 |
| `ui/rack/RackScreen.kt`、`RackViewModel.kt` | 首页牌架：整个平面先在桌面内转 `TURN`（-27°）再向后倾 `TILT`（44°），共用一个镜头。左右为角色（最近玩过的在前），上下为会话（新的在上），上方一格空位、下方是"＋ 新局"和虚线空位，最后一列是"＋ 新角色"。拖动先判定轴向，有惯性、边界回弹和撞边重震；焦点附近的牌抬起，3 天内暖橙光、7 天内蓝灰光。左下角是固定位置的角色名牌（标签盖章、名字上滑），金线从焦点牌连到名牌 |
| `ui/theme/Table.kt` | 牌桌配色（亮色象牙/金，暗色酒红/金）、金箔渐变、衬线字体字号 |
| `ui/card/CardArt.kt` | 牌面（金箔外框、内金线、四角弧饰、角标点数＋花色、首字放大的红色字头、底部渐隐）、牌背（酒红底＋金色斜格＋❦） |
| `ui/deck/DeckGeometry.kt` | 牌在纵列中的位置和倾角（移植自牌堆原型的 `slot()`），`TILT` 与 `CAMERA` 两个常量 |
| `ui/deck/DeckViewModel.kt` | 由牌架指定打开哪个会话；消息、流式、生成状态合成为 `DeckUiState`（`cardsFor` 标明牌属于哪个会话，新牌组加载完前不会露出上一副牌）；发送、停止、切换候选、重抽 |
| `ui/deck/DeckScreen.kt` | 牌组主页面，全屏绘制：牌是扑克比例（63×88），尺寸只取决于整屏，不随输入法变化；角色名和会话名悬浮在场景顶部、带极淡的渐变；底部 menu/setting 是手里捏着的两张交叠的牌，叠画在牌堆上（menu = 收回牌架）；生成中屏幕边缘金光呼吸。`DeckEntrance` 的 `flight`/`deal` 驱动入场：中间那张牌从牌架上的位置、姿态和大小起飞，半途翻面，落定后其余牌从它身后展开，页眉页脚随后滑入 |
| `ui/deck/DeckOverlays.kt`、`LiftOverlay.kt` | 全屏读牌、全屏写牌（从被点的牌原地放大）；设置牌：模型预设的新建/编辑/删除/测试连接、这一局用哪个预设、导入 ZIP |
| `ui/deck/DeckCurl.kt` | 写牌：牌堆上那张牌背捏住近端整条边卷起——远端 20% 留在牌堆上，中段绕圆柱卷过 180°−倾角，剩下的部分正对镜头成为书写面。卷曲按条带投影成透视四边形贴牌背/牌面纹理（`CurlProjector` 与 RenderNode 的相机一致），卷到位后换成真正的输入框 |
| `ui/setup/SetupViewModel.kt` | 模型预设（只在设置里配：协议、地址、模型、Key；编辑时保留其他配置键）、会话所用预设、导入 ZIP |

已实现的交互：

- 上下拖动浏览历史，带惯性和两端回弹；每换一张牌有一次轻触觉反馈。
- 点正面牌全屏阅读；点其他牌把它拨到正面。
- 点或下拉下方盖着的牌开始写；写的时候上方正文整体上移。
- 正面牌左右滑切换候选；在最新一条回复的最后一个候选上继续左滑，就是重抽。有多个候选时，正面牌后面露出一两张牌背。
- 生成中：新牌显示"落笔中"并实时出字，底部有"停 ■"，屏幕边缘金光呼吸。思考阶段显示"思索中"。
- 错误显示在底部状态行，4 秒后消失。

还没做、或只是占位的：

- 长按牌架上的会话：牌抬起，下面打开暗格，确认后牌落进去消失，下面的会话依次补位。
- 没有预设时点"＋ 新局"会打开设置去建预设；新建角色不再问连接。
- 没有音效（原型里有）；角色主题色暂由角色 ID 哈希出色相，角色卡里还没有颜色字段。
- 编辑消息、分叉、附件、记忆、＋半屏面板都还没做；新建会话只有临时流程（不选 Persona、不能编辑已有角色或预设），不是设计稿里的三按钮准备页。
- 每个会话最多读最近 500 条消息。

截图检查：`app/src/test/.../ScreenshotTest.kt` 用 Roborazzi + Robolectric 原生图形把牌组、牌架、空牌架、抽牌转场的 7 帧、洗牌翻牌的 7 帧渲染成 PNG（`app/build/outputs/roborazzi/`）。运行 `.\gradlew.bat :app:testDebugUnitTest`。注意：截图里的 3D 透视与真机 RenderNode 可能有出入，倾角和镜头距离以真机为准。`ScreenshotTest` 里还有一项点击测试，确认倾斜牌架上点正中会打开会话、点相邻的牌会把焦点移过去。总览图见 `docs/design/screens-2026-10-29.png`。

国内网络下 Gradle 拉 Google/Maven Central 时常 TLS 握手中断。可以用一个只在本机的 init 脚本，在 `settingsEvaluated` 里把 `https://maven.aliyun.com/repository/google` 和 `/public` 插到 `dependencyResolutionManagement.repositories` 最前面，再加 `--init-script`；依赖进了缓存之后可以加 `--offline`（仍要带同一个 init 脚本）。

## 6. 接手后先做什么

1. ~~首次构建~~（已完成，2026-10-29 构建通过）。首次构建需要联网拉新依赖（不要加 `--offline`）：
   ```powershell
   cd E:\SimpleTavern\SimpleTavern-Android
   .\gradlew.bat :app:assembleDebug --no-daemon --console=plain
   ```
   只在 `app` 里修编译错误，不改后端。
2. 装到真机，用一个已导入数据的会话检查：
   - 倾斜方向：上方旧牌应当上边缘朝向镜头，下方的牌下边缘朝向镜头，跟牌堆原型一致。如果整体反了，把 `DeckGeometry.TILT` 改成 `-1f`。
   - 透视强弱：如果太平或太夸张，调 `DeckGeometry.CAMERA`（乘以屏幕密度后作为相机距离，越小透视越强；现为 14）。
   - 牌架：倾角 `RackGeometry.TILT`、平面转角 `TURN`、镜头 `CAMERA`、焦点位置 `PIVOT_Y`；斜向拖动是否顺手，点牌是否点得准。
   - 转场：牌架→牌组（抽出、半空翻面、落定后展开）、menu 或返回键收回、设置的洗牌翻牌、读牌/写牌从被点的牌放大。节奏在 `TableApp.draw/putBack` 的 spring 参数和 `ShuffleReveal` 的时间常量里调。
   - 手势冲突：写牌时在输入框里上下拖动、正面牌的横滑和纵列的竖拖是否互相抢。
   - 键盘弹出后，写牌区域不能被挡住。
   - 发送、流式出字、停止、重抽、切换候选都走一遍。后端报错要能在底部状态行看到。
3. 后端问题记到单独的问题报告里交给 GPT，前端不动后端。

## 7. 前端设计方向（已定）

- 视觉锚点是**扑克牌**，华丽的装饰风格，不走科幻触感。底色以黑白为主，主题色只用在少数地方；偏好衬线字体；**不要聊天气泡**。
- 操作要像游戏一样有"演出感"（参考 Persona、全境封锁、死亡空间的 UI）。作者大概率是唯一用户，可以为体验牺牲"秒开即用"，效果的性能成本不是问题。
- **首页是牌架**：左右滑选角色，上下滑选会话；镜头倾斜并镜像，方便右手拇指沿 45–60° 方向滑动；有边界，缺的位置放空白占位牌（上方、下方都要有）。角色名是固定位置的一行浮动文字，牌上只写会话名。
- **会话就是一副牌**：选中后以弹簧动作从牌架里抽出，悬停在画面中央、纵向铺满。中间一张牌正对镜头，显示最新回复；上下各有几张逐级倾斜的牌，内容眼力好也能看清；上下滑浏览历史，点击进入全屏。候选回复是叠在当前牌后面的牌，状态显示在屏幕边缘。
- **牌组纵列从上到下**：旧历史的薄牌边、两张弯折牌、正面大牌、一张背面朝上的牌（抽它就是写新消息）、薄牌边，最底部两个 tab：menu 和 setting。见 `docs/design/sketch-deck-layout.png`。
- **写新消息**：像赌场里看底牌那样，手指捏住牌背近端，把牌卷起来朝向镜头，不是对折。卷起后远端剩一小条牌背，近端成为输入面；下压再上甩就是出牌。全屏编辑时整张牌被拿起，当前正文向上收起。见 `docs/design/sketch-draw-card.png`。
- **设置**：一段洗牌动画（riffle 或 cascade）盖住屏幕，然后翻开一张牌，牌面就是设置页。
- **其他已定交互**：
  - ＋ 是半屏面板，记忆设置放在里面；右上角是熟悉的新建会话按钮。
  - 新会话的准备方式：角色、用户身份、情景三个圆角按钮各自展开成列表，下面是"新建会话"按钮。
  - 历史列表：用柔光表示新近程度（暖橙 = 2–3 天内，蓝灰 = 3–7 天，无光 = 更早），左侧用角色主题色的线把同一系列串起来；标题不加副标题，也不需要图例。
  - 群聊里玩家固定扮演一个角色。
- 动效备选：角色主题色加两三种邻近色的极光；新分支时一道光从手机底部沿屏幕边缘升到顶部后消失；幕与幕之间用柔和的光线分隔。

参考原型（浏览器直接打开）：

- `docs/design/deck-prototype.html`：牌堆。纵列几何、抽牌卷角、全屏读写，都以它为准。
- `docs/design/rack-prototype.html`：牌架。首页的滑动方向、倾斜镜头、抽出牌组。

## 8. 已被作者否定的结论，不要再提

- "两端都只有滚动窗口，重现了 Janitor 的问题"：支持手动锚点截断。
- "总结和截断脱钩是高危问题"：作者不用自动总结。
- "命中率应该按会话、按轮次细化"：汇总是有意的设计。
