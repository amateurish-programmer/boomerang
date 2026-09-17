# 数据库契约

结构唯一来源为 `supabase/migrations/202609170001_initial.sql`。采用 PostgreSQL 17；Supabase 提供 `auth.users`、`auth.uid()` 及 authenticated/service_role。测试 bootstrap 仅用于一次性隔离库。

客户端具有业务表 SELECT 权限和 owner RLS，通过三个 SECURITY DEFINER RPC 写记录，另有通知已读 RPC。RPC 不接受 owner 参数，固定 search_path，从 auth.uid() 取用户。service_role 仅获研究、证据、报告写权限与队列 RPC，不可直接更新 records 或执行用户确认 RPC。service_role 凭据不得进入客户端。迁移管理员是受信任系统管理员，不是运行时 AI 身份。

## RPC

- `upsert_record(p_record jsonb,p_expected_revision bigint,p_operation_id uuid) returns jsonb`：完整记录快照，首次 expected_revision=0，后续为当前 revision；返回 records 行。只接收用户可编辑字段，拒绝 confirmed_status 等确认审计字段。operation_id 同用户全局唯一，同一操作同一请求重放返回原响应；不同请求复用键拒绝。
- `confirm_check(p_check_id uuid,p_expected_revision bigint,p_status text,p_reason text) returns jsonb`：要求报告 record_revision 和当前 record revision 都等于 expected_revision；保存用户确认、报告引用和理由，产生新修订。该动作不是 AI 写接口。
- `accept_candidate(p_candidate_id uuid,p_operation_id uuid) returns jsonb`：要求候选拥有工具核实来源；事务内复制候选和证据到正式记录。重复确认返回同一记录，不重复写。
- `is_due(p_due_end date,p_timezone text,p_now timestamptz) returns boolean`：用户本地日期严格大于 due_end 才到期，未知日期返回 false。
- `mark_notification_read(p_notification_id uuid) returns jsonb`：仅当前用户可标记自己的通知，重复调用保留首次 read_at，返回 notifications 行。
- service_role 专用：`enqueue_job(p_owner_id uuid,p_kind text,p_dedupe_key text,p_payload jsonb) returns uuid`、`claim_jobs(p_limit integer,p_lease_seconds integer) returns setof private.jobs`、`finish_job(p_job_id uuid,p_lease_token uuid,p_success boolean,p_error text) returns void`、`consume_quota(p_owner_id uuid,p_day date,p_limit integer) returns boolean`。claim 使用 SKIP LOCKED、新租约 token、最多三次尝试；完成必须同时匹配 token 和有效租约。生产 worker 仍须对供应商调用加超时和容量上限。

SQLSTATE：22023 输入/幂等键不匹配，23514 业务约束，42501 认证或所有权错误，PT409 修订或租约冲突（PostgREST 直接映射 HTTP 409，不触发序列化失败重试）。API 应映射为 400/422、401/403、409，禁止把数据库内部错误详情原样返回。

记录类型、原话、主体、说出日期、期限区间/原文/精度、时区或验证标准发生变化时，当前行清空全部用户确认字段；此前确认仍留在不可变历史中。仅修改 notes/topic 时保留确认，避免把旧报告的结果套到新的承诺上。

## 表与约束

records 保存原话、五类记录、生命周期与独立确认结果、日期区间/精度/原文/时区、不可逆胶囊锁、revision、软删除。record_revisions 保存不可更新/删除快照；sources 保存短摘录和内容哈希，不声称完整网页存证。checks 的 suggested_status 与 records 的 confirmed_status 分离，check_sources 关联报告与证据。候选从 research_jobs 派生，accepted_record_id 保证一次入库。notifications/reminders 使用 dedupe_key。ai_sessions/messages 与业务 owner 隔离。private schema 保存队列、幂等响应、日使用量，客户端没有访问权限。

所有关联业务子表使用 `(owner_id,parent_id)` 复合外键，报告还关联 `(owner_id,record_id,revision)` 历史。业务表开启 RLS，客户端读取仅 auth.uid() = owner_id。索引支持 owner/id 分页、到期扫描、外键和领取队列。

URL 数据库校验是防御补充，只允许带域名的 HTTPS，排除 localhost、IP 字面量、内部后缀；真实搜索结果 URL 必须由服务层与百炼工具结果精确关联。数据库不抓取 URL，也不验证 DNS 解析。不得直接对模型 URL 发网络请求。

## 隔离验证

在空 PostgreSQL 17 数据库按顺序执行，任一 SQL 错误立即退出：

```sh
psql -v ON_ERROR_STOP=1 -f supabase/tests/bootstrap.sql
psql -v ON_ERROR_STOP=1 -f supabase/migrations/202609170001_initial.sql
psql -v ON_ERROR_STOP=1 -f supabase/tests/behavior_test.sql
```

测试用固定虚构 UUID，事务结束回滚业务数据。bootstrap 不适用于真实 Supabase。SQL 测试验证隔离数据库权限和行为，不代表线上部署、真实 JWT、供应商、Cron 或真机验收完成。

2026-09-17 已在一次性 Docker `postgres:17` 中执行：先运行测试，因 `upsert_record` 不存在而失败；实现迁移后，在新的空数据库重新执行迁移及行为测试，退出码均为 0，输出 `Database behavioral assertions passed`。覆盖 37 项业务/权限断言（包括异常拒绝路径），涵盖双用户、不可变历史、修订/幂等、用户确认、语义修改清除旧确认、截止日、胶囊、候选、配额及三次租约重试。并行 worker 真实并发压力测试、线上 Supabase/JWT 和 Cron 验收仍属于后续阶段。

`checks.confidence` 仅是模型输出分数，范围约束不使其成为统计概率。`sources.source_type` 为 OFFICIAL/INTERVIEW/REGULATORY/MAINSTREAM_MEDIA/SECONDARY_MEDIA/SOCIAL_MEDIA/UNKNOWN，`source_level` 为 S/A/B/C；摘录引文列名为 `quoted_text`。
