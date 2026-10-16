# Playback lab

[Français](README.fr.md)

Why does a stream freeze for a moment and then go on, and is it the network or the player? This lab answers
with measurements: libVLC (the library behind Ondo's VLC engine) plays test streams from a local server that
misbehaves on purpose, and every freeze is counted on screen.

- `faulty_server.py`: a provider that misbehaves on demand. Live channels: real time, in bursts, with stops,
  slower than real time, with or without a head start on connection. Films: a file with byte ranges, with a
  stop, a dropped connection or a slow connection.
- `harness.py`: plays one stream the way `VLCBackend` does. The pictures come out of VLC's real video output at
  their display time (scaled down to 320×180). Each test picture carries its number as a barcode, read on screen:
  a picture counts when its number changed, and after a seek the number says where playback landed. A freeze is
  300 ms or more without a new picture once the picture started.
- `run.py`: plays every scenario of a suite with every setting and prints a table.
- `check_verdict.py`: the app's freeze verdict (`PlaybackHealth` in IPTVCore), ported line for line, run on
  libVLC's counters recorded during a run, against what the screen showed.
- `make_media.sh`: generates the test streams (nothing is downloaded): a channel (720p at 25 frames per second,
  3.4 Mb/s in MPEG-TS), a film (1080p, 6 Mb/s, a key frame every 10 s as encoders do by default) as MP4 with its
  index at the end or at the start, MKV and MPEG-TS, the MKV with a second audio track that is not played (as
  films carry other languages), and a 4K stream too heavy for a busy device.

## Running it

Needs Python 3, ffmpeg and libVLC 3 with its Python binding (Debian or Ubuntu: `vlc-bin vlc-plugin-base
python3-vlc`).

```sh
cd tools/playback-lab
./make_media.sh
python3 faulty_server.py 8765 &
python3 run.py live repeat=2    # then vod, film, resume, zap, reactions, traces, heavy
python3 check_verdict.py results-traces.jsonl results-heavy.jsonl
python3 run.py check            # what CI checks
```

The numbers below come from 45-second runs with libVLC 3.0.20 on Linux, three at a time on four cores (Ondo
ships libVLC 3.0.21 with VLCKit 3.6). The live suite ran twice and gave the same numbers both times. They
compare settings with each other; they are not measurements of a phone or a Mac.

## What the counters say, and what the screen shows

libVLC updates its position and its counters (pictures shown, bytes read) only while data comes in. When a
provider stops sending, they stand still at once, while the picture goes on from VLC's buffer: with a 5-second
stop and 2 seconds of buffer, the counters stopped at 20.0 s and the picture at 22.5 s. When data arrives in
bursts every 2.5 seconds, the counters move in 2.5-second steps over a picture that never stops.

A first version of this lab counted freezes with those counters and found a picture frozen 51 seconds a minute
with bursty providers; on screen there is no freeze at all. The app reads the same counters (VLCKit gives nothing
else), so `PlaybackHealth` treats a still position as a *stall* of the data and estimates the freeze as the part
of the stall the buffer did not cover. The estimate errs on the side of a freeze: VLC may hold more than its
network caching, after it raised its own delay (see below) or with a provider that sent a head start, and then a
stop the app counts as a freeze may not show.

The buffer only covers the part of a stall where no data came in. When the data keeps coming but more slowly than
the video needs, the position stands still while bytes still arrive: the player has run out and refills, and the
picture is frozen for the whole stall (a viewer's report: a 12 Mb/s 4K film sent at 9 Mb/s froze 6 to 8 s at a
time with 5 s of buffer, which the first estimate put at 1 to 3 s).

## Tracks not played, and the picture after a refill

A film often carries several audio tracks (other languages). VLC receives them all and hands its decoders the
tracks it plays only: its count of bytes used (the "used" column of a report) stays below what was received, by
the share of the tracks not played. The film with a second 4.6 Mb/s audio track, on a fast connection: used was 57 %
of received from start to end, without a freeze. It is no sign of data piling up, and the network has to carry
every track: the bitrate a film needs is what was received per second of video.

A viewer's report showed both: a 4K MKV whose file carries 12.4 Mb/s, from a provider sending 8 to 9 Mb/s (used: 66 %
of received). VLC ran out, refilled with its position standing still, then started the picture again at a key
frame while the sound went on: the position moved for 6 and 8 more seconds under the same picture. `PlaybackHealth`
counts these as freezes of the picture alone (pictures shown standing still while the position goes on), with the
cause the data gives them: 31 s frozen in that minute instead of the 17 s the position's stalls showed. The libVLC
used here (Linux, decoding in software) brings the picture back without waiting for a key frame; the report is
replayed in IPTVCore's tests (`PlaybackReportReplayTests`).

## Live channels

Freezes / seconds frozen on screen in 45 seconds, by network caching (the buffer VLC fills before it shows the
picture):

| Provider | 2 s | 3 s | 5 s | 10 s | 2 s + clock-synchro=0 | 5 s + clock-synchro=0 |
|---|---|---|---|---|---|---|
| Real time | 0 | 0 | 0 | 0 | 0 | 0 |
| Bursts every 1 s | 0 | 0 | 0 | 0 | 0 | 0 |
| Bursts every 2.5 s | 0 | 0 | 0 | 0 | 0 | 0 |
| Stops of 1.5 s and 2.5 s | 1 / 1.3 s | 0 | 0 | 0 | 1 / 1.3 s | 0 |
| A 5 s stop | 2 / 3.3 s | 1 or 2 / 2.3 s | 0 | 0 | 2 / 3.3 s | 0 |
| 95 % of the needed rate | 2 / 2.1 s | 0 | 0 | 0 | 2 / 2.1 s | 0 |

With a provider sending 95 % of what the video needs, a larger buffer only delays the freezes: it drains by 5 %
a second.

Seconds until the picture shows:

| Provider | 2 s | 3 s | 4 s | 5 s |
|---|---|---|---|---|
| Real time | 2.9 | 3.9 | 4.9 | 5.9 |
| 8 s head start on connection | 0.9 | 0.9 | 0.9 | 0.9 |

- The buffer covers a stop up to about its own length; a longer stop freezes the picture for the rest.
- With a provider that sends in real time, the picture shows about 0.9 s after the buffer is full; one that sends
  a few seconds ahead on connection (many keep the last seconds of each channel) shows it at once, whatever the
  buffer.
- `clock-synchro=0` and `clock-jitter` change nothing on screen.
- When a stop outlasts the buffer, VLC raises its own delay for the rest of that channel (a 3 s stop after a
  2.5 s one froze 0.8 s instead of 1.3 s).

## Films: stops and dropped connections

| Provider | 3 s | 5 s |
|---|---|---|
| Fast | 0 | 0 |
| A 4 s stop | 0 | 0 |
| Connection dropped at 30 s | 0 | 0 |
| 90 % of the needed rate | 2 / 4.5 s | 0 (within 45 s) |

VLC reads ahead and picks a dropped connection up again on its own (`http-reconnect` is not needed). With 1 s
of buffer too, a 4 s stop and a dropped connection do not show, on a fast connection and on one three times
faster than the film.

## Films: start and seeks

The film in three containers on three networks: fast, distant (each request waits 150 ms, data at three times the
film's bitrate) and slow (300 ms, one and a half times the bitrate). In each cell: seconds until the picture
moves; then, for a skip of +10 s, a skip of −10 s and a jump to 1:30, seconds until it moves again.

| File | Network | 1 s | 3 s | 5 s |
|---|---|---|---|---|
| MP4, index at the end | fast | 0.2; 0.7 / 0.3 / 0.1 | 0.2; 0.6 / 0.3 / 0.1 | 0.2; 0.7 / 0.2 / 0.1 |
| | distant | 1.1; 0.3 / 1.2 / 0.7 | 1.8; 0.3 / 1.9 / 1.4 | 2.4; 0.3 / 2.5 / 2.1 |
| | slow | 2.1; 4.5 / 6.2 / 1.4 | 3.4; 4.8 / 0.5 / 2.7 | 4.7; 4.9 / – / 4.2 |
| MP4, index at the start | fast | 0.1; 0.6 / 0.2 / 0.1 | 0.1; 0.7 / 0.3 / 0.1 | 0.2; 0.7 / 0.3 / 0.1 |
| | distant | 0.7; 0.3 / 1.2 / 0.7 | 1.4; 0.3 / 1.9 / 1.4 | 2.4; – |
| | slow | 1.4; 4.5 / 6.2 / 1.4 | 2.7; 4.8 / 0.5 / 2.7 | 4.0; 4.9 / – / 4.2 |
| MKV | fast | 0.1; 0.3 / 0.2 / 0.1 | 0.2; 0.3 / 0.2 / 0.1 | 0.1; 0.4 / 0.2 / 0.1 |
| | distant | 2.4; 0.4 / 1.3 / 0.7 | 2.5; 0.4 / 2.0 / 1.3 | 2.5; 0.4 / 2.6 / 2.0 |
| | slow | 4.7; 2.2 / – / 2.1 | 4.8; 3.6 / – / 2.7 | 4.7; 5.0 / – / 4.1 |

"–": the harness could not tell where that seek landed. Every seek it saw landed on the target (within 0.04 s).

- The buffer is waited for at the start and after every seek that leaves what VLC already read: on anything but
  a fast connection, each second of buffer is a second more to wait. A skip forward into what is already read
  (+10 s on the distant network) costs 0.3 s whatever the buffer.
- An MKV film on a distant or slow server takes 2.4 to 4.8 s to start whatever the buffer: VLC first reads the
  file's index at its end, a few round trips away.
- On the slow network a precise seek needs the data from the key frame before the target (here up to 10 s of
  film): 4.5 to 6 s.
- VLC's fast seek (`input-fast-seek`) jumps to a key frame: 1.3 s on the slow network, but it lands up to 7 s
  before the target, so a +10 s skip moves 3 to 5 s. It stays off.
- Films in MPEG-TS seek poorly in VLC: 2 to 4 s even on a fast connection, landing 5 s before the target, and
  later seeks were not seen landing at all. MKV and MP4 seek precisely.

## Films: resume

The app resumes a film with VLC's `start-time`. Seconds until the picture moves at 1:00:

| File | Network | 1 s | 3 s |
|---|---|---|---|
| MP4, index at the end | fast / distant / slow | 0.4 / 1.4 / 2.8 | 0.4 / 2.1 / 4.1 |
| MP4, index at the start | fast / distant / slow | 0.2 / 1.1 / 2.6 | 0.2 / 2.0 / 4.0 |
| MKV | fast / distant / slow | 0.5 / 3.0 / 5.7 | 0.5 / 3.6 / 7.3 |

Each resumed at 1:00 exactly. VLC ignores `start-time` on MPEG-TS and starts at 0:00; the app then seeks.

## A device that does not keep up

The 4K stream on half of one core: 28 % of the pictures lost and three short freezes, while the data arrived on
time. A player short of data loses pictures too: the MKV with a second audio track, sent at 70 % of its rate, lost
4 %. Lost pictures point at the device only when the data kept up, and the verdict looks at the pace first.

## VLC's events

VLC posts a "buffering" event about twice a second while it plays (103 in 45 seconds), even when nothing is
wrong. Ondo used to react to each one (aspect ratio, crop, tracks, size). No freeze came from it in the lab, but
it was work for nothing on the main thread: Ondo now reacts to real changes only.

## The verdict, checked

`check_verdict.py` on the recorded counters (`traces` and `heavy` suites):

| Recording | On screen | Verdict |
|---|---|---|
| Live, real time, 2 s | no freeze | smooth |
| Live, bursts every 2.5 s, 2 s | no freeze | smooth |
| Live, a 5 s stop, 2 s | 3.3 s frozen | network |
| Live, a 5 s stop, 5 s | no freeze | smooth |
| Live, 95 % of the rate, 2 s | 2.1 s frozen | network |
| Film, 90 % of the rate, 3 s | 4.5 s frozen | network |
| Film, fast, 3 s | no freeze | smooth |
| Film MKV with a second audio track, 70 % of the rate, 1 s | 17.2 s frozen, 4 % lost | network |
| Live 4K, busy device | 28 % lost | player |
| Film 4K, busy device | 27 % lost | player |

## In CI

`run.py check` plays thirteen cases with the defaults Ondo ships and fails when one passes its limit: stops a live
buffer must cover, bursts that must never freeze, how soon a live channel and a film show, where and how fast
seeks land, a resume at 1:00, a stop and a dropped connection during a film, and the freeze verdict on recorded
counters. The limits leave room for shared machines. It runs on every change to the lab, the playback core or
`VLCBackend` (`.github/workflows/playback-lab.yml`). The app's own CI also plays Apple's public sample stream with
both engines and checks the start and seek times the player measures (`AppTests/PlaybackTimingTests.swift`).

## What Ondo does with this

- Live channels start with a 3-second buffer (2 before): stops up to about 2.5 s no longer show, for one second
  more before the picture of a real-time provider. Once a stop used up the buffer, the next channels get 6
  seconds (`BufferPolicy`).
- Films start with 1 second (3 before): on a distant server a film starts 0.7 s sooner, resumes 0.7 s sooner and
  a skip back takes 1.2 s instead of 1.9 s. Once a stop used up the buffer (a connection too slow to read ahead),
  the next films get 5 seconds.
- Settings, Buffer for live TV and Buffer for movies & series, replace the automatic choice by a fixed length.
- More, Report a Problem… shows a report of the playback to share: the start and seek times as the viewer waited
  for them, the freezes, the statistics and the events, with no address, login or title.
- More, Stream Statistics shows the engine, the video size, the video's bitrate, what arrives, the smoothness,
  the freezes, the lost pictures and the verdict. The spinner shows when a stall outlasts the buffer, and a live
  channel that received nothing for 12 seconds is reconnected.
