#!/usr/bin/env python3
"""Drives the installed release build of Ondo on an Android TV emulator with remote keys, as a viewer would:
first launch, Add a Playlist, Xtream Codes (the made-up panel of fake_xtream_panel.py, reached through
`adb reverse`), Add, then every tab. Watches the app's process the whole time: a crash, an ANR or Android ending the
app fails the run. Screenshots, the screen's text, logcat, the exit reasons and memory use go to the output folder.
With --paths, more of the app after that, each path from a fresh start of the app (the saved playlist read again):
settings, search, details, player, guide, mylist, playlists, restore, browse, sync, crash (see PATHS).

Usage: tv_drive.py <output folder> [--server http://localhost:8080] [--wait-load 240] [--paths settings,search,...]
"""
import argparse
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.markedoe.ondo"
ACTIVITY = f"{PACKAGE}/.MainActivity"


class Stopped(Exception):
    pass


class Tv:
    def __init__(self, out):
        self.out = out
        self.step = 0
        self.pid = None
        self.log = open(os.path.join(out, "steps.log"), "a", buffering=1)

    def say(self, text):
        line = f"{time.strftime('%H:%M:%S')} {text}"
        print(line, flush=True)
        self.log.write(line + "\n")

    def adb(self, *args, check=False, timeout=60):
        result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {result.stderr.decode(errors='replace')}")
        return result.stdout.decode(errors="replace")

    def shell(self, command, timeout=60):
        return self.adb("shell", command, timeout=timeout)

    def current_pid(self):
        return self.shell(f"pidof {PACKAGE}").strip() or None

    def check_alive(self, where):
        pid = self.current_pid()
        crash = self.adb("logcat", "-d", "-b", "crash")
        if PACKAGE in crash:
            raise Stopped(f"{where}: crash buffer:\n{crash[-4000:]}")
        anr = self.adb("logcat", "-d", "-s", "ActivityManager:E", timeout=120)
        if re.search(r"ANR in " + re.escape(PACKAGE), anr):
            raise Stopped(f"{where}: ANR:\n{anr[-4000:]}")
        if self.pid and pid != self.pid:
            raise Stopped(f"{where}: the app's process {self.pid} is gone (now {pid})")
        return pid

    def screenshot(self, name):
        self.step += 1
        path = os.path.join(self.out, f"{self.step:03d}-{name}.png")
        with open(path, "wb") as f:
            f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)

    def screen(self):
        """The window's nodes (uiautomator); None when the screen never settles (an animation)."""
        for _ in range(3):
            self.shell("rm -f /sdcard/ui.xml")
            out = self.shell("uiautomator dump /sdcard/ui.xml", timeout=60)
            text = self.adb("exec-out", "cat", "/sdcard/ui.xml")
            if text.startswith("<?xml"):
                try:
                    return ET.fromstring(text)
                except ET.ParseError:
                    pass
            time.sleep(1)
        self.say(f"no screen dump: {out.strip()}")
        return None

    @staticmethod
    def texts(node):
        found = []
        for n in node.iter("node"):
            for key in ("text", "content-desc"):
                value = n.get(key)
                if value:
                    found.append(value)
        return found

    def focused(self, root=None):
        root = root if root is not None else self.screen()
        if root is None:
            return None, []
        nodes = [n for n in root.iter("node") if n.get("focused") == "true"]
        if not nodes:
            return None, []
        node = nodes[-1]
        return node, self.texts(node)

    def visible(self, *words):
        root = self.screen()
        if root is None:
            return False
        all_text = " | ".join(self.texts(root))
        return all(w in all_text for w in words)

    def key(self, name, times=1, pause=0.4):
        code = f"DPAD_{name}" if name in ("UP", "DOWN", "LEFT", "RIGHT") else name
        for _ in range(times):
            self.shell(f"input keyevent KEYCODE_{code}")
            time.sleep(pause)

    def wait_for(self, what, condition, timeout, every=2.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.check_alive(f"waiting for {what}")
            if condition():
                return True
            time.sleep(every)
        self.screenshot(f"timeout-{what.replace(' ', '-')}")
        raise TimeoutError(f"{what} never came in {timeout} s")

    def focus_on_field(self, index, max_presses=14):
        """Presses Down until the focused element is the form's text field number [index] (from 0, top to bottom)."""
        for _ in range(max_presses + 1):
            root = self.screen()
            if root is not None:
                fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
                focused = [i for i, n in enumerate(fields) if n.get("focused") == "true"]
                if focused and focused[-1] == index:
                    return fields[index]
                # Injected arrows stay in a text field (Compose moves out of one for a physical remote only): Tab
                # goes on to the next field, as Next on the TV's keyboard does.
                self.key("TAB" if focused else "DOWN")
                continue
            self.key("DOWN")
        self.screenshot(f"never-focused-field-{index}")
        raise TimeoutError(f"the focus never reached text field {index}")

    def focus_on(self, label, keys=("DOWN",), max_presses=14, editable=None):
        """Presses [keys] in turn until the focused element shows [label]."""
        for i in range(max_presses + 1):
            node, texts = self.focused()
            joined = " | ".join(texts)
            if node is not None and label in joined and (editable is None or (node.get("class") == "android.widget.EditText") == editable):
                return node
            self.key(keys[i % len(keys)])
        self.screenshot(f"never-focused-{label}")
        raise TimeoutError(f"the focus never reached '{label}' (on: {joined})")

    # The paths after the first walk (--paths).

    def note_screen(self, name):
        """A screenshot, and the screen's words and the focused element in the steps log (to find the way next time)."""
        self.screenshot(name)
        root = self.screen()
        if root is None:
            return
        words = " | ".join(dict.fromkeys(self.texts(root)))
        _, focused = self.focused(root)
        self.say(f"  [{name}] focus: {' | '.join(focused)[:120]!r}; screen: {words[:700]!r}")

    def focus_exact(self, label, keys=("DOWN",), max_presses=14):
        """Presses [keys] in turn until the focused element has a text (or description) that is exactly [label]."""
        joined = ""
        for i in range(max_presses + 1):
            node, texts = self.focused()
            joined = " | ".join(texts)
            if node is not None and label in texts:
                return node
            self.key(keys[i % len(keys)])
        self.screenshot(f"never-focused-{label}")
        raise TimeoutError(f"the focus never reached '{label}' (on: {joined})")

    def open(self, label, keys=("DOWN",), max_presses=14, exact=False, wait=3.0):
        """Moves to [label] and presses OK."""
        if exact:
            self.focus_exact(label, keys, max_presses)
        else:
            self.focus_on(label, keys, max_presses)
        self.key("DPAD_CENTER")
        time.sleep(wait)
        self.check_alive(f"{label} pressed")

    def restart(self):
        """Ondo stopped and started again: Home with the saved playlist, read back from the device's storage."""
        self.shell(f"am force-stop {PACKAGE}")
        time.sleep(1)
        self.shell(f"am start -W -n {ACTIVITY}")
        self.pid = self.current_pid()
        self.say(f"restarted, pid {self.pid}")
        self.wait_for("home after a restart", lambda: self.visible("Live TV") and not self.visible("Try the demo"), 60)
        time.sleep(3)
        self.check_alive("home after a restart")

    @staticmethod
    def left_edge(node):
        """The node's left edge in pixels (bounds="[x1,y1][x2,y2]")."""
        found = re.match(r"\[(-?\d+),", node.get("bounds", "")) if node is not None else None
        return int(found.group(1)) if found else 10_000

    def in_drawer(self):
        """The focus on one of the drawer's places: its words, at the screen's left edge (a page's chips have the same words)."""
        node, texts = self.focused()
        return node is not None and any(t in DRAWER for t in texts) and self.left_edge(node) < 260

    def to_drawer(self):
        """The focus on the drawer: Back from a page lands on its tab (never Back from the drawer: on Home it leaves)."""
        for attempt in range(8):
            if self.in_drawer():
                return
            self.key("BACK" if attempt < 3 else "LEFT")
            time.sleep(1)
        raise TimeoutError("the drawer never took the focus")

    def restart(self):
        """Ondo stopped and started again: Home with the saved playlist, read back from the device's storage."""
        self.shell(f"am force-stop {PACKAGE}")
        time.sleep(1)
        self.shell(f"am start -W -n {ACTIVITY}")
        self.pid = self.current_pid()
        self.say(f"restarted, pid {self.pid}")
        self.wait_for("home after a restart", lambda: self.visible("Live TV") and not self.visible("Try the demo"), 60)
        time.sleep(3)
        self.check_alive("home after a restart")

    @staticmethod
    def left_edge(node):
        """The node's left edge in pixels (bounds="[x1,y1][x2,y2]")."""
        found = re.match(r"\[(-?\d+),", node.get("bounds", "")) if node is not None else None
        return int(found.group(1)) if found else 10_000

    def in_drawer(self):
        """The focus on one of the drawer's places: its words, at the screen's left edge (a page's chips have the same words)."""
        node, texts = self.focused()
        return node is not None and any(t in DRAWER for t in texts) and self.left_edge(node) < 260

    def to_drawer(self):
        """The focus on the drawer: Back from a page lands on its tab (never Back from the drawer: on Home it leaves)."""
        for attempt in range(8):
            if self.in_drawer():
                return
            self.key("BACK" if attempt < 3 else "LEFT")
            time.sleep(1)
        raise TimeoutError("the drawer never took the focus")

    def wait_enabled(self, label, timeout, what):
        """Waits until the element showing [label] can be pressed again (a button greyed out while something loads)."""
        def enabled():
            root = self.screen()
            if root is None:
                return False
            for n in root.iter("node"):
                if label in self.texts(n) and n.get("enabled") == "true" and n.get("clickable") == "true":
                    return True
            return False
        time.sleep(3)
        self.wait_for(what, enabled, timeout, every=4)

    def drawer(self, item):
        """Opens a place of the drawer (a tab, Search or Settings)."""
        self.to_drawer()
        for i in range(18):
            node, texts = self.focused()
            if node is not None and item in texts and self.left_edge(node) < 260:
                break
            self.key("UP" if i < 8 else "DOWN")
        else:
            raise TimeoutError(f"the drawer's {item} never had the focus")
        self.key("DPAD_CENTER")
        time.sleep(4)
        self.check_alive(f"{item} opened")

    def stay(self, seconds, where):
        """Waits while watching the app."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            time.sleep(min(3, max(0, deadline - time.time())))
            self.check_alive(where)

    def player_shown(self):
        return f"{PACKAGE}/.player.PlayerActivity" in self.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")

    def leave_player(self):
        """Back until the player is gone (the first Back may only hide its controls)."""
        for _ in range(4):
            if not self.player_shown():
                return
            self.key("BACK")
            time.sleep(2)
        if self.player_shown():
            raise TimeoutError("Back never closed the player")


DRAWER = ("Search", "Home", "Movies", "Series", "Live TV", "My List", "Settings")


def settings_section(tv, section):
    """Settings open, the remote on one of its parts on the left (its page shows on the right)."""
    tv.focus_exact(section, keys=("UP",) * 5 + ("DOWN",) * 5, max_presses=10)
    time.sleep(1)


def path_settings(tv, args):
    """Every part of Settings and what opens from it; Refresh last (the whole catalogue loaded again beside the one shown)."""
    tv.drawer("Settings")
    tv.note_screen("settings")
    settings_section(tv, "Sync")
    tv.key("RIGHT")
    tv.open("Sync on this network", keys=("UP",), max_presses=3)
    tv.stay(10, "sync on")
    tv.note_screen("settings-sync-on")
    tv.key("DPAD_CENTER")
    tv.stay(3, "sync off")
    tv.key("LEFT")
    settings_section(tv, "Player")
    tv.key("RIGHT")
    for row in ("Buffer for live TV", "Buffer for movies & series"):
        tv.open(row, keys=("DOWN",), max_presses=3)
        tv.note_screen("settings-buffer-choice")
        tv.key("DOWN")
        tv.key("DPAD_CENTER")
        time.sleep(2)
        tv.check_alive(f"{row} chosen")
    tv.key("LEFT")
    settings_section(tv, "Catalog")
    tv.key("RIGHT")
    tv.open("Languages and categories", keys=("UP",), max_presses=3, wait=5)
    tv.note_screen("settings-categories")
    # Tab goes through everything in order (an arrow stays in the page's text fields).
    tv.open("Children", keys=("TAB",), max_presses=40, exact=True, wait=3)
    tv.note_screen("settings-styles-kids-hidden")
    tv.key("DPAD_CENTER")
    time.sleep(3)
    tv.check_alive("a style turned over twice")
    tv.open("Hide all", keys=("TAB",), max_presses=40, exact=True, wait=6)
    tv.note_screen("settings-categories-all-hidden")
    tv.open("Show all", keys=("LEFT",), max_presses=3, exact=True, wait=6)
    tv.note_screen("settings-categories-all-shown")
    tv.key("BACK")
    time.sleep(3)
    tv.check_alive("back from the categories")
    if not tv.visible("Show adult categories"):
        settings_section(tv, "Catalog")
        tv.key("RIGHT")
    for row in ("Show adult categories", "Hide watched movies", "Update awards automatically"):
        tv.open(row, keys=("DOWN",), max_presses=5, wait=2)
        tv.key("DPAD_CENTER")
        time.sleep(2)
    tv.stay(5, "catalogue switches turned over")
    tv.note_screen("settings-catalogue")
    tv.key("LEFT")
    settings_section(tv, "About")
    tv.key("RIGHT")
    tv.open("Help", keys=("UP",), max_presses=3, wait=3)
    tv.note_screen("settings-help")
    tv.key("BACK")
    time.sleep(2)
    for row in ("Privacy policy", "Support"):
        if not tv.visible("Privacy policy"):
            settings_section(tv, "About")
            tv.key("RIGHT")
        tv.open(row, keys=("DOWN",), max_presses=4, wait=8)
        tv.note_screen(f"settings-{row.split()[0].lower()}")
        tv.key("BACK")
        time.sleep(3)
        tv.check_alive(f"back from {row}")
    if not tv.visible("Privacy policy"):
        settings_section(tv, "About")
        tv.key("RIGHT")
    tv.key("DOWN", 6)
    tv.note_screen("settings-about-end")
    tv.key("LEFT")
    settings_section(tv, "Playlists")
    tv.key("RIGHT")
    tv.open("Refresh", keys=("UP", "LEFT"), max_presses=4, wait=1)
    tv.note_screen("settings-refreshing")
    tv.stay(100, "the catalogue and its guide refreshed from the Settings")
    tv.note_screen("settings-refreshed")
    tv.key("BACK")
    time.sleep(3)
    tv.check_alive("back from the Settings")


def path_search(tv, args):
    """Search typed with the remote's keys: a movie opened, then a person (actor, director) and one of their titles."""
    tv.drawer("Search")
    tv.note_screen("search")
    tv.focus_on_field(0)
    tv.shell("input text 'Movie%s12'")
    tv.stay(4, "search typed")
    tv.note_screen("search-typed")
    tv.key("TAB")
    time.sleep(1)
    tv.open("Movie 12", keys=("DOWN", "RIGHT", "TAB"), max_presses=12, wait=5)
    tv.note_screen("search-movie")
    tv.open("Actor One", keys=("DOWN", "RIGHT"), max_presses=8, wait=4)
    tv.note_screen("search-person")
    tv.key("DOWN")
    tv.key("DPAD_CENTER")
    time.sleep(4)
    tv.check_alive("a title of the person opened")
    tv.note_screen("search-person-title")
    for _ in range(3):
        tv.key("BACK")
        time.sleep(2)
    tv.check_alive("back to the search")
    tv.drawer("Search")
    tv.focus_on_field(0)
    tv.key("MOVE_END")
    tv.key("DEL", times=10, pause=0.1)
    tv.shell("input text 'Director'")
    tv.stay(4, "a person searched")
    tv.note_screen("search-people")
    tv.key("TAB")
    tv.open("Director", keys=("DOWN", "RIGHT", "TAB"), max_presses=12, wait=4)
    tv.note_screen("search-director")


def path_details(tv, args):
    """A movie's page (get_vod_info) and a series' page (get_series_info): seasons, an episode played."""
    tv.drawer("Series")
    tv.key("DOWN", 2)
    tv.key("RIGHT", 2)
    tv.key("DPAD_CENTER")
    tv.wait_for("a series page", lambda: tv.visible("Season 1"), 30)
    tv.note_screen("series")
    tv.focus_exact("Season 1", keys=("DOWN",), max_presses=6)
    tv.key("RIGHT")
    tv.key("DPAD_CENTER")
    time.sleep(3)
    tv.key("RIGHT")
    tv.key("DPAD_CENTER")
    time.sleep(3)
    tv.check_alive("seasons changed")
    tv.note_screen("series-season3")
    tv.key("DOWN")
    time.sleep(1)
    tv.note_screen("series-episodes")
    tv.key("RIGHT")
    tv.key("DPAD_CENTER")
    time.sleep(8)
    tv.check_alive("an episode played")
    tv.note_screen("series-episode-player")
    tv.leave_player()
    tv.check_alive("back from an episode")
    tv.key("DOWN", 4)
    tv.note_screen("series-bottom")
    tv.key("BACK")
    time.sleep(2)
    tv.drawer("Movies")
    tv.key("DOWN", 2)
    tv.key("RIGHT", 1)
    tv.key("DPAD_CENTER")
    tv.wait_for("a movie page", lambda: tv.visible("Play") and tv.visible("Collection"), 30)
    tv.stay(3, "a movie page")
    tv.note_screen("movie")
    tv.open("Play", keys=("UP", "LEFT"), max_presses=4, wait=8)
    tv.note_screen("movie-player")
    tv.leave_player()
    tv.check_alive("back from a movie")
    tv.open("Collection", keys=("RIGHT",), max_presses=4, wait=2)
    tv.note_screen("movie-collection")
    tv.key("BACK")
    time.sleep(2)
    tv.key("DOWN", 6, pause=0.6)
    tv.note_screen("movie-bottom")
    tv.key("DPAD_CENTER")
    time.sleep(4)
    tv.check_alive("a title further down a movie's page")
    tv.note_screen("movie-bottom-opened")


def path_player(tv, args):
    """A live channel and a movie in the player, the remote's keys pressed: OK, arrows, channels, figures, Info, the menu."""
    tv.drawer("Live TV")
    tv.key("DOWN")
    tv.key("DPAD_CENTER")
    time.sleep(8)
    tv.check_alive("a live channel opened")
    tv.note_screen("player-live")
    if not tv.player_shown():
        raise TimeoutError("the player never opened from Live TV")
    for key in ("DPAD_CENTER", "DPAD_CENTER", "UP", "UP", "DOWN", "CHANNEL_UP", "CHANNEL_DOWN", "LAST_CHANNEL"):
        tv.key(key, pause=2.5)
        tv.check_alive(f"{key} in the live player")
    tv.key("1", pause=0.3)
    tv.key("2", pause=3)
    tv.check_alive("a channel number typed")
    tv.key("9", pause=0.2)
    tv.key("9", pause=0.2)
    tv.key("9", pause=0.2)
    tv.key("9", pause=3)
    tv.check_alive("a channel number that does not exist")
    tv.note_screen("player-live-zapped")
    for key in ("INFO", "GUIDE", "MENU", "MEDIA_PLAY_PAUSE", "MEDIA_PLAY_PAUSE"):
        tv.key(key, pause=2)
        tv.check_alive(f"{key} in the live player")
    for _ in range(3):
        if tv.visible("More"):
            break
        tv.key("INFO", pause=1)
    tv.note_screen("player-live-controls")
    tv.open("More", keys=("DOWN", "RIGHT", "RIGHT", "RIGHT", "RIGHT", "RIGHT"), max_presses=12, wait=2)
    tv.note_screen("player-menu")
    tv.open("Report a Problem…", keys=("DOWN",), max_presses=4, wait=3)
    tv.note_screen("player-report")
    tv.key("BACK")
    time.sleep(2)
    tv.leave_player()
    tv.check_alive("back from the live player")
    tv.drawer("Movies")
    tv.key("DOWN", 2)
    tv.key("DPAD_CENTER")
    tv.wait_for("a movie page", lambda: tv.visible("Play") and tv.visible("Collection"), 30)
    tv.open("Play", keys=("UP", "LEFT"), max_presses=4, wait=10)
    tv.note_screen("player-movie")
    for key in ("DPAD_CENTER", "DPAD_CENTER", "RIGHT", "RIGHT", "LEFT", "MEDIA_FAST_FORWARD", "MEDIA_REWIND", "UP", "DOWN", "INFO", "INFO"):
        tv.key(key, pause=2.5)
        tv.check_alive(f"{key} in the movie player")
    tv.note_screen("player-movie-keys")
    tv.shell("input keyevent --longpress KEYCODE_DPAD_RIGHT")
    time.sleep(3)
    tv.check_alive("a long press in the movie player")
    tv.leave_player()
    tv.stay(4, "back from the movie player")
    tv.note_screen("player-movie-closed")


def path_guide(tv, args):
    """The TV guide: scrolled, a programme opened, a reminder set; its WorkManager job run at once."""
    tv.drawer("Live TV")
    tv.key("UP", 3)
    tv.open("TV guide", keys=("RIGHT",), max_presses=8, wait=8)
    tv.wait_for("the guide", lambda: tv.visible("All channels") or tv.visible("Reminders"), 60)
    tv.note_screen("guide")
    tv.key("DOWN", 6, pause=0.5)
    tv.key("RIGHT", 4, pause=0.5)
    tv.key("UP", 3, pause=0.5)
    tv.check_alive("the guide scrolled")
    tv.note_screen("guide-scrolled")
    tv.key("DPAD_CENTER")
    time.sleep(3)
    tv.note_screen("guide-programme")
    tv.open("Remind me", keys=("DOWN", "TAB", "RIGHT"), max_presses=8, wait=3)
    tv.note_screen("guide-reminder-set")
    tv.key("BACK")
    time.sleep(2)
    jobs = tv.shell("dumpsys jobscheduler")
    ids = sorted(set(re.findall(r"JOB #u\d+a\d+/(\d+): \S+ " + re.escape(PACKAGE) + r"/androidx\.work", jobs)))
    tv.say(f"WorkManager jobs: {ids}")
    for job in ids:
        tv.say("run job " + job + ": " + tv.shell(f"cmd jobscheduler run -f {PACKAGE} {job}").strip())
    tv.stay(10, "the reminder's job run")
    notes = tv.shell("dumpsys notification --noredact")
    shown = [line.strip() for line in notes.splitlines() if PACKAGE in line and "NotificationRecord" in line]
    tv.say(f"notifications: {shown}")
    if not shown:
        raise TimeoutError("no reminder notification after its WorkManager job ran")
    tv.open("Reminders", keys=("UP", "RIGHT"), max_presses=10, wait=3)
    tv.note_screen("guide-reminders")
    tv.key("BACK")
    time.sleep(2)
    tv.key("BACK")
    time.sleep(2)
    tv.check_alive("back from the guide")


def path_mylist(tv, args):
    """A movie and a channel added to My List and taken out again; the long press on a channel."""
    tv.drawer("Movies")
    tv.key("DOWN", 2)
    tv.key("RIGHT", 3)
    tv.key("DPAD_CENTER")
    tv.wait_for("a movie page", lambda: tv.visible("Play") and tv.visible("Collection"), 30)
    tv.open("My List", keys=("RIGHT",), max_presses=4, wait=2)
    tv.note_screen("mylist-added")
    tv.key("BACK")
    time.sleep(2)
    tv.drawer("Live TV")
    tv.key("DOWN")
    tv.shell("input keyevent --longpress KEYCODE_DPAD_CENTER")
    time.sleep(3)
    tv.check_alive("a long press on a channel")
    tv.note_screen("mylist-channel-longpress")
    tv.drawer("My List")
    tv.note_screen("mylist")
    for segment in ("Movies", "Channels"):
        tv.open(segment, keys=("UP", "RIGHT", "RIGHT", "RIGHT", "LEFT", "LEFT", "LEFT"), max_presses=14, exact=True, wait=2)
        tv.note_screen(f"mylist-{segment.lower()}")
        tv.key("DOWN")
        tv.key("DPAD_CENTER")
        time.sleep(5)
        tv.check_alive(f"an item of My List's {segment} opened")
        tv.note_screen(f"mylist-{segment.lower()}-opened")
        tv.leave_player()
        if segment == "Movies":
            tv.open("My List", keys=("UP", "RIGHT"), max_presses=6, wait=2)
            tv.note_screen("mylist-removed")
        tv.key("BACK")
        time.sleep(2)
        tv.drawer("My List")


def path_playlists(tv, args):
    """A second Xtream playlist added from the Settings, the first used again, the second deleted."""
    tv.drawer("Settings")
    settings_section(tv, "Playlists")
    tv.key("RIGHT")
    tv.open("Add a Playlist", keys=("DOWN",), max_presses=6, wait=3)
    tv.wait_for("add form", lambda: tv.visible("Server", "Username", "Password"), 30)
    for index, value in ((0, "Second"), (1, args.server), (2, "viewer"), (3, "secret")):
        tv.focus_on_field(index)
        tv.shell(f"input text '{value}'")
        time.sleep(0.5)
    tv.focus_on("Add", keys=("TAB",), editable=False)
    tv.key("DPAD_CENTER")
    tv.wait_for("the second playlist", lambda: tv.visible("Second") and not tv.visible("Username"), args.wait_load, every=3)
    tv.stay(5, "the second playlist loaded")
    tv.note_screen("playlists-two")
    if not tv.visible("Use"):
        settings_section(tv, "Playlists")
        tv.key("RIGHT")
    tv.open("Use", keys=("DOWN", "UP"), max_presses=8, exact=True, wait=1)
    tv.stay(40, "the first playlist used again")
    tv.note_screen("playlists-switched")
    settings_section(tv, "Playlists")
    tv.key("RIGHT")
    tv.focus_exact("Delete Second", keys=("DOWN", "RIGHT", "RIGHT", "UP", "DOWN"), max_presses=12)
    tv.key("DPAD_CENTER")
    time.sleep(2)
    tv.note_screen("playlists-confirm")
    tv.key("RIGHT")
    tv.key("DPAD_CENTER")
    tv.stay(8, "a playlist deleted")
    tv.note_screen("playlists-deleted")
    if tv.visible("Second"):
        raise TimeoutError("the second playlist is still there after Delete")
    tv.key("BACK")
    time.sleep(3)
    tv.check_alive("back from the Settings")


def path_browse(tv, args):
    """Pages opened with arguments: a category's grid (and every movie's), a genre and a year (Browse: its kind read
    back by name), a new collection and its page, the filters of Search, Live TV and the guide."""
    tv.drawer("Movies")
    tv.open("FR | Films 2", keys=("UP", "RIGHT", "RIGHT", "RIGHT"), max_presses=8, exact=True, wait=5)
    tv.note_screen("browse-category-grid")
    tv.key("DOWN")
    tv.key("RIGHT", 2)
    tv.key("DPAD_CENTER")
    time.sleep(4)
    tv.check_alive("a movie of a category's grid opened")
    tv.note_screen("browse-grid-movie")
    tv.open("Drama", keys=("DOWN", "DOWN", "DOWN", "UP"), max_presses=10, exact=True, wait=5)
    tv.note_screen("browse-genre")
    tv.key("DOWN")
    tv.key("DPAD_CENTER")
    time.sleep(4)
    tv.check_alive("a title of a genre opened")
    tv.note_screen("browse-genre-movie")
    tv.focus_exact("Drama", keys=("DOWN", "DOWN", "DOWN", "UP"), max_presses=10)
    tv.open("Year ", keys=("RIGHT",), max_presses=4, wait=5)
    tv.note_screen("browse-year")
    tv.key("BACK")
    time.sleep(2)
    tv.open("Collection", keys=("UP", "UP", "UP", "UP", "RIGHT"), max_presses=10, exact=True, wait=2)
    tv.open("New collection", keys=("DOWN",), max_presses=4, wait=2)
    tv.note_screen("browse-collection-name")
    tv.focus_on("Create", keys=("TAB",), max_presses=6, editable=False)
    tv.key("DPAD_CENTER")
    tv.stay(3, "a collection created")
    tv.note_screen("browse-collection-created")
    tv.drawer("My List")
    tv.open("Collections", keys=("UP", "RIGHT", "RIGHT", "RIGHT", "RIGHT"), max_presses=10, exact=True, wait=2)
    tv.open("Collection 1", keys=("DOWN",), max_presses=4, wait=4)
    tv.note_screen("browse-collection-page")
    tv.key("DOWN")
    tv.key("DPAD_CENTER")
    time.sleep(4)
    tv.check_alive("a title of a collection opened")
    tv.key("BACK")
    time.sleep(2)
    tv.key("BACK")
    time.sleep(2)
    tv.drawer("Search")
    tv.focus_on_field(0)
    tv.shell("input text 'Series%s1'")
    tv.stay(3, "search typed")
    tv.open("Filters", keys=("TAB",), max_presses=4, wait=3)
    tv.note_screen("browse-search-filters")
    tv.key("DOWN", 2)
    tv.key("DPAD_CENTER")
    time.sleep(2)
    tv.check_alive("a search filter chosen")
    tv.note_screen("browse-search-filter-chosen")
    tv.key("BACK")
    time.sleep(2)
    tv.drawer("Live TV")
    tv.key("UP", 3)
    tv.open("Categories", keys=("RIGHT",), max_presses=8, exact=True, wait=3)
    tv.note_screen("browse-live-categories")
    tv.key("DOWN", 3)
    tv.key("DPAD_CENTER")
    time.sleep(3)
    tv.check_alive("a live category chosen")
    tv.note_screen("browse-live-category")


def listening_ports(tv):
    """The TCP ports the app listens on (its sync server), from the system's socket tables."""
    uid = re.search(r"userId=(\d+)", tv.shell(f"dumpsys package {PACKAGE}"))
    if not uid:
        return []
    ports = []
    for table in ("/proc/net/tcp6", "/proc/net/tcp"):
        for line in tv.shell(f"cat {table}").splitlines()[1:]:
            fields = line.split()
            # sl local_address rem_address st tx:rx tr:when retrnsmt uid ...
            if len(fields) > 7 and fields[3] == "0A" and fields[7] == uid.group(1):
                ports.append(int(fields[1].rsplit(":", 1)[1], 16))
    return sorted(set(ports))


def sync_exchange(port, snapshot):
    """One sync session as another device runs it (docs/SYNC.md): a frame (4-byte length, JSON) each way."""
    payload = json.dumps(snapshot).encode()
    with socket.create_connection(("127.0.0.1", port), timeout=30) as connection:
        connection.sendall(struct.pack(">I", len(payload)) + payload)
        header = b""
        while len(header) < 4:
            chunk = connection.recv(4 - len(header))
            if not chunk:
                return None
            header += chunk
        size = struct.unpack(">I", header)[0]
        body = b""
        while len(body) < size:
            chunk = connection.recv(min(1 << 16, size - len(body)))
            if not chunk:
                return None
            body += chunk
    return json.loads(body)


def path_sync(tv, args):
    """Sync on the local network turned on, and a session from another device (a made-up phone, through adb forward):
    its snapshot read by the release build, merged (a playlist, My List, progress, a list), and the answer written."""
    tv.drawer("Settings")
    settings_section(tv, "Sync")
    tv.key("RIGHT")
    tv.open("Sync on this network", keys=("UP",), max_presses=3, wait=6)
    ports = listening_ports(tv)
    tv.say(f"the app listens on {ports}")
    if not ports:
        raise TimeoutError("no sync server listening after sync was turned on")
    tv.adb("forward", "tcp:18765", f"tcp:{ports[0]}")
    now = int(time.time() * 1000)
    playlist = "6f1c0d3e-0000-4000-8000-00000000a001"
    snapshot = {
        "type": "ondo-sync", "version": 1, "device": "0f0f0f0f-0000-4000-8000-0000000000aa", "deviceName": "Test phone",
        "playlists": [{"id": playlist, "name": "Synced", "kind": "xtream", "url": args.server, "username": "viewer",
                       "password": "secret", "addedAt": now, "updatedAt": now}],
        "favorites": [{"playlist": playlist, "kind": "movie", "item": "12", "title": "Movie 12", "addedAt": now},
                      {"playlist": playlist, "kind": "live", "item": "3", "title": "Channel 3", "addedAt": now}],
        "progress": [{"playlist": playlist, "kind": "movie", "item": "12", "title": "Movie 12", "position": 60.0,
                      "duration": 5400.0, "watchedAt": now}],
        "lists": [{"id": "6f1c0d3e-0000-4000-8000-00000000b001", "name": "Weekend", "updatedAt": now,
                   "items": [{"playlist": playlist, "kind": "movie", "item": "12", "title": "Movie 12"}]}],
        "deleted": {}, "replaced": {},
    }
    try:
        answer = sync_exchange(18765, snapshot)
    except (OSError, ValueError) as problem:
        answer = None
        tv.say(f"sync session: {problem!r}")
    tv.check_alive("a sync session from another device")
    if answer is None:
        raise TimeoutError("the app's sync server gave no answer")
    tv.say(f"sync answer from {answer.get('deviceName')!r}: {len(answer.get('playlists', []))} playlists, "
           f"{len(answer.get('favorites', []))} in My List, {len(answer.get('progress', []))} progress, {len(answer.get('lists', []))} lists")
    tv.stay(10, "a sync merged")
    tv.note_screen("sync-heard")
    tv.key("LEFT")
    settings_section(tv, "Playlists")
    tv.stay(3, "the playlists after a sync")
    tv.note_screen("sync-playlists")
    settings_section(tv, "Sync")
    tv.key("RIGHT")
    tv.open("Sync on this network", keys=("UP",), max_presses=3, wait=3)
    tv.adb("forward", "--remove", "tcp:18765")
    tv.key("BACK")
    time.sleep(3)
    tv.drawer("My List")
    tv.note_screen("sync-mylist")


def recreated(tv, where):
    """The screen made again by Android (a system setting changed: night mode, and back), its saved state read back."""
    for mode in ("yes", "no"):
        tv.shell(f"cmd uimode night {mode}")
        tv.stay(5, f"{where}: recreated (night mode {mode})")
    tv.note_screen(f"recreated-{where}")


def killed_in_background(tv, where, expect):
    """Home, Android ends the app in the background (am kill, as it does for memory), then Ondo opened again: Android
    recreates the screens from their saved state in a new process."""
    tv.key("HOME")
    time.sleep(4)
    tv.shell(f"am kill {PACKAGE}")
    time.sleep(3)
    before, after = tv.pid, tv.current_pid()
    if after == before:
        raise TimeoutError(f"{where}: am kill left the app's process {before}")
    tv.shell(f"am start -W -n {ACTIVITY}")
    tv.pid = tv.current_pid()
    tv.say(f"{where}: process {before} ended in the background, {tv.pid} now, screens restored")
    tv.stay(8, f"{where}: restored after the process ended")
    tv.note_screen(f"restored-{where}")
    if expect and not tv.visible(expect):
        tv.say(f"  {where}: '{expect}' not on screen after the restore")


def path_restore(tv, args):
    """Saved state: pages recreated by Android (configuration change), and restored in a new process after Android
    ended the app in the background (navigation's routes, the player's request, the remembered values)."""
    tv.drawer("Movies")
    tv.key("DOWN", 2)
    tv.key("RIGHT", 2)
    tv.key("DPAD_CENTER")
    tv.wait_for("a movie page", lambda: tv.visible("Play") and tv.visible("Collection"), 30)
    recreated(tv, "movie")
    killed_in_background(tv, "movie", "Collection")
    tv.key("BACK")
    time.sleep(3)
    tv.check_alive("Back after the restore")
    tv.note_screen("restored-movie-back")
    tv.drawer("Settings")
    settings_section(tv, "Catalog")
    tv.key("RIGHT")
    tv.open("Languages and categories", keys=("UP",), max_presses=3, wait=5)
    recreated(tv, "categories")
    killed_in_background(tv, "categories", "Styles")
    tv.key("BACK")
    time.sleep(3)
    tv.check_alive("Back to the Settings after the restore")
    tv.note_screen("restored-settings")
    tv.key("BACK")
    time.sleep(3)
    tv.drawer("Live TV")
    tv.key("UP", 3)
    tv.open("TV guide", keys=("RIGHT",), max_presses=8, wait=8)
    tv.key("DOWN", 3)
    recreated(tv, "guide")
    killed_in_background(tv, "guide", "Reminders")
    tv.key("BACK")
    time.sleep(3)
    tv.drawer("Movies")
    tv.key("DOWN", 2)
    tv.key("DPAD_CENTER")
    tv.wait_for("a movie page", lambda: tv.visible("Play") and tv.visible("Collection"), 30)
    tv.open("Play", keys=("UP", "LEFT"), max_presses=4, wait=8)
    if not tv.player_shown():
        raise TimeoutError("the player never opened from a movie page")
    recreated(tv, "player")
    killed_in_background(tv, "player", None)
    tv.say(f"  player in front after the restore: {tv.player_shown()}")
    tv.stay(8, "the player restored")
    tv.leave_player()
    tv.stay(4, "back from the restored player")
    tv.note_screen("restored-player-closed")


def path_crash(tv, args):
    """The last crash in Settings → About: a crash made on purpose (am crash), then the next start shows it."""
    tv.say("making the app crash on purpose (am crash): not a failure")
    tv.shell(f"am crash {PACKAGE}")
    time.sleep(5)
    tv.adb("logcat", "-b", "crash", "-c")
    tv.restart()
    tv.drawer("Settings")
    settings_section(tv, "About")
    tv.key("RIGHT")
    tv.focus_on("Last crash", keys=("DOWN",), max_presses=10)
    tv.stay(3, "the last crash shown")
    tv.note_screen("settings-last-crash")
    tv.key("BACK")
    time.sleep(2)


PATHS = {
    "settings": path_settings,
    "search": path_search,
    "details": path_details,
    "player": path_player,
    "guide": path_guide,
    "mylist": path_mylist,
    "playlists": path_playlists,
    "restore": path_restore,
    "browse": path_browse,
    "sync": path_sync,
    # Last: it crashes the app on purpose.
    "crash": path_crash,
}


def limit_memory(tv, limit):
    """Sets the memory limit of every app (dalvik.vm.heapgrowthlimit) and restarts Android's runtime to apply it."""
    tv.adb("root", timeout=60)
    tv.adb("wait-for-device", timeout=120)
    tv.shell(f"setprop dalvik.vm.heapgrowthlimit {limit}")
    tv.shell("stop")
    tv.shell("start")
    deadline = time.time() + 240
    while time.time() < deadline:
        time.sleep(5)
        ready = tv.shell("getprop sys.boot_completed").strip() == "1" and "package:" in tv.shell("pm path android")
        if ready and tv.shell("pidof com.android.systemui").strip():
            break
    time.sleep(20)
    tv.say(f"memory limit set to {tv.shell('getprop dalvik.vm.heapgrowthlimit').strip()}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--server", default="http://localhost:8080")
    parser.add_argument("--wait-load", type=int, default=240)
    parser.add_argument("--wrong-password-first", action="store_true")
    parser.add_argument("--growth-limit", default="", help="the app's memory limit, as an entry-level TV box's (e.g. 128m)")
    parser.add_argument("--paths", default="", help=f"more paths after the first walk, comma-separated: {','.join(PATHS)} (or all)")
    args = parser.parse_args()
    paths = list(PATHS) if args.paths.strip() == "all" else [p.strip() for p in args.paths.split(",") if p.strip()]
    unknown = [p for p in paths if p not in PATHS]
    if unknown:
        parser.error(f"unknown paths {unknown}: {', '.join(PATHS)}")
    os.makedirs(args.out, exist_ok=True)
    tv = Tv(args.out)
    result = 1
    try:
        if args.growth_limit:
            if not re.fullmatch(r"[0-9]{2,4}m", args.growth_limit):
                raise RuntimeError(f"a memory limit is written like 128m, not {args.growth_limit!r}")
            limit_memory(tv, args.growth_limit)
        tv.say("device: " + " ".join(tv.shell(f"getprop {p}").strip() for p in (
            "ro.build.version.sdk", "ro.product.model", "dalvik.vm.heapgrowthlimit", "dalvik.vm.heapsize", "ro.config.low_ram")))
        tv.say("app: " + tv.shell(f"dumpsys package {PACKAGE} | grep -E 'versionCode|versionName'").strip().replace("\n", " "))
        # No on-screen keyboard: the remote's arrows move between the fields, text comes as key presses.
        for ime in tv.shell("ime list -s").split():
            tv.shell(f"ime disable {ime}")
        tv.adb("logcat", "-c")
        tv.adb("logcat", "-b", "crash", "-c")
        tv.shell(f"am start -W -n {ACTIVITY}")
        tv.pid = tv.current_pid()
        tv.say(f"started, pid {tv.pid}")
        tv.wait_for("welcome", lambda: tv.visible("Add a Playlist"), 90)
        tv.screenshot("welcome")
        tv.focus_on("Add a Playlist", keys=("UP", "UP", "DOWN"))
        tv.key("DPAD_CENTER")
        tv.wait_for("add form", lambda: tv.visible("Server", "Username", "Password"), 30)
        tv.screenshot("form")
        password = "wrong" if args.wrong_password_first else "secret"
        # Name (left empty), Server, Username, Password: Xtream Codes is the form's first kind.
        for index, value in ((1, args.server), (2, "viewer"), (3, password)):
            tv.focus_on_field(index)
            tv.shell(f"input text '{value}'")
            time.sleep(0.5)
            field = tv.focus_on_field(index)
            tv.say(f"field {index}: {field.get('text')!r}")
        tv.screenshot("filled")
        tv.focus_on("Add", keys=("TAB",), editable=False)
        tv.say("pressing Add")
        tv.key("DPAD_CENTER")
        if args.wrong_password_first:
            time.sleep(5)
            tv.check_alive("after a refused password")
            tv.screenshot("refused")
            for _ in range(4):
                tv.key("UP")
            tv.focus_on_field(3)
            tv.key("MOVE_END")
            tv.key("DEL", times=8, pause=0.1)
            tv.shell("input text 'secret'")
            tv.focus_on("Add", keys=("TAB",), editable=False)
            tv.key("DPAD_CENTER")
        start = time.time()
        # The catalogue arrives: the welcome and the form are replaced by the tabs (Live TV in the drawer).
        tv.wait_for("catalogue", lambda: not tv.visible("Server", "Username") and tv.visible("Live TV"), args.wait_load, every=3)
        tv.say(f"catalogue on screen after {time.time() - start:.0f} s")
        tv.screenshot("home")
        # The guide and the details load in the background: stay a while (a large guide takes a minute), then walk.
        for _ in range(30):
            time.sleep(3)
            tv.check_alive("home, guide loading")
        tv.say(f"still running {time.time() - start:.0f} s after Add")
        tv.key("DOWN", 2)
        tv.key("RIGHT", 3)
        tv.screenshot("home-moved")
        tv.key("DPAD_CENTER")
        time.sleep(4)
        tv.check_alive("a poster opened")
        tv.screenshot("opened")
        tv.key("BACK")
        time.sleep(2)
        tabs = ("Home", "Movies", "Series", "Live TV", "My List")
        for tab in ("Movies", "Series", "Live TV", "My List", "Home"):
            # To the drawer: Back from a page lands on its tab (never Back from the drawer: on Home it leaves the app).
            for attempt in range(6):
                _, texts = tv.focused()
                if any(t in tabs for t in texts):
                    break
                tv.key("BACK" if attempt < 2 else "LEFT")
                time.sleep(1)
            tv.focus_on(tab, keys=("UP", "UP", "UP", "UP", "UP", "UP", "DOWN", "DOWN", "DOWN", "DOWN", "DOWN", "DOWN", "DOWN", "DOWN"), max_presses=16)
            tv.key("DPAD_CENTER")
            time.sleep(4)
            tv.check_alive(f"{tab} opened")
            tv.screenshot(f"tab-{tab.replace(' ', '')}")
            tv.key("DOWN", 3)
            tv.key("RIGHT", 4)
            tv.key("DPAD_CENTER")
            time.sleep(4)
            tv.check_alive(f"{tab}: an item opened")
            tv.screenshot(f"tab-{tab.replace(' ', '')}-item")
            tv.key("BACK")
            time.sleep(2)
        for _ in range(10):
            time.sleep(3)
            tv.check_alive("idle at the end")
        tv.screenshot("end")
        # Each path from a fresh start: a path the remote could not finish does not stop the others; a crash does.
        missed = []
        for name in paths:
            tv.say(f"path {name}")
            try:
                tv.restart()
                PATHS[name](tv, args)
                tv.say(f"path {name}: done")
            except TimeoutError as problem:
                missed.append(name)
                tv.say(f"path {name}: NOT DRIVEN to its end: {problem}")
                tv.note_screen(f"{name}-stuck")
        if missed:
            raise RuntimeError(f"no crash, but the remote never got through: {', '.join(missed)}")
        tv.say("PASS: no crash, no ANR, the app ran throughout" + (f" (paths: {', '.join(paths)})" if paths else ""))
        result = 0
    except (Stopped, TimeoutError, RuntimeError, subprocess.TimeoutExpired) as problem:
        tv.say(f"FAIL: {problem}")
        try:
            tv.screenshot("failure")
        except Exception:
            pass
    finally:
        with open(os.path.join(args.out, "logcat.txt"), "w") as f:
            f.write(tv.adb("logcat", "-d", "-v", "threadtime", timeout=120))
        with open(os.path.join(args.out, "crash.txt"), "w") as f:
            f.write(tv.adb("logcat", "-d", "-b", "crash"))
        with open(os.path.join(args.out, "exit-info.txt"), "w") as f:
            f.write(tv.shell(f"dumpsys activity exit-info {PACKAGE}", timeout=120))
        with open(os.path.join(args.out, "meminfo.txt"), "w") as f:
            f.write(tv.shell(f"dumpsys meminfo {PACKAGE}", timeout=120))
    return result


if __name__ == "__main__":
    sys.exit(main())
