# 青绿山水动态样板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** UI-D6在已获用户确认的首页构图上提供可审阅的独立云雾、墨尘、朱砂反馈与温和过渡。

**Architecture:** 浏览器样板保留原完整山水PNG与现有固定演示流程。纯JavaScript调度模块负责模式和暂停条件，Canvas只绘装饰层；DOM负责内容和可访问操作。控制器完成真实浏览器验收及阶段报告。

**Tech Stack:** 浏览器ES modules、Canvas 2D、CSS、Node内置test/assert，无新增依赖。

**Spec:** docs/design/ink-wuxia-v2/SPEC.md。用户于2026-10-02“可以，继续”确认UI-D5构图、进入UI-D6；动态强度待本阶段交付后确认。

## Global Constraints

- 山体稳定，云雾与墨尘独立运动。山峦不能使用灰度照片拼接、简单三角形或多边形替代。品牌旁不恢复印章。
- UI-D6：浏览器动态样板，云雾、24–40个墨尘粒子与少量操作触发朱砂微粒；进入渐显、轻微滚动视差、按钮反馈。供用户确认运动强度和节奏。
- 完整/减少/关闭动效；尊重系统动画设置，后台、非首页、遮挡和不可见时停止。
- 浏览器360px窄屏无横向溢出，48px操作目标，主动作与首条记录在手机样板首屏可达。
- 固定演示数据、不保存输入、不调用后端，不修改Android、版本、APK、OTA、数据库或百炼配置；保留原静态截图和山水资源。
- 本阶段浏览器调度行为测试与浏览器可视检查分别记载，不冒充Android/真机通过。

---

### Task 1: 动态装饰与可验证生命周期

**Files:**
- Create: `docs/design/ink-wuxia-v2/motion.mjs` — 可注入request/cancel函数的纯调度核心。
- Create: `docs/design/ink-wuxia-v2/landscape-motion.mjs` — Canvas装饰与DOM生命周期适配。
- Create: `scripts/test-ink-motion.mjs` — Node行为测试。
- Modify: `docs/design/ink-wuxia-v2/index.html`, `style.css`, `preview.js` — 模块入口、样板控制、演示交互。
- Modify: `docs/design/ink-wuxia-v2/SPEC.md` — 静态已确认、动态实现细则。
- Modify: `.github/workflows/ci.yml` — contracts job现有校验旁增加`node --test scripts/test-ink-motion.mjs`。

**Interfaces:**
- Produces: `effectiveMode(selected, systemReduced)` → `full | reduced | off`。systemReduced为true时full上限为reduced；off保持off。
- Produces: `createFrameLoop({request, cancel, draw})` → `{setActive(boolean), destroy()}`。draw接受(dtMs, timestamp)，第一帧/恢复第一帧dt为0，后续dt限制0..50ms，同一实例至多一个待运行回调，destroy不可恢复。
- Produces: `installLandscapeMotion()` → `{refresh(), feedback(phone), replay()}`。preview.js在主题切换、模态开/关后调用refresh；仅“完成预览”触发反馈，不宣称已保存。

- [x] **Step 1: 写行为测试并观察真实RED**

使用下面接口和确定性时钟队列；先运行测试，缺失导出/模块是预期RED，报告明确原因，随后实现。

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import {effectiveMode, createFrameLoop} from '../docs/design/ink-wuxia-v2/motion.mjs';
test('system reduction caps full but never enables off', () => {
  assert.equal(effectiveMode('full', true), 'reduced');
  assert.equal(effectiveMode('off', true), 'off');
  assert.equal(effectiveMode('full', false), 'full');
});
```

完整测试至少覆盖：重复激活无双循环，停止取消回调，已取消的迟到回调不会复活，恢复首帧dt0、大间隔dt上限50ms，destroy终结。模式测试覆盖full/reduced/off与系统减少组合。额外导出纯`shouldAnimate({mode, pageVisible, panelVisible, heroVisible, dialogOpen})`，对每个暂停条件写独立测试，DOM适配必须使用同一函数。

- [x] **Step 2: 实现最小调度核心并GREEN**

```js
export function effectiveMode(selected, systemReduced) {
  return selected === 'off' ? 'off' : selected === 'reduced' || systemReduced ? 'reduced' : 'full';
}
export function shouldAnimate({mode, pageVisible, panelVisible, heroVisible, dialogOpen}) {
  return mode === 'full' && pageVisible && panelVisible && heroVisible && !dialogOpen;
}
```

FrameLoop使用generation标记使迟到回调失效，取消后清空pending与lastTime；运行回调先移除pending、检验generation/active，然后draw并仅在仍有效时排下一帧。draw内允许关闭loop，不能再排新帧。

- [x] **Step 3: 集成绘制和交互**

每个手机山景内插入aria-hidden、pointer-events:none Canvas，DPR上限2，32个固定池墨尘；位置以归一化坐标表示，远离标题与正文，控制粒子尺寸约0.6..2px、不规则长宽/透明度、慢速漂移。云雾用至少两层柔和透明渐变/曲线在谷地移动，不移动山形；不新增灰度山图。绘制帧率上限30fps；暂停清除动态层并保留原画。

样板工具条新增“完整 / 减少 / 关闭”可访问选项，每按钮至少48px，附简短演示说明；默认full但系统reduce即时上限reduced。减少/关闭均没有循环粒子或视差，关闭模式所有过渡/反馈静止；减少模式允许短非循环淡入，尊重系统变化事件。

监听visibilitychange、IntersectionObserver（英雄区与窗口交集、手机滚动根）、ResizeObserver和模态开关。主题隐藏、英雄滚出窗口/手机滚动可视区、document隐藏、dialog打开均取消rAF。refresh设置phone的data-motion-state为running/paused与data-motion-mode供只读验收，状态属性不逐帧更新。恢复不补算后台时间。

CSS用于入场山景约800ms、品牌约500ms、列表约350ms；山景只淡入。full滚动视差以requestAnimationFrame调度scroll反馈、位移上限6px且不暴露图片边缘；模式切换清零。提供工具条“重播动效”可真实重复体验入场。

保持现有演示dialogs；create的返回主按钮改为“完成预览”，明确不会保存，点击后清空草稿并返回首页，feedback在原手机主按钮附近绘制最多8个朱砂微粒，约450ms渐散。Escape/关闭不触发完成反馈。button反馈可用局部伪元素/WAAPI，粒子不得复制用户文本。feedback在off无效果、reduced只有短暂颜色变化。反馈也必须在后台/遮挡取消，不留无限循环。

更新页头/页脚为动态样板、说明山形不动/背景暂停/演示输入不保存；保持主题、记录和导航原有布局。所有装饰不影响命中测试。不要写诊断开发信息到产品按钮。

- [x] **Step 4: 验证和提交**

运行`node --test scripts/test-ink-motion.mjs`、`node --check`全部新增/修改JS、`git diff --check`，将CI job加入测试。控制器随后验证真实浏览器，不由实施代理自行调用浏览器或做Android/设备验收。

完成后以实际Git身份SSH签名提交Task1，语义标题与逐文件变更表；仅暂存本任务明确文件，不纳入控制器报告/计划或任何无关工作。完整实现报告包含RED/GREEN命令和输出、测试数量、变更和疑虑。

## 控制器验收与收尾

- [x] 刷新本地浏览器，核对两主题的云雾/粒子可见、山形稳定、文字清晰；分别完整/减少/关闭、重播、主题切换、模态遮挡及恢复。
- [x] 360px窄屏布局、48px操作、固定输入后Esc草稿丢弃、完成预览不持久化；检查页面错误日志，恢复默认视口与并排。
- [x] 保存动态样板截图（不同于已归档静态截图），更新UI-D6与PROGRESS；源码两次签名提交已发布预览分支，最终CI37024439816成功。阶段资料随本计划另行签名归档。
- [ ] 用户确认动态节奏后才进入UI-D7。当前不发布APK、不修改云端安装包。
