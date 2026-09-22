# muxueChat RP 角色体系重构计划（配角、主角实体化、词条与可编辑数值）

状态：设计阶段，未实施。**排在 xAI 链路之后开工**（用户 2026-09-22 确认）。
建立日期：2026-09-22
范围：新增配角实体；主角从三个字符串升格为独立实体；统一词条概念；
状态面板支持手动编辑数值
不在范围：多角色同场（群聊）、关键词触发世界书、角色图片、角色卡导入导出、长期记忆总结

## 进度记录（接续工作先读这里）

| 阶段 | 状态 | 阻塞于 |
| --- | --- | --- |
| 需求确认 | 完成（R1～R9） | — |
| 现状核对 | 完成，见第 2 节 | — |
| 本计划评审 | 未开始 | — |
| RP-1 数据层 | 未开始 | xAI 链路收尾 |
| RP-2 Prompt 与状态块 | 未开始 | RP-1 |
| RP-3 UI | 未开始 | RP-2 |

**Room 版本冲突**：本计划要占用 **v10**。`docs/MULTIMODAL_PLAN.md` 原本把 v10 分配给
`message_attachments` 表，需改为 **v11**。两条线必须串行，当前顺序是
xAI → 本计划 → MM-1a。实施本计划时需同步修订 `MULTIMODAL_PLAN.md`。

## 1. 已确认的需求（2026-09-22）

| # | 决策 |
| --- | --- |
| R1 | 配角是**独立实体**，不绑定角色卡或世界观，会话上自由勾选搭配 |
| R2 | 配角只有 `name` + `description` + **文本词条**，无数值，不进状态面板 |
| R3 | 主角从 `ConversationRpSettings` 的三个字符串**升格为独立实体**，并拥有数值定义 |
| R4 | 角色卡、主角、配角共享**词条（`RpEntry`）**概念，但不合并成同一个类 |
| R5 | 状态块用**前缀命名空间**区分归属（`角色::好感度` / `主角::体力`），不改 JSON 结构 |
| R6 | 数值定义在绑定会话时**快照**进会话，之后改档案不影响进行中的会话 |
| R7 | 主角是**会话级选择**，并记住上次选过的作为新会话默认值 |
| R8 | 手动编辑数值用**文字输入**，不用拖动进度条（进度条只做展示） |
| R9 | 手动编辑是「修正当前值」而非「锁定」，模型下一轮仍可改动该值。因此**不引入 `updatedBy`** |
| R10 | 定义快照存 `conversation_rp_settings`，不存 `rp_status_panels`。理由见 3.4 |
| R11 | 前缀分隔符用 **`::`**，不用 `.`。理由见 3.3 |
| R12 | 配角数量**不设硬上限**，改为显示已选数量与预计占用，超阈值给警告但不阻止。理由见 5.1 |
| R13 | 主角**不新增角色卡的专属字段**，需要时用文本词条表达。理由见 3.2 |

关于 R9 的机制说明（已核实）：`PromptComposer.buildCurrentMetricLines` 从
`panel.metrics` 按 name 取当前值写进 prompt。手动编辑只要落库到 `rp_status_panels`，
下一轮请求就会带上修改后的值。但模型仍可在其状态块中输出新值并覆盖它，这是预期行为。

## 2. 现状核对（实测，2026-09-22）

**现有 RP 相关表**（`data/local/ChatEntities.kt`）：

```text
role_cards、world_settings、writing_style_profiles
conversation_rp_settings、rp_status_panels、message_rp_metadata
```

关键事实：

- `RoleCard` 字段：`id`、`name`、`description`、`persona`、`speakingStyle`、
  `userIdentity`、`openingMessage`、`boundaries`、`metricDefinitions`、两个时间戳。
- `ConversationRpSettings` 每个会话**只能绑一张角色卡**（`roleCardId: String?`，带外键）。
- 主角是三个普通字符串：`protagonistName` / `protagonistDescription` /
  `protagonistInitialState`，主代码中共 **52 处**引用。
- `rp_status_panels` **主键是 `conversationId`**，一个会话一条记录，
  一个扁平的 `metricsJson` 外加 `relationship` / `scene` / `rawJson`。
- `<muxue_state>` 的契约是 `{"metrics":[{name,value,min,max}],"relationship":…,"scene":…}`，
  **一个扁平数组**，目前只对应角色卡的 `metricDefinitions`。
- `RpStatusPanelCard` **完全只读**，没有任何编辑控件。
- `RpStateBlockFormat.currentValueOf` 按 **name 匹配**取值——这是现有隐患：
  改了数值名称，历史值就丢失。R6 的快照机制同时修掉它。
- 世界观（`WorldSetting`）只有 `title` + `content` 两个字段，是一段自由文本。
  这正是「世界书写不了那么多」的来源。

## 3. 目标数据模型

### 3.1 词条（共享概念，R4）

```kotlin
sealed interface RpEntry {
    val name: String

    /** 静态档案信息：年龄、身份、与主角的关系…… */
    data class Text(
        override val name: String,
        val value: String,
    ) : RpEntry

    /** 数值，进状态面板并显示进度条 */
    data class Numeric(
        override val name: String,
        val minValue: Int = 0,
        val maxValue: Int = 100,
        val initialValue: Int = 0,
        val description: String = "",
        val progressRule: String = "",
    ) : RpEntry
}
```

现有 `RpMetricDefinition`（`id`/`name`/`minValue`/`maxValue`/`initialValue`/
`description`/`progressRule`）就是 `Numeric` 的雏形，按它改造，保留 `id`。

**不引入 `updatedBy`**（R9）。

### 3.2 三类角色

**不合并成同一个类。** 理由：字段需求差异太大，合并会让配角拖着一堆永远为空的字段，
UI 与 prompt 拼装到处要判「这个字段对这个类型有没有意义」。
业界也是这样：Character Card V3 规范里根本没有玩家角色（persona）的定义，
persona 是客户端概念，字段远少于角色卡。

| | 角色卡 | 主角 | 配角 |
| --- | --- | --- | --- |
| `name` / `description` | ✓ | ✓ | ✓ |
| `entries`（文本 + 数值） | ✓ | ✓ | **仅文本** |
| `persona` / `speakingStyle` | ✓ | — | — |
| `userIdentity`（角色眼中的玩家） | ✓ | 无意义 | — |
| `openingMessage`（开场白） | ✓ | 无意义 | — |
| `boundaries` | ✓ | — | — |

**主角不新增专属字段（R13）**：

- `userIdentity` 按定义就是「**这张角色卡**眼中的玩家身份」，挂到主角上没有意义。
- `openingMessage` 是角色卡说的第一句话，主角不说开场白。
- `persona` / `speakingStyle` 看似对主角也成立（尤其当 `RpNarrativePerspective` 让模型
  代叙主角动作时），但这类需求**用文本词条表达即可**（例如一条名为「说话风格」的
  `RpEntry.Text`）。这正是引入通用词条的目的——避免为每种角色再长一套专属字段。

新增表：

```text
supporting_characters   id, name, description, entriesJson, createdAt, updatedAt
protagonist_profiles    id, name, description, entriesJson, createdAt, updatedAt
conversation_supporting_characters   conversationId, supportingCharacterId, orderIndex
```

`conversation_rp_settings` 增加 `protagonistProfileId: String?`。
配角走关联表（多对多），角色卡与主角各绑一个。

`RoleCard.metricDefinitions` 迁移为 `entries`（文本词条初始为空）。

### 3.3 状态块契约（R5）

保持 `{"metrics":[{name,value,min,max}],"relationship":…,"scene":…}` 的形状不变，
只在 `name` 上加前缀：

```json
{"metrics":[
  {"name":"角色::好感度","value":62,"min":0,"max":100},
  {"name":"主角::体力","value":80,"min":0,"max":100}
],"relationship":"…","scene":"…"}
```

**分隔符用 `::`（R11）**，不用 `.`：

- `.` 在数值名里很可能自然出现（`Lv.`、`H.P.`、小数写法），用 `.` 会让校验频繁误伤，
  用户得为了系统限制去改自己的命名。
- `::` 几乎不会有人写进数值名，校验基本不会触发。
- 多一个字符对 token 的影响可忽略。
- SillyTavern 的宏语法（`{{getvar::name}}`）也用 `::`，该生态里模型见过这个形状。
- 代价：`::` 比 `.` 略不常见，模型复现的稳定度需真机验收（见风险 5）。

理由：状态块是**模型输出的契约**。改 JSON 结构会让长会话里新旧两种示例并存，
模型输出质量下降；而前缀只是字符串，模型无压力。SillyTavern 的变量系统同样是
扁平命名空间 + 前缀约定。

实现要求：

- 前缀分隔符集中定义为一个常量，且**必须校验用户自定义的词条名不含该分隔符**，
  否则提示改名。
- `RpStateBlockFormat` 负责加前缀与剥前缀，解析器只认带前缀的名字。
- **向后兼容**：旧会话的 `metricsJson` 里没有前缀，读取时一律按「角色」归属补前缀。
  该兼容逻辑需要单元测试覆盖。

### 3.4 快照（R6）

会话绑定角色卡/主角时，把当时的 `Numeric` 词条定义（name、min、max、initial、
description、progressRule）快照一份存进会话，之后：

- 改档案不影响进行中的会话；
- `currentValueOf` 改为按快照里的定义取值，不再直接引用档案；
- 新会话才使用最新定义。

**存放位置：`conversation_rp_settings` 新增一列 JSON（R10）**，不放 `rp_status_panels`。

理由：

- 语义上它是**会话配置**（这个会话在用哪套定义），不是运行时状态。
- `rp_status_panels` 每轮都被改写（新数值落库），而快照绑定后不可变。
  把不可变数据塞进高频改写的行，等于每轮重新序列化一遍快照，浪费且增加写坏的机会。
  这与 `docs/MULTIMODAL_PLAN.md` 中「附件不随高频更新的 `messages` 反复序列化」
  是同一条理由，保持一致。
- 联表成本为零：`PromptContext` 本来就同时持有 `rpSettings` 与 `statusPanel`，
  `currentValueOf` 取定义与取当前值各从一边拿，不需要新增读取路径。

## 4. Prompt 变更

- `buildRpInstructions` 在「角色卡」段之后新增「配角」段，逐个列出
  `name` + `description` + 文本词条。配角全部常驻（R2 场景下一个会话 3～4 个，可接受）。
- 主角段从现有三个字符串改为读主角实体，并列出其数值词条。
- `buildCurrentMetricLines` 改为同时输出角色卡与主角的数值，带前缀。
- `buildStateBlockExample` / `metricsJson` 同步带前缀。
- `buildRpMetadataSystemPrompt` 中 `inner_thought` **仍固定为主角色（角色卡）的心理活动**，
  配角不参与内心独白（用户 2026-09-22 确认）。
- 配角只影响正文叙事，不进 `<muxue_state>`，不进元数据。

## 5. UI 变更

### 5.1 配角数量的处理（R12）

**不设硬性上限。** 理由：真正的约束是上下文预算，而预算由
`domain/context/ChatContextPlanner.kt` 按模型真实窗口算出，一个写死的数字必然是武断的。
但放任多选而不给反馈，正是「世界书写不了那么多」这个痛点的来源。

做法：

- RP 设置 Sheet 的配角多选区显示「已选 N 个 · 预计占用 ~X tokens」，
  估算走现有 `HeuristicTokenEstimator`，文案必须标注「预计」（AGENT.md 既有要求）。
- 超过阈值（建议 6 个）显示警告文案，说明会挤占对话历史，**但不阻止选择**。
- 警告不能只靠颜色表达，要有文字（AGENT.md §9）。

### 5.2 其余 UI

- 状态面板（`RpStatusPanelCard`）：
  - 分「角色」与「主角」两组展示，各自带进度条；
  - 每个数值可点击，**弹出文字输入框**修改（R8），校验范围后落库；
  - 进度条只做展示，不可拖动。
- 配角管理：与角色卡、世界观同级的列表页，支持增删改。
- 主角管理：同上。原「主角档案」那几个输入框从 RP 设置 Sheet 中移除，
  改为选择一个主角实体。
- RP 设置 Sheet：角色卡、主角、世界观各选一个；配角多选。
- 编辑器沿用 `EditorSheet` + `rememberGuardedDismiss`（未保存草稿要确认）。
- 词条编辑：文本词条是 name + value 两个输入框；数值词条沿用现有
  `metricDefinitions` 的编辑形态。

## 6. 数据迁移（Room v10）

### 6.1 主角三字段 → 主角实体（R3）

现有每个会话的 `protagonistName` / `protagonistDescription` /
`protagonistInitialState` 需要转成 `protagonist_profiles` 记录：

- 三个字段**全为空**的会话：不生成记录，`protagonistProfileId` 留 null。
- 有内容的会话：生成一条记录，`name` 取 `protagonistName`（为空时用
  「主角」+ 序号），`description` 取 `protagonistDescription`，
  `protagonistInitialState` 转成一条名为「初始状态」的**文本词条**。
- **去重**：三个字段内容完全相同的会话合并到同一条记录（用内容哈希判定），
  避免产生大量重复档案。
- 迁移后保留原三列一个版本周期，确认无误再删，避免迁移出错无法回滚。

### 6.2 角色卡数值 → 词条

`role_cards.metricDefinitionsJson` 转成 `entriesJson`，每个 `RpMetricDefinition`
变成一个 `RpEntry.Numeric`，`id` 保留。

### 6.3 状态面板前缀补齐

`rp_status_panels.metricsJson` 中无前缀的 name 一律补「角色.」前缀。

### 6.4 迁移要求

- 必须导出 `10.json`，并用 `MigrationTestHelper` 做 `9.json → 10.json` 的真实升级测试。
- 迁移中的 JSON 解析必须容错：解析失败的记录按空处理，不得让整次迁移失败。

## 7. 分期

### [ ] RP-1：数据层

新增三张表与关联表；`RpEntry` 模型；`RoleCard` 改造；`ConversationRpSettings` 扩展；
DAO；Repository；Room v10 迁移与 schema 导出；快照机制。

验收：迁移测试通过；旧会话的主角数据正确转成档案且去重；
角色卡数值正确转成词条；无前缀的历史状态块被正确补前缀。

### [ ] RP-2：Prompt 与状态块

`RpEntry` 进 prompt；前缀加解；`buildCurrentMetricLines` 输出两组；
状态块示例与解析器；向后兼容；分隔符校验。

验收：单测覆盖带前缀/无前缀两种输入；角色卡与主角的数值互不串位；
配角正确进入正文 prompt 且不进状态块与元数据。

### [ ] RP-3：UI

状态面板分组与文字输入编辑；配角与主角的管理页；RP 设置 Sheet 改造；词条编辑器。

验收：手动改值后下一轮请求带的是改后的值（可用日志或上下文面板确认）；
配角多选上限与展示；空列表与删除后解绑的表现。

## 8. 测试计划

JVM 单元测试：

- `RpEntry` 的序列化与反序列化，含未知类型的容错。
- 前缀加解：往返一致；用户词条名含分隔符时被拒绝；无前缀输入按「角色」归属补齐。
- `buildCurrentMetricLines` 同时输出两组且不串位。
- 快照：改档案后进行中的会话取值不变；新会话取新定义。
- 主角迁移：空会话不生成记录；相同内容去重；`protagonistInitialState`
  转成文本词条。
- 配角进 prompt 的拼装；配角不出现在状态块与元数据中。
- 手动编辑：越界值被收敛到 min/max。

API 36 设备 instrumentation：

- `MIGRATION_9_10` 真实升级测试。
- 关联表的外键级联：删除配角时会话绑定被清理。

## 9. 风险与未决事项

1. **主角实体化牵动 52 处引用**，是本计划最大的一处改动面。建议 RP-1 中先保留
   旧三列并行一个版本，降低回滚成本。
2. **配角常驻 prompt 的成本**：按 R2 每会话 3～4 个可接受，R12 给出数量与预计占用提示。
   若用户后续习惯性勾很多，需要回头做关键词触发（即 ROADMAP 中「关键词触发世界书」，
   本计划不做）。
3. **模型对带前缀数值的遵从度**：`::` 让 name 变长且形状略不常见，Grok 与 DeepSeek
   是否都能稳定输出正确前缀**需真机验收**。若不稳定，退路依次是：
   prompt 中给更明确的示例 → 换回 `.` 并加强命名校验 → 最后才考虑改 JSON 结构。
6. **手动编辑会被模型覆盖**（R9 的预期行为）。若实际使用中感觉困扰，
   再考虑加回「锁定」语义，届时 `RpEntry.Numeric` 增加一个字段即可。
7. **与 MM-1a 的 Room 版本冲突**：本计划占 v10，多模态改占 v11，
   需同步修订 `docs/MULTIMODAL_PLAN.md`。

## 10. 给审查者的重点问题

R1～R13 均已确认，本计划希望被重点攻击的地方：

1. **三类角色不合并是否正确？** 共享 `RpEntry` 而非共享基类，是否会导致
   角色卡、主角、配角三条几乎相同的 CRUD/编辑器代码重复？重复到什么程度才值得抽？
2. **主角实体化的 52 处引用**是本计划最大的改动面。「保留旧三列并行一个版本」
   这个降风险手段是否足够？还是应该进一步拆期？
3. **前缀方案（R11）是否成立？** 模型每轮都要精确复现 `角色::好感度` 这种 name。
   有没有比前缀更稳的办法，且不破坏 `<muxue_state>` 的 JSON 结构？
4. **快照（R6/R10）的边界**：绑定时快照，那么用户在会话进行中**更换**角色卡或主角时，
   已有的数值怎么办？本计划没有写明，这是一个缺口。
5. **迁移去重（6.1）**：用内容哈希合并相同的主角档案，会不会把用户主观上不同、
   但恰好文字相同的档案错误合并？
6. **状态块向后兼容（3.3）**：旧数据补「角色::」前缀，但如果用户历史数值名里
   本来就含 `::` 会怎样？
7. **R9 的取舍**：手动编辑可被模型覆盖。这在实际 RP 体验中是否会成为主要抱怨来源，
   以致于一开始就该做「锁定」？
8. 第 8 节测试计划是否有遗漏，尤其是迁移与前缀这两块。
