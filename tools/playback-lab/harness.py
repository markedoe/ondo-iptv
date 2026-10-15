"""Plays a stream with libVLC 3 the way Ondo's VLCBackend does and measures every freeze on screen.

The pictures come out of VLC's real video output, at their display time (vmem callbacks, scaled to 320x180).
VLC shows the last picture again while no new one comes, so a picture counts only when its content changed:
the test stream changes every frame. A freeze is a stretch of at least 300 ms without a new picture, once
the picture started moving. With `trace`, the half-second samples the app's PlaybackHealth works on are kept
(check_verdict.py): libVLC's own position and counters, which stand still while no data comes in even when the
picture goes on. `app_reaction` repeats what PlayerController did on every VLC "playing" or "buffering" event
before it ignored repeated ones: reset the aspect ratio and the crop, read the tracks and the video size.
"""
import ctypes
import json
import sys
import threading
import time
import warnings
import zlib

warnings.filterwarnings("ignore")
import vlc  # noqa: E402

FREEZE = 0.3
W, H = 320, 180


def run(url, options, seconds, label="", instance_options=(), trace=False, app_reaction=False):
    instance = vlc.Instance(["--quiet", "--aout=adummy", "--no-video-title-show", "--stats", *instance_options])
    media = instance.media_new(url)
    for option in options:
        media.add_option(option)
    player = instance.media_player_new()
    player.set_media(media)
    buffer = (ctypes.c_ubyte * (W * H * 4))()
    lock = threading.Lock()
    new_pictures = []
    last_crc = [None]

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
        crc = zlib.crc32(buffer)
        with lock:
            if crc != last_crc[0]:
                last_crc[0] = crc
                new_pictures.append(time.monotonic())

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
        times = [t - started for t in new_pictures] + ([end - started] if state not in (vlc.State.Ended, vlc.State.Stopped) else [])
    # Playback starts with the first two new pictures in a row (VLC shows one picture early while it buffers).
    start_index = next((i for i in range(1, len(times)) if times[i] - times[i - 1] < FREEZE), None)
    freezes = []
    if start_index is not None:
        for a, b in zip(times[start_index:], times[start_index + 1:]):
            if b - a >= FREEZE:
                freezes.append({"at": round(a, 2), "seconds": round(b - a, 2)})
    result = {
        "label": label,
        "options": options,
        "first_picture_seconds": round(times[start_index - 1], 2) if start_index else None,
        "played_seconds": round(player.get_time() / 1000, 1),
        "final_state": str(state),
        "freezes": len(freezes),
        "frozen_seconds": round(sum(f["seconds"] for f in freezes), 2),
        "longest_freeze": max((f["seconds"] for f in freezes), default=0),
        "freeze_list": freezes[:12],
        "new_pictures": len(new_pictures),
        "displayed_pictures": stats.displayed_pictures,
        "lost_pictures": stats.lost_pictures,
        "events": events,
    }
    if trace:
        result["trace"] = rows
    player.stop()
    player.release()
    instance.release()
    return result


if __name__ == "__main__":
    print(json.dumps(run(**json.loads(sys.argv[1]))))
