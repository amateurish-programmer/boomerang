# 云端开发、构建与部署

代码仓库：`amateurish-programmer/boomerang`（私有）。每阶段推送触发 CI，测试和安装包保留为 Actions artifacts。

## 权限
现有 GitHub CLI 已有 repo/workflow；Codespaces 需要 codespace scope。可执行 `gh auth refresh -h github.com -s codespace` 完成用户登录授权；未授权前不能创建远程开发机。
Codex Cloud 也可连接该仓库，但当前桌面任务不会自动变成 Cloud 任务。

## 专用 Supabase 项目
在已有组织中新建 boomerang 项目，区域 Singapore；不要选择记账工具的生产项目。
GitHub Secrets：SUPABASE_ACCESS_TOKEN、SUPABASE_DB_PASSWORD；Variables：SUPABASE_PROJECT_REF、SUPABASE_URL、SUPABASE_ANON_KEY（公开客户端配置）。
服务端 Secrets：DASHSCOPE_API_KEY、QWEN_BASE_URL、QWEN_MODEL、WORKER_SECRET。
Supabase 自动提供 SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY；不要复制到 Android。
先在隔离环境执行 migration/RLS 测试，再 `supabase link --project-ref ...`、`supabase db push`、`supabase functions deploy`。部署工作流使用 main 分支并禁止并发迁移。

## Auth 与通知
配置邮件发信和邮箱验证；使用专用测试邮箱验证注册登录刷新流程。应用内通知与本地 WorkManager 不依赖 FCM。
Cron 仅入队和调 worker，worker secret 存 Vault，禁止把 service_role 明文拼入版本库 SQL。

## 验收与回退
上线检查：未认证401、双用户隔离、同步往返、研究任务、到期复核、幂等重试、额度429。
每次迁移前按项目可用能力备份；回退函数版本可从已知提交部署，数据库采用向前修复 migration，不执行 drop 清库。
数据库导出只放受控存储，不作为 GitHub artifacts。APK artifact 仅包含编译产物与测试报告，不带真实用户数据。
