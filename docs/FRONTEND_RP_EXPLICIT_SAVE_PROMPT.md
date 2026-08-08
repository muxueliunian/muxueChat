# 前端增量任务 Prompt：RP 设置显式保存

> 状态：已实现（截至 2026-04-25）。本文保留为历史实现提示，不再作为待办清单；当前完成情况以 `docs/ROADMAP.md` 为准。

你是 muxueChat 项目的前端/UI Agent。请先读取根目录 `AGENT.md`，并严格遵守项目规范。本轮任务只修改 `presentation` 层；除非编译必须，不要修改 data/domain/remote 层。

## 背景

RP 基础功能已经完成：角色卡、世界观、状态栏定义、状态面板展示都已接入。现在需要优化 RP 设置 Sheet 的交互：用户在 Sheet 里切换 RP、选择角色卡、选择世界观时，不要立即保存，而是先形成本地草稿，点击“保存”后再一次性提交。

后端已经支持：

- 当前会话绑定带状态栏定义的角色卡后，会立即创建初始状态面板。
- 新建角色卡时，如果当前会话已经开启 RP，会自动应用到当前会话。
- 模型回复没有返回可解析状态块时，会保留上一次状态面板，并通过 snackbar 提示“本次回复未更新状态栏，已保留上一次状态。”

## 目标

把 `RpSettingsSheet` 从“实时点选保存”改成“本地编辑 + 显式保存”。

## 需要实现

1. Sheet 打开时，用当前状态初始化本地草稿：

```kotlin
draftRpEnabled = state.activeRpSettings?.isRpEnabled == true
draftRoleCardId = state.activeRpSettings?.roleCardId
draftWorldSettingId = state.activeRpSettings?.worldSettingId
```

如果项目里已有对应参数名，请沿用现有命名，不强行照搬。

2. 用户操作只更新草稿：

- 切换 RP 开关：只更新 `draftRpEnabled`
- 选择角色卡：只更新 `draftRoleCardId`
- 选择世界观：只更新 `draftWorldSettingId`
- 点“不绑定角色卡 / 不绑定世界观”：只把对应 draft 置为 null

不要在这些点击事件里调用：

```kotlin
updateActiveConversationRpSettings(...)
```

3. 在 Sheet 底部增加操作区：

- “取消”：关闭 Sheet，不保存草稿。
- “保存”：调用一次现有上层回调：

```kotlin
onUpdateRpSettings(
    draftRpEnabled,
    draftRoleCardId,
    draftWorldSettingId,
)
```

如果现有 UI 参数叫 `onRpSettingsChange` 或其他名字，请使用现有名字，不要为了命名重构调用链。

4. 保存后关闭 Sheet。

5. 保存按钮状态：

- 推荐：只有草稿和当前设置不一致时启用。
- 如果判断不方便，可以保持可点击，但必须保证选择行点击本身不触发保存。

6. 文案调整：

把 Sheet 顶部说明改为类似：

```text
为当前会话绑定角色卡与世界观。点击保存后应用到当前会话，下一次发送消息时生效。
```

7. 视觉状态：

- 角色卡/世界观列表的选中态必须基于 draft 值。
- RP 开关的 checked 状态必须基于 draft 值。
- 进入 Sheet 后，如果后端状态刷新导致当前设置变化，可以用 `LaunchedEffect` 或重新打开时同步草稿，避免展示旧选择。

## 注意事项

- 不要改 DeepSeek 请求逻辑。
- 不要在 UI 层拼 RP prompt。
- 不要在 UI 层解析 `<muxue_state>`。
- 不要直接访问 Room、DataStore、Keystore 或网络。
- 不要显示 `rawJson`。
- 保持现有 Claude-inspired 暖色、低对比、文本优先风格。
- 小屏不能横向滚动，按钮触控区域尽量不小于 44dp。

## 建议检查文件

- `app/src/main/java/com/muxuechat/presentation/chat/ChatScreen.kt`
- `app/src/main/java/com/muxuechat/presentation/chat/components/RpSettingsSheet.kt`
- 如有必要，检查 `app/src/main/java/com/muxuechat/presentation/AppNavigator.kt`

## 验收

- 打开 RP Sheet，切换开关/选择角色卡/世界观时不会立即弹出“RP 设置已更新”。
- 点击“取消”后，重新打开 Sheet，应恢复到保存前的当前设置。
- 点击“保存”后，才应用 RP 设置并关闭 Sheet。
- 保存带状态栏定义的角色卡并绑定后，聊天页顶部能立即出现初始状态面板。
- 普通聊天、角色卡编辑、世界观编辑不受影响。
- `.\gradlew.bat :app:assembleDebug` 通过。
