# ACCEPTANCE — 黑盒核对（不依赖读源码）

## 环境

- 安装 DEBUG APK（applicationId 带 `.debug`）。  
- 通过 `SimpleTavernCore` 诊断入口或后续 Codex UI 调用。  

## 导入

1. 导入完整桌面 ZIP：角色/会话/记忆可见；报告无静默成功掩盖缺项。  
2. 仅设置 ZIP：报告 missing chats，不得称完整迁移。  
3. 同一包再导：无重复会话。  
4. 本地改消息后再导旧包：本地编辑 retained。  
5. RikkaHub 包：助手与消息候选/选中保留；无 DB 时 settings-only 明示。  

## 记忆

1. `buildContext` 诊断显示 `memory=true` 且请求含记忆块。  
2. 手工编辑记忆带版本冲突时拒绝覆盖。  
3. 总结失败不推进 anchor。  
4. 分叉会话不共享 MVU/KG。  

## 沙箱

1. 创建两个沙箱，分别写文件；A 的 shell 不能读 B 路径（至少 path 围栏）。  
2. 手工导入外部文件后仅副本可访问。  
3. 同沙箱两任务并行；停一个不杀另一个。  

## 审批

1. custom 策略下 shell 未批不准执行。  
2. FIFO 逐项批准；拒绝无副作用。  
3. 停止任务后旧审批不可执行。  

## 生命周期

1. 同会话第二次生成应 Conflict。  
2. 杀进程后任务为 INTERRUPTED，不重放工具。  

## 备份

1. 无活跃任务时可导出 ZIP 并含 chats/attachments 元数据说明。  
2. 有活跃任务时导出失败。  
