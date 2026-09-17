# 研究与复核 Cron 运维

当前未配置真实百炼供应商，Cron 保持未启用。源码、隔离队列测试、线上 worker 部署不等于供应商或 Cron 闭环验收。

`scripts/configure-research-cron.ps1` 默认 `Preview`：只显示没有秘密的 SQL，不连接数据库、不读取任何本机 Secret。`InstallInactive` 在一个事务中创建或更新本项目的两个任务并设为 inactive，不存在先启用再停用的提交窗口。当前任务没有执行安装或启用。

## 准备

1. 仅使用项目 `skeghmapzrmahxehazlp`。通过项目扩展设置启用 `pg_cron`、`pg_net`，确认 Vault 可用及迁移已完成。
2. 数据库连接由秘密管理环境注入 `BOOMERANG_DATABASE_URL`，另设置公开的 `BOOMERANG_PROJECT_REF`。支持本项目直连地址或带 `postgres.<project-ref>` 用户名的 Supabase pooler URL，数据库必须为 postgres。脚本不打印 URL，不把连接密码放入命令参数或临时文件；本机需安装 psql。
3. 将同一高熵 WORKER_SECRET 分别配置到 Edge Function Secret 和本项目 Vault 中名为 `WORKER_SECRET` 的条目，长度至少 32 字符。使用受控 Secret 管理流程或 Vault 控制台录入，不粘贴到版本库、SQL 历史示例、聊天、日志或 APK。脚本不会读取 `.tools/worker-secret.dpapi`，不会把秘密复制到 SQL 文件。Vault 视图仅允许受信任管理员访问。
4. 用户另行配置供应商后，先执行真实 analyze/chat、搜索提取、单任务 worker、证据落库、通知及明确用户确认冒烟，记录日期、部署提交、脱敏结果。401/503 或合成夹具不能算通过。

```powershell
# 无配置也可预览；不产生任何云端操作
./scripts/configure-research-cron.ps1

# 只有显式执行才连接数据库；仍保持 inactive
./scripts/configure-research-cron.ps1 -Mode InstallInactive

# 仅在真实供应商及worker冒烟通过，并决定启用后使用
./scripts/configure-research-cron.ps1 -Mode Enable -SupplierSmokeVerified

# 暂停两个任务，不删除业务队列和记录；已发出的HTTP请求可能仍完成
./scripts/configure-research-cron.ps1 -Mode Disable
```

## 有界执行与观察

`boomerang-enqueue-due` 每 5 分钟调用 `enqueue_due(5,20)`：最多扫描入队 5 条，按用户 UTC 日额度上限 20，截止边界仍由记录原时区判断。`boomerang-worker` 每分钟最多发一个 HTTP 请求，worker 每次最多处理一个任务；没有就绪队列或过期租约时不调用。HTTP 超时 65 秒，worker 数据库预算 60 秒、供应商 45 秒、租约 90 秒。吞吐量有限，积压必须通过观测后另行评估；不要擅自增加并发或配额。

worker 的网关 `verify_jwt=false`，应用处理器验证专用 Bearer WORKER_SECRET；不能拿用户 JWT、anon key 或 service_role 代替。HTTP 请求在执行时从 Vault 取得值，cron.job 的 SQL 只保存名字引用。pg_net 发起请求时会短暂持有 Authorization 头；限制数据库管理员及 net/Vault 内部表权限，避免把请求队列导出到普通日志。

先检查 `cron.job` 的 active，再检查 `cron.job_run_details`；SQL 调度成功只说明异步 HTTP 已排队，必须继续核对 `net._http_response` 的 status_code/timed_out/error_msg（不要导出敏感响应体），以及本项目 research_jobs、checks、notifications 和用户确认结果。函数调用失败不等于承诺未达成。验证重试与租约恢复时使用专用测试记录，不修改真实用户记录或将报告自动确认为最终结果。

当前验证仅包括 PowerShell 语法/默认预览/启用前置拒绝。未在真实 pg_cron、pg_net、Vault 扩展执行；上线扩展兼容性和实际 Cron → HTTP → 供应商 → 报告 → 通知仍待上述真实冒烟。

## 官方依据

2026-09-17 核对 [Supabase 定时调用 Edge Functions](https://supabase.com/docs/guides/functions/schedule-functions)、[Cron 创建与启停](https://supabase.com/docs/guides/cron/quickstart)、[Vault 权限与加密存储](https://supabase.com/docs/guides/database/vault)、[pg_net HTTP 与异步响应](https://supabase.com/docs/guides/database/extensions/pg_net)。本项目使用专用 worker Secret，其身份规则以实际 worker handler 为准。
