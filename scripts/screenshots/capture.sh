#!/usr/bin/env bash
# Drives the debug build on a running emulator and captures the README screenshots.
# Usage: scripts/screenshots/capture.sh <apk> <output-dir>
set -euo pipefail

APK="$1"
OUT="$2"
PKG="app.snapcast"
ACTIVITY="$PKG/.MainActivity"
VIDEO_PAGE="https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_4K.webm"
WORK="$(mktemp -d)"
mkdir -p "$OUT"

log() { echo "[screenshots] $*"; }

# Prints "x y" of the centre of the first UI node whose text or content-desc matches the regex.
find_center() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml > "$WORK/ui.xml" 2>/dev/null || return 1
  python3 - "$1" "$WORK/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
pattern = re.compile(sys.argv[1])
try:
    root = ET.parse(sys.argv[2]).getroot()
except ET.ParseError:
    sys.exit(1)
for node in root.iter("node"):
    for attr in ("text", "content-desc"):
        value = node.get(attr) or ""
        if value and pattern.search(value):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}

# Waits until a node matching the regex is on screen; prints its centre.
wait_for() {
  local pattern="$1" timeout="${2:-60}" waited=0 center
  while (( waited < timeout )); do
    if center="$(find_center "$pattern")"; then
      echo "$center"
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done
  log "timed out waiting for /$pattern/"
  return 1
}

tap() {
  local center
  center="$(wait_for "$1" "${2:-60}")"
  # shellcheck disable=SC2086
  adb shell input tap $center
}

shot() {
  sleep "${2:-2}"
  adb exec-out screencap -p > "$OUT/$1.png"
  log "captured $1"
}

demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

log "installing $APK"
adb install -r -g "$APK" >/dev/null
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

log "clean status bar"
adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 1200
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false

log "home screen"
adb shell am start -W -n "$ACTIVITY" >/dev/null
wait_for "Speed dial|Search or enter address" 120 >/dev/null
shot 01-home 4

log "video page"
adb shell am start -W -a android.intent.action.VIEW -d "$VIDEO_PAGE" -n "$ACTIVITY" >/dev/null
wait_for "Videos found on this page" 120 >/dev/null
shot 02-browser 6

# First run shows a one-time tip next to the video button.
if find_center "^Got it$" >/dev/null; then
  tap "^Got it$" 10
  sleep 1
fi

log "video list"
tap "Videos found on this page" 30
wait_for "Videos on this page" 30 >/dev/null
shot 03-videos 3

log "add-ons"
adb shell input keyevent KEYCODE_BACK
sleep 2
tap "^More options$" 30
tap "^Add-ons$" 30
wait_for "uBlock Origin" 60 >/dev/null
shot 04-addons 3

log "done"
