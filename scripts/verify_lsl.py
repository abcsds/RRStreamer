#!/usr/bin/env python3
"""Resolve and pretty-print samples from any 'HR ' / 'RR ' LSL marker streams.

Run on a host on the same network as the phone running RRStreamer:

    pip install pylsl
    python scripts/verify_lsl.py

The script prints each HR sample (bpm) and each RR interval as it arrives.
"""
from __future__ import annotations

import time
from typing import Dict

try:
    from pylsl import StreamInlet, resolve_streams
except ImportError as e:
    raise SystemExit("pylsl not installed. `pip install pylsl`") from e


def matches(name: str) -> str | None:
    if name.startswith("HR "):
        return "HR"
    if name.startswith("RR "):
        return "RR"
    return None


def main() -> None:
    print("Looking for HR/RR LSL streams on the network (5s)…")
    streams = resolve_streams(wait_time=5.0)
    inlets: Dict[str, StreamInlet] = {}
    for info in streams:
        kind = matches(info.name())
        if kind:
            print(f"  + {info.name()} ({info.type()}, source={info.source_id()})")
            inlets[info.name()] = StreamInlet(info, max_buflen=60)

    if not inlets:
        raise SystemExit("No HR/RR streams found. Is the phone running RRStreamer and on the same network?")

    print("Streaming. Ctrl+C to stop.\n")
    try:
        while True:
            for name, inlet in inlets.items():
                sample, ts = inlet.pull_sample(timeout=0.05)
                if sample is None:
                    continue
                kind = matches(name)
                value = sample[0]
                if kind == "HR":
                    print(f"[{ts:.3f}] HR {value:>3} bpm   ({name})")
                else:
                    print(f"[{ts:.3f}]    RR {value:>4}     ({name})")
            time.sleep(0.001)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
