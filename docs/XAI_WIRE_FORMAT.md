# xAI Responses API 线上格式记录

采集日期：2026-09-22
采集方式：mitmproxy 12.2.3 + pi 0.87.0
模型：`grok-4.7`

> **本文只记录实际观察到的内容。** 未观察到的一律标注「未采集」，不做推测。
> 这是 XAI-2 适配器实现的唯一依据。

## 0. 两轮采集的范围

| 轮次 | provider | 端点 | 覆盖 |
| --- | --- | --- | --- |
| 第一轮 | pi 内置 `xai` | `https://api.x.ai/v1/responses` | 纯文本、思考、多轮 |
| 第二轮 | `pi-xai-oauth` 的 `xai-auth` | `https://cli-chat-proxy.grok.com/v1/*` | 纯文本、思考、**工具调用**、models-v2、user、billing |

两条路径的 SSE 事件一致；请求体只有系统提示的放法不同（见第 2 节，2026-09-23 更正），
其余差别在 host、请求头与 `Accept`。因此适配器的翻译逻辑对两者通用。

> **2026-09-23 复核**：为接入工具调用重读了第二轮原始抓包（005、006），发现第 1 版漏记了
> 工具结果回传的形状，并把第一轮的两条观察误写成了两条路径的共同结论。
> 本次更正的段落都标了日期；复核只提取字段结构，未读取对话正文。

## 1. 请求头

### 1.1 订阅路径 `cli-chat-proxy.grok.com`（实测）

```text
authorization: Bearer <OAuth access token，本次 921 字符>
Accept: text/event-stream
content-type: application/json
X-XAI-Token-Auth: xai-grok-cli
x-grok-client-identifier: pi-xai-oauth      ← 发送方自己的名字
x-grok-client-version: 1.6.0                ← 发送方自己的版本
x-grok-client-mode: interactive
x-grok-conv-id:    01a0c932-3c97-7134-bb6f-a4527c7162bf
x-grok-session-id: 01a0c932-3c97-7134-bb6f-a4527c7162bf   ← 与 conv-id 相同
x-grok-req-id:     1acc76ec-0463-4342-b67f-bae4b73eb74a   ← 每请求不同
x-grok-model-override: grok-4.7
x-authenticateresponse: authenticate-response
```

**要点**：

- `x-grok-client-identifier` 实测为 `pi-xai-oauth`，即**发送方自报的包名，不是 Grok CLI 的身份**，
  且被服务端接受。这证实了计划 2.4 的判断：muxueChat 可以发自己的包名与 `versionName`。
  （仍不能证明任意标识都被接受，但风险显著低于原判断。）
- `x-grok-conv-id` 与 `x-grok-session-id` 在同一会话内相同，`x-grok-req-id` 每请求新生成。
- `X-Stainless-*` 是 OpenAI JS SDK 的遥测头，**muxueChat 不需要发送**。

### 1.2 公开路径 `api.x.ai`（对照）

只需要 `Authorization: Bearer <token>` + `content-type`，
**完全不需要任何 `x-grok-*` 或 `X-XAI-Token-Auth`**。OAuth token 可直接使用。

## 2. 请求体（两条路径一致）

```json
{
  "model": "grok-4.7",
  "input": [ … ],
  "stream": true,
  "store": false,
  "max_output_tokens": 488300,
  "prompt_cache_key": "<会话 id>",
  "reasoning": { "effort": "medium", "summary": "auto" },
  "include": ["reasoning.encrypted_content"],
  "tools": [ … ]
}
```

- **系统提示的两种放法（2026-09-23 更正）**：第一轮（`api.x.ai`）放在 `input[0]`，
  角色是 **`developer`**（不是 `system`），`content` 是**纯字符串**；第二轮（订阅路径）
  改用顶层 **`instructions`** 字段，`input[0]` 直接是用户消息。第 1 版写的「`instructions` 未使用」
  只对第一轮成立。App 在订阅路径上用 `developer` 写法，2026-09-23 真机验收通过，
  说明订阅路径**也接受** `developer` 写法。
- `previous_response_id` **未发送**；多轮靠完整重发 `input`。
- `prompt_cache_key` 用于前缀缓存。
- `tool_choice`、`parallel_tool_calls` **两轮都未发送**，模型照样会调用工具（2026-09-23 复核）。

### 2.1 `input[]` item 形状

```jsonc
{ "role": "developer", "content": "……纯字符串……" }
{ "role": "user", "content": [ { "type": "input_text", "text": "你好" } ] }
{ "type": "message", "role": "assistant",
  "content": [ { "type": "output_text", "text": "……", "annotations": [] } ] }

// 工具调用与结果回传（第二轮 005 / 006，2026-09-23 补记）
{ "type": "function_call", "id": "fc_<uuid>_0", "call_id": "call-<uuid>-0",
  "name": "list_dir", "arguments": "……JSON 字符串……" }
{ "type": "function_call_output", "call_id": "call-<uuid>-0", "output": "……纯字符串……" }
```

pi 回放的 message 与 function_call 项还带 `status: "completed"` 与 `id`。

**reasoning item 的回传（2026-09-23 更正）**：第 1 版写「历史中没有回传任何 reasoning item」，
这不准确。两轮里 pi 都会把**自己产生的回合**连同 reasoning item（含 `encrypted_content`）一起回放：

```jsonc
{ "type": "reasoning", "id": "rs_<uuid>", "status": "completed",
  "summary": [ { "type": "summary_text", "text": "…" } ], "encrypted_content": "…" }
```

同时，第一轮的历史里有两条 assistant 消息**没有配对的 reasoning item**，id 是客户端自编的
`msg_pi_1` / `msg_pi_3`，上游照常接受。结合 App 从不回传 reasoning、普通多轮真机验收通过，
可以确认的只有：**普通多轮不回传 reasoning item 是被接受的。**
工具轮不回传是否被接受，见 5.5。

### 2.1.1 工具轮的回放顺序（第二轮 006）

一轮带工具的回复被回放为：`reasoning` → `message`（工具前言）→ 本轮全部 `function_call`
→ 按调用顺序排列的全部 `function_call_output`。同一请求里连续两轮工具调用时，
第二轮的这组 item 紧接在第一轮的 `function_call_output` 之后，中间**没有**用户消息。

`call_id` 的序号在同一会话内跨轮递增（第二轮第一个调用是 `-3`），`fc_` id 的序号则每轮从 `_0` 重来。

### 2.2 `tools[]` 形状

扁平，**不是** chat/completions 的嵌套结构：

```json
{ "type": "function", "name": "read", "description": "…", "parameters": { … } }
```

## 3. SSE 事件

`Content-Type: text/event-stream`。每个事件同时有 `event:` 行和 `data:` 里的 `"type"`，
取值相同。每个 data 带递增 `sequence_number`。

### 3.1 事件清单（实测）

```text
response.created
response.in_progress
response.output_item.added
response.output_item.done
response.content_part.added
response.content_part.done
response.output_text.delta
response.output_text.done
response.reasoning_summary_part.added
response.reasoning_summary_part.done
response.reasoning_summary_text.delta
response.reasoning_summary_text.done
response.function_call_arguments.delta
response.function_call_arguments.done
response.completed
```

### 3.2 ⚠️ 多个 output item 会**并发交错**（实现上最容易写错的地方）

实测的工具调用流（004）：

```text
output_item.added            output_index=0  item.type=reasoning
  reasoning_summary_* …
output_item.done             output_index=0
output_item.added            output_index=1  item.type=message
  content_part.added
  output_text.delta ×15
output_item.added            output_index=2  item.type=function_call  ← 正文项尚未结束！
  function_call_arguments.delta / .done      output_index=2
output_item.done             output_index=2
output_item.added            output_index=3  item.type=function_call
  function_call_arguments.delta / .done      output_index=3
output_item.done             output_index=3
output_item.added            output_index=4  item.type=function_call
  function_call_arguments.delta              output_index=4
  output_text.done                           output_index=1   ← 正文项此刻才收尾
  content_part.done                          output_index=1
output_item.done             output_index=1
  function_call_arguments.done               output_index=4
output_item.done             output_index=4
response.completed
```

**结论：解析器不能假设「同一时刻只有一个活动 item」。**
必须按 `output_index` 分别维护状态。

每个事件都带 `output_index`；delta 类事件还带 `item_id`，`output_text.delta` 另有 `content_index`。

### 3.3 item 类型与关键字段

`output_item.added` 的 `item`：

| `item.type` | 关键字段 | 对应内容 |
| --- | --- | --- |
| `reasoning` | `id` 前缀 `rs_` | 思考，后续走 `reasoning_summary_*` |
| `message` | `id` 前缀 `msg_` | 正文，后续走 `content_part` / `output_text` |
| `function_call` | `id` 前缀 `fc_`、`name`、`call_id` | 工具调用，后续走 `function_call_arguments` |

- `call_id` 形如 `call-<uuid>-<序号>`，**工具结果回传时必须原样带回**。
- `function_call_arguments.delta` 的 `delta` 是 JSON 字符串片段；
  本次采集中一次就发完了，但**不能假设不分片**——`.done` 的 `arguments`
  才是权威的完整值，实现上应累积 delta 并以 `.done` 为准。

工具调用三种事件的字段（004，2026-09-23 补记）：

```jsonc
// output_item.added：arguments 为空串，status 为 in_progress
{ "type": "response.output_item.added", "output_index": 2,
  "item": { "type": "function_call", "id": "fc_…_0", "call_id": "call-…-0",
            "name": "list_dir", "arguments": "", "status": "in_progress" } }
// function_call_arguments.done：带完整 arguments 与 name，没有 call_id
{ "type": "response.function_call_arguments.done", "output_index": 2,
  "item_id": "fc_…_0", "name": "list_dir", "arguments": "…" }
// output_item.done：item 里是完整 arguments，status 为 completed
{ "type": "response.output_item.done", "output_index": 2,
  "item": { "type": "function_call", "id": "fc_…_0", "call_id": "call-…-0",
            "name": "list_dir", "arguments": "…", "status": "completed" } }
```

以工具调用收尾的一轮，`response.completed` 的 `status` **仍是 `completed`**，
`response.output[]` 的类型依次为 `reasoning`、`message`、`function_call` ×3。
没有单独的「需要执行工具」终止状态。

### 3.4 未采集的事件

以下场景未触发，事件名未知，**不得凭猜测实现**：

- 达到 `max_output_tokens` 的截断结束
- 失败 / 错误事件
- 客户端主动取消后的断流
- 429 / 限额
- 无终止事件的异常断流

## 4. `usage`（在 `response.completed` 中）

```json
{
  "input_tokens": 10576,
  "input_tokens_details": { "cached_tokens": 1152 },
  "output_tokens": 118,
  "output_tokens_details": { "reasoning_tokens": 103 },
  "total_tokens": 10694,
  "num_sources_used": 0,
  "num_server_side_tools_used": 0,
  "cost_in_usd_ticks": 68448800,
  "context_details": { … }
}
```

适配器必须归一字段名：

| Responses | OpenAI 兼容 |
| --- | --- |
| `input_tokens` | `prompt_tokens` |
| `output_tokens` | `completion_tokens` |
| `total_tokens` | `total_tokens` |
| `input_tokens_details.cached_tokens` | 现有缓存命中字段 |
| `output_tokens_details.reasoning_tokens` | 无对应，可丢弃或另行展示 |

`response.completed` 中还有 `status: "completed"`、`store: false`、
`metadata.system_fingerprint`、`incomplete_details: null`。
`incomplete_details` 推测在截断时非空，但**未采集**。

### 4.1 `cost_in_usd_ticks` 的更正

第一轮曾把该字段视作「按 API 计费」的反向证据。**第二轮推翻了这个判断**：
订阅路径 `cli-chat-proxy` 的响应中同样存在该字段（68448800）。

因此它只是**成本等价报告**，不能用来区分订阅额度与 API 计费额度。
D1 的验证仍需依赖 `/v1/billing?format=credits`（见第 5 节）。

## 5. 三个 JSON 接口（第三轮补采，2026-09-22）

### 5.1 `GET /v1/billing?format=credits` —— **D1 已确认**

```json
{ "config": {
  "currentPeriod": { "type": "USAGE_PERIOD_TYPE_WEEKLY",
                     "start": "…", "end": "…" },
  "creditUsagePercent": 17.0,
  "productUsage": [ { "product": "GrokBuild", "usagePercent": 14.0 },
                    { "product": "GrokChat",  "usagePercent": 3.0 } ],
  "onDemandCap":    { "val": 0 },
  "onDemandUsed":   { "val": 0 },
  "prepaidBalance": { "val": 0 },
  "isUnifiedBillingUser": true,
  "topUpMethod": "TOP_UP_METHOD_SAVED_PAYMENT_METHOD",
  "billingPeriodStart": "…", "billingPeriodEnd": "…"
} }
```

**结论：D1「使用订阅额度而非 API 计费」成立。**

依据：用量以**周期内百分比**计（`creditUsagePercent`、`productUsage[].usagePercent`），
周期类型是 `USAGE_PERIOD_TYPE_WEEKLY`；而 `onDemandUsed`、`onDemandCap`、
`prepaidBalance` **全为 0**，说明本次请求没有动用按量付费或预付额度。

这同时最终解释了 4.1：`cost_in_usd_ticks` 只是成本等价报告，与扣费来源无关。

XAI-4 可直接用 `creditUsagePercent` 与 `productUsage` 做额度展示，
配合 `currentPeriod.end` 显示重置时间。

### 5.2 `GET /v1/user`

字段：`userId`、`email`、`firstName`、`lastName`、`profileImageAssetId`、`xUserId`、
`principalType`、`principalId`、`teamId` / `teamName` / `teamRole`、
`organizationId` / `organizationName` / `organizationRole`、`userBlockedReason`、
`teamBlockedReasons`、`codingDataRetentionOptOut`、`hasGrokCodeAccess`、`canAdministerTeam`。

个人账号下 team / organization 相关字段均为 `null`。

**注意**：该响应包含真实邮箱与 X 用户 ID，属于个人可识别信息，不得进日志或测试快照。
App 只需要 `email` 用于账号展示——而这一项 `id_token` 里已经有了，
因此 **XAI-1a 现有的账号显示无需改用此接口**。

### 5.3 `GET /v1/models-v2` —— 比预期有用得多

```json
{ "object": "list", "data": [ {
  "id": "grok-4.7", "object": "model", "owned_by": "xAI",
  "model": "grok-4.7", "model_family": "xai",
  "name": "Grok 4.7", "description": "…",
  "context_window": 500000,
  "api_backend": "responses",
  "auto_compact_threshold_percent": 80,
  "system_prompt_label": "Grok 4.7",
  "reasoning_effort": "high",
  "supports_reasoning_effort": true,
  "reasoning_efforts": [ { "id": "xhigh", "value": "xhigh", "label": "Extra High",
                           "description": "…", "default": false }, … ],
  "supports_backend_search": true,
  "compactions_remaining": 1, "compaction_at_tokens": true
}, … ] }
```

请求头（2026-09-23 从原始抓包补记，只提取了头名与非敏感值）：

```text
GET https://cli-chat-proxy.grok.com/v1/models-v2
Accept: application/json
Authorization: Bearer <token>
X-XAI-Token-Auth: xai-grok-cli
x-authenticateresponse: authenticate-response
x-grok-client-identifier / x-grok-client-version / x-grok-client-mode（同 §1.1）
```

**没有** `x-grok-conv-id`、`x-grok-session-id`、`x-grok-req-id`、`x-grok-model-override`。
三次采集的响应都是 200，`etag` 相同。

本次返回四个模型：`grok-4.7`、`grok-4.7-build-fast`（"Fast variant. 2x the price."）、
`grok-4.6`、`grok-4.5`。**全部 `context_window: 500000`**。

对 muxueChat 的直接价值：

- **`context_window` 可直接喂给 `ModelContextSpec`**，不需要用户手填上下文窗口，
  也不会落到 32K 回退。这是本轮意外收获。
- **`reasoning_efforts[]` 给出了 `reasoning.effort` 的合法取值**
  （`xhigh` / `high` / `medium` / `low`，`grok-4.5` 无 `xhigh`），
  并带 `label` / `description` / `default`，可直接驱动思考强度选择 UI。
  这一项原本标为「待 XAI-0 确认」，现已确定，且**逐模型不同**，不能写死成全局枚举。
- `api_backend: "responses"` 可用于判断该模型是否走 Responses 协议。

### 5.4 已知未支持（实现侧，非上游限制）

以下字段上层会构造，但适配器**不翻译**，因为抓包中从未出现，无法确认字段名与语义：

| 字段 | 处理 |
| --- | --- |
| `temperature` | 不发送。构造了就记一条 warn 日志（只记字段名，不记取值） |
| `top_p` | 同上 |
| `tool_choice` | 不发送，也不记日志。两轮抓包都没有这个字段（第 2 节），而不发送时模型照样调用工具；上层恒为 `"auto"` |

temperature / top_p 要支持必须先补采一轮带这些参数的请求，不得照 OpenAI Responses 的公开文档猜字段名。

工具调用本身**已支持**（2026-09-23，XAI-5）。第 1 版以「结果回传形状未采集」为由拒绝工具请求，
复核后确认形状已在 2.1 中，拒绝已移除。

### 5.5 仍未采集 / 未验证

见 3.4：截断结束、失败/错误事件、主动取消断流、429、无终止事件的异常断流。

工具轮回放有两处与 pi 的做法不同，抓包无法回答，只能真机验证。**2026-09-23 XAI-5 真机验收通过，两处均被上游接受**：

1. **不回传 reasoning item。** App 的上层消息模型装不下 `encrypted_content`（计划 D7）。
   普通多轮已确认可以不回传（2.1），工具轮未验证。
2. **回放的 `function_call` 不带 `id`（`fc_…`）。** 上层只保留 `call_id`。
   message 项不带 `id` 已确认可以（App 的普通多轮就是这样发的），function_call 项未验证。

若上游将来改为拒绝，按 `docs/XAI_OAUTH_PLAN.md` XAI-5 的方案 B 处理，而不是在上层开口子。

## 6. 采集资料

原始抓包在本机临时目录，**未入库**：
`scratchpad/xai-capture-round1-apix/`（第一轮）、`scratchpad/xai-capture/`（第二轮）。

脚本 `xai_capture.py` 已对 Authorization 头、token 类字段与裸 JWT 打码，
但原始文件仍含完整对话正文、本机路径与目录结构，**不要提交进仓库**。
