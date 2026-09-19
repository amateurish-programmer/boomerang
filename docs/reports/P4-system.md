# P4 系统通知边界验收（0.3.1）

日期：2026-09-19。对应 `docs/plans/2026-09-19-release-acceptance.md` 任务 2。

## 范围与当前状态

本轮围绕 Android 系统通知、本机通知中心、WorkManager 及通知点击账号边界补充设备行为用例。只使用隔离 UUID 账号命名空间和合成记录，没有调用云端账号、邮件或供应商服务。

当前新增测试已写入；生产页面的修复暂缓到基线回归验证之后。第一轮设备测试编译发现错误（误导入 Compose 成员断言、对 Room 数据库使用 `use`），已修正，等待主任务重新编译。尚未取得本轮 API 26/33/35 设备执行结果，不能标记系统边界验收通过。

## 定位结果

- `NotificationCenter` 将 `NotificationManager.areNotificationsEnabled()` 的初始结果保存在 `remember`，但没有在页面恢复到前台时重新读取。用户从系统设置开启通知后，原页面可能继续显示未开启。
- 关闭通知时只有 API 33 以上的运行时权限申请按钮。API 26–32 没有系统设置入口；API 33 以上用户拒绝后也缺少直接前往通知设置的路径。
- 既有 Worker 先持久化通知中心条目，再尝试系统通知；重复键抑制再次投递。每条记录会重新读取最新内容；延期到提醒范围以外、取消或删除时撤销对应系统提醒。账号激活持久化新的命名空间并取消旧账号工作，发送前再校验当前账号。本轮通过真实系统边界用例约束这些既有行为。

## 新增设备用例（9 项）

| 测试类 | 行为与实际观察对象 |
|---|---|
| `NotificationPermissionTest`（1 项，API 33+） | 点击真实权限弹窗的拒绝按钮；检查系统权限和 `NotificationManager` 禁用状态；执行真实 Worker 后本机通知中心仍显示条目；从真实系统设置开启后回到同一页面，提示应刷新；新提醒进入系统通知，拒绝期间的旧条目不补发重复提示。 |
| `NotificationSettingsTest`（2 项） | 全 API 的系统通知关闭状态都有设置入口；从外部系统设置返回也会刷新原页面。第二个用例独立于按钮存在性，直接覆盖旧 `remember` 状态问题。 |
| `ReminderSystemTest`（4 项） | 真实系统通知内容和隐私级别；同键重复及用户移除通知后不重复发送；最新修订替换显示，延期/取消/删除撤销旧通知；真实 WorkManager 待执行工作在切换账号后变为 `CANCELLED`，旧 Worker 不投递到新账号。 |
| `ReminderPendingIntentTest`（2 项） | 保存实际通知生成的 `PendingIntent`，切换账号后发送，确认实际 `onNewIntent` 已接收而未打开当前账号相同 UUID 记录；从系统通知栏实际点击新通知，检查打开的原话准确。 |

`TestListenableWorkerBuilder` 仅构造 Worker 参数；数据库、Worker 业务、NotificationManager、PendingIntent 和 WorkManager 任务均使用真实 Android 实现，没有 Mock 通知服务。构造 Worker 的方法依据 [Android 官方 Worker 测试说明](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl)。

权限用例必须单独运行：在启动 instrumentation 之前，由设备执行脚本撤销 `POST_NOTIFICATIONS` 并清理 `user-set`/`user-fixed`，避免测试进程内撤权终止自身。其他测试运行前允许系统通知，结束时恢复测试改变的权限/账号状态。权限测试兼容 AOSP 与 Google 镜像的权限控制器包名。

截图保存在目标应用外部文件目录 `acceptance/`，覆盖权限拒绝、开启后的通知中心、真实系统通知栏以及点击后的详情。文件生成不等于已视觉检查，待主任务下载并检查。

## 验证记录与边界

- 源码定位：已完成。
- 新增测试编译：首次失败后已修正；重编译结果待主任务填入。
- 基线失败 / 修复后通过：尚未取得，不宣称已完成 red/green。
- API 26 / 33 / 35：待主任务统一云端执行，分别记录报告。
- Git、CI 和交付：由主任务统一操作，本子任务未提交或推送。
- 未覆盖真实手机厂商的省电限制、设备重启后的实际后台延时；WorkManager 仍是尽力而为，不能承诺准点。
- 没有接入推送/FCM、云端通知合并或 P7 Cron；百炼与邮件边界维持暂缓状态。

## 自检

所有新增 `@Test` 方法显式返回 `Unit`。跨账号点击用例同时构造两个命名空间相同记录 UUID，并等待真实 Intent 到达，避免“未送达通知所以没打开”造成假通过。去重用例先撤销已显示通知后再运行 Worker，避免仅凭相同通知 ID 覆盖而误认为没有重复投递。
