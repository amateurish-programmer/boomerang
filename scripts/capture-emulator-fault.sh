#!/usr/bin/env bash
# Opt-in disposable Linux CI only; stdout/stderr from GDB never leave private files.
set -uo pipefail
diagnostics="$1"
status_file="$diagnostics/host-emulator-fault-status.txt"
metadata_file="$diagnostics/host-emulator-fault.txt"
target_pid=unavailable
private_dir=
wrapper_pid=
record() { printf 'target_pid=%s %s\n' "$target_pid" "$*" >> "$status_file"; }
unavailable() {
  record "observer=unavailable reason=$1"
  printf 'readiness=unavailable\n' >> "$status_file"
}
cleanup() {
  local code=0 wrapper_code=0
  if [[ -n "$wrapper_pid" ]]; then
    # Only reap our timeout wrapper; its own group receives INT after 300s.
    wait "$wrapper_pid" || wrapper_code=$?
    wrapper_pid=
    record "observer_exit=$wrapper_code target_exit_not_inferred=true"
  fi
  if [[ -n "$private_dir" ]]; then
    rm -f -- "$private_dir/commands" "$private_dir/stdout" "$private_dir/stderr" 2>/dev/null || code=$?
    rmdir -- "$private_dir" 2>/dev/null || code=$?
    record "cleanup_exit=$code"
    private_dir=
  fi
  return 0
}
trap 'helper_exit=$?; cleanup; exit "$helper_exit"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ "${INK_TRACE_HOST_FAULT:-0}" != 1 || "${2:-}" != 35 || "${RUNNER_ENVIRONMENT:-}" != github-hosted || "$(uname -s)" != Linux ]]; then
  unavailable trusted-ci-opt-in-required
  exit 0
fi
for tool in gdb sudo timeout python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    unavailable required-tool-missing
    exit 0
  fi
done
code=0
candidates=$(timeout --kill-after=5s 20s ps -eo pid=,comm:32= 2>/dev/null | awk '
  $1 ~ /^[0-9]+$/ && $1 > 0 &&
  ($2 == "emulator" || $2 == "qemu-system-x86" || $2 == "qemu-system-x86_64") { print $1 }
') || code=$?
if [[ "$code" -ne 0 ]]; then unavailable pid-read-failed; exit 0; fi
if [[ -z "$candidates" ]]; then unavailable emulator-pid-missing; exit 0; fi
mapfile -t pids <<< "$candidates"
if [[ "${#pids[@]}" -ne 1 ]]; then unavailable ambiguous-emulator-pids; exit 0; fi
target_pid=${pids[0]}
private_dir=$(umask 077; mktemp -d /tmp/ink-host-fault.XXXXXX 2>/dev/null) || {
  unavailable temporary-directory-failed
  exit 0
}
if ! (umask 077; : > "$private_dir/commands" && : > "$private_dir/stdout" && : > "$private_dir/stderr") 2>/dev/null; then
  unavailable temporary-files-failed
  exit 0
fi
if ! cat > "$private_dir/commands" 2>/dev/null <<'GDB_COMMANDS'
set confirm off
set pagination off
handle SIGSEGV SIGABRT SIGBUS SIGFPE SIGILL stop print pass
python
import gdb
import os
import re

faults = {"SIGSEGV", "SIGABRT", "SIGBUS", "SIGFPE", "SIGILL"}
pending = None
first_stop = None
exited = False
observed_fault = False
invalid_pid = False
attached_inferior = None

def emit(fields):
    gdb.write("INK_FAULT pid=%d %s\n" % (target_pid, fields))
    gdb.flush()

def safe_name(value, pattern, limit):
    return value if isinstance(value, str) and 0 < len(value) <= limit and re.fullmatch(pattern, value, flags=re.ASCII) else "unavailable"

def stop_signal_name(value):
    # Fixed Linux names/aliases and GDB numeric real-time spellings SIG32..SIG64.
    pattern = r"SIG(?:HUP|INT|QUIT|ILL|TRAP|ABRT|IOT|BUS|FPE|KILL|USR1|SEGV|USR2|PIPE|ALRM|TERM|STKFLT|CHLD|CLD|CONT|STOP|TSTP|TTIN|TTOU|URG|XCPU|XFSZ|VTALRM|PROF|WINCH|IO|POLL|PWR|SYS|UNUSED|3[2-9]|[45][0-9]|6[0-4])"
    return safe_name(value, pattern, 12)

def on_stop(event):
    global pending, invalid_pid, first_stop
    if gdb.selected_inferior().pid != target_pid:
        invalid_pid = True
        return
    if isinstance(event, gdb.SignalEvent):
        pending = event.stop_signal
        kind, signal = "signal", stop_signal_name(pending)
    else:
        pending = None
        kind = "breakpoint" if isinstance(event, gdb.BreakpointEvent) else "stop" if isinstance(event, gdb.StopEvent) else "unavailable"
        signal = "unavailable"
    if first_stop is None:
        first_stop = (kind, signal)

def on_exit(event):
    global exited
    if event.inferior != attached_inferior:
        emit("error reason=pid_mismatch")
        return
    exited = True
    code = getattr(event, "exit_code", None)
    if isinstance(code, int) and 0 <= code <= 255:
        emit("exit kind=code value=%d" % code)
    else:
        # Fixed GDB convenience variable, never a target-derived expression.
        signal_names = {11: "SIGSEGV", 6: "SIGABRT", 7: "SIGBUS", 8: "SIGFPE", 4: "SIGILL"}
        try:
            name = signal_names.get(int(gdb.parse_and_eval("$_exitsignal")), "unavailable")
        except Exception:
            name = "unavailable"
        emit("exit kind=%s value=%s" % ("signal" if name != "unavailable" else "unknown", name))

try:
    if gdb.selected_inferior().pid != target_pid:
        emit("error reason=pid_mismatch")
    else:
        attached_inferior = gdb.selected_inferior()
        gdb.events.stop.connect(on_stop)
        gdb.events.exited.connect(on_exit)
        emit("ready")
        gdb.execute("continue", to_string=True)
        if invalid_pid:
            emit("error reason=pid_mismatch")
        elif pending in faults and not exited:
            observed_fault = True
            emit("fault signal=%s" % pending)
            try:
                frame = gdb.newest_frame()
                for index in range(8):
                    if frame is None:
                        break
                    pc = frame.pc()
                    if not isinstance(pc, int) or not 0 <= pc <= 0xffffffffffffffff:
                        emit("error reason=frame_unavailable")
                        break
                    function = safe_name(frame.name(), r"[A-Za-z0-9_:.$~+\-]+", 160)
                    path = gdb.solib_name(pc)
                    module = safe_name(os.path.basename(path) if isinstance(path, str) else None, r"[A-Za-z0-9_.+\-]+", 96)
                    emit("frame index=%d pc=0x%x function=%s module=%s" % (index, pc, function, module))
                    frame = frame.older()
            except Exception:
                emit("error reason=frame_unavailable")
            finally:
                # Deliver the original pending fault with normal pass semantics.
                # Never issue signal/kill, suppress the fault, or launch a target.
                gdb.execute("handle SIGSEGV SIGABRT SIGBUS SIGFPE SIGILL nostop noprint pass", to_string=True)
                gdb.execute("continue", to_string=True)
        if not observed_fault:
            emit("observation=no_observed_fault")
        if not exited and not observed_fault and not invalid_pid:
            kind, signal = first_stop if first_stop is not None else ("unavailable", "unavailable")
            emit("first_stop kind=%s signal=%s" % (kind, signal))
            emit("error reason=unknown_stop")
except KeyboardInterrupt:
    emit("error reason=interrupted")
except Exception:
    emit("error reason=debugger_failure")
finally:
    try:
        if gdb.selected_inferior().pid == target_pid:
            gdb.execute("detach", to_string=True)
            emit("detach result=complete")
    except Exception:
        emit("detach result=failed")
end
GDB_COMMANDS
then
  unavailable command-file-failed
  exit 0
fi
# Recheck the exact comm immediately before attach; argv/env are never inspected.
code=0
comm=$(timeout --kill-after=5s 20s ps -p "$target_pid" -o comm:32= 2>/dev/null) || code=$?
comm=${comm//[[:space:]]/}
if [[ "$code" -ne 0 || ! "$comm" =~ ^(emulator|qemu-system-x86|qemu-system-x86_64)$ ]]; then
  unavailable emulator-pid-recheck-failed
  exit 0
fi
# Shell opens 0600 output files before sudo; GDB does not create root-owned files.
# timeout owns a separate group; the existing emulator is outside that group.
LC_ALL=C timeout --signal=INT --kill-after=5s 300s sudo -n gdb -nx -nh --batch --quiet \
  -iex 'set auto-load off' -iex 'set debuginfod enabled off' \
  -p "$target_pid" -ex "python target_pid = $target_pid" -x "$private_dir/commands" \
  > "$private_dir/stdout" 2> "$private_dir/stderr" &
wrapper_pid=$!
record 'observer=started diagnostic-only=true bound_seconds=300 kill_after_seconds=5'
for ((attempt = 0; attempt < 90; attempt++)); do
  if grep -Fxq "INK_FAULT pid=$target_pid ready" "$private_dir/stdout" 2>/dev/null; then
    printf 'readiness=ready\n' >> "$status_file"
    break
  fi
  if ! kill -0 "$wrapper_pid" 2>/dev/null; then
    printf 'readiness=unavailable\n' >> "$status_file"
    break
  fi
  sleep 0.1
done
if [[ "$attempt" -eq 90 ]]; then printf 'readiness=timeout\n' >> "$status_file"; fi
code=0
wait "$wrapper_pid" || code=$?
wrapper_pid=
record "observer_exit=$code target_exit_not_inferred=true"
case "$code" in
  0) record 'observer=finished' ;;
  124) record 'observer=timeout' ;;
  137) record 'observer=timeout-or-wrapper-killed' ;;
  *) record 'observer=tool-or-attach-failed' ;;
esac
reason=none
if grep -Eqi 'ptrace.*(not permitted|permission denied)|attach.*(not permitted|permission denied)' "$private_dir/stderr" 2>/dev/null; then
  reason=ptrace_permission_denied
elif grep -Eqi '^sudo:' "$private_dir/stderr" 2>/dev/null; then
  reason=sudo_unavailable
elif [[ -s "$private_dir/stderr" ]]; then
  reason=unclassified
fi
record "stderr_reason=$reason"
filter_code=0
if [[ ! -f "$private_dir/stdout" ]]; then
  record 'metadata=missing-raw'
else
  # Validate the entire marker set before emitting anything. Native GDB output,
  # paths, error strings and target names never enter the artifact/log stream.
  python3 - "$private_dir/stdout" "$target_pid" "$metadata_file" <<'FILTER_PY' 2>/dev/null || filter_code=$?
import re
import sys

raw, target, output = sys.argv[1:]
if not re.fullmatch(r"[1-9][0-9]{0,19}", target, re.ASCII):
    sys.exit(2)
prefix = "INK_FAULT pid=" + target + " "
fixed = re.compile(r"(?:ready|fault signal=SIG(?:SEGV|ABRT|BUS|FPE|ILL)|exit kind=code value=(?:0|[1-9][0-9]{0,2})|exit kind=signal value=SIG(?:SEGV|ABRT|BUS|FPE|ILL)|exit kind=unknown value=unavailable|error reason=(?:pid_mismatch|frame_unavailable|interrupted|debugger_failure|unknown_stop)|observation=no_observed_fault|detach result=(?:complete|failed))", re.ASCII)
frame = re.compile(r"frame index=([0-7]) pc=0x[0-9a-f]{1,16} function=[A-Za-z0-9_:.$~+\-]{1,160} module=[A-Za-z0-9_.+\-]{1,96}", re.ASCII)
first_stop = re.compile(r"first_stop kind=(?:signal|breakpoint|stop|unavailable) signal=(?:unavailable|SIG(?:HUP|INT|QUIT|ILL|TRAP|ABRT|IOT|BUS|FPE|KILL|USR1|SEGV|USR2|PIPE|ALRM|TERM|STKFLT|CHLD|CLD|CONT|STOP|TSTP|TTIN|TTOU|URG|XCPU|XFSZ|VTALRM|PROF|WINCH|IO|POLL|PWR|SYS|UNUSED|3[2-9]|[45][0-9]|6[0-4]))", re.ASCII)
records = []
frames = 0
fault = False
stop_seen = False
with open(raw, "rb") as source:
    payload = source.read(1024 * 1024 + 1)
    if len(payload) > 1024 * 1024:
        sys.exit(2)
    for line in payload.decode("utf-8", errors="replace").splitlines():
        if not line.startswith("INK_FAULT"):
            continue
        if not line.startswith(prefix) or len(records) >= 24:
            sys.exit(2)
        fields = line[len(prefix):]
        match = frame.fullmatch(fields)
        if match:
            if not fault or int(match[1]) != frames or frames >= 8:
                sys.exit(2)
            frames += 1
        elif first_stop.fullmatch(fields):
            if stop_seen or fault or (not fields.startswith("first_stop kind=signal ") and not fields.endswith("signal=unavailable")):
                sys.exit(2)
            stop_seen = True
        elif not fixed.fullmatch(fields):
            sys.exit(2)
        if fields.startswith("exit kind=code") and int(fields.rsplit("=", 1)[1]) > 255:
            sys.exit(2)
        if fields.startswith("fault "):
            if fault or stop_seen:
                sys.exit(2)
            fault = True
        records.append("target_pid=" + target + " " + fields)
if not records:
    sys.exit(3)
with open(output, "w", encoding="ascii") as destination:
    destination.write("\n".join(records) + "\n")
FILTER_PY
  case "$filter_code" in
    0) record 'metadata=validated' ;;
    3) record 'metadata=no-observer-records' ;;
    *) record 'metadata=filter-failed' ;;
  esac
fi
exit 0
