# P3 Android 账号与同步连接

已接通邮箱注册（需邮件验证时明确提示）、登录、会话刷新、安全存储和退出。本机匿名空间与每个用户使用独立 Room 文件；切换取消旧数据订阅和详情读取，清除当前表单及详情。读取登录凭据失败时不显示匿名数据冒充恢复成功，需明确退出。

匿名导入必须预览并逐项选择，再确认。按目标账号和原 UUID 生成稳定的新 UUID（含来源、历史引用），避免同一匿名记录导入 A/B 后碰撞全局主键；已导入当前账号的记录不再列为可导入；整批校验后事务提交，任何重复/非法数据使整批回滚。原匿名数据保留，不会登录即上传。

Room v2 增加持久化冲突、胶囊字段与来源 origin/verifiedByTool，1→2 显式迁移，无破坏性重建。同步使用 sync_record 原子上传记录和手工来源，固定在途快照与 expected_revision；网络失败或进程重启后重试相同内容、相同幂等键。等待响应时的新修改保留并以已确认云版本继续上传。分页全部获取成功才事务应用，删除墓碑同步。冲突展示双方内容及来源，用户选择后保留双方历史；本机胜出需再次同步。

验证证据：cloud.* JVM 共40项通过（Auth/Backend22、SyncEngine8、SyncMergePolicy6、CloudRecordCodec4）；testDebugUnitTest + assembleDebugAndroidTest 成功，日志 .tools/p3-snapshot-green.log。ACK来源保护两项先观察真实断言失败（8项中2失败，.tools/p3-sync-red.log），再修复并转绿。确认上传读取该上传 revision 的 record_source_revisions 权威来源；下载通过 get_record_snapshot RPC 原子读取记录与当前来源，避免并发版本混合；读取失败保留在途任务；无后续编辑时，ACK以新本地修订记录权威回包，避免导出历史与当前值不一致。

新增 RoomSyncTest 6项覆盖丢响应冻结快照与新编辑 ACK、权威 ACK 历史、冲突双版本、匿名导入隔离/账号 UUID 映射/整批回滚；补充胶囊冲突双方选择的锁定保护；ReminderColdStartTest 两项覆盖冷启动等待账号恢复后打开及拒绝其他账号；DatabaseMigrationTest 覆盖已有 v1 数据经 1→2 升级保留及新表字段校验；账号 UI 入口测试。前述 instrumentation 在来源保护补充前已编译，执行等待模拟器，不能称已运行通过。最后补充来源来源类型持久化及回归：保留工具/服务端来源，仅手工来源进入最多10条上传，富证据记录可编辑且不丢失已有来源；新增 codec1项与 Room富证据1项等待主任务最终全量编译验证。

尚未验收：Android 真机多账号切换、邮件实际投递、真机离线/重连双设备冲突；本报告不能代替这些验收。早期 P3-backend.md 的双账号 HTTPS 冒烟属于后端验证。

## 本轮集成补记

完整集成测试、Lint、Debug 和设备测试 APK 编译成功，84 个 JVM 用例全部通过，含最新来源保护。迁移005已部署，真实双用户原子快照及越权拒绝通过。设备执行待云端模拟器。

## 设备首轮与修正

b1940ad 的 CI35231050338：android/contracts 成功；模拟器26项通过，ReminderColdStartTest 初始化失败（Kotlin推断非void，JUnit拒绝运行）。两个冷启动测试显式声明 Unit；本地重新编译成功，并用 javap 验证两方法均为 void。设备重跑待记录。此修正只改测试，不改候选APK业务源码。

## 最终集成验收

提交263960e/b1940ad，冷启动测试方法签名修正b998828；[CI35232361933](https://github.com/amateurish-programmer/boomerang/actions/runs/35232361933)的Android、后端与Android15模拟器通过（28项设备用例、0失败）。JVM84项、后端46项通过，独立签名0.3.0候选已交付。前文“待编译/待模拟器”属当时状态，以本补记为准；真机和真实供应商仍待验收。详见 [本轮报告](INTEGRATION-0.3.0.md)。用户要求当前阶段结束即停止，未启动下一阶段。
