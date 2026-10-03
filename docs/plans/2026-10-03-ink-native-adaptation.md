# 青绿山水 Android 原生适配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute task-by-task, review each task, then review the complete native change.

**Goal:** 将用户已确认的 UI-D6 动态样板接入真实 Android 首页，保持业务与数据契约，并提供可持久化的动效设置。

**Architecture:** 原画以 Android 本地资源展示，Compose Canvas 只绘制独立装饰。动效策略为纯 Kotlin；设置存储封装在 data Repository，ShellViewModel 暴露 StateFlow 和动作；UI 仅处理渲染、生命周期及窗口可见性。已有导航、记录保存、同步和备份链路保持原契约。

**Tech Stack:** Kotlin, Compose Material 3, lifecycle-runtime-compose, SharedPreferences, JUnit, Compose instrumentation; minSdk 26。

**Spec:** docs/design/ink-wuxia-v2/SPEC.md；用户于 2026-10-03 以“好的，开始”确认动态样板并授权进入 UI-D7。

## Global Constraints

- 沿用完整原创青绿山水两版 assets/landscape-light.png 与 landscape-dark.png；禁止灰度拼接、多边形山体和品牌印章。图片为装饰，文字保持原生可访问。
- 完整/减少/关闭，默认完整。系统 animator scale 为 0 时有效关闭；0<scale<1 时完整上限为减少；即时响应变化，不覆写用户选择。减少不循环、无粒子/视差，关闭所有渐显和反馈。
- 完整：32 个固定池墨尘、两层缓慢云雾；山体静止，装饰视差上限 6dp，绘制最多 30fps。入场山景 800ms、品牌 500ms、记录 350ms；减少入场 160ms。
- 成功反馈只能由真实保存成功触发，最多 8 个朱砂微粒、450ms；减少只显示 180ms 颜色反馈，关闭静止。不得延迟业务保存或伪称保存成功。
- 前台 RESUMED、窗口有焦点、首页且英雄区域可见时允许装饰；任一条件失效立即暂停并清空粒子/有限反馈。恢复首帧 dt=0，后续 dt≤50ms，不追赶后台时间、不重复入场。无后台线程循环/固定轮询。
- UI 不访问设置存储/网络/数据库；不改变 Room schema、账号隔离、日期、AI、同步、OTA 契约。版本仍 0.5.0/code8，UI-D8 验收后才递增。
- 所有操作保持至少48dp；窄屏、200%字体和短窗口可用；编辑/详情等工作页面保持安静，底部主动作固定可达。
- 只在当前 worktree 工作，不读生产数据、不用真实手机安装 debug 包。提交用真实身份 SSH 签名、逐文件变更表；不推送、不发布，由控制者完成。
- 测试/构建使用 D:/SoftWare/Git/bin/bash.exe，workdir U:/（已映射本 worktree）；JAVA_HOME F:/SoftWare/jdk-17、ANDROID_HOME F:/SoftWare/Android/Sdk、GRADLE_USER_HOME F:/SoftWare/Gradle。禁止另行安装或清理 C:。

## Controller acceptance

控制者负责 PROGRESS/UI-D7 报告、批准记录、发布与 CI/设备矩阵、最终整分支复审。UI-D7 原生实现与 UI-D8 真机/性能/数据验收分别记载。无手机继续源码/CI，不宣称真机通过。

### Task 1: Native artwork, policy, settings and lifecycle integration

**Files:**
- Create domain/InkMotionPolicy.kt, data/AppearanceRepository.kt, ui/InkLandscape.kt (under android/app/src/main/java/com/boomerang/app/).
- Create drawable-nodpi ink_landscape_light.png and ink_landscape_dark.png by copying the approved originals. Decode at most one theme bitmap per composed hero; no per-frame resource loading.
- Modify ui/Theme.kt, ui/InkComponents.kt, ui/Screens.kt, ui/AccountScreen.kt, ui/BoomerangApp.kt, ui/ShellViewModel.kt.
- Test android/app/src/test/java/com/boomerang/app/InkMotionPolicyTest.kt.

**Interfaces:**
- Produces `enum class InkMotionMode { FULL, REDUCED, OFF }` and pure effective-mode/active-clock policy, exposed to instrumentation.
- Produces `AppearanceRepository` encapsulating device-scoped preference and system animation scale, with VM-visible flows and cleanup.
- Existing HomeScreen/AccountScreen signatures retain existing parameters and default-compatible new parameters so direct fixture tests compile. Add a real local-date label in the hero through the VM (no hardcoded sample date); expose a default-compatible String parameter for isolated fixtures.
- HomeScreen accepts motion mode/system scale and a pending-save feedback signal plus consume callback. ShellViewModel sets signal only AFTER repository.save succeeds; when homepage becomes eligible it consumes once. Cancel/validation/error do not signal; removing the homepage cancels feedback. A delayed first return home is feedback for the actual completed save, not a new write.
- Expose stable test tags `ink_landscape`, `motion_FULL`, `motion_REDUCED`, `motion_OFF`; motion effective/running semantics or internal test seam for lifecycle acceptance. Preserve `create_record`, `all_records`, nav and record tags.

- [ ] RED: Write behavior tests before implementation. Include full→reduced under scale .5, any→off at scale 0, user-off remains off, background/unfocused/invisible stop, first/resumed dt 0 and large dt clamp50ms.

```kotlin
assertEquals(InkMotionMode.REDUCED, effectiveMotionMode(InkMotionMode.FULL, .5f))
assertEquals(InkMotionMode.OFF, effectiveMotionMode(InkMotionMode.FULL, 0f))
assertEquals(InkMotionMode.OFF, effectiveMotionMode(InkMotionMode.OFF, 1f))
// Equivalent API acceptable if all these behaviors and the named enum remain.
```

- [ ] Run focused unit tests and record failure caused by absent new policy (not an unrelated environment error).
- [ ] Implement pure policy and settings repo. Preferences have safe default on absent/invalid values; initialization and observer cleanup are deterministic. Pass data through VM, preserving actual save/error behavior.
- [ ] Copy approved image resources, update light paper F4F0E6/cinnabar973A32 and dark172C2B/cinnabarDBA08D across coherent Material colors. Forced theme tests must choose corresponding asset regardless of OS theme; preserve contrast.
- [ ] Replace old Home polygon mountains with native hero approximately first-screen third, text in left blank region, image with natural paper edge. Keep real records and fixed create action. Use cached reusable particle/mist shapes and a cancellable frame loop; state updates used for drawing must not recompose the record list each frame. Lifecycle/focus/visible guards apply to entrance and feedback too. Native page background matches artwork edges.
- [ ] Add radio/selection controls in 我的空间 for 完整/减少/关闭, with current selection and readable system limit explanation. No decorative fake buttons.
- [ ] Run `bash android/gradlew -p android testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`, `git diff --check`; inspect fresh logs. Preserve tests (do not weaken existing expectations silently).
- [ ] Self-review and signed commit explicit files. Full report includes exact files, test evidence, lifecycle design, image allocation limit, commit IDs and concerns.

### Task 2: Native behavior and layout acceptance tests

**Files:**
- Create android/app/src/androidTest/java/com/boomerang/app/InkMotionUiTest.kt.
- Modify existing InkUiTest.kt or InkSecondaryUiTest.kt only when approved copy changes legitimately need updating.
- Modify test seams in new InkLandscape/AppearanceRepository only if necessary to verify real behavior without production data.

**Interfaces:**
- Consumes Task1 enum, settings callbacks, hero tags and motion semantics/test seam. Exact landed API provided separately by controller.
- Produces isolated instrumentation coverage; no production database, no network dependency, no real-phone interactions.

- [ ] Write actual fixture tests for mode switching/current selection, system upper limit, forced light/dark themes, absence of brand seal, lifecycle/focus/invisible stop and stable resume without time jump. Use deterministic clocks, explicitly disable infinite animation for layout assertions.

```kotlin
compose.onNodeWithTag("motion_OFF").performClick()
compose.onNodeWithTag("motion_OFF").assertIsSelected()
// Scope fixtures and callbacks to isolated data; do not initialize production ShellVM.
```

- [ ] Verify 360dp short-window and 200% font fixtures keep `create_record` clickable, hero non-intercepting and first record operable; real create/save callback path must remain single-shot. Successful-save feedback consumes once, errors/cancel no signal, full/reduced/off guard finite behavior.
- [ ] Compile tests plus required JVM/Lint/build and record exact counts. Controller dispatches API26/33/35 matrix; cannot mark instrumented tests run based only on compilation.
- [ ] Self-review and signed commit with each staged file table row; full report names every unexecuted acceptance item. No publishing or worker-created reviewers.

### Task 3: Bounded emulator diagnostics for incomplete test execution

**Reason:** Preliminary API35 run37082606430 reached53/61 with one legacy PendingIntent failure and no trace; only52 results arrived, post-test ADB diagnostics stalled until the newer source cancelled the run. Root cause is unconfirmed. Keep failures, collect evidence and reproduce; do not change business code based on a guess.

**Files:** Modify scripts/test-device.sh and .github/workflows/ci.yml only.

**Interfaces:** Existing synthetic emulator-only pipeline, original test/permission/asset exit statuses and screenshot acceptance paths stay intact. Produce bounded capture logs and raw test results in existing Android report artifact.

- [ ] Before any install/permission mutation, assert `adb shell getprop ro.kernel.qemu` is1. This script is for emulators and must refuse a physical phone. Bound this read too.
- [ ] Replace unbounded diagnostic ADB calls with a helper; preserve test status and separately record capture status. Use Ubuntu GNU timeout explicitly; no local phone execution.

```bash
capture_adb() {
  local name="$1"; shift
  local code=0
  timeout --kill-after=5s 20s adb "$@" > "$diagnostics/$name" 2>&1 || code=$?
  printf '%s exit=%s\n' "$name" "$code" >> "$diagnostics/capture-status.txt"
  return 0
}
```

- [ ] Capture bounded crash log/main recent runtime log before expensive meminfo/cpu collection; capture host `free -m` before/after and dmesg tail if readable. These CI/emulator fixtures contain no production users/secrets. No broad environment dump.
- [ ] Bound screenshot `adb pull` and keep existing rule: passing tests with missing acceptance assets fail; diagnostics capture failure must not hide original test failure.
- [ ] Copy `android/app/build/outputs/androidTest-results/connected` into report environment/raw-test-results when present, retaining UTP runner/test log evidence even when ADB disappears. Preserve permission reports collected separately.
- [ ] Add CI artifact raw device result paths as a fallback for an interrupted copy; avoid collecting source credentials/entire runner files. No framework/action/dependency upgrade.
- [ ] Validate `bash -n scripts/test-device.sh`, `git diff --check`, inspect exact workflow paths. Source-only validation does not prove emulator diagnosis; controller runs final matrix and reads raw failures.
- [ ] Signed semantic commit explicit files, per-file table, report task-3-report.md. No worker adb/emulator/phone/cloud operations or reviewers.
