# 阶段进度

更新：2026-09-17。当前版本 0.3.0 候选，尚未标记 V1 验收完成。

| 阶段 | 代码与自动验证 | 外部验收 |
|---|---|---|
| P0 | 架构、任务书、Schema、20 操作 API 合约检查完成 | 独立 Singapore Supabase 已创建 |
| P1–P2 | Android 骨架、离线记录、历史、倒计时完成；原阶段 CI 成功 | 原版本模拟器通过，不代表本次新增功能 |
| P3 | 账号隔离、显式匿名导入、冻结同步、冲突历史、Room 迁移、原子快照完成 | 真实双用户云端 RLS/同步冒烟通过；真机双设备、邮件投递待验收 |
| P4 | 提醒、去重、通知中心、冷启动路由完成 | Android 15 模拟器28项通过；真机后台时效待验收 |
| P5 | 智能录入草稿、对话恢复、校验和额度完成 | 用户选择暂不配置百炼，真实模型调用待验收 |
| P6 | 异步研究、信源校验、候选编辑确认完成 | 实际搜索/提取质量待百炼配置 |
| P7 | 租约、重试、并发防护、通知、用户确认完成；Cron 默认关闭脚本已校验 | 实际供应商闭环待验收，生产 Cron 未启用 |
| P8 | 胶囊、证据分组、年度回顾、时间轴、分享、备份、主动周报与签名构建流程完成 | 系统分享/文件选择器、真机矩阵及真实 AI 周报待验收；签名候选已交付 |

本轮集成：Android testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest 成功，84 个 JVM 用例、0 失败；Android 15 模拟器重跑28项全部通过。后端 46/46 用例、0 跳过，含隔离 PostgreSQL 迁移、权限、任务并发和来源历史；API 20 操作检查及 Cron 脚本 5 项检查通过。

独立云项目 skeghmapzrmahxehazlp 已部署迁移 001–005、api/worker。迁移前做 schema/data 备份。真实专用双账号验证记录与来源回读、RLS、幂等、409 冲突、来源不可变历史、原子快照、候选确认权限；匿名 AI 401、缺供应商 503、普通用户调用 worker 401。测试账号/记录已清理。上述不代表真实供应商调用通过。

已恢复原任务停止点 a64bd67；研究后端提交 3ab5a5b，CI 连接参数修正 04b0ffa8 后 [35228445501](https://github.com/amateurish-programmer/boomerang/actions/runs/35228445501) 全部启用作业成功。集成提交 b1940ad，测试签名修正 b998828；[最终CI与28项模拟器](https://github.com/amateurish-programmer/boomerang/actions/runs/35232361933)通过，[签名构建](https://github.com/amateurish-programmer/boomerang/actions/runs/35231056088)成功。

私有仓库：https://github.com/amateurish-programmer/boomerang 。Git HTTPS 超时期间使用 GitHub API 上传签名 Git 对象并核对哈希；GitHub 尚未登记该签名公钥，显示 unknown_key，不能称平台 Verified。独立 Android 签名四项 GitHub Secrets 已配置；恢复副本只存本机加密忽略目录。

用户最新要求：当前阶段完成后停止。本轮只完成集成验证、提交和候选测试包交付，不开始下一阶段。百炼按用户选择暂缓，生产 Cron 保持关闭；真机验收保留待办。

## 本阶段收尾

已交付 `dist/0.3.0/boomerang-0.3.0.apk`。完整结果、校验值、首次设备失败及修正见 [集成报告](reports/INTEGRATION-0.3.0.md)。按用户要求，本阶段完成后停止；不继续下一阶段，生产 Cron 保持关闭。
