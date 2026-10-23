# 原生联网与订阅账号接入计划
更新：2026-10-22（Asia/Hong_Kong）。状态：首轮原生代码已落地，Debug 构建通过；真实账号与设备验收未完成。

## 本轮结果与剩余项
- 官方 ChatGPT 动态注册、回环回调、ID token 验证、公开 Responses 推理与模型目录，以及 Grok 设备码兼容流程已接入公共业务接口。
- DeepSeek Search/Fetch、Claude server fetch/续轮、GPT 联动工具已接入主聊天/助手共用生成链；预设高级来源与开关已接线，新建受支持预设默认 native，旧来源保留。
- 与 Desktop 对齐供应商目录和 Grok Responses 协议。验证说明见 VERIFICATION.md，接口与后续 UI 要求见 CORE_API.md。本仓库完整产品前端仍待开发，本轮不宣称 Android 已有完整登录/额度屏幕。
- **额度数字目标尚未完成**：未核实 SIWC/Grok 可用额度读取接口，目前 unsupported 与官方管理链接；不估算5小时、周额度或刷新时间。
- 真实账号授权、订阅扣额、远端联网、系统浏览器/后台生命周期均未实机验收。未新增测试套件，未提交/推送。
## 已确认的产品决定
- 主聊天、重新生成、助手统一支持 DeepSeek / Claude / GPT 搜索与网页读取。
- 保留联网总开关；高级配置分别控制搜索和抓取。GPT 原生动作无法独立控制时显示“搜索与网页读取联动”。
- 旧预设保持现有来源选择；新预设默认供应商原生能力，保留独立搜索选择。失败不静默切换供应商或付费 API Key。
- ChatGPT 与 Grok 展示真实套餐、额度窗口和刷新时间；缺失字段显示暂无法查询，不估算消息数。
## ChatGPT 官方接入（替代原计划 Codex 后端方案）
- 使用 OpenAI Sign in with ChatGPT 正式文档中的预览机制；入口“使用 ChatGPT 登录”，预设标识 openai-chatgpt，旧 openai-codex 独立保留为兼容项，不将旧令牌冒充新授权。
- 每次安装持久化 ext_agent_host_id；首次使用 dynamic_agent_client 和真实应用名注册；先保存回调签发的 client_id 再换码；后续复用注册。
- 系统浏览器 + 127.0.0.1 回环监听 + PKCE/state/nonce；校验 ID token 签名、issuer/audience/nonce。只有实际获授 chatgpt.tokens.use.direct 才启用订阅推理。Android 使用原生回环监听，不依赖桌面进程。
- OAuth 凭据单独保护保存，按账号/预设绑定，刷新串行；取消/退出/删除清理运行态和令牌。公开状态、日志及导出不泄露凭据。
- 从 https://api.openai.com/v1/models 动态获取账号模型；推理 POST https://api.openai.com/v1/responses，store=false、stream=true，HTTP 输入包含所需历史；不使用 backend-api，不自动回退。
- 请求按官方 preview limitations 过滤不支持参数，function/custom tools 适配 namespace 或 additional_tools；非流式应用入口通过消费 SSE 聚合返回。只有 response.completed 为成功，处理流中途额度错误和 incomplete。
- 文案：通过 OpenAI 官方提供的 Sign in with ChatGPT，使用你的 ChatGPT 订阅额度。本功能目前处于预览阶段，OpenAI 可能随时调整可用范围、使用限制或接入方式。
- 额度来源须与新授权机制匹配，不拿 Codex 私有用量冒充。无法查询时明确展示并提供官方管理用量链接。
## 联网与 Grok
- DeepSeek 复用模型预设凭据，应用工具循环调用 /v1/tools/search 与 /v1/tools/fetch，保留引用与轮数/结果预算。
- Claude 扩展现有服务端 web_search，增加兼容版 web_fetch；保留 server_tool_use、结果块与引用，处理 pause_turn 和混合客户端工具。
- GPT 沿用 Responses web_search，展示 search/open_page/find_in_page 动作；按模型/账号策略提供能力提示。
- 新增 xai-oauth 订阅预设（与 xAI API Key 分开），官方 OIDC discovery + 设备码轮询/取消/刷新/退出；固定官方凭据发送边界。
- 统一额度响应：provider、state、plan、checkedAt、windows、manageUrl、errorCode；windows 含 id、label、windowMinutes、usedPercent、remainingPercent、resetsAt（仅填真实上游值）。state=available/unsupported/not_logged_in/error。
- Desktop GET /api/llm/oauth/usage?presetId=…&refresh=false；Android 等价原生服务。面板显示套餐、真实窗口、剩余与重置时间/倒计时；保留失败前快照并标过期。可见时最多60秒自动查询，手动刷新，换号清缓存。
## 实施顺序与分工
1. 主代理先落地两端计划文档并核实官方协议/额度来源。
2. Desktop 后端代理：官方 ChatGPT + Grok 登录、推理、模型、额度服务、联网适配及后端规格。
3. Android 代理：原生相同能力、接口/模型/目录/诊断入口与 Android 文档；当前仓库是后端及诊断宿主，不宣称完整产品 UI 已存在。
4. Desktop 前端代理：登录与额度面板、高级联网设置、准确文案和前端规格；与后端代理协调契约。
5. 主代理复核两端一致性、兼容与结果，更新交接和验证证据。
## 验证与完成条件
- 遵循现有仓库策略，不新增测试框架/断言/自动化门禁，不运行已作废 pytest/Vitest；执行 Python 语法/导入、前端类型检查与构建、Android 受影响模块和 Debug 构建。
- 规格列明：搜索→抓取、工具续轮/混用、开关/旧配置、取消/超时、登录拒绝/过期/刷新轮换、权限未授予、额度缺失/耗尽/过期/跨时区。
- 真实上游、Plus/Pro/Grok 登录、真实用量及 Android 系统浏览器回调未执行时必须明确标注；构建通过不能替代真实账号验收。
## 官方资料
- https://developers.openai.com/siwc
- https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://api-docs.deepseek.com/guides/search/
- https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-fetch-tool
- https://auth.x.ai/.well-known/openid-configuration

