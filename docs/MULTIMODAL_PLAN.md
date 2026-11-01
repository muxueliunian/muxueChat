# muxueChat 多模态（图片输入）实施计划

状态：MM-0 已实现待真机验收；其余分期尚未实施（2026-09-16 已按独立审查意见修订；2026-11-01 第二次修订，见进度记录）  
建立日期：2026-09-16  
范围：用户消息的图片输入，普通聊天与 RP 正文都支持；覆盖全部聊天链路——DeepSeek、自定义 OpenAI 兼容供应商、Google Vertex（OpenAI 兼容钉死实例）、xAI 订阅（Responses 适配器）；以及作为前置条件的 DeepSeek 模型 ID 迁移  
不在范围：图片生成、音频/视频、PDF 视觉解析、角色卡图片

> 本文中的代码位置来自 2026-09-16 与 2026-11-01 的工作区快照，行号仅供参考。实施时以类名、函数名和调用链为主定位。

## 进度记录（接续工作先读这里）

最后更新：2026-11-01（第二次修订：纳入 xAI 订阅与 Google Vertex；取消模型图片能力判断）

| 分期 | 状态 | 说明 |
| --- | --- | --- |
| 计划审查 | 完成 | 独立审查（Opus）提出的 H1/H2、M1～M8、L1～L7 已全部写入本文 |
| 第二次修订 | 完成 | 2026-11-01：D7（渠道范围）、D8（不判断模型能力，不支持就报错）；新增 1.2、1.3 官方事实与 3.4 xAI 映射 |
| MM-0 | `[~]` 已实现，待真机验收 | 实现记录见第 4 节 MM-0 |
| MM-1a～MM-2 | **进行中（2026-11-01 用户要求一次做完，未提交；工作区当前编译不通过）** | 见下方「2026-11-01 实施中断点」 |
| MM-3 | 未开始 | 分享入口（intent-filter）不做；其余在 MM-2 完成后再做 |

### 2026-11-01 实施中断点（接续先读这里）

已完成并验证（`compileDebugKotlin` 通过，`testDebugUnitTest --tests "com.muxuechat.data.remote.*"` 全过）：

- 数据层：`MessageAttachmentEntity`（`ChatEntities.kt`）、Room v11 + `MIGRATION_10_11`（SQL 已与导出的 `11.json` 逐字核对一致）、
  `ChatDao` 附件查询 / `insertUserMessageWithAttachments` / `commitHistoryReplacement` 新增 `expectedAttachments`、`removedAttachmentIds`、`addedAttachments`；
  `data/local/ChatImageStore.kt`（待发送区、原子写、promote 并刷新 mtime、`commitLock`、`removeOrphans`）、`data/local/ChatImageImporter.kt`（ImageDecoder → JPEG 85、白底、只记类型与尺寸）。
- domain：`domain/model/MessageAttachment.kt`（`MessageAttachment`、`PendingImage`、`MessageImageEdit`、`ImageInputSpec`）、`ChatMessage.attachments`。
- 请求层：`data/remote/ImagePayload.kt`（`ImagePayload` 占位符、`MessageContentJson`、`ImagePayloadRequestBody` 精确长度流式 base64、`withImageReadTimeout` 180s、`HttpStatusError` + `httpStatusOrNull`）；
  两个消息 DTO 保持 `content: String?`，新增 `images`，用 Wire 代理序列化器，**纯文本请求字节不变**；三个客户端已接入请求体与图片读超时；
  xAI `toInputItems` 产出 `input_image`（`image_url` 为字符串）；三家异常带 `httpStatus`。
- 已改但未编译验证：`PromptMessage.images` / `PromptImage` / `textWithImageNote()`（`PromptComposer.kt`，纯图片消息追加状态段不留空行）；
  `HeuristicTokenEstimator.estimateMessage(message, tokensPerImage)`；`domain/context/ImagePayloadBudget.kt`（`ImageTokenEstimate`：DeepSeek 1024 / OpenAI 兼容 1600；20 MiB 上限，本轮不省略）；
  `ChatContextPlanner`（裁剪/压缩都按图片计费、保留窗口后应用体积上限、`ContextWindowPlan.imageCount/omittedImageCount/missingImageCount`、`ChatContextInput.missingImageCount`）；
  `ContextSummaryPrompt` 用占位文字；`HistoryReplacementHistory` 成为唯一映射 `toPromptHistory(...)`，返回 `MappedHistory`（测试已改 `.messages`）；
  `RpHistoryMutation.lastCompletedUserContent*` 接受图片计数；`ChatRepository` / 两个 UseCase 新签名（`images`、`imageEdit`）。
- `ChatRepositoryImpl` 已改：import、构造参数 `imageStore`（测试默认临时目录）、`HistoryReplacementPlan` 新字段、`observeMessages` 合并附件（含编辑遮罩）、
  `sendMessage` + `persistUserMessage` + `deleteFilesOfRemovedAttachments`、`regenerateLastResponse`（统一映射、`expectedAttachments`）。

`ChatRepositoryImpl` 尚未改（这是编译不通过的原因）：

1. `editUserMessageAndRegenerate(messageId, content, imageEdit)`：按设计拆成外层 flow（finally 里 discardPending）+ `FlowCollector` 扩展内部函数；
   冻结后注册 session，promote 新图（`commitLock` 内），`throughEditedUser` 传入替换后的附件映射，计划带 `expectedAttachments`、`editedAttachments`（imageEdit 非空时）、
   `removedAttachmentIds`、`addedAttachments`；finally 里 `deleteFilesOfRemovedAttachments(已 promote 的新行 + 被移除行 + 后续消息上的附件)`。
2. `deleteConversation` / `clearConversation`：先 `getAttachments`，try 删除，finally `deleteFilesOfRemovedAttachments`。
3. 私有 `loadPromptHistory` / `List<MessageEntity>.toPromptHistory` 改为调用 `HistoryReplacementHistory.toPromptHistory`（读 `getAttachments`，`isAvailable` 查文件），返回 `MappedHistory`；
   需新增 `isAttachmentFileAvailable(entity) = imageStore.exists(entity.relativePath)`。调用点：`planRequestContext`（`replacementPlan.promptHistory`）、`autoCompactIfNeeded`、`runCompaction`、`planContextWindowForRpDraft`、`planContextWindow`。
4. `observeContextWindow`：把 `observeAttachments` 并入（5 参 combine 已满，需嵌套），`ContextWindowSignals` 加附件，`missingImageCount` 传入 `buildContextInput`（新增参数）。
5. `toDeepSeekMessages` / `toOpenAiCompatibleMessages`：`images` 映射为 `ImagePayload(imageStore.fileOf(imageStore.relativePathFor(id)), mimeType)`。
6. 两个 generate 的 catch：记录本轮 `contextPlan.imageCount > 0`，错误文案按 `error.httpStatusOrNull()` 为 400 / 413 时追加图片提示（3.4）。
7. `commitHistoryReplacement` 调用处传 `expectedAttachments` / `removedAttachmentIds` / `addedAttachments`。
8. `captureRpTurnSnapshot` 的 `lastUser` 改用 `RpHistoryMutation.lastCompletedUserContent*` + 按消息的图片计数；`renameConversationWithAi` 的标题行可带图片占位；新增常量 `IMAGE_ONLY_TITLE = "图片"`。

之后的步骤：跑 `testDebugUnitTest` 修测试 → ViewModel（注入 `ChatImageImporter`、`pendingImages`、发送/编辑传图）与 UI（附件菜单、Photo Picker、预览条、气泡缩略图、编辑弹窗、上下文面板图片信息、删除 `TEXT_ONLY_INPUT` 及 `ModelPresentationTest`）
→ Factory 传 `ChatImageStore(application.filesDir)` → 启动孤儿扫描（进程一次、DB 打开后、持 `commitLock`；`removeOrphans` 还需加「未引用文件 1 小时宽限期」，因为编辑时新图在提交前就已移入正式目录）
→ 补测试（序列化、xAI、流式请求体逐字节、预算、映射、MigrationTestHelper 10→11 只编译不在真机跑）→ 同步 AGENT.md 第 78、479 行附近与 ROADMAP。
**不要运行 `connectedDebugAndroidTest`（会卸载应用清数据）。** Bash 里长 heredoc 曾解析失败，改用把 Python 脚本写到 scratchpad 再执行。

当前工作区状态：

- MM-0、MM-0 之前的缺陷修复与 2026-09-20 的前端 UI/UX 优化**已提交**，见 `6705af1`（2026-09-22「建立基线」）；
  本计划文档首次提交于 `291f596`。代码中尚无任何图片发送逻辑（2026-11-01 核对：三套请求 DTO 的 `content` 仍为字符串，
  Room 仍为 v10，`ModelNotes.TEXT_ONLY_INPUT` 仍在使用）。
- 自本计划首版以来新增的链路：xAI 订阅（`XaiResponsesTransport`，`ProviderAuthKind.XAI_OAUTH`）与
  Google Vertex（`OpenAiCompatibleClient` 钉死主机实例，`ProviderAuthKind.GOOGLE_OAUTH`），以及
  「RP 辅助请求固定走 DeepSeek」。本次修订已把它们纳入。
- 最近一次与 MM-0 相关的验证（2026-09-20）：`testDebugUnitTest`、`assembleDebug`、
  `compileDebugAndroidTestKotlin` 均成功；MM-0 的 androidTest 尚未在真机执行。

待用户处理：

1. MM-0 真机验收：旧版选中 `deepseek-v4-flash` 后升级，确认显示并请求 `deepseek-flash`；
   在 `deepseek-flash` 上验证普通、流式、思考、工具调用与 RP Marker。
2. D6（允许纯图片消息）是按审查建议采纳的默认值，用户尚未明确表态；如需改为「必须带文字」，在 MM-1a 开工前告知。

MM-1a 开工时的要点（详见 3.1～3.5）：

- 先做统一历史映射，替换 `HistoryReplacementHistory` 私有的 `toPromptMessages`，否则重新生成会丢图；
- 文件去留一律查库决定（提交后可能重新抛出取消）；
- 规划顺序：缺失检查 → 20 MiB 上限 → 估算与裁剪（D8 后不再有「剥图」步骤）；
- 不做任何模型图片能力判断，不加设置开关；上游拒绝就按普通错误显示（D8）；
- 三条传输（DeepSeek、OpenAI 兼容含 Vertex、xAI 适配器）共用同一个「占位符 + 流式 base64」请求体写出器（3.4）；
- xAI 适配器把 `image_url` 块翻译成 `input_image`，翻译封在适配器内部；
- Room **v11** 需导出 `11.json`，并补 `MigrationTestHelper` 10→11 测试（v10 已被 RP-1 占用）。

## 0. 已确认的产品决策

| # | 决策 | 确认日期 |
| --- | --- | --- |
| D1 | 已保存的旧 DeepSeek 模型 ID（`deepseek-v4-flash`、`deepseek-v4-flash-vision-exp`）**自动迁移**到 `deepseek-flash`。 | 2026-09-16 |
| D2 | 历史中的图片**每轮都重发**（仍受上下文预算与请求体安全上限约束，见 3.3）。 | 2026-09-16 |
| D3 | 本地压缩规格：长边不超过 **1568px**，JPEG 质量 **85**；每条用户消息最多 **4 张**。 | 2026-09-16 |
| D4 | 编辑历史用户消息时**允许增删图片**。 | 2026-09-16 |
| D5 | 先落计划，按第 4 节分期实施；每期开工前由用户确认。 | 2026-09-16 |
| D6 | **允许只发图片、不写文字**（按审查建议采纳；如用户改变主意，只需恢复 3.5 中的两处非空校验）。 | 默认值，待确认 |
| D7 | 渠道范围：**所有聊天链路都支持发图片**——DeepSeek、自定义 OpenAI 兼容供应商、Google Vertex、xAI 订阅；普通聊天与 RP 正文都支持。 | 2026-11-01 |
| D8 | **不判断模型是否支持图片**：不维护内置能力表，不加逐模型开关，不剥除历史图片，不禁用图片入口。图片一律照发，上游拒绝时按普通请求错误显示。用户已知并接受：发过图的会话切到不支持图片的模型（如 `deepseek-v4-pro`）后，因 D2 历史图片每轮重发，**之后每一轮都会报错**，切回支持图片的模型或新开会话即可恢复。 | 2026-11-01 |

D8 取代了首版计划中的以下内容（已从正文删除）：`ModelInputCapabilities`、`deepSeekModelImageInput`、
`CustomProvider.modelImageInput`、剥图占位、发送/编辑的图片能力预检、图片入口禁用与设置页图片开关。

## 1. 官方事实

### 1.1 DeepSeek（2026-09-16 核查）

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
- OpenAI 兼容上游使用同样形状的 `image_url` content part，但是否支持图片取决于具体服务。按 D8，App 不判断，交给上游报错。

### 1.2 xAI（2026-11-01 核查）

来源：[xAI Image Understanding](https://docs.x.ai/docs/guides/image-understanding)（公开 `api.x.ai` 的 Responses API 文档）。

- 用户消息 `content` 为数组，图片块：`{"type": "input_image", "image_url": "data:image/jpeg;base64,..."}`。
  注意 `image_url` 是**字符串**，不是 OpenAI 的 `{"url": …}` 对象；文本块为 `{"type": "input_text", "text": …}`。
- 文档示例带 `"detail": "high"`，但未说明取值与默认值。本 App 不发送 `detail`；不发时是否正常需真机验收。
- 支持格式：**仅 JPEG 与 PNG**；单图上限 **20 MiB**；未写明张数上限；图片与文字顺序任意。
- 文档提醒发送图片时**不要让服务端保存请求/响应历史**，否则请求可能失败。现有适配器已固定发送 `store: false`，满足要求。
- 未写明每张图的 token 计费。
- **未采集**：订阅路径 `cli-chat-proxy.grok.com` 是否接受 `input_image`。`docs/XAI_WIRE_FORMAT.md` 的两轮抓包都只有纯文本；
  公开路径与订阅路径的请求体此前被证实一致（线上格式 §0、§2），据此推断可用，但必须真机验收。

### 1.3 Google Vertex（2026-11-01 核查）

来源：[Vertex AI OpenAI compatibility overview](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/migrate/openai/overview)、
[Image understanding](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/multimodal/image-understanding)。

- OpenAI 兼容端点的 `image_url` 支持 Cloud Storage URI 与 base64，写法 `data:<MIME-TYPE>;base64,<BASE64-ENCODED-BYTES>`，
  与 DeepSeek / OpenAI 的形状一致，**App 侧无需任何特殊代码**。
- `detail` 每个请求只能用一种取值，混用报错。本 App 不发送 `detail`，不受影响。
- 多数 Gemini 模型：每个 prompt 最多 3000 张；**inline 数据每个文件最大 7 MB**；支持 PNG、JPEG、WebP、HEIC、HEIF。
  D3 压缩后的单张约 200～500 KB，远低于 7 MB。
- **未写明**单个请求 inline 数据的总上限，20 MiB 安全上限（3.3）是否足够宽松需真机验收。
- 计费：Gemini 3 默认每张图 1120 tokens（3 之前的模型为 258）。

## 2. 不得突破的边界

- 上层仍只有「DeepSeek 专属」与「OpenAI 兼容」两条分支（`AGENT.md` §2）。图片块在上层统一用标准 `image_url` 形状；
  Vertex 走 OpenAI 兼容同一套 DTO，不另写代码；xAI 订阅链路的 `input_image` 翻译**只在 Responses 适配器内部**完成，
  上层不可见。不使用 DeepSeek Files API，不发送 `detail` 等可选字段，不按厂商名称、地址或认证类型写图片特判。
- **不判断模型是否支持图片**（D8）：不按模型名猜测，不维护能力表，不加用户开关。
- 不新增第三方依赖：选图用系统 Photo Picker（`ActivityResultContracts.PickVisualMedia` / `PickMultipleVisualMedia`，无需权限），解码与压缩用 `ImageDecoder` + `Bitmap.compress`，缩略图自行降采样解码，流式请求体用 OkHttp 自带的 okio，不引入 Coil 等图片库。若后续确有需要，另行说明用途与替代方案并取得确认。
- `minSdk` / `targetSdk` / `compileSdk` 保持 36。
- 图片内容、base64、原始 `content://` URI、完整文件路径**绝不进入日志**、错误提示或测试快照；日志只允许张数、字节数、宽高。
- 图片只保存在 App 私有 `filesDir`。现有 `data_extraction_rules.xml` 已排除 `file` 域，不参与云备份与设备迁移；不写入系统相册。
- 本地重新编码会丢弃 EXIF（含 GPS 定位），这是期望行为，不得为保留元数据而上传原图字节。
- 压缩输出固定为 JPEG：这是三方（DeepSeek、xAI、Vertex）支持格式的交集，xAI 只接受 JPEG/PNG。
- 以下内部请求一律不发图片，只发文字占位：RP 剧情推进建议、RP 元数据补写、RP 状态兜底、上下文摘要、AI 标题、设定生成工具。
  RP 辅助请求固定走 DeepSeek 时同样只发占位。

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
- 不新增任何模型图片能力类型或设置字段（D8）。
- 常量集中定义：`MAX_IMAGES_PER_MESSAGE = 4`、`MAX_LONG_EDGE_PX = 1568`、`JPEG_QUALITY = 85`、`MAX_IMAGE_PAYLOAD_BYTES = 20 MiB`。
- 每图 token 估算按**协议**取常量（上层只认两条协议，不得按 Vertex/xAI 区分）：DeepSeek 协议 1024（官方单图上限）；
  OpenAI 兼容协议（含 Vertex、xAI）1600（无统一官方口径，取保守值；Vertex Gemini 3 默认 1120、xAI 未公布，均不超过此值）。

### 3.3 Prompt 与上下文规划

继续遵守「一次请求的最终消息、UI 占用和裁剪判据都来自同一个 `ContextWindowPlan`」。

**统一历史映射（修正：重新生成与编辑原先会静默丢图）**

- 现状：正常发送走 `ChatRepositoryImpl.toPromptHistory`；重新生成（`HistoryReplacementHistory.beforeAssistant`）与编辑（`throughEditedUser`）走 `HistoryReplacementHistory` 自己的私有 `toPromptMessages`。后者拿不到附件，若不改，重新生成时历史图片会**静默丢失**。
- 改为只保留**一个**纯映射函数，签名形如 `List<MessageEntity>.toPromptHistory(attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>>, excludeMessageId)`，发送、重新生成、编辑、上下文面板四处共用。`HistoryReplacementHistory` 只负责截取与替换消息列表（编辑时同时替换该消息的附件集合），再调用这个函数。
- 过滤条件由 `content.isNotBlank()` 改为「**文字非空或带有图片**」（D6）。
- COMPLETED 的 user 消息带上图片引用。RP 状态段和 RP Marker 仍只追加到文本部分。普通聊天与 RP 正文共用这一映射，无需分别实现。

**规划顺序（固定，写进代码注释与测试）**

1. **缺失文件检查**：映射前确认每个附件文件存在。缺失的图片在此处替换为占位 `[图片文件已丢失]`，并计入 `missingImageCount`，UI 给出提示。不得留到请求层再悄悄替换，否则估算与实际请求不一致。
2. **请求体上限**（D2 的唯一例外）：按 `PromptImageRef.byteSize` 换算 base64 大小（×4/3 向上取整），总量超过 `MAX_IMAGE_PAYLOAD_BYTES` 时从最旧的消息开始把图片换成占位 `[更早的图片因请求体积上限已省略]`；**本轮用户消息的图片永不省略**。按 1568px JPEG 单张约 200～500 KB 估算，20 MiB 约可容纳 30～75 张历史图片。
3. **token 估算与 `findKeepFromIndex` 裁剪**：`HeuristicTokenEstimator` 新增图片估算，每张图的 token 数由规划器按协议传入（3.2），估算器本身不判断模型。一律标注「预计」。图片 token 计入对应消息所在分项。

- 首版的「剥图」步骤按 D8 删除：无论当前模型是什么，历史图片都照发。
- `ContextWindowPlan` 额外暴露 `imageCount`、`omittedImageCount`（只有「体积上限」一种原因）与 `missingImageCount`。
- `toEstimationMessages`（`ChatRepositoryImpl` 中两处）目前使用 `content.orEmpty()`，DTO 类型变更后需同步改为「取文本部分 + 图片计数」。
- 已被摘要覆盖的消息本来就不发送，其图片也不再发送；这与 D2 不冲突。
- 摘要请求只看到文字占位 `[用户发送了 N 张图片]`，因此摘要会丢失图片内容，这是已知限制。

**上下文面板**

- `observeContextWindow` 当前只 `combine` 消息流与设置等。必须把 `observeAttachments(conversationId)` 加入 combine，并使用上面的同一个映射函数，否则面板占用与实际请求不一致。

### 3.4 请求层

**DTO（DeepSeek 与 OpenAI 兼容，Vertex 自动包含在后者中）**

- `DeepSeekMessageDto.content` 与 `OpenAiCompatibleMessageDto.content` 从 `String?` 改为 `MessageContentDto?`：
  - `Text(String)` 序列化为 JSON 字符串；
  - `Parts(List<ContentPartDto>)` 序列化为数组，包含 `{"type":"text","text":...}` 与 `{"type":"image_url","image_url":{"url":...}}`；纯图片消息（D6）不生成空的 text 块；
  - `type` 字段与现有 `tools` 同理，用 `@EncodeDefault(ALWAYS)` 强制序列化；
  - 用自定义 `KSerializer` 实现；响应 DTO 不变。
- **没有图片的消息继续发字符串**，只支持纯文本的旧反代不受影响。
- 构造点：`ChatRepositoryImpl` 中实际约 **12 处**直接构造两种消息 DTO，另有 `ContextSummaryPrompt` 等主代码构造点，以及约 **7 个测试文件**直接构造这些 DTO。为减少改动，**新增**（当前并不存在）`text(role, content)` 形式的伴生工厂函数，统一替换这些构造点。实施时重新统计，xAI 与 Vertex 接入后数量可能有变。
- Vertex 使用 `OpenAiCompatibleClient` 的钉死主机实例，请求 DTO 与默认实例相同，**不需要任何图片相关的额外代码**。

**xAI 订阅适配器（`XaiResponsesTransport`）**

- `toInputItems` 目前对 user 消息只产出 `[{"type":"input_text","text":…}]`。改为遍历 `MessageContentDto`：
  - `Text` → 保持现状（一个 `input_text` 块），纯文本请求字节不变；
  - `Parts` → 按顺序产出 `input_text` 与 `{"type":"input_image","image_url":"<data URL 字符串>"}`；不发 `detail`。
- 新增 `XaiInputImagePart` DTO（`type` 同样 `@EncodeDefault(ALWAYS)`）。
- system/assistant/工具结果不会带图（3.3 只给 user 消息挂图片引用），适配器遇到时按协议错误处理即可，不做静默丢弃。
- 翻译完全封在适配器内部，符合 `AGENT.md` §2 对 xAI 例外的约束；上层不知道这条链路的图片字段名不同。

**流式请求体（三条传输共用）**

- 20 MiB 图片的 base64 若先拼成 `String` 再编码为请求体，峰值内存会有多份副本（原始字节、base64 字符串、JSON 字符串、UTF-8 字节），可能超过 100 MB。
- 做法：映射阶段图片的 URL 字段不放真实数据，而是放**每次请求随机生成**的占位符（例如 `@@muxue-img-<请求 UUID>-<序号>@@`）。照常把请求序列化成 JSON 字符串——此时它只有文字，体积很小。
  自定义 `ImagePayloadRequestBody.writeTo(sink)` 按占位符切分该字符串，文字段原样写出，占位符处写 `data:image/jpeg;base64,` 后边读文件边 base64 编码写入 `sink`。
  base64 字符集不含需要 JSON 转义的字符，因此直接写入合法。`contentLength()` 返回 -1（分块传输）。
- 这个写出器只依赖「JSON 字符串 + 占位符到文件的映射」，不关心协议，因此 DeepSeek、OpenAI 兼容（含 Vertex）与 xAI 适配器共用。
  xAI 的 `image_url` 是字符串、OpenAI 的在 `{"url": …}` 里，但占位符都落在 JSON 字符串值内部，写出方式相同。
- 占位符随请求随机生成，用户正文不可能事先包含它；仍在写出前断言每个占位符恰好出现一次。
- 纯文本请求保持现状，不走这个请求体。
- base64 读取：新增 `ImageAttachmentStore`（data.local），只负责按 `relativePath` 打开文件流；读取发生在 OkHttp 写请求体时（IO 线程）。请求层只做映射，不得二次裁剪或替换图片。

**超时**

- OkHttp 的 `writeTimeout` 限制的是**单次写操作之间的间隔**，不是整个上传的总时长；上传慢但持续有进度不会触发它。现有 30 秒可保留。
- 真正的风险是 `readTimeout = 60s`：服务端要先接收并处理大量图片（可能还有思考），首个字节可能晚于 60 秒到达。含图片的请求用 `client.newBuilder().readTimeout(...)` 单独放宽（例如 180 秒），共享连接池，不影响纯文本请求。
  DeepSeek 客户端、`OpenAiCompatibleClient` 的两个实例、xAI 适配器各有自己的 `OkHttpClient`，都要同样处理。具体数值在真机验收后确定。

**错误映射**（沿用 `DeepSeekErrorSanitizer` 等不回显正文的原则）

- 413：提示「请求体过大，请减少图片」。
- 400：无法可靠判断是否与图片有关。只在**本次请求含图片**时追加一句留有余地的提示：
  「如果问题与图片有关，可能是当前模型或服务不接受图片输入，可切换到支持图片的模型或新开会话」。不断言原因。
  这是 D8 下唯一的「不支持图片」处理，判断依据只有「请求是否含图片」，不识别厂商、模型或异常类型。
- 「请求是否含图片」由请求层通过通用标记传给错误映射，三条传输一致；xAI 适配器的 `httpError` 与 Vertex 的数组错误体解析都要接上。

### 3.5 Repository 流程

**`sendMessage(conversationId, content, images)`**

- 非空校验：现有 `require(trimmed.isNotEmpty())` 改为「文字非空**或**带图片」（D6）。
- 保持现有语义不变：**先写入用户消息，再冻结请求配置**（`freezeProviderRequest`）；冻结失败时仍走 `persistSendPreflightFailure`，写入失败的 assistant 回复并在 UI 显示。
- **不做图片能力预检**（D8）：模型不接受图片时，上游返回错误，走正常的请求失败路径，用户消息与图片保留。
- 写入顺序：在同一把附件 `Mutex` 内，先把 pending 文件移入正式目录并 `setLastModified(now)`，再在同一 Room 事务中插入 user 消息与附件行。事务出现异常后按 3.1 的规则**查库确认**后再决定是否删除已移动的文件。

**`regenerateLastResponse`**

- 修正：图片**不会**自动随历史带上。必须改用 3.3 的统一映射函数，并在调用前按会话读取附件映射。
- 验收中单列「重新生成带图对话，请求仍含历史图片」。

**`editUserMessageAndRegenerate(messageId, content, imageEdit)`**

其中 `imageEdit` 描述保留哪些原附件、新增哪些 pending 图片。

- 遵守 FIX-02 语义：配置预检失败时历史完全不变。上游因不支持图片而报错时同样视为生成失败，历史不变（D8）。
- `HistoryReplacementHistory.throughEditedUser` 使用编辑后的文本与图片集合，再走统一映射函数规划请求。
- `ChatDao.commitHistoryReplacement` 增加「新增附件行」与「删除附件 id」参数，在同一事务中切换；提交前比对原消息的附件快照，历史在生成期间变化时拒绝提交。
- 文件处理：新图片文件在提交前移入正式目录，但只有提交后才被引用。无论出现何种异常（含提交后重新抛出的取消），都**先查库**：
  - 新附件行不存在 → 删除这些新文件；已存在 → 保留；
  - 被移除的附件行已不存在 → 删除其文件；仍存在 → 保留。
- 启动孤儿扫描作为最后兜底。

**RP**

- RP 正文请求与普通聊天走同一条发送/重新生成/编辑流程，图片随用户消息发送，四条链路都适用。
- RP 补写等辅助请求：`RpTurnSnapshot.lastUser` 为文本 + `[用户附带了 N 张图片]`，不发图片；辅助请求无论跟随正文还是固定走 DeepSeek 都一样。

### 3.6 ViewModel 与 UI

- `ChatUiState` 增加 `pendingImages` 与 `isImportingImages`。现有文本文档附件（`PendingAttachment`）保留，二者可以同时存在。
- 发送按钮的可用条件改为「文字非空、或有待发送图片、或有文本文档附件」（D6）。
- 输入栏附件按钮改为菜单：「图片」「文本文档」。普通聊天与 RP 共用同一输入栏。
  - 剩余名额 ≥ 2 时用 `PickMultipleVisualMedia(maxItems)`，剩余 1 张时用 `PickVisualMedia`（前者要求 maxItems ≥ 2）。
  - 导入在后台线程进行：`ImageDecoder` 按目标尺寸降采样解码；带透明通道的 PNG/WebP 先铺白底再转 JPEG；GIF 与动图只取首帧；HEIC 由系统解码后转 JPEG。EXIF 方向处理需用真机照片验证。
  - 解码失败、源文件过大时给出可读提示，不改变已有待发送图片。
- 图片入口**始终可用**，不按当前模型禁用，也不显示能力说明（D8）。
- 预览条：缩略图、删除按钮、「2/4」计数。
- 消息气泡：user 消息显示缩略图网格（降采样解码 + 小容量内存 LruCache）；纯图片消息不显示空文本块。RP 的玩家消息气泡同样显示。
- 编辑消息弹窗（MM-2）：显示原图片，可删除，可继续添加（受 4 张上限约束）；删光文字与图片时禁止提交。
- 设置页：只删除现有 `ModelNotes.TEXT_ONLY_INPUT`（`ModelPresentation.kt`、`ContextSections.kt`）这句「尚未支持图片」的文案；**不新增**图片开关。
- 上下文面板：显示本轮图片张数、因体积上限省略的张数、丢失张数。
- 无障碍：缩略图提供 `contentDescription`，触控目标不小于 44dp。

### 3.7 规范文档同步（随实施提交）

- `AGENT.md`：
  - §2、§3.3 的 DeepSeek 模型清单；
  - 两条协议的 content 数组规则（无图时保持字符串），以及 xAI 适配器内部的 `input_image` 翻译；
  - 图片不进日志与存储位置规则；
  - 不判断模型图片能力（D8），删除 `TEXT_ONLY_INPUT` 相关条款（当前第 479 行附近）与「图片输入尚未实现」（第 78 行附近）；
  - 内部请求不发图片；
  - 「是否已提交以数据库状态为准」的文件清理规则。
- `docs/ROADMAP.md` 当前状态；`docs/DEEPSEEK_MODEL_DISCOVERY_RESEARCH_2026-09-05.md` 顶部状态段；
  `docs/XAI_WIRE_FORMAT.md` 补记订阅路径图片请求的真机结果。

## 4. 分期任务

状态标记沿用 `docs/DEFECT_FIX_TASKS.md`：`[ ]` 未实施、`[~]` 已实现待设备或真实服务验收、`[x]` 完成。

### [~] MM-0：DeepSeek 模型 ID 迁移（前置，小）

> 2026-09-16 实现记录：
> - 新增 `data/local/DeepSeekModelIdMigration.kt`，经 `preferencesDataStore(produceMigrations = ...)` 注册；
> - `DeepSeekModels` 新增 `FLASH`、`LEGACY_FLASH_IDS`、`isLegacyFlashId`，默认与兜底改为新 ID；
> - `ModelNotes` 新增 `LEGACY_FLASH_ID` 与 `deepSeekCapabilityNotes`（设置页与模型面板共用）。在 MM-1b 前，
>   `deepseek-flash` 显示「仅支持文本输入」，旧名显示「已由 deepseek-flash 取代」；
> - `:app:testDebugUnitTest` 294 个用例通过（新增 `DeepSeekModelIdMigrationTest` 9 个），`:app:compileDebugAndroidTestKotlin` 成功；
>   新增的 androidTest `legacyDeepSeekModelIds_areMigratedBeforeFirstRead` 尚未在真机执行；
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

改动：3.1 的表、文件存储、`observeAttachments` 与**基本文件清理**（删除会话、清空会话、历史替换删除后续消息）；3.2 domain；3.3 统一历史映射、缺失检查、体积上限、图片估算与上下文面板 combine；3.4 两套 DTO、xAI 适配器的 `input_image` 翻译、共用的流式请求体、各客户端 `readTimeout` 与错误映射；3.5 `sendMessage` 与 `regenerateLastResponse`。

验收（以单元测试与 instrumentation 为主）：

- 纯文本消息的请求体与改动前逐字节一致：DeepSeek、OpenAI 兼容（含 Vertex 实例）的 `content` 仍为字符串；xAI 的 `input` 仍为单个 `input_text`。
- 带图消息的请求 JSON 与官方示例形状一致（DeepSeek / OpenAI 兼容为 `image_url.url`，xAI 为 `input_image.image_url` 字符串）；纯图片消息不含空 text 块。
- 流式请求体的输出与一次性编码的 JSON 逐字节一致，三条传输各测一次。
- 重新生成带图对话时，请求仍包含历史图片。
- 不论当前模型是什么，请求都带上历史图片；上游返回 400 时写入失败回复，用户消息与图片保留，提示文案含「可能与图片有关」一句。
- 超过 20 MiB 时最旧图片被省略，本轮图片保留；文件缺失时以占位替换且计数正确；面板数据与实际请求一致。
- 删除会话、清空会话后对应图片文件被删除；提交后抛出取消的场景下不误删已引用文件（可用假 DAO 模拟）。
- 20 MiB 图片请求的内存峰值不随图片总量线性叠加多份副本（instrumentation 中粗测）。

### [ ] MM-1b：发送带图片的 UI

改动：3.6 的输入栏菜单、Photo Picker、导入压缩管线、预览条、发送按钮条件、气泡缩略图（含 RP 玩家消息）、上下文面板图片信息、删除 `TEXT_ONLY_INPUT` 文案。

验收：

- 第 5 节手动矩阵中的「发送」行：四条链路都能发送 1～4 张图片（含纯图片消息），普通与流式、普通聊天与 RP 均可。
- 不支持图片的模型（如 `deepseek-v4-pro`）：图片入口照常可用，发送后显示上游错误与「可能与图片有关」提示，不崩溃、不丢消息。
- 第 5 张图片无法加入。
- 日志、错误提示中找不到 base64、URI 或完整路径。
- 上下文面板显示图片张数与省略情况。

### [ ] MM-2：编辑图片与孤儿清理

改动：3.5 的编辑增删图片与两阶段提交（含查库判断文件去留）；3.6 编辑弹窗；3.1 的启动孤儿扫描与附件 `Mutex`。

验收：

- 编辑历史消息增删图片后重新生成：成功时新旧附件原子切换；预检失败、网络失败、上游拒绝图片或取消时原消息与原附件完全不变。
- 提交后重新抛出取消的场景：新附件文件保留且可读，被移除附件的文件被删除。
- 删除后续消息后没有孤儿附件行。
- 发送或编辑过程中强杀进程，重启后没有残留引用错误，孤儿文件被清理，未提交的新文件不被提前误删。

### [ ] MM-3：体验打磨

- 全屏大图查看。
- 从剪贴板粘贴图片。
- 大请求的发送中提示（例如「正在上传 12 MB」）。
- 从其他 App 分享图片到 muxueChat 需要新增 intent-filter，属于入口扩展，实施前另行确认。

## 5. 测试计划

JVM 单元测试：

- `MessageContentDto` 序列化：无图为字符串；有图为数组且 JSON 与官方示例形状一致；`type` 不被省略；纯图片消息无空 text 块。
- xAI 适配器：`Text` 内容的 `input` 与改动前逐字节一致；`Parts` 产出 `input_text` / `input_image`，顺序保持，`image_url` 为字符串，无 `detail`。
- 流式请求体：三条传输各自的输出与一次性编码的 JSON 逐字节一致（小图片 fixture）；占位符缺失或重复时断言失败。
- 统一历史映射：发送、重新生成、编辑三条路径得到相同的图片引用；纯图片消息不被过滤；RP 正文与普通聊天一致。
- 规划顺序：缺失检查 → 20 MiB 上限（最旧优先省略、本轮不省略）→ 估算与裁剪，并与 `ContextWindowPlan` 一致；每图 token 数按协议传入。
- 错误映射：含图片的 400 追加提示、不含图片的 400 不追加；413 文案；三条传输一致。
- MM-0 DataStore 迁移的每个字段、供应商条件与幂等性。
- `HistoryReplacementHistory` 对编辑后图片集合的处理。
- 文件去留判断：用假 DAO 模拟「提交成功但抛出取消」「提交失败」两种情况。
- 压缩目标尺寸计算（抽成纯函数）。
- 内部请求（RP 辅助含固定 DeepSeek 路由、摘要、标题、设定生成）只含文字占位，不含图片块。

API 36 设备 instrumentation：

- `MIGRATION_10_11` 的 `MigrationTestHelper` 真实升级测试。
- 附件外键级联；`upsertMessage` / `updateMessage` 不删除附件的回归测试。
- 删除、清空、历史替换后的文件清理，以及启动孤儿扫描（含刚移动文件不被误删）。
- `ImageDecoder` 管线：大图、透明 PNG、HEIC、动图 GIF、带旋转 EXIF 的相机照片。
- Repository 发送与编辑的失败语义（发送保留用户消息并写失败回复；编辑历史不变）。

手动验收矩阵：

| 场景 | deepseek-flash | deepseek-v4-pro（不支持） | 自定义 OpenAI 兼容 | Google Vertex（Gemini） | xAI 订阅（Grok） |
| --- | --- | --- | --- | --- | --- |
| 发送 1 张 / 4 张 / 纯图片 | 必测 | 报错可读 | 必测（视服务） | 必测 | 必测（订阅路径未采集） |
| 流式 + 图片 | 必测 | — | 必测 | 必测 | 必测 |
| 思考模式 + 图片 | 必测（官方未写明） | — | 支持时测 | 必测 | 必测 |
| 工具调用 + 图片 | 必测（官方未写明） | — | 显式开启时测 | 显式开启时测 | 显式开启时测 |
| RP 模式 + 图片 | 必测 | — | 必测 | 必测 | 必测 |
| 重新生成带图对话 | 必测 | 报错可读 | 必测 | 必测 | 必测 |
| 长会话大量历史图片 | 必测（20 MiB、超时） | — | 必测 | 必测（请求总量上限未写明） | 必测 |
| 带图会话切到不支持图片的模型 | — | 每轮报错，切回可恢复（D8 预期） | — | — | — |
| 编辑消息增删图片 | 必测 | 报错且历史不变 | 必测 | 必测 | 必测 |
| 发送中强杀进程 | 必测 | — | 任一链路覆盖 | — | — |

真实服务的可用性由用户验收；自动化测试不得包含真实 API Key、OAuth token 或真实用户图片。

## 6. 风险与未决事项

1. **费用与延迟**（D2 的代价）：DeepSeek 每张图最多 1024 tokens，Vertex Gemini 3 默认 1120，20 张历史图片约等于每轮多 2 万输入 token。字节恒定有利于前缀缓存，但不保证命中。Vertex 按用户自己的 GCP 项目计费，xAI 消耗订阅配额。
2. **请求体上限**：20 MiB 是对 D2 唯一的例外，属于物理限制；Vertex 未写明单请求 inline 总量上限，如真机触发再调整，调整前再确认。
3. **视觉 + 思考 / 工具**：官方均未写明，若真机出现 400，按 D8 给出可读错误，不自动关闭用户设置。
4. **D8 的已知代价**：发过图的会话切到不支持图片的模型后每轮报错；400 的提示只能留有余地，无法断言就是图片导致。
5. **xAI 订阅路径未采集图片请求**：若 `cli-chat-proxy.grok.com` 拒绝 `input_image`，按 D8 只显示错误；是否需要抓包补采由用户决定。不发 `detail` 是否可行同样待验收。
6. **旧名停用时间未公布**：因此 MM-0 应尽早完成真机验收，与后续多模态开发解耦。
7. **OpenAI 兼容协议的图片 token 估算**没有统一官方口径，只能保守估计并标注「预计」。
8. **摘要会丢失图片内容**，长会话压缩后模型只知道「曾经发过图片」。
9. GIF 与动态 WebP 只保留首帧。
10. 编辑消息时保留的原图不重新压缩，始终沿用首次导入的字节。
11. **`readTimeout` 的合适数值**需要真机测大量图片 + 思考的首字节延迟后确定，四个 `OkHttpClient` 实例都要覆盖。
12. **提交后重新抛出取消**会让「异常 ≠ 未提交」，所有文件去留必须查库决定；这是 MM-1a 与 MM-2 中最容易出错的地方，需重点测试。

## 7. 工作量估算

以下为粗略估算（含测试），实施时按实际拆分。2026-11-01 修订：D8 删掉了能力解析、剥图与设置开关；D7 增加了 xAI 适配器翻译与第三条传输的流式请求体、超时与错误映射，两者大致相抵。

| 分期 | 主要涉及 | 估计 |
| --- | --- | --- |
| MM-0 | `DeepSeekModels`、DataStore 迁移、RP Marker 与模型提示文案、预览、相关测试、文档 | 约 12～14 个文件，300～500 行（已实现） |
| MM-1a | Room v11 与导出 schema、文件存储与基本清理、domain、两套 DTO 与构造点、xAI 适配器翻译、共用流式请求体、统一历史映射、规划器与估算器、上下文面板、Repository 发送与重新生成 | 约 25 个文件，1800～2500 行 |
| MM-1b | Photo Picker 与压缩管线、输入栏与预览、气泡缩略图、面板展示 | 约 8 个文件，650～1000 行 |
| MM-2 | 编辑两阶段提交与文件去留、编辑弹窗、启动孤儿扫描与 Mutex、测试 | 约 10 个文件，700～1000 行 |
| MM-3 | 大图查看、粘贴、上传提示 | 约 5 个文件，300～500 行 |
