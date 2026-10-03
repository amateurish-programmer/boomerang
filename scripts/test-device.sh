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

capture_emulator_processes() {
  local name="$1"
  capture_host "$name" bash -c '
    set -o pipefail
    printf "PID PPID COMM STATE RSS_KIB VSZ_KIB\n"
    ps -eo pid=,ppid=,comm:32=,stat=,rss=,vsz= | awk '\''
      $3 == "emulator" || $3 == "qemu-system-x86" || $3 == "qemu-system-x86_64" {
        if (count < 4) { print; count++ }
      }
      END { if (count == 0) print "emulator process missing: no matching comm" }
    '\''
    code=$?
    if [[ "$code" -ne 0 ]]; then
      printf "emulator process metadata unavailable: ps/filter exit=%s\n" "$code"
    fi
    exit "$code"
  '
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

# Only the trusted workflow opt-in enables this diagnostic. It changes runtime
# timing and may add up to the remaining 300s + 5s trace bound when reaping.
trace_wrapper_pid=
trace_target_pid=unavailable
trace_raw=
trace_status_file="$diagnostics/host-emulator-exit-trace-status.txt"
trace_record() {
  printf 'target_pid=%s %s\n' "$trace_target_pid" "$*" >> "$trace_status_file"
}

start_host_exit_trace() {
  [[ "${INK_TRACE_HOST_EXIT:-0}" == 1 && "$api" -eq 35 ]] || return 0
  local candidates comm code=0
  local -a pids=()
  if ! command -v strace >/dev/null 2>&1 || ! command -v sudo >/dev/null 2>&1; then
    trace_record 'tracer=unavailable reason=strace-or-sudo-missing'
    return 0
  fi
  candidates=$(timeout --kill-after=5s 20s ps -eo pid=,comm:32= 2>/dev/null | awk '
    $1 ~ /^[0-9]+$/ && $1 > 0 &&
    ($2 == "qemu-system-x86" || $2 == "qemu-system-x86_64" || $2 == "emulator") { print $1 }
  ') || code=$?
  if [[ "$code" -ne 0 ]]; then
    trace_record "tracer=unavailable reason=pid-read-failed exit=$code"
    return 0
  fi
  if [[ -z "$candidates" ]]; then
    trace_record 'tracer=unavailable reason=emulator-pid-missing'
    return 0
  fi
  mapfile -t pids <<< "$candidates"
  if [[ "${#pids[@]}" -ne 1 ]]; then
    trace_record "tracer=unavailable reason=ambiguous-emulator-pids count=${#pids[@]}"
    return 0
  fi
  trace_target_pid=${pids[0]}
  trace_raw=$(mktemp /tmp/ink-host-exit-trace.XXXXXX) || {
    trace_record 'tracer=unavailable reason=temporary-output-failed'
    return 0
  }
  # Recheck the selected PID immediately before attaching; never inspect argv/env.
  comm=$(timeout --kill-after=5s 20s ps -p "$trace_target_pid" -o comm:32= 2>/dev/null) || code=$?
  comm=${comm//[[:space:]]/}
  if [[ "$code" -ne 0 || ! "$comm" =~ ^(qemu-system-x86|qemu-system-x86_64|emulator)$ ]]; then
    trace_record "tracer=unavailable reason=emulator-pid-recheck-failed exit=$code"
    rm -f -- "$trace_raw"
    trace_raw=
    return 0
  fi
  # GNU timeout creates its own process group (no --foreground). The already
  # running emulator remains outside it. INT detaches strace; no target kill.
  # -f plus -o supplies PID prefixes; the raw file is outside uploaded artifacts.
  timeout --signal=INT --kill-after=5s 300s sudo -n strace -f -q -e trace=none \
    -e signal=SIGSEGV,SIGABRT,SIGBUS,SIGFPE,SIGILL,SIGKILL,SIGTERM,SIGQUIT \
    -p "$trace_target_pid" -o "$trace_raw" >/dev/null 2>&1 &
  trace_wrapper_pid=$!
  trace_record 'tracer=started diagnostic-only=true bound_seconds=300 kill_after_seconds=5'
  return 0
}

finish_host_exit_trace() {
  [[ -n "$trace_wrapper_pid" ]] || return 0
  local code=0 filter_code=0
  # Reap only our bounded wrapper, including when EXIT cleanup runs early.
  wait "$trace_wrapper_pid" || code=$?
  trace_wrapper_pid=
  trace_record "tracer_exit=$code"
  printf 'host-emulator-exit-trace exit=%s target_pid=%s\n' "$code" "$trace_target_pid" >> "$diagnostics/capture-status.txt"
  case "$code" in
    0) trace_record 'tracer=finished' ;;
    124) trace_record 'tracer=timeout target_exit_not_inferred=true' ;;
    137) trace_record 'tracer=timeout-or-wrapper-killed target_exit_not_inferred=true' ;;
    *) trace_record 'tracer=unavailable-or-failed reason=attach-privilege-tool-or-tracing-failure' ;;
  esac
  if [[ -f "$trace_raw" ]]; then
    # -f includes threads/children. Only a terminal event for the selected PID
    # establishes a target exit; child/thread exits do not establish that.
    awk -v target="$trace_target_pid" -v status_file="$trace_status_file" '
      {
        line = $0; event_pid = "unattributed"
        if (line ~ /^\[pid[[:space:]]+[0-9]+\][[:space:]]+/) {
          event_pid = line; sub(/^\[pid[[:space:]]+/, "", event_pid); sub(/\].*$/, "", event_pid)
          sub(/^\[pid[[:space:]]+[0-9]+\][[:space:]]+/, "", line)
        } else if (line ~ /^[0-9]+[[:space:]]+/) {
          event_pid = line; sub(/[[:space:]].*$/, "", event_pid)
          sub(/^[0-9]+[[:space:]]+/, "", line)
        }
        terminal = line ~ /^\+\+\+ (exited with [0-9]+|killed by SIG[A-Z0-9]+( \(core dumped\))?) \+\+\+$/
        if (terminal || line ~ /^--- SIG(SEGV|ABRT|BUS|FPE|ILL|KILL|TERM|QUIT)[[:space:]].* ---$/) print $0
        if (terminal && event_pid == target) observed = 1
        if (terminal && event_pid == "unattributed") unattributed = 1
      }
      END {
        printf "target_pid=%s target_exit=%s unattributed_terminal=%s\n", target, (observed ? "observed" : "no-observed-exit"), (unattributed ? "present" : "absent") >> status_file
      }
    ' "$trace_raw" | tail -n 120 > "$diagnostics/host-emulator-exit-trace.txt" || filter_code=$?
    trace_record "filter_exit=$filter_code"
    rm -f -- "$trace_raw"
    trace_raw=
  else
    trace_record 'trace_output=missing target_exit=no-observed-exit'
  fi
  return 0
}

# Cleanup diagnostics never replace the original permission/main/asset exit.
trap 'script_exit_status=$?; finish_host_exit_trace; exit "$script_exit_status"' EXIT

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
if [[ "$api" -eq 35 ]]; then
  capture_emulator_processes host-emulator-processes-before.txt
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
start_host_exit_trace
"${gradle[@]}" connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.notClass=$permission_class"
test_status=$?
copy_raw_results "$diagnostics/raw-test-results" raw-test-results-copy.txt
finish_host_exit_trace
if [[ "$api" -eq 35 ]]; then
  capture_emulator_processes host-emulator-processes-after.txt
  # Read-only kernel evidence on the ephemeral GitHub Actions host, before ADB.
  capture_host host-kernel-events.txt bash -c '
    set -o pipefail
    events=$(sudo -n dmesg | awk '\''
      tolower($0) ~ /oom|out of memory|killed process|segfault|general protection fault|general fault|qemu|emulator/
    '\'' | tail -n 120)
    code=$?
    if [[ "$code" -ne 0 ]]; then
      printf "kernel events unavailable: noninteractive sudo/dmesg read or filter failed (privilege may be unavailable), exit=%s\n" "$code"
    elif [[ -n "$events" ]]; then
      printf "%s\n" "$events"
    else
      printf "kernel events: no matching events\n"
    fi
    exit "$code"
  '
fi
capture_host host-emulator-crash.json node scripts/describe-emulator-crash.mjs
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
