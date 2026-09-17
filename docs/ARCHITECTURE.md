# 回旋镖 V1 架构基线

依据用户提供的“规划回旋镖App”最终结论（2026-09-17），确定 Android 原生客户端 + Supabase + Qwen/百炼搜索。早期对话中的 OpenAI 路线不采用。

## 产品闭环
手动录入 / AI 提案 → 用户确认保存 → 离线镖库与倒计时 → 提醒 → AI 证据复核 → 用户确认 → 回顾与导出。
四个入口：首页、镖库、AI、我的。首页突出即将到期；详情展示原话、日期原文、验证标准、来源和修订。默认私有。

## 数据流
Compose → ViewModel → BoomerangRepository → Room（本地真实来源）/ BackendRepository（Supabase）。
Android → 经认证的 Edge Functions → ResearchService → QwenProvider/AliyunSearchProvider → 百炼工具。
Cron → 到期任务入队 → 带租约的 worker → 检查报告 + 应用内通知；Android WorkManager 辅助本地提醒。

## 状态和日期
record_type：FLAG/PROMISE/PREDICTION/STATEMENT/MILESTONE。
生命周期：DRAFT/ACTIVE/CANCELLED；最终结果另存 confirmed_status，值 FULFILLED/BROKEN/PARTIAL/DISPUTED/UNVERIFIABLE/REVISED。CHECKING 是任务派生显示，DUE_SOON 是日期派生显示，避免后台改变原话状态。
AI suggested_status 只存在 checks 中；确认须记录 confirm actor/time/check revision。用户可改选结果并填写理由。
said_at/due_start/due_end 是 ISO 日期，timezone 是 IANA 时区；UTC timestamptz 用于审计。due_end 当天仍属承诺期间，次日 00:00 后才自动复核。季度、月份保存完整区间，未知日期不猜。日历月运算不能以 30 天代替。

## 证据与 AI
sources 保留 URL、规范 URL、标题、发布者、发布时间、获取时间、短摘录、引文、来源类型/等级及内容哈希。哈希仅证明所保存摘录的一致性，不宣称完整网页存证。
搜索返回引用必须匹配真实工具返回的 URL。仅模型文本中的 URL 不算已核实信源。S/A/B/C 是来源等级，独立性人工可复核。研究候选与正式记录分表。
限制每任务最多 5 个候选、10 个来源、输入 4000 字、输出体积 128 KiB、供应商请求 45 秒、最多 3 次尝试。失败报告明确原因，不伪造候选。日额度按用户服务端原子计数。
百炼 Responses 模型和端点通过服务端配置指定，必须以账号实际支持列表和一次真实工具调用验证后上线。兼容 API 是协议形式，不意味着使用 OpenAI 服务。

## 同步
UUID 客户端生成；server revision 单调递增，客户端带 expected_revision 更新；不匹配返回冲突。软删除墓碑参与同步。历史 append-only；本地操作和 outbox 同事务。
本地库按账号隔离；退出登录取消该账号任务并清空会话，不把旧账号缓存展示给新账号。匿名数据导入先预览确认。
初版采用完整分页同步（每页 100 条，以 id 排序），不依赖设备时间作为游标。所有页成功之前不宣称同步完成。离线修改中收到旧响应不能覆盖新修改。

## 部署与验证边界
独立私有 GitHub 仓库；GitHub Actions 云端编译/测试；Codex Cloud/Codespaces 的开通由当前账号实际权限决定，不能把本机编辑叫作云端开发。
Supabase Singapore 是目标部署区域；未经真实网络测量，不承诺中国大陆延迟或可用性。未提供服务凭据前，仅验证隔离测试环境。
WorkManager 是尽力而为的后台提醒，不保证秒级准时；V1 应用内通知 + 本地通知，厂商推送后续单独集成。

## V1 排除项
社区、评论、公开人物评分、全网爬虫、iOS、Web 客户端、向量数据库。P8 趣味功能逐项交付，不牺牲证据与隐私。

## 官方依据
- [百炼联网搜索和 Responses 工具](https://help.aliyun.com/zh/model-studio/web-search)
- [Supabase 函数认证](https://supabase.com/docs/guides/functions/auth)
- [数据库迁移](https://supabase.com/docs/guides/local-development/database-migrations)
- [Cron](https://supabase.com/docs/guides/cron)

以上于 2026-09-17 核对。具体供应商型号、端点和费用以部署账号为准。
