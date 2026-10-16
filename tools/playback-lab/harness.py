"""Plays a stream with libVLC 3 the way Ondo's VLCBackend does and measures what the screen shows.

The pictures come out of VLC's real video output, at their display time (vmem callbacks, scaled to 320x180).
Each test picture carries its number as a barcode across the top tenth of the frame (make_media.sh), read here
on screen: VLC shows the last picture again while no new one comes, so a picture counts when its number
changed, and after a seek the number says where playback landed.

- A freeze is a stretch of at least 300 ms without a new picture, once the picture started moving.
- `seeks`: [[seconds after the picture started moving, target]], target in seconds of media, or "+10"/"-10"
  from the picture on screen. For each: how long until the picture moved again, and how far from the target
  it landed.
- `trace` keeps the half-second samples the app's PlaybackHealth works on (check_verdict.py): libVLC's own
  position and counters, which stand still while no data comes in even when the picture goes on.
- `app_reaction` repeats what PlayerController did on every VLC "playing" or "buffering" event before it
  ignored repeated ones: reset the aspect ratio and the crop, read the tracks and the video size.
"""
import ctypes
import json
import sys
import threading
import time
import warnings

warnings.filterwarnings("ignore")
import vlc  # noqa: E402

FREEZE = 0.3
W, H = 320, 180
BITS = 16


def frame_number(buffer):
    """The barcode on the picture: 16 cells across the top, white = 1, lowest bit on the left."""
    number = 0
    for bit in range(BITS):
        if buffer[(9 * W + bit * (W // BITS) + W // BITS // 2) * 4 + 1] > 128:
            number |= 1 << bit
    return number


def run(url, options, seconds, label="", instance_options=(), trace=False, app_reaction=False, seeks=(), fps=25):
    instance = vlc.Instance(["--quiet", "--aout=adummy", "--no-video-title-show", "--stats", *instance_options])
    media = instance.media_new(url)
    for option in options:
        media.add_option(option)
    player = instance.media_player_new()
    player.set_media(media)
    buffer = (ctypes.c_ubyte * (W * H * 4))()
    lock = threading.Lock()
    pictures = []  # (monotonic time, frame number) of each new picture
    last = [None]

    @vlc.CallbackDecorators.VideoLockCb
    def on_lock(opaque, planes):
        planes[0] = ctypes.cast(buffer, ctypes.c_void_p)
        return None

    @vlc.CallbackDecorators.VideoUnlockCb
    def on_unlock(opaque, picture, planes):
        pass

    @vlc.CallbackDecorators.VideoDisplayCb
    def on_display(opaque, picture):
        # No libVLC call here: the video output thread holds locks the player needs.
        number = frame_number(buffer)
        with lock:
            if number != last[0]:
                last[0] = number
                pictures.append((time.monotonic(), number))

    events = {"buffering": 0, "playing": 0, "reactions": 0}
    pending = [0]

    def on_event(kind):
        def handler(event):
            with lock:
                events[kind] += 1
                pending[0] += 1
        return handler

    manager = player.event_manager()
    manager.event_attach(vlc.EventType.MediaPlayerBuffering, on_event("buffering"))
    manager.event_attach(vlc.EventType.MediaPlayerPlaying, on_event("playing"))
    player.video_set_callbacks(on_lock, on_unlock, on_display, None)
    player.video_set_format("RV32", W, H, W * 4)
    stats = vlc.MediaStats()
    started = time.monotonic()
    player.play()
    rows = []
    next_sample = 0.5
    state = None
    moving_since = None
    pending_seeks = sorted(seeks, key=lambda s: s[0])
    seek_calls = []  # (monotonic time, target seconds, frame number on screen when asked)
    while time.monotonic() - started < seconds:
        time.sleep(0.02)
        if app_reaction:
            # The main thread of the app handled the events one by one.
            with lock:
                todo, pending[0] = pending[0], 0
            for _ in range(todo):
                events["reactions"] += 1
                player.video_set_aspect_ratio(None)
                player.video_set_crop_geometry(None)
                player.audio_get_track_description()
                player.video_get_spu_description()
                player.video_get_size(0)
        now = time.monotonic()
        with lock:
            shown = list(pictures[-2:])
        if moving_since is None and len(shown) == 2 and shown[1][0] - shown[0][0] < FREEZE:
            moving_since = shown[0][0]
        if moving_since is not None and pending_seeks and now - moving_since >= pending_seeks[0][0]:
            _, target = pending_seeks.pop(0)
            current = shown[-1][1] / fps
            if isinstance(target, str):
                target = current + float(target)
            target = max(target, 0)
            player.set_time(int(target * 1000))
            seek_calls.append((time.monotonic(), target, shown[-1][1]))
        state = player.get_state()
        if now - started >= next_sample:
            next_sample += 0.5
            media.get_stats(stats)
            rows.append([round(now - started, 2), player.get_time() / 1000 if player.is_playing() else None,
                         stats.read_bytes, stats.demux_read_bytes, stats.displayed_pictures, stats.lost_pictures,
                         int(player.is_playing()), str(state).split(".")[-1]])
        if state in (vlc.State.Ended, vlc.State.Error, vlc.State.Stopped):
            break
    end = time.monotonic()
    media.get_stats(stats)
    with lock:
        shown = list(pictures)
    times = [t - started for t, _ in shown]
    if state not in (vlc.State.Ended, vlc.State.Stopped):
        times.append(end - started)
    # Playback starts with the first two new pictures in a row (VLC shows one picture early while it buffers).
    start_index = next((i for i in range(1, len(times)) if times[i] - times[i - 1] < FREEZE), None)
    seek_marks = [c - started for c, _, _ in seek_calls]
    freezes = []
    if start_index is not None:
        for a, b in zip(times[start_index:], times[start_index + 1:]):
            # The wait after a seek is measured below, not counted as a freeze.
            if b - a >= FREEZE and not any(a - 0.1 <= m <= b for m in seek_marks):
                freezes.append({"at": round(a, 2), "seconds": round(b - a, 2)})
    result = {
        "label": label,
        "options": options,
        "first_picture_seconds": round(times[start_index - 1], 2) if start_index else None,
        "first_frame_number": shown[start_index - 1][1] if start_index else None,
        "played_seconds": round(player.get_time() / 1000, 1),
        "final_state": str(state),
        "freezes": len(freezes),
        "frozen_seconds": round(sum(f["seconds"] for f in freezes), 2),
        "longest_freeze": max((f["seconds"] for f in freezes), default=0),
        "freeze_list": freezes[:12],
        "new_pictures": len(shown),
        "displayed_pictures": stats.displayed_pictures,
        "lost_pictures": stats.lost_pictures,
        "events": events,
        "seeks": [measure_seek(shown, call, target, before, fps) for call, target, before in seek_calls],
    }
    if trace:
        result["trace"] = rows
    player.stop()
    player.release()
    instance.release()
    return result


def measure_seek(shown, call, target, before, fps):
    """The first picture after the call that does not follow on from the one before, away from where it was,
    and between the key frame before the target and just after it, is where the seek landed; the seek is done
    when the picture moves on from there."""
    previous = before
    for i, (t, number) in enumerate(shown):
        if t <= call:
            continue
        jumped = not 0 <= number - previous <= 3
        left = abs(number - before) / fps > 1.5 or abs(target - before / fps) <= 1.5
        if jumped and left and target - 10.5 <= number / fps <= target + 2:
            moving = i + 1 < len(shown) and shown[i + 1][0] - t < FREEZE
            return {"target": round(target, 2), "landed": round(number / fps, 2),
                    "landing_error": round(number / fps - target, 2), "seconds": round(t - call, 2),
                    "moving": moving}
        previous = number
    return {"target": round(target, 2), "landed": None, "seconds": None}


if __name__ == "__main__":
    print(json.dumps(run(**json.loads(sys.argv[1]))))
