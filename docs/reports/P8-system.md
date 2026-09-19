# P8 系统文件与分享验收（0.3.1）

更新：2026-09-19。范围仅为已实现 P8.1 / P8.7 的 Android 系统边界，未扩展产品功能。

## 验收实现

新增 `ExtrasSystemTest`，在已注册的 debug `ComponentActivity` 中承载实际 `ExtrasScreen` 与 `ExtrasViewModel`。UIAutomator 操作系统 DocumentsUI 的 Downloads 提供方，ActivityResult 回调交给生产 ViewModel，读写走实际 ContentResolver，数据保存到真实 Room。没有替换文件选择器、注册合成 DocumentsProvider 或伪造 ActivityResult。

| 用例 | 实际边界和断言 |
|---|---|
| `downloadsRoundTripRequiresPreviewAndExplicitConfirmation` | 系统创建 JSON → 本机编辑 → 系统选择已导出文件；预览能读回原话及私人备注；取消和跳过重复均不改记录、修订、队列；用户选择替换后保留旧修订并生成新的队列操作 |
| `freshRestoreWritesRecordsHistoryAndQueueOnlyAfterConfirmation` | 同一合成空间导出后清空测试库；从 Downloads 选择文件；预览期间无记录和队列，用户确认后恢复来源、历史及待同步操作 |
| `cancellingSystemPickersLeavesRecordsHistoryAndQueueUnchanged` | 系统导出和导入选择器按返回键取消；当前记录、历史和队列保持不变 |
| `fileChosenAfterOwnerSwitchCannotPreviewOrImportIntoNewOwner` | 文件选择器打开期间切换本机 owner；旧选择结果不得进入新账号预览，两个测试空间保持隔离 |
| `exportDestinationReturnedAfterOwnerSwitchReceivesNoAccountData` | 系统保存目标返回前切换 owner；新建系统文件保持 0 字节，重新选择只产生解析错误，不包含任一账号资料 |
| `contentResolverRejectsCorruptOversizedAndWrongOwnerFilesWithoutWriting` | 使用实际 FileProvider / ContentResolver 读取损坏 JSON、5 MiB + 1 字节及其他 owner 文件；全部不得产生预览和数据库写入 |
| `pngPreviewUsesRestrictedFileProviderAndOpensSystemChooserOnlyOnClick` | 实际 Canvas PNG 头、尺寸及文字像素，排除私人备注；真实 URI 可读且 MIME 正确，仅授予读权限，应用私有目录不能变成共享 URI；用户点击后才打开系统 ChooserActivity，随后取消 |

全部样例只含合成原话与 `example.org` 来源，不登录、不请求云端、不实际发送到第三方应用。测试使用随机 UUID 数据库与文档文件名；完成后只清理各自测试数据。`database.clearAllTables()` 只在 instrumentation 的合成库恢复用例中使用，生产迁移策略未改动。

账号切换用例覆盖本机 owner 状态与系统回调隔离，不等同于真实双账号登录/邮件或双设备同步验收。分享边界验收到系统分享面板和 FileProvider 可读 URI，没有测试外部接收应用最终发送成功。

## 证据与状态

- 新增 7 项设备用例，所有 JUnit 测试方法显式返回 `Unit`。
- PNG 和界面截图计划由设备测试保存在应用外部文件目录 `acceptance/`：`p8-apiXX-share-card.png`、`p8-apiXX-share-preview.png`、`p8-apiXX-import-preview.png`、`p8-apiXX-system-chooser.png`；失败附带截图和 UI 层级。
- 本文初次写入时尚未运行本轮 Gradle 或设备测试，不能将“测试源码已添加”记为通过。主任务统一执行编译及 API 26 / 33 / 35 矩阵，并补充实际用例数量、结果、CI 和视觉核验。
- 当前未修改生产 extras 实现。若设备运行暴露问题，先保留实际失败证据再作针对性修复。

### 首轮 Android 15（API 35）结果

[CI 35442288093](https://github.com/amateurish-programmer/boomerang/actions/runs/35442288093) 的 `ExtrasSystemTest` 实际执行 7 项：6 项通过、1 项失败、0 跳过。通过项覆盖系统选择器取消、从空库预览确认恢复、两个账号切换回调边界、ContentResolver 非法文件拒绝，以及 PNG / FileProvider / 系统分享面板。

失败项为 `downloadsRoundTripRequiresPreviewAndExplicitConfirmation`：首次打开导出选择器时，`awaitDocuments()` 读取先前获取的 `UiObject2.applicationPackage`，系统窗口刷新导致 `StaleObjectException`；堆栈在测试辅助方法，尚未到该用例的导入/替换断言。这是测试界面同步问题，不能记为该往返用例通过，也没有据此修改生产行为。

修正只涉及辅助方法：在原有 10 秒上限内，每次重新读取前台包名并验证其为 Android / Google DocumentsUI 的准确包名，同时确认对应界面存在，再返回该包名；不跨窗口变化保留 accessibility 节点。全部业务断言保留。修正后的设备重跑、API 26 / 33 结果及截图视觉检查仍待主任务补记，当前不宣称 7 项全部通过。

## 仍待验收

- 辅助方法修正后的设备重跑、API 26 / 33 与截图实际结果，待主任务补记。
- 第三方 DocumentsProvider、真实设备系统文件应用与外部分享接收方仍须真机验收。
- PNG 文字排版须查看实际截图后记录；像素和文件头断言不等同于视觉通过。
- 本任务不启用生产 Cron，也不声称百炼、真实 AI 周报或 V1 全部验收通过。

本轮修正后主任务执行 JVM84项、Lint、Debug和设备测试APK构建通过（.tools/acceptance-fix-build.log，1m17s）。设备矩阵结果另补，不以编译代替执行。

## 首轮完整矩阵的环境失败

[CI35443222393](https://github.com/amateurish-programmer/boomerang/actions/runs/35443222393) 的 Android 与后端检查通过。API26/33模拟器创建用户数据分区时磁盘不足（分别余7123.94/2518.71MB，需要7372.80MB），未执行应用测试；不计入通过或失败用例数。API35已取得报告与截图：Pixel Launcher无响应弹窗挡住权限弹窗和DocumentsUI，导致权限1项及P8的6项失败；通知点击2项和Worker4项通过。截图实际查看确认该系统弹窗，未发现据此需要修改产品代码的证据。

本轮环境修正：Kotlin编译移到模拟器启动前，停止预编译守护进程，设备阶段单worker/2GiB构建堆；测试数据分区限定2GiB，并只在GitHub临时runner删除本项目不用的NDK目录以留足空间。收集系统ANR、内存、CPU、磁盘诊断；不关闭ANR提示、不自动忽略失败、所有业务断言保留。2GiB分区为启动器[官方支持参数](https://github.com/ReactiveCircus/android-emulator-runner#configurations)。API35负载重叠已确认，但缺少当时资源采样，不能断言是内存不足导致系统桌面ANR。修正后的矩阵待执行。
