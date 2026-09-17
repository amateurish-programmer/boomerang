# 0.3.0 集成候选交付

2026-09-17。用户要求当前阶段完成后停止；本次只收尾已集成范围，不推进后续开发。此版本不是 V1 全量验收通过。

## 本轮交付

账号隔离与明确匿名导入、持久化同步与冲突历史、不可变来源历史及原子下载、Room 1→2 迁移；本地提醒和通知中心；AI 草稿/会话/异步研究/候选编辑/证据与明确用户确认；时间胶囊、年度回顾、时间轴、分享预览、备份预览及原子导入。导入文件不能伪造当前用户确认或工具核验来源。

后端迁移 001–005 与 api/worker 已部署到独立 Singapore 项目 `skeghmapzrmahxehazlp`。专用 worker Secret 已配置，百炼按用户选择暂缓；Cron 未启用，脚本默认只预览。

## 提交与验证

- `263960e`：候选编辑确认、原子快照、默认关闭的调度配置及测试。
- `b1940ad`：Android 集成与 0.3.0 独立签名候选构建。
- `b998828`：仅修正冷启动设备测试的 JUnit void 方法签名，补充安装和回退文档；应用业务源码与候选包相同。
- 本地 Android：84 JVM 用例、Lint、Debug APK、设备测试 APK 编译成功。
- 后端：46/46 用例、0 跳过，真实隔离 PostgreSQL 执行迁移、双用户权限、来源历史、幂等、租约和并发行为；API 20 操作检查通过；调度脚本 5 项检查通过。
- 云端真实双用户冒烟：RLS、记录/来源回读、原子快照、不可变来源历史、幂等、409 冲突、候选确认权限通过。匿名 AI 401、缺供应商 503、普通用户 worker 401。专用测试数据已清理。
- [首轮 CI](https://github.com/amateurish-programmer/boomerang/actions/runs/35231050338)：android/contracts 成功；设备 26 项通过，冷启动测试类初始化因非 void 返回失败。修复后字节码已验证为 void，重跑见下。
- [当前设备与 CI 重跑](https://github.com/amateurish-programmer/boomerang/actions/runs/35232361933)：Android、后端、Android 15 模拟器均通过；设备报告 28 项、0 失败、0 跳过。
- [签名候选构建](https://github.com/amateurish-programmer/boomerang/actions/runs/35231056088)：成功，下载后再次验证 APK v2 签名通过。

## 安装包

本机交付：`dist/0.3.0/boomerang-0.3.0.apk`，11,783,075 字节，versionCode 3，Android 8.0+（minSdk 26 / targetSdk 35）。

APK SHA256：`602ac170234eb2bcd9f54fa853dd64ea9ef865835b5ef2f08403a648510f4c71`。

独立 RSA4096 签名证书 SHA256：`748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2`。

Debug 与候选证书不同，先导出备份，再卸载 Debug、安装候选；不要直接覆盖或破坏性降级数据库。恢复策略见 [部署说明](../DEPLOYMENT.md)。APK 不提交 Git，私钥只用 Secret 和本机加密恢复副本。Git 提交已本地签名，GitHub 公钥未登记，不能称 GitHub Verified。

## 明确保留的未验收项

真实百炼 analyze/chat/search/extractor/周报质量；实际 Cron→任务→供应商→通知→用户确认闭环；真实邮件验证投递；真机双账号/双设备离线冲突、厂商后台提醒、通知权限切换、系统文件提供方及 PNG 分享。当前测试通过不替代这些验收。百炼未配置期间不以 Mock 声称线上成功，不启用生产自动复核。

下一次从此报告和 docs/PROGRESS.md 接续；本轮结束后停止。
