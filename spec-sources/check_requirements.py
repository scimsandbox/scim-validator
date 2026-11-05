#!/usr/bin/env python3
"""Mechanical validation of requirements-rfcNNNN.yaml against the RFC text (no judgment).

Usage: python3 -I check_requirements.py [RFC number, default 7643] [file.yaml]
(default file: src/main/resources/spec/rfcNNNN-requirements.yaml)

Per entry: id unique and of the form RFC<N>-<section>-<hash>, with the entry's own section and a hash
that is recomputed from rfc, section, subject and quote (generate_requirements.digest); area, capability and
disposition from the allowed vocabularies and equal to what the rules give; the quote appears verbatim (whitespace/hyphen-wrap insensitive) at `lines` of rfcNNNN-fixed.txt; the
quote contains the keyword that gives `level` and `polarity`; `record` exists in
normative-rfcNNNN.json and agrees on section/level/polarity; `original_lines` and `errata` agree
with the record; amended_by ids exist. For the file: every keyword record is covered exactly once.
Prints every problem; exit code 1 if any.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402
from generate_requirements import digest  # noqa: E402

RFC = cfg.rfc_arg(sys.argv)
FILE = next((a for a in sys.argv[1:] if a.endswith(".yaml")), cfg.catalog_path(RFC))
FILE = FILE if os.path.isabs(FILE) else os.path.join(cfg.HERE, FILE)
LEVEL = {"MUST": ("MUST", "positive"), "SHALL": ("MUST", "positive"), "REQUIRED": ("MUST", "positive"),
         "MUST NOT": ("MUST", "negative"), "SHALL NOT": ("MUST", "negative"),
         "SHOULD": ("SHOULD", "positive"), "RECOMMENDED": ("SHOULD", "positive"),
         "SHOULD NOT": ("SHOULD", "negative"), "NOT RECOMMENDED": ("SHOULD", "negative"),
         "MAY": ("MAY", "positive"), "OPTIONAL": ("MAY", "positive")}
KW = re.compile(r"\b(MUST NOT|SHALL NOT|SHOULD NOT|NOT RECOMMENDED|MUST|SHALL|REQUIRED|SHOULD|RECOMMENDED|MAY|OPTIONAL)\b")


def parse(path):
    out, cur = [], None
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        m = re.match(r"^- id:\s*(\S+)", line)
        if m:
            cur = {"id": m.group(1)}
            out.append(cur)
            continue
        m = re.match(r"^  (\w+):\s*(.*?)\s*$", line)
        if m and cur is not None:
            cur[m.group(1)] = json.loads(m.group(2))
    return out


def column_texts(chunk_lines):
    """Text of every table column (cells of one column joined across the lines)."""
    cols = {}
    for l in chunk_lines:
        if l.strip().startswith("|"):
            for i, cell in enumerate(l.strip().strip("|").split("|")):
                cols.setdefault(i, []).append(cell.strip())
    return [" ".join(c) for c in cols.values()]


def plain(s):
    s = re.sub(r"\s+", " ", s).strip()
    return re.sub(r"([A-Za-z0-9])- ([a-z])", r"\1-\2", s)


errors = []


def err(w, msg):
    errors.append(f"{w}: {msg}")


def main():
    text = open(cfg.paths(RFC)["fixed"], encoding="utf-8-sig").read().split("\n")
    recs = {r["n"]: r for r in json.load(open(cfg.paths(RFC)["normative"], encoding="utf-8")) if r["kind"] == "keyword"}
    am_path = os.path.join(cfg.HERE, "amendments.json")
    amids = {a["id"] for a in json.load(open(am_path, encoding="utf-8"))["amendments"]} if os.path.exists(am_path) else set()
    entries = parse(FILE)
    seen, covered, occ = set(), {}, {}
    for e in entries:
        w = e["id"]
        if w in seen:
            err(w, "duplicate id")
        seen.add(w)
        m = re.fullmatch(rf"RFC{RFC}-(.+)-([0-9a-f]{{6,}})", w)
        if not m or m.group(1) != e.get("section"):
            err(w, f"id does not have the form RFC{RFC}-<section>-<hash> with section {e.get('section')!r}")
        else:
            key = (e.get("section"), e.get("subject", ""), plain(e.get("quote", "")))
            occ[key] = occ.get(key, 0) + 1
            want = digest(RFC, e["section"], e.get("subject", ""), e.get("quote", ""), occ[key])
            if not want.startswith(m.group(2)):
                err(w, f"id hash {m.group(2)} does not match the entry's content (expected {want[:len(m.group(2))]})")
        if e.get("capability") not in cfg.CAPABILITIES:
            err(w, f"capability {e.get('capability')!r} not in {cfg.CAPABILITIES}")
        elif e.get("section") and e["capability"] != cfg.capability_of(RFC, e["section"], e.get("subject", "")):
            err(w, f"capability {e['capability']!r} differs from the rule for section {e['section']} subject {e.get('subject', '')!r} "
                   f"({cfg.capability_of(RFC, e['section'], e.get('subject', ''))!r})")
        if e.get("area") not in cfg.AREAS:
            err(w, f"area {e.get('area')!r} not in {cfg.AREAS}")
        elif e.get("section") and e["area"] != cfg.area_of(RFC, e["section"]):
            err(w, f"area {e['area']!r} differs from the rule for section {e['section']} ({cfg.area_of(RFC, e['section'])!r})")
        if e.get("disposition") == "na":
            if e.get("reason") not in cfg.NA_REASONS:
                err(w, f"disposition na needs a reason from {cfg.NA_REASONS}, got {e.get('reason')!r}")
        elif "reason" in e:
            err(w, "reason is only allowed with disposition na")
        if e.get("disposition") not in cfg.DISPOSITIONS:
            err(w, f"disposition {e.get('disposition')!r} not in {cfg.DISPOSITIONS}")
        for k in ("rfc", "section", "subject", "level", "polarity", "quote", "lines", "record", "area", "capability", "disposition"):
            if k not in e:
                err(w, f"missing field {k}")
        if e.get("rfc") != RFC:
            err(w, f"rfc {e.get('rfc')!r} != {RFC}")
        a, b = (e.get("lines") or [0, 0])[:2]
        if not (1 <= a <= b <= len(text)):
            err(w, f"lines {e.get('lines')} outside rfc{RFC}-fixed.txt")
        else:
            body = [l for l in text[a - 1:b] if not cfg.HEADER_RE.match(l.replace("\f", ""))]
            if e.get("in_table"):
                chunks = [plain(c) for c in column_texts(body)]
            else:
                chunks = [plain(" ".join(l.strip() for l in body))]
            if not any(plain(e.get("quote", "")) in c for c in chunks):
                err(w, f"quote not found verbatim at lines {a}-{b}: {e.get('quote', '')[:60]!r}")
        kws = KW.findall(e.get("quote", ""))
        if not kws:
            err(w, "quote contains no upper-case keyword")
        elif LEVEL[kws[0]] != (e.get("level"), e.get("polarity")):
            err(w, f"first keyword {kws[0]!r} gives {LEVEL[kws[0]]}, entry says {(e.get('level'), e.get('polarity'))}")
        r = recs.get(e.get("record"))
        if r is None:
            err(w, f"record {e.get('record')} is not a keyword record of RFC {RFC}")
        else:
            covered.setdefault(r["n"], []).append(w)
            for k in ("section", "level", "polarity"):
                if r[k] != e.get(k):
                    err(w, f"{k} {e.get(k)!r} differs from record {r['n']} ({r[k]!r})")
            if r["original_lines"] != e.get("original_lines"):
                err(w, f"original_lines {e.get('original_lines')} differ from record ({r['original_lines']})")
            if bool(r.get("in_table")) != bool(e.get("in_table")):
                err(w, "in_table flag differs from record")
            if bool(r.get("errata")) != bool(e.get("errata")):
                err(w, "errata flag differs from record")
        for x in e.get("amended_by", []):
            if x not in amids:
                err(w, f"unknown amendment {x}")
    for n in recs:
        if n not in covered:
            err("coverage", f"record {n} (section {recs[n]['section']}) is not covered")
        elif len(covered[n]) > 1:
            err("coverage", f"record {n} is covered more than once: {covered[n]}")
    print(f"{os.path.basename(FILE)}: {len(entries)} entries, {len(recs)} keyword records")
    for x in errors:
        print("  PROBLEM", x)
    print("OK" if not errors else f"{len(errors)} problem(s)")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
