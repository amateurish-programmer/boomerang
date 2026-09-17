# P0：记账工具只读分析

检查日期：2026-09-17。来源：本机 `F:/Git/记账工具` 与 GitHub 私有仓库 `amateurish-programmer/ai-family-ledger`。分析的是当时工作树，不把 README 版本号当源码版本号。

| 项目 | 实际证据与结论 |
|---|---|
| Android | android/build.gradle.kts：AGP 8.9.2，Kotlin/Compose plugin 2.1.20，KSP 2.1.20-1.0.32 |
| 平台 | app/build.gradle.kts：minSdk 26，compile/targetSdk 35，JDK17，Gradle 8.11.1 |
| UI | Compose BOM 2025.04.01，Material3，ViewModel/Flow |
| 数据 | Room 2.7.2；LedgerRepository 通过事务批量提交；软删除及历史导入 |
| 网络 | Repository 与独立 SyncEngine 分离；版本清单、批量拉取上传、大小上限 |
| 认证 | Supabase 邮箱认证、家庭成员权限；auth-result 与 ledger-ai 函数 |
| 数据隔离 | family_cloud.sql：RLS、auth.uid、私有 schema、安全定义 RPC |
| 同步 | SyncEngine 验证响应数量/ID/版本；成功落地后才推进索引；冲突不覆盖 |
| CI | android-ci.yml：GitHub Actions JVM 测试、lint、APK；隔离 PostgreSQL 权限测试；可选模拟器 |
| 发布 | 带版本 APK 与 Actions artifacts；生产发布需额外检查 |
| Secret | 模型密钥在服务端；本机 gh 使用系统 keyring；未读取或复制业务密钥 |
| 风险 | README 写 V1.3，源码 versionName 已为 1.6.0；必须以当前源码与 CI 为准 |

没有更改参考工程。未验证该参考项目此刻生产服务健康状态。
