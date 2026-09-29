# UI-D3 次级界面水墨统一实施计划

> For agentic workers: Use subagent-driven-development to implement and independently review this plan.

**Goal:** 将 AI 助手、个人、通知、回顾备份和更新页统一为已确认的宣纸朱砂风格，改善按钮层级、滚动和大字号可达性。
**Architecture:** 沿用 Compose + ViewModel + Repository，改动限定展示层。允许为可测性抽取无状态展示组件；系统启动器、生命周期观察与数据操作留在原有宿主。
**Tech Stack:** Kotlin / Compose Material 3 / Android minSdk26。
**Spec:** docs/design/ink-wuxia/index.html、docs/reports/UI-D1.md、AGENTS.md；用户已批准整体风格并要求去除印章、继续后续阶段。

## Global Constraints
- 使用现有 Theme.kt 的宣纸/夜墨配色与字体，不增加印章、装饰性卡片堆叠或新依赖。
- UI 只访问 ViewModel；ViewModel 访问 Repository；数据库与网络细节封装在 data 层。
- 原话、来源证据、AI 建议和用户确认分开保存。AI 不得直接确认最终状态。
- 研究结果先存候选；仅用户明确点击确认后写入镖库。无可追溯信源的候选不可确认。
- 登录切换按用户隔离本地库；匿名数据只能经明确导入归属，不得自动上传给新账号。
- Android 数据迁移不允许 destructive fallback；导入先校验全量再原子提交。
- 保留密码仅内存状态、所有 owner/busy/revision 校验、系统权限与安装回调、URL 校验和已有操作标签；不改变后端、版本、生产配置。
- 48dp 最小点击目标；正文保持无衬线；标题使用既有衬线。按钮明确主次，8–12dp 圆角；200% 字号和短窗口可滚动访问全部操作。
- 本阶段不在用户手机运行测试或安装调试 APK。百炼继续暂缓，生产 Cron 关闭。组件测试不得宣称真实供应商验收。

## Task 1: 次级页面统一与交互回归
**Files:** 修改 ui/AccountScreen.kt、ui/BoomerangApp.kt、assistant/AssistantScreen.kt、extras/ExtrasScreen.kt、updates/UpdateScreen.kt、reminders/NotificationCenter.kt（均在 android/app/src/main/java/com/boomerang/app）；可新增 ui/InkPageComponents.kt 与同包中纯展示组件文件；既有 InkComponents.kt 的核心页组件保持原状，避免无功能变化的改动。测试 androidTest/.../InkSecondaryUiTest.kt；必要时修订已有系统测试的定位/滚动方式，不删除业务断言。
**Interfaces:** 沿用各屏幕原有 ViewModel、回调与状态；可新增 AccountScreen 工具入口回调，把现有壳层两按钮合并进个人页滚动内容；新参数的调用处全部适配。
- [x] 阅读页面、测试、主题与阶段报告，记录原有功能和保护条件。
- [x] 先补有意义的展示行为用例：大字号短窗口可访问入口，AI 模式切换与 busy 禁用，OTA 可下载/下载中/安装权限/就绪状态与取消回调，备份预览的取消/跳过/替换操作。纯外观修改不写镜像测试；新布局交互需要回归。
- [x] 落地共同标题/分节/按钮风格（只抽取实际复用部分）。AI 使用可滚动模式导航，清晰区分输入、草稿、候选、人工确认；输入区域处理键盘滚动。个人页将账号/同步/工具/通知置于一致的页面层级，保留显式导入和冲突双方内容。备份操作清楚分组，长预览及大字号仍能取消/确认；分享流程保持用户发起。更新页清晰显示当前版本、新版说明、进度及当前主动作，保留所有下载校验和系统安装流程。
- [x] 补充云端可采集的明暗/200%字号截图，用于目视检查实际布局；合成状态与真实宿主分别标注。
- [x] 运行 git diff --check 及 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest，核对报告。不得本地 connectedDebugAndroidTest/installDebug。
- [x] 自检后仅暂存此任务明确文件，签名提交 feat(ui): unify secondary ink screens（实际 Git 身份，正文逐文件变更表）。写实现报告，交独立任务审查。

## Task 2: 云端验证与阶段交付（controller）
**Files:** docs/PROGRESS.md、docs/reports/UI-D3.md、docs/design/ink-wuxia/secondary-preview/、本计划。
**Interfaces:** 消费 Task1 提交及截图，使用现有 CI 与设备矩阵。
- [x] 独立任务审查，修复后进行全分支最终审查。
- [x] 将精确签名提交发布到专用分支，云端 API26/33/35 运行全部适用系统用例；记录首次失败和重试，必要修复后验证最终代码。
- [x] 下载并目视核查明暗、短屏、大字号截图，生成可浏览画廊，分开标注合成展示与实际应用截图。
- [x] 更新阶段报告和进度，签名提交，按已有授权合并 main 并核验 main CI。记录尚未发布 APK、百炼未配与真机验收边界。
