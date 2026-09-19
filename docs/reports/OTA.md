# Android OTA 阶段报告

日期：2026-09-19。结果：0.4.1/code6 已云端发布，vivo V2430A（Android16/API36）已从0.4.0完成应用内OTA；已安装包与云端摘要一致，本机验收记录保留。本OTA阶段完成，未标记V1全量验收完成。

## 部署与范围

业务服务位于独立 Singapore Supabase `skeghmapzrmahxehazlp`，不是电脑本地服务器。OTA使用同项目专用 `app-updates` 公开读取桶；私有GitHub仓库负责源码、CI与签名构建。业务数据库、迁移、账号同步和研究函数未改；百炼继续暂缓，生产Cron保持关闭。

“我的 → 版本与更新”独立于登录，提供检查版本、更新说明、下载进度、取消/重试、完成文件恢复、包级安装权限与系统确认安装。每次下载完成、恢复及安装前检查大小、SHA256、包名、版本、minSdk、固定发布证书和当前安装证书。只允许项目固定HTTPS路径，限制清单/APK体积并拒绝重定向。

发布器从实际APK提取身份并验证签名；APK按versionCode不可覆盖，公开回读核验后才提升最新清单。跨机器发布锁串行化操作，重试仍验证公开清单。工作流 `publish_ota` 默认关闭。操作与恢复见 [OTA运维说明](../OTA_OPERATIONS.md)。

## 验证与提交

| 项目 | 实际证据 |
|---|---|
| 本地Android | 98项JVM，0失败/0跳过；`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`成功；Lint为0错误、22警告 |
| 发布器 | 16项离线行为测试通过；实际签名APK经aapt/apksigner验证，包括Windows中文临时路径 |
| Android8/API26 | 47项设备测试通过，0失败/0跳过 |
| Android13/API33 | 46项设备测试通过（权限1+其余45），0失败/0跳过 |
| Android15/API35 | 46项设备测试通过（权限1+其余45），0失败/0跳过 |
| 数据库与接口 | 同次CI中隔离迁移、双用户RLS/同步、研究队列和20操作合约检查通过 |
| 全矩阵CI | [35447737145](https://github.com/amateurish-programmer/boomerang/actions/runs/35447737145)，Android源码5daad3a，成功 |
| 发布修复CI | [35448174295](https://github.com/amateurish-programmer/boomerang/actions/runs/35448174295)、主分支[35448433975](https://github.com/amateurish-programmer/boomerang/actions/runs/35448433975)，成功 |
| 签名与真实云发布 | [35448187387](https://github.com/amateurish-programmer/boomerang/actions/runs/35448187387)，产物源码2a890ca，成功 |

实现提交 `346408c0c8d951dff8f160184fcf70ddd70214a9`；版本提交 `5daad3a6b89a4546d9fe6234fd46bf9e022a1636`；发布器修复 `2a890ca310533d2074e19af8d1687ad08a9e4f3d`，均已签名发布并整合到main。后两个提交之间没有Android源码变化，矩阵证据适用于最终APK代码。GitHub签名状态unknown_key，不称为Verified。报告收尾提交不改变APK来源。

独立审查及实际测试发现并修复：Windows重试元数据替换、Unicode码点长度、原始上传缓存指令、HTTP自动解压长度、相同版本重试的公开清单核验。相关用例先失败后通过。设备测试包编译与上述实际执行结果分别记录。

## 真实云端验收

首次签名工作流 [35447739377](https://github.com/amateurish-programmer/boomerang/actions/runs/35447739377) 构建成功、发布失败，没有提升清单。原桶配置64MiB超过项目全局上限，真实API复现HTTP400、内部413/EntityTooLarge。修复为新桶继承全局限制，已有桶按本次APK/清单/锁实际字节检查，客户端64MiB安全上限保留；两项新增回归和独立复审通过。

最终公开清单及APK均通过无凭据下载和摘要校验。清单缓存头 `public, max-age=0`，不可变APK缓存头 `public, max-age=31536000, immutable`。Storage对象与桶均启用RLS，无客户端写策略；真实匿名上传被拒绝（HTTP400，内部403，row-level security）。客户端没有签名私钥或写凭据。

- 清单：[android/latest.json](https://skeghmapzrmahxehazlp.supabase.co/storage/v1/object/public/app-updates/android/latest.json)
- APK：[0.4.1安装包](https://skeghmapzrmahxehazlp.supabase.co/storage/v1/object/public/app-updates/android/6/app.apk)
- 发布时间：2026-09-19T14:19:35.014Z。
- 大小：11,815,931字节；SHA256：`f6a328e6022ea42f7b2a10a20b1f0bba5559d86d7a8f3eb09fde3e3d8ade387a`。
- 证书SHA256：`748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2`。

## vivo手机实际闭环

1. 原安装0.3.0/code3，证书与项目一致。添加本机记录 `OTA_ACCEPTANCE_20260919_KEEP_AFTER_UPDATE`，标准 `Record preserved after OTA upgrade`，保存升级前证据。
2. USB仅用于同证书覆盖安装0.4.0/code5，引入OTA入口。系统返回Success，原记录仍在；没有卸载或清数据。
3. 发布前检查显示服务暂不可用；发布后同一入口真实读取0.4.1说明，下载后显示安装包校验通过。
4. 终止并重新打开本应用，再进入更新页，显示安装包已重新校验、可以安装。证明完成文件跨进程恢复；没有向应用私有目录注入或复制APK。
5. 包级安装权限入口返回后可安装，实际app-op为allow；随后手机经系统安装完成0.4.1/code6。本阶段没有执行USB安装0.4.1。用户也参与手机操作，系统确认过程没有完整截图，不宣称全程无人参与。
6. 首次安装时间仍为2026-09-17 22:27:36，更新时间2026-09-19 22:25:07；镖库仍显示上述验收记录。从已安装包读取的SHA256、签名、版本与云端完全一致。

下载较快，尝试取消时已完成，故手机中途取消未独立验收；取消、截断、超长、篡改、重试由行为测试覆盖。记录保留结论针对本次验收记录，不外推为双账号/双设备所有数据情形。恢复的是应用进程，未冒充整机重启或长期后台验收。

## 交付与后续边界

交付 `dist/0.4.1/boomerang-0.4.1.apk`，公开清单、云端检查、设备报告及受控截图/XML在 `dist/0.4.1/evidence/`。0.4.0引导包另保留于 `dist/0.4.0/`，SHA256 `4425ef3b6a52c979cbdd046424319556bd0fff6f0be21f4fac23b1b217dd394c`。只归档受控页面，不收录用户后续编辑的私人草稿。

本阶段在OTA交付后停止。百炼真实调用、邮件投递、双设备同步及厂商长期后台提醒仍按既有清单另行验收；本次不能据此标记V1全部完成。
