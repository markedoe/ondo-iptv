# Playback lab

[Français](README.fr.md)

Why does a stream freeze for a moment and then go on, and is it the network or the player? This lab answers
with measurements: libVLC (the library behind Ondo's VLC engine) plays test streams from a local server that
misbehaves on purpose, and every freeze is counted on screen.

- `faulty_server.py`: a provider that misbehaves on demand. Live channels: real time, in bursts, with stops,
  slower than real time, with or without a head start on connection. Films: a file with byte ranges, with a
  stop, a dropped connection or a slow connection.
- `harness.py`: plays one stream the way `VLCBackend` does. The pictures come out of VLC's real video output at
  their display time (scaled down to 320×180) and a picture counts when its content changed. A freeze is 300 ms
  or more without a new picture once the picture started.
- `run.py`: plays every scenario of a suite with every setting and prints a table.
- `check_verdict.py`: the app's freeze verdict (`PlaybackHealth` in IPTVCore), ported line for line, run on
  libVLC's counters recorded during a run, against what the screen showed.
- `make_media.sh`: generates the test streams (nothing is downloaded): 720p at 25 frames per second, 3.4 Mb/s
  in MPEG-TS like a typical channel, and a 4K stream too heavy for a busy device.

## Running it

Needs Python 3, ffmpeg and libVLC 3 with its Python binding (Debian or Ubuntu: `vlc-bin vlc-plugin-base
python3-vlc`).

```sh
cd tools/playback-lab
./make_media.sh
python3 faulty_server.py 8765 &
python3 run.py live repeat=2    # then vod, zap, reactions, traces, heavy
python3 check_verdict.py results-traces.jsonl results-heavy.jsonl
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
of the stall the buffer did not cover.

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

## Films

| Provider | 3 s | 5 s |
|---|---|---|
| Fast | 0 | 0 |
| A 4 s stop | 0 | 0 |
| Connection dropped at 30 s | 0 | 0 |
| 90 % of the needed rate | 2 / 4.5 s | 0 (within 45 s) |

VLC reads ahead and picks a dropped connection up again on its own (`http-reconnect` is not needed). On a fast
connection the picture shows in 0.9 s whatever the buffer; on a slow one, in 4.1 s with 3 s and 6.3 s with 5 s.

## A device that does not keep up

The 4K stream on half of one core: 28 % of the pictures lost and three short freezes, while the data arrived on
time. Lost pictures are the sign: in every network case, none were lost.

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
| Live 4K, busy device | 28 % lost | player |
| Film 4K, busy device | 27 % lost | player |

## What Ondo does with this

- Live channels start with a 3-second buffer (2 before): stops up to about 2.5 s no longer show, for one second
  more before the picture of a real-time provider. Once a stop used up the buffer, the next channels get 6
  seconds (`LiveBufferPolicy`).
- Films get 5 seconds (3 before): nothing more to wait on a fast connection, fewer freezes on a slow one.
- More, Stream Statistics shows the engine, the video size, the video's bitrate, what arrives, the smoothness,
  the freezes, the lost pictures and the verdict. The spinner shows when a stall outlasts the buffer, and a live
  channel that received nothing for 12 seconds is reconnected.
