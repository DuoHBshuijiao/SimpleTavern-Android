# CORE_API — 前端应调用的业务接口

入口类：`com.simpletavern.core.api.SimpleTavernCore`

获取：`SimpleTavernCore.get(context)`（Application 内单例）。

## 线程与取消

- 对外 `suspend` 函数可从主线程调用；内部 IO 使用 `Dispatchers.IO` / Room。
- 生成流：`observeGeneration(chatId)` 为 SharedFlow，页面订阅与否不影响接收与落盘。
- `stopTask(taskId)` 取消网络/工具树；不回滚已落盘消息与已记录文件变更。
- 进程重启：`onProcessStart()` 将非终态任务标为 `INTERRUPTED`，并使待审批失效。

## 配置与内容

| 方法 | 说明 |
|------|------|
| `listCharacters/getCharacter/saveCharacter` | 角色 |
| `listPersonas/savePersona` | Persona |
| `listWorldBooks/saveWorldBook` | 世界书 |
| `getSettingsJson/saveSettingsJson` | 全局设置 JSON |
| `getAssistant/saveAssistant` | 助手配置 |
| `listPresets/savePreset` | 模型预设 |
| `listChats/getChat/saveChat/deleteChat/createChat` | 会话 |

## 消息与生成

| 方法 | 说明 |
|------|------|
| `pageMessages(chatId, limit, offset)` | 消息节点分页（含候选与选中） |
| `selectCandidate/addCandidate/editSelected` | 候选版本 |
| `searchMessages` | 全文模糊检索 |
| `forkChat/forkLineage` | 硬分叉到新会话；不复制 MVU/KG |
| `startGeneration(...)/stopTask/observeGeneration` | 生成与停止 |

错误：`StError.Conflict` 表示同会话已有生成任务。

## 记忆 / MVU / 图谱

| 方法 | 说明 |
|------|------|
| `getMemory/editMemory/summarizeMemory` | 长期记忆；编辑带版本校验 |
| `buildContext` | 返回注入诊断（含 memory/mvu/kg 是否进入） |
| `getMvu/saveMvu` | MVU 状态乐观锁 |
| `getKg/saveKg/upsertKgEntity/upsertKgRelation` | 知识图谱 |

总结成功后才推进 `lastAutoMemorySummaryAfterMessageId`。

## 导入导出

| 方法 | 说明 |
|------|------|
| `preflightImport/runImport/cancelImport/observeImportProgress` | 桌面 ZIP / RikkaHub 备份 |
| `exportBackup(dest, BackupOptions)` | 完整导出底层能力 |

导入报告状态：`converted|retained|missing|failed|deferred`。

## 沙箱

| 方法 | 说明 |
|------|------|
| `createSandbox/listSandboxes/deleteSandbox` | 环境生命周期 |
| `importIntoSandbox` | 手工复制导入，不挂载 |
| `sandboxListDir/sandboxRead/sandboxWrite/runShell` | 文件与 shell |

路径越界抛 `StError.Sandbox`。

## 审批

| 方法 | 说明 |
|------|------|
| `approvalPolicy/updateApprovalPolicy` | 策略 |
| `pendingApprovals/pendingApprovalCount` | FIFO 队列 |
| `approve/reject` | 逐项；禁止批量；批准幂等执行一次 |

未批准前工具无副作用。拒绝以工具结果返回，不换入口重试。

## 任务

| 方法 | 说明 |
|------|------|
| `getTask/recentTasks` | 状态机见 `TaskStatus` |
| `fileChangesForTask` | 文件变更；`incomplete=true` 表示记录不完整 |

状态：`QUEUED|RUNNING|AWAITING_APPROVAL|STOPPING|SUCCEEDED|FAILED|STOPPED|INTERRUPTED`。

## 事件顺序（生成）

1. 可选写入 user 消息节点  
2. 构建上下文（记忆/世界书/MVU/KG）  
3. `StreamEvent.Delta/ToolCall/Usage`  
4. 工具调用若需审批则任务侧等待（页面通过审批 API）  
5. 持久化 assistant 候选  
6. `StreamEvent.Done` 或 `Error`  
7. 任务终态  

重订阅只恢复展示，不重放工具。
