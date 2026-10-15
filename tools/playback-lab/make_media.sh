#!/bin/sh
# Test streams, generated: nothing is downloaded.
#   test720.ts  2 minutes, 720p25 (H.264 3 Mb/s, AAC, MPEG-TS at 3.4 Mb/s), as a typical IPTV channel.
#   heavy.ts    1 minute, 2160p50 (H.264 25 Mb/s): more than one busy core decodes in time (the "heavy" suite).
set -e
cd "$(dirname "$0")"
mkdir -p media
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 120 -c:v libx264 -preset veryfast -b:v 3000k -maxrate 3000k -bufsize 3000k -g 50 -pix_fmt yuv420p \
  -c:a aac -b:a 128k -f mpegts -mpegts_flags +resend_headers -muxrate 3400k media/test720.ts
ffmpeg -hide_banner -loglevel error -y -i media/test720.ts -c copy media/test720.mkv
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=3840x2160:rate=50" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 60 -c:v libx264 -preset superfast -b:v 25000k -maxrate 25000k -bufsize 25000k -g 100 -pix_fmt yuv420p \
  -c:a aac -b:a 128k -f mpegts -mpegts_flags +resend_headers -muxrate 26000k media/heavy.ts
