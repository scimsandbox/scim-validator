#!/usr/bin/env python3
"""Generate the requirements catalog of one RFC from its normative JSON (deterministic).

Usage: python3 -I generate_requirements.py [RFC number, default 7643]

Writes src/main/resources/spec/rfcNNNN-requirements.yaml (shipped in the jar): one entry per `kind: keyword` record of
normative-rfcNNNN.json, in document order. Nothing is written by hand or by a model.

Entry fields
  id           RFC<NNNN>-<section>-<hash>: the first 6 hex digits of sha256(rfc|section|subject|normalized quote)
               (longer only if two quotes of one section collide). It does not change when other sentences
               move; it changes only if the sentence's own wording changes, which is when its test must be
               reviewed.
  rfc, section
  subject      label_path (attribute, parameter, ...), empty if the RFC text names none
  level        MUST | SHOULD | MAY      polarity  positive | negative
  quote        the sentence (or, for a mixed sentence, the clause) verbatim from rfcNNNN-fixed.txt
  summary      self-contained 1-sentence normative summary (preserved across regenerations by id)
  lines        [first, last] in rfcNNNN-fixed.txt; original_lines: the same in the untouched rfcNNNN.txt
  record       n of the record in normative-rfcNNNN.json
  in_table     (only if true) the quote is a cell of an ASCII table; `lines` span the table row
  errata       (only if present) Verified errata that changed this record's wording
  amended_by   (only if present) ids of amendments.json entries that target this record
  area         what the requirement is about (schema, query, patch, ...): groups tests into spec classes;
               derived from the section by the rules in rfc_config.py
  capability   server feature a test needs (core, filter, patch, ...): derived from the section by
               the rules in rfc_config.py, an approximate grouping
  disposition  test-planning state, NOT in the RFC: starts as "unassigned"; then tested | optional | na | gap.
               The one hand-set field: regenerating keeps it by id (a warning lists ids that vanished).
  reason       (only for disposition na) client-side | not-observable | process | deployment

check_requirements.py recomputes every id from its quote.
"""
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

RFC = cfg.rfc_arg(sys.argv)
OUT = cfg.catalog_path(RFC)


def q(s):
    """YAML double-quoted scalar (JSON string syntax is valid YAML)."""
    return json.dumps(s, ensure_ascii=False)


def digest(rfc, section, subject, quote, occurrence=1):
    """Hex digest of rfc, section, subject (label_path) and normalized quote; the id uses a prefix.
    Bare fragments such as "REQUIRED." occur under many subjects, so the subject is part of the
    digest; if even that repeats within a section, the occurrence number (2, 3, ...) is added."""
    norm = re.sub(r"\s+", " ", quote).strip()
    extra = f"|#{occurrence}" if occurrence > 1 else ""
    return hashlib.sha256(f"{rfc}|{section}|{subject}|{norm}{extra}".encode("utf-8")).hexdigest()


def amended_by(rfc):
    path = os.path.join(cfg.HERE, "amendments.json")
    if not os.path.exists(path):
        return {}
    out = {}
    for am in json.load(open(path, encoding="utf-8"))["amendments"]:
        if am["target"]["rfc"] == rfc:
            for n in am["target"]["records"]:
                out.setdefault(n, []).append(am["id"])
    return out


def prior_judgments(path):
    """{id: [disposition, reason, summary]} from an existing catalog, so regenerating keeps hand-set values."""
    out, cur = {}, None
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        if "requirements generated from normative-rfc" not in f.readline():
            return out  # not a file this script wrote (for example the old hand-written catalog)
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^- id:\s*(\S+)", line)
        if m:
            cur = out.setdefault(m.group(1), ["unassigned", None, None])
            continue
        m = re.match(r"^  (disposition|reason|summary):\s*(.*?)\s*$", line)
        if m and cur is not None:
            idx = {"disposition": 0, "reason": 1, "summary": 2}[m.group(1)]
            cur[idx] = json.loads(m.group(2))
    return out


def entries(rfc, prior=None):
    recs = [r for r in json.load(open(cfg.paths(rfc)["normative"], encoding="utf-8")) if r["kind"] == "keyword"]
    amended, out = amended_by(rfc), []
    recs = sorted(recs, key=lambda r: r["n"])
    seen, digests = {}, []
    for r in recs:
        quote = r.get("clause") or r["text"]
        key = (r["section"], r["label_path"], re.sub(r"\s+", " ", quote).strip())
        seen[key] = seen.get(key, 0) + 1
        digests.append(digest(rfc, r["section"], r["label_path"], quote, seen[key]))
    size = 6
    while len({(r["section"], d[:size]) for r, d in zip(recs, digests)}) < len(recs):
        size += 1  # extend the prefix on the (very unlikely) chance that two 6-digit prefixes collide
    for r, d in zip(recs, digests):
        disposition, reason, summary = (prior or {}).get(f"RFC{rfc}-{r['section']}-{d[:size]}", ("unassigned", None, None))
        e = {
            "id": f"RFC{rfc}-{r['section']}-{d[:size]}",
            "rfc": rfc, "section": r["section"],
            "subject": r["label_path"], "level": r["level"], "polarity": r["polarity"],
            "quote": r.get("clause") or r["text"],
        }
        if summary:
            e["summary"] = summary
        e.update({
            "lines": r["lines"], "original_lines": r["original_lines"], "record": r["n"],
        })
        if r.get("in_table"):
            e["in_table"] = True
        if r.get("errata"):
            e["errata"] = r["errata"]
        if r["n"] in amended:
            e["amended_by"] = amended[r["n"]]
        e.update(area=cfg.area_of(rfc, r["section"]), capability=cfg.capability_of(rfc, r["section"], r["label_path"]),
                 disposition=disposition)
        if reason:
            e["reason"] = reason
        out.append(e)
    return out


def emit(e):
    lines = [f"- id: {e['id']}"]
    for k, v in e.items():
        if k == "id":
            continue
        if isinstance(v, str):
            v = q(v)
        else:
            v = json.dumps(v, ensure_ascii=False)
        lines.append(f"  {k}: {v}")
    return "\n".join(lines)


def main():
    prior = prior_judgments(OUT)
    es = entries(RFC, prior)
    vanished = [i for i, (d, _, _) in prior.items() if d != "unassigned" and i not in {e["id"] for e in es}]
    if vanished:
        print(f"WARNING: {len(vanished)} hand-set disposition(s) belong to ids that no longer exist "
              f"(the sentence changed): {', '.join(vanished)}")
    head = (f"# RFC {RFC}: requirements generated from normative-rfc{RFC}.json (generate_requirements.py)\n"
            f"# One entry per upper-case-keyword sentence of rfc{RFC}-fixed.txt (RFC text + Verified errata).\n"
            f"# Do not edit by hand: regenerate. capability/disposition/legacy_ids are filled\n"
            f"# through the overlay described in README.md.\n")
    open(OUT, "w", encoding="utf-8").write(head + "\n" + "\n\n".join(emit(e) for e in es) + "\n")
    print(f"{len(es)} entries -> {OUT}")


if __name__ == "__main__":
    main()
