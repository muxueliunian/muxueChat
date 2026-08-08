# 前端增量任务 Prompt：RP 结构化消息与历史决策改写

> 状态：已实现（截至 2026-04-25）。本文保留为历史实现提示，不再作为待办清单；当前完成情况以 `docs/ROADMAP.md` 为准。

你是 muxueChat 项目的前端/UI Agent。请先读取根目录 `AGENT.md`，并严格遵守项目规范。本轮任务主要修改 `presentation` 层；除非编译必须，不要修改 data/domain/remote 层。

## 背景

后端已为 RP 消息新增结构化元数据：

```kotlin
ChatMessage.rpMetadata: RpMessageMetadata?
```

其中：

```kotlin
data class RpMessageMetadata(
    val messageId: String,
    val conversationId: String,
    val innerThought: String,
    val options: List<String>,
    val statusPanelSnapshot: RpStatusPanel?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
```

后端会在 RP assistant 回复完成后，用一次小的非流式整理请求生成：

- 角色内心描述 `innerThought`
- 3 个玩家快捷选项 `options`
- 当时状态快照 `statusPanelSnapshot`

老消息或普通聊天消息可能没有 `rpMetadata`，前端必须兼容。

## 新增 ViewModel 能力

`ChatViewModel` 已新增：

```kotlin
fun editUserMessageAndRegenerate(messageId: String, content: String)
```

含义：

- 只用于编辑玩家消息。
- 后端会删除这条玩家消息之后的全部消息。
- 后端会把 RP 状态回滚到这条消息之前的最近状态快照。
- 然后自动重新生成 assistant 回复。

正在生成时不要允许用户触发历史编辑。后端也有保护，但 UI 应直接禁用交互。

## 目标

1. RP assistant 消息支持结构化展示：
   - 正文：保持原有 assistant 正文显示。
   - 角色内心：显示 `message.rpMetadata?.innerThought`。
   - 玩家选项：显示 `message.rpMetadata?.options`。

2. 玩家选项交互：
   - 点击选项主体：直接发送该选项文本，等同于用户输入后发送。
   - 点击选项最右侧编辑按钮：把选项文本填入底部输入框，不发送，让用户自行修改。

3. 历史玩家消息可改写：
   - 只允许点击玩家消息气泡进入编辑。
   - `state.isGenerating == true` 时禁用点击，不打开编辑。
   - 编辑弹窗/Sheet 预填原玩家消息。
   - 明确提示：“重新发送会删除这条消息之后的所有回复与决策，并回滚 RP 状态。”
   - 确认后调用 `editUserMessageAndRegenerate(message.id, editedText)`。

## 具体 UI 建议

### Assistant 消息

在 `MessageItem` / `AssistantMessage` 中：

- 原正文继续使用现有排版。
- 如果 `rpMetadata?.innerThought` 非空，在正文下方加一个轻量区块：
  - 标题：“内心”
  - 可默认折叠，或者弱显示；第一版默认展开也可以。
  - 视觉保持低对比，不要抢正文。

- 如果 `rpMetadata?.options` 非空，在操作行上方或下方展示 3 个选项：
  - 每个选项是一行。
  - 左侧/主体显示选项文本，点击直接发送。
  - 右侧一个编辑图标按钮，点击填入输入框。
  - 选项行触控高度不小于 44dp。
  - 长文本最多 2 行，超出省略或自然换行，但不能撑爆布局。

推荐新增回调：

```kotlin
onSendOption: (String) -> Unit
onEditOption: (String) -> Unit
```

并从 `ChatScreen` 传到 `MessageList` 再传到 `MessageItem`。

`onSendOption(option)` 可以直接调用现有 `onSendMessage(option)`。

`onEditOption(option)` 应把底部输入框 `inputValue` 设为 `TextFieldValue(option)`，不发送。

### 玩家消息历史编辑

在 user 气泡上加点击行为：

- 当 `state.isGenerating == false` 时可点击。
- 点击后打开确认编辑 Sheet/Dialog。
- 文本框预填原消息。
- 确认按钮文案：“重新发送”。
- 取消按钮文案：“取消”。
- 确认前要求内容非空。

推荐新增回调：

```kotlin
onEditUserMessage: (messageId: String, newContent: String) -> Unit
```

从 `AppNavigator` 接到：

```kotlin
viewModel.editUserMessageAndRegenerate(messageId, newContent)
```

## 边界情况

- 普通聊天 / 老消息：`rpMetadata == null`，只显示原正文，不显示内心和选项。
- `innerThought` 为空：不显示内心区块。
- `options` 少于 3 个：显示已有选项即可，不要前端硬补。
- 正在生成：选项可以禁用或保持发送禁用；历史编辑必须禁用。
- 点击选项发送后，按普通发送路径走，不要特殊处理。
- 编辑历史消息后，后端会删除后续消息并重新生成，前端不需要手动删除列表。

## 禁止事项

- 不要在 UI 层拼 RP prompt。
- 不要在 UI 层请求模型。
- 不要在 UI 层解析状态 JSON。
- 不要直接访问 Room、DataStore、Keystore 或网络。
- 不要显示 `statusPanelSnapshot.rawJson`。
- 不要记录角色卡、完整消息正文或 API Key 到日志。

## 验收

- RP assistant 回复下方能看到“内心”和 3 个选项。
- 点击选项主体能直接发送并推进剧情。
- 点击选项编辑按钮能把选项填入输入框，不自动发送。
- 非生成状态下，点击玩家消息可编辑并重新发送。
- 编辑历史玩家消息后，后续消息消失并重新生成，状态栏回滚后再随新回复更新。
- 生成中点击玩家消息不会打开编辑。
- 普通聊天和没有元数据的旧消息显示正常。
- `.\gradlew.bat :app:assembleDebug` 通过。
