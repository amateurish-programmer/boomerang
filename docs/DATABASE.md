# 数据库契约

结构唯一来源为按名称排序的 `supabase/migrations/*.sql`：001 基线、002 研究队列、003 来源同步。采用 PostgreSQL 17；Supabase 提供 `auth.users`、`auth.uid()` 及 authenticated/service_role。测试 bootstrap 仅用于一次性隔离库。

客户端具有业务表 SELECT 权限和 owner RLS，通过 SECURITY DEFINER RPC 写记录，另有通知已读 RPC。用户 RPC 不接受 owner 参数，固定 search_path，从 auth.uid() 取用户。002 撤销 service_role 对来源、候选和报告的直接 INSERT，证据仅由租约事务 RPC 保存；不可直接更新 records 或执行用户确认 RPC。service_role 凭据不得进入客户端。迁移管理员是受信任系统管理员，不是运行时 AI 身份。

## RPC

- `sync_record(p_record jsonb,p_sources jsonb,p_expected_revision bigint,p_operation_id uuid) returns jsonb`：P3 同步首选入口，返回 records 行；`p_record` 使用 upsert_record 的字段，`p_sources` 为至多 10 个 `{id: UUID,title: string(1..500),url: HTTPS URL}`，拒绝其余字段。owner、origin、verified_by_tool 等信任字段只由服务端赋值。记录、来源、来源历史和幂等响应同事务；一次同步只生成一条记录修订，任一步失败全量回滚。同操作键要求完整 record/sources/expected 请求一致。

  来源内容同 UUID 不可改，改标题或 URL 须生成新 UUID。仅 `origin=CLIENT` 且未被工具核实的手动来源参与传入列表对齐，遗漏者填写 `archived_at`，相同内容重新加入可恢复。SERVER/工具证据始终保留；已被报告引用的手动来源不得移除（PT409）。客户端显示活动来源用 archived_at IS NULL，报告仍可读取归档证据。每次 sync_record 的活动来源完整快照保存于只读、不可改删的 `record_source_revisions`，按 owner/record/revision 关联原修订；不会改写 001 中的历史快照。此前或通过 upsert_record/confirm_check 产生的修订没有来源成员快照，不能由缺失快照推断当时没有来源。

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
for migration in supabase/migrations/*.sql; do psql -v ON_ERROR_STOP=1 -f "$migration"; done
for test in supabase/tests/*_test.sql; do psql -v ON_ERROR_STOP=1 -f "$test"; done
```

测试用固定虚构 UUID，事务结束回滚业务数据。bootstrap 不适用于真实 Supabase。SQL 测试验证隔离数据库权限和行为，不代表线上部署、真实 JWT、供应商、Cron 或真机验收完成。

研究行为测试另执行 `node --test scripts/test-ai.mjs scripts/test-research.mjs`。本机设置 `RESEARCH_SQL_CONTAINER=boomerang-sql-test`，或 CI 设置 `RESEARCH_SQL_URL` 指向 localhost 的一次性 PostgreSQL 管理连接。必须先用 bootstrap 建立测试角色；各研究套件自动新建独立数据库、执行所有迁移并在结束后删除。连接凭据仅通过环境变量交给 psql。未设置任一 SQL 环境变量时，SQL 套件明确 skip，不可据此宣称数据库验收通过。

2026-09-17 已在一次性 Docker `postgres:17` 中执行：先运行测试，因 `upsert_record` 不存在而失败；实现迁移后，在新的空数据库重新执行迁移及行为测试，退出码均为 0，输出 `Database behavioral assertions passed`。覆盖 37 项业务/权限断言（包括异常拒绝路径），涵盖双用户、不可变历史、修订/幂等、用户确认、语义修改清除旧确认、截止日、胶囊、候选、配额及三次租约重试。并行 worker 真实并发压力测试、线上 Supabase/JWT 和 Cron 验收仍属于后续阶段。

`checks.confidence` 仅是模型输出分数，范围约束不使其成为统计概率。`sources.source_type` 为 OFFICIAL/INTERVIEW/REGULATORY/MAINSTREAM_MEDIA/SECONDARY_MEDIA/SOCIAL_MEDIA/UNKNOWN，`source_level` 为 S/A/B/C；摘录引文列名为 `quoted_text`。
