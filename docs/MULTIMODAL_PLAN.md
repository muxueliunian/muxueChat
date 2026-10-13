# muxueChat 多模态（图片输入）实施计划

状态：MM-0 已实现待真机验收；其余分期尚未实施（2026-09-16 已按独立审查意见修订）  
建立日期：2026-09-16  
范围：用户消息的图片输入（DeepSeek 与自定义 OpenAI 兼容供应商），以及作为前置条件的 DeepSeek 模型 ID 迁移  
不在范围：图片生成、音频/视频、PDF 视觉解析、角色卡图片

> 本文中的代码位置来自 2026-09-16 的工作区快照，行号仅供参考。实施时以类名、函数名和调用链为主定位。

## 进度记录（接续工作先读这里）

最后更新：2026-10-09（核对提交状态；上次内容更新为 2026-09-23 的 Room 版本调整）

| 分期 | 状态 | 说明 |
| --- | --- | --- |
| 计划审查 | 完成 | 独立审查（Opus）提出的 H1/H2、M1～M8、L1～L7 已全部写入本文 |
| MM-0 | `[~]` 已实现，待真机验收 | 实现记录见第 4 节 MM-0 |
| MM-1a | 未开始 | **下一步**：用户确认后开工 |
| MM-1b / MM-2 / MM-3 | 未开始 | — |

当前工作区状态：

- MM-0、MM-0 之前的缺陷修复与 2026-09-20 的前端 UI/UX 优化**已提交**，见 `6705af1`（2026-09-22「建立基线」）；
  本计划文档首次提交于 `291f596`，v10→v11 调整随 `270e669` 提交。工作区现有未提交改动属于 RP / xAI 等其他线，不含多模态代码。
- 最近一次验证（2026-09-20）：`testDebugUnitTest`（294 个，0 失败）、`assembleDebug`、
  `compileDebugAndroidTestKotlin` 均成功，`git diff --check` 无问题；未跑 `lint`。
- 2026-09-20 已用 `adb install -r` 覆盖安装到真机（保留数据），首次启动时会执行 MM-0 迁移。
  现已有可用真机，androidTest 可以在其上执行，但尚未执行。
- 前端 UI/UX 优化与多模态无关：图片仍不可发送；`deepseek-flash` 与视觉实验旧名在界面上标注「仅支持文本输入」，
  MM-1b 上线图片发送时需同步移除或改写 `ModelNotes.TEXT_ONLY_INPUT` 的使用点。

待用户处理：

1. MM-0 真机验收：旧版选中 `deepseek-v4-flash` 后升级，确认显示并请求 `deepseek-flash`；
   在 `deepseek-flash` 上验证普通、流式、思考、工具调用与 RP Marker。
2. D6（允许纯图片消息）是按审查建议采纳的默认值，用户尚未明确表态；如需改为「必须带文字」，在 MM-1a 开工前告知。

MM-1a 开工时的要点（详见 3.1～3.5）：

- 先做统一历史映射，替换 `HistoryReplacementHistory` 私有的 `toPromptMessages`，否则重新生成会丢图；
- 文件去留一律查库决定（提交后可能重新抛出取消）；
- 规划顺序：缺失检查 → 剥图 → 20 MiB 上限 → 估算与裁剪；
- 发送预检基于冻结后的 `requestSnapshot`，保持「先写用户消息、失败写失败回复」；
- 含图片请求使用流式 `RequestBody`，并单独放宽 `readTimeout`；
- Room **v11** 需导出 `11.json`，并补 `MigrationTestHelper` 10→11 测试。
  （v10 已被 `docs/RP_ACTOR_PLAN.md` 的 RP-1 占用，2026-09-23 改。）

## 0. 已确认的产品决策

2026-09-16 由用户确认：

| # | 决策 |
| --- | --- |
| D1 | 已保存的旧 DeepSeek 模型 ID（`deepseek-v4-flash`、`deepseek-v4-flash-vision-exp`）**自动迁移**到 `deepseek-flash`。 |
| D2 | 历史中的图片**每轮都重发**（仍受上下文预算与请求体安全上限约束，见 3.3）。 |
| D3 | 本地压缩规格：长边不超过 **1568px**，JPEG 质量 **85**；每条用户消息最多 **4 张**。 |
| D4 | 编辑历史用户消息时**允许增删图片**。 |
| D5 | 先落计划，按第 4 节分期实施；每期开工前由用户确认。 |
| D6 | **允许只发图片、不写文字**（按审查建议采纳；如用户改变主意，只需恢复 3.5 中的两处非空校验）。 |

## 1. 官方事实（2026-09-16 核查）

只采用 DeepSeek 官方文档：
[Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion)、
[Vision guide](https://api-docs.deepseek.com/guides/vision/)、
[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)、
[Change Log](https://api-docs.deepseek.com/updates/)。

模型：

- 2026-09-10 发布 DeepSeek-V4.1-Flash，请求 ID 为 `deepseek-flash`，原生多模态，1M 上下文。
- `deepseek-v4-pro`：1M 上下文，**不支持图片输入**。
- 旧名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 仍被接受，但对应模型已退役，请求**都**由 V4.1-Flash 处理；更新日志称其为暂时兼容，未公布停用日期。
- Chat Completions 的 `model` 取值现为 `deepseek-flash`、`deepseek-v4-pro`。

图片请求格式：

- 用户消息的 `content` 可以是字符串，也可以是 content part 数组。
- 图片块：`{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}`；`url` 也接受 http(s) 地址；可选 `detail`。
- 格式按文件内容识别：JPEG、PNG、GIF、WebP。
- 限制：单图 32 MiB（base64/URL）；请求体 48 MiB；单请求最多 600 张；每边最大 8192px（15 张及以上时为 4096px）。
- 服务端会把图片缩放到约 1300×1300 以内，**每张图最多计 1024 tokens**，多图分别计费。
- **只有 user 消息可以带图片**，system/assistant 带图返回 400。
- 另有 DeepSeek 私有的 Files API（`type: "file"`），本项目不使用。

其他：

- 流式请求不发 `stream_options` 时，`usage` 仍会出现在最后一个 chunk。
- 官方文档**没有写明**视觉输入与 thinking 模式、tools 能否同时使用，需真机验收。
- OpenAI 兼容上游使用同样形状的 `image_url` content part，但是否支持图片取决于具体服务，App 无法从标准 `GET /models` 得知。

## 2. 不得突破的边界

- 请求协议仍然只有「DeepSeek 专属」与「OpenAI 兼容」两条。图片块采用两者共有的标准 `image_url` 形状，不使用 DeepSeek Files API，不发送 `detail` 等可选字段，不为任何厂商写特判。
- 不新增第三方依赖：选图用系统 Photo Picker（`ActivityResultContracts.PickVisualMedia` / `PickMultipleVisualMedia`，无需权限），解码与压缩用 `ImageDecoder` + `Bitmap.compress`，缩略图自行降采样解码，流式请求体用 OkHttp 自带的 okio，不引入 Coil 等图片库。若后续确有需要，另行说明用途与替代方案并取得确认。
- `minSdk` / `targetSdk` / `compileSdk` 保持 36。
- 图片内容、base64、原始 `content://` URI、完整文件路径**绝不进入日志**、错误提示或测试快照；日志只允许张数、字节数、宽高。
- 图片只保存在 App 私有 `filesDir`。现有 `data_extraction_rules.xml` 已排除 `file` 域，不参与云备份与设备迁移；不写入系统相册。
- 本地重新编码会丢弃 EXIF（含 GPS 定位），这是期望行为，不得为保留元数据而上传原图字节。
- 模型是否支持图片**不得按模型名猜测**，解析顺序与上下文窗口一致：用户逐模型覆盖 → 内置已知 ID → 默认不支持。
- 以下内部请求一律不发图片，只发文字占位：RP 元数据补写、RP 状态兜底、上下文摘要、设定生成工具。

## 3. 总体设计

### 3.1 数据层

**新表 `message_attachments`（Room v11）**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | UUID |
| `messageId` | TEXT NOT NULL | 外键 → `messages(id)`，`ON DELETE CASCADE`，建索引 |
| `conversationId` | TEXT NOT NULL | 冗余列，便于按会话清理和孤儿扫描，建索引（不另设外键，会话删除经 messages 级联） |
| `kind` | TEXT NOT NULL | 目前只有 `IMAGE` |
| `mimeType` | TEXT NOT NULL | 压缩后固定 `image/jpeg` |
| `relativePath` | TEXT NOT NULL | 相对 `filesDir` 的路径，例如 `chat_images/<id>.jpg` |
| `byteSize` | INTEGER NOT NULL | 压缩后字节数 |
| `width` / `height` | INTEGER NOT NULL | 压缩后尺寸 |
| `sha256` | TEXT NOT NULL | 完整性校验与去重排查 |
| `orderIndex` | INTEGER NOT NULL | 同一消息内的顺序 |
| `createdAtMillis` | INTEGER NOT NULL | |

- 单独建表而不是给 `messages` 加 JSON 列：流式期间 `messages` 高频更新，附件不可变，不应随之反复序列化；级联删除也更可靠。
- `MIGRATION_10_11` 只新建表，不重建既有表。导出 `11.json`，并用 `MigrationTestHelper` 按 `10.json → 11.json` 做真实升级测试。
- 已核实 `ChatDao.upsertMessage` 使用 `@Upsert`、`updateMessage` 使用 `@Update`，不会触发 `INSERT OR REPLACE` 式的先删后插，因此更新消息不会级联删除附件。实施时必须补回归测试，防止以后被改回 REPLACE。
- DAO 提供 `observeAttachments(conversationId): Flow<List<MessageAttachmentEntity>>`，供上下文面板与消息列表使用（见 3.3、3.6）。

**文件存储**

- 已发送：`filesDir/chat_images/<attachmentId>.jpg`。数据库是唯一真相来源。
- 待发送：`filesDir/chat_images/pending/<id>.jpg`。发送时移动到正式目录。
- 写文件一律「临时文件 + 原子 rename」，避免半截文件。
- 同一张图只在导入时压缩一次，此后每轮重发**完全相同的字节**，便于上游前缀缓存命中。

**文件清理（基本清理属于 MM-1，不能拖到 MM-2）**

- 删除会话、清空会话、历史替换删除后续消息的 DAO 事务，返回被删附件的 `relativePath`；**事务提交后**再删文件，失败只记日志（异常类型），不回滚数据库。否则 MM-1 上线后删除的会话会在手机上留下图片，存在隐私问题。
- **判断「是否已提交」一律以数据库状态为准，不以异常分支为准**。现有提交都在 `withContext(NonCancellable)` 中执行，事务已经提交后，返回边界仍可能重新抛出取消（`isCompletionCommitted` 就是为此而设）。因此：
  - 新文件：出现异常后先查库，**附件行不存在**才删除对应文件；
  - 被移除附件的文件：只有查库确认附件行**已不存在**时才删除；
  - 查库本身也放在 `NonCancellable` 中。
- 启动孤儿扫描（MM-2）：
  - 由 `Application` 或 Repository 初始化时在后台协程中**进程级只执行一次**，**不放进 Room `onOpen` 回调**（回调运行在建连路径上，不能做文件 IO 或调用 DAO）。须在 `STARTUP_GENERATION_RECOVERY_CALLBACK` 完成之后执行（例如先对数据库做一次查询以确保已打开）。
  - 删除数据库不再引用的 `chat_images/*.jpg`，以及超过 24 小时的 `pending/` 文件。
  - rename 会保留文件原来的修改时间，所以「修改时间早于 10 分钟」保护不住刚移动过来、事务尚未提交的文件。改为：移动到正式目录后立即 `setLastModified(now)`；并且扫描与发送/编辑提交共用同一把进程内 `Mutex`，扫描期间不发生移动与提交。
- 附件只挂在 user 消息上。启动恢复删除的 `history-replacement:` 行都是 assistant 暂存消息，不涉及附件。

### 3.2 domain 层

- 新增 `MessageAttachment`（id、messageId、mimeType、width、height、byteSize、orderIndex），不含字节。
- `ChatMessage.attachments: List<MessageAttachment> = emptyList()`。
- `PromptMessage` 增加 `images: List<PromptImageRef> = emptyList()`，其中 `PromptImageRef` 含 `attachmentId`、`mimeType` 与 **`byteSize`**（规划器按它计算请求体上限，无需读盘）。默认空列表，现有构造点与测试无需改动。
- 新增 `ModelInputCapabilities.supportsImageInput(settings, modelId)`：
  - DeepSeek 内置：`deepseek-flash` 支持；两个旧名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` **判断一致**，都按支持处理（官方称两者都由 V4.1-Flash 处理；MM-0 迁移后这只影响用户在未知/手填场景重新输入旧名的情况）；`deepseek-v4-pro` 不支持；其余 ID 默认不支持。
  - DeepSeek 用户覆盖：`ChatSettings.deepSeekModelImageInput: Map<String, Boolean>`，用于服务端新出现的未知 ID。
  - 自定义供应商：`CustomProvider.modelImageInput: Map<String, Boolean>`，按模型手动开启，默认关闭；旧 DataStore JSON 缺字段时视为关闭。
- 常量集中定义：`MAX_IMAGES_PER_MESSAGE = 4`、`MAX_LONG_EDGE_PX = 1568`、`JPEG_QUALITY = 85`、`MAX_IMAGE_PAYLOAD_BYTES = 20 MiB`。

### 3.3 Prompt 与上下文规划

继续遵守「一次请求的最终消息、UI 占用和裁剪判据都来自同一个 `ContextWindowPlan`」。

**统一历史映射（修正：重新生成与编辑原先会静默丢图）**

- 现状：正常发送走 `ChatRepositoryImpl.toPromptHistory`；重新生成（`HistoryReplacementHistory.beforeAssistant`）与编辑（`throughEditedUser`）走 `HistoryReplacementHistory` 自己的私有 `toPromptMessages`。后者拿不到附件，若不改，重新生成时历史图片会**静默丢失**。
- 改为只保留**一个**纯映射函数，签名形如 `List<MessageEntity>.toPromptHistory(attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>>, excludeMessageId)`，发送、重新生成、编辑、上下文面板四处共用。`HistoryReplacementHistory` 只负责截取与替换消息列表（编辑时同时替换该消息的附件集合），再调用这个函数。
- 过滤条件由 `content.isNotBlank()` 改为「**文字非空或带有图片**」（D6）。
- COMPLETED 的 user 消息带上图片引用。RP 状态段和 RP Marker 仍只追加到文本部分。

**规划顺序（固定，写进代码注释与测试）**

1. **缺失文件检查**：映射前确认每个附件文件存在。缺失的图片在此处替换为占位 `[图片文件已丢失]`，并计入 `missingImageCount`，UI 给出提示。不得留到请求层再悄悄替换，否则估算与实际请求不一致。
2. **剥图**：当前请求所用模型不支持图片时，把每条**历史**消息的图片替换成文字占位，例如 `[图片 ×2：当前模型不支持图片输入，已省略]`；UI 提示「本轮将省略 N 张历史图片」。本轮新消息带图但模型不支持时不走剥图，而是在预检阶段直接失败（见 3.5）。
3. **请求体上限**（D2 的唯一例外）：按 `PromptImageRef.byteSize` 换算 base64 大小（×4/3 向上取整），总量超过 `MAX_IMAGE_PAYLOAD_BYTES` 时从最旧的消息开始把图片换成占位 `[更早的图片因请求体积上限已省略]`；**本轮用户消息的图片永不省略**。按 1568px JPEG 单张约 200～500 KB 估算，20 MiB 约可容纳 30～75 张历史图片。
4. **token 估算与 `findKeepFromIndex` 裁剪**：`HeuristicTokenEstimator` 新增图片估算，**每张图的 token 数由规划器按模型传入**（DeepSeek 已知视觉模型 1024；自定义供应商无官方口径，保守取 1600），估算器本身不判断模型。一律标注「预计」。图片 token 计入对应消息所在分项。
- `ContextWindowPlan` 额外暴露 `imageCount`、`omittedImageCount`（区分原因：不支持 / 体积上限）与 `missingImageCount`。
- `toEstimationMessages`（`ChatRepositoryImpl` 中两处）目前使用 `content.orEmpty()`，DTO 类型变更后需同步改为「取文本部分 + 图片计数」。
- 已被摘要覆盖的消息本来就不发送，其图片也不再发送；这与 D2 不冲突。
- 摘要请求只看到文字占位 `[用户发送了 N 张图片]`，因此摘要会丢失图片内容，这是已知限制。

**上下文面板**

- `observeContextWindow` 当前只 `combine` 消息流与设置等。必须把 `observeAttachments(conversationId)` 加入 combine，并使用上面的同一个映射函数，否则面板占用与实际请求不一致。

### 3.4 请求层

- `DeepSeekMessageDto.content` 与 `OpenAiCompatibleMessageDto.content` 从 `String?` 改为 `MessageContentDto?`：
  - `Text(String)` 序列化为 JSON 字符串；
  - `Parts(List<ContentPartDto>)` 序列化为数组，包含 `{"type":"text","text":...}` 与 `{"type":"image_url","image_url":{"url":...}}`；纯图片消息（D6）不生成空的 text 块；
  - `type` 字段与现有 `tools` 同理，用 `@EncodeDefault(ALWAYS)` 强制序列化；
  - 用自定义 `KSerializer` 实现；响应 DTO 不变。
- **没有图片的消息继续发字符串**，只支持纯文本的旧反代不受影响。
- 构造点：`ChatRepositoryImpl` 中实际约 **12 处**直接构造两种消息 DTO，另有 `ContextSummaryPrompt` 等主代码构造点，以及约 **7 个测试文件**直接构造这些 DTO。为减少改动，**新增**（当前并不存在）`text(role, content)` 形式的伴生工厂函数，统一替换这些构造点。
- **流式请求体**（不再推迟到出现 OOM 后）：20 MiB 图片的 base64 若先拼成 `String` 再编码为请求体，峰值内存会有多份副本（原始字节、base64 字符串、JSON 字符串、UTF-8 字节），可能超过 100 MB。含图片的请求改为自定义 `RequestBody`：在 `writeTo(sink)` 中逐条写出消息 JSON，图片部分边读文件边 base64 编码写入 `sink`，不在内存中保留整份请求体。`contentLength()` 返回 -1（分块传输）。纯文本请求保持现状。
- base64 读取：新增 `ImageAttachmentStore`（data.local），只负责按 `relativePath` 打开文件流；读取发生在 OkHttp 写请求体时（IO 线程）。请求层只做映射，不得二次裁剪或替换图片。
- **超时（修正原计划的误判）**：
  - OkHttp 的 `writeTimeout` 限制的是**单次写操作之间的间隔**，不是整个上传的总时长；上传慢但持续有进度不会触发它。现有 30 秒可保留。
  - 真正的风险是 `readTimeout = 60s`：服务端要先接收并处理大量图片（可能还有思考），首个字节可能晚于 60 秒到达。含图片的请求用 `client.newBuilder().readTimeout(...)` 单独放宽（例如 180 秒），共享连接池，不影响纯文本请求。具体数值在真机验收后确定。
- 错误映射（沿用 `DeepSeekErrorSanitizer` 不回显正文的原则）：
  - 413 提示「请求体过大，请减少图片」；
  - 400 无法可靠判断是否与图片有关，因此只在**本次请求含图片**时追加一句留有余地的提示，例如「如果问题与图片有关，可能是当前模型或服务不接受图片输入」，不断言原因。

### 3.5 Repository 流程

**`sendMessage(conversationId, content, images)`**

- 非空校验：现有 `require(trimmed.isNotEmpty())` 改为「文字非空**或**带图片」（D6）。
- 保持现有语义不变：**先写入用户消息，再冻结请求配置**（`freezeProviderRequest`）；冻结或预检失败时仍走 `persistSendPreflightFailure`，写入失败的 assistant 回复并在 UI 显示。原计划中「在任何写库之前失败」的写法会改变这一语义，已作废。
- 图片能力预检**基于冻结后的 `requestSnapshot`**（冻结那一刻的供应商与模型），不再读取实时设置。这样用户在发送途中切换模型时，本轮图片既不会被误剥，也不会违反「本轮图片永不省略」。本轮带图而冻结的模型不支持图片时，预检失败，写入可读错误；不走剥图。
- 写入顺序：在同一把附件 `Mutex` 内，先把 pending 文件移入正式目录并 `setLastModified(now)`，再在同一 Room 事务中插入 user 消息与附件行。事务出现异常后按 3.1 的规则**查库确认**后再决定是否删除已移动的文件。
- ViewModel 本应在 UI 层拦截不支持图片的情况，Repository 的预检是兜底。

**`regenerateLastResponse`**

- 修正：图片**不会**自动随历史带上。必须改用 3.3 的统一映射函数，并在调用前按会话读取附件映射。
- 验收中单列「重新生成带图对话，请求仍含历史图片」。

**`editUserMessageAndRegenerate(messageId, content, imageEdit)`**

其中 `imageEdit` 描述保留哪些原附件、新增哪些 pending 图片。

- 遵守 FIX-02 语义：配置预检失败时历史完全不变；新增或保留了图片但冻结的模型不支持图片，同样视为预检失败。
- `HistoryReplacementHistory.throughEditedUser` 使用编辑后的文本与图片集合，再走统一映射函数规划请求。
- `ChatDao.commitHistoryReplacement` 增加「新增附件行」与「删除附件 id」参数，在同一事务中切换；提交前比对原消息的附件快照，历史在生成期间变化时拒绝提交。
- 文件处理：新图片文件在提交前移入正式目录，但只有提交后才被引用。无论出现何种异常（含提交后重新抛出的取消），都**先查库**：
  - 新附件行不存在 → 删除这些新文件；已存在 → 保留；
  - 被移除的附件行已不存在 → 删除其文件；仍存在 → 保留。
- 启动孤儿扫描作为最后兜底。

**RP 补写**

- `RpTurnSnapshot.lastUser`：文本 + `[用户附带了 N 张图片]`，不发图片。

### 3.6 ViewModel 与 UI

- `ChatUiState` 增加 `pendingImages` 与 `isImportingImages`。现有文本文档附件（`PendingAttachment`）保留，二者可以同时存在。
- 发送按钮的可用条件改为「文字非空、或有待发送图片、或有文本文档附件」（D6）。
- 输入栏附件按钮改为菜单：「图片」「文本文档」。
  - 剩余名额 ≥ 2 时用 `PickMultipleVisualMedia(maxItems)`，剩余 1 张时用 `PickVisualMedia`（前者要求 maxItems ≥ 2）。
  - 导入在后台线程进行：`ImageDecoder` 按目标尺寸降采样解码；带透明通道的 PNG/WebP 先铺白底再转 JPEG；GIF 与动图只取首帧；HEIC 由系统解码后转 JPEG。EXIF 方向处理需用真机照片验证。
  - 解码失败、源文件过大时给出可读提示，不改变已有待发送图片。
- 预览条：缩略图、删除按钮、「2/4」计数。
- 当前模型不支持图片时，图片入口禁用并说明原因（自定义供应商引导到对应模型开关）；已有待发送图片后切到不支持的模型时，禁止发送并提示。
- 消息气泡：user 消息显示缩略图网格（降采样解码 + 小容量内存 LruCache）；纯图片消息不显示空文本块。
- 编辑消息弹窗（MM-2）：显示原图片，可删除，可继续添加（受 4 张上限约束）；删光文字与图片时禁止提交。
- 设置页：
  - 自定义供应商的模型列表增加逐模型「支持图片输入」开关；
  - DeepSeek 上下文窗口编辑区为未知 ID 提供同样的开关；
  - 删除现有 `ModelNotes.TEXT_ONLY_INPUT`（`ModelPresentation.kt`、`ContextSections.kt`）这句「尚未支持图片」的文案。
- 上下文面板：显示本轮图片张数、省略张数及原因、丢失张数。
- 无障碍：缩略图提供 `contentDescription`，触控目标不小于 44dp。

### 3.7 规范文档同步（随实施提交）

- `AGENT.md`：
  - §2、§3.3 的 DeepSeek 模型清单；
  - 两条协议的 content 数组规则（无图时保持字符串）；
  - 图片不进日志与存储位置规则；
  - 模型图片能力的解析顺序；
  - 内部请求不发图片；
  - 「是否已提交以数据库状态为准」的文件清理规则。
- `docs/ROADMAP.md` 当前状态；`docs/DEEPSEEK_MODEL_DISCOVERY_RESEARCH_2026-09-05.md` 顶部状态段。

## 4. 分期任务

状态标记沿用 `docs/DEFECT_FIX_TASKS.md`：`[ ]` 未实施、`[~]` 已实现待设备或真实服务验收、`[x]` 完成。

### [~] MM-0：DeepSeek 模型 ID 迁移（前置，小）

> 2026-09-16 实现记录：
> - 新增 `data/local/DeepSeekModelIdMigration.kt`，经 `preferencesDataStore(produceMigrations = ...)` 注册；
> - `DeepSeekModels` 新增 `FLASH`、`LEGACY_FLASH_IDS`、`isLegacyFlashId`，默认与兜底改为新 ID；
> - `ModelNotes` 新增 `LEGACY_FLASH_ID` 与 `deepSeekCapabilityNotes`（设置页与模型面板共用）。在 MM-1b 前，
>   `deepseek-flash` 显示「仅支持文本输入」，旧名显示「已由 deepseek-flash 取代」；
> - `:app:testDebugUnitTest` 294 个用例通过（新增 `DeepSeekModelIdMigrationTest` 9 个），`:app:compileDebugAndroidTestKotlin` 成功；
>   新增的 androidTest `legacyDeepSeekModelIds_areMigratedBeforeFirstRead` 因本机无 API 36 设备尚未执行；
> - 未完成：真机验收（见下方验收最后一条）。

改动：

- `DeepSeekModels`：
  - 新增 `FLASH = "deepseek-flash"`，`DEFAULT_ID = FLASH`，`BUILTIN_FALLBACK_IDS = [FLASH, V4_PRO]`；
  - 旧常量保留并标注为旧名；
  - `knownContextWindowTokens` 加入 `FLASH`；
  - `supportsRpMarker` 加入 `FLASH`。理由：2026-09-10 起旧名 `deepseek-v4-flash` 已由 V4.1-Flash 处理，现有 Marker 流量实际已在该模型上运行，开启不引入新行为；仍需真机验收。
- 迁移触发位置：实现为 DataStore 的 `DataMigration<Preferences>`（例如 `DeepSeekModelIdMigration`），通过 `preferencesDataStore(name = "chat_settings", produceMigrations = ...)` 注册。DataStore 保证它在首次读取前原子执行；`shouldMigrate` 检查完成标记 `deepseek_model_ids_migrated_v1`，保证幂等。迁移对象单独暴露，测试用 `PreferenceDataStoreFactory.create(migrations = ...)` 构造。迁移内容：
  - `model`：两个旧名 → `deepseek-flash`；
  - `rpAuxiliaryModel`：该设置由所有供应商共用，**只有迁移时 `chat_provider` 为 DeepSeek 才迁移**；当前为自定义供应商时保持原值（中转服务未必认识 `deepseek-flash`，而旧名在 DeepSeek 官方仍被接受）；
  - `deepSeekModelContextWindows`：旧名的手填窗口在 `deepseek-flash` 尚无覆盖时迁移过去，否则丢弃旧键；
  - `deepSeekCachedModelIds`：若缓存含旧名，则清空缓存与刷新时间，回到内置兜底，等待下次真实刷新。不得伪造服务器结果。迁移只执行一次，之后真实刷新得到的列表即使仍含旧名也不再清空。
- 明确不动：
  - `CustomProvider.models`、`selectedModel` 与各自定义供应商的 `modelContextWindows`（它们是用户中转服务的 ID）；
  - `conversation_token_usage.model`（历史事实）与消息内容。
- 已知可见变化：迁移后，按旧模型名记录的「上次实际用量」会因模型不同而显示为「已过期」，属预期行为，写进验收说明。
- 文案：`RpMarkerAvailability` 中「已验证的是 …」一句；`ModelPresentation` 对旧视觉实验名的提示改为「旧名，已由 deepseek-flash 取代」。
- 需同步修改的代码与测试：
  - 主代码：`DeepSeekModel.kt`、`SettingsDataStore.kt`、`RpMarkerAvailability.kt`、`ModelPresentation.kt`、`ContextSections.kt`、`UiStatePreviews.kt`（第 60 行附近引用旧视觉实验名）；
  - 测试：`DeepSeekModelsTest`、`SettingsDataStoreDeepSeekModelTest`（旧名读取后应迁移）、`ModelContextSpecTest`、`PromptComposerTest` 的 Marker 用例、`ModelPresentationTest`、`SettingsRepositoryModelRefreshTest`（第 95～119 行以 `V4_FLASH` 作为已选模型的断言）、`ChatSettingsProviderTest`（第 32、158 行附近）、androidTest `SettingsContextWindowPersistenceTest`；
  - 新增迁移用例：各字段迁移、自定义供应商下不迁移 `rpAuxiliaryModel`、幂等（二次启动不再改动）、迁移后刷新结果含旧名时不被清空。
  - 其余只把旧名当普通字符串 fixture 的测试（如请求序列化、模型列表解析）可以不改。

验收：

- 旧安装 `model = deepseek-v4-flash` 或 `deepseek-v4-flash-vision-exp` 升级后显示并请求 `deepseek-flash`；多次启动不重复迁移。
- 用户选择的 `deepseek-v4-pro` 或未知 ID 不受影响；自定义供应商的模型列表与窗口设置不受影响。
- 旧名的手填上下文窗口被带到新 ID；DeepSeek 下的 RP 辅助模型被迁移，自定义供应商下保持原值。
- 历史用量显示「已过期」属预期。
- 真机：`deepseek-flash` 的普通、流式、思考、工具调用与 RP Marker 正常。

### [ ] MM-1a：数据、请求与规划（无 UI）

改动：3.1 的表、文件存储、`observeAttachments` 与**基本文件清理**（删除会话、清空会话、历史替换删除后续消息）；3.2 domain 与能力解析；3.3 统一历史映射、缺失检查、剥图、体积上限、图片估算与上下文面板 combine；3.4 DTO、流式请求体、`readTimeout` 与错误映射；3.5 `sendMessage` 与 `regenerateLastResponse`。

验收（以单元测试与 instrumentation 为主）：

- 纯文本消息的请求体与改动前逐字节一致（`content` 仍为字符串）。
- 带图消息的请求 JSON 与官方示例形状一致；纯图片消息不含空 text 块。
- 重新生成带图对话时，请求仍包含历史图片。
- 冻结模型不支持图片时：历史图片被占位替换，请求不含 `image_url`；本轮带图则预检失败并写入失败回复，用户消息保留。
- 超过 20 MiB 时最旧图片被省略，本轮图片保留；文件缺失时以占位替换且计数正确；面板数据与实际请求一致。
- 删除会话、清空会话后对应图片文件被删除；提交后抛出取消的场景下不误删已引用文件（可用假 DAO 模拟）。
- 20 MiB 图片请求的内存峰值不随图片总量线性叠加多份副本（instrumentation 中粗测）。

### [ ] MM-1b：发送带图片的 UI

改动：3.6 的输入栏菜单、Photo Picker、导入压缩管线、预览条、发送按钮条件、气泡缩略图、设置页图片开关、上下文面板图片信息、删除 `TEXT_ONLY_INPUT` 文案。

验收：

- `deepseek-flash` 与已开启图片开关的自定义供应商都能发送 1～4 张图片（含纯图片消息），普通与流式均可。
- `deepseek-v4-pro`、未开启开关的自定义模型：图片入口禁用并说明原因。
- 第 5 张图片无法加入。
- 日志、错误提示中找不到 base64、URI 或完整路径。
- 上下文面板显示图片张数与省略原因。

### [ ] MM-2：编辑图片与孤儿清理

改动：3.5 的编辑增删图片与两阶段提交（含查库判断文件去留）；3.6 编辑弹窗；3.1 的启动孤儿扫描与附件 `Mutex`。

验收：

- 编辑历史消息增删图片后重新生成：成功时新旧附件原子切换；预检失败、网络失败或取消时原消息与原附件完全不变。
- 提交后重新抛出取消的场景：新附件文件保留且可读，被移除附件的文件被删除。
- 删除后续消息后没有孤儿附件行。
- 发送或编辑过程中强杀进程，重启后没有残留引用错误，孤儿文件被清理，未提交的新文件不被提前误删。
- 切到不支持图片的模型后继续对话，请求成功且不含图片。

### [ ] MM-3：体验打磨

- 全屏大图查看。
- 从剪贴板粘贴图片。
- 大请求的发送中提示（例如「正在上传 12 MB」）。
- 从其他 App 分享图片到 muxueChat 需要新增 intent-filter，属于入口扩展，实施前另行确认。

## 5. 测试计划

JVM 单元测试：

- `MessageContentDto` 序列化：无图为字符串；有图为数组且 JSON 与官方示例形状一致；`type` 不被省略；纯图片消息无空 text 块。
- 流式请求体：输出与一次性编码的 JSON 逐字节一致（小图片 fixture）。
- 图片能力解析：用户覆盖 → 内置 → 默认不支持；两个旧名判断一致；自定义供应商旧 JSON 缺字段时为关闭。
- 统一历史映射：发送、重新生成、编辑三条路径得到相同的图片引用；纯图片消息不被过滤。
- 规划顺序：缺失检查 → 剥图 → 20 MiB 上限（最旧优先省略、本轮不省略）→ 估算与裁剪，并与 `ContextWindowPlan` 一致；每图 token 数按模型传入。
- 发送预检基于冻结快照：发送途中切换模型不影响本轮。
- MM-0 DataStore 迁移的每个字段、供应商条件与幂等性。
- `HistoryReplacementHistory` 对编辑后图片集合的处理。
- 文件去留判断：用假 DAO 模拟「提交成功但抛出取消」「提交失败」两种情况。
- 压缩目标尺寸计算（抽成纯函数）。

API 36 设备 instrumentation：

- `MIGRATION_10_11` 的 `MigrationTestHelper` 真实升级测试。
- 附件外键级联；`upsertMessage` / `updateMessage` 不删除附件的回归测试。
- 删除、清空、历史替换后的文件清理，以及启动孤儿扫描（含刚移动文件不被误删）。
- `ImageDecoder` 管线：大图、透明 PNG、HEIC、动图 GIF、带旋转 EXIF 的相机照片。
- Repository 发送与编辑的预检失败语义（发送保留用户消息并写失败回复；编辑历史不变）。

手动验收矩阵：

| 场景 | deepseek-flash | deepseek-v4-pro | 自定义 HTTPS（开关开） | 自定义（开关关） |
| --- | --- | --- | --- | --- |
| 发送 1 张 / 4 张 / 纯图片 | 必测 | 入口禁用 | 必测 | 入口禁用 |
| 流式 + 图片 | 必测 | — | 必测 | — |
| 思考模式 + 图片 | 必测（官方未写明） | — | 支持时测 | — |
| 工具调用 + 图片 | 必测（官方未写明） | — | 显式开启时测 | — |
| RP 模式 + 图片 | 必测 | — | 必测 | — |
| 重新生成带图对话 | 必测 | 剥图 | 必测 | 剥图 |
| 长会话大量历史图片 | 必测（20 MiB 提示、超时） | 剥图 | 必测 | 剥图 |
| 编辑消息增删图片 | 必测 | 预检失败 | 必测 | 预检失败 |
| 发送中强杀进程 | 必测 | — | 任一链路覆盖 | — |

真实服务的可用性由用户验收；自动化测试不得包含真实 API Key 或真实用户图片。

## 6. 风险与未决事项

1. **费用与延迟**（D2 的代价）：DeepSeek 每张图最多 1024 tokens，20 张历史图片约等于每轮多 2 万输入 token。字节恒定有利于前缀缓存，但不保证命中。
2. **请求体上限**：20 MiB 是对 D2 唯一的例外，属于物理限制；如需调整数值，实施前再确认。
3. **视觉 + 思考 / 工具**：官方未写明，若真机出现 400，先给出可读错误，不自动关闭用户设置。
4. **旧名停用时间未公布**：因此 MM-0 应尽早实施，与后续多模态开发解耦。
5. **自定义供应商的图片 token 估算**没有官方口径，只能保守估计并标注「预计」。
6. **摘要会丢失图片内容**，长会话压缩后模型只知道「曾经发过图片」。
7. GIF 与动态 WebP 只保留首帧。
8. 编辑消息时保留的原图不重新压缩，始终沿用首次导入的字节。
9. **400 错误无法可靠归因于图片**，提示文案只能留有余地。
10. **`readTimeout` 的合适数值**需要真机测大量图片 + 思考的首字节延迟后确定。
11. **提交后重新抛出取消**会让「异常 ≠ 未提交」，所有文件去留必须查库决定；这是 MM-1a 与 MM-2 中最容易出错的地方，需重点测试。

## 7. 工作量估算

以下为粗略估算（含测试），实施时按实际拆分。审查认为原估算偏低，已上调：

| 分期 | 主要涉及 | 估计 |
| --- | --- | --- |
| MM-0 | `DeepSeekModels`、DataStore 迁移、RP Marker 与模型提示文案、预览、相关测试、文档 | 约 12～14 个文件，300～500 行 |
| MM-1a | Room v11 与导出 schema、文件存储与基本清理、domain、两套 DTO 与约 12 处构造点（另约 7 个测试文件）、流式请求体、统一历史映射、规划器与估算器、上下文面板、Repository 发送与重新生成 | 约 25 个文件，1800～2500 行 |
| MM-1b | Photo Picker 与压缩管线、输入栏与预览、气泡缩略图、设置开关、面板展示 | 约 10 个文件，800～1200 行 |
| MM-2 | 编辑两阶段提交与文件去留、编辑弹窗、启动孤儿扫描与 Mutex、测试 | 约 10 个文件，700～1000 行 |
| MM-3 | 大图查看、粘贴、上传提示 | 约 5 个文件，300～500 行 |
