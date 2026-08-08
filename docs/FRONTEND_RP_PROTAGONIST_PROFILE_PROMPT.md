# 前端增量任务 Prompt：RP 主角档案配置与玩家视角选项

> 状态：已实现（截至 2026-04-25）。本文保留为历史实现提示，不再作为待办清单；当前完成情况以 `docs/ROADMAP.md` 为准。当前源码在本任务之外还新增了 RP 剧情正文最低字数 `narrativeMinChars` 设置。

你是 muxueChat 项目的前端/UI Agent。请先读取根目录 `AGENT.md`，并严格遵守项目规范。本轮任务主要修改 `presentation` 层；除非编译必须，不要修改 data/domain/remote 层。

## 背景

后端已在当前会话的 RP 设置中新增“主角档案”字段，用来明确玩家/主角是谁，避免模型把快捷选项写成角色卡角色的视角。

`ConversationRpSettings` 新增字段：

```kotlin
val protagonistName: String
val protagonistDescription: String
val protagonistInitialState: String
```

含义：

- `protagonistName`：玩家控制的主角名称。
- `protagonistDescription`：主角简介，例如身份、性格、背景、和角色的关系。
- `protagonistInitialState`：主角初始状态，例如当前处境、装备、情绪、身体状态、目标。

后端已经把这些字段注入 RP prompt 和 RP 元数据整理 prompt。`options` 会被要求从玩家/主角视角生成。

## ViewModel 能力

保留旧方法：

```kotlin
fun updateActiveConversationRpSettings(
    isRpEnabled: Boolean,
    roleCardId: String?,
    worldSettingId: String?,
)
```

新增重载：

```kotlin
fun updateActiveConversationRpSettings(
    isRpEnabled: Boolean,
    roleCardId: String?,
    worldSettingId: String?,
    protagonistName: String?,
    protagonistDescription: String?,
    protagonistInitialState: String?,
)
```

说明：

- 旧方法会保留已有主角档案。
- 新方法会保存主角档案。
- 传空字符串表示清空该字段。
- 传 `null` 表示保持原值；前端显式保存时建议传当前输入框内容，不要传 null。

## 目标

在 RP 设置 Sheet 中新增一个简单的“主角档案”配置区，并纳入显式保存流程。

## 需要实现

### 1. RP Sheet 草稿新增字段

打开 RP Sheet 时，从 `state.activeRpSettings` 初始化：

```kotlin
draftProtagonistName = state.activeRpSettings?.protagonistName.orEmpty()
draftProtagonistDescription = state.activeRpSettings?.protagonistDescription.orEmpty()
draftProtagonistInitialState = state.activeRpSettings?.protagonistInitialState.orEmpty()
```

如果当前项目已经实现了“本地草稿 + 保存按钮”，把这三个字段加入现有草稿即可。

### 2. 新增主角档案区块

放在 RP 开关下面、角色卡列表上面，或角色卡列表下面均可。推荐标题：

```text
主角档案
```

字段：

- 主角名称：单行输入。
- 主角简介：多行输入，建议 2-5 行。
- 初始状态：多行输入，建议 2-5 行。

占位文案建议：

- 主角名称：`例如：牧雪`
- 主角简介：`玩家控制的主角是谁，和角色是什么关系`
- 初始状态：`当前处境、情绪、目标、随身物品或能力`

### 3. 保存时调用新重载

RP Sheet 点击“保存”时调用：

```kotlin
onUpdateRpSettings(
    draftRpEnabled,
    draftRoleCardId,
    draftWorldSettingId,
    draftProtagonistName,
    draftProtagonistDescription,
    draftProtagonistInitialState,
)
```

如果现有 `ChatScreen` / `RpSettingsSheet` 的回调类型还是 3 个参数，需要扩展为 6 个参数，并在 `AppNavigator` 里继续传：

```kotlin
onUpdateRpSettings = viewModel::updateActiveConversationRpSettings
```

如果函数引用因重载无法推断，可以改成显式 lambda：

```kotlin
onUpdateRpSettings = { enabled, roleCardId, worldSettingId, name, description, initialState ->
    viewModel.updateActiveConversationRpSettings(
        enabled,
        roleCardId,
        worldSettingId,
        name,
        description,
        initialState,
    )
}
```

### 4. 选项视角 UI 文案

已有的选项展示逻辑不用特殊改，但建议在 UI 上把选项区标题命名为：

```text
你的选择
```

不要叫“角色选项”，避免视角混淆。

## 边界情况

- 三个主角档案字段都可以为空；为空时后端会默认视作“玩家本人”。
- 保存 RP 设置时，即使 RP 关闭，也可以保存主角档案，方便之后再开启。
- 新建会话尚不存在时，`updateActiveConversationRpSettings` 会自动创建会话。
- 主角档案只属于当前会话，不是全局设置，也不属于角色卡。
- 不要把主角档案写进角色卡表单。

## 禁止事项

- 不要在 UI 层拼 RP prompt。
- 不要直接访问 Room、DataStore、Keystore 或网络。
- 不要记录角色卡、主角档案完整内容或 API Key 到日志。
- 不要新增第三方依赖。

## 验收

- RP Sheet 能编辑主角名称、简介、初始状态。
- 点击取消不会保存草稿。
- 点击保存后，重新打开 Sheet 能看到已保存内容。
- 保存后发送 RP 消息，生成的快捷选项应明显是玩家/主角视角。
- 普通聊天和已有 RP 设置不受影响。
- `.\gradlew.bat :app:assembleDebug` 通过。
