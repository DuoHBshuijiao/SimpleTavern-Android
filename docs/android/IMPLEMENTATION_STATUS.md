# IMPLEMENTATION_STATUS

更新日期：2026-09-30

状态枚举：未开始 / 实现中 / 已实现待构建 / 构建通过待运行 / 已运行验证 / 受阻

| 功能 | 状态 | 模块 | 缺口 |
|------|------|------|------|
| Gradle 多模块工程 | 构建通过待运行 | 根工程 | 真机未跑 |
| 领域模型与错误/任务状态机 | 构建通过待运行 | core:model | |
| Room 存储与 repository | 构建通过待运行 | core:data | 尚无升级 Migration（v1） |
| 桌面导入适配器 | 构建通过待运行 | core:import | 需真实完整包验证 |
| RikkaHub 导入适配器 | 构建通过待运行 | core:import | 表名启发式；用户版本未验证 |
| 单聊/群聊调度与分叉 | 构建通过待运行 | core:conversation | 设备未跑 |
| 上下文/世界书/记忆注入 | 构建通过待运行 | core:memory | token 为 chars/4 |
| 长期记忆总结/版本 | 构建通过待运行 | core:memory | 自动总结需宿主接 provider |
| MVU/KG 读写与注入 | 构建通过待运行 | core:memory | 自动 agent 全工具环未对齐桌面 |
| LLM 四协议流式 | 构建通过待运行 | core:llm | 需真实 Key/网关验证；OAuth 未做 |
| 工具+分类审批 FIFO | 构建通过待运行 | core:tools | UI 留给 Codex |
| 多沙箱文件/shell | 构建通过待运行 | runtime:sandbox | **PRoot 未捆绑**；隔离证据不足 |
| 文件变更观测 | 构建通过待运行 | runtime:sandbox | shell 归属为 observed |
| 前台服务/通知停止 | 构建通过待运行 | runtime:host | 真机后台限制未测 |
| 备份导出 | 构建通过待运行 | core:api | 活跃任务时拒绝 |
| 公开 API 门面 | 构建通过待运行 | core:api | |
| DEBUG 宿主 | 构建通过待运行 | app | 非产品 UI；`assembleDebug` 已通过 |
| 性能 1 万条采样 | 未开始 | — | 缺设备 |
| 真机导入后继续聊 | 未开始 | — | 缺设备/备份样本 |

## 阻断项

1. ~~本机构建环境缺少 Android SDK~~ → 已用 `D:\Android\Sdk` 完成 `assembleDebug`。  
2. 沙箱强文件隔离未用 PRoot 证明。  
3. 无用户 RikkaHub/桌面完整备份样本的导入运行证据。  
4. 真机后台/锁屏与性能采样未做。  
