# Android 在线更新发布

在线更新使用本项目 Singapore Supabase 的 `app-updates` 公开读取存储桶。安装包和更新说明为公开内容，勿含私人记录。业务数据库、研究任务和 Cron 不参与发布。

固定清单地址：<https://skeghmapzrmahxehazlp.supabase.co/storage/v1/object/public/app-updates/android/latest.json>。格式见 [ota-manifest.schema.json](ota-manifest.schema.json)；下载地址中的十进制版本号必须与 `versionCode` 完全相同，校验器额外检查此跨字段规则。APK 上限 64 MiB，清单上限 64 KiB，更新说明 1–4000 字符。

## 发布前提

- Node.js 22 或更新版本、Java 17、`ANDROID_HOME` 指向安装了 `build-tools;35.0.0` 的 Android SDK。
- 已通过阶段验证的 release APK，包名 `com.boomerang.app`；版本号大于线上版本，最高 2100000000，minSdk 至少 26。
- 必须使用原发布证书：SHA256 `748d6f30358c0be6b96e1ae29cd2538659f7b8f09ac1a40649f20bf26f08afd2`。
- 本项目 service-role key 仅通过环境变量或 GitHub Secret `BOOMERANG_SUPABASE_SERVICE_ROLE_KEY` 提供。不要把值放入命令参数、说明文件、APK、输出文件或日志。

## 手工发布

先通过现有 Secret 管理方式向当前进程注入上述环境变量，再执行：

```text
node --test scripts/test-ota.mjs
node scripts/publish-ota.mjs --apk dist/0.4.1/boomerang-0.4.1.apk --notes dist/0.4.1/ota-notes.txt
```

说明文件使用 UTF-8。CLI 没有版本、签名、项目地址或远程 URL 覆盖参数；它将读到的 APK 字节保存到专用临时文件，通过 `aapt dump badging` 和 `apksigner verify --verbose --print-certs` 验证同一份字节，并从 APK 提取包名、版本和 minSdk。通过后才使用服务凭据访问固定项目。

成功时标准输出为公开清单，可保存作证据。失败仅输出脱敏提示和非零退出码，不输出供应商响应或嵌套异常。若发生网络中断，先检查线上清单与锁状态，再使用相同 APK 和相同更新说明重试。

## GitHub Actions

运行 `Signed candidate APK`。默认 `publish_ota=false`，只验证和构建签名候选。需要实际发布时设置 `publish_ota=true`，填写 `ota_notes`；APK 构建、签名校验、测试和产物上传成功后才执行发布。说明通过环境变量写入文件，不拼接执行。仓库 Secret 使用上面的项目专用名称。

工作流 `signed-candidate` 并发组不取消正在运行的发布。不要在构建后改变版本或替换 APK；发布器会再次检查实际 APK。

## 发布顺序与恢复

1. 首次创建专用 `app-updates` 桶：公开读取，只接受 APK/JSON MIME；不指定桶级 `file_size_limit`，继承项目现有全局限制。协议与下载校验的 64 MiB 是安全上限，不保证云项目允许该大小；Supabase Free 项目的全局上限不能超过 50 MB，桶级限制不能超过全局限制。若桶已存在，只读取核对公开属性和 MIME，并确认显式桶级限制能分别容纳本次 APK、序列化清单和锁文件中的最大实际字节数；不要求桶支持完整 64 MiB。私有或不兼容的桶立即停止，不自动改变访问权限或暴露已有文件。全局限制仍由 Storage 在实际上传时执行，超限失败不会提升清单。发布器不创建匿名或已登录客户端的写策略；所有写操作使用 service-role。公开桶的访问规则仍须审查，避免另有宽泛 Storage 写策略覆盖此桶。
2. 以禁止覆盖的对象创建获取 `android/publish.lock`，串行化本机和 CI 发布。锁内只存随机 ID 与开始时间，不含密钥。
3. 通过认证读取现有清单，拒绝降级及同版本不同 APK、名称、SDK 或说明。
4. 上传 `android/<versionCode>/app.apk`，`x-upsert=false`，原始请求体使用完整 HTTP 头 `Cache-Control: max-age=31536000, immutable`。已有文件不能覆盖；中断重试必须验证已有公开文件与本次 APK 的长度和 SHA256 完全一致。
5. 不带凭据读取公开 APK，流式限制响应体积并验证字节数、SHA256；禁止重定向。任何失败均不更新清单。
6. 最后提升 `android/latest.json`，原始请求体使用 `Cache-Control: max-age=0`，再从公开地址校验清单。相同版本和相同内容重试保留原始发布时间，并同样重新验证公开清单。响应大小限制始终作用于解压后的字节，压缩响应不把网络传输长度误当为解压长度。原始二进制/JSON 上传必须提供完整缓存指令，不能使用 multipart SDK 的纯数字缓存时间形式。
7. 在成功或异常路径释放本次取得的锁。每次请求（含响应读取）最多 120 秒，无无限重试。若进程被杀死、断电或释放锁的请求失败，锁可能保留；不能仅凭锁年龄判断原发布已停止。

恢复遗留锁时，先确认 GitHub 和所有本机发布进程已经结束，并检查公开 `latest.json` 及目标版本 APK。只有确认没有活动发布者后，使用本项目 Supabase 控制台删除单个 `app-updates/android/publish.lock`，再重试。不要清空桶或删除已发布版本。上传已成功但清单未提升的 APK 可以保留，重试会验证它；同版本有冲突则分配更大的版本号。

清单提升已成功但其公开验证暂时失败时，脚本返回失败，线上可能已是新版本。核查公开清单后原样重试；不要为了回退而覆写旧版本。修复应发布更大的 `versionCode`，保留原有签名。不要删除应用或清除数据来回退。

## 验收边界

离线 Node 测试使用隔离 HTTP 响应，证明校验、顺序和失败处理，不能替代真实云上传。APK 元数据与签名校验不能替代手机安装验收。发布后分别记录公开下载哈希、实际清单、手机升级前后版本/签名，以及已有记录保留情况。未知来源权限和系统安装确认由用户在应用中明确操作。

参考：[Supabase Storage API](https://supabase.com/docs/reference/self-hosting-storage/introduction)、[全局和桶级文件限制](https://supabase.com/docs/guides/storage/uploads/file-limits)、[公开桶与访问控制](https://supabase.com/docs/guides/storage/security/access-control)、[Android APK 签名验证](https://developer.android.com/tools/apksigner)。
