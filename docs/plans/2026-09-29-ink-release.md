# UI-D4 水墨版发布与升级实施计划

> For agentic workers: Use subagent-driven-development for the bounded version task; controller handles existing release workflow and device acceptance.

**Goal:** 发布已完成 UI-D2/UI-D3 验证的0.5.0/code8水墨界面，并通过应用内OTA核对用户手机升级及原数据保留。
**Architecture:** 只提升版本元数据与发布说明。复用原签名、GitHub签名构建、独立Supabase最新包发布器和原生安装流程。
**Tech Stack:** Kotlin/Gradle/GitHub Actions/Supabase Storage/ADB只读及授权UI操作。
**Spec:** docs/OTA_OPERATIONS.md、docs/reports/UI-D2.md、docs/reports/UI-D3.md、AGENTS.md。

## Global Constraints
- 版本0.5.0，versionCode8；包名、签名、minSdk、数据与接口契约保持原值。
- 百炼继续暂缓；生产Cron关闭；不宣称真实AI供应商验收。
- 新包公开验证成功后才提升清单并清理旧包；云端OTA存储只保留一个最新APK，本机历史交付保留。
- 不卸载、不清除用户手机数据、不用USB调试安装代替App内OTA。身份验证和系统安装确认由用户本人完成。
- 每次手机触控前重新检查前台；若切至其他应用或锁屏，停止触控。先记录升级前版本及已有记录/历史，再验证升级后版本、签名/摘要及原内容。
- 只提交明确本阶段文件；保留主工作区无关 puppet-dance.html 和既有stash。

## Task 1: 发布元数据与说明
**Files:** android/app/build.gradle.kts；docs/releases/0.5.0.md。
- [x] 在UI-D3最终源码提交后准备版本元数据；发布必须等待UI-D3最终云端及实拍验收通过。将versionName从0.4.2提升至0.5.0，versionCode7提升至8，其他构建配置不变。
- [x] 编写简洁用户更新说明：宣纸/夜墨、核心与次级页面统一、按钮及滚动优化、大字号和键盘避让；不称AI能力已经上线。
- [x] git diff --check；Android testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest；不为纯版本号修改新增镜像测试。
- [x] 实际Git身份签名提交chore(release): prepare ink interface version 0.5.0，正文逐文件表；独立范围审查及最终审查可关注纯元数据与说明是否准确。

## Task 2: 云端签名发布（controller）
**Files:** docs/reports/UI-D4.md、docs/PROGRESS.md、本计划；dist/0.5.0本地交付及.tools证据不入Git。
- [x] 推送精确提交，CI通过后合并main；运行现有Signed candidate APK发布0.5.0，使用现有Secret，不输出密钥。
- [x] 下载公开清单/APK，核对版本、包名、原证书与SHA256，保存交付；发布器成功及独立存储只读列举分别记录latest-only。
- [ ] 用户手机通过现有App检查、下载、安装；需要系统身份验证时请用户完成。
- [ ] 核对手机versionCode8、首次安装时间不变、APK摘要，原记录原话/验证标准/备注/历史保留；目视首页/镖库/详情/个人/备份/OTA。
- [ ] 报告源码、CI、云端、真机各层结果及未验收项，签名提交并自动推进不依赖百炼的收尾。
