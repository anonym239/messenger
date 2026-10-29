#!/usr/bin/env bash
# Läuft im Android-Emulator: alle Tests (Handy hell), dann Oberflächentest im
# dunklen Design und als Tablet. Screenshots landen in ./screens
set -e
LEAVE="-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"

run() {
  if ! gradle --no-daemon connectedDebugAndroidTest $LEAVE "$@"; then
    adb logcat -d | grep -A40 "FATAL EXCEPTION" | head -80 | sed 's/^/::error::/' || true
    find app/build/outputs/androidTest-results -name "*.xml" -exec grep -h -A4 "<failure" {} \; 2>/dev/null \
      | head -40 | sed 's/^/::error::/' || true
    pull_screens
    exit 1
  fi
}

pull_screens() {
  mkdir -p screens
  adb pull /sdcard/Android/data/de.notfall.mesh/files/screens screens/ >/dev/null 2>&1 || true
}

run
adb shell cmd uimode night yes
run -Pandroid.testInstrumentationRunnerArguments.class=de.notfall.mesh.UiSmokeTest
adb shell cmd uimode night no
adb shell wm size 1600x2560
adb shell wm density 280
run -Pandroid.testInstrumentationRunnerArguments.class=de.notfall.mesh.UiSmokeTest
adb shell wm size reset
adb shell wm density reset
pull_screens
