# RP-2 交接文档（2026-09-23）

**状态：已完成（2026-09-24）。** 2a～2h 全部完成并提交；本文只作历史记录，接续 RP-3 请读 `docs/RP_ACTOR_PLAN.md`。

接手前按顺序读：`AGENT.md` → 本文 → `docs/RP_ACTOR_PLAN.md` 的第 3.3、3.6、6、11 节与第 9 节
「RP-1 实施记录」。本文只写**接下来怎么做**，设计理由在计划文档里。

## 0. 现状

- 基线提交：`270e669`（RP-0 + 主角设定工具 + RP-1）。其后 `d1a5c10` / `1cf884b` 是 xAI 工具调用，
  没碰 RP 文件。
- 2026-09-23 追加（未提交）：RP-2a、2b、2c 已完成，JVM 415/415，androidTest 编译通过，未上真机。
- 2026-09-23 再追加（未提交）：2c 遗留问题（空名字主角）、2d、2e、2f、2g 与 2b 的端到端用例已完成。
  JVM **424/424**，androidTest 编译通过。
- 2026-09-23 真机 instrumented（用户同意，带 `leaveApksInstalledAfterRun=true`，App 未被卸载）：
  第一轮 81 项跑完后进程被 ColorOS 息屏杀掉（`OsenseKillAction`，发生在卡住两分钟的
  `ChatRepositoryTokenUsageTest.observeContextWindow_emitsWithoutOpeningAnyPanel` 期间）；
  补跑未覆盖的 `RpBindingTransitionTest` 与 `SettingsMigrationAndCredentialConcurrencyTest` 共 44 项全过。
  合计 125 项，失败只有 `ChatRepositoryTokenUsageTest` 的 3 项老问题；该类另有 6 项因进程被杀未跑。
  **真机跑测试时要保持亮屏。**
- 2026-09-23 第 4 节数据恢复已完成（见第 4 节末尾）。剩下 2h 真机验收（用户做）。
- RP-1 完成：Room v10，主角 / 配角表，会话级数值定义快照，线上名带前缀。
  验证：JVM 396/396；真机 instrumented 116/119（3 项是 `ChatRepositoryTokenUsageTest` 的老问题，与 RP 无关）。
- **RP-1 不能单独发布，必须与 RP-2 一起发布。** 2c 之后 RP 设置里的主角三个输入框改了不生效（2d 解决）。

## 1. 必须知道的概念（代码里的真相）

| 概念 | 位置 | 要点 |
| --- | --- | --- |
| 词条 | `domain/model/RpEntry.kt` | `Text` / `Numeric`；`RpEntryOwner` = `ROLE_CARD`（前缀「角色」）/ `PROTAGONIST`（「主角」） |
| 线上名 wireName | `RpStateBlockFormat.wireNameOf` | `角色::X` / `主角::X`，分隔符常量 `WIRE_NAME_SEPARATOR` |
| 定义快照 | `ConversationRpSettings.metricSnapshot` | 绑定时冻结，改档案不影响已绑定会话（R6）。只有 `RpRepository.syncMetricDefinitionsFromProfiles` 会重拍 |
| 有效定义 | `domain/rp/RpEffectiveDefinitions.kt` | `of(settings)` 返回 `RpMetricDefinition`，**`name` 是 wireName，`id` 是 entryId**，所以下游按名字匹配的代码不用改。`displayNameOf(wireName, snapshot)` 只解析快照里登记过的名字 |
| 绑定变化 | `RpRepositoryImpl.applyBindingTransition` | 换卡重置角色组并清关系/场景；换主角只重置主角组；暂停保留一切 |
| 状态合并 | `domain/rp/RpStateMerger.kt` | 正文、补写、兜底、回滚都走它，失败保留当前值 |

面板、消息元数据里存的名字全部是 wireName（迁移时已经给旧数据加了 `角色::`）。

## 2. 小任务（按顺序做，每个做完单独跑一遍验证）

每个任务的**基本验证**：`./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 全绿。

### [x] RP-2a 状态面板显示名（已完成，未提交）

**已做**：`RpEffectiveDefinitions.displayLabelsOf(wireNames, snapshot)`，面板
（`RpStatusPanelCard` 新增 `metricSnapshot` 参数，`ChatScreen` 传 `activeRpSettings.metricSnapshot`）
显示快照里的显示名；角色卡与主角撞名时才显示成「主角·体力」。全仓只有面板显示数值名，
消息气泡不显示。测试：`RpEffectiveDefinitionsTest`（3 项）。以下是原任务描述，留作对照。


- `presentation/chat/components/RpStatusPanelCard.kt:172` 直接显示 `metric.name`，改为
  `RpEffectiveDefinitions.displayNameOf(metric.name, settings.metricSnapshot)`。
  快照里查不到的名字（模型自报、无定义）原样显示。
- 需要把 `activeRpSettings` 的快照传到面板；再搜一遍 `presentation/` 里其它显示数值名的地方
  （如上下文窗口、消息元数据展示），同样处理。
- 验收：面板显示「好感度」，不显示 `角色::`。

### [x] RP-2b 部分失败保留关系与场景（已完成，未提交）

**已做**：
- `RpStateMergeResult` 新增 `textOnlyPanel`：`panel == null`（数值全没对上）且模型给了关系或场景时非空，
  数值是当前值。`panel` 的原语义不变。
- `ChatRepositoryImpl.mergeRpState` 改为返回整个结果。
- 正文路径：`localStatusPanel = panel ?: textOnlyPanel`，会随正文提交写进面板；
  但 `hasLocalPanel` 只看 `panel`，所以后台照常发兜底请求。补写的 `currentPanel` / `expectedStatusPanel`
  沿用已提交的这份，乐观并发守卫不受影响。
- 补写路径：`candidatePanel = (元数据面板 ?: 兜底面板) ?: (兜底 textOnly ?: 元数据 textOnly)`；
  「未更新状态栏」提示只看数值有没有更新（`metricsPanel == null`）。
- 测试：`RpStateMergerTest` 新增 3 项。

**端到端用例（2026-09-23 补上，只编译、未上设备）**：`ChatRepositoryRpStateTest` 新增 2 项——
正文状态块数值缺前缀、只有关系/场景有效 → 仍发兜底（共 3 次请求）；兜底失败时数值保持当前值、
关系与场景是正文给的新值、提示「未更新状态栏」；兜底成功时数值来自兜底，兜底留空的关系与场景
不回退到本轮开始前的旧值（补写的基准面板是正文提交的那份，`ChatRepositoryImpl` 构造 `RpEnrichmentInput` 处）。

以下是原任务描述，留作对照。


- 用户选定：状态块里**一个数值都没对上**时，**保留关系与场景的新值**，数值保留当前值，
  **仍然发兜底请求**。
- 现状：`RpStateMerger.merge` 在 `matched == 0` 时返回 `panel = null`，关系与场景一起丢了。
- 改法：`matched == 0` 时仍产出面板（当前数值 + 新关系/场景），另加一个信号（例如
  `RpStateMergeResult.metricsMatched: Boolean`）让调用方继续走兜底。
  **先读完所有调用方**（`grep -rn "RpStateMerger.merge"`），确认它们原来靠 `panel == null`
  判断什么，不要让「有面板」被误当成「成功、不用兜底」。
- 兜底请求回来后再合并一次：兜底也失败时，关系与场景保持第一次的新值。
- 测试：`RpStateMergerTest` 补用例；`ChatRepositoryRpStateTest`（instrumented）补端到端用例。

### [x] RP-2c 主角改读档案（已完成，未提交）

**已做**：
- `PromptContext`、`RpMetadataPromptContext`、`ChatContextInput` 新增 `protagonistProfile`
  （配角字段**没加**，留给 2e，避免空管道）。
- `RpTurnConfigurationSnapshot` 在同一事务里读主角档案（RP 启用且绑定时），`RpTurnSnapshot` 带着它；
  正文请求与元数据补写都用本轮冻结的档案。压缩路径没有轮次快照时现查（`loadProtagonistProfile`）。
- 上下文估算额外观察 `observeProtagonistProfiles()`，改档案后面板 token 数会刷新。
- `PromptComposer`：主角段读档案的名称、简介、非空文本词条；「视角规则」改看档案；
  元数据 prompt 同样读档案，原来固定的「初始状态」行改为逐条输出文本词条。旧三字段 prompt 不再读。
- 测试：`PromptComposerTest` 新增 5 项。

**遗留问题（已解决，用户 2026-09-23 选 (a)）**：迁移时旧主角名字为空的会话，档案名原先被填成
「主角1」「主角2」，2c 之后会作为「主角名称: 主角1」发给模型。现在：
- 迁移不再填占位名（删掉 `RpMigrationV10.protagonistNameOrFallback`），名字原样留空。
- `saveProtagonistProfile` 放宽为：名字可以为空，但名称、简介、有内容的词条（数值词条算有内容）不能全空。
- 名字为空时正文 prompt 不发名称行，元数据 prompt 写「未设定，视为玩家本人」，与 v9 一致。
- **RP-3 做档案列表时必须给空名字兜底显示（如「未命名主角」）**；RP-2 没有任何列表显示档案名，所以没加。
- 测试：`RpMigrationV10Test` 删掉占位名断言；`ChatDatabaseMigrationTest` 改为断言空名字；
  `RpBindingTransitionTest.protagonistProfile_allowsBlankNameButNotEmptyProfile`。

以下是原任务描述，留作对照。


- `PromptContext`（`PromptComposer.kt:24`）加 `protagonistProfile: ProtagonistProfile?` 和
  `supportingCharacters: List<SupportingCharacter>`（2e 用，先加上）。
- `ChatRepositoryImpl` 组装 turn 快照的地方按 `rpSettings.protagonistProfileId` 读档案、
  按 `getConversationSupportingCharacters` 读配角，放进 turn，和角色卡一起读（同一个快照时刻）。
- `PromptComposer` 三处读旧三字段（约 `:333-341`、`:439-452`、`:583-585`）改为读档案：
  名称、描述、文本词条逐条列出。档案为空时维持现有的「未设定，视为玩家本人」。
- 旧三字段 `protagonistName/Description/InitialState` 之后只作取证材料，prompt 不再读。
- 测试：`PromptComposerTest` 覆盖有档案 / 无档案 / 档案只有文本词条。

### [x] RP-2d RP 设置界面的主角三个输入框（已完成，未提交）

**用户 2026-09-23 选定建议方案。已做**：
- `updateConversationRpSettings` 的 `protagonistName/Description/InitialState` 改为编辑
  `protagonistProfileId` 指向的**档案本身**（`RpRepositoryImpl.applyProtagonistTextEdit`，同一事务）：
  - 已绑定：改名称、简介与第一条「初始状态」文本词条（空值删掉该词条，没有就追加），其它词条原样保留；
    内容没变时不写库（不刷新 `updatedAtMillis`）。
  - 改完档案什么都不剩：本会话解绑，档案本身保留（可能还有别的会话在用）。
  - 未绑定且有内容：新建档案并绑定，走 `applyBindingTransition`；全空则不建。
  - 参数为 null 表示该项没改；三个都为 null 不动档案。**v9 旧三列不再写入**，原样保留。
- Sheet 只把**改过的**项传下去（没改的传 null），不会用打开时的旧值覆盖档案。
- `ChatUiState.activeProtagonistProfile`（ViewModel 合并观察设置与档案列表）；Sheet 初值读档案。
- 输入框下方的说明：已绑定时写明「共用该档案的会话文字会一起变，数值不受影响」、
  另有 N 条词条未显示（仍生效）、三项清空后解绑；未绑定时写明「保存会新建档案」。
- 常量 `ProtagonistProfile.INITIAL_STATE_ENTRY_NAME`（仓库、UI 共用；迁移里保留自己的常量，迁移代码不跟着 App 常量变）。
- 测试：`RpBindingTransitionTest` 新增 5 项（新建并绑定 / 全空不建 / 原地改共用档案且保留其它词条与快照 /
  没改不写库 / 清空后解绑）。

**没做**：影响会话数不显示具体数字（需要新 DAO 查询，RP-3 做档案管理时一起做）。

**遗留问题**：
- 打开 Sheet 期间，档案被别处（设定生成工具）改了，用户又改了同一项，保存时以用户输入为准。概率很低，未处理。
- androidTest 只编译，未在设备上运行（红线：先问用户）。

以下是原任务描述，留作对照。

2c 之后，RP 设置 Sheet（`RpSettingsSheet.kt`、`ChatScreen.kt:418-465`、
`ChatViewModel.kt:842-889`）编辑的旧三字段就不再生效了，用户会发现「改了没用」。
RP-3 才做档案选择器，所以 RP-2 必须有过渡方案。我建议的方案（**动手前请用户确认**）：

- 三个输入框改为编辑**当前绑定的主角档案**：名称、描述、名为「初始状态」的文本词条。
- 没绑定档案且输入框非空时，新建一个档案并绑定（走 `applyBindingTransition`）。
- 注意：多个会话可能绑定同一档案（工具建的档案可以复用），在这里改档案会影响所有会话的
  文本部分（数值部分有快照保护，不受影响）。要在输入框下方写明。

### [x] RP-2e 数值按组输出与配角进正文（已完成，未提交）

**已做**：
- 前缀规则集中为 `RpStateBlockFormat.PREFIX_RULE`（「name 带归属前缀，必须原样照抄，不去掉也不另加」），
  正文 prompt、元数据补写 system prompt、状态兜底 system prompt 三处共用。
- 正文 prompt 的「状态栏定义」按快照分组：`#### <角色卡名>的状态条（前缀「角色::」）`、
  `#### 主角（玩家）的状态条（前缀「主角::」）`，组内各自编号；顺序沿用快照（角色组在前）。
  没有定义时的文案从「当前角色卡没有定义状态条」改为「当前没有定义状态条」（为 2f 铺路）。
- 配角：`PromptContext.supportingCharacters`，`buildRpInstructions` 在角色卡段（及视角规则）之后、
  世界观之前输出「### 配角」段：每人 `#### 名称` + 简介 + 非空文本词条；说明「不要为配角新增状态条」。
  名称、简介、词条全空的配角跳过。**不进状态块、不进元数据、不进兜底**；`inner_thought` 不变。
- 数据链路：`RpTurnConfigurationSnapshot.supportingCharacters` 与设置、主角档案同一事务读出（只在 RP 开启时）；
  `RpTurnSnapshot` 冻结后用于正文请求；无轮次快照的路径现查（`loadSupportingCharacters`）。
- 上下文规划：`ChatContextInput.supportingCharacters` → `PromptContext`，配角文本落在
  `ComposedPrompt.rpInstructions` 里，计入 RP 固定成本；响应式估算额外观察
  `observeConversationSupportingCharacters(conversationId)`（勾选变化与配角内容修改都会触发）。
- 测试：`PromptComposerTest` 新增 4 项（分组 + 前缀规则 + 示例与当前状态带前缀 / 无定义文案 /
  配角位置与内容 / 配角不进元数据与普通聊天）；`ChatContextPlannerTest` 新增 1 项；
  `ChatRepositoryRpStateTest.supportingCharacters_goIntoMainRequestOnly`（只编译，未上设备）。

**偏离**：「当前状态」（每轮附在最后一条 user 消息后的易变段）**没有加分组标题**。
那段每轮都发，名字本身已带前缀、顺序已按组排好，再加标题只是每轮多几十 token。

**没做**：配角数量阈值与预计占用提示（§6.2，属 RP-3 的 UI）。

**遗留问题**：
- 配角的增删与会话勾选在 RP-2 没有 UI；真机上只能等 RP-3，或靠测试插数据验证。
- 一个超长配角会让 RP 固定成本超预算，此时按现有规则本地报 `overflowError`，发送失败——符合 §6.2，但 UI 提示要等 RP-3。

以下是原任务描述，留作对照。


- `buildCurrentMetricLines`（`PromptComposer.kt:539`）、状态块示例、定义说明按「角色」「主角」
  两组输出，并在 RP 规则里讲清楚前缀规则：状态块里的 `name` 必须原样带前缀。
- 配角：`buildRpInstructions` 在角色卡段之后加「配角」段（名称 + 描述 + 文本词条）。
  **配角只进正文 prompt，不进状态块，不进元数据**；`inner_thought` 仍然只写角色卡主角色。
- 上下文规划：新加的段落要计入 `ComposedPrompt` 的分项原文，否则占用统计不准（§6.2）。
- 数据来源：配角的增删和会话关联在 RP-2 没有 UI（RP-3 做），测试直接用 DAO 插数据。

### [x] RP-2f 无角色卡路径（§11.1）（已完成，未提交）

**判据**：状态链路生效 = RP 开启 **且绑了角色卡或主角**（`stateActive`）。只新增「无角色卡、有主角」
这一种情况；**两者都没绑的 RP 仍然不记状态**，与之前一致（否则回滚会给这类会话写出空面板，UI 冒出一张空卡）。

**逐个判断的结果**：

| 位置 | 原判断 | 现在 |
| --- | --- | --- |
| `ChatDao.getRpTurnConfigurationSnapshot` 的 `statusPanel` | 有角色卡 | 有角色卡或主角 |
| `captureRpTurnSnapshot` 的 `definitions`、`resolveRpStatusFromSnapshot(isRpActive)` | 有角色卡 | `stateActive` |
| `planContextWindow` / `buildRequestContextInput` 的状态面板 | 有角色卡 | 有角色卡或主角（估算与请求一致） |
| `PromptComposer.metricSnapshot` | 有角色卡 | 有角色卡或主角 |
| `prepareAssistantMessage` 的 `needsEnrichment` | 有角色卡 | 有角色卡，**或**数值本轮没更新（只跑兜底） |
| `requestRpMessageMetadata` 的 `roleCard ?: return null` | — | **保留**：内心独白与剧情建议是角色卡人物的，没有角色卡就不发元数据请求 |
| `retryLatestRpOptions` 的 `require(turn.roleCard != null)` | — | **保留**，理由同上；UI 的重试入口本来就要求有角色卡 |

- 删角色卡（RP-1 实施记录第 5 条）：**会话还绑着主角时 RP 保持原开关**，两者都没了才关（按建议）。
- 测试：`RpBindingTransitionTest.deletingRoleCard_keepsProtagonistGroup` 断言改为 RP 保持开启；
  `ChatRepositoryRpStateTest` 新增 2 项（只绑主角：正文状态块更新面板且整轮只 1 次请求 /
  正文没状态块时只跑兜底，共 2 次请求）；`PromptComposerTest` 新增 2 项（只绑主角发主角组 /
  两者都没绑不发快照里残留的定义）。instrumented 只编译，未上设备。

**遗留问题**：
- 只有主角的会话没有内心独白与剧情建议（元数据需要角色卡）。要支持得另写一版元数据 prompt，未做。
- 「未更新状态栏」在只有主角时同样会出现（兜底失败时），文案里的「状态栏」不区分归属，可接受。

以下是原任务描述，留作对照。


- 目标：角色卡没有数值、只有主角有数值时，状态链路照常工作。
- 需要放开的 `roleCard != null` 判断：`ChatRepositoryImpl.kt:346`、`:1318`、`:1892`、
  `:2045`、`:2049`，以及 `PromptComposer.kt:566`。**逐个判断**哪些真的只该看「是否启用 RP」，
  哪些确实需要角色卡（比如 `inner_thought`）。
- RP-1 实施记录第 5 条：删角色卡目前仍会关闭 RP，放开后要重新决定（建议：还有主角就不关）。
- 这条改动面最大，放在最后做；如果时间不够，可以和用户商量挪到 RP-3。

### [x] RP-2g 设定生成工具的数值适配（§11.2）（已完成，未提交）

**核实结果**：已被 RP-1 覆盖。工具协议不变，`SettingsToolArgumentParser` 仍产出旧形状的
`RoleCardDraft.metricDefinitions`；保存走 `saveRoleCard` → `RoleCardDraft.toEntries()`，
转成 `RpEntry.Numeric`，**id 原样保留**；绑定走 `saveGeneratedSettings` → `nextMetricSnapshot`，
快照 wireName 带「角色::」。草稿展示（`SettingsDraftSheet.MetricSummary`）读旧形状，无需改。

**补了一处缺口**：模型给的状态条名若含「::」，仓库保存会整笔拒绝，而草稿页的状态条只读、用户改不了，
只能丢掉整份草稿。现在解析时把「::」换成「·」并在草稿警告里写明（名字是模型起的，不适用
「用户起的名字不静默修正」那条）。替换在去重之前做，替换后撞名按重复处理。

**测试**：`SettingsToolArgumentParserTest` 新增 2 项（旧 DTO → Numeric 保留身份 / 分隔符替换并提示）；
`RpBindingTransitionTest.generatedRoleCardMetrics_areSavedAsNumericEntriesAndSnapshotted`（只编译）。

**顺手修正**：`SettingsToolArgumentParser` 里两处 RP-1 前遗留的过时注释（「主角没有独立实体」「与 RpRepositoryImpl 截断一致」）。

以下是原任务描述，留作对照。


- 核实 `SettingsToolArgumentParser.kt:63` 解析出的 `metricDefinitions` 在保存角色卡时
  是否已经转成 `RpEntry.Numeric`（RP-1 的 `RoleCardDraft.toEntries()` 可能已经覆盖了）。
  已覆盖就补一条测试然后把 §11.2 标完成；没覆盖再补适配。

### [x] RP-2h 真机验收（2026-09-24 完成，角色组通过）

**结果**（会话由设定生成工具建卡后在同一会话开启 RP；角色卡 1 条数值「根系共鸣」，主角档案只有文本词条）：

| 轮次 | 供应商 | 请求数 | 数值 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | Grok（grok-4.7-build-fast） | 正文 + 元数据 | 20（初始） | 通过 |
| 2（重新生成） | DeepSeek | 正文 + 元数据 | 20 → 22 | 通过 |
| 3 | DeepSeek | 正文 + 元数据 | 22 → 22 | 通过 |
| 4 | DeepSeek | 正文 + 元数据 | 22 → 42 | 通过 |

- 两个模型每轮都原样写出 `角色::根系共鸣`，4 轮都**没有发兜底请求**、没有「未更新状态栏」提示。
- 面板、每条消息的状态快照一致；关系与场景保留；元数据每轮解析成功（3 条建议）。
- 面板显示「根系共鸣」，不露前缀（2a）。

**未覆盖**：`主角::` 组——当前 UI 与工具都无法给主角加数值词条，真机测不了，要等 RP-3 的词条编辑器；
Grok 只跑了 1 轮。

**验收中顺带修复的回退**：在会话里编辑本会话绑定的角色卡后，面板不变。原因是 R6 冻结后同步入口
`syncMetricDefinitionsFromProfiles` 没有任何 UI 调用，而 `ChatViewModel.saveRoleCard` 用同一个
`roleCardId` 重新绑定，绑定没变就不重拍快照，却提示「已应用到当前会话」。现在改为：编辑的是当前会话绑定的卡时，
对**当前会话**调用一次同步（按 entryId 保值），其它绑定该卡的会话仍冻结。真机确认面板随之更新。

以下是原任务描述，留作对照。


计划第 9 节 RP-2 的验收：Grok 与 DeepSeek 各跑一轮，看模型能不能稳定原样写出带前缀的名字。
不稳定时的退路见 §3.3（改传短键）。

## 3. 红线

1. **不要在用户手机上直接跑 `connectedDebugAndroidTest`。** 测试执行器跑完会卸载应用，清空全部数据
   （2026-09-23 已经发生过一次）。必须加
   `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`，并先征得用户同意。
2. **RP-2 不改数据库版本。** 如果发现必须改 schema，先停下来问用户。
3. 不在日志里输出完整 prompt、角色内容、密钥。
4. 提交只在用户要求时做；提交信息不得包含任何 AI 署名或共同作者 trailer（见用户的 CLAUDE.md）。
5. Windows 上不要让 shell 的当前目录停在 `app/build` 下面，否则 gradle 清理时会报 FileSystemException。

## 4. RP-2 完成后：恢复用户数据

用户的 v9 数据库备份（只读）：`C:\Users\atlas\Desktop\LLMChat-db-backup\2026-09-23-v9-before-RP1\`。

1. 用 `installDebug` 装最终包（**不要**用 connected test 装）。
2. 先不要启动应用；`adb push` 到 `/data/local/tmp`，再用 `adb shell run-as com.muxuechat cp` 复制进 `databases/`
   （含 `-wal` / `-shm` 的话一起推，或者先确认备份已经 checkpoint）。
3. 启动应用，首次打开会从 v9 迁移到 v10。检查会话、消息、角色卡、RP 设置的数量：
   10 个会话 / 36 条消息 / 2 张角色卡 / 2 条 RP 设置。
4. 设置、API Key、xAI 登录已经丢失，需要用户手动重新填写。

**执行记录（2026-09-23 05:15～05:16）**：
- 手机上当时已有 04:09 安装的版本（1 会话 / 6 消息 / 1 角色卡，用户已重新配置设置与 xAI 登录）。
  恢复前把它的 `databases/`、`files/datastore/`、`shared_prefs/secure_*.xml` 备份到
  `C:\Users\atlas\Desktop\LLMChat-db-backup\2026-09-23-v10-before-restore\`。
- `installDebug` → `am force-stop` → 推 v9 三个文件并替换 → **注意：备份文件是只读的，复制后要 `chmod` 成可写**，
  否则 Room 写不了库 → 启动 App 完成迁移。
- 拉回核对：user_version 10；10 会话 / 36 消息 / 2 角色卡 / 2 条 RP 设置，与预期一致；
  生成 2 个主角档案且都已绑定、无空名字；2 条快照；面板状态名全部带前缀；`integrity_check` ok，外键检查无问题。
- 只替换了数据库，设置、API Key、xAI 登录保持用户重新配置后的状态。
- 测试在 `shared_prefs/` 留下若干 `test-*-credentials-*.xml`、`databases/` 留下几个 `room-migration-*.lck` 空文件，
  是测试残留，App 不读它们；未删除。

## 5. 收尾

（2026-09-24：2h 真机验收完成，计划文档进度表与「RP-2 实施记录」已更新，本文已标注「已完成」。）

- 更新 `docs/RP_ACTOR_PLAN.md` 顶部的进度表，并在第 9 节 RP-2 下写「RP-2 实施记录」
  （做了什么、偏离了什么、没做什么）。
- 本文完成使命后可以删掉，或者在顶部标注「已完成」。
