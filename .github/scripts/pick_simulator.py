"""Prints the UDID of the newest available simulator of a kind.

Kinds: `iphone` (default, Pro models first), `iphone-max` (6.9" Pro Max, App Store screenshots),
`ipad-13` (13" iPad Pro, App Store screenshots)."""
import json
import sys

kind = sys.argv[1] if len(sys.argv) > 1 else "iphone"
devices = json.load(sys.stdin)["devices"]
runtimes = sorted((name for name in devices if "iOS" in name), reverse=True)

if kind == "iphone-max":
    tests = [lambda n: n.startswith("iPhone") and n.endswith("Pro Max")]
elif kind == "ipad-13":
    tests = [lambda n: n.startswith("iPad Pro") and "13-inch" in n]
else:
    tests = [lambda n: n.startswith("iPhone") and "Pro" in n, lambda n: n.startswith("iPhone")]

for test in tests:
    for runtime in runtimes:
        for device in devices[runtime]:
            if test(device["name"]):
                print(device["udid"])
                sys.exit(0)
sys.exit(f"no {kind} simulator available")
