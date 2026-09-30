# CURSOR_HANDOFF

日期：2026-09-30

## 可构建状态

- `gradlew.bat :app:assembleDebug`：**已通过**（JDK17 + `D:\Android\Sdk`）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 详见 `VERIFICATION.md`

## 模块

| 模块 | 职责 |
|------|------|
| `:core:model` | 实体、错误、任务状态、事件 |
| `:core:data` | Room、repository、附件路径 |
| `:core:import` | 桌面/RikkaHub 导入 |
| `:core:conversation` | 会话、分叉、生成调度 |
| `:core:memory` | 记忆、总结、MVU、KG、上下文 |
| `:core:llm` | 四协议流式客户端 |
| `:core:tools` | 工具执行与审批队列 |
| `:core:api` | `SimpleTavernCore` 门面 |
| `:runtime:sandbox` | 多沙箱文件/shell/变更 |
| `:runtime:host` | 前台服务、凭据 |
| `:app` | DEBUG 诊断宿主 |

## 启动

1. 安装 Android SDK 35 + Build-Tools，写入 `local.properties` 的 `sdk.dir=`。  
2. `gradlew.bat :app:assembleDebug`  
3. 产物：`app/build/outputs/apk/debug/`  

## Codex 接手点

- 只依赖 `SimpleTavernCore` 与 `docs/android/CORE_API.md`。  
- 实现 Compose 会话/消息/审批/设置/沙箱文件管理 UI。  
- 不要直接碰 Room DAO 或沙箱绝对路径。  

## 已知问题

- 无 SDK 时无法完成本机构建证明。  
- 沙箱隔离未达 PRoot 级证据。  
- RikkaHub 用户版本与真机导入未验证。  
- OAuth/部分提供商专属能力未移植。  
- MVU 自动 agent 全工具环未完全对齐桌面。  

## 手动提交命令（勿由代理执行，除非用户要求）

```bat
git add -A
git status
git commit -m "feat(android): add Kotlin business core modules and handoff docs"
```

不要 push，除非用户明确要求。
