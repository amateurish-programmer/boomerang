# 0.3.1 系统边界与兼容性验收

2026-09-19。本轮 P4 通知及 P8 文件、分享的独立验收完成，交付 0.3.1 签名候选。用户要求继续未完成阶段，百炼维持暂缓；此版本不是 V1 全量验收通过。

## 交付变化

- 通知中心从系统设置返回及权限回调时，重新读取实际通知状态；关闭通知时，所有支持版本均提供系统设置入口。
- 通知数据库显式实现 `java.io.Closeable`，修复 Android 8 旧框架中 `use` 发生类型转换异常、提醒任务反复重试的问题。数据库结构、SQL、去重和发送规则没有改变。
- 新增真实系统文件选择器、ContentResolver、FileProvider、分享面板、通知权限、Worker、账号切换及通知点击行为用例；设备矩阵覆盖 API26/33/35。

## 最终验证

源代码及测试提交：`75e9d536ed8452b5a859b7829382f1a5a45e7c4d`。[最终CI](https://github.com/amateurish-programmer/boomerang/actions/runs/35444923136)五个作业全部成功。

| 系统 | 实际执行 | 失败 | 跳过 |
|---|---:|---:|---:|
| Android8 / API26 | 43 | 0 | 0 |
| Android13 / API33 | 42（主集41+独立权限1） | 0 | 0 |
| Android15 / API35 | 42（主集41+独立权限1） | 0 | 0 |

共127次设备用例执行，不是127个不同用例。API26运行旧版设置用例，API33/35运行真实运行时权限用例；不适用的版本用例由SDK过滤，不计为通过或跳过。

本地84个JVM用例、0失败；Lint、Debug APK、设备测试APK构建通过（`.tools/api26-compat-build.log`，55s），云端再次通过。后端26个AI边界用例与20个研究/隔离SQL用例通过，0跳过；全部迁移及双用户RLS、不可变来源、原子快照和并发行为断言通过。API20操作、Cron默认关闭脚本5项检查通过。独立代码复查未发现阻塞项。

已实际查看三个系统版本的系统分享面板，API26/35的PNG及导入预览、通知设置返回状态、通知栏和记录详情。合成中文、日期、来源和用户确认标识可读，导出PNG没有私人备注。测试只打开分享面板并取消，没有向外部接收方发送内容。测试页面部分运行于隔离ComponentActivity，不能把这些截图称为真机完整UI验收。

设备HTML、PNG、系统窗口XML与环境诊断保存在 `dist/0.3.1/evidence/api26`、`api33`、`api35`；机器可读校验记录为 `dist/0.3.1/verification.json`。GitHub对应artifact为 `device-api26-reports-21`、`device-api33-reports-21`、`device-api35-reports-21`。

## 失败证据与修正

| 提交/运行 | 实际观察与处理 |
|---|---|
| `fdce546` / [35442288093](https://github.com/amateurish-programmer/boomerang/actions/runs/35442288093) | 首次系统测试暴露窗口节点失效、权限前置条件及ActivityScenario清理问题；具体失败边界见P4/P8报告，未将编译当作设备通过。 |
| `4dbf0c4` / [35443222393](https://github.com/amateurish-programmer/boomerang/actions/runs/35443222393) | 修复权限刷新、设置入口和测试同步；API26/33因模拟器数据分区磁盘不足未启动测试，API35被Pixel Launcher无响应弹窗遮挡。首次签名包随后被本次最终包替代，不交付已知有API26问题的旧构建。 |
| `7ec48ff` / [35444006687](https://github.com/amateurish-programmer/boomerang/actions/runs/35444006687) | 将编译移至模拟器启动前，限制构建资源、数据分区，仅清理临时runner不用的NDK。API33/35各42项全过；API26执行43项、10失败，暴露通知库Closeable兼容问题及无效AppOps准备。三个版本P8各7项通过，但分享面板截图早于实际绘制。 |
| `75e9d53` / 最终CI | 修复Closeable兼容，旧系统设置改为真实总开关，分享截图等待真实窗口和内容；全部矩阵通过。 |

环境调整未屏蔽ANR提示，也未削弱业务断言。最终API35系统日志保留1条Google Search后台job无响应记录，未见本应用ANR；实际界面操作与全部用例通过。不由此声称模拟器所有系统进程完全稳定，也不将早期桌面ANR的根因断言为内存不足。

## 签名安装包

[最终签名构建](https://github.com/amateurish-programmer/boomerang/actions/runs/35444925603)取自同一提交 `75e9d53`，JVM/Lint/Release构建成功；下载后本机再次校验APK v2签名通过。

- 文件：`dist/0.3.1/boomerang-0.3.1.apk`，11,783,075字节。
- 版本：0.3.1 / versionCode4；minSdk26 / targetSdk35。
- APK SHA256：`bfb8c1c50eab29f96efc19f33d2a65d242ced77d4770f852b94f9a123e745d14`。
- 独立RSA4096证书SHA256：`748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2`，与0.3.0相同。

同证书、递增版本满足0.3.0覆盖升级条件，实际手机升级与数据保留仍需验收。Debug证书不同，先导出备份再卸载安装。安装和回退见 [部署说明](../DEPLOYMENT.md)；APK、私钥不进入Git。

所有阶段提交使用实际Git身份并签名；GitHub API发布逐对象核对哈希，main没有强制更新。GitHub尚未登记该签名公钥，显示 `unknown_key`，不能称GitHub Verified。

## 云端状态与剩余验收

本轮只读核对独立Singapore项目 `skeghmapzrmahxehazlp`：迁移001–005一致，api v3 / worker v2 ACTIVE。没有修改后端业务或云端数据，没有发送验证邮件、调用供应商或启用生产Cron。9月17日真实双用户云端冒烟见 [0.3.0报告](INTEGRATION-0.3.0.md)，不是本轮重新执行的供应商验收。

真实手机的升级、邮箱验证、双设备离线冲突、厂商省电及重启后的提醒时效、第三方文件提供方和外部分享接收，按 [真机验收清单](../DEVICE_ACCEPTANCE.md)逐项记录。合成本机账号空间不替代真实登录与双设备证据。

真实百炼analyze/chat/search/extractor/周报质量，以及Cron→复核→通知→用户确认闭环仍待服务配置。遵循用户暂缓决定，生产Cron继续关闭；本轮交付后收尾。
