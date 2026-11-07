#!/usr/bin/env python3
"""Deterministic sanity checks for normative-rfcNNNN.json (no model involved).

Usage: python3 -I check_extraction.py [RFC number, default 7643]
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

RFC = cfg.rfc_arg(sys.argv[1:])
P = cfg.paths(RFC)
print(f"RFC {RFC}")

text = open(P["fixed"], encoding="utf-8-sig").read()
records = json.load(open(P["normative"], encoding="utf-8"))
lines = text.split("\n")

# 1. Keyword completeness (raw upper-case keyword count vs captured)
KW = r"\b(MUST NOT|SHALL NOT|SHOULD NOT|NOT RECOMMENDED|MUST|REQUIRED|SHALL|SHOULD|RECOMMENDED|MAY|OPTIONAL)\b"
raw = len(re.findall(KW, text))
unattached = json.load(open(P["unattached"], encoding="utf-8"))
in_records = sum(len(r["keywords"]) for r in records)
in_examples = sum(len(e["keywords"]) for r in records for e in r.get("schema_examples", []))
in_unattached = sum(len(u["keywords"]) for u in unattached)
got = in_records + in_examples + in_unattached
print(f"[keywords] raw={raw} captured={got} (records {in_records} + attached examples {in_examples} "
      f"+ unattached examples {in_unattached}) -> {'OK' if raw == got else 'MISMATCH'}")

# 2. Section coverage: table-of-contents headings vs section headings present in the body
toc, toc_end = {}, 0
for no, l in enumerate(lines, 1):
    m = re.match(r"^\s*((?:\d+|[A-Z])(?:\.\d+)*)\.\s+(.+?)\s*(?:\.{2,}|(?:\. ){2,}\.?)\s*\d+\s*$", l)
    if m:
        toc[m.group(1)] = m.group(2)
        toc_end = no
if not toc:  # newer RFCs list the TOC without page numbers: read between the heading and section 1
    start = next((i for i, l in enumerate(lines) if l.strip() == "Table of Contents"), None)
    if start is not None:
        for i in range(start + 1, len(lines)):
            if re.match(r"^\d+\.\s", lines[i]):  # flush-left "1.  Introduction" starts the body
                toc_end = i
                break
            m = re.match(r"^\s+((?:\d+|[A-Z])(?:\.\d+)*)\.\s+(\S.*)$", lines[i])
            if m:
                toc[m.group(1)] = m.group(2)
body_secs = {}
for no, l in enumerate(lines, 1):
    m = re.match(r"^((?:\d+|[A-Z])(?:\.\d+)*)\.\s{1,3}(\S.*)$", l.replace("\f", ""))
    if m and no > toc_end:
        body_secs[m.group(1)] = no
toc_depth = max((k.count(".") for k in toc), default=0)  # deeper headings are not in the TOC by design
missing_in_body = sorted(set(toc) - set(body_secs))
extra_in_body = sorted(k for k in set(body_secs) - set(toc) if k.count(".") <= toc_depth)
print(f"[sections] toc={len(toc)} body={len(body_secs)}; in TOC but no body heading: {missing_in_body}; "
      f"body heading not in TOC: {extra_in_body}")
rec_secs = {r["section"] for r in records}
unknown = sorted(s for s in rec_secs if s not in toc and s != "front" and s.count(".") <= toc_depth)
print(f"[sections] record sections not in TOC: {unknown}")

# 3. Sentence sanity
no_term = [r for r in records if not re.search(r"[.:;!?\")]$", r["text"])]
long_ = [r for r in records if len(r["text"]) > 600]
print(f"[sentences] no terminator: {[r['n'] for r in no_term]}; >600 chars: {[r['n'] for r in long_]}")

# 4. Line numbers point at the sentence (first 4 words)
bad = []
for r in records:
    a, b = r["lines"]
    chunk = re.sub(r"\s+", " ", " ".join(l.strip() for l in lines[a - 1:b]))
    chunk = re.sub(r"([A-Za-z0-9])- ([a-z])", r"\1-\2", chunk)  # words wrapped at their hyphen
    if r.get("in_table"):  # cell text is interleaved with other columns: check the words separately
        if not all(all(part in chunk for part in w.split("-") if part) for w in r["text"].split()[:4]):
            bad.append(r["n"])
    elif " ".join(r["text"].split()[:4]) not in chunk:
        bad.append(r["n"])
print(f"[lines] records whose cited lines do not contain the sentence start: {bad}")

# 5. Attached examples cite lines that really contain their text
bad_ex = []
for r in records:
    for e in r.get("schema_examples", []):
        a, b = e["lines"]
        chunk = re.sub(r"\s+", " ", " ".join(l.strip() for l in lines[a - 1:b]))
        if " ".join(e["text"].split()[:3]) not in chunk:
            bad_ex.append((r["n"], e["path"]))
print(f"[examples] attached examples whose cited lines do not contain the text: {bad_ex}")
