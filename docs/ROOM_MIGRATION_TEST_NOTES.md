# Room migration 测试的历史资料审计与替代方案

## 结论

目前无法恢复可证明来自已发布版本的 Room v1～v7 导出 schema，也无法恢复这些版本的
`room_master_table.identity_hash`。因此仓库中不新增人工伪造的 `1.json`～`7.json`，避免把
“根据现有迁移反推的结构”误报成“真实历史 schema”。

本轮新增 `ChatDatabaseMigrationTest` 作为可执行的重建库替代测试。它能够验证生产迁移 SQL
的连续性、数据保留、默认值、索引、外键和 `ON DELETE` 行为，并通过 Room 自身的最新 schema
校验；但它不等价于拿真实旧版本用户数据库做升级测试。

> **2026-09 更新**：数据库已升到 v9（新增 `conversation_token_usage`）。`8.json` 与 `9.json` 都是
> Room 正常导出的真实 schema，因此 8 → 9 这一跳不受本文档描述的历史资料缺失影响：
> `ChatDatabaseMigrationTest.migrate8To9_fromExportedV8Schema_validatesAgainstExportedV9Schema`
> 使用 `MigrationTestHelper` 按 `8.json` 建库、执行生产 migration，并对照 `9.json` 做 Room 校验。
> 下文「无法恢复历史 schema」「重建库替代测试」的结论只适用于 v1～v7。

## 审计证据

截至 2026-08-10，本地项目满足以下事实：

- Git 只有一个初始提交 `c18dc22`，该提交中的 `ChatDatabase` 已经是 v8。
- 仓库没有 remote、其他 branch/tag、reflog 旧提交或 dangling Git object 可供恢复。
- `app/schemas/com.muxuechat.data.local.ChatDatabase/` 只有 `8.json`。
- 工作区（排除 `build/` 与 `.gradle/`）没有 APK、AAB、SQLite 数据库或其他历史 schema 备份。
- `docs/HANDOFF_2026-07-31.md` 明确记录：到 v8 才把 `exportSchema` 从 false 改为 true。

因此，v1～v7 的原始导出 JSON、identity hash 以及“各版本全新安装时由 Room 生成的 schema”
无法从当前资料中证实。

## 重建边界

替代测试只采用可以由当前代码直接证明的最小假设：

1. v1 包含 `conversations` 与 `messages`。这两张表在 v8 schema 中存在，并且七段 migration
   从未修改它们；测试据此逐字段重建其 v1 结构、索引和级联外键。
2. v2～v7 不手写整套建表结构，而是由生产 `ChatDatabase` 中真实的七段 `Migration`
   依次执行得到。
3. 测试通过反射按 `startVersion/endVersion` 取得 private migration，目的是避免复制迁移 SQL
   后只测到测试副本。若生产 migration 被删除或版本断链，测试会明确失败。
4. 重建 v1 使用占位 identity hash。Room 升级不依赖旧 hash；1→8 成功后，Room 会写入 v8
   hash。这个占位值不能被解释为真实历史 hash。

## 自动化覆盖

`app/src/androidTest/java/com/muxuechat/data/local/ChatDatabaseMigrationTest.kt` 覆盖：

- 1→2：RP 表、列、索引、三组外键以及 CASCADE/SET NULL 实际行为。
- 2→3：`metricDefinitionsJson` 的非空约束、`'[]'` 默认值及旧行回填。
- 3→4：消息 RP 元数据表、索引、双外键和两条级联删除路径。
- 4→5：三个主角字段的默认值、旧行回填和绑定保留。
- 5→6：`narrativeMinChars` 默认值、旧行回填和原字段保留。
- 6→7：叙事视角、建议视角、Marker 模式的默认值与旧行回填。
- 7→8：`conversation_rp_settings` 整表重建的数据保留，新增文风/摘要表，全部新增索引、
  外键和 CASCADE/SET NULL 实际行为，并确认临时表不残留。
- 8→9（重建库）：新增 `conversation_token_usage` 表的列、三个可空计数列、外键 CASCADE 与既有数据保留。
- 8→9（真实导出 schema）：`MigrationTestHelper` 从 `8.json` 建库，迁移后按 `9.json` 做 Room 完整校验
  （`validateDroppedTables = true`）。`app/build.gradle.kts` 已把 `schemas/` 挂到 androidTest assets。
- 1→9：使用生产八段 migration 打开重建 v1 数据库，由 `ChatDatabase_Impl` 对最终 v9 的
  表、列、索引和外键做 Room 完整校验，同时检查 v1 会话与消息仍在。

在已授权的 Android 16+ 设备或模拟器上可运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.muxuechat.data.local.ChatDatabaseMigrationTest"
```

2026-08-10 验证记录：该测试及全部 AndroidTest 已通过 Kotlin/KSP 编译，instrumentation APK
也已成功打包；本机仅有 API 33 AVD，低于项目 `minSdk=36`，因此测试尚未在设备上执行。
不得通过降低 minSdk 绕过这一环境要求。

2026-09-05 验证记录：v9 相关的 `ChatDatabaseMigrationTest`（含 8→9、1→9）、
`ConversationTokenUsageDaoTest`、`SettingsContextWindowPersistenceTest` 与
`ChatRepositoryTokenUsageTest` 均已通过 Kotlin/KSP 编译。设备侧仍未执行：
本机 AVD 只有 android-30 / android-33，`system-images` 目录下没有 android-36 镜像，
且没有连接真机。要真正跑起来需要安装 Android 16 系统镜像并创建对应 AVD，或连接一台
Android 16 设备并开启 USB 调试。同样不得通过降低 minSdk 绕过。

2026-09-16 验证记录：新增 `migrate8To9_fromExportedV8Schema_validatesAgainstExportedV9Schema`
（`MigrationTestHelper`）。`:app:compileDebugAndroidTestKotlin` 成功；`:app:mergeDebugAndroidTestAssets`
确认 `8.json`、`9.json` 已打包进测试 APK 的 assets。设备侧仍未执行，原因同上。

## 尚未关闭的风险与补齐方式

要把 TEST-02 从“重建库替代覆盖”提升到“真实升级覆盖”，仍需取得至少一种可靠资料：

- 每个历史版本在发布时导出的 Room schema JSON；或
- 每个历史 APK 全新创建并保留的数据库文件；或
- 脱敏后的真实用户 v1～v7 数据库备份。

取得资料后，应保留原文件及校验和，把真实 schema 放入 Room schema 目录，再使用
`MigrationTestHelper` 对每个相邻 hop 和 1→9 全路径重跑。测试数据必须脱敏，不能包含 API Key、
完整消息或角色卡敏感内容。
