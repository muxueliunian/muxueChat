# muxueChat xAI OAuth 订阅链路实施计划

状态：XAI-1a/1c/2 已实现，XAI-2 待真机验收。阻塞关系见「进度记录」。
建立日期：2026-09-22
最后更新：2026-09-22（第 2 版，已并入外部审查意见）
范围：App 内实现 xAI OAuth 登录，用**订阅额度**访问 Grok 模型
不在范围：xAI 图片生成、视频生成、Grok 原生编码工具（`read_file` / `run_terminal_command` 等）

> 外部事实来自 2026-09-22 对开源项目 `pi-xai-oauth`（npm 包 1.6.0）的源码核查与 xAI 公开文档。
> 私有端点 `cli-chat-proxy.grok.com` 无官方文档，形状随时可能变更。

## 修订记录

**第 2 版（2026-09-22）** 并入外部审查意见，实质性修改如下：

| # | 第 1 版的问题 | 本版修改 |
| --- | --- | --- |
| 1 | 只抽四个方法解决不了认证与路由 | 新增第 4 节「认证与路由契约」，已核实 `freezeProviderRequest` 会拦截无 Key 的供应商 |
| 2 | 「刷新失败就清凭据」会误伤 | 改为按失败类型区分，见 5.3 |
| 3 | Keystore 存储描述不准确 | 改为「Keystore 持密钥 + 密文入私有存储」，对齐仓库现有 `EncryptedApiKeyStore` |
| 4 | OAuth token 可能被发往用户填写的任意地址 | 新增 4.3 地址钉死与重定向限制 |
| 5 | 翻译清单缺工具调用闭环与异常事件 | 重写 6.3 翻译表 |
| 6 | loopback「只接受一次请求」过脆 | 改为「只接受一次有效回调」，并把默认登录方式改为设备码 |
| 7 | XAI-0 验收承诺过满 | 改为测试矩阵，见 7.1 |
| 8 | 声称 AGENT.md「两条协议」条款无需修改 | 更正：线上协议确实增加到三种，条款必须改写 |
| 9 | 风险 7 建议用 `rpAuxiliaryModel` 省额度 | 更正：已核实它只换 model，不换凭据与地址 |
| 10 | 用 `/billing` 余额下降作为验收 | 更正：只能证明有消耗，不能证明消耗的是订阅额度 |
| 11 | XAI-3 取消导致 RP 无验收 | 更正：代码可复用，但完整验收保留 |
| 12 | 开头与分期的阻塞关系矛盾 | 统一为：AGENT.md 阻塞 XAI-1，XAI-0 阻塞 XAI-2 |

## 进度记录（接续工作先读这里）

| 分期 | 状态 | 阻塞于 |
| --- | --- | --- |
| 事实核查 | 完成 | — |
| 架构决策（D7 适配器） | 完成，已过一轮外部审查 | — |
| AGENT.md 修订 A 组（xAI OAuth 例外） | 完成（2026-09-22 用户逐条批准） | — |
| XAI-0 抓包第一轮（`api.x.ai` 路径） | 完成，见 `docs/XAI_WIRE_FORMAT.md` | — |
| XAI-0 抓包第二轮（订阅路径 `cli-chat-proxy`） | 完成。**D1 已确认走订阅额度** | — |
| AGENT.md 修订 B 组（传输层第二实现） | **完成**（2026-09-22）：上层仍两条协议分支，传输层允许 xAI 适配器 | — |
| XAI-1a 认证模块（仅设备码，自包含，不接入聊天） | **已通过真机验收**（2026-09-22，321 个 JVM 测试） | — |
| XAI-1a 遗留：登出调用 `/oauth2/revoke` | 完成，真机确认两次吊销都发出 | — |
| XAI-1b loopback 路径 | 未开始 | — |
| XAI-1c 凭据契约接入（第 4 节） | **完成**（commit 8d90101），真机验过登录/登出/刷新 | — |
| XAI-2 适配器 | **已实现**，348 个 JVM 测试通过，**待真机验收** | — |
| XAI-4 额度显示 | 未开始 | XAI-2 |
| XAI-4 附带：`/v1/models-v2` 拉模型与 500K 窗口 | 未开始。`refreshProviderModels` 的 CAS 校验依赖 `expectedApiKey`，OAuth 没有 Key，需要先改那套并发保护 | XAI-2 |

XAI-1 只依赖 OAuth 规范，不依赖 wire 格式，因此不被 XAI-0 阻塞。

XAI-1 拆分（2026-09-22 用户确认）：1a 只做设备码登录、凭据加密保存、刷新、登出与最小设置页入口，
不改 `ChatRepositoryImpl`、`ProviderRequestSnapshot`、`CustomProvider`、`freezeProviderRequest` 与 Room；
打开浏览器用 `Intent.ACTION_VIEW`，不引入 `androidx.browser`。D4「两种都做」不变，loopback 挪到 1b。
第 7 节 XAI-1 验收中「请先登录 xAI」一条归 1c。

## 1. 已确认的产品决策

| # | 决策 | 状态 |
| --- | --- | --- |
| D1 | 目的是使用 **xAI 订阅额度**，不是 API Key 计费 | 已确认 |
| D2 | 在 **App 内**实现，不采用 CLIProxyAPI 中转。用户已知悉 CPA 可零代码达成同一目的 | 已确认 |
| D3 | 复用 Grok CLI 的公开 client_id。用户已知悉这构成客户端身份冒用并接受风险 | 已确认 |
| D4 | 登录两种方式都做。**第 2 版调整：默认设备码，loopback 经真机验证后再考虑设为默认** | 已确认，实现顺序调整 |
| D5 | 全量支持 RP 能力 | 已确认 |
| D6 | 客户端标识无需冒充 Grok Build 版本，见 2.4 | 已查清 |
| D7 | xAI 实现为 OpenAI 兼容传输层背后的**适配器**，不作为第三条上层路由 | 已确认 |
| D8 | 适配器**只为 xAI 实现**，不预先抽象成通用多 Provider 框架 | **待用户确认**（审查意见认为合适） |

AGENT.md 相关禁令：用户 2026-09-22 批准按需修改，理由为「当初禁止 App 内 OAuth 只是为了
保持应用轻量，现在没有这个需求」。修改稿仍需逐条过目，见第 3 节。

## 2. 事实核查（2026-09-22）

### 2.1 OAuth（标准 OIDC）

```text
issuer         https://auth.x.ai
discovery      https://auth.x.ai/.well-known/openid-configuration
authorize      https://auth.x.ai/oauth2/authorize
device code    https://auth.x.ai/oauth2/device/code
token          https://auth.x.ai/oauth2/token
jwks           https://auth.x.ai/.well-known/jwks.json
id_token 算法  ES256
PKCE           S256
client_id      b1a00492-073a-47ea-816f-4c329264a828   （public client，无 secret）
scope          openid profile email offline_access grok-cli:access
               api:access conversations:read conversations:write
loopback       http://127.0.0.1:56121/callback
device grant   urn:ietf:params:oauth:grant-type:device_code
refresh        grant_type=refresh_token
revocation     https://auth.x.ai/oauth2/revoke
```

**2026-09-22 已用 `GET https://auth.x.ai/.well-known/openid-configuration` 实地核对**，
上述端点全部属实；`grant_types_supported` 确实包含 `authorization_code`、`refresh_token`
与设备码 grant，`code_challenge_methods_supported` 只有 `S256`。

新发现：**存在 `revocation_endpoint`（`/oauth2/revoke`，RFC 7009）**，
而 `end_session_endpoint` 不存在。因此登出应当调用吊销端点，
不能只清本机凭据——见 XAI-1a 的遗留缺口。

`grok-cli:access` 这个 scope，加上源码中复用 `~/.grok/auth.json` 的逻辑，共同说明该
client_id 属于 Grok CLI 官方客户端。`pi-xai-oauth` 用同一 client_id 实现了设备码 grant，
说明服务端允许该 client 使用此流程。

### 2.2 订阅链路端点

```text
订阅正文   POST https://cli-chat-proxy.grok.com/v1/responses
模型目录   GET  https://cli-chat-proxy.grok.com/v1/models-v2
用户信息   GET  https://cli-chat-proxy.grok.com/v1/user
额度查询   GET  https://cli-chat-proxy.grok.com/v1/billing?format=credits
对照：API Key 链路 https://api.x.ai/v1  ← OpenAI 兼容，但走计费，不是本计划目标
```

在 `pi-xai-oauth` 全仓库检索 `chat/completions`：**零命中**。订阅链路只用 Responses API。

请求头契约：

```text
Authorization: Bearer <access_token>
X-XAI-Token-Auth: xai-grok-cli
x-grok-client-identifier / x-grok-client-version / x-grok-client-mode
x-grok-conv-id / x-grok-req-id / x-grok-session-id
x-grok-model-override:  <小写模型 ID>
x-authenticateresponse: authenticate-response
Accept: text/event-stream（流式）| application/json
```

请求体在标准 Responses 之外还加 `store: false` 与 `include: ["reasoning.encrypted_content"]`。

### 2.3 Responses API 形状（xAI 公开文档）

请求体字段：`model`、`input`（**不是 `messages`**）、`instructions`、`temperature`、
`top_p`、`top_k`、`min_p`、`max_output_tokens`、`reasoning`、`tools`、`stream`、
`previous_response_id`、`store`、`search_parameters`。

**SSE 事件类型名未被官方文档写明。** `pi-xai-oauth` 把流式解析委托给上游 SDK，源码里也
拿不到。事件名必须由 XAI-0 抓包确定，**不得凭 OpenAI Responses 的经验写死**。

### 2.4 客户端标识无需冒充 Grok Build

`XAI_CLIENT_IDENTIFIER` / `XAI_PROXY_CLIENT_VERSION` 取自 pi 自己的 `package.json`
（`"pi-xai-oauth"` / `"1.6.0"`）。常量 `XAI_GROK_BUILD_REVIEWED_REVISION` 只出现在
报错文案（`wire.ts:319`），不参与请求。

结论：muxueChat 发自己的包名与 `versionName` 即可。真正被钉死的只有
`X-XAI-Token-Auth: xai-grok-cli` 与 client_id。**但需 XAI-0 实测**：pi 的错误文案表明
代理端会拒绝某些 client/version 组合，能通过不代表任意标识都能通过。

## 3. 架构决策（D7）与 AGENT.md 改动

### 3.1 被否决的方案：第三条上层协议链路

实测现有代码的分叉面：

```text
ChatRepositoryImpl.kt 中 ChatProviderId.CUSTOM_OPENAI 分支   8 处
引用 ChatProviderId.CUSTOM_OPENAI 的主代码文件              7 个
```

正文、RP 元数据补写、RP 状态兜底、上下文摘要、设定生成、错误映射均需新增第三条分支。
否决理由：每加一个 Provider 都要重来一遍，`ChatRepositoryImpl` 会持续膨胀。

### 3.2 采用的方案：传输层适配器

`OpenAiCompatibleClient` 的公开面只有四个方法：

```kotlin
fun streamChat(apiKey: String, baseUrl: String, requestDto: OpenAiCompatibleChatRequest)
        : Flow<OpenAiCompatibleStreamEvent>
suspend fun completeChat(apiKey: String, baseUrl: String, requestDto: OpenAiCompatibleChatRequest)
        : OpenAiCompatibleChatChunkDto
suspend fun listModels(apiKey: String, baseUrl: String): List<String>
fun cancelActiveRequest()
```

抽出接口 `OpenAiCompatibleTransport`，由 `OpenAiCompatibleClient` 与新的
`XaiResponsesTransport` 分别实现。上层按供应商配置选择实现，不新增 `when (provider)` 分支。

**但仅抽这四个方法不够**——这四个方法的签名假设凭据是一个 `apiKey: String`、地址是一个
可变 `baseUrl: String`。OAuth 供应商两者都不成立。认证与路由契约见第 4 节。

### 3.3 收益（已按审查意见收敛）

- `ChatRepositoryImpl` 新增上层路由分支 0 处。
- RP 正文、状态块、元数据补写、状态兜底、上下文压缩**代码可复用**，无需为 xAI 重写。
  但**完整验收不可省略**（见 7.3）：代码路径相同不等于模型行为相同。
- 适配器失效时，影响范围被限制在一个类里。

### 3.4 AGENT.md 需要修改的条款

**更正第 1 版的说法**：加入 Responses 适配器后，上层路由仍是两类，但**App 实际会说的
线上协议增加到三种**。因此不能声称「仅两种协议实现」的禁令无需修改，必须改写。

| 位置 | 现有规定 | 需要的改法 |
| --- | --- | --- |
| §2 | 「请求层永远只有两条分支……不为某个具体厂商写特判」 | 改写为：**上层路由**只有两条；允许在传输适配器内部实现第三种线上协议并适配具体厂商，但其输出必须收敛为 OpenAI 兼容语义，厂商私有字段不得上浮到 Repository 及以上 |
| §2 | 「App 不实现、发起、保存或复用任何 OAuth……不得包含第三方 CLI 的登录身份」 | 增加 xAI 例外；写明复用 Grok CLI client_id 的风险由用户承担 |
| §2 | 「上游是谁（Grok、OpenAI、任意中转）与 App 无关」 | 更正为：仅对 OpenAI 兼容供应商成立；xAI 适配器是明示的例外 |
| §3.3 | 「不允许 App 内 OAuth」 | 同上开例外 |
| §5.3 | 「App 不保存任何 OAuth 凭据、浏览器会话或第三方 CLI 登录状态」 | 改为：OAuth 凭据按 5.2 的方式加密保存；仍禁止读取任何第三方 CLI 的本地凭据文件 |
| §9 | 「设置页不提供任何 OAuth 登录、Token 导入或第三方 CLI 身份复用入口」 | 改为允许 xAI 登录入口，并要求显示风险提示 |
| §11 | OAuth 相关三条禁令 | 相应收窄 |

新增建议条款：

- 传输适配器的输出必须收敛为 OpenAI 兼容语义；厂商私有字段不得出现在 Repository 及以上。
- OAuth 凭据绝不进 DataStore 明文、Room、日志、错误提示或测试快照。
- 不保存浏览器 Cookie，不读取任何第三方 CLI 的本地凭据文件。
- OAuth 供应商的服务地址由 App 钉死，不接受用户编辑，不复用明文 HTTP 放行策略。

## 4. 认证与路由契约（第 1 版缺失，本节为审查重点产出）

### 4.1 现状与冲突

`ChatRepositoryImpl.freezeProviderRequest`（约 592～620 行）对两类 Provider 都硬性要求
非空 API Key：

```kotlin
apiKey = apiKey?.takeIf { it.isNotBlank() }
    ?: error("请先在设置中保存「${activeProvider.displayName}」的 API Key")
```

OAuth 供应商会在**进入适配器之前**被拦住。`ProviderRequestSnapshot` 目前也只有
`apiKey: String` 与 `baseUrl: String` 两个凭据/地址字段。

**明确禁止用假 API Key 绕过。** 这会让「是否已登录」的真相分散到两处，且假值可能被写进
日志或错误提示。

### 4.2 需要的改动

1. `CustomProvider` 增加认证类型字段（例如 `authKind: API_KEY | XAI_OAUTH`），旧 JSON
   缺字段时按 `API_KEY` 处理。
2. `ProviderRequestSnapshot` 的凭据字段从 `apiKey: String` 改为一个可表达两种形态的类型
   （例如 `ProviderCredential.ApiKey(value)` / `ProviderCredential.OAuthBearer(token)`）。
3. `freezeProviderRequest` 按 `authKind` 分派校验：API Key 型沿用现有报错；OAuth 型改为
   检查「是否已登录且凭据可用」，未登录时报「请先登录 xAI」。
4. **冻结时刷新**：OAuth 型在冻结快照时完成一次按需刷新，使快照持有一个当时有效的 token。
   这与现有「先写用户消息、再冻结请求配置」的语义一致，不改变 FIX-02 的行为。
5. **取消归属**：`cancelActiveRequest()` 当前由具体 Client 持有活动请求。改为接口方法后，
   必须保证「请求期间用户切换供应商」时取消的是**发起该请求的那个实现**，而不是切换后的
   新实现。需要在 XAI-2 中明确活动请求的归属并补测试。

### 4.3 地址钉死与重定向限制

自定义供应商允许用户填写任意 `http(s)://` 地址（AGENT.md 明示放行明文 HTTP）。
**OAuth 凭据绝不能继承这一策略**，否则 token 可能被发往用户误填的主机。

- xAI 供应商的 `baseUrl` 由 App 钉死为 `https://cli-chat-proxy.grok.com/v1`，
  设置页不提供编辑入口。
- 适配器内部**禁止跟随跨主机重定向**；仅允许同主机、同 scheme 的重定向，或干脆全部禁止。
- OAuth 端点同样钉死在 `https://auth.x.ai`，`verification_uri` 的 origin 需校验（见 5.1）。
- 任何情况下都不得把 `Authorization` 头透传给非钉死主机。

## 5. 认证详细设计

### 5.1 两条登录路径

**为什么不能用自定义 scheme**：redirect_uri 由 client_id 在服务端注册，我们无法新增。
可用的只有已注册的 `http://127.0.0.1:56121/callback` 与设备码这个无 redirect 的 grant。

**路径 A：设备码流程（默认）**

1. `POST /oauth2/device/code`（form 编码，`client_id` + `scope`）。
2. 显示 `user_code`，按钮用 Custom Tab 打开 `verification_uri`。
   校验其 origin 必须是 `https://auth.x.ai` 或 `https://accounts.x.ai`，
   且 URI 中不得包含 `device_code`。
3. 按 `interval` 轮询 `POST /oauth2/token`，处理 `authorization_pending`、`slow_down`、
   `expired_token`、`access_denied`；总时长上限 15 分钟。
4. 进程被杀后轮询中断，重启时显示「上次登录未完成」并允许重新发起，不留半截状态。

**路径 B：loopback 重定向（真机验证后再考虑设为默认）**

1. 生成 PKCE `code_verifier` / `code_challenge`(S256) 与随机 `state`。
2. 在 `127.0.0.1:56121` 起 `ServerSocket`，**只接受一次有效回调**：
   路径不匹配、缺 `code`、`state` 不符的请求一律返回错误页并**继续监听**，
   不终止整个授权流程。
3. Custom Tab 打开 `/oauth2/authorize`。
4. 收到有效回调后校验 `state`，取 `code`，关闭 socket。
5. `POST /oauth2/token`，`grant_type=authorization_code` + `code_verifier`。

已知弱点（审查指出，需真机验证后再决定是否设为默认）：

- 授权期间 App 可能被系统回收，内存中的超时计时器随之消失，用户回到 App 时看不到失败提示。
  需要在恢复时检测「存在未完成的 loopback 会话」并显式标记为中断。
- 端口被占用时静默回退设备码。
- socket 只绑回环地址。

### 5.2 凭据存储（更正第 1 版的错误描述）

Android Keystore **保存的是密钥，不是任意数据**。沿用仓库现有 `EncryptedApiKeyStore`
的成熟做法：

- Keystore 中以独立 alias 持有一个 AES 密钥（与 DeepSeek、各自定义供应商的 alias 互不复用）。
- 用该密钥做 AES/GCM 加密，**密文与 IV 写入应用私有存储**（沿用现有偏好文件方案）。
- 明文 token 只在内存中存在。

还需明确：

- **备份排除**：凭据文件必须在 `data_extraction_rules.xml` 中排除，不参与云备份与设备迁移。
- **密钥失效**：Keystore 密钥可能因锁屏凭据变更等原因失效，解密抛异常时必须当作「未登录」
  处理并清理残留密文，不能崩溃。
- **登出**：同时删除密文与 Keystore alias。

### 5.3 刷新策略（更正第 1 版的「失败即清凭据」）

按失败类型区分，**不得一律清除凭据**：

| 失败类型 | 处理 |
| --- | --- |
| 网络错误、超时、DNS | 保留凭据，向上报可重试错误 |
| 5xx、429 | 保留凭据，按退避重试；不退出登录 |
| `invalid_grant`、refresh token 被吊销 | 确认授权失效，清除凭据并要求重新登录 |
| 其他 4xx | 保留凭据并记录错误类型，不自动退出登录 |

其他要求：

- 提前 2 分钟刷新；全局串行（一把 Mutex），并发请求只触发一次刷新。
- **refresh token 轮换需原子保存**：服务端返回新 refresh token 时，新旧必须原子切换，
  中途崩溃不得留下「新 access token + 旧 refresh token」的不一致状态。
- **登出与刷新的竞态**：登出后若一个在途刷新恰好成功，不得把凭据写回去导致「登录态复活」。
  需要一个登出世代号（generation）或在同一把 Mutex 内检查登出标记。
- 401 后允许刷新并重试一次；重试仍 401 则按授权失效处理。

`id_token` 只用于显示账号（取 `sub` / `email`），**不做任何安全决策**，因此不验签、
不引入 JWT/JWKS 依赖，符合 §8。

## 6. 适配器设计

### 6.1 边界约束

Responses API 的 `previous_response_id`、`store`、`include: reasoning.encrypted_content`、
Grok 原生编码工具在 chat/completions 中没有对应物。这些一律由适配器内部处理，
**绝不向上暴露**。

**但不得在 XAI-0 之前就断言「一律丢弃」**：若抓包发现 `reasoning.encrypted_content`
必须在多轮之间回传才能正常工作，则 D7 需要重新评估（因为 chat/completions 的消息模型
装不下它），而不是硬丢。见风险 5。

### 6.2 不预先泛化（D8）

AGENT.md §8 禁止通用多 Provider SDK，§5.1 要求「不为一两个调用点过早抽象」。
本次只为 xAI 实现，接口照现有四个方法（加第 4 节的凭据类型改造）抽；
等第二家 OAuth 厂商真正出现时再泛化。

### 6.3 翻译表

**请求方向**（`OpenAiCompatibleChatRequest` → Responses）

| OpenAI 兼容 | Responses | 备注 |
| --- | --- | --- |
| `messages[role=system]` | `instructions` | 多条 system 的合并方式与顺序待 XAI-0 |
| `messages[role=user/assistant]` | `input[]` | item 形状待 XAI-0 |
| `messages[role=assistant].tool_calls` | `input[]` 中的工具调用项 | **历史工具调用必须能回放**，形状待 XAI-0 |
| `messages[role=tool]` | `input[]` 中的工具结果项 | 需保持 call ID 对应关系 |
| `tools` | `tools` | 形状不同：Responses 为扁平 `{type,name,parameters}` |
| `tool_choice` | `tool_choice` | 取值映射待 XAI-0 |
| `max_tokens` | `max_output_tokens` | |
| `reasoning_effort` | `reasoning.effort` | 档位取值待 XAI-0 |
| `temperature` / `top_p` | 同名 | |
| `stream` | `stream` | |
| — | `store: false` | 适配器固定注入 |
| — | `include: ["reasoning.encrypted_content"]` | 适配器固定注入，去重 |

**响应方向**（Responses → `OpenAiCompatibleStreamEvent` / `OpenAiCompatibleChatChunkDto`）

| Responses | OpenAI 兼容 | 备注 |
| --- | --- | --- |
| 文本增量事件 | `choices[0].delta.content` | 事件名待 XAI-0 |
| 推理增量事件 | 现有 reasoning 字段 | 与 `reasoning_content` / `reasoning` 别名一致处理 |
| 工具调用项创建 | `delta.tool_calls[].id` / `.function.name` | |
| 工具调用参数分片 | `delta.tool_calls[].function.arguments` | **参数是分片到达的**，需累积 |
| 正常结束事件 | `finish_reason = stop` | |
| 截断结束（达到 `max_output_tokens`） | `finish_reason = length` | 不得当作正常完成 |
| 失败 / 错误事件 | 抛出可读错误 | 不得当作正常完成 |
| **缺少终止事件的断流** | 抛出可读错误 | 不得静默当作完成 |
| `usage.input_tokens` / `output_tokens` | `prompt_tokens` / `completion_tokens` | 字段名不同，需归一 |
| `previous_response_id` / 加密推理 | 见 6.1 | 待 XAI-0 决定 |

`listModels` 映射到 `GET /v1/models-v2`，响应结构待 XAI-0。

### 6.4 日志与安全

- access token、refresh token、`device_code`、`user_code`、`code_verifier`、`state`
  **绝不进日志**。
- 日志只允许：请求开始/结束、HTTP 状态码、错误类型、消息长度、conversationId。
- 沿用 `muxueChat.*` tag 前缀。

## 7. 分期任务

状态标记沿用 `docs/DEFECT_FIX_TASKS.md`：`[ ]` 未实施、`[~]` 已实现待验收、`[x]` 完成。

### [ ] XAI-0：抓包验证（阻塞 XAI-2）

不写 App 代码。用 CLIProxyAPI 或 `pi` 本体完成真实登录与对话，产出
`docs/XAI_WIRE_FORMAT.md`。

> 用 CPA 抓包不违背 D2：CPA 在此是调研工具，不是运行时依赖。
> **抓包资料必须脱敏**：token、账号信息、对话正文一律不得留在文档或仓库中。

**测试矩阵**（一次对话抓不全，必须覆盖以下场景）：

| 场景 | 要采集的东西 |
| --- | --- |
| 单轮纯文本，流式 | 每一种 SSE 事件名与 payload、`usage` 字段名 |
| 单轮纯文本，非流式 | `output[]` 结构 |
| 多轮对话 | `input[]` 中历史消息的形状；是否需要 `previous_response_id` |
| 带思考 | 思考内容在哪个字段；`reasoning.effort` 取值 |
| 工具调用完整闭环 | 工具定义形状、调用项、**参数分片**、call ID、工具结果回传形状 |
| 达到 `max_output_tokens` | 截断时的结束事件 |
| 客户端主动取消 | 断流时的表现 |
| 触发限额 / 429 | 错误响应形状 |
| 异常断流 | 无终止事件时客户端如何判定 |
| 自定义 client 标识 | `x-grok-client-identifier` / `-version` 填自定义值是否被接受 |
| 加密推理 | `reasoning.encrypted_content` 是否必须多轮回传 |

验收：`XAI_WIRE_FORMAT.md` 覆盖上表每一行，文中无「推测」字样；
若加密推理被确认为必须回传，**先回到第 3 节重新评估 D7**，再决定 XAI-2 是否照原样进行。

### [ ] XAI-1：认证与凭据管理（阻塞于 AGENT.md 修订）

设备码路径（默认）、loopback 路径、PKCE 与 `state` 校验、轮询、按类型区分的刷新策略、
refresh token 原子轮换、登出竞态处理、凭据加密存储与备份排除、密钥失效处理、
第 4 节的认证类型与凭据契约改造、设置页登录/登出入口与风险提示。

验收：

- 两条路径都能完成登录；端口占用时静默回退；进程被杀后不留半截登录状态。
- 网络错误/5xx/429 不导致退出登录；`invalid_grant` 才清凭据。
- refresh token 轮换中途崩溃不产生不一致状态。
- 登出后在途刷新不会让登录态复活。
- Keystore 密钥失效时按未登录处理，不崩溃。
- 日志与错误提示中找不到任何 token、`device_code`、`user_code`、`state`。
- 未登录时发消息的报错是「请先登录 xAI」，而不是「请保存 API Key」。

### [ ] XAI-2：传输适配器（阻塞于 XAI-0 + XAI-1）

抽出 `OpenAiCompatibleTransport` 接口（纯机械重构，行为不得改变）；新增
`XaiResponsesTransport` 与双向翻译；`listModels` 接 `models-v2`；错误映射；
活动请求归属与取消；地址钉死与重定向限制。

验收：

- 现有 OpenAI 兼容供应商行为**逐字节不变**，需有回归测试佐证。
- xAI 下正文聊天流式与非流式均可用；思考内容正确展示。
- 工具调用完整闭环可用（定义 → 调用 → 参数分片累积 → 结果回传 → 再次生成）。
- 截断、失败事件、无终止事件的断流都产生可读错误，不被当作正常完成。
- 请求期间切换供应商时，取消作用于发起该请求的实现。
- 401 能刷新并重试一次。
- Responses 私有字段不出现在 Repository 及以上任何一层。

### [ ] XAI-3：（作为独立开发期取消，验收保留）

采用 D7 后 RP 能力的**代码路径**可直接复用，无需新增实现。但**完整验收保留**，
并入 XAI-2 的真机验收（见 7.3）：代码路径相同不等于 Grok 的输出行为与 DeepSeek 一致。

另需一件实现工作：RP Marker 是 DeepSeek 专属，xAI 下不可用，需在
`presentation/chat/RpMarkerAvailability.kt` 的文案中如实说明。

### [ ] XAI-4：额度与用量显示

`/v1/billing?format=credits` 与 `/v1/user` 接入，设置页显示剩余额度与账号。

## 8. 测试计划

### 8.1 JVM 单元测试

- 设备码状态机：`authorization_pending` / `slow_down` / `expired_token` / `access_denied`
  与总时长上限，用假 HTTP 客户端驱动。
- loopback：`state` 不匹配必须拒绝**且继续监听**；路径不匹配不终止流程；端口占用回退。
- `verification_uri` 的 origin 校验与「不得包含 device_code」检查。
- 刷新策略矩阵：网络错误/5xx/429/`invalid_grant`/其他 4xx 各自的处理。
- refresh token 轮换的原子性；并发请求只触发一次刷新；登出竞态。
- 凭据契约：OAuth 供应商未登录时 `freezeProviderRequest` 的报错文案正确，
  且**不存在任何假 API Key 路径**。
- 翻译层双向：请求逐字段；响应按 XAI-0 记录的每一种事件构造 fixture；
  工具调用参数分片累积；截断/失败/断流三种异常；`usage` 字段归一。
- 接口抽取回归：`OpenAiCompatibleClient` 经接口调用产生的请求与改动前逐字节一致。
- 地址钉死：适配器拒绝非钉死主机；`Authorization` 不透传给跨主机重定向。
- 凭据绝不出现在任何序列化输出或日志断言中。

### 8.2 真机验收（认证）

两条登录路径各走一次；授权期间切后台并被回收后的表现；杀进程重启后仍为登录态；
token 过期后自动恢复；断网、登录中途取消、user code 过期。

### 8.3 真机验收（RP，不可省略）

即使代码路径复用，仍需在 xAI 下完整走一遍：

- `<muxue_state>` 状态块的输出遵从度（Grok 未必像 DeepSeek 那样稳定遵守格式）。
- 内心与剧情选项的元数据补写质量。
- 状态兜底与上下文压缩在长会话下的表现。
- 历史改写与回滚。
- RP Marker 不可用的提示文案正确。

### 8.4 额度验收（更正第 1 版）

`/billing` 余额下降**只能证明产生了消耗，不能单独证明消耗的是订阅内额度**
（也可能是额外购买的额度）。需要结合 xAI 账户页面的订阅用量视图交叉确认，
或在文档中如实标注该项无法由 App 单独验证。

自动化测试不得包含真实 token 或真实账号。

## 9. 风险与未决事项

1. **账号风险（最高）**：冒用 Grok CLI 的 client_id 与请求头可能违反 xAI 服务条款，
   最坏情况是账号被限制或封禁。用户已知悉并接受，但必须写进设置页说明。
2. **私有端点无文档**：`cli-chat-proxy.grok.com` 随时可能改协议或吊销 client_id。
   适配器方案的好处是失效范围被限制在一个类里。
3. **客户端标识被拒**：需 XAI-0 实测；若存在白名单，再把该值做成设置页可手填字段。
4. **SSE 事件名未知**：XAI-0 完成前，6.3 的响应方向翻译表全是占位。
5. **翻译可能有损**：若 XAI-0 确认 `reasoning.encrypted_content` 必须多轮回传，
   chat/completions 的消息模型装不下它，**D7 需要重新评估**，不得在上层开口子绕过。
6. **接口抽取的回归风险**：`OpenAiCompatibleClient` 是已验证的现网路径，
   抽接口必须是纯机械重构，需回归测试佐证。
7. **内部请求消耗订阅配额**：RP 元数据补写、状态兜底、上下文摘要同样走订阅，会消耗配额。
   **更正第 1 版**：现有 `rpAuxiliaryModel`（`ChatRepositoryImpl.kt:2294`）只在同一
   `requestSnapshot` 内替换 model，凭据与地址不变，**无法切到另一供应商**来规避。
   若要支持「RP 辅助请求走别的供应商」，是一项独立的设计改动，不在本计划范围内。
8. **RP 行为差异**：代码可复用，但 Grok 对 `<muxue_state>` 的遵从度需真机验收。
9. **loopback 在 Android 上的可靠性**：进程被回收会导致回跳无人接收，
   且内存中的超时计时器消失。这是把设备码设为默认的原因。
10. **与 MM-1a 的排期冲突**：本计划不改 Room schema，但仍应与多模态串行，
    避免同时改动 `ChatRepositoryImpl`。

## 10. 工作量估算

第 2 版估算（含测试）。第 1 版低估了认证与凭据契约的成本：

| 分期 | 估计 | 说明 |
| --- | --- | --- |
| XAI-0 | 不写 App 代码，产出 1 份 wire 文档 | 测试矩阵有 11 行，需多次会话 |
| XAI-1 | 约 14～16 个文件，1200～1600 行 | 含第 4 节的凭据契约改造，高于第 1 版估算 |
| XAI-2 | 约 10～12 个文件，1000～1500 行 | 含工具调用闭环与异常事件处理 |
| XAI-3 | 实现约 1～2 个文件；**验收不可省略** | 见 8.3 |
| XAI-4 | 约 4 个文件，250～400 行 | |

## 11. 仍待确认

1. **D8**：只为 xAI 做适配器，不预先泛化。外部审查认为合适，仍需用户拍板。
2. **AGENT.md 修改稿**：第 3.4 节是改法提纲，具体条文需起草后由用户逐条过目。
3. **loopback 是否最终设为默认**：待 XAI-1 真机验证后决定。
4. **`reasoning.encrypted_content` 的处理**：待 XAI-0，可能触发 D7 重新评估。
