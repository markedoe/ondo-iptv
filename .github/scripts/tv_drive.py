#!/usr/bin/env python3
"""Drives the installed release build of Ondo on an Android TV emulator with remote keys, as a viewer would:
first launch, Add a Playlist, Xtream Codes (the made-up panel of fake_xtream_panel.py, reached through
`adb reverse`), Add, then every tab. Watches the app's process the whole time: a crash, an ANR or Android ending the
app fails the run. Screenshots, the screen's text, logcat, the exit reasons and memory use go to the output folder.

Usage: tv_drive.py <output folder> [--server http://localhost:8080] [--wait-load 240]
"""
import argparse
import os
import re
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--server", default="http://localhost:8080")
    parser.add_argument("--wait-load", type=int, default=240)
    parser.add_argument("--wrong-password-first", action="store_true")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    tv = Tv(args.out)
    result = 1
    try:
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
        # The guide and the details load in the background: stay a while, then walk the pages.
        for _ in range(10):
            time.sleep(3)
            tv.check_alive("home, guide loading")
        tv.key("DOWN", 2)
        tv.key("RIGHT", 3)
        tv.screenshot("home-moved")
        tv.key("DPAD_CENTER")
        time.sleep(4)
        tv.check_alive("a poster opened")
        tv.screenshot("opened")
        tv.key("BACK")
        time.sleep(2)
        for tab in ("Movies", "Series", "Live TV", "My List", "Home"):
            tv.key("LEFT", 4)
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
        tv.say("PASS: no crash, no ANR, the app ran throughout")
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
