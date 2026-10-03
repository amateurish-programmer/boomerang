#!/usr/bin/env bash
# Preserve the test exit status while collecting only synthetic acceptance assets.
set -uo pipefail
gradle=(bash android/gradlew -p android --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process)
diagnostics=android/app/build/reports/androidTests/environment
mkdir -p "$diagnostics"
# GNU timeout is supplied by the Ubuntu CI runner. Refuse physical devices
# before Gradle can install APKs or any permission command can mutate the app.
qemu_status=0
qemu=$(timeout --kill-after=5s 20s adb shell getprop ro.kernel.qemu 2> "$diagnostics/emulator-guard.txt") || qemu_status=$?
printf 'emulator-guard exit=%s\n' "$qemu_status" >> "$diagnostics/capture-status.txt"
if [[ "$qemu_status" -ne 0 ]]; then exit "$qemu_status"; fi
if [[ "${qemu//$'\r'/}" != 1 ]]; then
  echo 'Device tests require an emulator (ro.kernel.qemu=1).' >&2
  exit 1
fi

capture_adb() {
  local name="$1"; shift
  local code=0
  timeout --kill-after=5s 20s adb "$@" > "$diagnostics/$name" 2>&1 || code=$?
  printf '%s exit=%s\n' "$name" "$code" >> "$diagnostics/capture-status.txt"
  return 0
}

capture_host() {
  local name="$1"; shift
  local code=0
  timeout --kill-after=5s 20s "$@" > "$diagnostics/$name" 2>&1 || code=$?
  printf '%s exit=%s\n' "$name" "$code" >> "$diagnostics/capture-status.txt"
  return 0
}

copy_raw_results() {
  local destination="$1"
  local name="$2"
  local source=android/app/build/outputs/androidTest-results/connected
  if [[ -d "$source" ]]; then
    mkdir -p "$destination"
    capture_host "$name" cp -R "$source/." "$destination/"
  fi
}

capture_host host-memory-before.txt free -m
capture_adb memory-before.txt shell dumpsys meminfo
capture_adb disk-before.txt shell df -h
permission_class=com.boomerang.app.reminders.NotificationPermissionTest
permission_status=0
api_status=0
api=$(timeout --kill-after=5s 20s adb shell getprop ro.build.version.sdk 2> "$diagnostics/api-level.txt") || api_status=$?
printf 'api-level exit=%s\n' "$api_status" >> "$diagnostics/capture-status.txt"
if [[ "$api_status" -ne 0 ]]; then exit "$api_status"; fi
api=${api//$'\r'/}
if [[ ! "$api" =~ ^[0-9]+$ ]]; then
  echo 'Could not read the emulator API level.' >&2
  exit 1
fi
if [[ "$api" -ge 33 ]]; then
  # Changing permission while instrumentation is running can kill its process.
  "${gradle[@]}" installDebug installDebugAndroidTest || exit "$?"
  timeout --kill-after=5s 20s adb shell pm revoke com.boomerang.app android.permission.POST_NOTIFICATIONS || exit "$?"
  timeout --kill-after=5s 20s adb shell pm clear-permission-flags com.boomerang.app android.permission.POST_NOTIFICATIONS user-set user-fixed || exit "$?"
  "${gradle[@]}" connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.class=$permission_class"
  permission_status=$?
  mkdir -p android/app/build/permission-reports
  capture_host permission-reports-copy.txt cp -R android/app/build/reports/androidTests/. android/app/build/permission-reports/
  copy_raw_results android/app/build/permission-reports/raw-test-results permission-raw-test-results-copy.txt
fi
"${gradle[@]}" connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.notClass=$permission_class"
test_status=$?
copy_raw_results "$diagnostics/raw-test-results" raw-test-results-copy.txt
capture_adb crash-log.txt logcat -b crash -d -v threadtime
capture_adb runtime-log.txt logcat -b main -d -t 1000 -v threadtime
capture_adb anr-events.txt logcat -b events -d -s am_anr
capture_host host-memory-after.txt free -m
capture_host host-dmesg.txt bash -c 'set -o pipefail; dmesg | tail -n 100'
capture_adb memory-after.txt shell dumpsys meminfo
capture_adb cpu-after.txt shell dumpsys cpuinfo
if [[ -d android/app/build/permission-reports ]]; then
  mkdir -p android/app/build/reports/androidTests/permission
  capture_host permission-reports-restore.txt cp -R android/app/build/permission-reports/. android/app/build/reports/androidTests/permission/
fi
mkdir -p android/app/build/reports/androidTests/acceptance
timeout --kill-after=5s 20s adb pull /sdcard/Android/data/com.boomerang.app/files/acceptance/. android/app/build/reports/androidTests/acceptance/ > "$diagnostics/acceptance-pull.txt" 2>&1
asset_status=$?
printf 'acceptance-pull.txt exit=%s\n' "$asset_status" >> "$diagnostics/capture-status.txt"
if [[ "$permission_status" -ne 0 ]]; then exit "$permission_status"; fi
if [[ "$test_status" -eq 0 && "$asset_status" -ne 0 ]]; then
  echo 'Device tests passed but acceptance screenshots could not be collected.' >&2
  exit "$asset_status"
fi
exit "$test_status"
