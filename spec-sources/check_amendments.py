#!/usr/bin/env python3
"""Mechanical validation of a curated amendments file (no judgment).

Usage: python3 -I check_amendments.py [amendments.json | amendments-proposed.json]

Checks, for every amendment:
  * structure, enum values, unique ids; a condition for 'modifies' and 'supersedes'
  * the newer-RFC quote appears verbatim (whitespace-insensitive) inside the cited lines of
    rfcNEWER-fixed.txt, and the target quote inside the cited lines of rfcOLD-fixed.txt
  * the target section exists in the older RFC
  * target.records are real keyword records of the older RFC that lie in the target section
and for the file as a whole: every candidate of amendment-candidates.json is covered exactly once
(by an amendment or by a not_amendments entry). Prints every problem; exit code 1 if any.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

FILE = next((a for a in sys.argv[1:] if a.endswith(".json")), "amendments.json")
FILE = FILE if os.path.isabs(FILE) else os.path.join(cfg.HERE, FILE)
RELATIONS = {"extends", "modifies", "supersedes"}
CONFIDENCE = {"high", "medium", "low"}
STATUS = {"proposed", "confirmed"}
NEWER, OLDER = {"9865", "9967"}, {"7643", "7644"}

data = json.load(open(FILE, encoding="utf-8"))
cands = {c["id"]: c for c in json.load(open(os.path.join(cfg.HERE, "amendment-candidates.json"), encoding="utf-8"))}
texts, norm_records, sections = {}, {}, {}
for rfc in NEWER | OLDER:
    texts[rfc] = open(cfg.paths(rfc)["fixed"], encoding="utf-8-sig").read().split("\n")
for rfc in OLDER:
    norm_records[rfc] = {r["n"]: r for r in json.load(open(cfg.paths(rfc)["normative"], encoding="utf-8"))}
    sections[rfc] = set()
    for no, l in enumerate(texts[rfc], 1):
        m = re.match(r"^((?:\d+|[A-Z])(?:\.\d+)*)\.\s{1,3}(\S.*)$", l.replace("\f", ""))
        if m and no > 150:
            sections[rfc].add(m.group(1))

errors = []


def err(where, msg):
    errors.append(f"{where}: {msg}")


def plain(s):
    s = re.sub(r"\s+", " ", s).strip()
    return re.sub(r"([A-Za-z0-9])- ([a-z])", r"\1-\2", s)


def quote_in(rfc, rng, quote):
    if not (isinstance(rng, list) and len(rng) == 2 and all(isinstance(x, int) for x in rng)):
        return "lines must be [first, last] integers"
    a, b = rng
    if not (1 <= a <= b <= len(texts[rfc])):
        return f"lines {rng} outside rfc{rfc}-fixed.txt"
    chunk = plain(" ".join(l.strip() for l in texts[rfc][a - 1:b] if not cfg.HEADER_RE.match(l.replace("\f", ""))))
    if plain(quote) not in chunk:
        return f"quote not found verbatim in rfc{rfc}-fixed.txt lines {a}-{b}: {quote[:70]!r}"
    return None


covered = {}
seen_ids = set()
for am in data.get("amendments", []):
    w = am.get("id", "?")
    if w in seen_ids:
        err(w, "duplicate id")
    seen_ids.add(w)
    for k in ("id", "candidates", "newer", "target", "relation", "condition", "effect", "confidence", "status"):
        if k not in am:
            err(w, f"missing field {k}")
    if am.get("relation") not in RELATIONS:
        err(w, f"relation {am.get('relation')!r} not in {sorted(RELATIONS)}")
    if am.get("confidence") not in CONFIDENCE:
        err(w, f"confidence {am.get('confidence')!r} invalid")
    if am.get("status") not in STATUS:
        err(w, f"status {am.get('status')!r} invalid")
    if am.get("relation") in ("modifies", "supersedes") and not (am.get("condition") or "").strip():
        err(w, f"relation {am.get('relation')} needs a non-empty condition")
    for cid in am.get("candidates", []):
        if cid not in cands:
            err(w, f"unknown candidate {cid}")
        covered.setdefault(cid, []).append(w)
    nw, tg = am.get("newer", {}), am.get("target", {})
    if nw.get("rfc") not in NEWER:
        err(w, f"newer.rfc {nw.get('rfc')!r} not in {sorted(NEWER)}")
    else:
        msg = quote_in(nw["rfc"], nw.get("lines"), nw.get("quote", ""))
        if msg:
            err(w, "newer " + msg)
    if tg.get("rfc") not in OLDER:
        err(w, f"target.rfc {tg.get('rfc')!r} not in {sorted(OLDER)}")
    else:
        if tg.get("section") not in sections[tg["rfc"]]:
            err(w, f"target section {tg.get('section')!r} does not exist in RFC {tg['rfc']}")
        msg = quote_in(tg["rfc"], tg.get("lines"), tg.get("quote", ""))
        if msg:
            err(w, "target " + msg)
        for n in tg.get("records", []):
            r = norm_records[tg["rfc"]].get(n)
            sec = tg.get("section", "")
            if r is None:
                err(w, f"target record {n} does not exist in RFC {tg['rfc']}")
            elif not (r["section"] == sec or r["section"].startswith(sec + ".")):
                err(w, f"target record {n} is in section {r['section']}, outside target section {sec}")
    # the amendment's candidates should concern the same newer RFC
    for cid in am.get("candidates", []):
        if cid in cands and nw.get("rfc") and cands[cid]["newer_rfc"] != nw["rfc"]:
            err(w, f"candidate {cid} is from RFC {cands[cid]['newer_rfc']} but newer.rfc is {nw['rfc']}")

for na in data.get("not_amendments", []):
    cid = na.get("candidate")
    if cid not in cands:
        err("not_amendments", f"unknown candidate {cid}")
    if not (na.get("reason") or "").strip():
        err("not_amendments", f"{cid} has no reason")
    covered.setdefault(cid, []).append("not_amendments")

for cid in cands:
    if cid not in covered:
        err("coverage", f"candidate {cid} is not covered")
    elif len(covered[cid]) > 1:
        err("coverage", f"candidate {cid} is covered more than once: {covered[cid]}")

rel = {}
for am in data.get("amendments", []):
    rel[am.get("relation")] = rel.get(am.get("relation"), 0) + 1
print(f"{os.path.basename(FILE)}: {len(data.get('amendments', []))} amendments {rel}, "
      f"{len(data.get('not_amendments', []))} not_amendments, {len(cands)} candidates")
for e in errors:
    print("  PROBLEM", e)
print("OK" if not errors else f"{len(errors)} problem(s)")
sys.exit(1 if errors else 0)
