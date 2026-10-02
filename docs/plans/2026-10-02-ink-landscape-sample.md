# UI-D5 青绿山水首页静态样板实施计划

> For agentic workers: execute this bounded visual sample in the current isolated worktree; UI-D6 begins after the user approves the actual composition.

**Goal:** 交付宣纸、夜墨两版原创山水首页样板供用户审核。

**Architecture:** 独立HTML/CSS/JavaScript静态设计稿；山水是两张完整原创图片，文字、按钮和导航是真实DOM；仅使用固定演示内容。

**Tech Stack:** 内置imagegen、浏览器原生HTML/CSS/JavaScript、本地预览服务。

**Spec:** docs/design/ink-wuxia-v2/SPEC.md。

## Global Constraints
- 只操作本项目，保留无关puppet-dance.html。
- 不恢复品牌印章；不使用灰度照片拼接或简单多边形山峦。
- 不改Android、后端、版本、Secret和OTA；0.5.0手机验收仍未完成。
- 本阶段无连续动画，明确标注静态样板和演示数据。
- 按用户要求先审核静态构图，再动态样板，再原生适配。

## Task 1：原创素材与首页样板
**Files:** docs/design/ink-wuxia-v2/{assets/landscape-light.png,assets/landscape-dark.png,index.html,style.css,preview.js,PROMPTS.md,SPEC.md}。
**Interfaces:** 两个样板共用结构，以data-theme区分颜色；data-view切换并排、宣纸、夜墨。演示操作使用本页dialog，不读写业务数据。
- [x] 内置生图生成整幅宣纸山水并目视检查；夜版以相同画面编辑，保留山形与布局。
- [x] 复制两版原图到本项目，完整记录生成提示词与模式。
- [x] 实现品牌/日期、两条记录、主按钮和四导航；图像只在页头，标题放在留白上。
- [x] 实现主题对照切换、记录详情/录入/导航演示弹窗；键盘与取消可用，不伪称实际保存。
- [x] 浏览器检查图像加载、明暗构图、360px无横向溢出、点击/对话框关闭；保存截图。

## Task 2：设计交付
**Files:** docs/PROGRESS.md、docs/reports/UI-D5.md、本计划、设计截图。
- [x] 记录静态验证证据、未实现动画和原生验收边界，git diff --check和JavaScript语法检查。
- [x] 明确文件暂存、实际身份签名提交，专用分支云端CI；审核样板留在本地浏览器，不发布APK。
- [ ] 用户确认具体构图后推进UI-D6；有批注时先修订本样板。
