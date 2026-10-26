#!/usr/bin/env bash
# Drives one Snapcast build through the local field-test site on a running emulator.
# Usage: scripts/fieldtest/run.sh <apk> <label> <output-dir>
set -uo pipefail

APK="$1"; LABEL="$2"; OUT="$3"
PKG="app.snapcast"
ACTIVITY="$PKG/.MainActivity"
SITE="http://site.10.0.2.2.nip.io:8080/"
WORK="$(mktemp -d)"
mkdir -p "$OUT"

log() { echo "[fieldtest:$LABEL] $*"; }

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml > "$WORK/ui.xml" 2>/dev/null || true
}

# Prints "x y" of the centre of the n-th (1-based) node whose text or content-desc matches.
find_center() {
  dump_ui
  python3 - "$1" "${2:-1}" "$WORK/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
pattern, nth = re.compile(sys.argv[1]), int(sys.argv[2])
try:
    root = ET.parse(sys.argv[3]).getroot()
except Exception:
    sys.exit(1)
hits = []
for node in root.iter("node"):
    for attr in ("text", "content-desc"):
        value = node.get(attr) or ""
        if value and pattern.search(value):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
            hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
            break
if len(hits) >= nth:
    print(*hits[nth - 1])
    sys.exit(0)
sys.exit(1)
PY
}

# Prints every visible text / content-desc, top to bottom, for the report.
ui_text() {
  dump_ui
  python3 - "$WORK/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    print("(no ui dump)"); sys.exit(0)
rows = []
for node in root.iter("node"):
    for attr in ("text", "content-desc"):
        value = (node.get(attr) or "").strip()
        if value:
            y = int(re.findall(r"\d+", node.get("bounds", "0 0 0 0"))[1])
            rows.append((y, value.replace("\n", " ")))
seen = set()
for y, value in sorted(rows):
    if (y, value) not in seen:
        seen.add((y, value))
        print(f"  {y:5d}  {value}")
PY
}

# Prints the text / content-desc of the first node matching the regex.
find_text() {
  dump_ui
  python3 - "$1" "$WORK/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
pattern = re.compile(sys.argv[1])
try:
    root = ET.parse(sys.argv[2]).getroot()
except Exception:
    sys.exit(1)
for node in root.iter("node"):
    for attr in ("text", "content-desc"):
        value = node.get(attr) or ""
        if value and pattern.search(value):
            print(value); sys.exit(0)
sys.exit(1)
PY
}

# Opens each detection-coverage page and reports whether the app listed a video for it.
coverage() {
  local key expect found count desc
  # localhost is a secure context (service workers need one); forward it to the test site.
  adb reverse tcp:8080 tcp:8080 >/dev/null
  for key in mp4 sources late nested hlsjs dash api-hls api-text sw ad; do
    expect="found"; [ "$key" = "ad" ] && expect="filtered"
    adb shell am start -W -a android.intent.action.VIEW -d "http://localhost:8080/s/$key" -n "$ACTIVITY" >/dev/null
    found="none"; count=0
    if wait_for "Videos found on this page" 35 >/dev/null; then
      desc="$(find_text "Videos found on this page" || true)"
      count="${desc##*: }"; found="listed"
      find_center "^Got it$" >/dev/null && tap "^Got it$" 5
      tap "Videos found on this page" 15 && wait_for "Videos on this page" 15 >/dev/null
      echo "::group::[$LABEL] coverage $key"; ui_text; echo "::endgroup::"
      shot "cov-$key" 1
      adb shell input keyevent KEYCODE_BACK; sleep 1
    fi
    echo "COVERAGE $LABEL $key expect=$expect result=$found count=$count"
  done
}

wait_for() {
  local pattern="$1" timeout="${2:-60}" waited=0 center
  while (( waited < timeout )); do
    if center="$(find_center "$pattern" "${3:-1}")"; then echo "$center"; return 0; fi
    sleep 3; waited=$((waited + 3))
  done
  return 1
}

tap() {
  local center
  if center="$(wait_for "$1" "${2:-30}" "${3:-1}")"; then
    # shellcheck disable=SC2086
    adb shell input tap $center; return 0
  fi
  log "could not find /$1/ #${3:-1}"; return 1
}

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$LABEL-$1.png"; log "screenshot $1"; }

report() { # title
  echo "::group::[$LABEL] $1"; ui_text; echo "--- logcat"; \
    adb logcat -d -v brief 2>/dev/null | grep -iE "ExoPlayer|PlaybackException|InvalidResponseCode|HttpDataSource|Response code|AndroidRuntime|FATAL|Snapcast|coil|Coil" | tail -n 60; \
    echo "::endgroup::"; adb logcat -c; }

play_item() { # n step-name
  log "play on phone #$1"
  if tap "^Play on phone$" 20 "$1"; then
    shot "$2" 18
    report "player $2"
    adb shell input keyevent KEYCODE_BACK; sleep 2
    # Leave the player (PiP may keep it): bring Snapcast's main activity back.
    adb shell am start -W -n "$ACTIVITY" >/dev/null 2>&1; sleep 2
  fi
}

open_list() {
  if ! find_center "Videos on this page" >/dev/null; then
    find_center "^Got it$" >/dev/null && tap "^Got it$" 5
    tap "Videos found on this page" 30 || return 1
  fi
  wait_for "Videos on this page" 20 >/dev/null
}

log "install"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" >/dev/null
adb logcat -c

log "open test site"
adb shell am start -W -a android.intent.action.VIEW -d "$SITE" -n "$ACTIVITY" >/dev/null
if ! wait_for "Videos found on this page" 150 >/dev/null; then log "video button never appeared"; fi
shot 01-page 8
report "page"

open_list
shot 02-list 4
report "video list"

play_item 1 03-play-1
open_list; play_item 2 04-play-2
open_list; play_item 3 05-play-3

log "waiting for tokens to expire"
sleep 130
open_list; play_item 1 06-play-1-expired
open_list
tap "^Scan again$" 10 && sleep 6
shot 07-list-after-rescan 2
report "list after rescan"
play_item 1 08-play-1-after-rescan

log "detection coverage"
coverage

log "reload page and retry"
adb shell am start -W -a android.intent.action.VIEW -d "$SITE" -n "$ACTIVITY" >/dev/null
wait_for "Videos found on this page" 90 >/dev/null
open_list; shot 09-list-after-reload 3
play_item 1 10-play-1-after-reload
log "done"
