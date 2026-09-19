#!/usr/bin/env bash
# Preserve the test exit status while collecting only synthetic acceptance assets.
set -uo pipefail
permission_class=com.boomerang.app.reminders.NotificationPermissionTest
permission_status=0
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$api" -ge 33 ]]; then
  # Changing permission while instrumentation is running can kill its process.
  bash android/gradlew -p android installDebug installDebugAndroidTest || exit "$?"
  adb shell pm revoke com.boomerang.app android.permission.POST_NOTIFICATIONS || exit "$?"
  adb shell pm clear-permission-flags com.boomerang.app android.permission.POST_NOTIFICATIONS user-set user-fixed || exit "$?"
  bash android/gradlew -p android connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.class=$permission_class"
  permission_status=$?
  mkdir -p android/app/build/permission-reports
  cp -R android/app/build/reports/androidTests/. android/app/build/permission-reports/
fi
bash android/gradlew -p android connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "-Pandroid.testInstrumentationRunnerArguments.notClass=$permission_class"
test_status=$?
if [[ -d android/app/build/permission-reports ]]; then
  mkdir -p android/app/build/reports/androidTests/permission
  cp -R android/app/build/permission-reports/. android/app/build/reports/androidTests/permission/
fi
mkdir -p android/app/build/reports/androidTests/acceptance
adb pull /sdcard/Android/data/com.boomerang.app/files/acceptance/. android/app/build/reports/androidTests/acceptance/
asset_status=$?
if [[ "$permission_status" -ne 0 ]]; then exit "$permission_status"; fi
if [[ "$test_status" -eq 0 && "$asset_status" -ne 0 ]]; then
  echo 'Device tests passed but acceptance screenshots could not be collected.' >&2
  exit "$asset_status"
fi
exit "$test_status"
