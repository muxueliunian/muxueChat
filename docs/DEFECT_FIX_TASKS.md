# muxueChat 缺陷修复任务清单

状态：核心修复已实施并通过主机构建/JVM 验证；API 36 设备测试与手工矩阵待完成  
建立日期：2026-08-10  
适用基线：当前 main 分支  
范围：已确认缺陷 + 第二优先级优化

> 本文中的行号来自 2026-08-10 审查快照。实施时应以类名、函数名和调用链为主定位，不能只依赖行号。

状态标记：

- `[x]`：实现完成，当前环境内要求的自动化验证已通过。
- `[~]`：实现完成且源码/可运行测试已通过，但仍依赖 API 36 设备或真实服务验收。
- `[ ]`：尚未实施，或只完成了其中一部分。

## 0. 本轮实施与验证结果

截至 2026-08-10，本轮已完成 FIX-01～FIX-12 的代码实现，并新增覆盖数据完整性、历史替换、流式压力、生成生命周期、RP 一致性、设置并发、凭据迁移和网络安全边界的回归测试。

主机验证结果：

- `:app:testDebugUnitTest`：成功，180 tests，0 failure。
- `:app:compileDebugAndroidTestKotlin`：成功；`app/src/androidTest` 当前包含 52 个测试方法。
- `:app:assembleDebug`：成功；最终 Debug APK 为 67,624,979 bytes。
- `:app:lintDebug`：成功，0 error、25 warning；剩余项只涉及依赖/AGP 可用更新、尚未删除的旧 round PNG 重复资源等第二优先级事项。
- Gradle Wrapper：已从 `9.0-milestone-1` 切换到稳定版 8.13，并加入官方 SHA-256 校验。
- `:app:connectedDebugAndroidTest`：测试 APK 成功构建，但未执行测试。本机唯一可用 AVD 为 API 33，而项目 `minSdk=36`，Gradle 以 `minSdkVersion [36.0] > deviceApiLevel [33]` 正确拒绝安装；没有通过降低 SDK 绕过。

因此，标记为 `[~]` 的任务仍需在 API 36 / Android 16 设备上执行 instrumentation 与第 11 节手工矩阵。需要真实 DeepSeek 或自定义 Provider 的场景还必须由不进入自动化测试的真实凭据验收。

2026-09-16 补充验证（上下文 token 口径重构、v9 用量表、DeepSeek 模型目录之后）：

- `:app:testDebugUnitTest`：成功，281 tests，0 failure，0 skipped。上文 180 tests 是 2026-08-10 的数字。
- `:app:compileDebugAndroidTestKotlin`：成功；`app/src/androidTest` 当前包含 80 个测试方法（含新增的 8→9 `MigrationTestHelper` 用例）。
- `:app:mergeDebugAndroidTestAssets`：`8.json`、`9.json` 已进入 androidTest assets。
- 本次未重跑 `:app:assembleDebug` 与 `:app:lintDebug`；设备侧 instrumentation 仍未执行（本机仍无 API 36 设备或镜像）。
- 本次修复：`ChatContextPlanner.planCompaction` 在历史条数为奇数、切点达到上限时不再直接放弃压缩，改为退回上一个 user 边界，并补充回归测试。

## 1. 目标

本轮工作的首要目标是消除已经确认的数据完整性、流式正确性、任务生命周期和敏感信息驻留问题；其次补齐 RP、设置、迁移、测试、架构和构建质量方面的优化。

完成后应满足：

- 不得静默丢失任何 SSE 事件或 token。
- 不得在新结果成功提交前破坏仍属于正式历史的原分支。
- 所有异步写入在落库前都必须验证 conversationId、messageId 和 generation session 仍然有效。
- 编辑或删除 RP 设定不会意外解绑、残留幽灵状态或继续使用过期派生数据。
- 重新生成、历史改写和配置错误不会造成非预期的数据丢失。
- DeepSeek 与 OpenAI 兼容流式链路不会因本地消费者较慢而丢 token 或主动截断。
- 生成、停止、删除、清空、切换会话和进程重启具有一致且可测试的状态语义。
- RP 后台补写不会拿错轮次输入，也不会由旧请求覆盖新状态。
- API Key、Authorization、完整消息和响应正文不会进入持久化设置、日志、错误提示或长生命周期泄漏对象。
- 新增回归测试能够覆盖本轮修复的关键失败路径。

## 2. 不得突破的项目边界

- 保持 minSdk、targetSdk、compileSdk 均为 36，不得降级。
- 请求协议仍然只有 DeepSeek 专属协议与 OpenAI 兼容协议两条，不增加第三条厂商私有链路。
- 不引入 OAuth、Cookie、CLI 登录身份、第三方私有 SDK或通用多 Provider 框架。
- 不引入 Hilt、Navigation Compose 等大型框架；依赖组装继续保持轻量。
- 不改变已经确认的自定义供应商 HTTP/HTTPS 能力。任何收紧或扩大明文 HTTP 策略的决定都必须先由用户确认。
- API Key 仍只存 Android Keystore，不进入 Room、DataStore、日志、错误文本或测试快照。
- 不为修复缺陷进行无关的大范围 UI 改版或架构重写。
- 每个任务都应保留 DeepSeek 与自定义 OpenAI 兼容供应商的协议边界和 Provider 快照语义。

## 3. 实施前验证基线

2026-08-10 审查结果：

- :app:testDebugUnitTest 成功：132 tests，0 failure，0 error，0 skipped。
- :app:assembleDebug 成功。
- :app:lintDebug 成功：0 error，35 warning。
- 当前没有 app/src/androidTest。
- Room 注册了 1→8 的七段手写 migration，但仓库只保存 v8 schema。
- Git 工作区在审查开始和结束时均为干净状态。
- Debug APK 约 63.2 MiB。

实施期间的基础命令：

    .\gradlew.bat :app:testDebugUnitTest
    .\gradlew.bat :app:assembleDebug
    .\gradlew.bat :app:lintDebug

涉及 Room migration、DAO 外键或 Android Keystore 的任务，还必须运行对应 instrumentation test 或真机验证，不能只依赖 JVM 单测。

## 4. 修复批次与依赖顺序

| 批次 | 目标 | 任务 | 依赖 |
| --- | --- | --- | --- |
| A | 先阻止静默数据破坏和敏感请求泄漏 | FIX-01、FIX-02、FIX-03、FIX-04 | 无 |
| B | 修复流式背压和生成生命周期 | FIX-05、FIX-06 | A |
| C | 修复 RP 与历史派生状态一致性 | FIX-07、FIX-08、FIX-09 | A、B |
| D | 修复设置、迁移和网络安全边界 | FIX-10、FIX-11、FIX-12 | A |
| E | 补齐自动化测试和手动验收 | TEST-01～TEST-05 | 随对应修复同步落地 |
| F | 第二优先级维护与发布优化 | OPT-01～OPT-06、OPS-01 | A～E 行为稳定后 |

执行规则：

- 同一时间尽量只修改一条核心生成链路，避免 ChatRepositoryImpl、ChatViewModel 和两个 Client 出现难以审查的交叉改动。
- 每个 FIX 必须同时提交对应回归测试，不能等到最后集中补测试。
- 批次 F 的类拆分不得与批次 A～D 的行为修复混在同一提交中。

## 5. 批次 A：数据完整性与敏感信息

### [~] FIX-01：消除 Room REPLACE 导致的静默解绑

优先级：P1  
主要文件：

- app/src/main/java/com/muxuechat/data/local/ChatDao.kt:49-53、100-104
- app/src/main/java/com/muxuechat/data/local/ChatEntities.kt:183-212
- app/src/main/java/com/muxuechat/data/repository/RpRepositoryImpl.kt:47-115

确认问题：

- role_cards、world_settings、writing_style_profiles 的保存使用 OnConflictStrategy.REPLACE。
- SQLite 的 INSERT OR REPLACE 会先删除旧父行，再插入同 ID 新行。
- conversation_rp_settings 对这些父表使用 ON DELETE SET NULL。
- 因此编辑同一个角色卡、世界观或文风，会把已有会话绑定静默置空。

实施要求：

- 将上述父表的编辑改为不会触发 DELETE 的 @Upsert 或显式 UPDATE。
- 审计 ChatDao 内所有 REPLACE 使用点，尤其是 conversations 和 messages：前者一旦被用于更新会级联删除消息、RP 设置、状态栏与摘要，后者一旦被用于更新会级联删除 message_rp_metadata。
- 创建与更新使用明确语义：创建优先使用失败可见的 INSERT，真正更新使用 @Update 或经过验证的 @Upsert。
- 新建仍应保持幂等；编辑应保留 createdAtMillis，只更新业务字段与 updatedAtMillis。
- 修正或删除 ConversationRpSettings.toEntity() 中遗漏 writingStyleId 的死 mapper。

验收标准：

- 编辑已绑定角色卡后，所有相关 conversation_rp_settings.roleCardId 保持不变。
- 编辑已绑定世界观和文风后，对应 ID 保持不变。
- 更新既有 conversation 不会丢失消息、RP 设置、状态栏或摘要。
- 更新既有 message 不会丢失其 RP metadata。
- 新建、重复保存和编辑均不会产生重复行。
- 外键约束保持开启，不能通过关闭 foreign_keys 绕过问题。
- 新增 DAO 回归测试能够在旧 REPLACE 实现上失败、在修复后通过。

### [~] FIX-02：重新生成与历史改写先预检，再修改数据

优先级：P1  
主要文件：

- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:151-247
- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:322-378
- app/src/main/java/com/muxuechat/presentation/chat/ChatViewModel.kt:279-385

确认问题：

- regenerateLastResponse 先删除旧 assistant，再冻结 Provider 请求快照。
- editUserMessageAndRegenerate 先删除全部后续消息并改写用户消息，再冻结请求快照。
- 缺 API Key、供应商不存在、Base URL 或模型无效时，预检抛错，但旧回复或后续分支已经永久删除。

实施要求：

- 所有破坏性历史操作必须先完成 Provider、Base URL、模型和 Key 的本地预检，并冻结 ProviderRequestSnapshot。
- 原分支必须保留到新结果成功提交；配置错误、网络失败、解析失败、用户取消和进程中断均不得永久删除原分支。
- 使用 staging、临时 assistant 或其他等价的两阶段替换方案；新结果成功后，再在 Room 事务中原子切换正式分支。
- 历史删除、用户消息更新、RP 状态回滚、摘要失效和 assistant 正式提交应在 Room 事务内保持一致。
- 不把网络请求放进 Room 事务。
- 配置预检失败时，数据库历史必须完全不变。
- 网络请求开始后失败时，应保留原分支并展示可读错误；临时 assistant 不得成为永久 STREAMING 或第二条正式历史。
- 普通新消息发送也要明确预检失败语义：用户输入不能无提示丢失，并应在消息位置显示失败状态或保留可重试入口。

验收标准：

- 清除当前 Provider Key 后点击“重新生成”，旧 assistant 仍完整存在。
- Provider 配置无效时编辑历史用户消息，原消息和所有后续消息均不变。
- 网络失败或用户取消重新生成后，原 assistant、metadata、状态栏和摘要仍完整可用。
- 新回复成功后，旧分支和新分支不会同时作为正式历史存在。
- 模拟正式提交前进程退出，重启后原分支仍可用，临时 staging 可安全回收。
- 事务中任一步骤抛错时，历史、摘要和 RP 状态整体回滚。
- 请求已开始后发生 401、DNS、timeout 或解析失败时，消息进入 FAILED，不停留在 loading。
- DeepSeek 与自定义 OpenAI 兼容两条链路行为一致。

### [~] FIX-03：删除角色卡时清理幽灵 RP 状态

优先级：P1  
主要文件：

- app/src/main/java/com/muxuechat/data/repository/RpRepositoryImpl.kt:70-72
- app/src/main/java/com/muxuechat/data/local/ChatDao.kt
- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:1007-1033
- app/src/main/java/com/muxuechat/domain/prompt/PromptComposer.kt

确认问题：

- 删除角色卡只删除父表记录。
- 外键只把 roleCardId 置 null，不会关闭 isRpEnabled，也不会删除 rp_status_panels。
- 后续 prompt 仍可能注入通用 RP 指令和旧状态栏。

实施要求：

- 在单个 Room 事务内找出所有绑定该角色卡的会话。
- 对受影响会话清空 roleCardId、关闭 RP，并删除当前 rp_status_panels。
- 保留历史消息正文；历史 message_rp_metadata 是否保留仅用于历史展示，应在实现中明确且不得继续影响当前状态。
- 删除世界观或文风仍可使用解绑语义，但应有回归测试确保不会误删其他设置。
- 角色卡 metricDefinitions 被编辑时，应为仍绑定的会话重新归一化当前状态栏，避免旧指标继续进入 prompt。

验收标准：

- 删除当前绑定角色卡后，聊天页不再显示旧状态栏。
- 受影响会话的 RP 明确关闭，后续普通请求不注入 RP 状态。
- 删除一个角色卡不会影响绑定其他角色卡的会话。
- 删除世界观或文风仅解除对应绑定，不重置无关 RP 设置。

### [x] FIX-04：OpenAI 非流式请求必须在所有退出路径释放 Call

优先级：P1 / 安全  
主要文件：

- app/src/main/java/com/muxuechat/data/remote/OpenAiCompatibleClient.kt:127-177
- app/src/main/java/com/muxuechat/data/remote/OpenAiCompatibleClient.kt:237-242

确认问题：

- completeChat 在 activeCalls.add 后执行 call.execute。
- execute 若在 DNS、连接、超时或取消阶段抛错，不会进入当前 finally。
- activeCalls 会长期持有包含 Authorization 和完整 JSON 请求正文的 Call。

实施要求：

- 用一个外层 try/finally 覆盖 execute、响应校验、读取和解析全过程。
- 所有成功、HTTP 错误、IOException、CancellationException 和解析错误路径都必须 remove(call)。
- cancelActiveRequest 负责发出取消，最终清理由请求自身 finally 完成；不得形成并发修改或误删其他请求。
- 测试和日志不得输出 Request、Authorization 或 body。

验收标准：

- DNS、连接拒绝、timeout、主动取消和响应解析失败后，activeCalls 数量均回到零。
- 重复失败请求不会线性增加 Client 持有对象。
- 停止生成仍能取消所有正文和 RP 补写请求。

## 6. 批次 B：流式正确性与生成生命周期

### [x] FIX-05：修复 SSE 背压、丢 token 和逐 delta 写库

优先级：P1  
主要文件：

- app/src/main/java/com/muxuechat/data/remote/OpenAiCompatibleClient.kt:50-124
- app/src/main/java/com/muxuechat/data/remote/DeepSeekApiClient.kt:34-102
- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:424-458、570-612
- 两套 StreamParser 与 ToolCallAccumulator

确认问题：

- callbackFlow 默认缓冲有限。
- OpenAI 链路在 trySend 失败时直接 break；缓冲满会被误认为通道已关闭。
- DeepSeek 链路忽略 trySend 失败，可能静默丢失正文、reasoning、tool_calls 或终止事件。
- Repository 每个 delta 都拼接 String、更新整条 Room 消息并触发 UI，消费者很容易慢于高速上游。

实施要求：

- 使用真正背压感知的有序传递方式；不得用无限缓冲简单掩盖问题。
- 正文、reasoning、finish_reason、tool_calls 和 DONE 必须保持原顺序。
- 使用 StringBuilder 或等价累加结构，避免长回复反复 String 相加形成平方级复制。
- UI 更新与 Room 持久化分离：UI 可按短时间窗口合并刷新，Room 按节流周期和终态强制 flush。
- 取消、失败、正常完成、纯工具调用和达到工具轮数上限都必须执行最终 flush。
- 两条 Provider 链路共享相同背压原则，但仍保留各自 DTO 和 Parser。

验收标准：

- 模拟超过默认通道容量的快速 SSE 事件和慢消费者，最终正文逐字节一致。
- reasoning 与正文均无缺段、重复或乱序。
- 多片段 tool_calls 能完整重组。
- DONE 或 finish_reason 不会因缓冲满而丢失。
- 长回复期间 Room 写入次数显著低于 delta 数量，且最终数据库内容与 UI 一致。
- 停止生成后不会再追加迟到 delta。

测试建议：

- 优先使用现有 coroutines-test。
- 如需真实 HTTP/SSE 行为，可引入同属 OkHttp 生态的 mockwebserver 作为 test-only 依赖；新增前需记录用途与不用依赖的替代方案。

### [~] FIX-06：建立会话级 GenerationSession 和启动恢复

优先级：P1  
主要文件：

- app/src/main/java/com/muxuechat/presentation/chat/ChatViewModel.kt:94-96、279-460、924-929
- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:272-307
- app/src/main/java/com/muxuechat/data/local/ChatDao.kt:142-148
- app/src/main/java/com/muxuechat/presentation/chat/components/ConversationDrawer.kt

确认问题：

- generationJob 只有 Job，没有 conversationId。
- 删除正在生成的会话不取消请求；后续流事件会把 activeConversationId 设回已删除 ID。
- 清空任意会话都会取消全局生成，即清空 B 可能停止 A。
- handleGenerationMessage 在每个流事件上强制切回生成会话，用户无法稳定浏览其他会话。
- 进程被杀后数据库中的 STREAMING/PENDING 不会恢复，UI 可永久显示打字动画。
- Repository 的 enrichmentScope 不随 ViewModel 清理。

实施要求：

- 明确单生成策略，并建立至少包含 sessionId、conversationId、assistantMessageId、Job、Provider 快照和状态的 GenerationSession。
- 删除或清空目标会话时，只在目标属于当前 session 时取消正文请求。
- 删除会话前取消该会话的正文、RP enrichment 和待执行状态写入。
- 用户切换到其他会话后，后台 delta 不得强制切回；新建会话首次发送时仍应正确选中新会话。
- stopGeneration 只把对应会话的 STREAMING/PENDING 改为 CANCELLED。
- 在进程级 composition root 或数据库初始化处只执行一次遗留 STREAMING/PENDING 原子回收；不能在每次 ViewModel 重建时重复执行。
- ViewModel onCleared 或等价生命周期出口应取消仍在运行的请求和独立 scope。
- enrichment 必须可按 conversationId 和 messageId 定向取消。

验收标准：

- A 生成时切换到 B，界面保持 B；A 在后台继续且最终落库。
- A 生成时清空 B，不会停止 A。
- A 生成时删除 A，请求立即取消，A 不会重新出现在会话列表。
- 停止生成只影响当前 session，不误伤模型列表刷新。
- 强制结束进程再启动，遗留消息显示 CANCELLED 或 FAILED，不显示永久打字动画。
- 快速连续点击停止、删除、清空不会产生未捕获异常或恢复已删除会话。

## 7. 批次 C：RP 与历史派生状态一致性

### [~] FIX-07：让 RP enrichment 使用固定轮次上下文并防止乱序覆盖

优先级：P1  
主要文件：

- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:1036-1165
- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:1239-1374
- app/src/main/java/com/muxuechat/presentation/chat/ChatUiState.kt
- app/src/main/java/com/muxuechat/presentation/chat/components/MessageList.kt

确认问题：

- 每条 assistant 启动独立 IO enrichment job，下一轮无需等待。
- job 运行时才读取“最后一条用户消息”和当前 panel，旧轮可能读取到新轮输入。
- 旧请求晚返回时会无条件 upsert 当前状态栏，覆盖新轮状态。
- enrichingMessageIds 已进入 ChatUiState，但没有用于 MessageList；补写期间会提前显示“重试建议”，可产生重复请求。

实施要求：

- 正文完成时捕获本轮 user、assistant、角色卡、RP 设置、状态栏和 ProviderRequestSnapshot。
- 同一 conversation 的 enrichment 按顺序执行，或通过单调 turn/version 做提交校验。
- 每条消息的 metadata 可以独立保存，但只有仍是最新有效轮次的结果才能更新当前 rp_status_panels。
- 旧轮结果到达时不得覆盖新轮当前状态。
- 删除、清空、历史改写和 ViewModel 清理时取消对应 enrichment。
- UI 使用 enrichingMessageIds 显示补写中状态，并在补写中禁用重复重试。

验收标准：

- 第 N+1 轮在第 N 轮补写完成前发送，第 N 轮仍使用自己的 user 内容。
- 人为让旧请求晚于新请求返回，当前状态栏仍属于新轮。
- 历史消息 metadata 可正确落到各自 messageId。
- 补写期间不显示可点击的重复重试；失败后才出现重试入口。

### [~] FIX-08：assistant 编辑后使 metadata、RP 状态和摘要失效

优先级：P2  
主要文件：

- app/src/main/java/com/muxuechat/data/repository/ChatRepositoryImpl.kt:250-269
- app/src/main/java/com/muxuechat/data/local/ChatDao.kt
- app/src/main/java/com/muxuechat/domain/context/ContextWindowPlanner.kt:82-100（该文件已于 2026-09 删除，对应逻辑现位于 `ChatContextPlanner.kt`）

确认问题：

- editAssistantMessage 只更新正文和状态。
- 旧 message_rp_metadata 会继续拼回 UI。
- conversation_context 可能继续用覆盖旧正文的摘要，从而忽略新内容。
- 对该消息仍在运行的 enrichment 不会取消。

实施要求：

- 编辑前取消该消息的 enrichment。
- 删除该消息旧的内心、选项和状态快照派生数据，不能继续展示。
- 任何可能影响已覆盖历史的人工编辑都使 conversation_context 失效；首版可安全地删除该会话摘要，后续再讨论增量重算。
- 编辑最新 assistant 时，将当前状态栏恢复到该回复之前的有效快照。
- 对非最新 assistant 的编辑，清理该消息及其后所有受影响的 RP metadata/状态快照，回滚到编辑点之前，并明确后续正文继续保留时的 UI 提示与状态语义。
- 不自动重新请求 metadata，避免用户未确认的额外费用。

验收标准：

- 编辑后该消息不再显示旧内心和旧选项。
- 下一次请求包含编辑后的正文，不使用旧摘要替代它。
- 编辑期间到达的旧 enrichment 结果不会重新写回。
- 最新回复编辑后，当前状态栏不继续使用该回复修改前的状态。
- 不会因为人工编辑自动产生额外 Provider 请求或费用。

### [~] FIX-09：历史变更统一失效摘要和 RP 派生状态

优先级：P2  
主要文件：

- ChatRepositoryImpl 的 regenerate、edit、clear、delete 和状态回滚路径
- ChatDao 的 conversation_context、message_rp_metadata、rp_status_panels 操作

实施要求：

- 建立一份“历史变更 → 应失效派生数据”的明确映射。
- 重新生成最后回复：删除该回复及其 metadata，恢复前一状态，摘要按覆盖边界决定失效；为降低首版风险可直接清除摘要。
- 编辑用户消息并删除后续：删除对应后续 metadata，恢复状态并清除摘要。
- 清空会话：消息、metadata、状态栏、摘要一起清理。
- 删除会话继续依赖外键级联，同时取消内存任务和草稿。
- 所有多表操作放在 Room 事务。

验收标准：

- 数据库中不存在指向已删除消息的孤儿 metadata。
- 清空或历史改写后，不会在下一次请求中出现已删除历史的摘要内容。
- RP 状态回滚与当前最后一条有效 assistant 对齐。

## 8. 批次 D：设置、迁移与网络边界

### [~] FIX-10：设置改为原子更新，并对文本输入做短 debounce

优先级：P2  
主要文件：

- app/src/main/java/com/muxuechat/data/local/SettingsDataStore.kt:53-101
- app/src/main/java/com/muxuechat/data/repository/SettingsRepositoryImpl.kt:27-139
- app/src/main/java/com/muxuechat/presentation/chat/ChatViewModel.kt:465-630
- app/src/main/java/com/muxuechat/presentation/settings/SettingsScreen.kt

确认问题：

- 多个入口使用 settings.first() 后修改副本，再覆盖全部设置。
- 快速并发操作可能都基于旧快照，后写者覆盖前写者的无关字段。
- Base URL 和 system prompt 每次按键都启动 DataStore 写入，可能造成旧值回流、光标跳动和过量 I/O。

实施要求：

- 提供串行、原子的 update(transform) 或细粒度字段更新 API。
- Provider 列表、模型刷新、Provider 选择和普通生成参数不得互相覆盖。
- 文本字段保留本地草稿，使用短 debounce 自动保存；离开页面、发送消息或明确提交前执行 flush。
- 仍保持“除 API Key 外实时自动保存，下一次发送生效”的产品语义。
- API Key 保存继续使用独立手动动作，不进入自动保存模型。

验收标准：

- 快速交替修改 temperature、top_p、system prompt 和 Provider，不丢任何最后值。
- 模型列表刷新与设置编辑并发时，缓存模型不会被旧设置覆盖。
- 中文 IME 连续输入不回跳、不丢 composition。
- 自动保存次数明显低于按键次数，退出页面后最新文本已持久化。

### [~] FIX-11：旧 Provider Key 迁移改为幂等、可重试状态机

优先级：P2  
主要文件：

- app/src/main/java/com/muxuechat/data/local/SettingsDataStore.kt:104-136
- app/src/main/java/com/muxuechat/data/repository/SettingsRepositoryImpl.kt:142-149
- EncryptedCliProxyApiKeyStore 与 EncryptedProviderKeyStore

确认问题：

- DataStore 先写 LEGACY_MIGRATED=true 和新 Provider。
- Key 复制在之后执行。
- 两步之间进程退出或 Keystore 写入失败时，下次启动不会重试。

实施要求：

- 使用 pending/completed 两阶段状态，或其他等价的幂等迁移方案。
- pending 状态必须保存稳定的目标 providerId，重试时不得重复创建 Provider。
- 新 Key 写入后读取验证成功，再清除旧密文并标记 completed。
- 任一步骤失败时保留可重试状态，不记录 Key 明文。
- 迁移日志只记录阶段和异常类型。

验收标准：

- 在每个迁移步骤模拟进程中断，重启后均可继续完成。
- 重试不会创建重复供应商或多个 Keystore alias。
- 成功后旧密文被清理，新 Provider Key 可正常使用。

### [~] FIX-12：统一 DeepSeek Base URL 校验和安全错误映射

优先级：P2 / 安全  
主要文件：

- app/src/main/java/com/muxuechat/domain/model/ChatSettings.kt:98-112
- app/src/main/java/com/muxuechat/data/remote/DeepSeekApiClient.kt
- app/src/main/java/com/muxuechat/presentation/settings/SettingsScreen.kt:746-774
- ChatRepositoryImpl 的错误映射和日志调用

确认问题：

- DeepSeek Base URL 当前主要做 trim/default，没有自定义 Provider 同等级的 URI 校验。
- 全局允许明文 HTTP，DeepSeek 设置页没有相应风险提示。
- DeepSeek 若出现未识别异常，部分路径会记录 error.message 和 Throwable，响应解析异常可能携带不适合进入日志或 UI 的内容。

实施要求：

- 在不擅自改变 HTTP 接受策略的前提下，统一校验 scheme、host、port，并拒绝 user-info、query、fragment。
- 合法 Base URL 的既有路径前缀必须保留，例如 /v1；不能在归一化时误删。
- DeepSeek Base URL 为 HTTP 时显示与实际风险一致的明文提示；若未来要禁止，必须先取得用户确认。
- 修正 ChatViewModel 中“只支持 https:// 地址”的过期提示，统一表述为支持 http:// 或 https://。
- 用户可见错误不得回显原始响应正文、完整 URL、Authorization 或 Key。
- 日志以 HTTP 状态、异常类型、host 和长度为限，不记录完整异常 message 或敏感 stack 内容。
- 保留 401、402、403、429、5xx、DNS、timeout、SSL 和提前断流的中文提示。

验收标准：

- 非法 URI、user-info、query、fragment 和非法端口在请求前被拦截。
- HTTP 端点有明确风险提示，HTTPS 正常使用。
- 构造包含敏感片段的解析异常，Logcat 和消息 errorMessage 中均找不到该片段。

## 9. 测试任务

### [~] TEST-01：Room DAO 与外键回归测试

- 建立 app/src/androidTest 基础设施。
- 加入 room-testing 和必要 AndroidX Test 依赖；新增前记录用途及替代方案。
- 覆盖编辑角色卡、世界观、文风不解绑。
- 覆盖删除角色卡事务清理。
- 覆盖历史改写多表事务回滚。

### [~] TEST-02：Room migration 测试

- 尽可能从 Git 历史、已发布包或可靠备份恢复 v1～v7 schema。
- 测试每个相邻 hop 和 1→8 全路径。
- 核对表、列、默认值、索引、外键、ON DELETE 行为与 Room v8 schema。
- 如果历史 schema 无法恢复，必须记录阻塞和替代的手工建库测试，不能假装已覆盖真实升级。

### [~] TEST-03：Repository 与生成状态测试

- Provider 预检失败不破坏历史。
- 网络失败创建 FAILED assistant。
- stop/delete/clear/switch 的 conversationId 归属。
- 启动回收遗留 STREAMING/PENDING。
- onCleared 取消正文和 enrichment。

### [x] TEST-04：SSE 与工具调用压力测试

- 两个 Provider 各自覆盖至少 1000 个小 delta 的高速事件。
- 慢 collector 下正文、reasoning、finish_reason、DONE 无丢失。
- 分片 tool_calls 参数完整累积。
- 取消和终态 flush。
- Room 写入节流不改变最终内容。

### [~] TEST-05：RP 并发与设置竞态测试

- 两轮 enrichment 反序返回。
- 旧轮 metadata 保存但不得覆盖新当前状态。
- 编辑 assistant 后旧 enrichment 不得回写。
- 快速并发设置更新不丢字段。
- 旧 Provider migration 在每个中断点可恢复。

## 10. 第二优先级维护与发布优化

### [ ] OPT-01：拆分超大类，但不改变行为

- ChatRepositoryImpl 约 1591 行；建议提取生成协调、上下文压缩、RP enrichment、工具执行和错误映射协作者。
- ChatViewModel 约 1016 行；按聊天、Provider 设置、RP、上下文和草稿动作分组委托。
- ChatScreen、SettingsScreen 和 RpSettingsSheet 按现有 presentation/components 规则继续拆小。
- 拆分必须在 FIX 与回归测试稳定后进行，不与行为修复混合。
- 类行数不是验收目标；职责单一、可测试性提升和公共行为不变才是验收目标。

验收标准：

- Repository 仍是 presentation 的稳定入口。
- UI 不直接访问 DAO、DataStore、Keystore 或 Client。
- 不增加第三协议分支、不引入大型框架。
- 全部回归测试与手动验收无行为变化。

### [ ] OPT-02：建立轻量 AppContainer 并统一边界

- 将 ChatViewModel.Factory 中的数据库、Client、Keystore 和 Repository 组装移入轻量 AppContainer。
- 明确 use case 与 Repository 的职责：有业务策略的用例保留，纯一行透传可逐步精简。
- domain 不应依赖 Provider DTO；是否移动 Repository contract 应结合根架构规范单独审查，不在缺陷修复中顺带大改。

### [ ] OPT-03：优化长会话读取和上下文规划

- 当前每次规划会读取整个会话，再在内存中按 coveredMessageCount 丢弃。
- 为超长会话增加按边界/后缀查询，避免历史增长后每次发送都全量加载。
- 保持摘要覆盖计数与消息排序稳定；任何数据库查询优化都需与现有 `ChatContextPlannerTest` 对齐（`ContextWindowPlanner` 已于 2026-09 删除，由 `domain/context/ChatContextPlanner.kt` 取代）。

### [x] OPT-04：稳定 Gradle 与依赖管理

- 将 gradle-9.0-milestone-1 替换为与当前 AGP 官方兼容的稳定 Wrapper。
- 增加 distributionSha256Sum。
- 依赖升级逐项验证，不根据 Lint 提示一次性全部升级。
- 不降低 Android 36 要求。

### [ ] OPT-05：发布包体与资源优化

本轮部分完成：adaptive icon 已迁移到通用 `mipmap-anydpi`，加入 monochrome 主题图标并移除失真的 `roundIcon` manifest 引用。尚未完成 release/R8/resource shrink 方案、material-icons-extended 评估、发布包体对比；五个与主图完全相同的旧 `ic_launcher_round.png` 仍待删除。

- 明确 release buildType、版本流程和经过验证的 R8/resource shrink 策略。
- 评估 material-icons-extended；当前只使用少量图标，可改用核心图标或本地 VectorDrawable。
- 修复 round icon 形状、重复图标和 Android 主题图标 monochrome 资源。
- 对比优化前后的 release APK/AAB，而不是只比较 Debug APK。

### [ ] OPT-06：清理可行动 Lint warning

本轮部分完成：TimeFormat 已改为动态 Locale 且不再跨线程复用 `SimpleDateFormat`；Android 12+ 备份/迁移规则已显式排除全部敏感数据域；三类凭据存储的同步 `commit()` 仅做精确 `UseKtx` 豁免；原 `mipmap-anydpi-v26` 中的 `ic_launcher.xml` 已迁移到通用 `mipmap-anydpi`，`ic_launcher_round.xml` 已删除，该目录不再存在。剩余 warning 主要属于 OPT-05 的旧 round PNG 和需单独兼容性评估的依赖/AGP 更新。

- TimeFormat 不要把 Locale.getDefault() 固定在静态常量中。
- 为 Android 12+ 明确 dataExtractionRules，同时继续遵守不备份敏感数据的原则。
- 处理 launcher icon、KTX 建议和 obsolete SDK 条件。
- 依赖版本 warning 单独评估兼容性，不以“零 warning”为由盲目升级。

### [ ] OPS-01：处理本地诊断产物

当前项目根目录存在：

- java_pid27744.hprof，约 725.1 MiB。
- realme-RMX5060-Android-16_2026-08-08_220643.logcat，约 4.6 MiB。

要求：

- 两者已被 .gitignore 排除，审查未发现其进入 Git 历史。
- heap dump 可能包含 API Key、消息正文或角色卡内容，不能上传、提交或交给无关 Agent。
- 用户确认不再需要排查后，再移出项目或执行删除；不得由修复任务擅自删除。

## 11. 手动验收矩阵

每个相关批次完成后，至少在 Android 16 设备或模拟器验证：

| 场景 | DeepSeek | 自定义 HTTPS | 自定义 HTTP |
| --- | --- | --- | --- |
| 普通非流式聊天 | 必测 | 必测 | 必测且确认风险提示 |
| 普通流式长回复 | 必测 | 必测 | 必测 |
| reasoning 展示 | 必测 | 支持时测 | 支持时测 |
| 停止生成 | 必测 | 必测 | 必测 |
| 重新生成 | 必测 | 必测 | 必测 |
| 编辑用户消息并重生成 | 必测 | 必测 | 必测 |
| 切换/删除/清空会话 | 必测 | 必测 | 任一链路覆盖 |
| RP 连续两轮快速发送 | 必测 | 必测 | 支持时测 |
| RP 选项补写与重试 | 必测 | 必测 | 支持时测 |
| 上下文自动/手动压缩 | 必测 | 必测 | 任一链路覆盖 |
| tools / tool_calls | 必测 | 显式开启时测 | 显式开启时测 |
| 强杀进程后恢复 | 必测 | 必测 | 任一链路覆盖 |

真实服务的可用性仍由用户验收；自动测试应使用不会包含真实 API Key 的 fake 或本地 mock。

## 12. 完成定义

单个任务可以标记完成的条件：

- 代码范围与本文任务一致，没有无关重构。
- 对应自动化回归测试已加入并通过。
- 相关手动场景已验证或明确记录无法验证的外部原因。
- 日志、错误文本和测试输出不含 API Key、Authorization、完整消息、system prompt 或完整响应。
- :app:testDebugUnitTest、:app:assembleDebug 通过。
- 涉及 release 配置或资源缩减时，:app:assembleRelease 也必须通过。
- 涉及 Android/Room/UI 的任务已运行对应 instrumentation 或真机验证。
- Lint 没有新增 error；新增 warning 必须说明。
- 没有提交 build、.gradle、hprof、logcat、APK 等产物。
- 文档中的任务状态、实测结果和剩余风险已更新。

本轮整体关闭条件：

- FIX-01～FIX-12 全部完成。
- TEST-01～TEST-05 全部完成，或对不可恢复的历史 schema 给出明确阻塞记录和替代测试。
- Android 16 环境的 connectedDebugAndroidTest 或等价真机 instrumentation 验证通过。
- 关键手动验收矩阵通过。
- OPT-01～OPT-06 的已实施项不改变双协议和凭据安全边界。
- 未完成的优化必须保留为显式任务，不能以“构建通过”代替关闭。
