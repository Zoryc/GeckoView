#!/usr/bin/env python3
"""Summarizes the field-test site log per test step: which resources were fetched, with what result.

Usage: summarize.py <site.log>. Steps are delimited by the driver's /__mark requests.
"""
import collections
import json
import sys


def main(path):
    step = "start"
    counts = collections.OrderedDict()
    for line in open(path):
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if entry["path"] == "/__mark":
            step = entry["reason"].replace("MARK ", "")
            counts.setdefault(step, collections.Counter())
            continue
        parts = entry["path"].strip("/").split("/")
        # Segments and playlists are grouped by rendition directory (hls/360p/...).
        resource = "/".join(parts[:2]) + ("/*" if len(parts) > 2 else "") if parts and parts[0] == "hls" else entry["path"]
        key = f'{entry["host"]:5} {resource:22} {entry["status"]} {entry["reason"] or ""}'.rstrip()
        counts.setdefault(step, collections.Counter())[key] += 1
    for step, counter in counts.items():
        print(f"== {step}")
        for key, n in counter.items():
            print(f"   {n:3d} x {key}")


if __name__ == "__main__":
    main(sys.argv[1])
