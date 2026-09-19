# P0～P8 完整开发任务书

目标：交付可安装、离线可用、账号隔离、可溯源 AI 搜索与到期复核的 Android App。执行依据 `ARCHITECTURE.md`，协议依据 `API.md`、`openapi.json` 和 migrations。

## 通用执行方式
每阶段：读任务与前阶段报告 → 写行为用例并观察失败 → 实现 → 自检 → 对应 CI → 记录证据 → 独立提交/推送 → 汇报 → 自动进入下一阶段。阶段依赖阻塞时可以做后续不依赖线上环境的代码，但不能将阶段标为通过。
提交正文列逐文件变更；报告写提交、CI URL、测试数量、APK、部署目标、未验收边界。禁止跳过失败检查或关闭 RLS 来赶进度。

## P0：技术准备与契约冻结
**依赖**：GitHub 仓库访问；记账工具只读。
**产出**：AGENTS.md、CODEBASE_ANALYSIS.md、BOOMERANG_REUSE_PLAN.md、ARCHITECTURE.md、本文、API.md、openapi.json、001 migration、部署说明。
- [ ] 分析 Gradle、Room、网络/Auth、CI、Secret 与可复用范围，保留文件证据。
- [ ] 创建独立私有仓库；默认 main，保护规则在 CI 建立后配置。
- [ ] 明确状态、日期区间、证据归属、用户确认、离线同步冲突。
- [ ] 编写 SQL：主外键、检查、索引、RLS、历史、队列、配额与 RPC 权限。
- [ ] 编写 OpenAPI：请求/响应、鉴权、状态码、分页、幂等及错误。
**验收**：所有 P0 文档可导航；OpenAPI 可解析；迁移可在隔离 PostgreSQL 执行；两账号不可越权；无秘密入库。
**提交**：`docs(p0): define architecture and delivery contracts`。

## P1：Android 骨架与 CI
**依赖**：P0 架构；云端构建权限。
**文件**：android/{settings,build}.gradle.kts、app/build.gradle.kts、MainActivity.kt、ui/BoomerangApp.kt、ui/Theme.kt、.github/workflows/ci.yml。
- [ ] 建立 minSdk26、JDK17、Compose Material3 工程与 Wrapper 校验。
- [ ] 首页/镖库/AI/我的导航；新建/详情入口；日夜主题与中文文案。
- [ ] 预览使用显式演示数据，正式启动空库，加载/空态/失败分别显示。
- [ ] CI：JVM、lint、Debug APK、后端协议和隔离 SQL；每次 push 自动运行。
**用例**：四个入口可切换；返回键不会丢失未保存编辑；空镖库有新建入口；旋转后导航状态保留。
**验收命令**：`bash android/gradlew -p android testDebugUnitTest lintDebug assembleDebug`；云端产物可下载。截图/真机结果单列。
**提交**：`feat(p1): add android shell and cloud build`。

## P2：真实离线记录系统
**依赖**：P1。
**文件**：domain/Record.kt、domain/RecordRules.kt、data/BoomerangDatabase.kt、data/BoomerangRepository.kt、ui/RecordEditor.kt；对应 JVM/Room 测试。
- [ ] 五种记录、新建编辑、软删除、搜索、类型与结果筛选、截止日期排序。
- [ ] UUID、日期精度、原文、验证标准、来源；严格校验长度/日期。
- [ ] Room records/revisions/outbox 原子事务；无 destructive fallback。
- [ ] 状态与倒计时：注入 Clock/ZoneId；季度区间、闰年、无截止日期。
- [ ] 详情保留历史版本；编辑失败保留表单；重启恢复本地记录。
**用例**：2月末相对月份；截止当天显示今天到期、次日待复核；断网 CRUD；软删除后搜索不可见但导出保留墓碑；同次更新产生一条修订。
**验收**：JVM 与 Room instrumentation 通过；APK 离线闭环。
**提交**：`feat(p2): persist offline records and revision history`。

## P3：账号与云同步
**依赖**：P2；独立 Supabase 项目。
**文件**：data/AuthRepository.kt、data/BackendRepository.kt、data/SyncEngine.kt、supabase migrations/tests、deploy.yml。
- [ ] 邮箱注册/验证/登录/退出、刷新会话；服务端密钥永不进客户端。
- [ ] 用户 RLS + 复合外键阻止跨用户关联；RPC 检查 auth.uid，禁止任意 owner 参数。
- [ ] 本地按账号隔离；匿名记录由用户预览选择迁入账号。
- [ ] outbox 上传 expected_revision；冲突显示本机/云端，用户选择后按最新版本重试。
- [ ] 页大小100，软删除同步，失败不推进游标；相同 operation_id 重试不重复修订。
- [ ] 部署迁移、Auth 设置、Edge Functions；双账号真实冒烟，保留结果并清理测试数据。
**用例**：A 看不到 B；A 不能把 source 指到 B 的 record；账号切换无串数据；离线上传失败不丢数据；提交成功但响应丢失能幂等恢复；并发更新冲突不覆盖。
**验收**：隔离 SQL + JVM 同步用例 + 专用测试账号线上回读。缺项目凭据只能标“代码验证，部署待配置”。
**提交**：`feat(p3): add authenticated versioned sync`。

## P4：提醒
**依赖**：P2（本地）；P3（云端通知中心）。
**文件**：reminders/ReminderWorker.kt、ReminderPolicy.kt、AndroidManifest.xml、notifications 表与测试。
- [ ] 到期前7天、1天、当天提醒；无期限不调度；已确认/取消/删除不提醒。
- [ ] WorkManager 周期扫描 + 进入应用补扫；同 record/revision/offset 唯一投递键。
- [ ] Android13+ 动态通知权限、拒绝提示、通知渠道；点击直达记录。
- [ ] 修改期限取消旧计划，重启恢复，退出账号移除该账号任务。
**用例**：重复 worker 不重复通知；时区切换；拒绝权限仍可在通知中心查看；不补发连续三条过期提醒。
**验收**：时钟注入 JVM 用例，Android 权限/通知点击测试；真机后台时效单列，不能承诺准点。
**提交**：`feat(p4): schedule deduplicated return reminders`。

## P5：Qwen 智能录入与对话
**依赖**：P3；百炼 Secret。
**文件**：functions/api、_shared/providers.ts、schemas.ts、ai.ts；Android AI ViewModel/提案编辑页。
- [ ] LLMProvider analyze/chat；默认本阶段不联网；限制输入输出与请求超时。
- [ ] said_at + timezone 作为相对时间基准；未知日期保持 null，返回需澄清项。
- [ ] 严格 Schema 验证；模型失败/非法 JSON/超长内容均不写正式记录。
- [ ] 对话与待确认提案按用户保存；修改提案后点击保存才入库。
- [ ] 用户每日额度、429、供应商错误脱敏；配置缺失明确 503。
**用例**：提示注入不改变系统规则；“明年第一季度”区间；无日期不猜；JSON 缺字段拒绝；用户取消无新增。
**验收**：离线供应商夹具 + 真实百炼 analyze/chat 冒烟分别报告。
**提交**：`feat(p5): add validated qwen draft assistance`。

## P6：网络调查与证据
**依赖**：P5。
**文件**：_shared/research.ts、providers.ts、research_jobs/candidates/sources、Android 候选和证据视图。
- [ ] POST research 入队并返回202；GET job 查询运行/成功/失败；客户端离开不丢任务。
- [ ] 百炼 web_search/web_extractor；引用只接受工具返回的 URL；限制数量和体积。
- [ ] 保存候选、来源、引文与获取时间；去重但不把转载当独立来源。
- [ ] 候选查看/编辑/确认；确认事务写 record+sources，accepted_record_id 防止双击重复。
- [ ] 无来源或解析不完整明确提示，保留失败原因；不自动发到公共平台。
**用例**：模型虚构 URL 被拒绝；私网 URL 被拒绝；重复确认得到同一记录；中途超时可重试且无重复候选。
**验收**：工具响应夹具与真实查询；每条正式 AI 记录可追到实际来源。
**提交**：`feat(p6): research topics with traceable evidence`。

## P7：自动收镖
**依赖**：P4、P6。
**文件**：队列 migration、functions/worker、check/confirm RPC、Android 复核详情。
- [ ] Cron 定期扫到期 ACTIVE 未确认记录，按 record/revision 建唯一任务。
- [ ] worker 使用 SKIP LOCKED、租约到期回收、退避、最多3次、死信；处理耗时不阻塞调度事务。
- [ ] 读取特定 record revision，按当时验证标准生成支持/反对证据；过期 revision 报告标失效。
- [ ] AI 只存 suggested_status；用户确认 RPC 校验最新 revision，保存理由和审计。
- [ ] 成功/最终失败写应用内通知；失败不自动判 BROKEN；手动复核限额。
**用例**：并行 worker 只能一方领到同一任务；租约过期能恢复；旧 worker 不能写新租约结果；用户编辑后旧结论不能确认；证据不足 UNVERIFIABLE。
**验收**：Cron 实际触发 → 任务 → 报告 → 通知 → 用户确认真实闭环；故障注入和额度测试。
**提交**：`feat(p7): verify due records through leased jobs`。

## P8：趣味功能与发布
**依赖**：P7；按以下子项分别验收提交。
- [ ] P8.1 回旋卡：本机生成 PNG，用户预览后系统分享；包含来源简述与“AI建议/用户确认”标识，默认隐藏私人备注。文件：sharing/ShareCard.kt。
- [ ] P8.2 时间胶囊：保存锁定时间；锁定期间禁止改原话/期限/标准，补充说明走修订；解锁只按约定期限。文件：domain/CapsulePolicy.kt、DB 约束。
- [ ] P8.3 证据天平/反向核查：展示支持/反对/中性证据，不按链接数投票；错引原话以纠正说明和来源表示，用户确认 REVISED。
- [ ] P8.4 年度报告：用本地确定性统计，明确年份时区、去除删除记录、区分已确认和AI建议；覆盖跨年用例。
- [ ] P8.5 人物/事件时间轴：以 subject/topic 筛选记录与历史，支持未知日期，不推断人物身份相同。
- [ ] P8.6 AI周报：用户主动生成当周摘要，限制在自己的资料，引用 record ID，不自动发布。
- [ ] P8.7 JSON 导出/导入：SAF、版本标记、预览、UUID冲突选择、大小限制、原子写入、秘密不导出；测试损坏与重复文件。
- [ ] P8.8 1.0 发布：完整 JVM/Room/UI/SQL/API/真机矩阵，独立签名 Secret，版本递增，可追溯 APK、迁移和回滚指南。
**验收**：每个子项测试 + 真机主要流程；发布不把 Debug 包称为正式签名版；所有阻塞项明确关闭后才标 V1 完成。
**提交**：各子项 `feat(p8): ...`；最终 `chore(release): prepare verified v1 delivery`。

## P8.9：Android OTA（2026-09-19 用户增补）

**范围**：公开只读更新清单、同签名完整 APK、应用内检查/下载/校验/系统安装；不依赖百炼，不变更业务数据库。

- [ ] 完成客户端与独立云项目 Storage 发布器，版本、URL、完整性、签名和失败恢复采用行为测试。
- [ ] 验证旧版同签名覆盖安装后，通过真实云端完成下一版 OTA，保留已有本机验收记录。
- [ ] 记录源码、本地验证、CI、云端上传与手机安装的独立证据，更新阶段报告。

具体契约与任务拆分见 [OTA 计划](plans/2026-09-19-ota.md)。

## 必要云端配置
GitHub：repo/workflow；远程开发另需 codespace 或已连接 Codex Cloud。
Supabase：专用 project ref、URL、公钥、管理访问令牌和数据库密码（仅 CI）。
百炼：DASHSCOPE_API_KEY、QWEN_BASE_URL、QWEN_MODEL（支持 Responses 工具的实际模型）。
发布：ANDROID_KEYSTORE_BASE64、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD。缺配置时不产生伪成功部署。
