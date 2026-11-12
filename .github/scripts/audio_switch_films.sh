#!/bin/sh
# Films with two audio languages for the audio switch check (tv-audio-switch.yml), made here with ffmpeg from its own
# test pattern and tones: nothing third-party. English first (the default track), French second.
#   audio_switch_films.sh <folder> <seconds> <WxH> <cases, comma-separated>
# Cases: aac-ac3 (AAC stereo + AC-3 5.1), aac-aac (AAC stereo + AAC stereo), aac-aac51 (AAC stereo + AAC 5.1),
# aac-44k (AAC stereo 48 kHz + AAC stereo 44.1 kHz). Also clip.ts and live.m3u8 (one audio track) for the channels.
set -eu
folder=$1
seconds=$2
size=$3
cases=$4
mkdir -p "$folder"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
for case in $(printf '%s' "$cases" | tr ',' ' '); do
  rate=48000
  case "$case" in
    aac-ac3) second="-c:a:1 ac3 -ac:a:1 6 -b:a:1 384k" ;;
    aac-aac) second="-c:a:1 aac -ac:a:1 2 -b:a:1 128k" ;;
    aac-aac51) second="-c:a:1 aac -ac:a:1 6 -b:a:1 256k" ;;
    aac-44k) second="-c:a:1 aac -ac:a:1 2 -b:a:1 128k"; rate=44100 ;;
    *) echo "unknown case $case" >&2; exit 1 ;;
  esac
  # shellcheck disable=SC2086
  ff -f lavfi -i "testsrc2=size=$size:rate=25" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000" \
    -f lavfi -i "sine=frequency=660:sample_rate=$rate" \
    -t "$seconds" -map 0:v -map 1:a -map 2:a \
    -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p \
    -c:a:0 aac -ac:a:0 2 -b:a:0 128k $second \
    -metadata:s:a:0 language=eng -metadata:s:a:1 language=fra -disposition:a:0 default -disposition:a:1 0 \
    "$folder/$case.mkv"
  ffprobe -v error -show_entries stream=index,codec_name,channels,sample_rate:stream_tags=language -of compact "$folder/$case.mkv"
done
# The channels of the panel (the first walk opens one): one track is enough.
ff -f lavfi -i "testsrc2=size=640x360:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 60 -c:v libx264 -preset veryfast -g 50 -pix_fmt yuv420p -c:a aac -b:a 64k "$folder/channel.mkv"
ff -i "$folder/channel.mkv" -c copy -f mpegts "$folder/clip.ts"
ff -i "$folder/channel.mkv" -c copy -f hls -hls_time 4 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_filename "$folder/seg%03d.ts" "$folder/live.m3u8"
first=$(printf '%s' "$cases" | cut -d, -f1)
cp "$folder/$first.mkv" "$folder/clip.mkv"
ls -la "$folder"
