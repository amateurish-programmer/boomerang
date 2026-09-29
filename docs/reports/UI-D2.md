# UI-D2：水墨武侠核心界面改版

日期：2026-09-29。状态：核心界面实现与模拟器验收完成，尚未签名发布。

## 交付范围

用户确认“宣纸朱砂”方案，并要求移除品牌旁红色小印章。已完成 Compose 明暗主题、底部导航、首页、镖库、记录编辑和详情。

- 纸色背景、墨色正文、朱砂主操作，系统 Serif 标题与 SansSerif 正文，不新增字体资源；品牌区远山无印章。
- 首页固定新建入口；镖库的搜索、筛选、排序、记录整页滚动。筛选先暂存，取消不改变结果，应用才提交。
- 详情分为验证、来源、历史，固定编辑；删除与胶囊封存收纳至更多，保留确认。
- 编辑保留全部字段、来源与错误提示，隐藏区错误自动展开；固定保存、避让真实键盘，忙碌时禁止返回与修改。
- 未修改 ViewModel、Repository、数据库、网络、依赖或版本号。原话、证据、AI 建议、用户确认、未知日期、时区、截止包含当天及历史快照仍按原契约呈现。

依据：[UI-D1](UI-D1.md)、[已移除印章的原型](../design/ink-wuxia/index.html)、[实施计划](../plans/2026-09-29-ink-ui.md)。

## 关键提交与审查

| 提交 | 内容 |
|---|---|
| `4f4819a` | 起点；当时远端 main 经 API 核对一致 |
| `9d8f3fe` | 测试先行，新增交互在旧 UI 下失败 |
| `e054fb1` | 核心界面实现 |
| `959cd66` | 大字号镖库改为整页滚动，补足内容可达性检查 |
| `7e99c0c` | MainActivity 明确 adjustResize，修复真实键盘把顶部返回推走的问题 |
| `5bd7d9f` / `8c650e4` | 旧系统现场探针、真实系统夜间模式流程与独立截图 |
| `505c5ec` | 旧系统布局稳定、无障碍控件出现后的有限等待；最终测试提交 |

实现、修复复审、全分支及后续增量均经独立审查，无未处理的阻断项。提交使用实际配置的 Wuhao 身份及 SSH 签名；没有修改账户密钥设置。仅暂存本阶段明确文件。

## 验证结果

| 层级 | 证据与结果 |
|---|---|
| 本地 | `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 成功；98 项 JVM，0 失败/错误/跳过；Lint 0 错误、22 条现存警告；测试增量各次编译通过；差异检查通过 |
| 最终云端构建 | `505c5ec` 的 Android 与 contracts 已通过，包含隔离数据库和业务边界回归 |
| 最终设备矩阵 | [运行 36515075691](https://github.com/amateurish-programmer/boomerang/actions/runs/36515075691)：全部通过。API26/33/35 为 57/56/56 项，共 169 次执行，0 失败/跳过；API35 首次通知权限弹窗关闭超时，保留失败后未改代码复跑通过 |
| 实拍 | [Android 画廊](../design/ink-wuxia/android-preview/index.html)：已归档并目视检查。采用隔离模拟器、合成记录；含真实应用浅色/系统夜墨及 200% 字号页面 |
| 发布与手机 | 本阶段没有发布新版本，没有在用户手机安装测试包或清除数据；生产 OTA 仍为既有 0.4.2/code7 |

Windows 中文目录触发 Android 路径检查后，使用仅映射当前工作区的 U: 完成构建；已有 R: 属于其他项目，未更改。

设计色值按 sRGB 相对亮度计算：浅色正文 13.95、辅助文字 5.79、主按钮文字 6.99；深色正文 13.13、辅助文字 7.81、主按钮文字 7.12。色值检查不能代替系统渲染和读屏验收。

## 失败、修复与边界

1. [测试先行 API35](https://github.com/amateurish-programmer/boomerang/actions/runs/36508607408)：旧 UI 的 5 个新增用例按预期失败，其余既有用例通过；首版实现随后通过 [三系统矩阵](https://github.com/amateurish-programmer/boomerang/actions/runs/36509642863)。
2. 审查发现固定镖库头部在 200% 字号、360dp 高窗口下挤压记录；改为单一滚动列表。回归验证打开记录、再返回搜索输入，并检查大字号详情和编辑内容。
3. `959cd66` 的 [矩阵](https://github.com/amateurish-programmer/boomerang/actions/runs/36510297090) 首次 API33 仅系统文件选择器点击出现 StaleObjectException；保留失败，未改代码复跑成功。API26/33/35 分别 56/55/55 项，0 跳过。
4. [真实页面截图运行](https://github.com/amateurish-programmer/boomerang/actions/runs/36510934401) 自动测试通过，但目视发现键盘把编辑页标题/返回推走。MainActivity 增加 `adjustResize`，配合既有 Compose IME 避让；依据 [Android 官方窗口设置](https://developer.android.com/develop/ui/compose/system/setup-e2e)。补充真实键盘稳定后标题/返回/保存的物理位置断言。
5. [键盘修复矩阵](https://github.com/amateurish-programmer/boomerang/actions/runs/36511849058) 的 API33/35 通过，API26 两项失败。随后 [现场探针矩阵](https://github.com/amateurish-programmer/boomerang/actions/runs/36514196212) 全通过：键盘展开时窗口 320×640、IME 上沿 y=391，保存按钮 y=331..379、返回 y=24..72；草稿恢复早期截图仍在更新布局，后续同次数据为 IME=0、保存 y=580..628、原话 y=325..437。旧系统异步布局/无障碍树更新导致测试过早读取；最终改为有限等待稳定窗口及控件，保留内容、滚动可达性和屏幕边界断言。
6. 独立组件容器只切换 Compose 配色，不能代表真实暗色系统栏。新增系统夜间模式 MainActivity 流程，在 finally 恢复原设置；[API35 夜间验证](https://github.com/amateurish-programmer/boomerang/actions/runs/36514517721) 通过，正文、状态栏图标、键盘与按钮已目视确认。画廊暗色以真实系统夜间截图为准。

画廊已在内置浏览器检查：9 张原始图片均可加载，无横向溢出，大字号区可用键盘展开/收起。核心截图为最终提交首轮已通过的应用 UI 用例；系统权限子集的失败及复跑单独保留。

## 下一阶段

UI-D3 整理 AI、个人页、回顾/备份和更新页面；UI-D4 统一签名发布、真机升级及数据保留验收。TalkBack 遍历、厂商键盘/字号、低端机性能尚待实机验收，不能将本阶段标记为全 App 商业级验收完成。百炼配置继续暂缓，生产 Cron 未启用。
