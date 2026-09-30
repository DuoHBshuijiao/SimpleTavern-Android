# SimpleTavern Android

Android 业务核心仓库。已建立 Kotlin 多模块业务核心与 DEBUG 宿主；界面仍留给 Codex。构建需本机 Android SDK。

界面留给后续 Codex。本仓库先做可构建、可调用、可持久化的 Kotlin 业务底座。

## 从这里读

1. [`docs/android/CURSOR_TEAM_EXECUTION.md`](docs/android/CURSOR_TEAM_EXECUTION.md)
2. [`docs/android/DATA_REQUIREMENTS.md`](docs/android/DATA_REQUIREMENTS.md)

桌面源码仍在 `D:\SimpleTavern\SimpleTavern-master`。规格副本用来理解现有行为，迁移时以那边的源码核对。

## 2026-09-30 迁入的文档

来源是桌面仓库同一天的工作区副本，按原相对路径放下，执行文档里的引用还能对上。

- `docs/android/CURSOR_TEAM_EXECUTION.md`：Cursor 团队执行约束
- `docs/android/DATA_REQUIREMENTS.md`：数据、导入、沙箱和中断基线
- `docs/specs/BACKEND-API.md`：桌面后端行为参考
- `docs/specs/FRONTEND-FEATURES.md`：桌面功能参考，不授权在本仓库做界面
- `docs/knowledge-graph-requirements.md`：知识图谱历史规格
- `docs/message-fork-requirements.md`：消息分支历史规格

## 留在桌面仓库、没有迁入的文档

- `docs/00-INDEX.md`、`docs/state/CURRENT.md`、`docs/state/LAST_HANDOFF.md`：桌面版本进度和交接。其中「不要启动下一版」不约束这次 Android 工作。仓库内不新增自动化测试框架这条，已经写在执行文档第 12 节。
- `docs/SANDBOX.md`：桌面黑盒环境，不是 Android 多沙箱。
- `ANDROID_HOST_FEASIBILITY.md`：Python 核心复用调查，已失效，不作为本仓库约束。

执行文档里「仓库根下的 `android/`」是按当时还在桌面仓库里写的。本仓库的工程放在仓库根目录，任务文档仍在 `docs/android/`。
