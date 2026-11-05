#!/usr/bin/env python3
"""Download RFC texts and slice the RFC Editor errata feed, one RFC at a time.

Usage: python3 -I fetch_sources.py 7643 7644 9865 9967 [--force]

For every RFC number NNNN writes
  rfcNNNN.txt          https://www.rfc-editor.org/rfc/rfcNNNN.txt  (byte-for-byte)
  rfcNNNN-errata.json  entries of https://www.rfc-editor.org/api/v1/errata.json whose doc-id is RFCNNNN
An existing file is not overwritten (the script says whether the download is identical to it);
use --force to replace it. The errata feed is large (about 11 MB) and is downloaded once.
"""
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

TEXT_URL = "https://www.rfc-editor.org/rfc/rfc{}.txt"
ERRATA_URL = "https://www.rfc-editor.org/api/v1/errata.json"


def get(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def store(path, data, force):
    if os.path.exists(path):
        old = open(path, "rb").read()
        if old == data:
            return "unchanged"
        if not force:
            return "DIFFERS from existing file (kept; use --force to replace)"
    open(path, "wb").write(data)
    return "written"


def main():
    force = "--force" in sys.argv
    rfcs = [a for a in sys.argv[1:] if a.isdigit()]
    if not rfcs:
        raise SystemExit(__doc__)
    errata = json.loads(get(ERRATA_URL))
    for rfc in rfcs:
        p = cfg.paths(rfc)
        print(f"RFC {rfc}: text {store(p['text'], get(TEXT_URL.format(rfc)), force)}", end="; ")
        mine = [e for e in errata if str(e.get("doc-id", "")).upper() == f"RFC{rfc}"]
        data = json.dumps(mine, indent=2).encode("utf-8")
        print(f"errata ({len(mine)}) {store(p['errata'], data, force)}")


if __name__ == "__main__":
    main()
