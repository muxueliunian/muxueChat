# muxueChat Roadmap

本文件用于维护未来计划、功能顺序与 RP 模式讨论提纲。长期编码规范请查看根目录 `AGENT.md`。

## 当前状态

截至 2026-08-05，DeepSeek MVP、第一版 RP 前端与 OpenAI 兼容链路的 App 侧改造均已落地：

- DeepSeek API Key 本地保存。
- DeepSeek `chat/completions` 请求可正常工作。
- 流式输出可正常显示。
- `deepseek-flash` 与 `deepseek-v4-pro` 可用（2026-09-16 起默认 `deepseek-flash`，旧 Flash 名自动迁移）。
- 基础设置页可配置 Base URL、模型、system prompt、流式输出、max tokens、thinking mode、reasoning effort、采样参数。
- 设置页支持"显示大小"档位（极紧凑 / 紧凑 / 默认 / 舒适），叠加在系统字体大小之上，仅缩放字号和行距。
- 会话基础操作已接入：
  - 删除会话。
  - 重命名会话。
  - 清空当前会话。
  - 仅最后一条 assistant 消息显示重新生成。
- RP 后端与前端第一版已接入：
  - 角色卡 domain/local/repository/ViewModel 能力。
  - 世界观 domain/local/repository/ViewModel 能力。
  - 会话级 RP 设置。
  - Prompt Composer。
  - `<muxue_state>` 状态块解析与本地保存。
  - 角色卡自定义状态栏定义。
  - 聊天页 RP 入口与 RP 设置 Sheet。
  - 角色卡新增、编辑、删除、选择。
  - 世界观/世界设定新增、编辑、删除、选择。
  - 当前会话 RP 开关、角色卡绑定与世界观绑定。
  - RP 设置显式保存，取消不会提交草稿。
  - 主角档案：名称、简介、初始状态。
  - RP 回复正文最低字数设置。
  - 聊天页折叠式 RP 状态面板。
  - RP assistant 消息结构化展示：内心与“你的选择”。
  - 快捷选项支持直接发送或填入输入框编辑。
  - 玩家历史消息可编辑并重新生成，后端会删除后续消息并回滚 RP 状态。
- OpenAI 兼容链路已接入（原 CLIProxyAPI 专用链路，2026-08-05 起一般化为自定义供应商）：
  - 普通与流式聊天、重新生成、停止生成和协议级错误提示。
  - RP 正文、状态块解析、剧情选项/内心元数据、状态栏兜底与上下文压缩均按同一 Provider 快照路由。
  - 该链路下不发送 DeepSeek thinking/reasoning、RP Marker 或 OAuth 字段；可按供应商显式启用标准 OpenAI `tools` / `tool_calls`，用于创建角色卡、世界观和文风草稿。
  - 代码已通过单元测试；真实服务连通性仍由用户自行验收。

## 自定义 OpenAI 兼容供应商（2026-08-05 确认的方向调整）

原先「仅限第二种 Provider = 局域网 CLIProxyAPI」的限制已由用户确认解除，改为：

- 第二类 Provider 是**协议**而非具体服务：任何 OpenAI 兼容的 `http(s)://` 端点。
- 用户可自定义供应商名称、Base URL、API Key，保存多个并随时切换；原 CLIProxyAPI 配置降级为其中一条普通记录。
- 每个供应商的 API Key 占独立 Keystore alias，删除供应商时一并清除。
- 模型不再手填单值：通过 `GET {base}/models` 拉取并缓存，失败时回退手填；模型选择入口从设置页移到聊天页顶栏。
- 每个供应商可填 `reasoning_effort` 思考等级（预设 low/medium/high，可自填其他值，留空则不发送），并解析 `reasoning_content` / `reasoning` 展示思考过程。
- 明文 HTTP 经用户确认开放（用于局域网自建服务），风险由用户承担；UI 会在 http 端点上给出提示。
- 仍然禁止：App 内 OAuth、非 OpenAI 协议的私有适配、通用多 Provider SDK。

详细设计与任务拆分见 `docs/CUSTOM_PROVIDER_PLAN.md`。

## 前端 UI/UX 整体优化（2026-09-20 完成，已真机安装）

- 设置页按任务重新分组并可折叠；Base URL 行内校验与明文 HTTP 提示；清除 Key、删除供应商均二次确认。
- 修复上下文窗口设置一次组合上千个输入框的问题（`ModelListWindow`：当前项 + 已配置项 + 搜索）。
- 模型选择面板改为 `LazyColumn` + 搜索，目录来源、上次成功刷新时间、刷新互斥说明在各分组一致。
- 思考强度中文化（副文案保留 API 值）；思考关闭 / 开启时分别说明 reasoning_effort 与采样参数是否生效。
- 顶栏改为两行；消息列表不再强制拉回底部，提供「回到底部 / 有新内容」；输入栏 IME 与导航栏取并集。
- 编辑器 Sheet 离开前统一确认未保存草稿（点遮罩、返回不会先把 Sheet 隐藏）。
- RP Marker 不可用文案不再误称「当前模型不是 DeepSeek V4」。
- 真机（2026-09-20）已确认：键盘不遮挡输入框；编辑器放弃确认可弹出，且能回到编辑。
- 未覆盖：TalkBack 实测、系统大字体 × App 显示比例叠加、深色模式对比度实测、androidTest 真机执行。

## 多模态图片输入（2026-09-16 确认方向，MM-0 已实现，图片输入尚未实施）

DeepSeek 于 2026-09-10 发布原生多模态的 `deepseek-flash`，用户确认 App 跟进支持图片输入：

- 前置：已保存的旧 ID `deepseek-v4-flash` / `deepseek-v4-flash-vision-exp` 自动迁移到 `deepseek-flash`（MM-0，2026-09-16 已实现，待真机验收）。
- DeepSeek 与自定义 OpenAI 兼容供应商共用标准 `image_url` content part，不使用 DeepSeek Files API，不新增第三方依赖。
- 图片能力不按模型名猜测：DeepSeek 按内置已知 ID，自定义供应商按模型手动开启。
- 历史图片每轮重发（受上下文预算与 20 MiB 请求体安全上限约束）；本地压缩为长边 1568px、JPEG 85，每条消息最多 4 张。
- 编辑历史用户消息时允许增删图片；允许只发图片、不写文字。
- 分期：MM-0 模型 ID 迁移 → MM-1a 数据/请求/规划（含基本文件清理）→ MM-1b 发送 UI → MM-2 编辑图片与孤儿清理 → MM-3 体验打磨。
- 计划已于 2026-09-16 经独立审查修订（重新生成丢图、提交后取消误删文件等问题已写入设计）。

详细设计、边界、验收与风险见 `docs/MULTIMODAL_PLAN.md`。

## CLIProxyAPI 接入：三任务完成记录（历史）

以下三任务已完成，其产物已被上述一般化改造吸收，保留作为背景记录。

1. **任务 1：Provider 与设置基础（已完成）**
   - 保留 DeepSeek 现有配置与行为，新增 DeepSeek / CLIProxyAPI 选择。
   - 保存 CLIProxyAPI 的 HTTPS Base URL、模型名与代理 API Key；代理 API Key 单独存入 Keystore。
   - 对 CLIProxyAPI 隐藏 DeepSeek 专属的 thinking、reasoning effort 与 RP Marker 配置。
2. **任务 2：普通聊天链路（已完成）**
   - 通过 OpenAI 兼容 `chat/completions` 接口接入普通与流式聊天、停止生成、重新生成及可读错误处理。
   - 每轮生成开始时冻结 Provider、端点、模型与凭据快照，避免设置切换导致串线。
3. **任务 3：RP 全链路与回归（已完成）**
   - 让 RP 正文、上下文压缩、剧情选项/内心元数据与状态栏兜底请求均按当前 Provider 路由。
   - 该链路首版不接入角色卡、世界观的工具调用。

## 基础功能优先级

当前建议顺序（在上述三任务完成后）：

1. API Key 状态测试按钮。
2. 模型/参数预设。
3. RP 关键流程补充测试与手动验收记录。
4. RP 前端细节打磨：空状态、删除后解绑提示、长文本输入体验。
5. 按需讨论角色卡导入/导出、角色图片、多角色同场等非 MVP 功能。

## 已完成基础改动

已实现：

- 删除会话。
- 重命名会话。
- 清空当前会话。
- 只对最后一条 assistant 消息显示重新生成。
- RP 后端基础。
- RP 前端第一版。
- RP 设置显式保存。
- RP 主角档案。
- RP 回复正文最低字数。
- RP 结构化消息元数据展示。
- 玩家历史消息编辑并重新生成。
- 显示大小档位（v1）：DisplaySettings domain + DataStore 持久化 + Theme.Typography 倍率重建，dp 常量保持不变。

暂未实现：

- API Key 状态测试按钮。
- 模型/参数预设。

## RP 模式初步方向

RP 模式已经进入第一版可用状态，后续深化仍不要做成不可维护的硬编码 prompt。

已确认方向：

- RP 模式是会话级显式开关。
- 一个会话当前绑定一个角色卡与一个世界观。
- 角色卡已经是 domain model，不只是一个 system prompt 文本框。
- 角色卡至少应考虑：
  - 角色名称
  - 简介
  - 人设描述
  - 说话风格
  - 世界观/场景
  - 用户身份
  - 开场白
  - 禁止事项
  - 记忆/关系进展
- 角色卡本地保存，使用 Room。
- Prompt 拼接由 `PromptComposer` 负责，不要在 UI 或 API Client 中拼字符串。
- RP 模式应能和普通聊天模式共存。
- RP 设置使用会话级 `ConversationRpSettings`，不要污染任一 Provider DTO。
- 主角档案属于会话级 RP 设置，不写进角色卡。
- 角色卡内容可能非常私密，不允许写入日志。

## RP 前端状态

前端实现说明的历史 Prompt 保留在：

- `docs/FRONTEND_RP_PROMPT.md`
- `docs/FRONTEND_RP_EXPLICIT_SAVE_PROMPT.md`
- `docs/FRONTEND_RP_STRUCTURED_MESSAGE_PROMPT.md`
- `docs/FRONTEND_RP_PROTAGONIST_PROFILE_PROMPT.md`

这些文档描述的核心前端任务已经实现。后续 RP 前端剩余事项：

- 关键用户流手动验收：新建角色卡、绑定、发送 RP 消息、状态面板更新、历史改写回滚。
- 为 RP 设置、结构化消息、历史改写增加更聚焦的测试或可复现验收记录。
- 继续打磨小屏表单、长文本、空列表和删除后解绑体验。

暂不实现：

- 角色图片。
- 角色卡导入/导出。
- 多角色同场。
- 关键词触发世界书。当前已实现的是会话绑定的世界观/世界设定，不是按关键词动态召回的世界书。
- 长期记忆总结。

## RP 需求讨论提纲

已确认：

1. RP 模式是会话级开关。
2. 一个会话当前绑定一个角色卡。
3. 角色卡字段已包含名称、简介、人设、说话风格、用户身份、开场白、边界和状态栏定义。
4. System Prompt 与角色卡由 `PromptComposer` 合并，UI 不拼 prompt。
5. “用户 persona”以会话级主角档案实现。
6. 关系进展、状态条、场景通过 RP 状态面板实现。
7. UI 当前采用聊天页顶部入口 + 底部 Sheet。

仍待讨论：

1. 角色卡导入/导出格式。
2. 是否需要长期记忆总结。
3. 是否需要多角色同场。
4. 是否需要关键词触发世界书。当前世界观/世界设定已实现，关键词触发式世界书尚未实现。
5. 角色图片是否进入本地管理。

## RP 后续前置条件

在继续深化 RP 前，建议补齐：

- API Key 状态测试按钮。
- 模型/参数预设。
- 已有 Prompt Composer、状态块解析、RP 元数据解析基础单测；后续补 Repository/ViewModel/UI 集成测试与端到端 RP 生成回归。

## 状态栏定义 v1

状态栏定义跟随角色卡保存，当前数值跟随会话保存。

每个状态条字段：

- 名称。
- 最小值。
- 最大值。
- 初始值。
- 状态含义。
- 变化规则。

Prompt Composer 规则：

- 模型只能输出角色卡定义过的状态条。
- 模型不能新增、改名或遗漏状态条。
- 数值必须保持在定义范围内。
- App 保存状态面板前会再次按角色卡定义过滤并夹紧数值。
