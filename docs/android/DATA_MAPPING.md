# DATA_MAPPING — 桌面 / RikkaHub → Android

## 统一目标模型

Room 实体见 `core/data/.../Entities.kt`。消息为 `message_nodes` + `message_candidates`（候选与选中分离）。

ID 映射表 `id_maps(namespace, sourceId) -> targetId`；命名空间形如 `desktop:<batchId>` / `rikka:<batchId>`。

## 桌面 SimpleTavern

| 源 | 位置 | 目标 | 损失/备注 |
|----|------|------|-----------|
| settings | `settings.json` | `app_settings` | 保留整包 JSON |
| characters | `characters/*.json` | `characters` | 头像路径改相对引用 |
| personas | `personas/*.json` 或设置内 | `personas` | |
| world books | `world_books` / `worldbooks` | `world_books` | entries JSON 列 |
| chats | `chats/<id>/chat.json` 或 `chats/*.json` | `chats` + nodes/candidates | `greetingVariants` → 多候选 |
| long memory | overrides 或旁路文件 | `long_term_memories` | |
| MVU | `stateVariables` | `mvu_states` | |
| KG | `knowledge_graph.json` | `knowledge_graphs` | |
| images | `chats/<id>/images` | `attachments` + filesDir | |
| assistant settings/history | 文件 | assistant / source_blobs | 历史可 retained |
| usage | `usage/*.jsonl` | source_blobs | 可重建统计 |
| AI workspace files | `ai_workspace/**` | **deferred** | 明确延期 |

旧「仅设置」ZIP：报告缺 chats/附件/记忆，不得称完整成功。

## 官方 RikkaHub（样本 2.5.5 / schema 25）

| 源 | 目标 | 备注 |
|----|------|------|
| `settings.json` | app_settings | |
| `rikka_hub.db`（暂存副本只读） | chats/messages/assistants | 表名启发式 `conversation`/`message_node` 等 |
| `nodes` 遗留字段 | retained blob | 避免空读 |
| upload/ | attachments | URI→相对路径 |
| skills/fonts | retained/登记 | 不执行包内脚本 |
| 凭据 | missing | 需界面重配 |

用户实际 RikkaHub 版本：**未验证**。

## 通用正确性

- 同包重复导入：已映射 ID 复用；本地 `updatedAt` 更新时 retained 不覆盖。  
- 失败保留旧数据；批次状态 `running/cancelled/failed/done`。  
- Zip-slip 拒绝。  
