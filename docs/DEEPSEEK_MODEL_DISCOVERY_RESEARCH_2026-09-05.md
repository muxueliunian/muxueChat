# DeepSeek 模型列表与动态发现核查

核查日期：2026-09-05  
范围：只采用 DeepSeek 官方 API 文档、官方更新日志与官方 API 端点响应；不采用媒体、社区或聚合站资料。

> **2026-09-16 状态更新（与下文冲突时以本段为准）**
>
> 1. 下文「项目现状」描述的是 2026-09-05 核查当日的代码。其后已实施：DeepSeek 模型 ID 改为开放字符串
>    （`DeepSeekModels`）、内置兜底列表、用 DeepSeek 自己的 Base URL 与 Key 请求 `GET /models`
>    （`SettingsRepository.refreshDeepSeekModels`）、已选模型不在目录中的提示，以及视觉实验模型「暂未实现图片输入」的统一文案。
> 2. DeepSeek 于 2026-09-10 发布 DeepSeek-V4.1-Flash，新请求 ID 为 `deepseek-flash`（原生多模态）；
>    `deepseek-v4-flash` 与 `deepseek-v4-flash-vision-exp` 只作为旧名暂时兼容，请求由 V4.1 Flash 处理。
>    官方 Chat Completions 的 `model` 取值现为 `deepseek-flash`、`deepseek-v4-pro`，其中 `deepseek-v4-pro` 不支持图片输入：
>    [Change Log](https://api-docs.deepseek.com/updates/)、[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)。
> 3. 因此下文「当前公开模型」表格，以及「内置 3 个 ID、默认 `deepseek-v4-flash`」的建议已经过时。
>    2026-09-16 已实施迁移（`docs/MULTIMODAL_PLAN.md` MM-0）：`DEFAULT_ID = deepseek-flash`，
>    `BUILTIN_FALLBACK_IDS = [deepseek-flash, deepseek-v4-pro]`，已保存的旧名由 `DeepSeekModelIdMigration` 一次性改写。

## 结论摘要

1. **项目的 DeepSeek 模型列表确实写死了。** `DeepSeekModel` 是只有 `deepseek-v4-flash`、`deepseek-v4-pro` 两项的 Kotlin 枚举，模型选择 UI 直接遍历该枚举；DeepSeek 专属客户端也没有模型发现方法。自定义 OpenAI 兼容供应商已有 `GET /models`，但没有覆盖 DeepSeek 分支。
2. **DeepSeek 当前公开的 API 模型 ID 已有 3 个。** 除项目已有的两个文本模型外，2026-08-21 新增了实验性多模态模型 `deepseek-v4-flash-vision-exp`。官方首页、Models & Pricing 和 Chat Completions API 当前都列出这 3 个请求 ID：[Your First API Call](https://api-docs.deepseek.com/)、[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)、[Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)。
3. **DeepSeek 官方支持 `GET /models`。** 官方参考文档将其定义为“列出当前可用模型”，响应中的 `data[].id` 可直接用于其他 API 端点：[Lists Models](https://api-docs.deepseek.com/api/list-models)。2026-09-05 对 `https://api.deepseek.com/models` 的无凭据只读探测返回 `401 Authorization Required` 而非 `404`；这也说明实际拉取必须带有效 API Key。本次核查未读取或使用项目/用户凭据，因此没有伪造“实时成功响应”。
4. **建议把 DeepSeek 的“可选模型目录”改为运行时发现 + 本地缓存 + 内置兜底，而不是继续扩充枚举。** 这是根据官方接口和更新历史作出的工程推论，并非 DeepSeek 官方对客户端架构的原文要求。模型能力元数据仍需与模型 ID 分开管理，因为 `/models` 的公开 schema 只有 `id`、`object`、`owned_by`，没有模态、上下文长度或实验状态字段：[Lists Models](https://api-docs.deepseek.com/api/list-models)。

## 当前公开模型

| 请求中的 `model` ID | 官方当前版本/性质 | 与项目现状的关系 |
|---|---|---|
| `deepseek-v4-flash` | 当前映射到 DeepSeek-V4-Flash-0731；调用 ID 不变 | 已写入枚举 |
| `deepseek-v4-pro` | 当前映射到 DeepSeek-V4-Pro-0813；调用 ID 不变 | 已写入枚举 |
| `deepseek-v4-flash-vision-exp` | 2026-08-21 上线的实验性视觉模型，同时接受文本和图片 | 项目枚举缺失 |

上述版本映射来自官方首页和价格页；官方明确要求继续用稳定的小写 ID 调用，而不是把 `DeepSeek-V4-Flash-0731`、`DeepSeek-V4-Pro-0813` 这样的版本展示名当作请求 ID：[Your First API Call](https://api-docs.deepseek.com/)、[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)。

新视觉模型通过 Chat Completions 接受普通文本，也支持文本与图片混合输入。图片消息需要把 `content` 从字符串改为内容块数组；只有该视觉模型接受图片，图片出现在不允许的角色或发给非视觉模型时会返回 `400`：[Vision guide](https://api-docs.deepseek.com/guides/vision/)。因此，本项目即使先把它作为**纯文本模型**开放，也不能宣称已经支持视觉输入；真正支持发图还需要 DTO、附件存储、请求体和 UI 的独立改造。

## 最近的模型与命名变化

- **2026-04-24：** DeepSeek-V4 Preview 上线，新增请求 ID `deepseek-v4-pro` 和 `deepseek-v4-flash`。旧 ID `deepseek-chat`、`deepseek-reasoner` 在过渡期分别路由到 V4 Flash 的非思考/思考模式，并公告会在 2026-07-24 15:59 UTC 后完全停用：[V4 Preview announcement](https://api-docs.deepseek.com/news/news260424/)、[Change Log](https://api-docs.deepseek.com/updates/)。
- **2026-07-31：** V4 Flash 更新为官方公测版本 DeepSeek-V4-Flash-0731，但请求 ID 仍为 `deepseek-v4-flash`：[Change Log](https://api-docs.deepseek.com/updates/)。
- **2026-08-13：** V4 Pro GA 更新为 DeepSeek-V4-Pro-0813，但请求 ID 仍为 `deepseek-v4-pro`：[Change Log](https://api-docs.deepseek.com/updates/)。
- **2026-08-21：** 新增独立请求 ID `deepseek-v4-flash-vision-exp`，这是实验性多模态模型；官方明确给出该 `model` 值，并说明支持 Chat Completions、Messages 与 Responses：[官方发布公告](https://api-docs.deepseek.com/news/news260821/)、[Change Log](https://api-docs.deepseek.com/updates/)。

这段历史显示 DeepSeek 同时采用两种发布方式：

- **稳定 ID 后端升级：** `deepseek-v4-flash`、`deepseek-v4-pro` 保持不变，实际版本在后台更新；客户端不应把日期版本展示名替换进请求。
- **新能力新增 ID：** 视觉能力没有复用原有 ID，而是增加 `deepseek-v4-flash-vision-exp`；只写死旧 ID 的客户端不会自动出现该模型。

`deepseek-chat`、`deepseek-reasoner` 已过官方退役日期，不应重新加入默认列表或作为新安装的迁移兜底。官方 2026-04-24 公告明确说它们届时“fully retired and inaccessible”：[V4 Preview announcement](https://api-docs.deepseek.com/news/news260424/)。

## `GET /models` 的支持范围与一个文档陷阱

官方接口契约为：

```text
GET https://api.deepseek.com/models
Authorization: Bearer <DeepSeek API Key>
```

成功响应为 `object: "list"`，模型位于 `data` 数组，每项至少含可用于 API 请求的 `id`、固定值 `object: "model"` 和 `owned_by`：[Lists Models](https://api-docs.deepseek.com/api/list-models)。

需要注意：截至核查日，`Lists Models` 页面的**示例响应**仍只展示 `deepseek-v4-flash`、`deepseek-v4-pro` 两项，而官方首页、价格页、更新日志和 Chat Completions 的 `model` 枚举已经列出第三个视觉模型。因此：

- 不应把参考页中的示例 JSON 当成穷举清单；
- 客户端应解析认证后的实际 `data[].id`；
- 发布时仍应保留一份经官方当前 Models & Pricing 校验过的内置兜底，以应对离线、401、超时或临时服务错误；
- 若要对某个账号/地区声称“此刻实际返回哪些 ID”，必须用该账号有效 Key 调用，不能用文档示例代替。本次研究没有做带凭据调用。

## 对 muxueChat 的建议

### 1. 把“模型 ID”从封闭枚举改成开放值

`DeepSeekModel` 继续作为两项枚举，会让每次新增模型都需要发版，并牵动 DataStore 反序列化、ViewModel 方法签名、UI 遍历和测试。更稳妥的是让所选模型持久化为 `String`（或轻量 value class），DeepSeek 模型目录则是 `List<ModelDescriptor>`。

这不等于允许任意厂商私有协议：Provider 仍然只有项目规范中的 DeepSeek 专属协议和 OpenAI 兼容协议两条；这里只是让 DeepSeek 协议内的 `model` 标识不再封闭。

### 2. 为 DeepSeek 分支补运行时模型发现

建议复用现有自定义供应商的可靠性策略，但使用 DeepSeek 自己保存的 Base URL 与 API Key：

1. 用户在模型面板点击刷新时请求 `GET {deepSeekBaseUrl}/models`；可选地在 API Key 成功保存后做一次刷新。
2. 只解析非空 `data[].id`，去重并缓存最后一次成功结果。
3. 请求失败时保留旧缓存，不阻断聊天，并提供手动刷新；不要用失败结果清空当前选择。
4. 首次安装/无缓存时内置当前 3 个官方 ID 作为兜底，但把 `deepseek-v4-flash` 保持为默认，避免实验性视觉模型意外成为默认。
5. 已选模型若暂时未出现在刷新结果中，应给出“官方当前列表未返回该模型”的警告；是否继续保留选择可以由用户决定，不应静默替换后导致会话模型变化。

`/models` 的官方目标就是返回“currently available models”，且 `id` 被定义为可用于 API 端点的模型标识：[Lists Models](https://api-docs.deepseek.com/api/list-models)。官方又在 2026-08-21 通过新增 ID 扩展了目录：[官方发布公告](https://api-docs.deepseek.com/news/news260821/)。这两点共同构成运行时发现优于封闭枚举的官方事实依据。

### 3. 模型发现与能力注册分开

不要根据名字包含 `vision`、`v4` 等字符串来猜功能。`GET /models` 不返回能力字段，而官方价格页/视觉指南才说明：三个模型均为 1M context、视觉模型可接收图片、FIM 对视觉模型不支持等信息：[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)、[Vision guide](https://api-docs.deepseek.com/guides/vision/)。

建议：

- 动态目录负责“有什么 ID”；
- 本地能力表负责“已知 ID 支持什么”；
- 服务器新出现但本地未知的 ID 默认按最保守的**纯文本 Chat Completions**能力展示，不开启图片、特殊参数或名称推断；
- `deepseek-v4-flash-vision-exp` 在本项目尚无图片请求链路时明确标注“当前仅文本聊天；App 暂未实现图片输入”。

### 4. 迁移时保持向后兼容

- 现有 `V4_FLASH` / `V4_PRO` 设置应无损迁移为对应字符串 ID。
- 未识别字符串不能在读取时强制回退成 `deepseek-v4-flash`，否则未来新模型会在重启后被悄悄改回默认。
- 上下文窗口仍按现有“用户覆盖 → 已知官方常量 → 保守回退”策略处理；`/models` 本身不能提供上下文长度。

## 建议的实施优先级

1. **先做模型目录动态化与持久化兼容**，并把第三个官方 ID 纳入兜底列表。
2. **再决定是否只开放视觉模型的文本能力。** 官方说明其纯文本能力与 V4 Flash 相当，因此技术上可以先作为文本模型使用：[官方发布公告](https://api-docs.deepseek.com/news/news260821/)。UI 必须清楚说明尚不能发图。
3. **图片输入作为独立功能实施。** 它要求 `content` 内容块、图片来源/大小限制、仅 user 角色可带图等协议约束，不应被包装成一次“补枚举”小改动：[Vision guide](https://api-docs.deepseek.com/guides/vision/)。

## 本次未覆盖

- 未使用任何用户 API Key，因此未保存带认证的 `GET /models` 实时 JSON；只核验了官方接口契约及无凭据 `401` 行为。
- 未修改产品代码、配置或测试。
- 模型价格可能继续变化；实现不应把价格作为模型目录判断依据。官方也提示价格可能调整，应查看最新页面：[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)。
