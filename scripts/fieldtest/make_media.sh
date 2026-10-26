#!/usr/bin/env bash
# Generates the field-test media: a 3-rendition HLS stream whose frames show their own resolution,
# a hotlink-protected MP4 trailer, a poster image and a local copy of hls.js.
set -euo pipefail
OUT="$1"
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
mkdir -p "$OUT/hls"

label() { # width height text
  echo "drawtext=fontfile=$FONT:text='$3':fontsize=$(( $2 / 5 )):fontcolor=white:box=1:boxcolor=black@0.6:x=(w-text_w)/2:y=(h-text_h)/2"
}

for spec in "640 360" "1280 720" "1920 1080"; do
  set -- $spec
  w=$1; h=$2; dir="$OUT/hls/${h}p"; mkdir -p "$dir"
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${w}x${h}:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=44100" \
    -t 24 -vf "$(label $w $h "${h}p")" -c:v libx264 -preset veryfast -profile:v high -pix_fmt yuv420p \
    -g 50 -keyint_min 50 -sc_threshold 0 -c:a aac -b:a 96k \
    -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_filename "$dir/seg%02d.ts" "$dir/index.m3u8"
done

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25" -f lavfi -i "sine=frequency=660:sample_rate=44100" \
  -t 12 -vf "$(label 640 360 'TRAILER 360p')" -c:v libx264 -preset veryfast -pix_fmt yuv420p \
  -c:a aac -b:a 96k -movflags +faststart "$OUT/trailer.mp4"

ffmpeg -hide_banner -loglevel error -y -f lavfi -i "smptebars=size=640x360" -frames:v 1 \
  -vf "$(label 640 360 'POSTER')" "$OUT/poster.jpg"

curl -fsSL -o "$OUT/hls.min.js" https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js
ls -R "$OUT" | head -40

# Coverage extras: a 2-second "ad" clip, a DASH version of the 720p rendition, and dash.js.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25" -f lavfi -i "sine=frequency=880:sample_rate=44100" \
  -t 2 -vf "$(label 640 360 'AD')" -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 64k \
  -movflags +faststart "$OUT/ad.mp4"
mkdir -p "$OUT/dash"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=44100" \
  -t 24 -vf "$(label 1280 720 'DASH 720p')" -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 -keyint_min 50 \
  -sc_threshold 0 -c:a aac -b:a 96k -f dash -seg_duration 4 -use_template 1 -use_timeline 0 \
  "$OUT/dash/manifest.mpd"
curl -fsSL -o "$OUT/dash.all.min.js" https://cdn.jsdelivr.net/npm/dashjs@4.7.4/dist/dash.all.min.js
