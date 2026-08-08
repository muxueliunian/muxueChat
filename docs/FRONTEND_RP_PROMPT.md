# 前端 RP 功能实现 Prompt

> 状态：已实现（截至 2026-04-25）。本文保留为历史实现提示，不再作为待办清单；当前完成情况以 `docs/ROADMAP.md` 为准。

你是 muxueChat 项目的前端/UI Agent。请只修改 `presentation` 层，除非编译必须修复少量类型接线。修改前必须读取根目录 `AGENT.md`。

## 项目背景

- Android 16+ 原生 Kotlin + Jetpack Compose。
- UI 风格：Claude-inspired，暖色、安静、文本优先、低对比。
- 不使用 Claude / Anthropic 品牌素材。
- 当前后端已提供 RP 相关状态和操作，不要在 UI 层拼 DeepSeek prompt，不要直接访问 Room、DataStore、Keystore 或网络。

## 可用 ViewModel 状态

从 `ChatUiState` 读取：

```kotlin
val roleCards: List<RoleCard>
val worldSettings: List<WorldSetting>
val activeRpSettings: ConversationRpSettings?
val activeRoleCard: RoleCard?
val activeWorldSetting: WorldSetting?
val activeRpStatusPanel: RpStatusPanel?
```

含义：

- `roleCards`：全部角色卡。
- `worldSettings`：全部世界观。
- `activeRpSettings`：当前会话的 RP 开关与绑定关系，可能为空。
- `activeRoleCard`：当前会话绑定的角色卡，可能为空。
- `activeWorldSetting`：当前会话绑定的世界观，可能为空。
- `activeRpStatusPanel`：模型输出 `<muxue_state>` 后解析出的状态面板，可能为空。

## 可用 ViewModel 方法

在 `MuxueChatApp` 中把这些方法传给 UI：

```kotlin
viewModel.createConversation()
viewModel.saveRoleCard(id: String?, draft: RoleCardDraft)
viewModel.deleteRoleCard(roleCardId: String)
viewModel.saveWorldSetting(id: String?, draft: WorldSettingDraft)
viewModel.deleteWorldSetting(worldSettingId: String)
viewModel.updateActiveConversationRpSettings(
    isRpEnabled: Boolean,
    roleCardId: String?,
    worldSettingId: String?,
)
```

注意：

- `updateActiveConversationRpSettings` 在没有当前会话时会自动创建一个会话。
- 保存角色卡时，`id = null` 表示新增，传已有 id 表示编辑。
- 保存世界观同理。
- 所有成功/失败提示会进入 `state.errorMessage`，沿用现有 snackbar/toast 逻辑即可。

## 需要新增的 UI

### 1. RP 设置入口

建议在聊天页顶部设置图标附近增加一个不显眼的入口，或者在设置页增加 “角色扮演” 区块。

第一版推荐：在聊天页打开一个底部 Sheet 或独立页面。

入口展示：

- RP 开关。
- 当前角色卡名称，没有则显示“未选择角色”。
- 当前世界观标题，没有则显示“未选择世界观”。

### 2. RP 设置页面 / Sheet

需要包括：

- 当前会话 RP 开关。
- 角色卡选择列表。
- 世界观选择列表。
- “新建角色卡”按钮。
- “新建世界观”按钮。
- 对选中的角色卡/世界观提供编辑和删除入口。

调用：

```kotlin
onRpSettingsChange(
    isRpEnabled = trueOrFalse,
    roleCardId = selectedRoleCardId,
    worldSettingId = selectedWorldSettingId,
)
```

### 3. 角色卡编辑表单

字段：

```kotlin
RoleCardDraft(
    name = "",
    description = "",
    persona = "",
    speakingStyle = "",
    userIdentity = "",
    openingMessage = "",
    boundaries = "",
    metricDefinitions = emptyList(),
)
```

UI 文案：

- 角色名称
- 简介
- 人设描述
- 说话风格
- 用户身份
- 开场白
- 禁止事项 / 边界
- 状态栏定义

交互：

- `name` 必填；空时禁用保存。
- 大文本字段用多行输入。
- 状态栏定义支持新增、编辑、删除。
- 每个状态条包含：名称、最小值、最大值、初始值、状态含义、变化规则。
- 保存后关闭表单。
- 删除前二次确认。

### 4. 世界观编辑表单

字段：

```kotlin
WorldSettingDraft(
    title = "",
    content = "",
)
```

UI 文案：

- 世界观标题
- 世界观内容

交互同角色卡。

### 5. RP 状态面板展示

在聊天页消息列表上方或顶部栏下方显示一个可折叠小面板。

仅当：

```kotlin
state.activeRpSettings?.isRpEnabled == true &&
state.activeRpStatusPanel != null
```

展示：

- metrics：例如 好感度 42/100、欲望值 7/100，可用线性进度条。
- metrics 来自角色卡状态栏定义与模型返回值，前端只展示，不自行生成或修正。
- relationship：当前关系。
- scene：当前场景。

不要显示 `rawJson`。

## 设计要求

- 不要做花哨视觉，保持现有暖色、低对比、文本优先。
- 不要把页面做成营销页。
- 小屏不能横向滚动。
- 表单按钮触控区域不小于 44dp。
- 长文本必须可滚动，不要撑爆布局。
- 不要使用图片头像，第一版只做文本。

## 禁止事项

- 禁止在 UI 层拼接 RP prompt。
- 禁止在 UI 层解析 `<muxue_state>`。
- 禁止直接访问数据库或网络。
- 禁止记录角色卡完整内容到日志。
- 禁止改动 `data.remote` 的 DeepSeek 请求逻辑。

## 验收

- 可以新增角色卡并保存。
- 可以新增世界观并保存。
- 可以为当前会话开启 RP 并绑定角色卡/世界观。
- 发送消息后模型回复正常，不展示 `<muxue_state>` 原始块。
- 模型返回状态块后，聊天页展示状态面板。
- 关闭 RP 后普通聊天仍然正常。
- `.\gradlew.bat :app:assembleDebug` 通过。
