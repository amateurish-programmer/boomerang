# P5 后端：Qwen 录入与对话

日期：2026-09-17。范围仅 analyze/chat；本报告不代表 P5 Android、云端或供应商真实验收完成。

## 实现

- `supabase/functions/api/index.ts` 使用 Deno.serve；共享纯 JavaScript 模块可由 Node 执行同一处理逻辑，无外部运行依赖。
- `POST /functions/v1/api/v1/analyze` 返回 OpenAPI Draft 包装的 RecordInput，保持原话、said_at 和时区，只允许 DRAFT，sources 必须为空。未知日期只能 null，日期精度与完整月/季度/年区间一致。仅会话消息保存草稿，不写 records。
- `POST /functions/v1/api/v1/chat` 使用本人 session，服务端 Auth `/auth/v1/user` 验证访问令牌。owner 只取已验证用户 ID；客户端多余字段拒绝。读取最多最近12条消息，历史响应同样受128 KiB限制；超限安全失败。
- service_role 仅用于会话、消息、原子 consume_quota RPC。每日额度按 UTC 日计算，默认20次，可用 AI_DAILY_LIMIT（1～1000）配置。供应商失败仍消耗一次额度，防止故障重试无限花费。无自动重试。
- 单次45秒 AbortSignal 涵盖所有出站 fetch，包括响应体；输入32 KiB且text最多4000 Unicode字符，响应流累计最多128 KiB。模型 JSON 不修复、不剥离代码围栏；拒绝额外字段、假来源、工具调用、未知输出类型、非完成响应。完整草稿序列化最多16000字符，与现有消息表约束一致。
- 待确认草稿和对话存于本人 ai_sessions/ai_messages，消息对用一次 bulk INSERT。新session创建与消息写入不是跨请求事务；写消息失败可留下空会话，但不会产生正式记录。
- LLMProvider / SearchProvider 以 JSDoc 契约隔离；QwenProvider 已实现，P5不实现或启用搜索。错误只返回固定码、固定中文文案和request_id，不输出供应商原文或秘密。

## 配置与官方核对

必需：SUPABASE_URL、SUPABASE_ANON_KEY、SUPABASE_SERVICE_ROLE_KEY、QWEN_BASE_URL、QWEN_MODEL、DASHSCOPE_API_KEY。后3项缺失返回503。所有私密项只存部署Secret。

QWEN_BASE_URL为百炼官方HTTPS `/compatible-mode/v1` 基础地址，不包含 `/responses`；模型由实际账号支持列表确定，源码不硬编码型号。供应商域名仅接受阿里云域，HTTP重定向禁止。不使用OpenAI服务或SDK。

2026-09-17核对：
- [百炼联网搜索](https://help.aliyun.com/zh/model-studio/web-search)：tools挂载web_search才启用工具，P5不发送tools。
- [百炼Responses兼容接口](https://help.aliyun.com/zh/model-studio/compatibility-with-openai-responses-api)：POST `/compatible-mode/v1/responses`，完整响应`status=completed`，`output`可含reasoning和message，文本位于`content[type=output_text].text`。测试fixture依据该结构合成，不是线上抓取。

网关verify_jwt需由集成主任务明确配置；无论网关设置，本handler始终调用Auth验证，不能用anon key冒充用户。上线前需在真实网关确认路径转发及令牌算法兼容。

## 验证证据

- 首轮20项行为用例在占位501处理器上全部断言失败；实现后20/20通过。
- 增补季度边界、消息存储上限用例：观察2项200≠503红灯；修复后25/25通过。
- 增补消息对排序用例：观察缺少created_at导致顺序断言失败；补充服务端时间后26/26通过。
- 最终命令：`node --test scripts/test-ai.mjs`，26 tests / 26 pass / 0 fail。
- `node --check` 三个共享mjs模块通过。此子任务未安装Deno，未执行Edge类型检查或部署。
- 未修改SQL迁移；此处对Auth、REST、供应商均为明确合成fetch夹具，不能替代隔离SQL双用户RLS测试或真实云端网络测试。

## 未验收

尚无百炼key，未进行真实analyze/chat。提示注入测试证明用户内容隔离、规则固定及非法输出拒绝，不宣称真实模型永不受注入影响。相对日期推理的模型质量需真实供应商验证，夹具仅证明已生成完整季度可被校验。

未执行云端CI、Edge部署、真实额度/会话读写冒烟、Android闭环和真机验收。由主任务汇总提交与CI链接，并在后续P6接入真实搜索来源和异步研究队列。

## 本轮集成补记

api/worker 已部署；后端46项通过，Android全部84项 JVM 通过。Assistant 智能录入预览、会话、研究、周报 UI 已接通。云端缺配置返回503；用户明确暂不配置百炼，真实模型与周报质量仍未验收。
