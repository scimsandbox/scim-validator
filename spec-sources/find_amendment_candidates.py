#!/usr/bin/env python3
"""List the places where a newer RFC may amend an older one (deterministic, no judgment).

Usage: python3 -I find_amendment_candidates.py [newer RFCs, default 9865 9967]

For every newer RFC the script scans all paragraphs of rfcNNNN-fixed.txt (not only keyword
sentences, because amendments are often phrased without a keyword) and writes
amendment-candidates.json with three kinds of candidate:

  citation        a paragraph that cites a section of an older RFC ("Section 3.4.1 of [RFC7644]").
                  Each citation is resolved to the cited section of the older RFC and to the
                  keyword records of normative-rfcOLD.json inside that section.
  cue             a paragraph that mentions an older RFC and uses amendment wording ("updates",
                  "extends", "has been added", "IANA has amended" ...) without citing a section.
  name-collision  a subject name (label_path leaf) that appears in the newer RFC's records and
                  also in the older RFC's records (for example the query parameter "count").

Most candidates are plain references, not amendments. Deciding which are, and how, is a separate,
curated step (amendments.json, checked by check_amendments.py).
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

NEWER = [a for a in sys.argv[1:] if a.isdigit()] or ["9865", "9967"]
OLDER = ["7643", "7644"]
OUT = os.path.join(cfg.HERE, "amendment-candidates.json")

CITE = re.compile(
    r"Sections?\s+(?P<s1>\d[\d.]*(?:\s*(?:,|and)\s*\d[\d.]*)*)\s+of\s+\[(?P<r1>RFC\d+)\]"
    r"|\[(?P<r2>RFC\d+)\]\s+Section\s+(?P<s2>\d[\d.]*)"
    r"|Table\s+\d+\s+\([^)]*\)\s+of\s+Section\s+(?P<s3>\d[\d.]*)\s+of\s+\[(?P<r3>RFC\d+)\]")
MENTION = re.compile(r"\[(RFC7643|RFC7644)\]|\bRFCs?\s+7643\b|\bRFCs?\s+7644\b|\b7643 and 7644\b")
CUES = {
    "extends": r"\b(extend|extends|extended|in addition to|additional|adds?|added|new)\b",
    "modifies": r"\b(redefin\w*|relax\w*|instead of|overrid\w+|unless|only when|except)\b",
    "supersedes": r"\b(replac\w+|obsolet\w+|supersed\w+|no longer)\b",
    "declares": r"\b(updates|updated|amended?)\b",
}
STRONG = ("extends", "supersedes", "declares", "modifies")


def paragraphs(rfc):
    """(first line, last line, text) of every paragraph of the fixed text (page headers removed)."""
    lines = open(cfg.paths(rfc)["fixed"], encoding="utf-8-sig").read().split("\n")
    out, cur, start = [], [], 0
    for no, l in enumerate(lines, 1):
        if cfg.HEADER_RE.match(l.replace("\f", "")):
            continue
        if l.strip():
            if not cur:
                start = no
            cur.append((no, l.strip()))
        elif cur:
            out.append((start, cur[-1][0], re.sub(r"([A-Za-z0-9])- ([a-z])", r"\1-\2", " ".join(t for _, t in cur))))
            cur = []
    if cur:
        out.append((start, cur[-1][0], " ".join(t for _, t in cur)))
    return out


def section_headings(rfc):
    text = open(cfg.paths(rfc)["fixed"], encoding="utf-8-sig").read().split("\n")
    heads = {}
    for no, l in enumerate(text, 1):
        m = re.match(r"^((?:\d+|[A-Z])(?:\.\d+)*)\.\s{1,3}(\S.*)$", l.replace("\f", ""))
        if m and no > 150:
            heads[m.group(1)] = {"title": m.group(2).strip(), "line": no}
    return heads


def main():
    older = {r: json.load(open(cfg.paths(r)["normative"])) for r in OLDER}
    heads = {r: section_headings(r) for r in OLDER}
    cands, counter = [], {}

    def add(newer, kind, **kw):
        counter[newer] = counter.get(newer, 0) + 1
        cands.append({"id": f"C{newer}-{counter[newer]:03d}", "newer_rfc": newer, "kind": kind, **kw})

    for newer in NEWER:
        for a, b, p in paragraphs(newer):
            cites, seen = [], set()
            for m in CITE.finditer(p):
                rfc = (m.group("r1") or m.group("r2") or m.group("r3") or "")[3:]
                if rfc not in older:
                    continue
                for sec in re.findall(r"\d+(?:\.\d+)*", m.group("s1") or m.group("s2") or m.group("s3")):
                    if (rfc, sec) in seen:
                        continue
                    seen.add((rfc, sec))
                    recs = [r for r in older[rfc] if r["section"] == sec or r["section"].startswith(sec + ".")]
                    cites.append({
                        "rfc": rfc, "section": sec,
                        "section_exists": sec in heads[rfc],
                        "section_title": heads[rfc].get(sec, {}).get("title"),
                        "target_record_count": len(recs),
                        "target_records": [r["n"] for r in recs] if len(recs) <= 40 else "too many (cited section is broad)",
                    })
            cues = [k for k, rx in CUES.items() if re.search(rx, p, re.I)]
            if cites:
                add(newer, "citation", lines=[a, b], text=p, citations=cites, cues=cues)
            elif MENTION.search(p) and any(c in STRONG for c in cues):
                add(newer, "cue", lines=[a, b], text=p, cues=cues)
        mine = {}
        for r in json.load(open(cfg.paths(newer)["normative"])):
            if r["label_path"] and r["kind"] == "keyword":
                mine.setdefault(r["label_path"].split(".")[-1], []).append(r["n"])
        for rfc, recs in older.items():
            theirs = {}
            for r in recs:
                if r["label_path"] and r["kind"] == "keyword":
                    theirs.setdefault(r["label_path"].split(".")[-1], []).append(r["n"])
            for name in sorted(set(mine) & set(theirs)):
                add(newer, "name-collision", label=name, older_rfc=rfc,
                    newer_records=mine[name], older_records=theirs[name])

    json.dump(cands, open(OUT, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    kinds = {}
    for c in cands:
        kinds[(c["newer_rfc"], c["kind"])] = kinds.get((c["newer_rfc"], c["kind"]), 0) + 1
    print(f"{len(cands)} candidates -> {OUT}")
    for k, v in sorted(kinds.items()):
        print(f"  RFC {k[0]} {k[1]}: {v}")


if __name__ == "__main__":
    main()
