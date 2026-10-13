# Google Vertex AI OpenAI 兼容端点线上格式记录

采集日期：2026-10-14
采集方式：真实请求，认证使用 gcloud ADC access token（本文不记录任何 token）
模型：`google/gemini-4-argon`

> **本文只记录实际观察到的内容。** 未观察到的一律标注「未验证」，不做推测。
> 本文不写任何 token、完整签名值与项目编号。`{PROJECT_ID}` 为占位符。
> 文中引用的响应样本均为脱敏夹具，位于 `app/src/test/resources/vertex/`，脱敏规则见第 9 节。

## 0. 采集范围

| 主题 | 章节 | 夹具 |
| --- | --- | --- |
| 端点、区域、请求头 | 1 | `models_404.html` |
| 推理参数、思考正文、输出预算 | 2 | `nonstream_text_thought_tag.json` |
| 流式文本与工具调用 | 3 | `stream_text_final.sse`、`stream_toolcall.sse` |
| 非流式工具调用（单个与并行） | 4 | `nonstream_toolcall.json`、`nonstream_toolcall_parallel.json` |
| thought_signature 回放 | 5 | `error_400_missing_signature.json` |
| 错误体结构 | 6 | `error_401_array.json`、`error_404_model.json` |

## 1. 端点与请求头

### 1.1 端点（实测）

```text
POST https://aiplatform.googleapis.com/v1/projects/{PROJECT_ID}/locations/global/endpoints/openapi/chat/completions
```

- 请求头：`Authorization: Bearer <access token>`，token 的 scope 为 `cloud-platform`。
- 请求体中 `model` 写 `google/gemini-4-argon`。
- 区域必须为 `global`。`locations/us-central1` 返回 404。
- 未发送 `x-goog-user-project` 时请求成功。

### 1.2 模型列表（实测）

`GET .../endpoints/openapi/models` 返回 404，响应体为 HTML 页面（见 `models_404.html`）。该路径不可用于查询模型列表。

## 2. 推理参数与输出预算

### 2.1 reasoning_effort（实测）

- `low`、`medium`、`high`、`max` 均被接受。
- `usage.completion_tokens_details.reasoning_tokens` 的出现情况（同一提示）：

| reasoning_effort | reasoning_tokens |
| --- | --- |
| low | 不出现 |
| medium | 不出现 |
| high | 168 |
| max | 191 |

- 只传 `reasoning_effort` 时，响应中看不到思考正文。

### 2.2 与 thinking_config 的互斥（实测）

- 同时传 `reasoning_effort` 与 `extra_body.google.thinking_config`，返回 400，错误信息为：
  `Expected one of either reasoning_effort or custom thinking_config; found both.`
- 单独传 `thinking_config`（include_thoughts）时：
  - 非流式：思考正文以 `<think>…</think>` 放在 `content` 开头，且该消息带 `extra_content.google.thought=true`（见 `nonstream_text_thought_tag.json`）。
  - 流式：不返回思考正文。

**结论：本 App 不使用 `thinking_config`，只使用 `reasoning_effort`。**

### 2.3 输出预算与采样参数

- `max_tokens` 与 `max_completion_tokens` 都被接受（返回 200）。
- `max_tokens=16` 时 `finish_reason` 为 `"length"`，内容完整。
- `temperature` 与 `top_p` 同时发送时被接受（返回 200）。**是否生效：未验证。**

## 3. 流式响应（SSE）

### 3.1 基本格式（实测）

- 标准 SSE：每行 `data: {chat.completion.chunk}`，结束行为 `data: [DONE]`。
- 不传 `stream_options` 时，响应仍包含 `usage`。

### 3.2 文本流（实测）

- 最后一个 chunk 同时包含：`delta.content: ""`、`finish_reason: "stop"`、`delta.extra_content.google.thought_signature`、`usage`。
- 夹具：`app/src/test/resources/vertex/stream_text_final.sse`。

### 3.3 工具调用流（实测）

1. 第一个 chunk 内带完整的 `tool_calls`，每个元素包含 `index`、`id`、`function.name`、完整的 `function.arguments`、`extra_content`。
2. 随后的 chunk 带 `delta.content: ""`、`finish_reason: "tool_calls"`、`usage`。
3. 夹具：`app/src/test/resources/vertex/stream_toolcall.sse`。

## 4. 非流式响应

### 4.1 工具调用（实测）

- 签名位于 `tool_calls[i].extra_content.google.thought_signature`。
- 并行两个调用时，**只有第一个调用带签名**，第二个调用没有 `extra_content`。
- 含 `tool_calls` 的 `message` 中**没有 `content` 字段**。
- 夹具：`nonstream_toolcall.json`（单个）、`nonstream_toolcall_parallel.json`（并行）。

### 4.2 思考标签

- 见第 2.2 节。夹具：`app/src/test/resources/vertex/nonstream_text_thought_tag.json`。

## 5. thought_signature 回放（实测）

回放 `assistant(tool_calls)` 与 `tool` 结果时：

| 请求中 assistant 的 tool_calls | 结果 |
| --- | --- |
| 去掉 `extra_content` | 400，`Function call is missing a thought_signature in functionCall parts…` |
| 原样带回 `extra_content` | 200 |

- 单个调用与并行调用的结果相同。
- 普通文本多轮（assistant 消息无签名）返回 200。
- 400 夹具：`app/src/test/resources/vertex/error_400_missing_signature.json`。

**实现要求：** 回放工具调用时必须原样保留每个 `tool_calls[i].extra_content`。

## 6. 错误体

### 6.1 结构（实测）

401、400、404 的响应体都是 **JSON 数组包一个对象**：

```json
[{"error":{"code":401,"message":"…","status":"UNAUTHENTICATED","details":[…]}}]
```

- 解析时必须按数组读取，取第 0 个元素的 `error`。
- 401（无效 token）：`status` 为 `UNAUTHENTICATED`，`details[0].reason` 为 `ACCESS_TOKEN_TYPE_UNSUPPORTED`。测试时使用的是无效字符串 token。夹具：`app/src/test/resources/vertex/error_401_array.json`。
- 400：`status` 为 `INVALID_ARGUMENT`，见第 5 节。
- 404（模型不存在或无权限）：`status` 为 `NOT_FOUND`。夹具：`app/src/test/resources/vertex/error_404_model.json`。
- 404（区域 us-central1）：返回 404，响应体格式**未记录**。
- 404（GET models 路径）：响应体为 HTML 页面，见 `models_404.html`，**不是**上述 JSON 数组。

## 7. 未验证项

- 429 的错误体结构：未验证。
- 真实**过期** access token 的 401 错误体：未验证（本文 401 夹具来自无效字符串 token，`details[0].reason` 可能不同；实现只依赖 HTTP 状态码 401，不依赖该字段）。
- 并行工具调用时第二个调用的 `extra_content` 缺失是否对所有提示都成立：只观察了一次。
- 流式加工具调用的完整往返（流式返回工具调用后，回放结果并得到最终回答）：未验证。
- 重度思考时，小 `max_tokens` 预算是否会被思考 token 耗尽：未验证。
- `temperature` 与 `top_p` 是否生效：未验证。
- Argon 对 RP 的 `<muxue_state>` 指令的遵从度：未验证。

## 8. 与实现的关系

- 本文是 Vertex 适配器实现的依据。凡本文未记录的字段，实现前需先采集再补充。
- 不使用 `thinking_config`，不解析思考正文。
- 工具调用的 `extra_content` 必须原样保存并回放（见第 5 节）。

## 9. 夹具文件清单与脱敏规则

### 9.1 夹具清单

目录：`app/src/test/resources/vertex/`

| 文件 | 内容 |
| --- | --- |
| `stream_text_final.sse` | 流式文本，最后 chunk 带签名与 usage |
| `stream_toolcall.sse` | 流式工具调用，单个调用 |
| `nonstream_toolcall.json` | 非流式工具调用，单个调用 |
| `nonstream_toolcall_parallel.json` | 非流式工具调用，两个并行调用（仅第一个带签名） |
| `nonstream_text_thought_tag.json` | 非流式，思考正文以 `<think>` 放在 content 开头 |
| `error_401_array.json` | 401，无效 token |
| `error_400_missing_signature.json` | 400，回放时缺少 thought_signature |
| `error_404_model.json` | 404，模型不存在 |
| `models_404.html` | GET models 端点返回的 404 HTML 页面 |

### 9.2 脱敏规则

夹具是原始响应的逐字节副本，只做以下两种替换：

1. `"thought_signature":"<值>"` 的值替换为 `TEST_SIG_1`（同一文件出现多次时依次为 `TEST_SIG_1`、`TEST_SIG_2`…）。
2. 响应中出现的真实项目编号（12 位数字）替换为 `000000000000`。

其他字节不变，包括换行符。夹具目录中不含 `ya29` 前缀的 access token。

**注意：** 夹具中的 `TEST_SIG_n` 是占位值，不能用于真实回放测试。真实回放需要使用真实签名，且签名不得写入仓库。
