# 自定义 OpenAI 兼容供应商 改造计划

日期：2026-08-05
状态：已确认，实施中

## 1. 目标

把当前写死的「CLIProxyAPI」第二 Provider 一般化为**用户自定义的 OpenAI 兼容供应商**：

1. 供应商可自定义名称、Base URL（http 或 https）、API Key。
2. 可保存多个供应商并随时切换（就像 DeepSeek 一样被"记住"）。
3. 模型不再是设置页里手填的一个字符串，而是每个供应商各自的**可用模型列表**；
   选择入口移到聊天页顶栏。

## 2. 关键设计决定

### 2.1 仍然只有两条协议分支

`ChatRepositoryImpl` 现有 8 处 `when (provider)` 都是「DeepSeek 专属协议 vs OpenAI 兼容协议」
这一个轴，与供应商个数无关。因此**不把 `ChatProviderId` 改成 sealed class**，
仍保留两值枚举：

```kotlin
enum class ChatProviderId { DEEPSEEK, CUSTOM_OPENAI }
```

具体是哪个自定义供应商，由 `ChatSettings.activeCustomProviderId` 表达。
这样请求层的改动退化为纯重命名，风险最小。

### 2.2 模型发现：拉取优先，手填兜底

- 供应商编辑页与聊天页模型面板都能触发 `GET {base}/models`。
- 拉取成功 → 覆盖该供应商的 `models` 缓存并落 DataStore。
- 拉取失败（404 / 非 JSON / 空列表）→ 只提示，**不清空**已有缓存，用户可手填模型名。
- 聊天页只读缓存，不会因为进入页面就发网络请求。

### 2.3 允许明文 HTTP（用户确认，2026-08-05 调整）

自定义供应商接受 `http://` 与 `https://`，且**不限制地址范围**。
原因：用户的局域网自建服务经常没有证书，强制 HTTPS 会直接堵死这个场景。

代价（已向用户说明并接受）：

- 填 http 地址时，`Authorization` 头（API Key）与聊天正文都以明文经过网络，
  同网段设备或链路上的中间设备可以读取。
- `AndroidManifest.xml` 开启了 `android:usesCleartextTraffic="true"`，
  这是应用级开关，不只对某一个供应商生效。
- 应用层不做私网地址白名单，因此填错成公网 http 地址时不会被拦下，
  只能靠设置页的明文提示提醒。

仍然保留的校验：地址不得包含 user-info、query、fragment，端口必须合法。

### 2.3.1 思考等级（2026-08-05 追加）

自定义供应商支持 OpenAI 系的顶层 `reasoning_effort` 字段：

- 每个供应商一个 `reasoningEffort: String`，**空串表示完全不发送该字段**（默认）。
- 取值不做白名单：各家档位不统一（low/medium/high 之外还有 minimal、none、xhigh 等），
  UI 的 low/medium/high 只是快捷填充按钮，用户可以自己输入任意值。
- 只随正文对话发送。RP 元数据、状态兜底、上下文摘要都是短结构化输出，
  在这些请求上思考只会拖慢补写、多烧 token。
- 不为其他方言（OpenRouter 的 `reasoning` 对象、Qwen 的 `enable_thinking`、
  DeepSeek/GLM 的 `thinking` 对象）新增字段。若将来需要，应做成通用的
  「额外请求体 JSON」，而不是按厂商加分支。

响应侧同时解析 `reasoning_content`（DeepSeek/Qwen/GLM 等）与 `reasoning`（OpenRouter），
取先出现的非空值，复用聊天页已有的折叠式思考区。这是字段别名兼容，不是厂商特判。

### 2.3.2 标准工具调用（2026-08-08 追加）

自定义 OpenAI 兼容供应商可使用标准 Chat Completions function calling：

- 仅非 RP 正文请求可在 `tools` 中携带 `type: "function"` 定义，并发送 `tool_choice: "auto"`。
- 同时解析非流式 `message.tool_calls` 与流式 `delta.tool_calls`；流式参数按 `index` 拼接后，回传 assistant 工具调用消息和 `role: "tool"` 结果消息，再继续下一轮。
- 工具业务语义与 DeepSeek 一致：只产生待用户确认的内存草稿，绝不直接写入 Room。
- 每个 `CustomProvider` 有 `toolCallingEnabled` 开关，**默认 false**。旧 JSON 缺字段也按 false 解析，避免升级后向只支持普通聊天的反代发送未知字段。
- 工具总开关仍由 `ChatSettings.settingsToolsEnabled` 控制；两者都开启且非 RP 时才实际挂载。此能力只针对标准 OpenAI 字段，不为 Gemini、Grok 或任意厂商写私有分支。

### 2.4 Key 存储

DeepSeek 的 `EncryptedApiKeyStore` 原样不动（避免已保存的 Key 失效）。
新增 `EncryptedProviderKeyStore`，一个供应商一个 Keystore alias
（`muxuechat_provider_key_<providerId>`），密文存在独立 SharedPreferences。
删除供应商时同时删除其密文与 alias。

### 2.5 旧数据迁移

首次启动到新版本时，若 DataStore 里存在旧键 `cli_proxy_api_base_url` 且非空，
而新键 `custom_providers` 不存在，则：

1. 生成一条供应商记录：名称 `CLIProxyAPI`，Base URL 与模型取自旧键。
2. 把 `EncryptedCliProxyApiKeyStore` 里的 Key 复制到新的 per-provider alias。
3. 若旧 `chat_provider == "cli_proxy_api"`，则把它设为当前选中供应商。

迁移只执行一次，用 `custom_providers_migrated` 布尔键打标。

## 3. 数据模型

```kotlin
data class CustomProvider(
    val id: String,                 // UUID，Keystore alias 与 DataStore 索引都用它
    val name: String,               // 用户自定义显示名，如 "CLIProxyAPI"、"OpenRouter"
    val baseUrl: String,            // http(s):// 端点，不含 /chat/completions
    val models: List<String>,       // 可用模型缓存（拉取或手填）
    val selectedModel: String,      // 当前选中模型
    val modelsUpdatedAtMillis: Long,
    val toolCallingEnabled: Boolean, // 标准 tools/tool_calls；默认 false
    val reasoningEffort: String,    // OpenAI 系 reasoning_effort；空串 = 不发送
)
```

`ChatSettings` 变更：

- 删除 `cliProxyApiBaseUrl`、`cliProxyApiModel`
- 新增 `customProviders: List<CustomProvider>`、`activeCustomProviderId: String?`
- `activeBaseUrl` / `activeModel` 改为从当前供应商解析
- `isCliProxyApi` → `isCustomProvider`，`isCliProxyApiConfigured` → `isCustomProviderConfigured`

DataStore 序列化：`custom_providers` 存 kotlinx.serialization 的 JSON 字符串。
**Key 绝不进入该 JSON。**

## 4. 重命名

| 旧 | 新 |
|---|---|
| `CliProxyApiClient` | `OpenAiCompatibleClient` |
| `CliProxyApiDtos.kt` / `CliProxyApiChatRequest` 等 | `OpenAiCompatibleDtos.kt` / `OpenAiCompatibleChatRequest` 等 |
| `CliProxyApiRequestFactory` | `OpenAiCompatibleRequestFactory` |
| `CliProxyApiStreamEvent` / `CliProxyApiStreamParser` | `OpenAiCompatibleStreamEvent` / `OpenAiCompatibleStreamParser` |
| `ChatProviderId.CLI_PROXY_API` | `ChatProviderId.CUSTOM_OPENAI` |
| 用户可见文案 "CLIProxyAPI ..." | 供应商名 / "自定义供应商" |

`ChatProviderId.fromStorage` 需继续认得旧值 `"cli_proxy_api"`。

## 5. 任务拆分

1. **domain + local**：`CustomProvider`、`ChatSettings` 改造、`SettingsDataStore` JSON 读写与迁移、`EncryptedProviderKeyStore`。
2. **remote**：整体重命名 + `GET /models` 的 DTO 与 `listModels()`。
3. **repository**：`SettingsRepository` 的 Key/供应商 API；`ChatRepositoryImpl` 跟随重命名，`freezeProviderRequest` 按供应商 id 取 Key。
4. **presentation-设置页**：供应商列表、添加/编辑/删除、Key 保存、模型拉取与手填。
5. **presentation-聊天页**：顶栏显示当前供应商+模型（修掉现有恒显示 DeepSeek 字段的 bug），点击弹出模型选择面板。
6. **测试与构建**：重命名既有 3 个 OpenAI 兼容单测，新增 `ChatSettings` 供应商解析、`/models` 解析用例，跑 `:app:testDebugUnitTest` 与 `:app:assembleDebug`。

## 6. 已知风险 / 未覆盖

### 2026-10-02：本轮兼容范围与配置

用户将目标收敛为 Command Code GOAT 与 OpenCode Go/Go Plus 的文本 Chat Completions 模型。用户尚未订阅 OpenCode，接受官方文档核对、合成 HTTP/SSE、代码集成回归；真实连通性及服务对普通聊天/RP 用途的接纳不作为本轮完成前置，保持“未验证”。

| 服务 | Base URL | 接入说明 |
| --- | --- | --- |
| Command Code GOAT | `https://api.commandcode.ai/provider/v1` | 只选支持 `/chat/completions` 的模型；目录 `/models` |
| OpenCode Go / Go Plus | `https://opencode.ai/zen/go/v1` | 只选官方端点表中的 Chat Completions 模型；会话标识头填写 `x-opencode-session`；目录 `/models` |

来源：[Command Code Provider API](https://commandcode.ai/docs/provider)、[OpenCode Go](https://opencode.ai/docs/go/)，2026-10-02 核对。Go 文档面向编程代理请求，协议模拟通过不等于该服务已接受本 App 的聊天/RP 流量；不伪装为其它客户端。仅有 Responses 或 Messages 接口的模型不属于本轮目标。

实现采用通用设置，不按服务名称分叉：高级连接选项新增输出预算字段选择（旧默认 `max_tokens`，可选 `max_completion_tokens`）与可选会话标识头。标准 OpenAI 兼容客户端使用自身 `muxueChat/1.0` 标识。会话标识在正文、工具续轮、标题、压缩、RP 元数据与状态兜底请求保持一致；xAI OAuth 不接收此配置。

响应完整性：`refusal` 显示为可读回复；缺终止事件、截断工具参数、重复调用 ID 与不完整调用明确失败；`length` / `content_filter` 保留已收到正文并显示原因；HTTP 200 中的通用错误包也进入同一安全清理边界。对应合成回归覆盖普通和流式、工具结果回传及 usage-only 尾块，结果见 `docs/DEFECT_FIX_TASKS.md`。

### 既有边界

- 真机连通性、各家中转站 `/models` 的实际返回格式需用户实测。
- 部分服务的 `/models` 会返回上百个模型且混有 embedding/tts 模型，本版不做过滤，只按 id 排序。
- 供应商删除后，历史消息不会回溯标注它们由哪个供应商生成（当前数据模型本就不记录）。
- 只支持 DeepSeek 协议与 OpenAI 兼容格式；Anthropic、Gemini 等原生协议不支持，设置页已明确标注。
- `reasoning_effort` 是否被上游接受由服务决定：填错值或模型不支持时会拿到 400，App 只透传不校验。
- 明文 HTTP 已开放：安全性完全取决于用户填写的地址，公网 http 端点会泄露 API Key 与聊天内容。
