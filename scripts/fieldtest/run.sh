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

# Marks the start of a step in the site's request log (the driver runs on the host, next to the server).
mark() { curl -fsS -o /dev/null -H "Host: site.10.0.2.2.nip.io:8080" "http://127.0.0.1:8080/__mark?step=$LABEL-$1" || true; }

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml > "$WORK/ui.xml" 2>/dev/null || true
  dismiss_not_responding
}

# A slow emulator sometimes shows "<app> isn't responding" (often the launcher) over everything, and the
# dump then holds only that dialog. Another app's dialog is answered "Close app" (Wait lets it come back every
# few seconds and swallow the driver's taps); Snapcast's is a real ANR: logged loudly and answered Wait. Either
# way Android's reason and CPU usage summary are printed, which show what loaded the device.
dismiss_not_responding() {
  local found
  found="$(python3 - "$WORK/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(1)
title = None
buttons = {}
for node in root.iter("node"):
    value = node.get("text") or ""
    if re.search(r"isn.t responding$", value):
        title = value
    if value in ("Wait", "Close app"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        buttons[value] = f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
if not title:
    sys.exit(1)
button = "Wait" if title.startswith("Snapcast") or "Close app" not in buttons else "Close app"
if button not in buttons:
    sys.exit(1)
print(f"{buttons[button]}|{button}|{title}")
PY
)" || return 0
  local center="${found%%|*}" rest="${found#*|}"
  local button="${rest%%|*}" title="${rest#*|}"
  case "$title" in
    Snapcast*) log "SNAPCAST NOT RESPONDING: $title, tapping $button" ;;
    *) log "system dialog: $title, tapping $button" ;;
  esac
  echo "::group::[$LABEL] ANR reason ($title)"
  adb logcat -d -v brief 2>/dev/null | grep -A 25 "ANR in" | tail -n 60
  echo "::endgroup::"
  # shellcheck disable=SC2086
  adb shell input tap $center
  sleep 2
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
    # Crashes have their own buffer, which Gecko's chatty main log cannot push out.
    echo "--- crash buffer"; adb logcat -d -b crash -v brief 2>/dev/null | tail -n 80; \
    echo "::endgroup::"; adb logcat -c; adb logcat -b crash -c 2>/dev/null; }

# Why Snapcast's process ended, if it did (crash, ANR, low memory, killed), as Android recorded it.
exit_info() {
  echo "::group::[$LABEL] process exits"
  adb shell dumpsys activity exit-info "$PKG" 2>/dev/null | grep -E "ApplicationExitInfo|reason=|description=|importance=|pss=|rss=|timestamp=" | head -n 60
  echo "::endgroup::"
}

leave_player() {
  adb shell input keyevent KEYCODE_BACK; sleep 2
  # Leave the player (PiP may keep it): bring Snapcast's main activity back.
  adb shell am start -W -n "$ACTIVITY" >/dev/null 2>&1; sleep 2
}

# Logs whether the player shows its error overlay.
check_played() { # step-name
  if find_center "^Can.t play this video$" >/dev/null; then log "PLAYBACK ERROR at $1"; else log "$1: no error shown"; fi
}

watch_player() { # step-name
  sleep 6
  # Android's one-time "Viewing full screen" hint covers the video; dismiss it.
  find_center "^Got it$" >/dev/null && tap "^Got it$" 5
  shot "$1" 12
  check_played "$1"
  report "player $1"
  leave_player
}

tap_below() { # anchor-text pattern
  local center waited=0
  while (( waited < 20 )); do
    if center="$(find_below "$1" "$2")"; then
      # shellcheck disable=SC2086
      adb shell input tap $center; return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  log "no /$2/ below $1"; return 1
}

play_kind() { # type-chip (HLS, MP4, ...) step-name [keep: leave the player open]
  local center waited=0
  log "play on phone: $1 row"
  while (( waited < 20 )); do
    if center="$(find_below "$1" "^Play on phone$")"; then
      # shellcheck disable=SC2086
      adb shell input tap $center
      if [ "${3:-}" = "keep" ]; then
        sleep 6
        find_center "^Got it$" >/dev/null && tap "^Got it$" 5
        shot "$2" 12; report "player $2"
      else
        watch_player "$2"
      fi
      return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  log "no $1 row with a Play on phone button"; return 1
}

reload_site() {
  adb shell am start -W -a android.intent.action.VIEW -d "$SITE" -n "$ACTIVITY" >/dev/null
  wait_for "Videos found on this page" 90 >/dev/null
  sleep 8
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
if ! wait_for "Videos found on this page" 150 >/dev/null; then
  log "video button never appeared"
  adb shell dumpsys activity activities 2>/dev/null | grep -E "mResumedActivity|topResumedActivity" | head -n 3
  exit_info
fi
shot 01-page 8
report "page"

open_list
shot 02-list 4
report "video list"
# The page's own player plays the HLS stream (muted autoplay in its iframe; the 24 s clip may have ended by
# now): it is tagged and listed first.
if find_center "^(Playing|Paused) on page$" >/dev/null; then
  hls_y="$(find_center "^HLS$" | cut -d' ' -f2)"; mp4_y="$(find_center "^MP4$" | cut -d' ' -f2)"
  if [ -n "$hls_y" ] && [ -n "$mp4_y" ] && [ "$hls_y" -lt "$mp4_y" ]; then log "playing video tagged and listed first"; else log "PLAYING VIDEO NOT FIRST (HLS at $hls_y, MP4 at $mp4_y)"; fi
else
  log "PLAYING VIDEO NOT TAGGED"
fi

mark 03-play-hls
play_kind HLS 03-play-hls

# Pick 360p for the HLS stream, then play it on the phone: the site log must show only 360p segments.
# The page is loaded again first so its link is fresh (the test site's tokens last 120 s).
log "reload for a fresh link"
reload_site
open_list
if tap_below "HLS" "^More video options$" && tap "^Choose quality$" 10 && tap "^360p$" 10; then
  sleep 2; shot 04-quality-picked 1; report "list after picking 360p"
  mark 05-play-hls-360p
  play_kind HLS 05-play-hls-360p
fi

log "waiting for tokens to expire"
sleep 130
mark 06-play-hls-expired
open_list; play_kind HLS 06-play-hls-expired keep
# The expired master link answers with a web page: the player must say so rather than "malformed".
if find_center "sent a page instead of the video" >/dev/null; then log "expired link explained"; else log "EXPIRED LINK NOT EXPLAINED"; fi
# The refused link offers to reload the page; the browser then plays the video again by itself.
if find_center "^Reload page$" >/dev/null; then
  log "Reload page offered"
  mark 07-reload-and-replay
  tap "^Reload page$" 5
  sleep 25
  shot 07-replayed 1
  report "player after reload and replay"
  if find_center "^Can.t play this video$" >/dev/null; then log "REPLAY FAILED: error shown again"; else log "replay: no error shown"; fi
else
  log "no Reload page button"
fi
leave_player

# Report a site problem: the preview must name the site and never contain a link, token or cookie value.
mark 08-report
if tap "^More options$" 10 && tap "^Report a site problem$" 10; then
  tap "^Won.t play$" 10; tap "^See what will be sent$" 10; sleep 2
  shot 08-report 1
  report "report preview"
  preview="$(ui_text | grep -F '### Site' || true)"
  if [ -z "$preview" ]; then
    log "report preview not found"
  elif echo "$preview" | grep -qE 'https?://|[?&]t=|sess=ok|[0-9]{10}-[0-9a-f]{16}'; then
    log "REPORT LEAK: $preview"
  else
    log "report preview has no link, token or cookie"
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi

log "reload page and retry"
mark 09-reload
reload_site
open_list; shot 09-list-after-reload 3
report "list after reload"

# An "app not responding" of Snapcast during the run: print the main thread's stack (the image allows adb root)
# so the log shows where it was blocked. Other processes' traces (system apps while booting) are skipped.
if adb root >/dev/null 2>&1; then adb wait-for-device; sleep 2; fi
for f in $(adb shell "grep -l 'Cmd line: app.snapcast' /data/anr/* 2>/dev/null" | tr -d '\r' | sed 's#.*/##'); do
  echo "::group::[$LABEL] ANR $f (main thread)"
  adb shell "cat /data/anr/$f" | awk '/^"main"/{p=1} p{print} p&&/^$/{exit}' | head -n 80
  echo "::endgroup::"
  adb shell "rm -f /data/anr/$f" >/dev/null 2>&1 || true
done
exit_info
log "done"
