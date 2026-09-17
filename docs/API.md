# API V1

契约：`openapi.json`（OpenAPI 3.1）。后端公共前缀 `https://<project-ref>.supabase.co`。所有业务调用必须携带 `apikey: <public anon/publishable key>` 和 `Authorization: Bearer <user access token>`。公开客户端 key 不是用户凭证，不能替代 JWT。

## Supabase Auth
使用官方 Auth `/auth/v1/signup`、`/auth/v1/token?grant_type=password`、`/auth/v1/token?grant_type=refresh_token`、`/auth/v1/logout`。注册成功仍可能需要验证邮箱；不把缺 session 当服务错误。Android 安全存储刷新凭据；退出注销会话并取消账号任务。

## Records 与同步
GET `/rest/v1/records?select=*&order=id.asc&limit=100&offset=0`：RLS 仅返回自己记录，包含 deleted_at 墓碑。客户端每页最多100，拉取所有页完成后再报告成功。大规模改为稳定快照游标前必须升级契约。
POST `/rest/v1/rpc/upsert_record`：body `{p_record: RecordInput, p_expected_revision: 0, p_operation_id: UUID}`。新建 expected=0；更新带已知 revision。operation_id 在同一用户内唯一，同一操作重试用原键和相同载荷。
用户 ID、server revision、审计时间由服务端产生，客户端不可指定最终 confirmed_status。删除通过 upsert 的 deleted_at 墓碑；不能硬删历史。
409 / SQL PT409 表示版本冲突，拉取远端后由用户选择，不能自动覆盖。400 校验失败；401 未认证；403 无权；429 额度；503 缺配置/暂时不可用。PostgREST 原生错误体与 Edge Error 结构不同，Android 分别解析。

## AI（Edge 路由）
统一 Edge Function 名称 `api`；路径 `/functions/v1/api/v1/*`。

| 方法 | 路由 | 输入 | 成功结果 |
|---|---|---|---|
| POST | /analyze | text, said_at, timezone | 200：draft, questions，尚未入库 |
| POST | /chat | session_id 可选, text | 200：session_id, message；回答不直接改记录 |
| POST | /research | topic, said_at, timezone, operation_id | 202：job_id,status |
| GET | /jobs/{id} | 路径 UUID | 200：status,candidates/error，只有自己可读 |
| POST | /verify | record_id, expected_revision, operation_id | 202：job_id,status |
| POST | /weekly | week_start, timezone | 200：summary,record_ids，只引用本人数据 |

候选由受控 RPC `/rest/v1/rpc/accept_candidate` 确认（p_candidate_id,p_operation_id）；结果复用同一 accepted_record_id。来源不足返回校验错误。
复核通过 `/rest/v1/rpc/confirm_check` 确认（p_check_id,p_expected_revision,p_status,p_reason）；AI 结果必须对应最新 revision。用户拒绝建议可暂不确认或改选结果，保留理由。

## 证据与通知
只读 GET `/rest/v1/sources?record_id=eq.{id}`、`checks`、`check_sources`、`record_revisions`、`notifications` 均受 RLS。用户必须先能访问所属 record。来源 URL 仅 HTTPS，不允许本机、私网地址；打开外部链接交由系统浏览器。
通知已读使用专用 RPC，不能赋予客户端写任意通知内容的权限。具体 RPC 以迁移与 OpenAPI 为准。

## 任务与服务端
任务状态 QUEUED/RUNNING/SUCCEEDED/FAILED，最多3次尝试；租约和幂等由数据库保证。worker 用服务端 secret 鉴权，不接受客户端JWT启动管理操作；它不是 Android API。
request_id 用于日志关联，日志不包含用户原话、邮箱、JWT和供应商响应全文。供应商错误向客户端统一脱敏。

## 日期和可信度
日期 `YYYY-MM-DD`；事件时区独立字段。发布时间未知用 null，不把获取时间当发布时间。置信值0～1是模型自评，界面显示“AI建议”而非事实概率。无明确日期的提案必须由用户补充或保持未知。

## 验证与变更
后端必须校验枚举、Unicode长度、日期区间、JSON对象形状、URL来源关联。OpenAPI 描述的是交付目标，各路由实现/部署状态见 PROGRESS，不能据此宣称全部在线。

