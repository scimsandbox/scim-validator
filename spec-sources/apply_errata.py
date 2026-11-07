#!/usr/bin/env python3
"""Apply RFC errata to an RFC text, producing a corrected copy plus a report and a line map.

Usage: python3 -I apply_errata.py [RFC number, default 7643] [--status Verified[,Held for Document Update]]
Reads rfcNNNN.txt and rfcNNNN-errata.json (see fetch_sources.py).

Only errata whose status is listed (default: Verified) are applied. An erratum is applied only
if every changed segment of its "original text" is found exactly once in the RFC (whitespace-
insensitive, page headers/footers ignored). Anything else is skipped and reported, never guessed.

Segments: errata text may contain "---" lines (several places), lines containing only "..."
(elided text) and "Section N." context labels that are not in the RFC. The labels are dropped
and the text is split at the other separators; original and corrected text must have the same
number of segments. Segments that are identical in both are ignored.

How a segment is applied:
  * It covers whole lines  -> those lines are replaced by the erratum's corrected lines.
  * It starts/ends mid-line -> only the words that differ are changed in place, so the RFC's
    layout is otherwise untouched.

Outputs:
  rfcNNNN-fixed.txt             corrected text (a plain copy when nothing is applied)
  errata-applied-rfcNNNN.json   applied edits (original and fixed line ranges), skipped errata, line map
  errata-report-rfcNNNN.md      before/after diff of every applied edit, and why others were skipped
"""
import difflib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

args = sys.argv[1:]
STATUSES = {"Verified"}
if "--status" in args:
    i = args.index("--status")
    STATUSES = {s.strip() for s in args[i + 1].split(",")}
    del args[i:i + 2]
RFC = cfg.rfc_arg(args)
P = cfg.paths(RFC)
SRC, ERR, DST = P["text"], P["errata"], P["fixed"]
APPLIED, REPORT = P["applied"], P["report"]

HEADER_RE = cfg.HEADER_RE
PART_SEP = re.compile(r"\r?\n---\r?\n")
ELIDE_SEP = re.compile(r"\r?\n(?:[ \t]*\r?\n)*[ \t]*\.\.\.[ \t]*\r?\n(?:[ \t]*\r?\n)*")
CONTEXT_LABEL = re.compile(r"^Section \d+(\.\d+)*\.\s")


def segments(text):
    """List of parts (split at '---'); each part is a list of segments (split at '...')."""
    parts = []
    for part in PART_SEP.split(text.strip("\r\n")):
        kept = [ln for ln in part.split("\n") if not CONTEXT_LABEL.match(ln.strip("\r"))]
        parts.append([seg.strip("\r\n") for seg in ELIDE_SEP.split("\n".join(kept).strip("\r\n"))])
    return parts


def indent_of(s):
    return len(s) - len(s.lstrip(" "))


def reindent(corrected, original, rfc_lines):
    """Corrected lines with the RFC's indentation restored.

    Errata text is copied from a rendered page and often loses indentation (usually on the first
    line only, sometimes on every line). The offset is measured between the erratum's original
    text and the RFC's own lines: separately for the first line and for the rest.
    """
    o_lines = [ln.rstrip("\r") for ln in original.split("\n") if ln.strip()]
    c_lines = [ln.rstrip() for ln in corrected.replace("\r\n", "\n").split("\n")]
    if not o_lines or not rfc_lines:
        return c_lines
    d_first = indent_of(rfc_lines[0]) - indent_of(o_lines[0])
    d_rest = d_first
    if len(o_lines) == len(rfc_lines) and len(o_lines) > 1:
        d_rest = indent_of(rfc_lines[1]) - indent_of(o_lines[1])
    out, first = [], True
    for ln in c_lines:
        if ln.strip():
            ln = " " * max(0, indent_of(ln) + (d_first if first else d_rest)) + ln.lstrip(" ")
            first = False
        out.append(ln)
    return out


def main():
    lines = open(SRC, encoding="utf-8-sig").read().split("\n")
    # Token stream over non-header lines: (token, line_index, col_start, col_end)
    toks = []
    for i, line in enumerate(lines):
        if HEADER_RE.match(line.replace("\f", "")):
            continue
        for m in re.finditer(r"\S+", line):
            toks.append((m.group(), i, m.start(), m.end()))
    words = [t[0] for t in toks]
    first_in_line = {k for k, t in enumerate(toks) if k == 0 or toks[k - 1][1] != t[1]}
    last_in_line = {k for k, t in enumerate(toks) if k == len(toks) - 1 or toks[k + 1][1] != t[1]}

    def find(seg):
        want = seg.split()
        if not want:
            return None, "empty segment"
        hits = [s for s in range(len(words) - len(want) + 1) if words[s:s + len(want)] == want]
        if not hits:
            return None, "original text not found"
        if len(hits) > 1:
            return None, f"original text found {len(hits)} times"
        s = hits[0]
        return (s, s + len(want) - 1), None

    errata = [e for e in json.load(open(ERR, encoding="utf-8")) if e["errata_status_code"] in STATUSES]
    errata.sort(key=lambda e: int(e["errata_id"]))
    edits, skipped = [], []
    for e in errata:
        eid = e["errata_id"]
        o_parts, c_parts = segments(e["orig_text"]), segments(e["correct_text"])
        if len(o_parts) != len(c_parts):
            skipped.append({"id": eid, "section": e["section"], "reason": "part count differs"})
            continue
        planned = []
        for pi, (o_segs, c_segs) in enumerate(zip(o_parts, c_parts), 1):
            tag = f" (part {pi} of {len(o_parts)})" if len(o_parts) > 1 else ""
            if len(o_segs) != len(c_segs):
                skipped.append({"id": eid, "section": e["section"], "reason": "segment count differs" + tag})
                continue
            part_plan, why = [], None
            for o, c in zip(o_segs, c_segs):
                if o.split() == c.split():
                    continue  # unchanged segment (context only)
                rng, why = find(o)
                if rng is None:
                    break
                s, t = rng
                part_plan.append((s, t, o, c, s in first_in_line and t in last_in_line))
            if why:
                skipped.append({"id": eid, "section": e["section"], "reason": why + tag})
                continue
            planned.extend(part_plan)
        for s, t, o, c, whole in planned:
            a, b = toks[s][1], toks[t][1]
            ed = {"id": eid, "status": e["errata_status_code"], "section": e["section"],
                  "type": e["errata_type_code"], "kind": "lines" if whole else "words"}
            if whole:
                ed["orig"] = [a, b]
                ed["new"] = reindent(c, o, [ln for ln in lines[a:b + 1]
                                            if ln.strip() and not HEADER_RE.match(ln.replace("\f", ""))])
            else:
                ed["span"] = (s, t)
                ed["ctoks"] = c.split()
                ed["orig"] = [a, b]
            edits.append(ed)

    edits.sort(key=lambda x: x["orig"][0])
    for prev, cur in zip(edits, edits[1:]):
        if cur["orig"][0] <= prev["orig"][1]:
            raise SystemExit(f"errata {prev['id']} and {cur['id']} overlap at lines "
                             f"{prev['orig'][0] + 1}-{cur['orig'][1] + 1}; resolve manually")

    out = list(lines)
    # 1. word-level patches (line count unchanged); edited line indices are recorded.
    for ed in edits:
        if ed["kind"] != "words":
            continue
        s, t = ed["span"]
        otoks, ctoks = [x[0] for x in toks[s:t + 1]], ed["ctoks"]
        ops = []  # (line, col_start, col_end, replacement)
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, otoks, ctoks, autojunk=False).get_opcodes():
            if tag == "equal":
                continue
            if tag == "insert":
                if i1 < len(otoks):
                    _, ln, c0, _ = toks[s + i1]
                    ops.append((ln, c0, c0, " ".join(ctoks[j1:j2]) + " "))
                else:
                    _, ln, _, c1 = toks[s + i1 - 1]
                    ops.append((ln, c1, c1, " " + " ".join(ctoks[j1:j2])))
                continue
            for k in range(i1, i2):
                _, ln, c0, c1 = toks[s + k]
                if tag == "replace" and (i2 - i1) == (j2 - j1):
                    rep = ctoks[j1 + (k - i1)]
                elif tag == "replace" and k == i1:
                    rep = " ".join(ctoks[j1:j2])
                else:
                    rep = ""
                ops.append((ln, c0, c1, rep))
        changed = sorted({o[0] for o in ops})
        for ln, c0, c1, rep in sorted(ops, key=lambda o: (o[0], -o[1])):
            out[ln] = out[ln][:c0] + rep + out[ln][c1:]
        ed["orig"] = [changed[0], changed[-1]]
        ed["edited_lines"] = changed
    # 2. whole-line replacements, bottom-up
    for ed in sorted((e for e in edits if e["kind"] == "lines"), key=lambda x: -x["orig"][0]):
        a, b = ed["orig"]
        out[a:b + 1] = ed["new"]
    # fixed line ranges (1-based)
    shift = 0
    for ed in edits:
        a, b = ed["orig"]
        if ed["kind"] == "lines":
            ed["fixed_lines"] = [a + shift + 1, a + shift + len(ed["new"])]
            shift += len(ed["new"]) - (b - a + 1)
        else:
            ed["fixed_lines"] = [a + shift + 1, b + shift + 1]
        ed["orig_lines"] = [a + 1, b + 1]
        # Lines whose content really changed (ignoring indentation, blank and page-header lines).
        if ed["kind"] == "lines":
            key = lambda ls: [" ".join(x.split()) for x in ls]
            ed["changed_fixed"] = []
            for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(
                    None, key(lines[a:b + 1]), key(ed["new"]), autojunk=False).get_opcodes():
                if tag in ("replace", "insert"):
                    ed["changed_fixed"].append([ed["fixed_lines"][0] + j1, ed["fixed_lines"][0] + j2 - 1])
        else:
            ed["changed_fixed"] = [[ln + shift + 1, ln + shift + 1] for ln in ed["edited_lines"]]
    open(DST, "w", encoding="utf-8").write("\n".join(out))

    # Line map: fixed line (1-based) -> original line (1-based); null for replaced lines.
    line_map, oi = [], 0
    for ed in (e for e in edits if e["kind"] == "lines"):
        a, b = ed["orig"]
        while oi < a:
            line_map.append(oi + 1)
            oi += 1
        line_map.extend([None] * len(ed["new"]))
        oi = b + 1
    while oi < len(lines):
        line_map.append(oi + 1)
        oi += 1

    json.dump({
        "statuses": sorted(STATUSES),
        "applied": [{k: ed[k] for k in ("id", "status", "section", "type", "kind", "orig_lines", "fixed_lines",
                                                      "changed_fixed")}
                    for ed in edits],
        "skipped": skipped,
        "fixed_to_original": line_map,
    }, open(APPLIED, "w", encoding="utf-8"), ensure_ascii=False)

    with open(REPORT, "w", encoding="utf-8") as r:
        r.write(f"# Errata applied to {os.path.basename(SRC)}\n\nStatuses applied: {', '.join(sorted(STATUSES))}. "
                f"Applied edits: {len(edits)} from {len({e['id'] for e in edits})} errata. "
                f"Skipped: {len(skipped)}.\n\n")
        for s in skipped:
            r.write(f"- skipped erratum {s['id']} (section {s['section']}): {s['reason']}\n")
        for ed in edits:
            a, b = ed["orig"]
            fa, fb = ed["fixed_lines"]
            diff = difflib.unified_diff(lines[a:b + 1], out[fa - 1:fb], "original", "fixed", lineterm="", n=0)
            r.write(f"\n## Erratum {ed['id']} ({ed['status']}, {ed['type']}, {ed['kind']}) section "
                    f"{ed['section']}, original lines {a + 1}-{b + 1}\n\n```diff\n" + "\n".join(diff) + "\n```\n")

    ids = sorted({e["id"] for e in edits})
    print(f"applied {len(edits)} edits from {len(ids)} errata: {ids}")
    print(f"skipped {len(skipped)}: {[(s['id'], s['reason']) for s in skipped]}")
    print(f"lines: original {len(lines)} -> fixed {len(out)}")


if __name__ == "__main__":
    main()
