# 剩余阶段验收：2026-09-26

用户授权继续分阶段自动推进；此前阶段后停止的约定已被取代，百炼暂不配置的决定保留。本轮基线main `e5aa02e9ca8b6c3b2ecb5e5acccc5e6e9a3a958f`，已发布Android 0.4.1/code6。未修改产品代码、数据库结构或生产调度，未重新发布APK。

## 阶段1–2：本机功能与提醒

vivo V2430A，Android16/API36，ADB设备已授权，实际本机日期2026-09-26，记录时区Asia/Shanghai。创建专用记录 `ACCEPT_20260926_DEVICE_RECORD`，目标、人物“我”、起止2026-09-26、精度DAY、标准 `ACCEPT_verify_backup_history_reminder`。保存后详情显示“今天到期”，修改历史显示第1版 `2026-09-26T00:54:58.789244Z`。

新建与日期显示已实测；尚未进行编辑历史、备份、回旋卡、胶囊、通知点击及后台验收。继续操作时发现前台已切至机器人控制页面，立即停止手机操作并询问可用窗口。后续必须先读取当前前台，确认回旋镖页面后再触控，不依据先前坐标直接操作。没有卸载或清除应用，也没有完成备份、跨账号切换或发送分享。

本机受控XML证据保存在忽略目录 `.tools/acceptance-20260926/phone/` 的 `saved-due.xml`、`detail-more.xml`、`detail-top.xml`。专用记录暂留手机用于继续验收；未将其他应用页面归入交付证据。全库备份包含私人内容，后续只保存本地，不提交Git；完整备份仅测试取消及跳过重复，不执行全量替换。

## 阶段3：真实云端账号与同步

已完成10组真实HTTPS断言。独立项目 `skeghmapzrmahxehazlp`，创建两个随机example.invalid账号，以管理员预确认方式避免发送邮件；A使用两个独立登录会话，B用于隔离验证。

| 断言 | 实际结果 |
|---|---|
| A1/A2独立会话 | 同一用户、不同访问令牌，认证通过 |
| 创建与操作重放 | 两次返回revision1；实际未人为丢弃网络响应 |
| 双用户隔离 | A可读取自己的记录；B读取不到 |
| A1更新 | revision1→2 |
| A2旧版本更新 | HTTP409；A1内容不被覆盖 |
| A2明确重试 | 基于revision2得revision3，重复提交仍为3，三个原文版本全部保留 |
| 来源同步 | 重试幂等，B不能读取A来源 |
| 来源历史与原子快照 | 记录/来源及修订一致 |
| 越权操作 | 他人快照、无归属候选确认均HTTP403 |
| API边界 | 未登录analyze为401；已登录但未配百炼为503 NOT_CONFIGURED；普通用户调用worker为401 |

08:57完成测试。清理限定生成UUID、用途/本次run元数据及合成邮箱后缀，在单一事务中执行，锁等待2秒、语句15秒上限。为删除合成历史，独占锁内暂时关闭两项历史不可修改触发器并在同一事务恢复；失败会回滚，不留下持久变更。08:58独立只读查询确认该批次用户、会话、记录、来源、两类历史及幂等操作残余全部为0，两项历史保护触发器均启用。未查询或修改真实用户内容。

已脱敏原始结果：`.tools/acceptance-20260926/cloud-smoke-result.json`、`cleanup-verification.json`；持久汇总见同报告目录的 `ACCEPTANCE-2026-09-26-results.json`。这是后端双会话验收，不能代替邮箱投递、手机切换账号或两台物理手机的离线冲突界面验收。

## 阶段4：AI与自动复核前置检查

实时核对api v3和worker v2 ACTIVE；迁移001–005版本/名称与本地一致（未比较迁移内容哈希）；12张业务表RLS开启，8个核心RPC存在。邮箱注册启用且要求验证，但没有发送邮件，未声称投递成功。

仅检查Secret名称：DASHSCOPE_API_KEY、QWEN_BASE_URL、QWEN_MODEL仍缺失。真实已认证请求返回503，符合未配置状态。pg_cron、pg_net及cron.job不存在，生产Cron未启用。保留用户暂缓配置的决定，不将夹具测试作为真实analyze/chat/search/extractor/周报或Cron→复核→通知→确认闭环。

## 验证、交付及下一阶段

本次20操作API合约、Cron脚本5项检查、26项AI边界及16项OTA发布器行为测试通过。执行 `bash android/gradlew -p android testDebugUnitTest lintDebug assembleDebug --max-workers=1`，5分16秒成功：98项JVM、0失败/错误/跳过，Lint为0错误、22警告，Debug APK构建通过。使用本项目临时B盘映射绕过中文路径；现有R盘属于其他项目，未更改。构建日志在 `.tools/acceptance-20260926/android-verification.log`。没有运行手机instrumentation，也没有将Debug包安装到用户手机。

文档链接、脱敏结果结构和凭据模式检查通过，`git diff --check`通过。独立审查指出的历史段落归属及“响应丢失”歧义均已修正：本次只是重放相同操作，未注入真实网络丢包。

阶段签名提交 `3032be732a64843c6713183b4f87d6c4f6e95a91` 已发布main（GitHub签名标记unknown_key，不称Verified）。本次 [CI 36207231994](https://github.com/amateurish-programmer/boomerang/actions/runs/36207231994) 成功：Android测试/静态检查/构建及contracts任务全部通过，后者包含隔离PostgreSQL迁移、双用户权限、研究队列与接口边界。本次push未触发模拟器矩阵，device为skipped，未算作通过；原矩阵证据见OTA报告。后续仅补录本段CI结果与进度，不改变已验证产品代码或APK。

待可用手机窗口继续阶段1–2；随后账号界面/两台真机、本人邮件验证、厂商重启和长期后台、第三方文件提供方/分享接收分别验收。百炼配置仍为外部前置条件。以上未验收项均未标为通过，也未标记V1全量完成。
