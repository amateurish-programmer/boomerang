# 阶段进度

更新：2026-09-17。状态只能由验证证据推进，不以文件存在判断完成。

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 参考分析、任务书、规则、Schema/API、仓库 | 已完成文档与隔离测试 |
| P1 | Android 骨架与云端 CI | 代码完成，云端验证中 |
| P2 | 离线记录、历史、倒计时 | 未开始 |
| P3 | Auth、RLS、同步、云端部署 | 未开始 |
| P4 | 本地提醒与通知中心 | 未开始 |
| P5 | Qwen 智能录入与对话 | 未开始 |
| P6 | 搜索、证据、候选确认 | 未开始 |
| P7 | 自动复核、队列、确认闭环 | 未开始 |
| P8 | 分享、胶囊、回顾、导出 | 未开始 |

已创建私有仓库 https://github.com/amateurish-programmer/boomerang 。GitHub CLI 可管理仓库；Codespaces API 返回 403，缺少 codespace scope。Supabase/百炼独立项目配置尚未确认。

独立 Supabase 已创建：boomerang，Singapore，skeghmapzrmahxehazlp。P0 SQL隔离37项断言通过，OpenAPI16操作引用校验通过。Git HTTPS网络超时，改经GitHub API上传原始签名Git对象，哈希一致。签名公钥未登记GitHub，平台显示unknown_key，不能称Verified。

## 2026-09-17 中断恢复

原任务停于 a64bd67，现继续开发。回查云端：a64bd67 / [35223897960](https://github.com/amateurish-programmer/boomerang/actions/runs/35223897960)成功；P2 b405a3e / [35223520929](https://github.com/amateurish-programmer/boomerang/actions/runs/35223520929)成功；[35222725789](https://github.com/amateurish-programmer/boomerang/actions/runs/35222725789)包括模拟器device、android、contracts成功（对应1d0a9a6，不代表新增P3）。

研究后端3ab5a5b完成隔离46/46测试、0跳过，迁移002/003和api/worker已部署。发布后真实双用户冒烟通过：记录/来源回读、RLS、来源历史、幂等、409冲突；匿名AI401、缺百炼配置503、普通用户调用worker401。专用测试数据已清理。

该提交云端android成功，contracts失败于新测试适配器把连接URL当数据库名，正在修复；不是业务SQL断言失败。用户明确暂不配置百炼，自动Cron保持关闭。P3界面/同步、P4提醒、P5-P8客户端继续集成，未标V1完成。
