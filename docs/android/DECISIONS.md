# Android 核心决策记录

日期：2026-09-30

## 事实

- 业务核心语言：Kotlin；结构化存储：SQLite + Room（schema v1）。
- 工程位于仓库根目录多模块 Gradle（非 `android/` 子目录），与 README 说明一致。
- Cursor 本轮不实现 Compose 业务界面；`app` 仅含 DEBUG 诊断宿主与前台服务。
- 不引入仓库内自动化测试框架（无 `src/test` 业务测试套件）。
- 桌面 Python 核心不复用；行为参考 `D:\SimpleTavern\SimpleTavern-master`。

## 假设与选定默认值

- `minSdk=26`，`compileSdk/targetSdk=35`，Java/Kotlin 17。
- 审批默认策略：`custom`，shell/write/edit 均需逐项审批；`mode=auto` 为全自动。
- 同会话仅一个前台生成任务；同沙箱允许多任务并发。
- 沙箱 v1 隔离：独立目录 + canonical path 围栏 + cwd/环境变量约束；**未捆绑 PRoot/rootfs**。更强隔离待设备验证。
- 备份在有非终态任务时失败关闭（不复制热库文件冒充一致性快照）；导出以 JSON 快照 + 附件/沙箱用户文件为主。
- Token 估算策略：`chars/4`，不可用精确 tokenizer 时写入上下文诊断说明。
- 凭据使用 EncryptedSharedPreferences；导入不迁移明文密钥。
- RikkaHub 按可获取的 schema 25 样本启发式读表；用户实际版本未验证。

## 明确不做 / 延期

- Compose 聊天/设置/审批 UI（Codex）。
- 桌面内置助手工作区文件导入（报告 `deferred`）。
- Docker 级进程/网络/资源隔离。
- 外部目录挂载与双向同步。
- 本地大模型推理、新插件市场。
