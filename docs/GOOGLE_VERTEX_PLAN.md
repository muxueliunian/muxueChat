# muxueChat Google Vertex AI 接入计划

状态：代码完成，JVM 测试与构建见「进度记录」；**真机验收未执行**（需要你先完成第 3 节的 GCP 控制台配置）。
建立日期：2026-10-14
范围：用手机上的 Google 账号登录，通过 Vertex AI 的 OpenAI 兼容端点使用 `google/gemini-4-argon`
线上格式依据：`docs/GOOGLE_VERTEX_WIRE_FORMAT.md`（2026-10-14 实测）

## 1. 已批准的决策（用户 2026-10-14）

| # | 决策 |
| --- | --- |
| G1 | **不新增传输实现**：Vertex 的 `openapi` 端点就是标准 `POST {base}/chat/completions`，走 `OpenAiCompatibleClient` 的第二个**钉死主机实例**（https、主机固定 `aiplatform.googleapis.com`、端口 443、禁止任何重定向）。`AGENT.md` §2「不为 Gemini 新增适配层」保持成立。 |
| G2 | 路由判定只看 `ProviderAuthKind.GOOGLE_OAUTH` 与 `ProviderCredential.GoogleBearer`，不按名称或地址匹配。 |
| G3 | 登录用 Google Identity Services `AuthorizationClient`（`play-services-auth:22.0.0`），scope 为 `cloud-platform`，使用**用户自己 GCP 项目**里的 Android OAuth 客户端；源码里没有 client ID / secret。 |
| G4 | **App 不保存任何 Google token**：每次冻结请求时静默向 Play 服务索取，只存在于该轮快照内存里。DataStore 只存项目 ID，SharedPreferences 只存一个「已登录」布尔。 |
| G5 | 区域固定 `global`（`us-central1` 实测 404）；项目 ID 由用户填写并经 `^[a-z][a-z0-9-]{4,28}[a-z0-9]$` 校验，地址每次归一化都由项目 ID 重新推导。 |
| G6 | 思考档位只用 `reasoning_effort`（用户自填，留空不发）。**不使用 `thinking_config`**：与 `reasoning_effort` 互斥，且非流式把 `<think>` 混进正文、流式又不返回。因此看不到思考正文。 |
| G7 | 支持工具调用：Vertex 要求工具轮回放时原样带回 `tool_calls[].extra_content`（含 `thought_signature`），否则 400。新增 `AGENT.md` §2「不透明字段回传」例外：不解析、不修改、不持久化、不记日志，服务端没返回就不发（其他供应商请求字节不变）。工具开关默认关闭。 |
| G8 | RP 不需要新代码：RP 与普通对话共用同一条 OpenAI 兼容链路，RP 请求本来就不发 `tools`。 |
| G9 | `GET …/openapi/models` 实测 404：模型只能手填，新建供应商时预填 `google/gemini-4-argon`；设置页的「获取模型列表」置灰并写明原因。 |
| G10 | 新建 Google 供应商时 `temperature` / `top_p` 默认不发送（实测被接受，但是否生效未验证）。 |

规则修改已落入 `AGENT.md`：§1、§2（Google Vertex 链路 / 不透明字段回传 / Google Vertex 登录）、§3.2、§3.3、§5.3、§6、§8、§9、§11。

## 2. 代码地图

| 层 | 文件 | 要点 |
| --- | --- | --- |
| domain | `ProviderAuthKind.GOOGLE_OAUTH`、`CustomProvider.googleProjectId` / `googleBaseUrl()` / `usesGoogleOAuth` / `usesOAuth`、`GoogleAuthState` | 地址由项目 ID 推导；OAuth 供应商不接收会话标识头 |
| remote | `OpenAiCompatibleClient.pinnedTo()` / `pinnedOkHttpClient()`、`OpenAiCompatibleUnauthorizedException`、`OpenAiCompatibleToolCallDto.extraContent`、`OpenAiCompatibleToolCallAccumulator` | 钉死主机；401 带类型；错误体兼容单元素数组；`extra_content` 透传 |
| repository | `ProviderCredential.GoogleBearer`、`GoogleAccessTokenSource`、`GoogleAuthRequiredException`、`ProviderRequestFreezer`、`ChatTransportRouter`、`GoogleAuthRepository`、`PlayServicesGoogleAuthorizer`、`ChatRepositoryImpl` | 401 后 `forceRefresh` 重试一次；已吐过内容则不重试 |
| presentation | `GoogleAuthViewModel`、`GoogleAuthSections`、`ProviderSections`、`SettingsScreen`、`AppNavigator`、`ChatViewModel.addGoogleProvider` | 同意页只在 `MuxueChatApp` 拉起一次 |

## 3. 你需要在 GCP 控制台做的事（清单）

1. 确认项目是 `free001-496813`，Vertex AI API 已启用（命令行已实测可调用，说明已启用）。
2. **同意屏幕**（Google Auth Platform → 目标对象 Audience）：
   - 如果只能选 **Internal**，手机上登录的账号必须属于该组织的 Workspace 域名，个人 `@gmail.com` 会被拒绝。
   - 能选 **External** 时选「测试」模式，并把手机上要登录的账号加为**测试用户**。
3. **数据访问（Data Access）**：添加 scope `https://www.googleapis.com/auth/cloud-platform`。
4. **客户端（Clients）→ 创建客户端 → Android**：包名 `com.muxuechat`，SHA-1 填下一条获取的值。**必须建在 `free001-496813` 项目里。**
5. **获取 SHA-1**（二选一，pwsh）：
   - `.\gradlew.bat :app:signingReport`，看 `debug` 变体的 `SHA1`；
   - 或 `keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android`。
   - 换电脑或 debug keystore 被重建，SHA-1 会变，需要在控制台补一个客户端。
6. **IAM**：登录账号在该项目要有 `Vertex AI User`（`roles/aiplatform.user`）或 Owner。
7. 手机侧：已安装 **Google Play 服务**、已登录 Google 账号，并且能访问 googleapis.com。
8. 注意：测试模式下的授权**可能每 7 天过期**，到时设置页会提示重新登录（待真机观察，未确认）。

## 4. 进度记录（接续工作先读这里）

| 分期 | 状态 |
| --- | --- |
| 规则（AGENT.md）与实测记录（`GOOGLE_VERTEX_WIRE_FORMAT.md`） | 完成 |
| 认证模块、路由、透传、设置页 | 代码完成 |
| JVM 测试 | 见提交记录 / 下方「验证」 |
| 真机验收 | **未执行**（阻塞于第 3 节） |

## 5. 真机验收清单

前置：第 3 节全部完成；安装 debug 包（不要用 `connectedDebugAndroidTest`，它会卸载应用，见记忆里的备份说明）。

**A. 登录**
1. 设置 → 服务与模型 → 点「添加 Google Vertex 供应商」，应出现并自动选中「Google Vertex」，模型预填 `google/gemini-4-argon`。
2. 填入项目 ID `free001-496813`，下方应显示自动生成的地址（主机固定 `aiplatform.googleapis.com`）；填 `Bad ID` 应报红且不保存。
3. 展开「Google Vertex 登录」，点「登录 Google」→ 弹出 Google 授权页 → 同意。状态应变为「已登录」。
4. 失败时的提示：代码 10 = 包名/SHA-1 与 GCP 客户端不匹配；其他代码 = 检查 Play 服务与账号。

**B. 普通聊天与流式**
5. 关闭流式，发一条消息，应得到完整回复，聊天页显示真实 token 用量。
6. 打开流式，发一条消息，应逐段输出，结束后用量正常。
7. 停止生成：生成中点停止，消息应为已取消，不残留在途请求。

**C. 思考强度**
8. 「思考与采样」里把 `reasoning_effort` 填 `max`，发一个推理题；用量里的思考 token 应明显多于留空时（实测 max 为 191，留空更少）。
9. 留空 `reasoning_effort`：请求不应带该字段（行为上不报错）。

**D. 工具调用**
10. 供应商设置 → 高级连接选项 → 打开「启用工具调用」，并确认全局「设定生成工具」已开。
11. 非 RP 对话里让模型「创建一张角色卡」：应出现草稿预览，确认后落库，模型在工具结果后继续回复。
12. 一轮内多个调用（同时建角色卡与世界观）。若出现 400「missing a thought_signature」，记下状态码与文案（不要贴正文）并关闭开关。
13. RP 对话里不会发送工具；RP 状态面板、元数据补写、状态兜底、上下文压缩在 Vertex 下各走一遍，重点看 Argon 是否稳定输出 `<muxue_state>`。

**E. token 过期后自动刷新**
14. 登录后等待 > 1 小时（access token 约 1 小时），再发一条消息：应直接成功，无需重新登录（App 每次都向 Play 服务取新 token）。
15. 若提示「Google 授权已失效」：到设置页重新登录；记录这是否发生在 7 天左右（测试模式授权过期）。

**F. 退出登录**
16. 「退出登录」要二次确认；确认后状态为「未登录」，再发消息应提示「请先在设置中登录 Google」。
17. 在 Google 账号的「第三方访问权限」页，应看不到本 App 的授权（撤销是尽力而为，失败时 App 会提示）。
18. 删除 Vertex 供应商：对话框应写明「不会退出 Google 登录」，且不改变登录状态。

**G. 隔离**
19. 切回 DeepSeek / 其他 OpenAI 兼容供应商 / xAI，聊天行为与之前一致。
20. 日志（Logcat 筛选 `muxueChat`）里不应出现 access token、Authorization、`thought_signature`。

## 6. 已知限制与未验证

- 看不到思考正文（见 G6）。
- 429 的错误体、真实过期 token 的 401 错误体、流式 + 工具调用的完整往返均未验证（见 `GOOGLE_VERTEX_WIRE_FORMAT.md` §7）。
- 聊天页模型选择面板里的「刷新」对 Vertex 会提示不支持，而不是置灰；设置页内已置灰并写明原因。
- 授权过期周期（测试模式）与 Play 服务可用性取决于你的手机与项目配置，需真机确认。
- `play-services-auth` 会增加 APK 体积；仅用于本功能（`AGENT.md` §8）。
