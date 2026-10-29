# 安卓前端现状（2026-10-29）

写给下一位接手前端的人（包括云端会话）。代码停在 `main` 的 `214dc77`。后端（`core/*`、`runtime/*`）由 GPT/Codex 负责，前端不改后端，见 [HANDOFF.md](HANDOFF.md)。

## 1. 为什么停在这里

作者实机测试后决定暂停前端开发，先讨论清楚三维场景的技术选型和效果要求，再继续。

原因：现在每个场景各用一套 Compose 的 2.5D 手法拼出来，镜头、光照、深度都不统一，场景之间只能靠插值来"伪装"衔接：

| 场景 | 现在的做法 |
|---|---|
| 牌架 | 整个平面先 `rotationZ` 再 `rotationX`，共用一个 RenderNode 镜头 |
| 牌组 | 每张牌各自 `rotationX`，每张牌有自己的镜头（没有共享透视中心） |
| 抽牌转场 | 中间那张牌用三层嵌套的 graphicsLayer 模拟牌架的姿态，再插值到牌组 |
| 写牌卷起 | 自己算透视，把牌切成条带，每条用 `setPolyToPoly` 贴纹理；卷到位后叠一个真实的输入框 |
| 设置（瀑布洗牌）、新角色（落牌推近） | 每张牌一个全屏大小的图层，缩放加旋转 |

后果：

- 场景之间的镜头和光照对不上，越往后加场景越难连贯。
- 写牌卷起的条带之间有色带，书写面（真实输入框）和卷曲部分的宽度、金边对不齐，有明显接缝（作者实机截图圈出了右下角）。
- debug 包下开销明显：每条带单独裁剪和绘制；Compose 在 debuggable 模式下本来就慢。
- 后面还有大量后端能力要接（编辑消息、分叉、记忆、世界书、Persona、附件……），如果三维方案没定，每接一项就要在这套拼凑的 2.5D 上重做一遍演出。

## 2. 现在有什么

入口 `MainActivity` → `TableApp`（`app/src/main/java/com/simpletavern/app/ui/table/TableApp.kt`）。

| 场景 | 文件 | 已实现 |
|---|---|---|
| 牌架（首页） | `ui/rack/RackScreen.kt`、`RackViewModel.kt` | 倾斜平面上的会话牌；左右换角色，上下换会话；有惯性、回弹、触感；新近度柔光；左下角固定名牌和引线。长按会话打开暗格，确认后弃牌并补位。点角色名编辑角色 |
| 牌组 | `ui/deck/DeckScreen.kt`、`DeckGeometry.kt`、`DeckViewModel.kt` | 扑克比例的牌，全屏绘制；标题悬浮在场景上；底部是手里捏着的两张牌（menu/setting）；候选横滑、重抽；生成中边缘金光；流式出字、停止 |
| 牌架↔牌组 | `DeckScreen.kt`（`DeckEntrance`、`flightPose`） | 牌从牌架抽出，半空翻面，落定后展开；menu 或返回键收回 |
| 写牌 | `ui/deck/DeckCurl.kt` | 捏住近端整条边卷起，卷到位后可以输入；下压再上甩、或点"出牌"发送；可以展开成全屏编辑 |
| 读牌 / 全屏写牌 | `DeckOverlays.kt`、`LiftOverlay.kt` | 从被点的牌原地放大 |
| 设置 | `ui/table/ShuffleReveal.kt`（`CascadeReveal`）、`DeckOverlays.kt`（`SettingsCard`）、`ui/setup/SetupViewModel.kt` | 瀑布式洗牌后翻开顶牌；模型预设的新建、编辑、删除、测试连接；这一局用哪个预设；导入桌面 ZIP。退出时翻回牌背，整摞落下 |
| 角色卡 | `ShuffleReveal.kt`（`DropReveal`）、`ui/table/CharacterFace.kt`、`ui/setup/CharacterViewModel.kt` | 界面隐去，一副牌从空中落下，镜头推近，翻开第一张。字段与桌面一致：头像、名字、简介、性格/外貌、情景/世界观、系统提示词、首句、额外首句（存在 `extraJson.extraFirstMessageEntries`）、示例对话。新建和编辑共用 |
| 截图测试 | `app/src/test/.../ScreenshotTest.kt` | Roborazzi + Robolectric 原生图形，渲染各场景和转场帧，输出到 `app/build/outputs/roborazzi/`；另有点击命中测试 |

## 3. 作者给过的反馈和处理情况

| 反馈 | 状态 |
|---|---|
| 牌要扑克比例，不要方形 | 已改（63:88） |
| 输入法弹出时牌被拉伸 | 已改：尺寸只取决于整屏 |
| 牌组和底部按钮之间有空隙，应全屏绘制，按钮叠在牌上 | 已改 |
| 底部两个按钮要像手里捏着两张交叠的牌 | 已改，效果待实机确认 |
| 顶部状态区太占地方，应收窄或悬浮在场景上、加极淡渐变 | 已改 |
| 写牌不能像中间对折再翻面，要像看底牌那样捏住边缘卷起整条边 | 已改成整边卷起，但有接缝、色带和性能问题（见第 1 节），未解决 |
| 发送时整张牌应从牌堆抽出并打出去 | 未做（停在设计阶段） |
| 设置的 cascade 方向反了，应像参考视频那样自上而下落 | 已改 |
| 设置打开掉帧，退出没有好的过渡 | 已改：动画只在图层里读；退出改为翻回牌背、整摞落下。真机帧率未确认 |
| 新角色：UI 隐去，一副牌从空中落下，镜头推近，翻开第一张填角色 | 已做 |
| 长按会话出现暗格，确认后这副牌落下消失，其余补位 | 已做 |
| API 预设应在设置里，不应在新建角色时填 | 已改 |
| 保存角色后无法再编辑 | 已改：牌架上点角色名 |
| 角色卡由什么数据组成 | 对齐桌面 `CharacterCard`，见第 2 节 |

## 4. 已知问题

- 写牌卷起：条带接缝和色带；书写面和卷曲部分对不齐；远端那一截被直立的书写面挡住，"牌还躺在桌上"的感觉弱。
- 新角色推近到约七成时，牌的下缘因透视略超出屏宽。
- 镜头距离按 `cameraDistance × 72` 像素换算（RenderNode 的相机以英寸计、每英寸 72 像素），只在 Robolectric 截图里对齐过，没有在真机上校验。截图里的 3D 透视可能和真机有出入。
- `Canvas.drawVertices` 等网格绘制在 API 28 以下的硬件加速画布上不可用（`minSdk` 是 26）。
- 只有 debug 包。还没有用于真机看流畅度的非调试构建（打算加一个与 debug 同包名、同签名、`isDebuggable = false` 的构建类型，可以覆盖安装并保留数据）。
- 截图测试和真机都没覆盖的：真实输入法下的手势冲突、长文本流式输出时的性能、导入真实的大 ZIP。

## 5. 三维场景技术选型（待讨论）

要满足的：一个统一的牌桌世界（牌架、牌组、洗牌、发牌、卷牌都在同一个镜头和光照下）；牌是真正可弯曲的模型；场景之间是镜头运动，而不是页面切换；文字可读、可输入（中文输入法）；后端能力会持续接入；作者大概率是唯一用户，可以为效果牺牲包体和冷启动。

| 方案 | 优点 | 代价 |
|---|---|---|
| A. 继续用 Compose 2.5D，卷牌改成 `drawVertices` 网格 | 改动最小；能用截图测试验证；不需要新依赖 | 仍然没有统一镜头，场景连贯性的根本问题还在 |
| B. OpenGL ES 自写渲染器（TextureView 承载，Compose 只做 2D 浮层和输入） | 一个场景图、一个镜头；牌是顶点着色器里弯曲的网格；透视纹理正确，有光照和抗锯齿；不需要新依赖 | 要自写 EGL、着色器、纹理和拾取；文字要先渲染成纹理，输入用隐藏的输入框；JVM 截图测试做不了 |
| C. Filament（Google 的 PBR 引擎） | 成熟的材质、光照、阴影，Kotlin 绑定可用 | 包体增加几 MB；材质要预编译；依赖要联网下载（作者这边网络不稳）；弯曲网格要自己更新顶点缓冲 |
| D. 嵌入游戏引擎（Unity as a Library、Godot） | 编辑器、动画、粒子都现成 | 很重；中文输入、和 Kotlin 后端通信、构建链都复杂；对单人自用项目代价过高 |
| E. AGSL RuntimeShader 做局部特效 | 光效、极光、边缘光便宜 | API 33+；只能做 2D 着色，不解决三维场景 |

倾向：B，或者 B 与 E 搭配。整张牌桌用一个 GL 场景，牌、牌架、牌堆都是网格，转场就是镜头和网格的动画；Compose 只负责文字输入、系统控件和无障碍。如果更看重材质和光照的质感，可以评估 C。需要在云端会话里定下来：方案、文字和输入怎么处理、后端状态怎么驱动场景、性能预算、怎么验证（没有 JVM 截图的话用什么）。

## 6. 构建与验证

```powershell
cd E:\SimpleTavern\SimpleTavern-Android
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain   # 截图在 app/build/outputs/roborazzi/
```

国内网络下 Gradle 拉依赖时常 TLS 握手中断。本机可以用一个 init 脚本加阿里云镜像，项目文件不用改：

```kotlin
// mirrors.init.gradle.kts，用 --init-script 传入
settingsEvaluated {
    val repos = dependencyResolutionManagement.repositories
    listOf("https://maven.aliyun.com/repository/google", "https://maven.aliyun.com/repository/public").reversed().forEach { u ->
        val r = repos.maven { url = uri(u) }
        repos.remove(r); repos.add(0, r)
    }
}
```

依赖进了缓存之后，可以加 `--offline`，但仍要带同一个 init 脚本（缓存按仓库区分）。

设计参考：`docs/design/` 里的牌堆、牌架原型（浏览器直接打开）、草图，以及 `screens-2026-10-29.png`。设计方向见 [HANDOFF.md 第 7 节](HANDOFF.md)。
