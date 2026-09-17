# P6/P7 后端：可追溯研究与租约复核

日期：2026-09-17。范围：后端实现与隔离验证，不含 Android、真实百炼、线上 Cron 验收。

## 接口和流程

- `POST /functions/v1/api/v1/research`：Auth服务验证用户，严格topic/said_at/timezone/operation_id输入，数据库原子入队与首次扣额，返回202。不在HTTP请求中研究。
- `POST /functions/v1/api/v1/verify`：查询本人记录后交给RPC校验revision/ACTIVE/未删除/未确认；相同operation_id重放复用原任务，语义不同拒绝。
- `GET /functions/v1/api/v1/jobs/{id}`：只查询owner匹配任务，返回状态和Candidate包装；proposal只存RecordInput，confidence独立列，sources单独存储。整体响应最多128 KiB。
- `POST /functions/v1/worker`：仅独立WORKER_SECRET（Authorization Bearer）验证，不接受普通用户JWT；单次最多1个任务。90秒租约，供应商请求45秒，出站数据库调用60秒预算；最多3次尝试，数据库30×attempts秒退避。
- `AliyunResearchProvider`隔离Qwen Responses供应商；`ResearchService`负责执行与事务完成/失败RPC。P5离线Provider仍不挂载工具，P6仅挂web_search和web_extractor，max_tool_calls=6，返回也验证次数，最多5候选、总10信源、128 KiB。
- 仅实际`web_search_call.action.sources`中逐字匹配的HTTPS URL可进入证据。拒绝IP、本机/私网字面链接、内部伪域名和非443端口。此层不自行抓取URL，也不把模型自报引用当真实工具结果。域名的将来DNS变化/目标站重定向不由静态URL校验担保。
- 引文必须包含候选原话；未知说出日期/发布时间保持null，截止未知null。内容哈希由服务端对保存摘录计算，不宣称网页完整存证，也不保证模型提炼摘录必然逐字正确。来源等级/置信值仍是模型建议，独立性不按URL数量推断。
- 零候选是合法成功，不虚构内容或浪费重试。实际候选至少一条来源。复核零来源只允许UNVERIFIABLE，AI不会确认结果。

## 新迁移

`202609170002_research.sql`为新增迁移；未改001。新增candidates.confidence，撤销service_role对证据/候选/check的直接INSERT，统一通过租约栅栏事务落库。未授予service_role修改records权限。

精确RPC签名（均仅service_role）：

```sql
public.enqueue_research(p_owner_id uuid, p_kind text, p_input jsonb,
                        p_operation_id uuid, p_limit integer DEFAULT 20) RETURNS jsonb
public.complete_research_job(p_job_id uuid, p_lease_token uuid,
                            p_result jsonb) RETURNS void
public.fail_research_job(p_job_id uuid, p_lease_token uuid,
                        p_error text) RETURNS void
public.enqueue_due(p_limit integer DEFAULT 100,
                   p_daily_limit integer DEFAULT 20) RETURNS integer
public.claim_jobs(p_limit integer DEFAULT 1,
                  p_lease_seconds integer DEFAULT 90) RETURNS SETOF private.jobs
```

- enqueue返回`{job_id,status}`；kind为RESEARCH/CHECK；input分别为`{topic,said_at,timezone}`/`{record_id,expected_revision}`，不带operation_id。
- 相同owner+operation_id在事务锁内只首次扣UTC日额度；重试不扣。额度错误PT429，归属PT403，版本/租约PT409。
- complete锁job、验证lease_token和有效期，在同一事务中写candidate/source或check/check_source、完成任务并通知；证据写完后以clock_timestamp再次检查租约，超时全回滚。CHECK持有记录共享锁验证当前revision。
- fail仅接收固定脱敏错误码；过期worker不能失败/完成新租约。STALE_RECORD直接终态。第三次失败或最后一次租约崩溃过期均同步public状态并发一次通知。
- enqueue_due按用户时区次日判断到期、每record/revision唯一任务。筛除已有任务在LIMIT之前，避免旧记录阻塞后续；限额不足不创建孤立public任务。SQL不含HTTP Secret。
- 用户RPC accept_candidate在明确确认时将候选DRAFT转ACTIVE；复制信源，同一候选重复确认复用记录。确认结果仍只可调用authenticated的confirm_check。

## 测试证据

命令（已存在隔离容器和bootstrap角色）：

```powershell
$env:RESEARCH_SQL_CONTAINER='boomerang-sql-test'
node --test scripts/test-ai.mjs scripts/test-research.mjs
node --check supabase/functions/_shared/research.mjs
```

本次续查结果：45 tests、45 pass、0 fail、0 skip，随后新增真实双连接并发领取套件单独通过。包括P5原26项、16项研究HTTP/供应商合成夹具、3个隔离SQL场景套件；第一套同时执行原`behavior_test.sql`及来源同步`sources_test.sql`全回归。新增第4个SQL套件用两个独立PostgreSQL连接和service_role并发领取，断言仅一方获得唯一任务且attempts=1；不是顺序调用模拟并发。

续查发现外层Responses标记completed时，内部failed搜索调用仍可能把其URL列入可信集合。新增夹具先复现失败，再要求搜索/提取调用本身status=completed，回归通过。此检查不代表已进行真实百炼调用。

测试脚本新增CI模式`RESEARCH_SQL_URL`，仅接受localhost回环地址；在CI bootstrap角色后运行，通过本机psql创建和销毁各套件隔离数据库。本地Docker模式已通过，CI连接模式待云端运行确认。

SQL每套创建独立新数据库、执行bootstrap和所有迁移、测试后删除该测试数据库；没有操作生产数据。覆盖幂等只扣1次、额度事务无孤儿、双用户RLS/service-only权限、租约独占/重领/失效、第三次失败、崩溃耗尽、终态通知、候选成功分表、用户确认激活/重复确认、AI不能确认、用户confirm、旧revision回写全回滚、Cron复扫去重和新revision新任务、零候选成功。

红绿记录：新增RPC不存在断言先失败；13项HTTP/输出校验在501占位实现上失败后通过；成功链路发现候选确认仍DRAFT，先红灯后修复；空结果和聚合响应体积分别先失败后修复。最终所有测试重跑通过。

## 配置与未验收

沿用P5供应商/Supabase Secret，新增独立WORKER_SECRET。网关worker需配置verify_jwt=false，由handler的独立Secret验证。Cron调用enqueue_due和唤醒worker的凭据应由部署层Secret/Vault维护，本迁移不写Secret。当前未配置真实百炼，建议Cron保持inactive，避免对无法执行的研究收费。

已按[百炼联网搜索官方文档](https://help.aliyun.com/zh/model-studio/web-search)核对Responses工具及`web_search_call.action.sources`结构；测试均明确是合成响应。实际账号支持型号、max_tool_calls实际约束、真实搜索质量、摘录准确性及真实HTTP/租约时序必须用该账号真实调用复核。web_extractor响应本身不额外扩张可采信URL集合，仅search工具明确返回的URL可采信。

未执行真实供应商、线上Edge部署、实际Cron触发、云端双账号研究/复核/通知/确认闭环或Android验收。提交/CI/部署链接由主任务汇总，不能据隔离测试标P6/P7最终通过。

## 集成部署补记

主任务重跑46/46通过、0跳过。提交3ab5a5b，迁移002/003、api、worker已发布到专用项目。迁移前schema/data备份位于忽略目录，不上传产物。线上双用户来源同步/历史/幂等/冲突与RLS冒烟通过；匿名401、缺供应商配置503、普通用户调用worker401，测试数据已清理。专用WORKER_SECRET已配置，百炼按用户选择暂缺，Cron未开启。

CI35227821542 Android成功，研究SQL适配器PGDATABASE误用URL失败；改为单独PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE后等待新CI。真实供应商与自动复核闭环仍未验收。
