"""tv_drive.py's way through the TV player's buttons, against a made-up player with the app's timing: the controls go
5 s after the last key while the video plays (Right then skips, OK pauses), and a screen dump can take seconds.

Run: python3 -m unittest discover -s .github/scripts/tests
"""
import importlib.util
import time as real_time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("tv_drive", SCRIPTS / "tv_drive.py")
tv_drive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tv_drive)


class Clock:
    """Stands in for the time module inside tv_drive: sleeping moves the made-up clock on."""

    def __init__(self):
        self.now = 1_000.0

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds

    @staticmethod
    def strftime(fmt):
        return real_time.strftime(fmt)


class Player:
    """The live player's controls as PlayerControls and RemoteKeys make them behave on a TV."""

    TIMEOUT = 5.0
    PANELS = {
        "Channels": ["Channel 12"],
        "Subtitles": ["Off", "Track 1"],
        "Audio": ["Track 1", "Mono · AAC"],
        "More": ["Aspect ratio", "Report a Problem…"],
    }

    def __init__(self, clock, playing=True):
        self.clock = clock
        self.playing = playing
        self.shown = False
        self.focus = 0
        self.panel = None
        self.touched = 0.0
        self.skips = 0
        self.toggles = 0
        self.opened = []
        self.captions = True
        # Back with the controls hidden leaves the player.
        self.left = False
        # Shows from Info that come while the stream opens again: the skip buttons are not there.
        self.reloads = 0
        self.transport = True

    def row(self):
        play = "Pause" if self.playing else "Play"
        if not self.transport:
            return [play, "Channels", "Subtitles", "Audio", "More"]
        return ["Back 10 seconds", play, "Forward 10 seconds", "Channels", "Subtitles", "Audio", "More"]

    def settle(self):
        if self.shown and self.panel is None and self.playing and self.clock.now - self.touched >= self.TIMEOUT:
            self.hide()

    def hide(self):
        self.shown = False
        self.panel = None
        self.transport = True

    def show(self):
        self.shown = True
        self.panel = None
        self.focus = self.row().index("Pause" if self.playing else "Play")
        self.touched = self.clock.now

    def press(self, code):
        self.settle()
        if code == "KEYCODE_CAPTIONS" and self.captions:
            # The remote's subtitles key: the controls with the Subtitles panel, which keeps them.
            if not self.shown:
                self.show()
            self.panel = "Subtitles"
            return
        if code == "KEYCODE_INFO":
            if self.shown:
                self.hide()
            else:
                if self.reloads:
                    self.reloads -= 1
                    self.transport = False
                self.show()
            return
        if not self.shown:
            if code in ("KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_LEFT"):
                self.skips += 1
            elif code == "KEYCODE_DPAD_CENTER":
                self.toggles += 1
                self.playing = not self.playing
            elif code == "KEYCODE_BACK":
                self.left = True
            return
        if code == "KEYCODE_CAPTIONS":
            return
        self.touched = self.clock.now
        if self.panel is not None:
            if code == "KEYCODE_BACK":
                self.panel = None
            return
        if code == "KEYCODE_DPAD_RIGHT":
            self.focus = min(self.focus + 1, len(self.row()) - 1)
        elif code == "KEYCODE_DPAD_LEFT":
            self.focus = max(self.focus - 1, 0)
        elif code == "KEYCODE_DPAD_CENTER":
            label = self.row()[self.focus]
            if label in self.PANELS:
                self.panel = label
                self.opened.append(label)
            else:
                self.toggles += 1
                self.playing = not self.playing
        elif code == "KEYCODE_BACK":
            self.hide()

    def dump(self):
        self.settle()
        nodes = ['<node text="12  FR: Channel 12" content-desc="" focusable="false" focused="false" bounds="[48,40][900,90]"/>']
        if self.shown:
            for i, label in enumerate(self.row()):
                focused = "true" if self.panel is None and i == self.focus else "false"
                x = 100 + i * 120
                nodes.append(f'<node text="" content-desc={quoteattr(label)} focusable="true" focused="{focused}" bounds="[{x},900][{x + 80},980]"/>')
            if self.panel is not None:
                nodes.append(f'<node text={quoteattr(self.panel)} content-desc="" focusable="false" focused="false" bounds="[1400,80][1880,130]"/>')
                for i, label in enumerate(self.PANELS[self.panel]):
                    focused = "true" if i == 0 else "false"
                    y = 160 + i * 80
                    nodes.append(f'<node text={quoteattr(label)} content-desc="" focusable="true" focused="{focused}" bounds="[1400,{y}][1880,{y + 60}]"/>')
        return f'<?xml version="1.0"?><hierarchy><node text="" content-desc="" focusable="false" bounds="[0,0][1920,1080]">{"".join(nodes)}</node></hierarchy>'


class FakeTv(tv_drive.Tv):
    def __init__(self, clock, player, dump_seconds, input_seconds=0.5):
        self.clock = clock
        self.player = player
        self.dump_seconds = dump_seconds
        self.input_seconds = input_seconds
        self.step = 0
        self.pid = None
        self.last_key = 0.0

    def say(self, text):
        pass

    def shell(self, command, timeout=60):
        if command.startswith("input keyevent"):
            # The `input` command starts, then its keys go in.
            self.clock.sleep(self.input_seconds)
            for code in command.split()[2:]:
                self.player.press(code)
        return ""

    def screen(self):
        self.clock.sleep(self.dump_seconds)
        return ET.fromstring(self.player.dump())

    def screenshot(self, name):
        pass

    def check_alive(self, where):
        return None

    def note_screen(self, name):
        self.screen()


class PlayerPanelTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.saved_time = tv_drive.time
        tv_drive.time = self.clock

    def tearDown(self):
        tv_drive.time = self.saved_time

    def drive(self, player, dump_seconds):
        tv = FakeTv(self.clock, player, dump_seconds)
        tv_drive.player_panel(tv, "Subtitles", "live-subtitles", expect="Off")
        tv_drive.player_panel(tv, "Audio", "live-audio")
        tv_drive.player_panel(tv, "More", "player-menu", close=False)
        self.assertFalse(player.left, "Back with the controls hidden: the player closed")
        return tv

    def test_slow_screen_dumps_reach_more_without_a_skip(self):
        # Dumps of 4.5 s: a key after a dump comes after the controls went (the failed release check).
        player = Player(self.clock)
        player.show()
        self.drive(player, dump_seconds=4.5)
        self.assertEqual(["Subtitles", "Audio", "More"], player.opened)
        self.assertEqual("More", player.panel)
        self.assertEqual(0, player.skips, "Right reached the hidden controls: the video skipped")
        self.assertEqual(0, player.toggles, "OK reached the hidden controls or Play/Pause: the video paused")
        self.assertFalse(player.left, "Back with the controls hidden: the player closed")

    def test_fast_screen_dumps(self):
        player = Player(self.clock)
        self.drive(player, dump_seconds=1.0)
        self.assertEqual(["Subtitles", "Audio", "More"], player.opened)
        self.assertEqual((0, 0), (player.skips, player.toggles))

    def test_paused_video_keeps_its_controls(self):
        # Paused, the controls never go by themselves: Info hides them.
        player = Player(self.clock, playing=False)
        player.show()
        player.focus = 5
        self.drive(player, dump_seconds=3.0)
        self.assertEqual(["Subtitles", "Audio", "More"], player.opened)
        self.assertEqual((0, 0), (player.skips, player.toggles))

    def test_without_the_subtitles_key(self):
        # A remote with no subtitles key: the row is read as Info shows it.
        player = Player(self.clock)
        player.captions = False
        self.drive(player, dump_seconds=1.0)
        self.assertEqual(["Subtitles", "Audio", "More"], player.opened)
        self.assertEqual((0, 0), (player.skips, player.toggles))

    def test_a_row_changed_by_a_reload(self):
        # The stream opens again as the controls show: no skip buttons, OK lands on another button; its panel closes and
        # the way is read again.
        player = Player(self.clock)
        player.reloads = 1
        self.drive(player, dump_seconds=2.0)
        self.assertEqual("Audio", player.opened[0], "the first OK landed one button off")
        self.assertEqual(["Subtitles", "Audio", "More"], player.opened[-3:])
        self.assertEqual((0, 0), (player.skips, player.toggles))

    def test_steps_from_play_pause(self):
        row = [["0:34"], ["Back 10 seconds"], ["Pause"], ["Forward 10 seconds"], ["Channels"], ["Subtitles"], ["Audio"], ["More"]]
        self.assertEqual(5, tv_drive.player_steps(row, "More"))
        self.assertEqual(3, tv_drive.player_steps(row, "Subtitles"))
        self.assertIsNone(tv_drive.player_steps(row, "Speed"))
        self.assertIsNone(tv_drive.player_steps(None, "More"))


if __name__ == "__main__":
    unittest.main()
