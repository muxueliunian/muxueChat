# muxueChat 项目 Agent 规范

本文件是本项目的根规则文档。任何 Agent 在进行代码编写、文件修改、重构、依赖调整、架构设计前，必须先读取本文件并遵守。

## 1. 基本约束

- 所有交流、总结、注释性文档优先使用中文。
- 用户系统环境是 Windows 11，常用开发环境是 Android Studio。
- 本项目是个人使用 App，不做账号系统、云同步、埋点、推送通知、多用户权限或后台服务，且 App 自身不部署、运维或代理后台服务。
- App 可以作为客户端调用用户自行配置的 OpenAI 兼容 `chat/completions` 端点（自定义供应商，可保存多个，允许 http 或 https）；这些服务本身的部署、账号、OAuth 登录与运维均不在本项目范围内（唯一例外见 §2「xAI 订阅登录例外」）。
- 本项目只考虑 Android 16 及以上：
  - `minSdk = 36`
  - `targetSdk = 36`
  - `compileSdk = 36`
- 不允许为了本机 SDK、插件或依赖问题擅自降低 Android 版本要求。
- 如果构建环境缺少 Android 16 SDK，应明确说明并让用户安装，而不是降级。
- 默认技术栈是 Kotlin + Jetpack Compose + MVVM + Room + DataStore + OkHttp + kotlinx.serialization。

## 2. 当前产品定位

muxueChat 是一个 Android 16+ 的个人聊天客户端。DeepSeek MVP 已验证；经用户确认，第二类 Provider 由原先写死的「局域网 CLIProxyAPI」一般化为**用户自定义的 OpenAI 兼容供应商**：用户可自行填写供应商名称、Base URL（http 或 https）与 API Key，保存多个并随时切换。各服务的真实可用性由用户验收。

### Provider 边界

App 只承认两类协议，且这两类是**协议边界**而不是服务清单：

1. **DeepSeek**：App 直接调用 DeepSeek API，保留其 thinking/reasoning 专属行为，并支持标准 function 工具调用。
2. **自定义 OpenAI 兼容供应商**：App 按标准 OpenAI 协议访问用户填写的 `http(s)://` 端点，即 `POST {base}/chat/completions` 与 `GET {base}/models`。支持普通/流式聊天，以及在用户为该供应商显式开启后使用标准 `tools` / `tool_calls`；用户自建的局域网 CLIProxyAPI 是这一类里的普通一员，不再享有特殊代码路径。

规则：

- **上层永远只有「DeepSeek 专属协议」与「OpenAI 兼容协议」两条分支**，不按供应商名称分叉出第三条链路，也不为某个具体厂商写特判。仓库、Prompt 拼装、RP、上下文规划、错误映射都只认这两条。错误映射尤其不得识别厂商专属异常类型：需要原样展示的文案通过通用的「安全文案」标记接口传递。
- 传输层允许存在多个**实现**，但必须藏在 OpenAI 兼容协议的同一个接口之后，对上层不可见。**唯一例外是 xAI 订阅链路**（用户 2026-09-22 批准）：它在钉死地址上说 Responses 协议，因此以适配器形态实现该接口，负责把 OpenAI 兼容请求翻译过去、再把响应翻译回来。判定依据只能是供应商的认证类型（`ProviderAuthKind.XAI_OAUTH`），不得按名称或地址字符串匹配。
- 该例外不得扩大：除 xAI 订阅链路外，不允许为任何厂商新增传输实现；用户填写地址的自定义供应商一律只走标准 OpenAI 兼容传输。
- 上游是谁（OpenAI、任意中转）与 App 无关：除上述 xAI 例外外，App 的边界只到 OpenAI 兼容协议本身，不直接适配任何厂商的私有协议、私有字段或 SDK。
- 自定义供应商下不发送 DeepSeek 的 `thinking` 对象或 RP Marker；只有全局“设定生成工具”开关和当前供应商的“启用标准工具调用”开关都开启时，才在非 RP 正文请求中发送标准 OpenAI `tools` / `tool_choice` 并处理 `tool_calls`。不得按具体厂商写特判。
- 自定义供应商的思考等级走 OpenAI 系的顶层 `reasoning_effort` 字段，取值由用户自行填写，App 不做枚举校验（各家档位不统一）。留空时**必须完全不发送该字段**——不支持思考的模型收到未知字段可能直接 400。
- `reasoning_effort` 只随正文对话发送；RP 元数据、状态兜底、上下文摘要一律不发。
- 每个自定义供应商的 API Key 与 DeepSeek API Key 分别保存，各占独立的 Android Keystore alias；删除供应商必须同时删除其 Key。
- App 默认不实现、发起、保存或复用任何 OAuth：不得包含 OAuth client ID、授权码、access token、refresh token、Cookie 或第三方 CLI 的登录身份，用户只能填写服务已签发的 API Key。**唯一例外是 xAI 订阅登录**（用户 2026-09-22 批准），边界如下：
  - 仅限 xAI：OAuth 端点钉死 `https://auth.x.ai`，服务地址钉死 `https://cli-chat-proxy.grok.com/v1`，不接受用户编辑，不适用明文 HTTP 放行策略；任何情况下不得把 `Authorization` 头发往非钉死主机，禁止跟随跨主机重定向。
  - 复用 Grok CLI 公开 client_id 构成客户端身份冒用，可能违反 xAI 服务条款乃至导致账号被封，风险由用户承担；设置页必须如实提示。
  - 仍然禁止：读取任何第三方 CLI 的本地凭据文件（如 `~/.grok/auth.json`）、保存或读取浏览器 Cookie、Token 手工导入。
  - xAI 认证模块本身属于该例外，不视为请求层的厂商特判。
  - 不得借此为其他厂商或其他 OAuth 场景开口；新增任何 OAuth 厂商须由用户另行批准并修改本条。
  - 该例外不改变「App 不做后台服务」的约束：token 刷新只在前台按需进行，不得为刷新引入后台任务或定时器。
- 自定义供应商同时接受 `https://` 与 `http://` 端点。明文 HTTP 是**用户明确要求并接受风险**的能力，用于接入没有证书的局域网自建服务；应用层不再限制地址范围，但 UI 必须在选中 http 端点时给出明文提示。
- 「一般化」的边界到此为止：不引入通用多 Provider SDK，不为 Anthropic、Gemini 等非 OpenAI 协议新增适配层。用户需要这些服务时，应填写它们的 OpenAI 兼容端点。
- 设置页必须明确告知用户「只支持 DeepSeek 协议与 OpenAI 兼容格式」，不要让用户以为填任意厂商地址都能用。

当前 MVP 已验证：

- DeepSeek API Key 本地保存。
- DeepSeek `chat/completions` 请求可正常工作。
- 流式输出可正常显示。
- 支持 `deepseek-flash`（默认，V4.1-Flash）与 `deepseek-v4-pro`。旧名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp`
  由 `DeepSeekModelIdMigration`（DataStore 迁移，只执行一次）改写为 `deepseek-flash`；自定义供应商的模型 ID 不参与迁移。
  图片输入尚未实现，计划见 `docs/MULTIMODAL_PLAN.md`。
- 支持基础生成参数：
  - Base URL
  - model
  - system prompt
  - stream enabled
  - max tokens
  - thinking mode
  - reasoning effort
  - temperature
  - top_p
- 设置页中除 API Key 外，其余设置为实时自动保存，并在下一次发送消息时生效。
- API Key 必须手动保存，且不能明文展示已保存值。

OpenAI 兼容链路已完成以下代码能力，但不等同于 App 代为部署或验收用户的服务：

- 自定义供应商的增删改与切换：名称、Base URL、API Key、模型列表。
- 每个供应商一个独立 Keystore alias 保存 API Key，不明文展示、不跨供应商复用。
- OpenAI 兼容的普通/流式聊天、停止生成、重新生成及协议级错误提示。
- RP 正文、状态块、本地元数据补写与上下文压缩的同 Provider 路由。
- 模型列表通过 `GET {base}/models` 拉取并缓存，失败时回退用户手填。
- 自定义供应商下不发送 DeepSeek thinking/reasoning 或 RP Marker；支持按供应商显式启用的标准 OpenAI 工具调用（仅非 RP 正文请求）。旧供应商默认关闭该能力，避免向只支持普通聊天的反代发送未知字段。

其历史拆分与后续验收事项维护在 `docs/ROADMAP.md`，本轮一般化改造的设计维护在 `docs/CUSTOM_PROVIDER_PLAN.md`。

未来计划、功能顺序、RP 模式讨论提纲不放在本规范中，统一维护在 `docs/ROADMAP.md`。

## 3. 架构分层

源码根包：

```text
app/src/main/java/com/muxuechat
```

推荐分层：

```text
com.muxuechat
  MainActivity.kt

  data
    local
    remote
    repository

  domain
    model
    usecase

  presentation
    chat
    settings
    theme
    common
```

### 3.1 domain 层

职责：

- 定义业务模型与用例。
- 不依赖 Android UI。
- 不直接依赖任何 Provider 的 DTO。

已有模型包括：

- `Conversation`
- `ChatMessage`
- `ChatRole`
- `MessageStatus`
- `ChatSettings`
- `DeepSeekModels`（DeepSeek 模型 ID 是开放字符串；该对象只持有默认值、内置兜底列表与已知模型元数据，不要再做成封闭枚举）
- `ThinkingMode`
- `ReasoningEffort`

新增功能时优先先思考 domain 模型是否需要扩展，不要直接把业务状态塞进 UI 组件。

### 3.2 data.local 层

职责：

- Room 保存会话和消息。
- DataStore 保存普通设置。
- Android Keystore 保存各 Provider 的凭据。

规则：

- DeepSeek API Key、每个自定义供应商的 API Key 与 xAI OAuth 凭据必须分别用 Android Keystore 保护；不得明文写入 Room、DataStore、日志、错误提示或测试快照。
- 供应商的名称、Base URL、模型列表属于普通设置，可以进 DataStore；Key 绝不可以。
- App 不保存浏览器会话、Cookie 或第三方 CLI 登录状态。
- xAI OAuth 凭据（access / refresh / id token）与 API Key 同法保存：独立 Keystore alias 持有 AES 密钥，AES/GCM 密文与 IV 写入应用私有 SharedPreferences，明文只在内存中存在。解密失败按未登录处理并清理残留密文，不得崩溃；登出同时删除密文与 alias。
- 会话和消息数据优先通过 DAO 暴露，避免 UI 层直接操作数据库。
- 删除会话应依赖 Room 外键级联删除消息，避免手工多处删除导致不一致。

### 3.3 data.remote 层

职责：

- Provider 专属的请求 DTO、API Client 与 SSE 流式解析。
- DeepSeek 请求链路，以及所有自定义供应商共用的 OpenAI 兼容请求链路。

规则：

- 上层协议严格限于两条：DeepSeek 专属协议与 OpenAI 兼容协议。自定义供应商共用后者的同一套 DTO 与 RequestFactory，不允许按供应商名称新增代码分支；两条协议均可承载标准 function tools，但 DeepSeek 私有 thinking/RP Marker 绝不进入 OpenAI 兼容请求。
- OpenAI 兼容协议的**传输**由一个接口承载，默认实现是 `OpenAiCompatibleClient`（标准 `POST {base}/chat/completions`）。按 §2 的 xAI 例外，允许存在第二个实现：xAI 订阅链路的 Responses 适配器，入口同样是这个接口，翻译全部封在实现内部。**调用方不得知道自己拿到的是哪个实现**，也不得因此改变请求 DTO 的构造方式。
- 适配器只做协议翻译，不得引入新的上层概念：它接收同一个 OpenAI 兼容请求 DTO，产出同一套流式事件与用量快照（`input_tokens`/`output_tokens` 必须归一化为 `prompt_tokens`/`completion_tokens`）。
- 线上格式**只能依据 `docs/XAI_WIRE_FORMAT.md` 的实测记录实现**。该文档 §5.4 列出的未采集场景（截断、错误事件、流中取消、429、无终止事件断流）**不得凭 OpenAI Responses 的经验猜测**：未采集的事件一律走保守兜底（未知事件忽略、缺终止事件按失败处理并给可读文案），不得臆造字段语义。
- `OpenAiCompatibleClient` 请求用户配置的 `http(s)://` 端点；除 §2 的 xAI 例外外，不允许 App 内 OAuth。地址仍不得包含 user-info、query 或 fragment。
- 对自定义 OpenAI 兼容供应商，上游服务的 OAuth、令牌刷新、部署和运维均由该服务自身负责，App 不参与。
- `GET {base}/models` 只用于填充模型选择列表；服务不支持时必须优雅降级到手填，不能阻断聊天。
- 思考内容的响应字段名不统一：`reasoning_content`（DeepSeek/Qwen/GLM 等）与 `reasoning`（OpenRouter）都要解析，取先出现的非空值。这是字段别名，不是厂商特判。
- DeepSeek Base URL 默认：

```text
https://api.deepseek.com
```

- endpoint：

```text
POST /chat/completions
```

- 当前默认模型不得使用已计划废弃的 `deepseek-chat` 或 `deepseek-reasoner`。
- `stream=true` 必须实际出现在请求体中，不能被 `encodeDefaults=false` 省略。
- （仅自定义 OpenAI 兼容供应商）`stream_options: {"include_usage": true}` 只在用户为该供应商显式开启「请求流式用量统计」后才发送，
  **默认完全不发**。它是可选字段，只支持普通聊天的旧反代收到未知字段可能直接 400。
  DeepSeek 请求不发送该字段：官方文档说明省略时 `usage` 仍会出现在流的最后一个 chunk。
  与 `type`、`stream` 同理，`include_usage` 必须用 `@EncodeDefault(ALWAYS)` 强制序列化，
  否则 `encodeDefaults=false` 会把它省成一个空的 `"stream_options":{}`，上游不会返回任何用量。
- 响应里的 `usage` 只有正文聊天请求才可以消费并持久化。RP 元数据、状态兜底、上下文摘要
  这些内部请求的用量绝不能覆盖会话的「上次实际用量」。
- 不要在日志中输出 API Key 或完整用户消息。
- 网络日志 tag 使用 `muxueChat.*` 前缀，方便 Logcat 筛选。

#### thinking mode 请求语义

本小节只适用于 DeepSeek，不能透传或套用到自定义供应商的 OpenAI 兼容请求。

DeepSeek 官方文档明确：**`thinking` 的缺省值是 `enabled`**（默认 `high` 档）。
因此「省略 `thinking` 字段」等于开启思考，而不是关闭。

- thinking mode 关闭时，**必须显式发送 `thinking: { "type": "disabled" }`**，
  并且不发送 `reasoning_effort`：

```json
{
  "thinking": { "type": "disabled" }
}
```

- thinking mode 开启时，发送：

```json
{
  "thinking": { "type": "enabled" },
  "reasoning_effort": "high"
}
```

#### 不生效的参数一律不发送

- thinking mode 开启时，`temperature` 和 `top_p` 会被服务端静默忽略（不报错）。
  此时不要发送这两个字段，避免请求体与 UI 语义不一致。
- `presence_penalty` 和 `frequency_penalty` 已被 DeepSeek 标记为废弃，
  **任何情况下都不生效**，已从 DTO、`ChatSettings` 和设置页中整体移除。不要重新加回。
- 请求体构造统一走 `data/remote/DeepSeekRequestFactory.kt`，
  不要在 Repository 里手写 `DeepSeekChatRequest`。不同用途（普通聊天、RP 元数据、
  RP 状态兜底、设定生成）通过 `Purpose` 区分各自的 token 预算与采样策略。
- `max_tokens` 必须经过 `ChatSettings.coerceMaxTokens()` 收敛，
  domain、DataStore、请求构造三层都要校验，不能只在 UI 限制。

#### 上下文预算

- 上下文占用一律按**模型真实 token 窗口**计算，口径集中在
  `domain/context/ModelContextSpec.kt` 与 `domain/context/ChatContextPlanner.kt`。
  不允许再引入任何写死的字符预算。
- 一次请求的最终消息、UI 的占用百分比、硬裁剪与压缩判据都必须来自同一个
  `ContextWindowPlan`。请求层只做 DTO 映射，不得自行拼装或二次裁剪。
- 估算走 `HeuristicTokenEstimator`，不引入任何 tokenizer 依赖；结果在 UI 上必须标注为「预计」，
  只有响应返回的 `usage` 才能称为真实用量。
- 模型窗口的解析顺序固定为「用户逐模型覆盖 → 内置常量 → 32K 回退」，回退值必须在 UI 上明确警告，
  不得伪装成供应商的真实能力。也不得按模型名猜测自定义供应商的窗口。

### 3.4 data.repository 层

职责：

- 协调 local、remote、settings。
- 负责发送消息、重新生成、停止生成、后续删除会话等业务流程。
- 对 presentation 层暴露稳定的 Flow 或 suspend API。

规则：

- Repository 可以把底层异常转换成用户可读错误。
- 出错时应更新消息状态为 `FAILED`，并写入 `errorMessage`。
- 不要吞掉关键异常而让 UI 永远处于 loading。
- 取消生成应更新 `STREAMING` 消息为 `CANCELLED`。

### 3.5 presentation 层

职责：

- Compose UI。
- ViewModel。
- UI state 和 UI event。

规则：

- UI 不直接调用任何 Provider API。
- UI 不直接读写 Room、DataStore、Keystore。
- UI 通过 `ChatViewModel` 暴露的方法交互。
- UI 风格是 Claude-inspired：暖色、低对比、文本优先、安静克制。
- 不使用 Claude、Anthropic 的 logo、品牌素材或一比一复刻。
- Android 16 edge-to-edge 必须正确处理：
  - status bar
  - navigation bar
  - IME / 键盘
- 输入框不能被键盘遮挡。
- 小屏不能横向滚动。
- 触控目标尽量不小于 44dp。

## 4. 当前 ViewModel 边界

`ChatViewModel` 是当前 UI 的主要入口。

已有职责：

- 观察会话列表。
- 观察当前会话消息。
- 观察聊天设置。
- 发送消息。
- 停止生成。
- 重新生成最后一条回复。
- 保存/清除当前 Provider 的凭据。
- 暴露错误和状态给 UI。

后续新增功能应优先补 ViewModel 方法，而不是让 UI 直接依赖 Repository。

例如删除会话应按以下方向实现：

1. DAO 添加 `deleteConversation(conversationId)`。
2. Repository 暴露 `deleteConversation(conversationId)`。
3. domain/usecase 添加 `DeleteConversationUseCase`。
4. `ChatViewModel` 添加 `deleteConversation(conversationId)`。
5. UI 在会话列表中提供删除入口，并加确认。

## 5. 编码规范

### 5.1 Kotlin

- 遵循 Kotlin 官方风格。
- 优先使用不可变数据结构和 `copy()`。
- 函数职责保持单一。
- 不为一两个调用点过早抽象。
- 避免全局可变状态。
- 异步使用 coroutine、Flow、StateFlow。
- 不在主线程执行网络和数据库重操作。
- 错误信息面向用户时使用中文，日志可以使用英文。

### 5.2 Compose

- Composable 尽量保持无副作用。
- 副作用使用 `LaunchedEffect`、`rememberCoroutineScope` 等明确表达。
- UI state 从上层传入，组件通过回调上报事件。
- 小组件放在 `presentation/.../components`。
- 页面级组件放在对应 feature 包，例如 `presentation/chat/ChatScreen.kt`。
- 不要让 UI 组件持有 Repository、DAO、API Client。

### 5.3 Room / DataStore

- Room entity 与 domain model 分离。
- Entity 到 domain 的转换放在 local 层或邻近文件。
- DataStore 用于普通设置（含自定义供应商列表：名称、Base URL、模型列表、当前选中项），不存任何 Provider 凭据。
- DeepSeek API Key 与各自定义供应商的 API Key 只能通过 Keystore 加密存储；OAuth 凭据仅限 xAI，同样只能通过 Keystore 加密存储，不进 DataStore、Room。

### 5.4 网络

- 使用 OkHttp。
- 使用 kotlinx.serialization。
- 解析 DeepSeek 与 OpenAI 兼容端点的 SSE 时必须兼容：
  - `data: {...}`
  - `data: [DONE]`
  - 空行
  - 非 data 行
- 自定义供应商接受 HTTP 与 HTTPS。`android:usesCleartextTraffic="true"` 已按用户要求开启，改动它前必须先与用户确认。
- 网络错误必须落到用户可见状态。
- 401、402、403、429、5xx、DNS、timeout 应有可读提示。

## 6. 日志与安全

允许日志：

- 请求开始。
- 请求结束。
- HTTP 状态码。
- 错误类型。
- 消息长度。
- conversationId。

禁止日志：

- API Key。
- Authorization header。
- OAuth 的 access token、refresh token、id token、授权码、`device_code`、`user_code`、PKCE `code_verifier`、`state`。
- 完整用户消息。
- 完整 system prompt。
- 角色卡敏感内容。
- 任一 Provider 的完整响应正文，除非经过截断并确认不含敏感信息。
- 自定义供应商的完整 Base URL 中如含用户可识别信息，日志只输出 host。

推荐 Logcat 筛选：

```text
package:com.muxuechat muxueChat
```

## 7. 构建与测试

Windows 下优先使用：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

如果 Android Studio 正在同步依赖，可先用 Android Studio 的 Build 面板。

常见问题：

- 如果 D8 或 Gradle OOM，检查 `gradle.properties` 中的 `org.gradle.jvmargs`。
- 如果缺 Android SDK 36，应安装 SDK，不要降级。
- 如果 Logcat 无信息，确认筛选的是 `com.muxuechat` 进程。

当前项目可能产生的构建产物：

- `app/build/`
- `.gradle/`
- `*.hprof`

这些不应作为源码修改内容提交或交给其他 Agent 分析，除非用户明确要求排查构建缓存或内存转储。

## 8. 依赖管理

- 不要随意新增第三方依赖。
- 新增依赖前必须说明用途和替代方案。
- 现有技术可解决时，优先使用现有依赖。
- 不引入大型框架，例如 Hilt、Navigation Compose、通用多 Provider SDK，除非用户明确确认。
- 已确认的双协议路由保持手写、轻量，仅覆盖 DeepSeek 与 OpenAI 兼容协议。
- 当前项目使用手写轻量依赖组装，保持简单。

## 9. UI/UX 规范

整体视觉：

- 暖白、浅米、低对比边框。
- 文本阅读优先。
- 少装饰。
- 操作入口清晰但不喧宾夺主。
- 间距、圆角与触控目标统一取自 `presentation/theme/Dimens.kt`（`Spacing`、`Corner`、`MinTouchTarget`），
  不在组件里另写新数字；层次靠留白与分隔线表达，不给每块内容都套卡片。
- 状态不能只靠颜色表达：选中、警告、禁用都要同时有文字、图标或边框，禁用控件要说明原因。

聊天页：

- 用户消息右侧轻量气泡。
- assistant 消息正文块优先。
- reasoning 内容默认折叠。
- 错误消息直接显示在对应 assistant 消息位置。
- 正在生成时必须可停止。
- 自动滚动只在用户接近底部时跟随；用户在看历史时不得强行拉回，改为显示「回到底部 / 有新内容」。
- 编辑类 Sheet 统一用 `EditorSheet` + `rememberGuardedDismiss`：有未保存草稿时，返回、点遮罩、取消都先确认。

设置页：

- 各 Provider 的 API Key 手动保存，且不展示已保存明文。
- 其他设置实时保存。
- 必须提示”设置会在下一次发送消息时生效”。
- 设置页不提供 Token 导入或第三方 CLI 身份复用入口。唯一允许的登录入口是 xAI 订阅登录，入口旁必须显示账号风险提示；登出必须二次确认（与「清除 Key」一致）。
- 供应商地址为 http 时，设置页必须显示明文风险提示。
- 供应商的增删改在设置页完成；删除供应商必须二次确认，并同时清除其 Keystore 条目。xAI 订阅供应商没有 API Key，其登录是全局的：删除该供应商只删除它的模型列表与上下文窗口配置，**不退出登录**；退出登录是「xAI 订阅登录」分组的独立操作，二次确认同样适用。删除对话框必须写明这一点。
- thinking mode、reasoning effort 及其采样参数提示仅适用于 DeepSeek；自定义供应商下不得误导性展示为可生效的 DeepSeek 专属配置。
- 设置页按任务分组（服务与模型、RP 辅助请求、提示词与生成、思考与采样、上下文管理、外观），低频分组默认折叠，
  折叠摘要写当前值；分组折叠或离页前必须 flush 未提交的文本草稿。
- 清除已保存的 Key 必须二次确认。
- 模型目录最多 2,000 项：不得在普通 `verticalScroll` 里急切组合完整目录或逐模型输入框，
  统一用 `ModelListWindow`（当前项 + 已配置项 + 搜索命中的有限条）或 `LazyColumn`，截断时必须提示还有多少未显示。

聊天页的模型选择：

- 顶栏第一行是会话标题与操作，第二行是可点的「供应商 · 模型」选择器，长模型名只在第二行内省略。
- 面板按供应商分组，用 `LazyColumn` + 搜索列出该供应商已知的可用模型；选中即同时切换供应商与模型。
- 模型列表可在面板内触发刷新（`GET {base}/models`）；拉取失败只提示，不清空已缓存的列表。
- 刷新在 ViewModel 中全局串行：任一列表刷新期间，所有刷新入口都要禁用并写明原因，不能点了没反应。
- 模型标注文案统一出自 `presentation/common/ModelPresentation.kt` 的 `ModelNotes`，只认完整模型 ID，不按名字猜能力。
  图片输入上线前，`deepseek-flash` 与视觉实验旧名必须标注「当前 App 仅支持文本输入，尚未支持图片。」
- 不要展示 DeepSeek 已废弃的参数控件（presence penalty、frequency penalty）。

## 10. Agent 工作流

任何 Agent 开始修改前：

1. 读取本文件。
2. 查看相关包和调用链。
3. 明确本次修改范围。
4. 不回滚其他 Agent 或用户的无关改动。
5. 修改后尽量运行构建或测试。
6. 如果无法运行，说明具体原因。

提交回复时应说明：

- 修改了哪些文件。
- 为什么这样改。
- 是否运行了构建/测试。
- 是否存在未覆盖风险。

## 11. 明确禁止

- 禁止输出或记录 API Key。
- 禁止把 API Key 明文保存到 Room/DataStore。
- 禁止擅自降级 Android SDK。
- 禁止把 OpenAI 兼容链路扩展成通用多 Provider 框架，或为某个具体厂商新增私有协议适配。**唯一例外是 §2 / §3.3 所述 xAI 订阅链路的 Responses 传输适配器**：它藏在 OpenAI 兼容传输接口之后，对上层不可见，且不得扩大到其它厂商。
- 禁止在 App 内实现或复用任何 OAuth、浏览器 Cookie、第三方 CLI client ID、access token、refresh token 或登录身份。唯一例外为 §2 所述 xAI 订阅登录；该例外内仍禁止读取第三方 CLI 本地凭据文件、保存浏览器 Cookie、将 OAuth token 发往非钉死主机。
- 禁止在未经用户确认的情况下改动明文 HTTP 策略（收紧或进一步放开都算）。
- 禁止把任一供应商的 API Key 写入 DataStore、Room 或供应商列表 JSON。
- 禁止把 RP 模式直接做成一段不可维护的硬编码 prompt。
- 禁止在 UI 层直接访问数据库或网络。
- 禁止为了修一个问题进行大范围无关重构。
- 禁止提交或依赖 `build/`、`.gradle/`、`*.hprof` 等构建产物。
