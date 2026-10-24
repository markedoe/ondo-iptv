#!/bin/sh
# The app's memory limit on the emulator (dalvik.vm.heapgrowthlimit), as a TV box sets it: e.g. 128m. The emulator
# keeps its own when adb cannot run as root; the limit in force is printed either way.
limit=$(printf '%s' "$1" | tr 'A-Z' 'a-z')
current=$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')
if [ -n "$limit" ] && [ "$limit" != "$current" ]; then
  if adb root 2>&1 | grep -qv "cannot run as root"; then
    sleep 3
    adb wait-for-device
    adb shell setprop dalvik.vm.heapgrowthlimit "$limit"
    # The apps' processes start from zygote, which reads the limit when it starts.
    adb shell stop
    adb shell start
    for _ in $(seq 1 90); do
      [ "$(adb shell getprop init.svc.bootanim | tr -d '\r')" = "stopped" ] && adb shell pm path android > /dev/null 2>&1 && break
      sleep 2
    done
    sleep 10
  fi
fi
echo "App memory limit: $(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r') (asked: ${limit:-none})"
