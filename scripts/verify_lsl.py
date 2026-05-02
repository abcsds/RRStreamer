#!/usr/bin/env python3
"""Resolve and pretty-print samples from RRStreamer LSL outlets.

Run on a host on the same network as the phone running RRStreamer:

    pip install pylsl
    python scripts/verify_lsl.py

The script accepts:
  * `HR <devicename>` — heart rate (bpm), always present.
  * `RR <devicename>` — true R-R intervals from ECG bands (Polar H10, etc.).
  * `PP <devicename>` — peak-to-peak intervals from PPG bands (Polar Verity
                       Sense, OH1). Same units as RR (ms) but lower-fidelity;
                       the app's UI marks RMSSD as approximate when the
                       source is PP.

Both interval kinds are forwarded as int32 milliseconds; the script labels
the output line with the actual stream-name prefix so you can tell at a
glance which path the device went down.
"""
from __future__ import annotations

import time
from typing import Dict

try:
    from pylsl import StreamInlet, resolve_streams
except ImportError as e:
    raise SystemExit("pylsl not installed. `pip install pylsl`") from e


def kind_of(name: str) -> str | None:
    """Return one of 'HR', 'RR', 'PP' for a recognised RRStreamer stream name."""
    for prefix in ("HR ", "RR ", "PP "):
        if name.startswith(prefix):
            return prefix.strip()
    return None


def main() -> None:
    print("Looking for HR/RR/PP LSL streams on the network (5 s)…")
    streams = resolve_streams(wait_time=5.0)
    inlets: Dict[str, StreamInlet] = {}
    for info in streams:
        if kind_of(info.name()):
            print(f"  + {info.name()} ({info.type()}, source={info.source_id()})")
            inlets[info.name()] = StreamInlet(info, max_buflen=60)

    if not inlets:
        raise SystemExit(
            "No RRStreamer streams found. Is the phone running RRStreamer "
            "and on the same network?"
        )

    print("Streaming. Ctrl+C to stop.\n")
    try:
        while True:
            for name, inlet in inlets.items():
                sample, ts = inlet.pull_sample(timeout=0.05)
                if sample is None:
                    continue
                kind = kind_of(name) or "??"
                value = sample[0]
                if kind == "HR":
                    print(f"[{ts:.3f}] HR  {value:>4} bpm   ({name})")
                else:  # RR or PP
                    print(f"[{ts:.3f}]    {kind} {value:>5} ms   ({name})")
            time.sleep(0.001)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
