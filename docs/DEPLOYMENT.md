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

## 当前候选签名与恢复

0.3.0 为验收候选版本。手动运行 `release.yml`，读取四项独立 Android 签名 Secrets，执行 JVM/Lint/Release 构建与 apksigner 校验；产物名称 `boomerang-signed-candidate-*`。未配置签名时禁止生成可交付的 unsigned release。独立私钥的本机恢复副本为忽略目录中的 DPAPI 加密文件，绑定当前 Windows 用户；更换机器前须由该用户解密后转存受控秘密存储，不可提交或作为构建产物上传。

Debug 与签名候选使用不同证书，同 applicationId 不能直接覆盖安装。先在应用中导出 JSON 备份，再卸载 Debug 包、安装候选包；导入必须预览确认，外部文件的历史确认不直接成为当前正式结果。云端记录可登录后重新同步。后续候选使用同一独立发布证书及递增 versionCode。

客户端回退先导出备份；Android 不支持安全降级覆盖安装，Room v2 不得用旧 v1 数据库代码强行打开。优先提交向前修复、递增版本并保留显式迁移。迁移 001–005 已发布，不改写历史 SQL。生产 Cron 当前关闭，后续操作见 [研究调度说明](RESEARCH_CRON.md)，未做真实百炼冒烟前保持关闭。
