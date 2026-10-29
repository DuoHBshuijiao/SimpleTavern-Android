# 问题报告处理记录

2026-10-29，覆盖同级 `问题报告.md` 中的代码问题和迁移文档。

| 项目 | 处理结果 |
| --- | --- |
| 1. KG 导入失败 | 解码前注入目标 chatId；来源 sidecar 不能覆盖所属会话。core/import 新增缺失/错误 owner、空图谱、无效结构和用量映射回归测试。 |
| 2. token 严重低估 | 改为 Unicode 保守估算，短消息不再归零；计入工具定义、工具调用、提供商原生回合及媒体，预留已配置输出额度；解析附件后和每次工具续轮发送前再次检查。 |
| 3. 手动锚点 | 两端统一为 N 不含锚点本身，向前保留 N 条；N 缺省/0 不保留前文。桌面同步修改。 |
| 4. context_size 缺省 | 普通/助手缺省为不按 token 裁剪；显式 null/0 关闭继承的 token 限额。显式锚点和消息条数限制继续生效。 |
| 5–6. 桌面迁移包 | 桌面 with_chats 导出 chats 全部文件、ai_workspace、usage、助手配置与全局历史。basic 仍只包含设置与 Persona 头像；API Key 仍须在安卓单独配置。修正 DATA_MODEL、STATUS 与交接说明。 |
| 7. 世界书预算 | 超出预算时按整本淘汰，不仅记录日志；固定注入和最新完整组仍超限时返回 Validation。 |
| 8. Gemini 缓存 | 生成 404 清除本地绑定/手动选择；下一次用户发起的生成可重建。替换自动 history_tail 前缀前，先等待持久 DELETE 任务确认删除旧资源，失败则不创建新资源。DELETE 404 视为已删除。 |
| 9. OpenAI promptCache | 非官方端点及不支持缓存的旧模型只保留透明缓存；旧模型/Chat 使用适用的 key/retention，新版 Responses 才使用 options；不支持的断点降级，数值 TTL 可归一化。 |
| 10. 流式与门面 | 每个会话重放最近 256 条事件（含 Done），新生成清空旧窗口；数据库改为门面私有属性；修正 KDoc/API 文档。 |
| Compose 准备 | 根工程与 app 声明 Kotlin Compose 插件 2.0.21；app 开启 compose 并加入 runtime 1.7.0，诊断宿主仍可构建。 |

## 验证

- Android：core/import 5、core/memory 7、core/api 5、core/llm 2、core/conversation 2，共 21 项 JVM 回归测试全部通过。
- Desktop：3 项 unittest 全部通过，验证完整迁移文件逐字节保留、basic/with_characters 边界与无效 scope。
- `:app:assembleDebug` 构建成功，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- 验证命令：

```powershell
# SimpleTavern-Android
.\gradlew.bat :core:import:testDebugUnitTest :core:memory:testDebugUnitTest :core:api:testDebugUnitTest :core:llm:testDebugUnitTest :core:conversation:testDebugUnitTest :app:assembleDebug --offline --no-daemon --console=plain
# SimpleTavern-Desktop
$env:PYTHONPATH = 'backend'
python -m unittest discover -s backend/tests -v
```

## 使用边界

- token 仍是估算，不是模型 BPE 或计费用量；策略及媒体预留规则见 CHAT.md。未设输出上限且协议没有本地缺省时，不能推断远端实际最大输出。
- Gemini 404 发生的那一轮仍失败，不自动重发生成；超时或远端操作结果不确定时也不重放。更换凭据后的旧账户资源须在原账户管理。
- 流式重放窗口之外的完整正文从持久化消息读取；不会缓存整次无限长流。
- 旧桌面 ZIP 已缺失的媒体无法恢复，须用修复后的桌面端重新导出。尚无用户完整包、真机及真实付费提供商验收证据；KG 测试覆盖生产解码路径，不等于完整 Room 导入事务验收。
- 本批修复按记录命令重新验证通过后，与 HANDOFF.md 一起提交到本地；原 main 多出的提交保留，不推送。
