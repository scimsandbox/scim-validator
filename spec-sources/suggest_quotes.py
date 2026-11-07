#!/usr/bin/env python3
"""Suggest, for every entry of a requirements YAML, the RFC sentences it most likely stands for.

Usage: python3 -I suggest_quotes.py [RFC number, default 7643]

Reads ../src/main/resources/spec/rfcNNNN-requirements.yaml (flat entries, parsed without PyYAML)
and normative-rfcNNNN.json, and scores every keyword record against each entry by word overlap
between the entry's title/note and the record's sentence, paragraph and label_path. Records in the
entry's own section (or below it) get a bonus, as does a matching level. Deterministic: same
input, same output. Writes quote-suggestions-rfcNNNN.json with the best candidates per entry and
a verdict:

  clear      one candidate clearly ahead of the rest (accept automatically after a spot check)
  ambiguous  several candidates close together (a person or subagent decides)
  weak       best candidate scores low (probably an implicit rule or a rule outside the extraction)

The script only suggests; it never edits the YAML.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

RFC = cfg.rfc_arg(sys.argv)
YAML = os.path.join(cfg.HERE, "..", "src", "main", "resources", "spec", f"rfc{RFC}-requirements.yaml")
OUT = os.path.join(cfg.HERE, f"quote-suggestions-rfc{RFC}.json")
STOP = set("""a an and are as at be been by can for from has have if in is it its may must not of on or
shall should that the their this to when which with without are rfc section per all any each other
than then these those use used using also only must response request server client value values
attribute attributes include included""".split())


def parse_yaml(path):
    entries, cur = [], None
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^- id:\s*(\S+)", line)
        if m:
            cur = {"id": m.group(1)}
            entries.append(cur)
            continue
        m = re.match(r"^  (\w+):\s*(.*?)\s*$", line)
        if m and cur is not None:
            v = m.group(2)
            if len(v) >= 2 and v[0] == v[-1] == '"':
                v = v[1:-1].replace('\\"', '"')
            cur[m.group(1)] = v
    return entries


def words(s):
    out = set()
    for w in re.findall(r"[A-Za-z][A-Za-z0-9$.]*", s):
        for part in re.sub(r"([a-z])([A-Z])", r"\1 \2", w).split():
            p = part.lower().strip(".")
            if len(p) > 2 and p not in STOP:
                out.add(p)
        out.add(w.lower().strip("."))
    return out


def in_section(rec_sec, sec):
    return rec_sec == sec or rec_sec.startswith(sec + ".")


def main():
    entries = parse_yaml(YAML)
    recs = [r for r in json.load(open(cfg.paths(RFC)["normative"], encoding="utf-8")) if r["kind"] == "keyword"]
    rwords = [words(" ".join([r.get("clause") or r["text"], r["label_path"], r["section_title"]])) for r in recs]
    out, verdicts = [], {"clear": 0, "ambiguous": 0, "weak": 0}
    for e in entries:
        ew = words(e.get("title", "") + " " + e.get("note", ""))
        scored = []
        for r, rw in zip(recs, rwords):
            common = ew & rw
            if not common:
                continue
            score = len(common) / (len(ew) ** 0.5 * len(rw) ** 0.5)
            if in_section(r["section"], e.get("section", "")):
                score += 0.25
            if r["level"] == e.get("level"):
                score += 0.05
            scored.append((score, r, sorted(common)))
        scored.sort(key=lambda t: -t[0])
        top = scored[:3]
        if not top or top[0][0] < 0.30:
            verdict = "weak"
        elif len(top) > 1 and top[0][0] - top[1][0] < 0.08:
            verdict = "ambiguous"
        else:
            verdict = "clear"
        verdicts[verdict] += 1
        out.append({
            "id": e["id"], "section": e.get("section"), "level": e.get("level"), "title": e.get("title"),
            "verdict": verdict,
            "candidates": [{
                "score": round(s, 3), "n": r["n"], "section": r["section"], "level": r["level"],
                "polarity": r["polarity"], "label_path": r["label_path"], "lines": r["lines"],
                "text": r.get("clause") or r["text"], "common_words": c[:8],
            } for s, r, c in top],
        })
    json.dump(out, open(OUT, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    print(f"{len(entries)} entries, {len(recs)} keyword records -> {OUT}")
    print("  " + ", ".join(f"{k}: {v}" for k, v in verdicts.items()))


if __name__ == "__main__":
    main()
