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

### Task 4: Isolate API35 emulator graphics compatibility

**Reason:** Corrected d18e4ee matrix37087125751: API33 complete pass, API26 all17 native motion tests pass with two legacy system-panel failures; API35 again stops after three complete results. Its last lifecycle log reaches RESUMED before losing the guest and its console port5554 refuses connection during cleanup. No app exception or host OOM proof. Emulator37.2.12 uses SwiftShader Vulkan; official Android troubleshooting supports disabling Vulkan for graphics compatibility. Treat this as an isolated CI renderer hypothesis, not a proved application root cause.

**File:** .github/workflows/ci.yml only. Preserve app/test source, assertions, APK versions, action/dependency versions, matrix, scripts, report paths, timeout and acceptance gates.

- [ ] Add explicit current default emulator options (-no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim), with only API35 appending -feature -Vulkan. APIs26/33 keep their current effective options. No test skipping, retries or reduced assertions.
- [ ] Inspect exactly one workflow hunk and git diff --check. No local adb/device/emulator/cloud or full Android rerun; controller dispatches the unchanged complete matrix and inspects renderer startup/result evidence.
- [ ] Signed semantic commit actual identity with per-file table; report task-4-report.md. No subagents, push or broad environment/crash dump uploads.

Official reference: https://developer.android.com/studio/run/emulator-troubleshooting (checked2026-10-03). Successful experiment can establish this CI renderer configuration completes acceptance; it cannot establish a precise driver crash stack or phone graphics performance.

### Task 5: Use supported API35 graphics mode and bounded crash metadata

**Reason:** Matrix37088178975: API26 complete pass; API33 native17 pass with one legacy DocumentsUI wait failure; API35 still loses the emulator at the lifecycle fixture. Partial log reaches PAUSED then opens libGLES for EmptyFloatingActivity. Disabling Vulkan alone did not resolve it. Host/guest snapshots do not show OOM. Official Android graphics documentation marks swiftshader_indirect deprecated since36.4.9; current emulator37.2.12 is using it. Use a supported API35 mode and collect minimal host exception metadata instead of repeating an unexplained disappearance.

**Files:** scripts/describe-emulator-crash.mjs; scripts/test-emulator-crash.mjs; scripts/test-device.sh; .github/workflows/ci.yml. Native app/test APK source stays4b29bc4, all original assertions/versions/matrix/guard/exit/report gates preserved.

- [ ] Before parser implementation, behavior tests fail due missing parser. Pure synthetic MINIDUMP fixtures verify exception/module attribution above Number precision, safe basename-only output, missing exception, malformed/truncated offsets, bounded count/name length and no memory/string payload leakage. No local emulator/ADB/phone execution.
- [ ] Parser uses Node standard library only. Validate MDMP header, stream directory and bounds; read ExceptionStream(type6) thread/code/address and matching ModuleListStream(type4) base/size/name. Use BigInt for64-bit addresses. Output only structured exception code, thread, address, safe module basename and offset; never emit dump memory, arbitrary strings/paths, environment, commandline, private data or raw .dmp.
- [ ] CLI is Linux CI-only, scans only literal /tmp/android-runner/emu-crash-*.db crashpad folders (no arbitrary input paths or symlink traversal), at most16 files, nesting≤3, dump≤64MiB, streams≤128, modules≤1024, module name≤4096bytes. Missing dump/unsupported format is explicit diagnostic status, not proof of no crash; avoid guessing. No raw crash database artifact uploads.
- [ ] test-device captures parsed host metadata via existing bounded capture_host immediately after Gradle main run/raw result copy and before failed ADB calls. Captures never override tests' status. CI contracts job runs node --test scripts/test-emulator-crash.mjs.
- [ ] Change only API35 GPU value from deprecated swiftshader_indirect to supported swiftshader; retain previous API35 -feature -Vulkan and all other flags. API26/33 remain identical. No software installation, action/dependency version change, test skipping, weakening or retries. This is a renderer compatibility experiment, not a proven driver fix.
- [ ] Run parser behavior tests, node syntax checks, bash-n and git diff --check. No Android suite rerun for CI-only change. Signed exact4file commit, actualidentity/perfiletable; report task-5-report.md. No subagents/cloud/push/device actions; controller runs complete matrix and inspects actual startup/results/crash metadata.

References checked2026-10-03: https://developer.android.com/studio/run/emulator-acceleration ; https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_exception_stream ; https://learn.microsoft.com/en-us/windows/win32/api/minidumpapiset/ns-minidumpapiset-minidump_module . Reject malformed or ambiguous files; these docs define structures, not the actual CI crash cause.

### Task 6: Collect bounded API35 host process and kernel exit evidence

**Reason:** Full matrix37090395453 on5d99423: contracts/Android and API26/33 complete success, but API35 still loses emulator5554. Main78 batch only1completed and an empty-failure second slot; permission1 separately passed. Supported swiftshader+Vulkanoff did not resolve disappearance. Crash metadata collector ran Linux successfully but returned missing_dump; host snapshots before11884MiB/after14223MiB available do not prove OOM. Existing unprivileged dmesg capture is denied, leaving host exit reason unknown. Gather host evidence before changing application, memory or renderer again.

**File:** scripts/test-device.sh only. Native4b app/tests, parser9a, GPUflags, matrix/action/dependencies/version/permissions/testassertions/resultcopy/status/exit/artifact paths remain unchanged.

- [ ] Only API35 adds bounded host diagnostics after API validation before permission/main execution, and immediately after main raw-result copy BEFORE failing adb captures. Current capture_host provides timeout20s/kill5 and separate capture status, never replaces test outcome.
- [ ] Record emulator/QEMU process presence before/after with ps metadata only (PID/PPID/comm/state/RSS/VSZ, max4matchingprocesses). Match explicit process names emulator and qemu-system-x86 (Linux15char comm) and qemu-system-x86_64; never output commandline/args/env. Keep unavailable/missing status explicit and no inferred root cause.
- [ ] After test, use sudo -n for read-only dmesg in this ephemeral GitHub-hosted runner, no password prompt, installation or kernel setting changes. Bound20s via capture_host, retain pipefail, filter only OOM/killed-process/segfault/general-protection/qemu/emulator events and tail≤120lines. Keep no-matching-event explicit; report privilege failure independently. No raw core dumps, arbitrary memory, environment or credentials are collected.
- [ ] Fresh minimal worker, no local device/emulator/ADB/cloud/push/subagents. bash -n using existing GitBash, focused diff and git diff --check; no new implementation-mirroring tests/full Android rerun for read-only CI capture. Signed exact one-file commit actualidentity/perfiletable; report task-6-report.md command/results/scope/remaininglimits. Controller runs focused API35 diagnostic first; this is not full-matrix acceptance or permission to skip original tests.

Preflight: shares capture_host and test_status order withTasks3/5. Fixed API35 condition consumes already validated api; existing API26/33 execution paths unchanged, new host captures non-overriding and beforeADBtimeouts. No app/sourceinterface changes or speculative renderer fix.
