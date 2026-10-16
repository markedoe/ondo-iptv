#!/bin/sh
# Test streams, generated: nothing is downloaded. Each picture carries its number as a 16-bit barcode across the
# top tenth of the frame (white = 1, lowest bit on the left), which harness.py reads on screen.
#   test720.ts  2 minutes, 720p25 (H.264 3 Mb/s, AAC, MPEG-TS at 3.4 Mb/s), as a typical IPTV channel.
#   heavy.ts    1 minute, 2160p50 (H.264 25 Mb/s): more than one busy core decodes in time (the "heavy" suite).
#   film.*      2 minutes, 1080p25 (H.264 6 Mb/s, a key frame every 10 s as encoders do by default), as a film:
#               film.mp4 with its index at the end, film-faststart.mp4 with it at the start, film.mkv, film.ts.
#   film-tracks.mkv the film with a second audio track (6 channels, 4.6 Mb/s) that is not played, as films carry
#               other languages: VLC receives it and drops it.
set -e
cd "$(dirname "$0")"
mkdir -p media
bar() {
  echo "color=c=black:size=16x1:rate=$3,format=yuv444p,geq=lum='if(bitand(N\,pow(2\,X))\,255\,0)':cb=128:cr=128,scale=$1:$2:flags=neighbor[bar];[0:v][bar]overlay=0:0:shortest=1,format=yuv420p"
}
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -filter_complex "$(bar 1280 72 25)" -t 120 -c:v libx264 -preset veryfast -b:v 3000k -maxrate 3000k -bufsize 3000k -g 50 \
  -c:a aac -b:a 128k -f mpegts -mpegts_flags +resend_headers -muxrate 3400k media/test720.ts
# SKIP_HEAVY=1 leaves the 4K stream out (CI does not run the heavy suite).
if [ -z "$SKIP_HEAVY" ]; then
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=3840x2160:rate=50" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
    -filter_complex "$(bar 3840 216 50)" -t 60 -c:v libx264 -preset superfast -b:v 25000k -maxrate 25000k -bufsize 25000k -g 100 \
    -c:a aac -b:a 128k -f mpegts -mpegts_flags +resend_headers -muxrate 26000k media/heavy.ts
fi
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=25" -f lavfi -i "sine=frequency=330:sample_rate=48000" \
  -filter_complex "$(bar 1920 108 25)" -t 120 -c:v libx264 -preset veryfast -profile:v high -b:v 6000k -maxrate 8000k -bufsize 12000k \
  -g 250 -c:a aac -b:a 160k media/film.mp4
ffmpeg -hide_banner -loglevel error -y -i media/film.mp4 -c copy -movflags +faststart media/film-faststart.mp4
ffmpeg -hide_banner -loglevel error -y -i media/film.mp4 -c copy media/film.mkv
ffmpeg -hide_banner -loglevel error -y -i media/film.mp4 -c copy -f mpegts media/film.ts
ffmpeg -hide_banner -loglevel error -y -i media/film.mp4 -f lavfi -i "sine=frequency=220:sample_rate=48000" -map 0:v -map 0:a -map 1:a \
  -t 120 -c:v copy -c:a:0 copy -c:a:1 pcm_s16le -ac:a:1 6 -disposition:a:0 default -disposition:a:1 0 media/film-tracks.mkv
