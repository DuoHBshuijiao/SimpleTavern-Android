# 提供商、模型与请求

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="bedrock"></a>

## Bedrock Runtime

2026-10-09。原后端生成链新增 Bedrock API-key/Bearer 适配，不增加 SDK、数据库表、任务引擎或应用页面。普通编译与打包不代表真实云账号或 Android 运行验收。

2026-10-09 后续：Converse 已接来源 explicit 缓存断点、implicit 不发标记、TTL 写入明细，以及 Claude 思考 / effort / top-k、明确服务等级、JSON Schema 输出和 strict 工具定义。仍不是所有模型专属参数或真实云验收；此前“Converse 缓存和结构化输出均未接”由本段更新。

2026-10-09 AWS鉴权后续：已接明确 Access Key / Secret Access Key / 可选 Session Token 的 SigV4，复用现有加密库和生成链；下文 Bearer-only / SigV4 全未实现为历史。尚未接 STS 申请 / AssumeRole / 自动刷新、SSO、实例凭据链或 SigV4a，也没有真实云账号验证。

2026-10-09 在线目录：明确 `listProviderModels` 默认 `kind=MODELS` 时通过 SigV4 `GET https://bedrock.<region>.amazonaws.com/foundation-models` 读取基础模型；中国区域使用 `.amazonaws.com.cn`。只有显式 `BEDROCK_INFERENCE_PROFILES` 才读取 `GET /inference-profiles?maxResults&nextToken`，并返回 `nextToken`；Core 的 pageToken 映射到该上游参数。目录只接受 `aws_sigv4` 与结构化加密凭据；签名覆盖 canonical query。基础模型端点不分页。返回可得 model/profile ARN、名称、状态、模态、推理类型、流式支持、profile 关联模型和 profile type；profile 的数值 Unix `createdAt`（含小数秒）转 UTC Instant，字符串时间保留原值。未增加 SDK/表、自动刷新或生成权限探测；此目录不包含完整 catalog/价格，也没有真实云账号调用。

### 连接与调用

选择 `providerId: "amazon-bedrock"`，公开 `providerParams.region` 指定区域；已明确配置固定区域 runtime 根地址时也可从地址读取区域。默认、`auto` 和明确 `anthropic_messages` 保留桌面目录原生 Anthropic 路径；只有明确 `protocol: "bedrock_converse"` 才使用 Converse。不会按模型名替换模型或失败后换协议。`aws-bedrock-mantle` / legacy `aws-bedrock` 仍是原 OpenAI 兼容目录项，与 native Runtime 不混用。

根地址为 `https://bedrock-runtime.<region>.amazonaws.com`，中国区域使用 `.amazonaws.com.cn`。默认主密钥沿原加密凭据引用、Bearer 鉴权；AWS 凭据必须明确选择 `aws_sigv4`。不读取环境变量、申请账号或生成 API key。模型 ID / inference-profile ID / ARN 作为同一个路径段编码，仍使用调用者选定值；具体资源权限与模型支持由上游决定。

普通聊天、群成员、独立助手、维护及明确 TTS 预处理共用原 Resolver / LlmClient。生成仍沿原任务、请求发送前账目检查点、停止和工具循环；取消中断 HTTP，不重发生成，不续接旧任务。

| 选择 | 同步 | 流式 | 消息编码 |
| --- | --- | --- | --- |
| 默认 / `anthropic_messages` | `InvokeModel` | `InvokeModelWithResponseStream` | 复用原 Anthropic 消息、工具、思考签名及响应解析；body 加 `anthropic_version: bedrock-2023-05-31`，model / stream 改由路径决定 |
| 明确 `bedrock_converse` | `Converse` | `ConverseStream` | 独立原生 system / messages / inferenceConfig / toolConfig 编码与事件解析，输出沿原 canonical 工具事件和 ProviderTurn |

InvokeModel 流解开 AWS `chunk.bytes` 后交原 Anthropic parser。Converse 流读取 message / content / metadata 事件，保留 text、toolUse、reasoning text/signature/redactedContent；同步响应保留原生内容数组。只有完整正常结束才提交工具调用，沿既有声明工具检查，不把 `tool_use` 误作服务端自动续轮。

两者共用 `AwsEventStream` 二进制帧读取。CRC32 是 AWS 协议规定的帧字段，不是新增文件哈希、内容指纹或缓存身份校验。网络响应仍有本地读取预算；不以帧检查代替模型/权限/设备验证。

### AWS SigV4（明确选择）

Core 新增 `setBedrockAwsCredentials(credentialId, accessKeyId, secretAccessKey, sessionToken=null): String`。返回实际 `aws:bedrock:` 引用；名字原已带前缀时不重复添加。记录只进原 EncryptedSharedPreferences，默认备份仍不导出，明确密码加密凭据导出 / 恢复沿原策略；不把密钥写进 preset、任务、usage 或公开预览。`hasProviderCredential` / `removeProviderCredential` 可使用该返回引用，更新凭据再次调用 setter，不自动改模型或 preset。

例如调用 setter 时使用本地引用名 `local-aws`，再将返回的 `aws:bedrock:local-aws` 绑定到 preset 的公开配置：

```json
{
  "providerId": "amazon-bedrock",
  "authStyle": "aws_sigv4",
  "credentialId": "aws:bedrock:local-aws",
  "providerParams": { "region": "us-east-1" },
  "protocol": "bedrock_converse"
}
```

省略 protocol 仍是原生 Anthropic InvokeModel；模型仍取原明确选择的 ID。`aws-sigv4` 是样式别名。结构化 AWS 记录不作为 Bearer 字符串发送，也不能借给普通 API-key、TTS、搜索或自定义头；普通 setter 不写这个保留前缀。Resolver 从本连接加密快照解码，runtime ProviderConfig 持有脱敏类型、普通 apiKey 留空，三种凭据值都进入现有私有材料遮蔽。

签名仅在本次请求边界计算，四种 InvokeModel / Converse 同步与流式操作共用 `AwsSigV4`。对实际 UTF-8 body、原区域模型路径、Host / Content-Type / AWS 头建立 canonical request，使用 UTC 日期、固定签名服务 `bedrock` 和本区域 scope 派生 HMAC 签名；有 Session Token 时加入并签署 `X-Amz-Security-Token`。不签向其它供应商，不允许手动覆盖 Authorization / 签名日期 / token / payload 签名字段；构造签名完成后仍走原请求前持久检查点、取消和无重发 HTTP。

SigV4 里的 SHA-256 / HMAC 是 AWS 鉴权协议计算，不是文件完整性校验、缓存摘要或自动校验工作流。没有增加 AWS SDK、环境密钥发现、后台刷新、token 过期猜测、401 重发或另一套鉴权任务。临时凭据由调用者明确提供 / 更新，过期或权限失败按原 HTTP 错误返回；此代码与普通构建不能证明 IAM 权限、区域模型可用性、系统时钟偏差或真实签名验收。

### 参数、附件与账目

原生 Anthropic 保留已有可编码参数、工具结果、思考与缓存控制。来源 `promptCache` 可在该固定 root 下使用原 Claude ephemeral / explicit breakpoints，TTL 仍只允许 5m / 1h；Bedrock 新建控制的 5m TTL 按默认行为省略，1h 明确发送，不改写已有原生签名历史。具体模型、区域支持和缓存门槛未验证。

Converse 映射输出上限、温度、topP 与 stopSequences。Claude 的 `top_k`、`thinking` / `thinking_budget`、`output_config.effort` 放到 `additionalModelRequestFields`，不发明模型能力；manual thinking 仅在明确输出上限时比较预算，未填上限仍交模型默认决定。`service_tier` 的 priority/default/flex/reserved 映射 `serviceTier.type`，请求等级与响应等级分开记录，不以请求 priority 冒认已提供该等级。

`response_format` 的 json_schema 映射原生 `outputConfig.textFormat`，schema 原样序列化为字符串，可带 name/description；strict 未填或 true 可用，false、json_object/text 与未支持字段失败，不改写 schema、做本地递归审查或自动重试。明确 function.strict 传入 `toolSpec.strict`，由实际上游决定所选模型是否支持。

来源 `promptCache` explicit 的 system/tools/history_tail 在对应数组尾部追加独立 `{cachePoint:{type:default}}`，不把 Anthropic 控制写到 Converse 请求根。5m 省略 ttl，1h 明确发送；无断点的 native cache_control 沿原尾部默认语义映射 history_tail。system/tools 必须有实际内容，不能创建空缓存段。implicit 不发标记，也不能强制 TTL；off/best_effort 沿原行为。不建客户端缓存索引、不计算内容哈希、不调用 Gemini 的缓存管理接口。`explicitMarkers:true` 的任意消息级来源控制仍未接。

图片支持 PNG/JPEG/GIF/WebP 内联，PDF 与已解码文本内联；图片 / PDF 分别受 3.75 MiB / 4.5 MiB 预算约束。合并后最终 user 消息最多 20 个图片、5 个文档，含文档需有非空文本，不生成假提示词。工具结果使用原 toolUseId / toolResult、JSON 或文本及支持的内联附件；canonical JSON 结果 `ok:false` 映射 `status:error`，复用同一次解析。不能把嵌套工具结果当作顶层 user 附件限制处理。

Bedrock 不调用其他提供商的 `/files` API。请求内远端上传句柄、超出内联预算的大文件、S3、音视频和新的生成媒体编码未接；其它模型专属 additional fields、guardrail / performance 参数、任意响应字段选择和每轮 beta 控制也未接。供应商原生 web search 未启用，仍可走既有独立搜索工具。Fast 开关不冒充 Bedrock optimized latency。

Converse usage 接 input / output / total 与 cache-read / cache-write；`cacheDetails` 的 5m/1h 写入量接原 cacheWriteShortTokens/cacheWriteLongTokens 及原价格组件。inputTokens 不含缓存数量，沿原 UsageTokens 排重与账目，不虚构服务端金额或修补未知原生总量。原生 Anthropic usage 沿原解析。没有新增价格目录或实时能力探测；实际结算与全费用覆盖未验证。

### 分工与剩余项

Luna 直接修改公开协议、目录 / 连接 / 参数接线和消息 / 附件编码；后续的缓存配置、JSON Schema 与工具状态 / strict 小补丁也交 Luna 编写。AWS 这批两个 Luna 直接编写凭据模型、连接/HTTP配置和 Core 接口，主代理实现签名及加密记录隔离，整合原发送链并修正直接 LlmClient 配置的协议/查询参数接线。此前缓存/参数普通构建成功1分4秒；本批 SigV4 集成1分25秒、最终收尾49秒均成功，最终 APK 时间2026-10-09 14:58:08 Asia/Hong_Kong。没有文件哈希校验、冒烟测试或单测集；没有真实云签名验收。

仍缺 STS / AssumeRole / AWS 凭据自动生命周期与 SSO / 环境链 / SigV4a、Converse 其余模型专属参数及消息级缓存控制、S3 / 大文件 / 音视频与双向流、完整模型与价格更新，以及真实账号权限 / 区域 / 云计费 / Android 运行证据。在线基础模型与 inference profile 列表不证明所选模型可生成或调用者有权限。全后端目标未完成；本批不是全面架构审查或防御性设计清理。

参考官方：[API key / Bearer](https://docs.aws.amazon.com/bedrock/latest/userguide/api-keys-use.html)、[InvokeModelWithResponseStream](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InvokeModelWithResponseStream.html)、[Converse](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html)、[ConverseStream](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html)、[消息限制](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Message.html)、[提示缓存](https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-caching.html)、[AWS event-stream](https://smithy.io/2.0/aws/amazon-eventstream.html)。

后续参数参考：[Converse 默认推理参数](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InferenceConfiguration.html)、[Claude 自适应思考](https://docs.aws.amazon.com/bedrock/latest/userguide/claude-messages-adaptive-thinking.html)、[JSON Schema / strict 工具](https://docs.aws.amazon.com/bedrock/latest/userguide/structured-output.html)、[缓存 TTL 明细](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_CacheDetail.html)、[服务等级](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ServiceTier.html)。

目录参考：[ListFoundationModels](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_ListFoundationModels.html)、[ListInferenceProfiles](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_ListInferenceProfiles.html)。

签名参考：[AWS SigV4 / 临时凭据](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html)、[官方 Java signer 的路径 / header 规范化](https://github.com/aws/aws-sdk-java-v2/blob/master/core/http-auth-aws/src/main/java/software/amazon/awssdk/http/auth/aws/internal/signer/V4CanonicalRequest.java)、[Bedrock 官方服务签名名称](https://github.com/aws/aws-sdk-java-v2/blob/master/services/bedrockruntime/src/main/resources/codegen-resources/service-2.json)。

<a id="codex-oauth"></a>

## Codex 登录与生成：桌面兼容路径

2026-10-07。已实现设备码、浏览器 PKCE 回调粘贴、加密凭据、刷新/退出与 Responses 请求衔接；只做代码检查和普通构建，尚未实际登录、调用上游、执行导入/恢复或设备。本文不代表全后端完成，也不是新第三方应用注册方案的实现证明。

### 使用入口

明确模型预设选择 `providerId: "openai-codex"`（兼容 `codex`），根地址只能是 `https://chatgpt.com/backend-api/codex`，协议 Responses/auto，authStyle 为 oauth_pkce 或 oauth_device；未指定协议采用 Responses。不能把 Codex token 发给用户自定义网关/另一 API root，不能退回普通 API key。

设备流程沿用既有 Core `startProviderDeviceLogin(presetId)`、`pollProviderDeviceLogin(loginId)`；DTO providerId 为 openai-codex，method 为 device_code。返回用户验证码、固定验证网址、到期时间、间隔和 nextPollAt，不返回 device_auth_id 或 token。每次 poll 最多一个查询，早于间隔/终态不请求；pending/403/404 等待至 15 分钟，slow_down 增加间隔，成功后一次交换并保存 token。一次消费后的交换失败置 failed，不再次交换同一 code。设备码功能可能没有对账号/工作区开放，不擅自开启账号设置。

浏览器备用入口：

- `startProviderBrowserLogin(presetId)`：纯本地生成授权地址，返回 ProviderBrowserLogin(loginId, presetId, providerId, authorizeUrl, redirectUri, expiresAt)。保留 verifier/state，仅将 challenge/state 放进授权网址。
- 用户自行打开 authorizeUrl、完成授权，浏览器跳转 localhost:1455 失败时也可以复制完整地址。
- `completeProviderBrowserLogin(loginId, callbackUrl)`：仅接受 `http://localhost:1455/auth/callback?...` 完整回调，匹配这次 state，不接裸 code，不监听本机端口；校验成功后明确发一次 token 交换。终态 DTO method 为 pkce，不伪装设备轮询。

`cancelProviderDeviceLogin(loginId)` 同时适用两类会话；已完成登录返回原终态，不回滚已保存凭据。`getProviderOAuthStatus(presetId)` 离线返回 providerId/loggedIn/expiresAt/needsRefresh/accountId，accountId 是路由元数据，不是身份证明或订阅验收。`refreshProviderOAuth` 明确交换，`logoutProviderOAuth` 删除本预设两种提供商的本地记录/等待会话，也能清理删除预设后的记录；不撤销服务端令牌，不停止已发出的生成。每种提供商最多 16 个待登录会话，重启不恢复或轮询。

### 凭据与请求

记录存既有加密凭据库的 `oauth:openai-codex:<Android presetId>`，包含 accessToken、refreshToken、expiresAt、accountId。Desktop/RikkaHub 各自映射 ID，因此登录不会借另一来源的记录；不导入桌面 oauth_tokens.json。普通 API-key/头/TTS/搜索入口不能直接读取结构化 OAuth JSON。记录/请求对象 toString 隐去 token，实际请求的脱敏材料包含 access 和 refresh token。

accountId 从 HTTPS token 交换响应的 access token payload 读取，仅用于 chatgpt-account-id 路由；没有把未验签 JWT 当授权/身份/角色判定，也没有实现新版第三方流程的 OIDC 身份验证。刷新保存返回的新 refresh token，失败不自动重试；若旋转后本地保存失败，仍需重新登录，没有自动恢复旧请求。

普通聊天、独立助手、记忆/状态生成和语音预处理都通过同一提供商鉴权入口。新执行时按需刷新，原预览/配置/状态不发请求；语音预处理准备时仅读取缓存 token，实际 process 时再解析/刷新，保留原来源限制、tools-disabled、模型/用量生命周期。下游 TTS 凭据独立，不把 OAuth 记录当语音密钥。

Codex 请求走固定 `/backend-api/codex/responses`，bearer + chatgpt-account-id/originator/OpenAI-Beta 兼容头；系统文本放 instructions（无系统文本也保留空字符串），历史/工具交换沿用 ProtocolMessages；store=false、stream=true，必须收到 response.completed 才成功。不额外加 `/v1`，不自动重试、降级或换模型。普通 Responses/其他提供商的默认编码不变。

采样、max_output_tokens、Fast、缓存、native web search 和媒体等其他字段仍沿用现有控制/协议约束；Codex 内部接口没有可证明全部参数/模型能力的公开合同，不宣称所有组合已支持。来源 Fast/promptCache 对非 OpenAI/Azure origin 的既有拒绝仍保留，不能据目录声明推导真实可用。完整 Codex 参数/能力适配与真实上游证明仍在待办。

认证 HTTP 与 Copilot 共用 ProviderAuthHttp：30 秒限时、取消关闭响应、64 KiB 正文预算、禁重定向/自动重试；公开错误只给固定文本和状态码，不回传 token 响应原文。新型登录不创建持久任务/重启续接系统。原密码加密 native 备份可明确包含凭据，但完整 OAuth/预设/来源/包外关系的恢复审计仍未完成。

### 官方流程与兼容来源的区别

[OpenAI 官方 Codex 登录说明](https://learn.chatgpt.com/docs/auth#login-on-headless-devices) 确认 Codex CLI 的设备登录需要账号/工作区允许；没有据此假设账号已经允许或实际登录成功。

当前兼容客户端 ID、设备端点、JWT 路由 claim、回调 URI 和额外请求头来自本工作区桌面 `backend/app/llm/oauth/codex.py`/`apply.py`；固定根地址来自桌面目录。不是 SimpleTavern 自有客户端注册，也不等于官方批准第三方复用该客户端。

[官方第三方注册方案](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) 使用动态注册、主机标识及身份/权限验证；[该方案的推理说明](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) 要求公共 Responses 端点而非 backend-api。这与当前桌面兼容模式是不同流程，不能把旧客户端的 token 直接改发公共 API 并称官方接入。本轮没有自动办理注册、改账号设置或扩大到已登录 Codex 应用的凭据。

待补：兼容模式实际模型/参数/媒体与网络证明、适用的第三方正式注册/完整 OIDC 及账号模型列表流程、服务端撤销、全 native 关联/并发/旋转后存储失败边界，以及完整后端清单的其他主要功能。无新增单测、冒烟或额外文件哈希校验。

<a id="model-catalog-refresh"></a>

## 模型目录刷新与明确价格安装

2026-10-09。复用桌面 `sync_llm_catalog.py` 的字段归一化和既有目录/用户价格分层，Android 新增明确读取与安装入口，不自动改模型或重算历史。

### Core 入口

- `refreshModelCatalog(): ModelCatalogPage`：明确下载公共 models.dev 与 OpenRouter 元数据，两份成功后发布完整目录快照，返回首100项；其余沿 `getModelCatalog` 分页。无需用户 API key/登录，不调用生成服务。
- `getModelCatalog` / `getModelCapabilities`：读取最近可用快照；无缓存时沿 APK 内置目录。仍是明确 provider 的精确匹配，不自动选第一项或替换模型。
- `previewModelPricingCatalog(selections): List<PricingRule>`：纯读取选定目录条目，生成可查看的现有价格规则，不写账本。
- `installModelPricingCatalog(selections, expected: PricingVersion): PricingSnapshot`：从安装时的目录生成规则，沿原 `UsageStore.replacePricingRules(..., catalog=true)` 和版本比较替换目录价，保留用户价。不更新旧事件或已经冻结的请求价格。

`ModelCatalogPriceSelection(providerId, modelId, usageProvider, mode=null, serviceTier="standard")` 必须明确选择目录厂商/模型及实际账本 provider。`usageProvider` 是已有 `ProviderConfig.providerId`，通常对应本地凭据/预设引用，不是 models.dev 厂商名或密钥值。可从已有 ProviderSelection 的 credentialId 确认，不读取凭据。模式价只使用所选 mode 的报价，缺项保持未知，不借标准价或推算倍率。调用方需声明准确的 serviceTier；Fast 模式名并不自动等于响应/账本的服务等级（例如 priority）。

选择1..200项，仍受原全部规则预算200和价格配置256 KiB约束；不截断整个目录来适应该限制。安装是替换目录半部，不是追加单项；应提交希望保留的完整选择集合。若需安装预览时准确看到的规则，而不是安装时目录报价，可把预览返回值直接交原 `installPricingCatalog(rules, expected)`。不增加另一套目录 CAS、哈希或价格任务。

### 数据与生命周期

models.dev 保留全部提供商/模型，规范化 reasoning options、工具/温度、context/input/output限制、标准价格与模式 body/headers。复用桌面 pi→models.dev 的已知名称映射；未知厂商仍可作为元数据查询，但不据此新增生成适配器或更换主机。

OpenRouter 按相同厂商、准确模型 ID 补充参数/efforts、名称与可得限制，并更新其标准报价。原缓存报价保留每token文本；input/output/cache read/write及明确一小时缓存写入报价用 BigDecimal 换成每百万token文本，不经浮点数。两家单位见 [models.dev README](https://github.com/anomalyco/models.dev/blob/dev/README.md) 和 [OpenRouter 官方字段定义](https://github.com/OpenRouterTeam/terraform-provider-openrouter/blob/main/docs/data-sources/model.md)。

负数/动态报价不改为零；未支持的条件阶梯、独立思考/音频/图像/按次费等保留在条目/模式 `extraPricing`，目前不能通过这个标量规则转换安装。未知能力保留空，不以公开目录代替账号权限或协议能力证明。厂商生成路由/OAuth overlay 不在线替换。

公共 GET 共用一个无重发/无重定向客户端，调用协程取消会关闭请求。每份响应及规范化缓存32 MiB，最多512提供商/50000模型，OpenRouter最多10000行；普通分页上限200。失败下载/转换保留旧快照；不自动联网、刷新用户登录或回退重发。

规范化快照使用 Android AtomicFile 写入应用 cacheDir 的固定 `model-catalog-v1.json`，与发布/加载共享对象锁；不创建 Room 大行、数据库表、版本日志或文件校验。启动只离线读取；缺失/系统回收后用 bundled，损坏缓存带 warning，不启动修复下载。原 shared data lease 排除 native 快照/恢复交错，允许正常读写；缓存是可重建参考资料，不进入 native 用户数据包，已安装价格仍由原设置表备份。平台写入语义见 [Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)，未做真实断电/设备证明。

### 分工与未完成项

两个 Luna 直接编写公共源归一化和价格选择 DTO/转换；主代理接缓存、HTTP/Core和既有价格库，修正空限制、动态报价及模式来源接线。没有新依赖/表/任务引擎/前端、文件哈希、冒烟或单测。

条件阶梯和其它计费维度、完整 provider overlay 在线更新、Codex/Express/其它原生目录与全部变体，以及真实公共目录/账号权限/云计费/Android运行证据仍缺。编译记录见 [VERIFICATION.md](VERIFICATION.md)，整个后端目标未完成。

<a id="model-directory"></a>

## 在线连接模型列表

2026-10-09。模型选择新增明确的在线读取入口，离线目录和用户已选模型保持不变。这不是自动更新价格、能力或所有云平台目录。

### Core 入口

`listProviderModels(presetId, pageToken=null, pageSize=100, kind=MODELS): ProviderModelPage` 是可取消、main-safe 的明确联网读取；必须指定实际本地 preset ID，不借另一来源或全局默认连接。`kind` 默认 `ProviderModelDirectoryKind.MODELS`，Bedrock inference profiles 必须显式传 `BEDROCK_INFERENCE_PROFILES`。预设尚未配置 model 也可列出；已有 model 仅用于原 auto 协议选择，不被替换。不创建聊天、生成任务、缓存操作或模型消费账目。

返回本 preset、实际协议、取得时间、目录 kind、`ProviderModelInfo` 列表和可选下一页 token。条目可保留实际 ID、名称、创建时间、owner、token 上限、Gemini 原生支持方法，以及资源名、版本/状态、输入输出模态、推理类型、流式支持、关联模型和 profile 类型；缺失字段保持空/空列表，不借离线目录猜测。OpenAI created 秒数转 UTC Instant 文本；Bedrock profile 的数值 Unix 秒（含小数）转 UTC Instant，字符串时间原样保留；Gemini ID 去 `models/` 前缀，Vertex ID 去 `publishers/google/models/` 前缀。Vertex 不把父级展示名称伪造成 publisher model 的 displayName。保留响应顺序和未知模型，不自动过滤成生成模型。

| 连接 | 路由与分页 |
| --- | --- |
| OpenAI compatible / Responses | 原连接的 `/models`；空根默认 `/v1/models`。标准接口无分页，不发送 limit，返回完整列表并带 `unpaginated_endpoint`；非空 pageToken 返回 Unsupported |
| Anthropic | 原连接的 `/models`；空根默认 `/v1/models`，使用 limit / after_id，has_more 和 last_id 形成下一页 |
| Gemini Developer / 同协议网关 | 原版本根的 `/models`；空根默认 `/v1beta/models`，使用 pageSize / pageToken 与 nextPageToken |
| 已登录 Copilot | 原登录连接、认证头和不补版本的 `/models`，仍按 OpenAI 列表解析；未做真实权限验收 |
| Vertex IAM service account | 保留所选配置的 Google API host 与 region，固定 `GET /v1beta1/publishers/google/models?pageSize&view=PUBLISHER_MODEL_VIEW_BASIC`，用 pageToken / nextPageToken；解析实际 publisherModels 的 name、versionId、versionState，没有模型展示名时保持空。是 publisher directory，不是 project deployment；Vertex Express API-key 连接不支持 |
| Bedrock `MODELS` | 仅 `aws_sigv4` typed AWS credential；将区域 runtime host 转为 `bedrock.<region>.amazonaws.com`（中国区 `.amazonaws.com.cn`），`GET /foundation-models`，不分页 |
| Bedrock `BEDROCK_INFERENCE_PROFILES` | 相同 SigV4 管理 host，`GET /inference-profiles?maxResults&nextToken`，返回 nextToken；Core pageToken 映射到这个上游参数。这是显式 kind，不改变默认目录 |

pageSize 范围1..1000，仅传给支持分页的接口；OpenAI 和 Bedrock foundation models 不据此截断完整列表。下一页仍需调用者明确请求，不自动翻页、回退协议或选择第一项。Gemini 与 Vertex 分页应保持同一 preset 配置和 pageSize；token 是该上游返回的不透明值，不是 URL，不建立额外持久 token 注册表。

### 复用与生命周期

移植桌面三个 `list_models_*` 的同连接路由/模型 ID 归一化思路，复用 Android 的 ProviderConnectionSettings、ProviderEndpoints、加密引用、HTTP头/query_key/API-version 和已有 OAuth。明确读取可按原登录规则刷新已有短期 token，不启动新的浏览器/设备码登录。完整生成端点经原 helper 去掉操作后缀，不换到目录官方主机，也不覆盖混合来源配置。

首批两个 Luna 直接写公开 DTO、响应解析和 Core 门面，主代理接 Resolver/HTTP；本批原生目录也由两个 Luna 直接修改 DTO/解析及 Resolver/Core，主代理接 HTTP/SigV4 并整合。一个 Core 客户端复用连接池；原 shared data lease 防止读取与 native 快照/恢复交错，允许正常读写和 OAuth 刷新并行。读取沿调用协程取消，不新增后台任务引擎或 stopTask 入口。

普通 GET 使用原无重发策略，不跟随重定向；网络错误不回显响应体/凭据。响应最多2 MiB/10000条，返回数据若含当前选定凭据材料则失败，不持久化原响应。没有文件哈希、缓存指纹、递归 schema 审计、新依赖/表、冒烟或单测。

### 尚未完成

Codex 专用目录仍需适配；不向它猜标准 models 路由。Vertex 此处只列 Google publisher models，不支持 Express API-key、其它 publisher、用户自部署 endpoint 或预置/项目部署资源的完整目录。Bedrock 只提供上述两个显式目录端点，不代表完整 catalog 或价格。另一个明确入口已接公共models.dev/OpenRouter整份模型目录刷新及选定标量价格安装，见 [PROVIDERS.md#model-catalog-refresh](PROVIDERS.md#model-catalog-refresh)；条件阶梯/其它费用、provider overlay在线更新、全部能力/生命周期字段、其他原生目录格式及真实账号/设备验收仍缺。

`remote_listing_not_generation_capability_proof` / `prices_not_installed` 明确列出模型不证明所选请求协议、工具、模态、区域权限或价格可用；listProviderModels仍不修改参考目录或安装价格。getModelCatalog/getModelCapabilities可读取另一个明确刷新操作发布的离线快照。普通编译记录见 [VERIFICATION.md](VERIFICATION.md)，完整后端目标仍在进行中。

接口契约：[OpenAI Models](https://developers.openai.com/api/reference/resources/models/methods/list)、[Claude Models](https://platform.claude.com/docs/en/api/models/list)、[Gemini Models](https://ai.google.dev/api/models#method:-models.list)、[AWS ListFoundationModels](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_ListFoundationModels.html)、[AWS ListInferenceProfiles](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_ListInferenceProfiles.html)、[Vertex Model Garden ListPublisherModels v1beta1](https://github.com/googleapis/googleapis/blob/master/google/cloud/aiplatform/v1beta1/model_garden_service.proto)、[Vertex PublisherModel](https://github.com/googleapis/googleapis/blob/master/google/cloud/aiplatform/v1beta1/publisher_model.proto)。Vertex publisher list 是 v1beta1；这里的 `view=PUBLISHER_MODEL_VIEW_BASIC` 不表示 project deployment 或生成权限。OpenAI Docs 技能用于核对该接口的原生返回和无分页契约，不用于自动选择/迁移模型。

<a id="model-fast-cache"></a>

## 模型目录、Fast 与提示缓存

2026-10-10 显式生成连接：ConversationService 过去在 ProviderConfig 分支只应用成员 model/temperature/topP/reasoningEffort，遗漏成员 fastMode。现沿 Core 接回原 ProviderFastSettings，成员非 null 开关及显式 extra 中的 fast_mode/fastMode、service_tier/serviceTier、speed 使用同一转换；无模式字段且成员未指定时直接保留原连接。普通单聊、群聊、插话、助手与重新生成的显式生成分支共用此处接线。

转换只处理模式字段及必要公开 Fast header，不从当前预设借连接、密钥、默认参数或缓存计划。其它 extra、调用方 headers/credentialMaterials/apiVersion、工具与缓存引用保留；成员模型等原覆盖行为仍先执行，Fast 本身不换模型。明确 native 档位与开关冲突、未知模型/协议等沿原 helper 报错，不改走其它模型或重发请求。Azure 仍须命中自身声明的模式，不猜部署别名；这不增加 Gemini/Vertex/OAuth/Bedrock 的 Fast 适配，也不声称维护/语音等其它显式请求已经支持全部别名。构建与证据边界见 [VERIFICATION.md](VERIFICATION.md)。

2026-10-09 目录刷新：明确refreshModelCatalog接公共models.dev/OpenRouter完整模型元数据；读取与Fast选择使用最近可用离线快照，缓存缺失回bundled，不自动下载或改用户模型。选定标准/模式标量报价可预览并沿原catalog/user价格库和版本比较安装；阶梯/额外模态费仍未转换。下文“只bundled/远程刷新全未实现”为历史，见 [PROVIDERS.md#model-catalog-refresh](PROVIDERS.md#model-catalog-refresh)。

2026-10-09 在线模型：listProviderModels已读取实际预设连接的标准 OpenAI/Responses、Claude、Gemini和已登录Copilot列表，复用原鉴权与同连接路径，返回明确分页/时间及可得元数据；不安装价格/能力、改模型/配置或替换离线目录。下文远端列表完全尚缺为历史；Vertex/Bedrock/Codex专用目录、整份catalog/价格刷新及真实权限/设备仍缺，见 [PROVIDERS.md#model-directory](PROVIDERS.md#model-directory)。

2026-10-09 Converse后续：已接explicit的system/tools/history_tail cachePoint和隐式不发markers；5m默认省略ttl，1h明确发送。usage.cacheDetails的5m/1h接原写入组件；服务等级可由明确service_tier选择，Fast开关不等同optimized latency，实时能力/结算仍未验证。下段Converse所有cache未接为历史，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09 Bedrock：native Anthropic InvokeModel 可使用原 Claude ephemeral / explicit cache_control、breakpoints 与 5m/1h TTL，具体模型/区域门槛未验证；明确 Converse 的 cache checkpoint 与 Fast/performance 参数仍未接，不声称所有 Bedrock 缓存已完成，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

第四十一批补充：Gemini implicit 来源配置不发字段，显式缓存传输/引用底座已写，尚未接持久管理/收费账目或普通聊天自动创建。下文第四十批的 Gemini 拒绝说明由 [GEMINI_CACHE.md#gemini-cache-transport](GEMINI_CACHE.md#gemini-cache-transport) 局部更新，完整缓存目标不变。

第四十批，2026-10-06。正常实现、Debug 编译和代码检查；不做哈希校验、冒烟或单测集。没有实际模型、缓存服务、账户或设备调用。

### 离线目录 API

`SimpleTavernCore.getModelCatalog(providerId?, query?, offset=0, limit=100)` 在 IO 上加载 APK 中完整的桌面 `backend/app/llm/catalog/models.generated.json` 冻结快照。来源时间 `2026-09-09T09:22:03+00:00`，48 个模型提供商、1921 个 models.dev 条目及 431 个 OpenRouter 元数据条目；后者补上 73 个独有 ID，分页共 1994 条。读取上限 2 MiB/10000 条，严格 UTF-8/JSON 与重复键检查，分页上限 200，搜索最长 256 字符。提供商支持目录 canonical/legacy 与 modelsProviderId/piId 映射，未知 ID 不借别家数据。

`getModelCapabilities(model, providerId?)` 返回原请求 ID、匹配条目、来源时间与警告。仅做忽略大小写、去 `models/` 的精确查询；多个提供商同名但未指定提供商时返回 ambiguity，不擅自取官方第一项。日期别名/家族猜测、远程刷新与账号权限不在本批实现内，查询绝不替换请求模型。响应复制集合和 mode JSON，不让调用者修改共享快照。

返回 reasoning/temperature/toolCall/reasoningToggle/efforts、上下文与输出限制、releaseDate、Fast/pro 的原始 body/headers、参考价格及 OpenRouter 参数/缓存价格。未知能力保留 null，不猜 true。价格使用源十进制文本，不经过 Double；普通 cost 和 mode cost 为源每百万 token 数值，OpenRouter cachePricing 单独明确为每 token。它们不是现价、完整模态价、计费发票或实际可用模型；不会自动安装规则或覆盖用户价格、模型和设置。`frozen_catalog_not_runtime_capability_proof` / `reference_prices_not_installed` 始终保留。

### Fast 和档位

预设/默认/会话 `fastMode`（标准化为 `fast_mode`）与群成员 `fastMode` 接解析器，成员非 null 优先，false 可关闭继承的 Fast 开关。明确 native `service_tier`/`serviceTier` 或 Claude `speed` 与开关冲突时拒绝，不悄悄丢配置。

开关自动映射要求冻结模型有声明的 Fast mode，且在精确 HTTPS 官方 OpenAI/Azure 或 Claude API origin。OpenAI 同模型发 `service_tier=priority`；Claude 同模型发 `speed=fast` 并合并 `fast-mode-2026-02-01` beta，保留已有公开 beta。Azure 部署别名未能精确查目录时不借 OpenAI 模型；兼容网关可由调用者明确配置 native service_tier，不自动猜能力。Gemini/Vertex Fast、OAuth/Bedrock 等仍需要各自适配。

native service_tier 目前支持 auto/default/flex/priority/fast，限 OpenAI Chat/Responses；speed 支持 fast/standard，限 Claude。明确 unsupported 的协议/模型开关在发送前拒绝。实际账号能否用、服务端是否限流或降档仍取决于服务端；客户端不换模型、不 fallback、不重试。`previewProvider.fastRequested` 仅为请求意图，不证明实际档位。

用量分开保留 `requestedServiceTier`、`fastRequested` 与实际 `serviceTier`。Claude 读 usage.speed，OpenAI 读 usage/envelope service_tier，流式 Claude 后帧保留开始帧实际档位并标记冲突。按实际档位匹配开始时冻结的用户价格规则；请求指定档位但服务端没返回时 `missing_service_tier`，不拿标准价/请求档位冒充实际价格。provider 明确返回可信金额时仍按已有 provider-cost 规则保留。旧历史不自动重算、不 startup 改写。

### 提示缓存配置

已保留的 desktop `promptCache` 支持 mode auto/off/implicit/explicit/best_effort，ttl、breakpoints、cacheKey、explicitMarkers。仅缺新字段时读旧 `anthropicPromptCache=off/5m/1h`。当前已知配置会翻译成 native extra；未知字段、冲突及未适配语义明确失败，不无声截断。off 不发缓存控制，不能关闭供方透明隐式缓存。auto 用已匹配提供商 cacheStrategy；未知提供商只 best_effort，不自动创建收费缓存。

Claude API：implicit 使用顶层自动 cache_control；explicit 使用 system/tools/history_tail。system 只把本次既有合并 system 文本包装为一个 text 块；tools 标记最后一项定义；history_tail 用顶层自动断点，不改历史内容、工具结果或思考签名。TTL 为 5m/1h，不把未知 TTL 自动钳成另一个值。发送前检查缺失 system/tools 和四标记上限，保留 opaque 历史中的既有标记也占槽位。

OpenAI/Azure：旧模型 implicit 发送可选 prompt_cache_key/保留时间 in_memory/24h；未指定保留时间时不改变组织默认，GPT-5.5 显式 in_memory 拒绝。按桌面已知 GPT-5.6+ ID 格式映射 options（implicit/explicit、30m），该格式不证明账号或模型可用。当前 explicit 仅新版 Responses 的 system 断点：本次 system 转 developer/input_text，最后一个非空 system 标记 prompt_cache_breakpoint；不改 opaque 返回项/加密推理/工具往返。旧模型 explicit、Chat explicit、tools/history_tail explicit 等不自动降级，明确 Unsupported。

缓存 key 使用来源隔离的公开 ID：per_chat/per_character/global 的长度前缀，或明确自定义字符串；null 可禁用 key。不做摘要/哈希、不使用凭据派生 key、不在超限时借 global；超过本地 256 字符预算要求配置短 key。所选密钥材料仍经过现有参数/元数据脱敏保护。key 会作为公开标识发给供方，不是私密存储或命中保证。

RikkaHub 新导入已知 customBodies 的上述 native 字段随共享 sourceKeys 转换，原未知源字段/原包仍保留；既有三方合并、本地编辑保护和来源并存不变，不执行导入内容或重写旧数据库。显式语音预处理目前只用其既有固定生成参数，未声称接全套 cache/Fast。

### 本批边界和后续

Gemini cachedContents 创建/生命周期/费用/归属、Vertex 特有参数、Qwen message markers、其他供方缓存模式、全部 OpenAI 显式断点、多模态完整维度、远程模型/价格刷新及安装 CAS、完整 aliases/能力调整、真实模型/账户/费用尚需补齐。cache 命中/服务器接收/收费、设备与后台耐久性均未验证。本批不是完整后端完成，完整范围仍见 STATUS.md。

OpenAI Docs 技能影响了 native 字段、实际档位和缓存模式的映射；没有迁移用户模型。官方格式参考：[Chat create](https://developers.openai.com/api/reference/python/resources/chat/subresources/completions/methods/create)、[OpenAI caching](https://developers.openai.com/api/docs/guides/prompt-caching)、[Claude Fast](https://platform.claude.com/docs/en/build-with-claude/fast-mode)、[Claude caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)。冻结目录与当前服务端不保证一致。

<a id="non-streaming"></a>

## 非流式生成

2026-10-09：Bedrock native InvokeModel 与明确 Converse 也支持原 `stream: false`。同步 JSON 结果交原事件/工具/usage/ProviderTurn 与任务消费者，仍无新生成引擎；云账号/设备未运行，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-08。四协议主链路已接普通 JSON 响应模式，正常代码检查和编译；未使用真实账号/模型或设备。

### 配置与接口

`ProviderConfig.streaming` 默认 true，调用者可明确设 false。普通 Core 生成仍使用原 startGeneration/startAssistantGeneration、任务观察和停止接口；会话 `overrides.paramsJson` 的 `stream: false` 优先于配置/来源默认。生成参数标准化识别 Desktop `streamEnabled` 和 RikkaHub `streamOutput` 别名，仅接受布尔值，不把该控制塞进 Gemini generationConfig 或普通生成参数 extra。

Desktop 已保留的同来源全局 streamEnabled 在连接选择时参与默认；RikkaHub 普通聊天按同来源当前角色配置继承 streamOutput，群聊按实际发言者，独立助手按自身配置。新导入的 canonical 控制也进入原 chatDefaults/参数合并，旧来源原字段可读取，不启动迁移或重写聊天。更高优先级 canonical 会话参数不被原始别名覆盖。`previewProvider`/维护预览的 ProviderSelection 新增 streaming；显式语音预处理连接同样读取其自身开关。

模式只控制实际 HTTP 传输，不另建生成引擎。非流式等待完整响应，再向现有事件消费者提交完整正文/思考片段、工具调用、usage 和 Done；因此仍能保存消息、执行已声明/批准的工具并发起下一轮，原原生签名/加密 reasoning/搜索 metadata 用 ProviderTurn 保留。非流式不承诺收到完整 JSON 前持久化服务器内部的部分输出，首次输出延迟也不是服务器首 token 时间。

### 传输与解析

- Chat Completions：stream=false，省略 stream_options；读取 choices[0].message，包含正文/拒绝、兼容 reasoning_content、完整函数调用和 usage。
- Responses：stream=false、store=false 和原 include/输入保持；读取完整 output/status/usage，把消息正文/拒绝和 reasoning 文字转换成原事件，原始 output 用于函数往返与签名。不是向服务器请求 SSE 再藏掉增量。
- Anthropic Messages：stream=false；完整 content 区块送入同一组区块终态处理，保留 thinking signature、redacted_thinking、tool_use/server search、usage/stop_reason；pause_turn 仍沿原活跃任务的 continuation，不恢复旧任务。
- Gemini：使用 generateContent 且不加 alt=sse；streamGenerateContent 与 generateContent 完整标准端点之间可切换，保持同 origin/版本前缀。原缓存准备/租约、parts/thought/signature/functionCall、grounding 和 usage 链路共用。

Accept 为 application/json，完整响应沿原流输入规模限制最多 16 MiB；外部响应仍须是完整 JSON。默认流式分支保持 SSE。所有模式沿原 beforeExecute 持久化回调、HTTP 取消、固定工具声明及不自动重发策略；来源并存/三方合并不变，无新表、任务框架、哈希或测试套件。

Codex 专用登录后端不是标准 OpenAI API，当前明确拒绝非流式，不静默强制流式。未知第三方忽略 stream=false 或返回非 JSON 时失败，不探测/换协议/重发。全部协议变体、额外原生工具/输出媒体和真实运行仍未验收；不表示全部后端完成。

格式依据 [OpenAI 流式与默认完整响应](https://developers.openai.com/api/docs/guides/streaming-responses)、[Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[OpenAI 函数往返](https://developers.openai.com/api/docs/guides/function-calling)、[Anthropic Messages](https://platform.claude.com/docs/en/api/typescript/messages/create) 和 [Gemini generateContent](https://ai.google.dev/api/generate-content)。OpenAI Docs 技能用于核对 JSON/SSE 与完整函数响应差异，没有迁移或替换任何用户模型。

<a id="provider-catalog-routing"></a>

## 提供商离线目录与连接路由

2026-10-09 AWS鉴权：amazon-bedrock固定区域连接已允许明确aws_sigv4，默认Bearer不变；目录adapterStatus保留bedrock_bearer历史标识，不作为所有鉴权/上游验证证明。SigV4与结构化加密引用已接，STS/自动刷新/其它云链未接，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09：`amazon-bedrock` 的 adapterStatus 为 `bedrock_bearer`，默认保留 Anthropic InvokeModel，supportedProtocols 增加明确 `bedrock_converse`；固定区域 root 与 API-key/Bearer 已接，不改 Mantle 项或冻结原始 JSON。下文 Bedrock codec 全 Unsupported 是历史；SigV4、Converse cache/全参数、S3/大文件与真实云权限仍待补，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09：google-vertex增加明确的google_service_account/googleVertex项目配置入口，聊天与预处理支持服务账号兑换Bearer token和固定native/OpenAI兼容根地址；原目录Express模板/API-key默认值不变，不从目录自动启用IAM。下文Vertex IAM全未实现是历史状态；其他云鉴权、GCS/Files/cache/第三方publisher等仍待补，见 [PROVIDERS.md#provider-oauth](PROVIDERS.md#provider-oauth)。

2026-10-07 后续：Codex adapterStatus 为 `oauth_codex_compatibility`，设备码/PKCE 与固定 Responses root 已接；下文 Codex unsupported_oauth 是历史。它是桌面兼容适配，不是官方第三方动态注册/API 接入证明，见 [PROVIDERS.md#codex-oauth](PROVIDERS.md#codex-oauth)。

2026-10-07 更新：Copilot 已有 GitHub.com 设备码适配，adapterStatus 为 `oauth_device_github_com`；下文 Copilot unsupported_oauth 为历史，Codex 仍 unsupported_oauth。登录接口和私有兼容/未验证边界见 [PROVIDERS.md#provider-oauth](PROVIDERS.md#provider-oauth)，目录不启动登录或联网。

2026-10-05，第三十九批。正常编译/代码检查，未进行真实模型/账户/设备验证；本文不替代 STATUS.md 全后端清单。

### 离线目录

公开 getProviderCatalog() 返回可序列化 ProviderCatalogSnapshot：generatedAt、source、providers、warnings。核心打包桌面当前 providers.generated.json（39 源条目）和 providers.overlay.json（50 维护条目），按 piId 合并、补生成层未覆盖条目，最终 50 条；保留 canonical/legacyId、名称/地区、模板/占位符、协议/路径、鉴权、缓存/Fast 声明、OAuth/变体、文档/提示、环境变量名/未支持 API 和建议模型。

generatedAt 是源生成时间 2026-09-09，不是实时服务时间。没有远端刷新/模型列表请求、环境密钥读取或设置写入，不自动选择首个模型。字段是冻结目录声明，不证明账号/区域/网关支持；warnings 含 frozen_catalog_not_runtime_capability_proof。缓存/Fast、suggestedModels 与 modelsProviderId 不表示已执行或已加载完整模型/价格目录。

adapterStatus：standard_http（普通四协议 HTTP 编码路径）、oauth_device_github_com（Copilot 兼容适配）、oauth_codex_compatibility（Codex 兼容适配）、bedrock_bearer（native InvokeModel / 明确 Converse）、unsupported_oauth（其余 OAuth 目录项）、unsupported_protocol_variant（尚未适配的变体）、missing_template。不支持项不会冒充普通 API key 成功；这些适配状态都不是上游实测证明。目录 128 KiB 单资源读取预算、256 条预算，严格 UTF-8/JSON/重复身份检查，缺失/损坏明确失败。公开结果复制集合，不向调用者暴露内部可修改目录。

### 连接选择与模板

同一 ProviderConnectionSettings 用于聊天/群成员、维护和显式语音预处理。providerId 先 canonical/legacyId 匹配，再按显式地址的 scheme/host/port 和最长路径前缀识别；未知 ID 有明确 baseUrl 时仍可作为自定义连接，不猜成别的提供商。没有配置地址时，只能用明确所选目录项模板，不借其他来源/global/第一个目录项。

providerParams 是公共文本对象，至多 32 项、16 KiB、键 64 字符、值 1024 字符，敏感凭据名拒绝；不是任意鉴权对象。模板每次只将所需公开参数填成一个 DNS label/路径段，缺失失败，拒绝斜杠/查询/fragment/控制符和目录穿越。DNS label 最多 63 字符，路径占位值最多 256；模板 8192 字符，baseUrl 仍仅 HTTP(S)、无 userinfo/query/fragment。主/附加密钥仍来自加密引用。

| 目录条目 | 模板参数 / 已接路径 |
| --- | --- |
| azure-openai-responses / legacy azure-openai | resource；现代 /openai/v1，API key 头，用户模型原样 |
| cloudflare-ai-gateway | account_id、gateway_id；compat 与同源 anthropic 协议前缀 |
| cloudflare-workers-ai | account_id；accounts/.../ai/v1 |
| google-vertex | 快照的 Express /v1/publishers/google；不表示 regional ADC/IAM |
| aws-bedrock-mantle / legacy aws-bedrock | region；快照标准兼容模板，不代表最新 Mantle 地址已核准 |
| amazon-bedrock | region；Bearer native InvokeModel 与明确 Converse，同步 / AWS 事件流 |

明确 protocol 不改变；明确 providerId 且未填 protocol 时继承目录默认，既有未绑定目录的空协议连接仍默认 Chat。auto 按模型家族和明确/default/support 集合选择；目录补未声明的默认和支持集合，但用户明确能力优先。Google /openai 与原生、其他提供商所有支持情况受声明约束，不发送试探请求或失败后换协议。显式 authStyle 优先，缺失可继承明确绑定/匹配默认协议的目录鉴权；官方 Google 原生默认 x-goog-api-key，不把兼容层 Bearer 误作 API key 格式。显式 Bearer 等仍由用户选择，不尝试登录。

protocolPaths 现在接用户或目录的四种原生协议完整前缀模板，最多 4 项。目录路径只有当前 baseUrl 等于其渲染默认/协议地址时才自动应用；自定义网关地址不会被目录公共地址取代。所有路径必须同 scheme/host/有效端口；跨 origin 明确拒绝。不改已存设置，也不替换模型。预览增加 catalogProviderId/authStyle/apiVersion，返回有效 baseUrl，不含解密密钥。

### URL 查询与标准完整端点

providerParams 的 api_version / api-version 或顶层 apiVersion 转入运行时 api-version 查询参数；三个声明不一致拒绝，值限 128 ASCII token 字符。不会给现代 Azure 自动加日期版本，也不根据模型名自动造旧 deployment 路由；已有明确 legacy deployment baseUrl 可配显式版本，但所有 Azure 旧协议语义仍未实测。

authStyle=query_key/query-key 明确选择时，主加密凭据仅在 HTTP URL 构造阶段加 key 查询，不持久化到 baseUrl/预览/task/账目；不是 OAuth。不允许 URL 已含管理的 key/api-version 再叠加。Gemini 原有 alt=sse 保留，密钥实际编码由 HttpUrl 完成，原值和 URL 编码值均参与现有用量/费用/错误/搜索展示 metadata 脱敏。Google 当前 API 文档推荐 x-goog-api-key，本功能不自动把目录改 query，也不证明所有服务接受 query 鉴权。

客户端可接版本根地址或已完整的 chat/completions、responses、messages 端点；同操作完整地址不重复拼接，其他标准操作后缀先还原根再构造目标端点。Request/URL/头均在账本 beforeExecute 前验证，重定向/重试/follow-up 禁止保持，不增加旧任务恢复或失败重发。

### 来源、安全与剩余项

Desktop/RikkaHub 已保留配置通过同一选择链解析，源凭据不自动导入；稳定映射/自动合并并存/本地编辑保护/停止中断规则不变，旧资料不启动重写。Room v2/25 表/native format v1/外部依赖未改。自定义 HTTP 头与加密引用继续见 PROVIDERS.md#provider-http-options（其第三十八批 query/template 待补段落为历史，以上范围覆盖更新）。

OpenAI Docs 技能实际读取 [Python Azure 连接配置](https://developers.openai.com/api/reference/python) 与 [Ruby Azure v1 路由](https://developers.openai.com/api/reference/ruby)，区分现代 /openai/v1 与显式 legacy/api-version，不自动迁移用户模型。另核对 [Gemini 鉴权参考](https://ai.google.dev/api/) 的默认 key 头。目录地址仍按本仓库冻结快照展示，不将其等同于最新云 API 或设备验证。

仍需实现完整 models/prices/catalog 更新、Fast/cache/全参数、Bedrock SigV4/Converse 缓存与扩展、其余 OAuth 登录与生命周期、全部云 IAM/版本/权限和真实上游、全来源版本/工具/TTS/native/沙箱/设备等完整要求。普通 Debug/ZIP 只证明产物和代码，不表示整个后端完成。

<a id="provider-files"></a>

## 大文件输入与临时远端文件

2026-10-09：Bedrock Runtime 已接生成但没有这里的供应商 Files 上传接口。native InvokeModel 与明确 Converse 的请求内上传句柄会在远端调用前失败，不能借 Anthropic `/v1/files` 发送 Bedrock 密钥；支持范围为内联附件，S3/大文件仍缺，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-08：原聊天附件复制、普通/群聊/独立助手的生成、选中历史和工具续轮接大文件上传，不增加表、依赖或任务框架。Desktop/RikkaHub来源仍自动合并并存。本批不是文件搜索、远端文件库或整套媒体完成。

### 使用方式

仍先调用 `importChatAttachment(chatId, File/Uri/bytes, ...)`，再将返回ID交原生成入口的 `attachmentIds`。复制上限改为256 MiB；只复制用户选定输入，不保留URI权限或挂载源文件。原资产复制上限不变。PDF、音视频、图片超过6 MiB，文本超过1 MiB，以及已列Office文档，在生成准备时用请求内磁盘引用，不读成整文件Base64；小文本/原支持二进制仍内联。

| 协议 | 新上传输入 | 请求引用 |
| --- | --- | --- |
| Chat Completions | PDF | file.file_id |
| Responses | PDF、已列Word/Excel/PowerPoint/ODT/RTF、文本、图片 | input_file.file_id / input_image.file_id |
| Claude Messages | PDF、图片、文本 | document/image.source.type=file、file_id |
| Gemini Generate Content | PDF、图片、音频、视频、文本 | fileData.mimeType/fileUri |

Office MIME及常见扩展名识别共用AttachmentMimeTypes；不是本地解包/转PDF。文本上传对Claude/Gemini明确使用text/plain，字节不改。当前Claude Files官方已不要求beta header，不自动加入旧beta；用户配置的头仍保留。Chat大音频/图片、Responses音视频、Claude音视频/Office没有被伪装成PDF或自动换模型；用户输入在上传前失败，工具/助手补充沿原“附件保存但未解读”提示。

### 发送与生命周期

ProviderFileClient由请求内LlmFileSession拥有，普通/独立助手生成的连续工具轮共用同一session，群聊每个实际说话者单独创建。相同托管文件按attachmentId、路径、MIME、字节数和修改时间复用，不做内容哈希；每个历史出现仍计请求预算。会话固定原提供方连接，不跨成员、聊天或下一次生成复用。文件由磁盘分块写HTTP，取消检查和原阻止HTTP自动重发机制继续生效。上传元数据、处理轮询和删除不调用推理的beforeExecute，不冒记模型请求/语音计费。配置的代理根、原鉴权/公用头/API-version沿用，完整标准操作URL也能推导同根files地址，不切去供应商直连。

OpenAI/Claude使用multipart；OpenAI PDF/文档用user_data、图片用vision。Gemini先创建resumable会话，再上传finalize，PROCESSING每2秒读一次原文件状态，最多10分钟，ACTIVE后才发模型请求，FAILED立即失败；没有自动断点重传。会话URL跨来源时不发送配置的凭据/自定义头；传输错误不回显带会话令牌的URL。

说话者完成、失败或停止后，尝试删除仅属于session的远端文件，不在每个工具轮后删除，不删除本地附件或账号已有文件。Gemini缓存POST可能执行后，属于其前缀的源文件改为保留到上游到期，不假设提前删除源文件不会损坏缓存；响应丢失/失败也不提前删这些已用于POST的源文件。单次删除最多5秒，整批约30秒；删除失败不把已完成推理改成失败。OpenAI/Claude上传指定1小时过期，Gemini沿官方48小时生命周期；读取上游返回的expires_at/expirationTime，未报告则按请求过期值/默认生命周期限制本地复用，不冒称已确认服务端过期。工具续轮前剩余不足60秒则重新上传，不自动重试失败的推理。响应丢失/进程被杀无法确认创建结果时，不凭猜测ID补删或重发。

GenerationMetadata.fileTransfers记录attachmentId/filename/bytes及uploading、processing、ready、reused、upload_unconfirmed、deleted/delete_failed、reuseCount和清理HTTP码，沿原500ms候选保存及终态记录；不存上传URL、远端可复用ID、凭据或本地路径。LlmClient的onFileTransfers回调供原ConversationService接收，续轮候选可见此前上传及本轮复用，停止后的清理结果也能进入最终记录。早期工具候选保留当时的状态，最后候选记录session清理。历史/任务仍存托管附件引用，下一次生成重新上传，不复用已删除的远端文件。原LlmClient.stream仍自动创建/关闭单次session；显式fileSession调用者应先结束或取消收集，再close，不把close当作停止推理入口。

### 预算与余项

本地单文件256 MiB、一个上下文上传出现次数总量512 MiB/原20个附件出现次数；内联仍单二进制6 MiB、文本1 MiB、读取12 MiB、编码16 MiB。OpenAI文件输入出现总量小于50 MB，上游模型、页面、编码及更小的提供方限制仍由实际请求决定；本地上限不是所有模型能接256 MiB的承诺。`getChatAttachmentContent`仍为原有界内联读取、不公开大文件磁盘句柄；附件播放已改为从托管文件按需读取、上限256 MiB，见 [SPEECH.md#speech-playback](SPEECH.md#speech-playback)。大文件复制可沿原startCopyAttachmentToSandbox。

2026-10-08：自动Gemini historyTail现在支持托管上传文件。GeminiCacheFiles从已上传文件中选择实际缓存历史的来源，创建前及POST checkpoint核对TTL不超过最早源文件到期（预留60秒）；超出时报错，不偷偷缩短用户TTL。POST可能执行后保留源文件至上游到期，候选fileTransfers记录cache_retained/retainedUntil，不等同deleted。缓存操作/通用账目增sourceFilesExpireTime，GET/续期/删除任务沿引用传递；TTL和expireTime更新在登记及发送前限制到源文件寿命内。明确删除缓存并不立即删除其源文件，缓存更早到期也沿Files到期释放；没有新增定时清理/重启重放/远端文件索引。

同一进程的缓存前缀比较按已有托管身份对应原URI与新上传URI，正文、MIME、角色、工具、opaque签名仍逐值匹配；只在比较中替换fileData.fileUri，不改新内容实际发送的URI。缓存绑定只保存脱离session回调的身份/到期快照，不保留StreamingAnswer。下一次生成仍先上传自身文件，命中时可使用原缓存而不重复创建它，本次不属于缓存的上传仍照常删除；尚未消除跨生成的这次冗余上传。

手动创建现也支持附件：原startGeminiCacheCreate签名不变，在GeminiCacheCreateRequest.contents的ContextMessage.attachments放入已导入/历史托管附件引用即可。任务工作协程使用MessageAttachmentReader解析实际存储行，小文件内联、大文件上传；不接受调用方media中的路径、远端句柄或字节作为托管附件替代。GeminiCacheClient.createWithFiles复用ProviderFileClient，不另造上传器。原phase新增preparing，GeminiCacheTaskProgress.fileTransfers保存上传/处理/清理或cache_retained；准备阶段httpAttempted仍false，仅缓存POST checkpoint置true。源文件最早到期在POST前写入sourceFilesExpireTime，并与任务/通用账目同事务更新。取消/错误沿原worker/finally清理，缓存POST可能发送后的来源仍保留至Files到期。仅创建/管理缓存，不自动发一次模型生成或把手动缓存加入自动选择索引。

跨工具轮上传复用、自动历史缓存源文件保留、手动缓存附件上传及明确选择用于生成已接入；选择支持前缀替代或额外前置上下文，沿原源文件寿命/身份比较，见 [GEMINI_CACHE.md#gemini-cache-generation](GEMINI_CACHE.md#gemini-cache-generation)。跨生成免上传/缓存源文件更早显式清理、崩溃后精确远端清理/账号文件管理、完整native引用审计（含fileTransfers.attachmentId）、更大供应商文件上限/分片续传、外部URL、更多文件格式/原生云平台/Codex适配与真实模型/设备仍待补。当前native恢复保留原ID、聊天分叉共享托管附件，不需要凭空新增ID映射；这不替代整套引用审计。没有真实账号上传、哈希、冒烟、新单测或前端改动；普通编译不替代完整后端验收。

2026-10-08复用批使用OpenAI Docs核对[上传及过期字段](https://developers.openai.com/api/reference/resources/files/methods/create)。本轮只读[Gemini CachedContent](https://ai.google.dev/api/caching?hl=en)、[expirationTime字段](https://ai.google.dev/api/files?hl=en)、[Files自动到期](https://ai.google.dev/gemini-api/docs/files?hl=en)：官方支持上传文件加入缓存及Files自动到期，但未在所读页面保证提前删除源Files安全；通过保留源文件解除限制，不依赖这一未证实假设。

协议依据：OpenAI Docs的[文件输入](https://developers.openai.com/api/docs/guides/file-inputs)、[上传/过期字段](https://developers.openai.com/api/reference/resources/files/methods/create)、[图片文件ID](https://developers.openai.com/api/docs/guides/images-vision)，[Claude Files](https://platform.claude.com/docs/en/build-with-claude/files)，[Gemini Files上传/状态](https://ai.google.dev/api/files)、[生命周期](https://ai.google.dev/gemini-api/docs/files?hl=en)。

<a id="provider-generation-params"></a>

## 提供商生成参数与来源转换

2026-10-10：RikkaHub 普通提供商与模型 providerOverwrite 共用源映射，OpenAI/Gemini/Claude 和 Google Vertex IAM/Express 已接新导入，OpenAI生成路径接原传输；独立替代凭据、不混父连接、不启动迁移，原并存/三方合并不变。两个Luna继续实际编码普通提供商和Google映射，主代理接原Vertex/解析；未知类型、含query路径及旧未转换配置明确拒绝，完整范围与编译记录见 [PROVIDERS.md#rikka-provider-override](PROVIDERS.md#rikka-provider-override)。

2026-10-09 Converse扩展：top_k/thinking/预算/output_config.effort -> additionalModelRequestFields，service_tier -> serviceTier.type，response_format.json_schema -> outputConfig.textFormat（schema作为原样JSON字符串）；cache控制只检查、由传输编码cachePoint，不写入原生请求根。未给max_tokens时尊重Converse模型默认，不套4096；其它未支持字段仍失败，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09 Bedrock：默认 native InvokeModel 沿原 Anthropic 参数；明确 Converse 映射 `inferenceConfig.maxTokens/temperature/topP/stopSequences`，其他明确控制不静默丢弃。完整模型专属参数、cache checkpoint 与性能参数未接，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

第三十八批更新：本文第三十七批 headers/auth/auto 待补说明为历史；当前已接静态鉴权、公共头/加密引用、RikkaHub 新导入助手头与有限 auto，详见 [PROVIDERS.md#provider-http-options](PROVIDERS.md#provider-http-options)。未知正文/连接覆盖、完整 catalog/云/OAuth/Fast/cache/null 清除/全参数仍未完成。

第三十七批，2026-10-05。GenerationParameters 统一标准化字段、检查数值/结构/协议并生成原生 wire JSON；ProviderResolver 解析配置时先验证，四种 LlmClient 请求构造再验证。没有请求试探、参数失败后的删字段重试、模型替换或旧任务续接。只做代码检查和普通 Debug 编译，没有真实调用/收费/用户包/设备证据。

### 已接字段

| 控制 | Chat Completions | Responses | Anthropic Messages | Gemini generationConfig |
| --- | --- | --- | --- | --- |
| 输出上限 | max_tokens 或显式 max_completion_tokens | max_output_tokens | max_tokens（未设仍默认 4096） | maxOutputTokens |
| 温度 / topP | temperature / top_p | temperature / top_p | temperature / top_p | temperature / topP |
| topK | top_k（兼容网关显式字段，不保证官方 OpenAI 支持） | 未适配 | top_k | topK |
| 停止词 | stop，最多 4 | 未适配 | stop_sequences，适配器最多 64 | stopSequences，最多 5 |
| 频率 / 存在惩罚 | frequency_penalty / presence_penalty | 未适配 | 未适配 | frequencyPenalty / presencePenalty |
| seed | 有符号 64 位；不保证确定性 | 未适配 | 未适配 | 有符号 32 位 |
| 思考 | reasoning_effort；显式 thinking enabled/disabled 兼容网关片段 | reasoning.effort / summary，text.verbosity | 显式 thinking disabled/adaptive 或 enabled+budget_tokens；output_config.effort | thinkingConfig 的 thinkingBudget / thinkingLevel / includeThoughts |

camelCase、snake_case 输出限制和停止词别名标准化；同层 canonical 优先，原生嵌套 generationConfig/thinkingConfig/reasoning 按其片段合入，层间为来源默认→预设→会话→成员温度/topP/effort。null 仍沿用下层，不新增清除字段语义。未理解的源字段保留而不任意变成请求。extras 为标准化字段，结构化 stop/thinking/output_config 用 JSON 文本；低层调用者新增其他 extra 需适配，不再无声丢弃。

有界 extra 总量 64 KiB，停止字符串单项 4096 UTF-8 字节并检查 Unicode，不裁掉换行或自动截断。输出/topK 正整数，温度范围为 Anthropic 0..1、其他 0..2，topP 0..1，惩罚 -2..2；非法值不当“没配置”。enabled 手动思考须明确 1024 以上预算、低于实际 max_tokens，不加大发送上限；与温度非 1/topP/topK 冲突拒绝。Gemini 预算与 level 不能同时传；并非所有模型支持所有字段/档位。Anthropic/Gemini 不再无声忽略普通 reasoning_effort，需选其原生控制；完整模型能力/catalog/统一档位自动解析仍待补。

实现按 [OpenAI Chat](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Responses](https://developers.openai.com/api/reference/python/resources/responses/methods/create)、[Claude Messages](https://platform.claude.com/docs/en/api/messages/create)/[手动思考](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)、[Gemini REST](https://ai.google.dev/api/generate-content) 官方格式核对；没有依据当前文档自动换掉用户模型。手动/自适应模式和采样可用性仍由实际模型决定，服务端失败不会回退或重发。

### 来源配置与 RikkaHub

来源默认值及维护模型按稳定 source_settings / 0::settings 映射读取，检查 entityType/目标来源；没有映射时只允许唯一同源配置，无配置可空，多个配置明确歧义，不取第一个、不借另一来源/global。显式预设已有 generationDefaults 不依赖无关来源默认。映射目标和 namespace 1024 字节，先 SQL 看长度再读目标；settings 256 KiB 后仅取单列，不读取完整助手行。配置 JSON 256 KiB/严格输入解析，维护模型仍按既有覆盖行为，不宣称已修好全部维护模型预设选择。

RikkaHub 固定官方参考提交 [6c903fe Assistant](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)/[Provider 参数](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/provider/Provider.kt) 的 customBodies 是 key/value 列表，模型与助手已知生成字段可转换，至多 256 项/64 KiB。聊天按助手基础控制→声明 REASONING 模型的 level→模型自定义生成片段→助手自定义片段的顺序合并，原始配置仍脱敏保留，重复导入继续三方合并保护本地修改，不在 startup 批量重写旧参数。

声明 REASONING 的模型：OpenAI 非 auto level 映射原生 effort（off→none）；Anthropic off→disabled，其余 adaptive、非 auto 加 effort，去掉参考流程不发送的普通采样值；Gemini AUTO 保留模型默认预算并要摘要，3 系列用 level，旧系列按固定样本 budget，2.5 Pro 关思考例外和 includeThoughts 开关按参考处理。未知 level/未声明能力不猜。参考提交三协议源码已只读核对，用户版本及每家兼容网关能力仍未验证，不能宣称整个 RikkaHub 行为已迁完。

未知 custom body 仍保留并报告 deferred，不接受任意正文替换消息/工具/搜索/stream 或凭据。providerOverwrite 的普通三类与 Google Vertex 替代连接已按本页顶部补齐；旧未转换/未知对象在生成、目录及预处理的共享解析仍 Unsupported，不悄悄向父提供商发送。导入脱敏补 x-api-key/x-goog-api-key/x-ms-api-key/x-auth-token/Proxy-Authorization 等规范化名字，列表 header 的 value 和对象字段一起处理；公共头/加密引用的后续实现见 PROVIDERS.md#provider-http-options，不声称任意字段全部秘密可识别，旧已导入行不启动自动重写。所有自定义字段和 null 清除语义仍未适配，不把“保留”当“执行”；其余提供商/来源/设备余项见 STATUS.md。Room v2/25 表/format v1/依赖不变。

<a id="provider-http-options"></a>

## 请求头、静态鉴权与 auto 协议

2026-10-09 SigV4：明确amazon-bedrock的aws_sigv4/aws-sigv4使用加密结构化AWS凭据而非API-key字符串；最后请求边界生成AWS头，不能手动覆盖签名字段，普通提供商仍不支持该样式。默认Bearer和原公共头规则保持，签名SHA/HMAC不是文件校验，STS/自动刷新未接，见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-09 Bedrock Runtime：固定区域 native InvokeModel 与明确 Converse 已接 API-key/Bearer，使用原加密凭据引用；Converse 不接受其他静态鉴权，SigV4 尚未实现。默认仍是 Anthropic InvokeModel，Mantle 兼容项不变，详见 [PROVIDERS.md#bedrock](PROVIDERS.md#bedrock)。

2026-10-07 更新：Copilot 设备码登录已单独适配，运行时 bearer/固定兼容头与不加 `/v1` 的根路径由适配器生成；不会把 OAuth 风格通用别名化为静态 API-key。其余 OAuth/云 IAM 仍未接，见 [PROVIDERS.md#provider-oauth](PROVIDERS.md#provider-oauth)。

第三十九批更新：本文第三十八批 query/template/catalog 待补为历史；现已接离线目录/模板与同源协议路径、运行时 query_key/API-version，详见 [PROVIDERS.md#provider-catalog-routing](PROVIDERS.md#provider-catalog-routing)。OAuth/Bedrock 专用编码/完整模型价格/Fast/cache/云 IAM 和完整后端继续。

2026-10-05，第三十八批。仅普通编译/代码检查，无真实上游、账户或设备验证。完整余项仍见 STATUS.md。

### 鉴权与配置

authStyle 现在实际决定发送方式，不再只检查后忽略。ProviderResolver、显式语音预处理和 LlmClient 共用 ProviderHttpOptions；密钥仍由公开 setProviderCredential(credentialId, apiKey) 写加密存储。

| authStyle | 请求头 |
| --- | --- |
| 未填、旧 api_key 别名 | Chat/Responses Bearer；Anthropic x-api-key；Gemini x-goog-api-key |
| bearer | Authorization: Bearer + 主凭据 |
| x-api-key / x_api_key | x-api-key + 主凭据 |
| api-key | api-key + 主凭据，区别于旧 api_key 别名 |
| x-goog-api-key / google_api_key | x-goog-api-key + 主凭据 |
| none | 不加默认主鉴权头，须有敏感头加密引用或 allowUnauthenticated=true |

OAuth device/PKCE、query_key 仍 Unsupported，不把 API key 当登录/换发。api-key 头不等于 Azure deployment/api-version/云模板/IAM 支持。Anthropic 默认 anthropic-version=2023-06-01，可被明确公共头覆盖。

headers/customHeaders 接对象映射和 RikkaHub name/value 列表（也接受 key）。公共值只能文本；敏感 Authorization/API key/Cookie/token/secret/password 名称必须加密引用，不接受明文：

```json
{
  "authStyle": "none",
  "customHeaders": [
    {"name": "Authorization", "credentialId": "gateway-access", "prefix": "Bearer "},
    {"name": "OpenAI-Project", "value": "project-id"}
  ]
}
```

引用通过相同公开凭据接口存取。prefix/suffix 是可选公共文本、只能配合引用。缺值/凭据、同时明文与引用、非法描述符发送前失败；非敏感头也可引用并同样脱敏。options 是不可序列化的运行时对象，不能进入 task/usage/selection JSON，toString 全部隐藏。

覆盖：预设 headers→customHeaders、selectedModel 同顺序、会话 paramsJson 同顺序；名称大小写不敏感、后者覆盖前者。空集合/null 不删除前层头。最多累计 256 条输入、最终 64 头，名称 128 字符、值 16384 字符、最终显式头合计 60 KiB；凭据 8192 字符、引用 ID 1024 字符。仅 HTTP token 名称、ASCII 可见值/空格；禁 CR/LF/控制字符、Host/Content-Length/Content-Type/Accept/连接与传输控制头覆盖。

一次锁内读取仅选中的最多 65 个凭据，不取整个库。预览 hasCredential 表示任一选中凭据存在，不证明全部引用齐全或可发送；resolve 核对全部引用/鉴权/预算。预览身份及发送配置元数据含选中密钥拒绝。聊天/总结/状态维护/预处理 usage/cost/错误、原生搜索展示 metadata 均检查所有选中密钥；不是检测未知字段/模型正文所有秘密，不改 opaque 协议内容或旧用户资料。

Request/Call 和头验证在账本 beforeExecute 之前；现有禁止重定向/连接重试/follow-up 保持，认证失败不自动刷新或重发。

### auto 边界

显式协议不改变。auto 按桌面 claude、gemini/gemma、OpenAI 家族规则选 Anthropic/Gemini/Responses，未知沿用默认；不会换模型或 HTTP 探测。可明确声明 defaultProtocol/supportedProtocols（最多四种原生协议；默认必须在集合内）。

无声明时只有精确 api.openai.com、api.anthropic.com、generativelanguage.googleapis.com 用已知默认/支持集合；Google /openai 默认 Chat、其余默认 Gemini。其他网关默认 Chat，原生协议须明确能力声明，不只凭模型名猜。切 Gemini 仅官方 Google host 去 /openai；切 Anthropic 可去 /chat/completions 后缀，保留原 origin/端口/其他前缀。预览展示实际协议/有效 baseUrl。

auto 非空 protocolPaths 模板明确 Unsupported，不从目录换域名发送原凭据。完整 generated/overlay/legacyId/catalog、云模板/参数/路由及桌面调整原因尚未迁入，不能宣称完整桌面 auto 兼容。

### 来源与剩余范围

Desktop headers/customHeaders 保留执行已支持形式；RikkaHub 新导入 selectedModel/助手 customHeaders 接覆盖链，不放生成 extra。来源敏感值移除、名字保留待补加密引用占位，须补配才能发送，不自动导入源密钥。未知 customBodies/providerOverwrite 保留并 deferred/Unsupported，不当任意正文扩展。

稳定来源映射/合并并存/重复导入/本地编辑保护不变；旧聊天未映射助手头不在启动补写。可明确配置或重新导入，合并仍保护本地修改。Room v2/25 表/native format v1/外部依赖不变。

OpenAI Docs 技能核对 [API Authentication / Request headers](https://developers.openai.com/api/reference/overview)：Bearer、组织/项目头和头预算。本实现单值更小、显式头保留约 4 KiB 常规库头余量；不代表已经验证所有代理/中间层。

OAuth/cloud/query auth、完整 catalog/云路由、Fast/cache、全参数/多模态/工具/TTS/来源版本和真实包/设备与完整清单继续，普通 Debug 不代替整个后端完成。

<a id="provider-oauth"></a>

## 提供商鉴权：Copilot、Codex 与 Vertex

2026-10-09 后续：Vertex native 缓存创建/列表/查询/更新/删除及现有生成选择/来源 explicit 已接，服务账号正常 token 刷新保留同账号运行时绑定。下文“Vertex cache 管理尚缺”不再适用于该原生路径；GCS/大文件、CMEK、OpenAI extra_body、跨进程前缀等仍缺，见 [GEMINI_CACHE.md#vertex-cache](GEMINI_CACHE.md#vertex-cache)。

### 2026-10-09 Vertex 服务账号

新增 `configureGoogleVertexPreset(presetId, GoogleVertexOptions(projectId, location="global", apiVersion="v1"), expectedUpdatedAt)` 与 `setGoogleVertexServiceAccount(presetId, serviceAccountJson)`。前者明确切换该预设到 `google-vertex` / `google_service_account`，只保存公开项目、区域、版本和固定 Google 路由；保留模型、其他设置和来源身份，使用现有 Room 事务/更新时间检查。后者接受 Google `service_account` JSON 中的 RSA PKCS8 私钥、client_email 和可选 private_key_id，仅保存到原加密凭据库 `oauth:google-vertex:<presetId>`，绑定预设来源，不存原始 JSON/私钥到 Room、配置或任务记录；导入时不联网。普通备份不包含这些材料，只有已有显式密码加密凭据备份可包含。

`getProviderOAuthStatus` 离线返回本地账号配置状态、email 和 token 到期/需刷新状态，loggedIn 不表示已证实 IAM 权限。`refreshProviderOAuth` 或新生成连接缺少/即将到期 token 时，用 RS256 JWT 和 cloud-platform scope 向固定 `https://oauth2.googleapis.com/token` 兑换 Bearer token；token 回写加密库，刷新不占用跨网络的快照租约，配置/凭据中途改变则拒绝回写。`logoutProviderOAuth` 也删除 Vertex 记录，支持已删除预设遗留凭据；不是云端撤销，不取消已有生成。没有设备码/浏览器登录、后台定时刷新或 401 后重放生成。RS256 是 Google 鉴权协议要求，不是文件哈希校验。TTS 预处理离线准备不联网，缺少可用 token 时要求先明确刷新。

聊天、维护和 TTS 预处理共用原 ProviderResolver/协议编解码：Gemini native 路由为 `/<version>/projects/<project>/locations/<location>/publishers/google`；明确 OpenAI compatible 时为 `.../endpoints/openapi`，仍由用户选择实际模型 ID。global 使用 aiplatform.googleapis.com，其余区域使用 `<location>-aiplatform.googleapis.com`。只支持 v1/v1beta1，不允许 IAM token 跟随自定义 protocolPaths/variant 发往其他端点；不改写模型或重试协议。原 Vertex Express API-key 预设不自动切到 IAM，离线目录仍描述 Express 默认模板。

本批仅实现服务账号和上述两个既有协议入口；ADC、用户 OAuth、WIF/账号模拟、第三方 publisher、自部署 endpoint、GCS 文件导入、Vertex 专用 Files/cache 管理、全部云参数、Bedrock 与真实账号/设备验证仍未完成。没有新表、依赖、前端、文件校验、冒烟或单测。依据 [Google 服务账号 OAuth](https://developers.google.com/identity/protocols/oauth2/service-account)、[Vertex native 推理](https://cloud.google.com/vertex-ai/generative-ai/docs/model-reference/inference) 和 [Google 官方 OpenAI 示例](https://github.com/GoogleCloudPlatform/generative-ai/blob/main/gemini/chat-completions/intro_chat_completions_api.ipynb)。

2026-10-07。Copilot 与 Codex 兼容登录、请求衔接和语音预处理鉴权已接；没有实际登录账号、调用模型或运行设备。Codex 设备码/PKCE、请求及官方新方案边界详见 [PROVIDERS.md#codex-oauth](PROVIDERS.md#codex-oauth)。云 IAM、Enterprise Server、全参数/能力与完整 OAuth 恢复关系仍待补。以下主体为 Copilot 行为，不把两种提供商端点混用。

### 已接能力

选中明确模型预设，配置 `providerId: "github-copilot"`（兼容 `copilot`），`authStyle: "oauth_device"`；模型仍由用户选择，不自动替换。默认 API 根地址为 `https://api.individual.githubcopilot.com`，允许 GitHub.com 的 individual/business/enterprise 三个已知 API 根地址，不允许自定义 Enterprise Server、代理地址或协议路径。

Core 接口：

- `startProviderDeviceLogin(presetId)`：明确发起登录，返回 loginId、用户验证码、GitHub 验证网址、到期时间、轮询间隔和 nextPollAt。
- `pollProviderDeviceLogin(loginId)`：每次只执行一次轮询；过早调用不联网。authorization_pending 保持等待，slow_down 延长间隔，拒绝/过期/失败返回终态；成功交换并保存 Copilot 凭据。交换失败抛出安全错误并把会话置 failed，不重发已消费设备码。
- `cancelProviderDeviceLogin(loginId)`：取消并删除等待中的进程内会话；已完成的登录返回原终态，不假装回滚凭据。
- `getProviderOAuthStatus(presetId)`：离线读取 loggedIn、expiresAt、needsRefresh；loggedIn 表示本地存在有效格式的登录记录，不表示远端确认订阅或账号状态。
- `refreshProviderOAuth(presetId)`：明确进行一次令牌交换。
- `logoutProviderOAuth(presetId)`：删除该预设本地凭据和等待会话，也可清理已删除预设的遗留记录。不是 GitHub 服务端撤销，不停止已经发出的生成。

前端展示用户验证码与固定 `https://github.com/login/device` 链接，等待用户授权，再按 nextPollAt 调用 poll。后端不打开浏览器、不监听 localhost、不自行创建轮询任务。设备码、GitHub/Copilot token 不出现在公开 DTO、Room 设置、任务或用量记录里。最多 16 个待登录会话，重启后丢弃，不自动恢复登录；已完成 token 持久保存。

普通聊天、独立助手及沿用 ProviderResolver 的记忆/状态请求，在新生成解析连接时读取该预设 OAuth 记录，临近过期才交换，随后使用现有四协议请求器中适用的 Chat Completions / Responses / Anthropic 路径。运行时为 bearer，公共预览仍为 oauth_device；固定桌面兼容请求头。不额外加 `/v1`，也不重试失败的模型请求。Gemini native 协议明确不适用。

### 凭据与并存

结构化记录存入既有 Android 加密凭据库，键为 `oauth:github-copilot:<Android presetId>`；GitHub token 和 Copilot token 不写入预设 JSON。Desktop/RikkaHub 的预设原本具有各自命名空间/Android ID，因此登录不会隐式跨来源复用；同一预设明确登录会替换该预设旧记录，不覆盖静态 API-key 记录。导入不执行登录，不搬运桌面 oauth_tokens.json。

`oauth:` 为结构化登录记录保留前缀，普通静态凭据/自定义请求头、TTS、搜索不能把整条 OAuth JSON 当 API key 发出去。只有登录适配器读取记录。记录自身 toString 隐去 token，实际 ProviderConfig 跟踪 Copilot 和 GitHub token 用于原有错误脱敏。

既有“包含凭据”的密码加密 native 备份会包含这些记录；普通备份不包含。恢复采用原凭据恢复机制；适配器读取时检查记录格式、token 和固定 API 根地址。尚未完成全部 OAuth 与预设/来源/包外引用关系的 native 恢复审计，不能据此称恢复功能全面验收。

认证操作通过既有数据门控，与 native 快照/恢复排他；服务内串行登录/交换/退出，避免退出被并发刷新重新写回。取消会等待当前认证操作结束，单次 HTTP 限时 30 秒；调用方协程取消可取消 HTTP，但取消与凭据提交边界不等于服务端撤销。关闭自动重试/重定向，错误不返回远端原始响应正文。

### 兼容来源和未完成项

标准 GitHub 设备流程的端点、间隔与 slow_down 行为按 [GitHub 官方文档](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow) 实现。Copilot 的 `/copilot_internal/v2/token`、兼容客户端 ID 和请求头来自本工作区桌面 `backend/app/llm/oauth/copilot.py`，并不是公开 GitHub Copilot API 合同，也不是 SimpleTavern 自有 OAuth 应用注册；官方设备流程文档不证明这个兼容客户端和内部接口可用于本应用。发布前必须核对注册/使用条件及真实上游兼容性，不能把编译成功当官方支持或登录成功。

尚未接：Enterprise Server 任意域名、订阅/账号查询、服务端撤销、GitHub 新型过期 access/refresh token 的轮换、Codex 正式第三方注册/全参数、完整恢复关系与真实设备/账号验证。当前 GitHub token 失效后需重新登录。语音预处理准备/预览只读缓存凭据，process 才按需刷新并发 LLM 请求；来源/下游 TTS 凭据仍独立。无新增单测、冒烟或额外文件哈希校验。

<a id="rikka-provider-override"></a>

## RikkaHub 模型替代连接

2026-10-10：固定官方2.5.5的 `Model.providerOverwrite` 是可空的完整 `ProviderSetting`，非父连接的局部补丁。新导入已转换 OpenAI、Google Gemini API、Claude，以及 Google Vertex 的服务账号和 Express API-key 连接；普通提供商与模型替代连接共用同一源映射，模型名称与自身控制仍按该模型读取，不用父提供商协议发送。未知类型仍保留原文并报告 deferred。官方固定依据：[Model.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/provider/Model.kt)、[ProviderSetting.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt)。主代理只读GET核对了源码，网页内容仅作格式依据。

### 导入和凭据

沿原模型预设 ID、namespace、ImportMerge/事务写入；覆盖对象先经过原脱敏，不导入 apiKey/privateKey，不另建提供商表或任务。canonical配置来自替代对象，不混入父对象连接字段；去掉执行模型上的 providerOverwrite 以供原四协议解析，完整源数据仍由原 source_blobs 保留。报告新增 model_provider_overwrite 的 converted/deferred；不支持的对象仍留在 selectedModel，原共享解析拒绝，不悄悄回到父连接。

覆盖连接的 credentialId 是该 Android 模型预设 ID，用户通过原 setProviderCredential(id, key) 明确配置；sourceProviderId 只保留原父来源引用，不作为此连接的密钥后备。无覆盖模型仍用原父凭据引用。历史消息用量按源模型当时声明的替代协议/模型预设归属映射，费用仍未知，不计成父连接的第二笔请求。

已导入旧数据不启动迁移；明确再次导入沿原三方合并更新，保留本地改动/删除和 Desktop/RikkaHub 并存。若本地保护留下旧未转换配置，解析仍明确失败，需用户处理该预设，不强制覆盖。

### Google Vertex

按固定来源 [GoogleProvider.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt) 选择：vertexAI=false 使用普通 Gemini，useServiceAccount 此时不启用 IAM；vertexAI=true 且 useServiceAccount=true 转为原 google-vertex/google_service_account 配置，googleVertex 带来源 projectId/location（缺省 us-central1）和 v1。复用现有项目/区域路由和服务账号 token 兑换；区域主机由 Android 原适配派生，不照搬来源的固定全局主机。私钥不进入普通配置或 source_blobs。用户用原 setGoogleVertexServiceAccount(实际选用的模型预设ID, serviceAccountJson) 单独配置；普通父提供商的账号不自动复制给模型。缺项目等配置仍需用原 configureGoogleVertexPreset 明确补齐。

vertexAI=true 且 useServiceAccount=false 使用 Express：固定 https://aiplatform.googleapis.com/v1/publishers/google，复用 Gemini 原生生成、SSE 与 query_key 鉴权；来源自定义 baseUrl 不作为 Vertex 传输根（来源自身也不使用它）。无项目/区域对象，不误选 IAM。替代连接用模型凭据，普通继承连接用父提供商凭据，均沿原 setProviderCredential。此全局无 projects/locations 路径及 key query 鉴权与 [官方 Express API](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/express-mode/api-reference) 一致。模型目录和原生显式缓存尚不支持 Express，会明确拒绝，不调用普通 Gemini 的目录/缓存。没有新增 ADC/WIF、GCS 或自动账号迁移。

普通提供商也使用相同 canonical 默认 URL、协议和 OpenAI 生成路径；provider_connection 的 deferred 表示元数据保留不等于连接可执行。未转换旧 Vertex 或非默认生成路径在原共享解析（包括预处理/目录）明确失败，不静默回退普通 Gemini/默认路径。

未知或缺失提供商 type 不再默认映射为 OpenAI：保留原配置与 deferred 报告，protocol 使用不支持的来源标记，由原协议选择统一拒绝；无需新的执行校验层。已知普通连接、模型覆盖、来源ID和三方合并不变。旧已导入配置仍不自动重写。

### OpenAI 生成路径

useResponseApi 选择原 Responses 或 Chat Completions。实际 responsesPath/chatCompletionsPath 转为 generationPath；缺字段沿固定来源默认值。ProviderConfig 只增加一个可空路径，ProviderEndpoints 只在对应生成操作拼到同一 baseUrl 的路径下。模型目录、文件/上传与其余操作仍使用原服务根，不因生成路径变成另一个错误根。

空路径明确表示使用配置根，不自动补 v1；其他路径须以斜线开头，既有URI解析保留同一origin，不用 URI.resolve 换主机。当前只接受无query/fragment的路径，含这些参数的来源自定义路径尚不支持；不静默去掉或重发。原无此字段行为不变。停止/取消、原账目、缓存和预处理继续复用原链，预处理也采用对应生成路径。

两个 Luna 分别编码小型源映射与路径适配，主代理接导入/解析并核对默认值、完整替代及凭据选择。最初普通离线 app:assembleDebug 成功1分37秒，路径deferred补正后再次成功24秒；随后两个 Luna 实际编码普通 provider 与 Google 分支，主代理接现有 Vertex/Express 路由和旧配置拒绝，最终普通离线构建成功42秒、退出0（230 tasks，12 executed/218 up-to-date，import/api Kotlin 实际重编）。仅编译/静态证据，详情见 [VERIFICATION.md](VERIFICATION.md)。未执行用户包、数据库导入、模型、账户或设备。无新依赖、表、任务引擎/校验框架、前端、哈希、冒烟或新增单测；不是所有来源变体或整体后端已验收。
