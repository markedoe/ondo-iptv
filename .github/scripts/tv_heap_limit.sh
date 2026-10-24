#!/bin/sh
# The app's memory limit on the emulator (dalvik.vm.heapgrowthlimit), as a TV box sets it: e.g. 128m. It can only be
# set when adb runs as root, which the Android TV image usually refuses: the emulator then keeps its own. The limit in
# force is printed, and whether the one asked was applied; the run goes on either way.
limit=$(printf '%s' "$1" | tr 'A-Z' 'a-z')
in_force() { adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r'; }
reason="the emulator kept its own"
if [ -n "$limit" ] && [ "$limit" != "$(in_force)" ]; then
  root=$(adb root 2>&1)
  case "$root" in
    "" | *"cannot run as root"*)
      reason="adb cannot run as root"
      ;;
    *)
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
      ;;
  esac
fi
now=$(in_force)
if [ -z "$limit" ]; then
  echo "App memory limit: $now (none asked)"
elif [ "$now" = "$limit" ]; then
  echo "App memory limit: $now, as asked"
else
  echo "App memory limit: $now — $limit asked, not applied ($reason)"
fi
