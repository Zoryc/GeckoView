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

# Prints "x y" of the first node matching $2 below the first node whose text is exactly $1
# (e.g. the "Play on phone" button of the row tagged "HLS").
find_below() {
  dump_ui
  python3 - "$1" "$2" "$WORK/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
anchor, pattern = sys.argv[1], re.compile(sys.argv[2])
try:
    root = ET.parse(sys.argv[3]).getroot()
except Exception:
    sys.exit(1)
nodes = []
for node in root.iter("node"):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "0 0 0 0")))
    nodes.append(((node.get("text") or "", node.get("content-desc") or ""), (x1 + x2) // 2, (y1 + y2) // 2))
tops = [y for (t, d), x, y in nodes if anchor in (t.strip(), d.strip())]
if not tops:
    sys.exit(1)
below = sorted((y, x) for (t, d), x, y in nodes if y > tops[0] and (pattern.search(t) or pattern.search(d)))
if not below:
    sys.exit(1)
print(below[0][1], below[0][0])
PY
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

leave_player() {
  adb shell input keyevent KEYCODE_BACK; sleep 2
  # Leave the player (PiP may keep it): bring Snapcast's main activity back.
  adb shell am start -W -n "$ACTIVITY" >/dev/null 2>&1; sleep 2
}

watch_player() { # step-name
  sleep 6
  # Android's one-time "Viewing full screen" hint covers the video; dismiss it.
  find_center "^Got it$" >/dev/null && tap "^Got it$" 5
  shot "$1" 12
  report "player $1"
  leave_player
}

play_kind() { # type-chip (HLS, MP4, ...) step-name
  local center waited=0
  log "play on phone: $1 row"
  while (( waited < 20 )); do
    if center="$(find_below "$1" "^Play on phone$")"; then
      # shellcheck disable=SC2086
      adb shell input tap $center
      watch_player "$2"; return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  log "no $1 row with a Play on phone button"; return 1
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

play_kind HLS 03-play-hls
open_list; play_kind MP4 04-play-mp4

log "waiting for tokens to expire"
sleep 130
open_list; play_kind HLS 05-play-hls-expired
open_list
tap "^Scan again$" 10 && sleep 6
shot 06-list-after-rescan 2
report "list after rescan"
play_kind HLS 07-play-hls-after-rescan

log "reload page and retry"
adb shell am start -W -a android.intent.action.VIEW -d "$SITE" -n "$ACTIVITY" >/dev/null
wait_for "Videos found on this page" 90 >/dev/null
sleep 8
open_list; shot 08-list-after-reload 3
report "list after reload"
play_kind HLS 09-play-hls-after-reload
log "done"
