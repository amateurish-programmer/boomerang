# P4 系统通知边界验收（0.3.1）

日期：2026-09-19。对应 `docs/plans/2026-09-19-release-acceptance.md` 任务 2。

## 范围与当前状态

本轮围绕 Android 系统通知、本机通知中心、WorkManager 及通知点击账号边界补充设备行为用例。只使用隔离 UUID 账号命名空间和合成记录，没有调用云端账号、邮件或供应商服务。

已完成通知页面的最小修复：每次恢复前台及权限回调时重新读取系统实际通知状态；所有支持的 Android 版本在通知关闭时提供系统设置入口。Worker 和生产通知路由没有修改。

新增测试在本地编译通过后运行了 API 35 基线；其中 4 项 Worker 用例通过，权限页面用例与通知点击用例仍有下述失败。已根据失败定位修正测试生命周期清理、细分权限页面断言并保存失败现场；最新变更等待主任务重新编译与 API 26/33/35 矩阵，不能标记系统边界验收通过。

## 定位结果

- `NotificationCenter` 将 `NotificationManager.areNotificationsEnabled()` 的初始结果保存在 `remember`，但没有在页面恢复到前台时重新读取。用户从系统设置开启通知后，原页面可能继续显示未开启。
- 关闭通知时只有 API 33 以上的运行时权限申请按钮。API 26–32 没有系统设置入口；API 33 以上用户拒绝后也缺少直接前往通知设置的路径。
- 既有 Worker 先持久化通知中心条目，再尝试系统通知；重复键抑制再次投递。每条记录会重新读取最新内容；延期到提醒范围以外、取消或删除时撤销对应系统提醒。账号激活持久化新的命名空间并取消旧账号工作，发送前再校验当前账号。本轮通过真实系统边界用例约束这些既有行为。

## 新增设备用例（9 项）

| 测试类 | 行为与实际观察对象 |
|---|---|
| `NotificationPermissionTest`（1 项，API 33+） | 点击真实权限弹窗的拒绝按钮；检查系统权限和 `NotificationManager` 禁用状态；执行真实 Worker 后本机通知中心仍显示条目；从真实系统设置开启后回到同一页面，提示应刷新；新提醒进入系统通知，拒绝期间的旧条目不补发重复提示。 |
| `NotificationSettingsTest`（2 项，API 26–32） | Android 13 以前系统通知关闭状态有设置入口；从外部系统设置返回也会刷新原页面。第二个用例独立于按钮存在性，直接覆盖旧 `remember` 状态问题。 |
| `ReminderSystemTest`（4 项） | 真实系统通知内容和隐私级别；同键重复及用户移除通知后不重复发送；最新修订替换显示，延期/取消/删除撤销旧通知；真实 WorkManager 待执行工作在切换账号后变为 `CANCELLED`，旧 Worker 不投递到新账号。 |
| `ReminderPendingIntentTest`（2 项） | 保存实际通知生成的 `PendingIntent`，切换账号后发送，确认实际 `onNewIntent` 已接收而未打开当前账号相同 UUID 记录；从系统通知栏实际点击新通知，检查打开的原话准确。 |

`TestListenableWorkerBuilder` 仅构造 Worker 参数；数据库、Worker 业务、NotificationManager、PendingIntent 和 WorkManager 任务均使用真实 Android 实现，没有 Mock 通知服务。构造 Worker 的方法依据 [Android 官方 Worker 测试说明](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl)。

权限用例必须单独运行：在启动 instrumentation 之前，由设备执行脚本撤销 `POST_NOTIFICATIONS` 并清理 `user-set`/`user-fixed`，避免测试进程内撤权终止自身。其他测试运行前允许系统通知，结束时恢复测试改变的权限/账号状态。权限测试兼容 AOSP 与 Google 镜像的权限控制器包名。

独立设置用例通过 AppOps 构造 Android 13 以前的关闭状态，因此明确限制在 API 26–32；API 33 以上 `areNotificationsEnabled` 根据运行时权限判断，由真实权限用例覆盖。基线测试最初未加该版本限制；若 API 33/35 出现其设置准备失败，属于测试前置条件错误，不能记录为产品回归。

截图保存在目标应用外部文件目录 `acceptance/`，覆盖权限拒绝、开启后的通知中心、真实系统通知栏以及点击后的详情。失败时在清理前保存截图、UI XML 和 Activity 状态；权限页面另存当前阶段、ViewModel 记录 ID、错误状态及 Compose 语义树。文件生成不等于已视觉检查，待主任务下载并检查。

## API 35 基线实际结果

[基线 CI 35442288093](https://github.com/amateurish-programmer/boomerang/actions/runs/35442288093) 的 Android 构建与后端作业通过，设备作业失败。P4 原始报告保存于 `.tools/acceptance-baseline/device-api35-reports-15/`。

- `ReminderSystemTest`：4 项、0 失败，实际覆盖系统通知、重复抑制、最新修订和失效撤销、WorkManager 账号切换取消。
- `NotificationPermissionTest`：1 项失败，位于原第 51 行等待收件箱文字显示的 10 秒超时。此前真实权限拒绝、系统通知禁用、收件箱有 1 条记录、无系统通知的断言已执行通过；尚未到达设置按钮与返回刷新断言。因此这次失败不是已观测到的“缺少设置按钮/返回状态陈旧”回归，不能声称完成了该修复的 red/green。
- `ReminderPendingIntentTest`：2 项均记录为 `ActivityScenario.close` 等待 `DESTROYED` 超时，最后观察状态为 `PAUSED`；HTML 未记录更早的业务断言异常。AndroidX 的 [ActivityScenario 实现](https://github.com/android/android-test/blob/main/core/java/androidx/test/core/app/ActivityScenario.java) 以原 Intent 的 action/data/type/categories 匹配生命周期事件，实际通知到达后 `MainActivity.onNewIntent` 更换 Intent，导致测试观察器不再匹配。测试现仅在真实点击/交付及页面断言结束后的清理阶段恢复原 launch Intent，仍正常调用 `close`，没有吞掉异常或改变生产路由。
- 基线中两个独立设置用例错误地在 API 35 使用 AppOps 构造关闭状态，失败在准备阶段；现已限制至 API 26–32。这是测试前置条件错误，不算产品缺陷。
- 基线截图未保留下来，不能推断权限页面超时的具体视觉原因。下一轮权限用例使用显式 ViewModel；确认系统弹窗关闭、原 Activity 恢复且有焦点，再分别检查 Flow 条目、Compose 语义和元素可见。没有为测试手动补刷新业务数据。

## 验证记录与边界

- 源码定位：已完成。
- 新增测试初次编译：误导入 Compose 成员断言、Room 数据库不支持 `use`，修正后 `.tools/acceptance-compile-green.log` 记录 `BUILD SUCCESSFUL in 26s`（包含 `assembleDebugAndroidTest`）；用 `javap` 核对新增 9 个测试方法均为 `public final void`。最新页面修复与测试诊断变更待重新编译。
- 基线失败：已取得，具体结果如上；修复后通过尚待验证，不宣称已完成 red/green。
- API 26 / 33 / 35 完整矩阵：待主任务统一云端执行，分别记录报告。
- Git、CI 和交付：由主任务统一操作，本子任务未提交或推送。
- 未覆盖真实手机厂商的省电限制、设备重启后的实际后台延时；WorkManager 仍是尽力而为，不能承诺准点。
- 没有接入推送/FCM、云端通知合并或 P7 Cron；百炼与邮件边界维持暂缓状态。

## 自检

所有新增 `@Test` 方法显式返回 `Unit`。跨账号点击用例同时构造两个命名空间相同记录 UUID，并等待真实 Intent 到达，避免“未送达通知所以没打开”造成假通过。去重用例先撤销已显示通知后再运行 Worker，避免仅凭相同通知 ID 覆盖而误认为没有重复投递。

本轮修正后主任务执行 JVM84项、Lint、Debug和设备测试APK构建通过（.tools/acceptance-fix-build.log，1m17s）。设备矩阵结果另补，不以编译代替执行。

## 首轮完整矩阵的环境失败

[CI35443222393](https://github.com/amateurish-programmer/boomerang/actions/runs/35443222393) 的 Android 与后端检查通过。API26/33模拟器创建用户数据分区时磁盘不足（分别余7123.94/2518.71MB，需要7372.80MB），未执行应用测试；不计入通过或失败用例数。API35已取得报告与截图：Pixel Launcher无响应弹窗挡住权限弹窗和DocumentsUI，导致权限1项及P8的6项失败；通知点击2项和Worker4项通过。截图实际查看确认该系统弹窗，未发现据此需要修改产品代码的证据。

本轮环境修正：Kotlin编译移到模拟器启动前，停止预编译守护进程，设备阶段单worker/2GiB构建堆；测试数据分区限定2GiB，并只在GitHub临时runner删除本项目不用的NDK目录以留足空间。收集系统ANR、内存、CPU、磁盘诊断；不关闭ANR提示、不自动忽略失败、所有业务断言保留。2GiB分区为启动器[官方支持参数](https://github.com/ReactiveCircus/android-emulator-runner#configurations)。API35负载重叠已确认，但缺少当时资源采样，不能断言是内存不足导致系统桌面ANR。修正后的矩阵待执行。
