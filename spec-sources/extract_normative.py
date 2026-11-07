#!/usr/bin/env python3
"""Extract every RFC 2119 normative sentence from an RFC text, with its section number.

Usage: python3 -I extract_normative.py [RFC number, default 7643]
Reads rfcNNNN-fixed.txt (run apply_errata.py first) and writes normative-rfcNNNN.json. Line numbers
refer to the fixed text, "original_lines" to rfcNNNN.txt. Per-RFC options live in rfc_config.py.

Only upper-case keywords count (RFC 2119 / RFC 8174). Page headers, footers and form
feeds are stripped, paragraphs are re-joined, then split into sentences. Each record
carries the source line numbers, the attribute label path (e.g. "bulk.maxOperations")
and neighbouring paragraphs for context. Description strings inside the JSON schema
examples (section 8.7.x) are extracted as their own sentences with a path derived from
the nesting of "name" keys. Sentences that mix keywords of different level/polarity are
split into one record per clause where a clause boundary can be found.
"""
import difflib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rfc_config as cfg  # noqa: E402

RFC = cfg.rfc_arg(sys.argv[1:])
P = cfg.paths(RFC)
CONF = cfg.config(RFC)
SRC, DST = P["fixed"], P["normative"]
UNATTACHED = P["unattached"]
ERRATA_APPLIED = P["applied"]  # written by apply_errata.py; used to flag/relocate records

# Longest first so "MUST NOT" wins over "MUST".
KEYWORDS = [
    "MUST NOT", "SHALL NOT", "SHOULD NOT", "NOT RECOMMENDED",
    "MUST", "REQUIRED", "SHALL", "SHOULD", "RECOMMENDED", "MAY", "OPTIONAL",
]
KW_RE = re.compile(r"\b(" + "|".join(KEYWORDS) + r")\b")
LEVEL = {
    "MUST": "MUST", "MUST NOT": "MUST", "REQUIRED": "MUST", "SHALL": "MUST", "SHALL NOT": "MUST",
    "SHOULD": "SHOULD", "SHOULD NOT": "SHOULD", "RECOMMENDED": "SHOULD", "NOT RECOMMENDED": "SHOULD",
    "MAY": "MAY", "OPTIONAL": "MAY",
}
NEGATIVE = {"MUST NOT", "SHALL NOT", "SHOULD NOT", "NOT RECOMMENDED"}

HEADER_RE = cfg.HEADER_RE
SECTION_RE = re.compile(r"^((?:\d+|[A-Z])(?:\.\d+)*)\.\s{1,3}(\S.*)$")
APPENDIX_RE = re.compile(r"^Appendix ([A-Z](?:\.\d+)*)\.\s+(.*)$")
# An attribute label: a lone token, or a token followed by two+ spaces and its description.
LABEL_RE = re.compile(r"^([A-Za-z$][\w$\-]*(?:\.[\w$\-]+)*)(?:\s{2,}(\S.*))?$")
# Only these sections define attributes with label lines (elsewhere "None" etc. are prose).
LABEL_SECTIONS = CONF["label_sections"] or re.compile(r"(?!)")  # matches nothing when unset
# A sentence that starts with a value/attribute label followed by two spaces, e.g. "global  The value ...".
LEAD_LABEL_RE = re.compile(r"^([A-Za-z$][\w$\-]*)\s{2,}(?=\S)")
# "externalId: If known, ..." / "Registration Format: An event ..." : one identifier or up to four
# Capitalized words, then a colon.
COLON_LABEL_RE = re.compile(r"^((?:[a-z]+[A-Z]\w*|[a-z]{1,12}|[A-Z][a-z]+(?: [A-Z][a-z]+){0,3})):\s+(?=[A-Z])")
NOT_LABELS = {"Note", "Warning", "Example", "Examples", "Important", "Editor", "Notes"}
BULLET_RE = re.compile(r"^\*\s+")
# Do not split after common abbreviations.
SENT_SPLIT = re.compile(
    r"(?<!\bMs\.)(?<!\bMr\.)(?<!\bMrs\.)(?<!\bDr\.)(?<!\be\.g\.)(?<!\bi\.e\.)(?<!\betc\.)(?<!\bvs\.)"
    r"(?<=[.!?}])\s+(?=[A-Z\"'(\[])"
    r"|(?<=\betc\.)\s{2,}(?=[A-Z\"'(\[])"           # RFC style: two spaces end a sentence
    r"|(?<=[.!?])\s+(?=[a-z]+[A-Z]\w*\b)"            # next sentence starts with a camelCase name
    )
_KW_START = r"(?=(?:" + "|".join(KEYWORDS) + r")\b)"
CLAUSE_SPLIT = re.compile(
    r"(?<=;)\s+"
    r"|,\s+(?=(?:and|but|although|while|which)\s)"
    r"|\s+(?=(?:but|although)\s)"
    r"|\s+and\s+" + _KW_START +
    r"|(?<=,)\s+(?=or\s+[^,;]{0,60}?\b(?:" + "|".join(KEYWORDS) + r")\b)"
    r"|\s+(?=where\s+[^,;]{0,60}?\b(?:" + "|".join(KEYWORDS) + r")\b)"
    r"|\s+(?=or\s+(?:" + "|".join(KEYWORDS) + r")\b)"
    r"|\s+(?=and\s+(?:is|are)\s+(?:" + "|".join(KEYWORDS) + r")\b)"
    r"|(?<=,)\s+(?=as\s)")


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


def join_lines(texts):
    """Join wrapped lines with a space, except a word the RFC wrapped at its hyphen ('cursor-' +
    'paginated' -> 'cursor-paginated')."""
    out = ""
    for t in texts:
        if out and re.search(r"[A-Za-z0-9]-$", out) and t[:1].islower():
            out += t
        else:
            out += (" " if out else "") + t
    return out


def clean_lines(text):
    """Yield (1-based line number in the source file, line). Numbering matches the file."""
    for no, raw in enumerate(text.split("\n"), 1):
        yield no, raw.replace("\f", "").rstrip()


def read_blocks(text):
    """Paragraph blocks: dict(section, title, lines=[(lineno, indent, text)])."""
    section, title = "front", ""
    para, blocks = [], []
    pending_break = para_after_break = False

    def flush():
        nonlocal para, para_after_break
        if not para:
            return
        prev = blocks[-1] if blocks else None
        # A sentence cut by a page break: glue the halves back together.
        if (para_after_break and prev and prev["section"] == section
                and not re.search(r"[.:;!?][\"')\]]*$", prev["lines"][-1][2])
                and para[0][1] == prev["lines"][-1][1]):  # a continuation keeps the indent
            prev["lines"].extend(para)
        else:
            blocks.append({"section": section, "title": title, "lines": para})
        para, para_after_break = [], False

    for no, line in clean_lines(text):
        if HEADER_RE.match(line):
            pending_break = True
            continue
        m = SECTION_RE.match(line) or APPENDIX_RE.match(line)
        if m and not line.startswith(" "):
            flush()
            section, title = m.group(1), m.group(2).strip()
            pending_break = False
            continue
        if not line.strip():
            flush()
            continue
        if not para:
            para_after_break, pending_break = pending_break, False
        para.append((no, len(line) - len(line.lstrip(" ")), line.strip()))
    flush()
    return blocks


BORDER_RE = re.compile(r"^\+[-=+]+\+$")


def is_table_block(blk):
    ts = [t for _, _, t in blk["lines"]]
    return (len(ts) >= 3 and any(BORDER_RE.match(t) for t in ts)
            and all(t.startswith(("+", "|")) for t in ts))


def parse_table(blk):
    """Rows of an ASCII table: list of {"cells": [text, ...], "cell_lines": [[first, last], ...]}.

    Column boundaries are the '+' positions of the most recent border line. A row ends at a
    border line or at a '|' line whose cells are all empty; a following chunk whose first cell is
    empty continues the row before it (a cell with several paragraphs). Wrapped cell text is joined.
    """
    rows, cols, chunk = [], [], []   # chunk: [(lineno, [segment per column])]

    def flush():
        nonlocal chunk
        if not chunk:
            return
        n = len(chunk[0][1])
        cells = [norm(join_lines([segs[k] for _, segs in chunk if segs[k]])) for k in range(n)]
        spans = []
        for k in range(n):
            nos = [no for no, segs in chunk if segs[k]]
            spans.append([min(nos), max(nos)] if nos else None)
        row = {"cells": cells, "cell_lines": spans}
        if rows and not cells[0] and len(rows[-1]["cells"]) == n and not rows[-1].get("closed"):
            prev = rows[-1]
            for k in range(n):
                prev["cells"][k] = norm(prev["cells"][k] + " " + cells[k])
                if spans[k]:
                    ps = prev["cell_lines"][k]
                    prev["cell_lines"][k] = [min(ps[0], spans[k][0]), max(ps[1], spans[k][1])] if ps else spans[k]
        else:
            rows.append(row)
        chunk = []

    for no, _, t in blk["lines"]:
        if BORDER_RE.match(t):
            flush()
            if rows:
                rows[-1]["closed"] = True  # a border ends the row for good
            cols = [i for i, ch in enumerate(t) if ch == "+"]
            continue
        if not cols:
            continue
        segs = [t[cols[k] + 1:cols[k + 1]].strip() for k in range(len(cols) - 1)]
        if chunk and len(segs) != len(chunk[0][1]):
            flush()
        if not any(segs):
            flush()  # empty row line: row boundary
            continue
        chunk.append((no, segs))
    flush()
    return rows


def is_json_block(blk):
    return sum(1 for _, _, t in blk["lines"] if re.match(r'^"[\w$]+" ?: ', t)) >= 3


def sentences_with_keywords(body):
    """Yield (offset, sentence, lead_label) for sentences containing a keyword.

    lead_label is the definition-list label stripped from the start of the sentence ('' if none)."""
    cursor = 0
    for sent in SENT_SPLIT.split(body):
        pos = body.find(sent, cursor)
        cursor = pos + len(sent) if pos >= 0 else cursor
        if KW_RE.search(sent):
            bullet = BULLET_RE.match(sent)
            if bullet:
                pos += bullet.end()
                sent = sent[bullet.end():]
            lead = LEAD_LABEL_RE.match(sent)
            colon = None if lead else COLON_LABEL_RE.match(sent)
            if colon and colon.group(1) in NOT_LABELS:
                colon = None
            label = ""
            if lead or colon:  # drop the definition label, keep offsets pointing at the real sentence
                m = lead or colon
                label = m.group(1)
                pos += m.end()
                sent = sent[m.end():]
            yield max(pos, 0), sent, label


def split_mixed(sent):
    """Split a mixed-level sentence into clauses, one keyword class each. [] if not splittable."""
    pieces = [p for p in CLAUSE_SPLIT.split(sent) if p and p.strip()]
    grouped = []
    for p in pieces:
        if KW_RE.search(p) or not grouped:
            grouped.append(p)
        else:
            grouped[-1] += " " + p  # keyword-less tail stays with the clause before it
    kw_pieces = [g for g in grouped if KW_RE.search(g)]
    return kw_pieces if len(kw_pieces) > 1 else []


def label_paths(blocks):
    """Attach the label path (top.sub) to every block, tracking indentation.

    RFCs without label sections (everything but 7643) only have simple definition lists
    ("POST      Depending on the endpoint, ..."): the block's first-line label is its subject."""
    if not CONF["label_sections"]:
        for blk in blocks:
            _, indent, text = blk["lines"][0]
            m = LABEL_RE.match(text)
            blk["label_path"] = (m.group(1) if m and m.group(2) and m.group(1) != "o" and 3 <= indent <= 9
                                 and not is_table_block(blk) else "")
        return
    top = sub = None
    cur_sec = None
    for blk in blocks:
        if blk["section"] != cur_sec:
            cur_sec, top, sub = blk["section"], None, None
        _, indent, text = blk["lines"][0]
        m = LABEL_RE.match(text)
        is_label = (m and m.group(1) != "o"  # "o" is a bullet marker, not a label
                    and LABEL_SECTIONS.match(blk["section"]) and indent in (3, 6)
                    and not is_json_block(blk))
        if is_label:
            if indent == 3:
                top, sub = m.group(1), None
            else:
                sub = m.group(1)
        elif indent == 3 and LABEL_SECTIONS.match(blk["section"]):
            top = sub = None  # a plain prose paragraph ends the previous attribute entry
        blk["label_path"] = ".".join(x for x in (top, sub) if x)


def json_records(blk, sec, ttl):
    """Records for keyword-bearing "description" strings in a JSON schema example."""
    out, stack = [], []  # stack of (indent, name)
    lines = blk["lines"]
    i = 0
    while i < len(lines):
        no, indent, text = lines[i]
        m = re.match(r'^"name" ?: ?"([^"]+)"', text)
        if m:
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, m.group(1)))
        m = re.match(r'^"description" ?: ?"(.*)$', text)
        if m:
            parts, start, j = [m.group(1)], no, i
            while not re.search(r'",?$', parts[-1]) and j + 1 < len(lines):
                j += 1
                parts.append(lines[j][2])
            desc = norm(" ".join(parts))
            desc = re.sub(r'",?$', "", desc)
            end = lines[j][0]
            path = ".".join(n for _, n in stack)
            for _, sent, _lead in sentences_with_keywords(desc):
                out.append(make_record(sec, ttl, sent, [start, end], [lines[0][0], lines[-1][0]],
                                       desc, "", "", path, True))
            i = j
        i += 1
    return out


def make_record(sec, ttl, sent, lines, para_lines, paragraph, prev, nxt, path, in_json, in_table=False):
    kws = [m.group(1) for m in KW_RE.finditer(sent)]
    text = norm(sent)
    kind = "keyword"
    if re.search(r"\bkey words\b|\bBCP 14\b", text):
        kind = "notation"  # RFC 2119 / RFC 8174 boilerplate
    elif CONF["convention_prefix"] and sec == "1.1" and text.startswith(CONF["convention_prefix"]):
        kind = "convention"
    elif re.match(r'^(o\s+)?"\w+" is "', text):
        kind = "default"
    elif CONF["informative_prefix"] and sec.startswith(CONF["informative_prefix"]) and not in_json:
        kind = "informative"
    rec = {
        "section": sec,
        "section_title": ttl,
        "label_path": path,
        "kind": kind,
        "keywords": kws,
        "level": LEVEL[kws[0]],
        "polarity": "negative" if kws[0] in NEGATIVE else "positive",
        "mixed": len({(LEVEL[k], k in NEGATIVE) for k in kws}) > 1,
        "in_json_example": in_json,
        "lines": lines,
        "paragraph_lines": para_lines,
        "text": text,
        "paragraph": norm(paragraph),
        "previous_paragraph": prev,
        "next_paragraph": nxt,
    }
    if in_table:
        rec["in_table"] = True
    return rec


# JSON example root (first segment of the path) -> prose sections that state its rules.
ROOT_SECTIONS = CONF["root_sections"]


def annotate_errata(out, path):
    """Add 'errata' (errata whose edit overlaps the record's lines) and 'original_lines'."""
    try:
        applied = json.load(open(path, encoding="utf-8"))
    except FileNotFoundError:
        return
    edits, fmap = applied["applied"], applied["fixed_to_original"]

    def original(line, edge):
        o = fmap[line - 1] if line - 1 < len(fmap) else None
        if o is not None:
            return o
        for e in edits:  # replaced line: use the original range of the edit that replaced it
            if e["fixed_lines"][0] <= line <= e["fixed_lines"][1]:
                return e["orig_lines"][edge]
        return None

    def hits(rng):
        return [{"id": e["id"], "status": e["status"], "type": e["type"]}
                for e in edits
                if any(c[0] <= rng[1] and rng[0] <= c[1] for c in e["changed_fixed"])]

    for r in out:
        r["original_lines"] = [original(r["lines"][0], 0), original(r["lines"][1], 1)]
        found = hits(r["lines"])
        if found:
            r["errata"] = found
        for ex in r.get("schema_examples", []):
            found = hits(ex["lines"])
            if found:
                ex["errata"] = found


def _plain(s):
    return re.sub(r"[^a-z0-9 ]", "", s.lower())


def attach_examples(out):
    """Attach each JSON-example description to the prose record it illustrates.

    A JSON record (path like 'User.emails.primary') goes to the prose keyword record whose
    label_path ends in the same last segment and whose sentence is most similar (bare
    'REQUIRED.'/'OPTIONAL.' fragments must share a keyword instead). Without a same-leaf
    candidate a very similar sentence (>= 0.8) is accepted. Returns the JSON records that
    found no home; those stay in the output untouched.
    """
    prose = [r for r in out if not r["in_json_example"] and r["kind"] == "keyword"]
    unmatched = []
    for j in [r for r in out if r["in_json_example"]]:
        root, *jsegs = j["label_path"].split(".")
        bare = len(j["text"]) < 30
        best, best_score = None, 0.0
        for p in prose:
            if p["section"] not in ROOT_SECTIONS.get(root, ()):
                continue
            psegs = p["label_path"].split(".") if p["label_path"] else []
            if not psegs or not jsegs or psegs[-1].lower() != jsegs[-1].lower():
                continue  # the attribute itself must be the same
            if not all(s in jsegs[:-1] for s in psegs[:-1]):
                continue  # and so must its parent attributes (manager.value is not emails.value)
            sim = difflib.SequenceMatcher(None, _plain(j["text"]), _plain(p["text"])).ratio()
            shared = bool(set(j["keywords"]) & set(p["keywords"]))
            score = sim + (1 if shared else 0)
            if score > best_score:
                best, best_score = p, score
        if best is None and len(jsegs) >= 2:
            # Fallback: a sub-attribute's description that only restates its parent's rule.
            for p in prose:
                psegs = p["label_path"].split(".") if p["label_path"] else []
                if (p["section"] in ROOT_SECTIONS.get(root, ()) and psegs
                        and psegs[-1].lower() == jsegs[-2].lower()
                        and set(j["keywords"]) & set(p["keywords"])):
                    sim = difflib.SequenceMatcher(None, _plain(j["text"]), _plain(p["text"])).ratio()
                    if sim >= 0.5 and sim > best_score:
                        best, best_score = p, sim
        if best is None:
            unmatched.append(j)
            continue
        best.setdefault("schema_examples", []).append({
            "section": j["section"],
            "path": j["label_path"],
            "lines": j["lines"],
            "keywords": j["keywords"],
            "text": j["text"],
            # the example uses a different keyword than the prose (RFC inconsistency, worth a look)
            "keyword_differs": not (set(j["keywords"]) & set(best["keywords"])),
        })
    return unmatched


def main():
    text = open(SRC, encoding="utf-8-sig").read()
    blocks = read_blocks(text)
    label_paths(blocks)
    body_of = lambda b: join_lines([t for _, _, t in b["lines"]])

    out = []

    def emit(sec, ttl, sent, pos, body, line_at, rec_args, fixed=None):
        """Append the record(s) for one keyword sentence; split mixed-level sentences by clause.

        'fixed' is a [first, last] line range to use for everything (table cells)."""
        if fixed:
            line_at = lambda o: fixed[0]  # noqa: E731  (unused: ranges are fixed)
            first, last = fixed
        else:
            first, last = line_at(pos), line_at(pos + len(sent) - 1)
        rec = make_record(sec, ttl, sent, [first, last], **rec_args)
        clauses = split_mixed(sent) if rec["mixed"] and rec["kind"] == "keyword" else []
        if not clauses:
            out.append(rec)
            return
        for clause in clauses:
            cpos = body.find(clause, pos)
            crange = list(fixed) if fixed else [line_at(cpos), line_at(cpos + len(clause) - 1)]
            r = make_record(sec, ttl, clause, crange, **rec_args)
            # "text" is always the whole sentence; keywords/level/polarity describe the clause.
            r["clause"], r["clause_lines"] = r["text"], r["lines"]
            r["text"], r["lines"], r["split"] = norm(sent), [first, last], True
            out.append(r)

    for bi, blk in enumerate(blocks):
        sec, ttl = blk["section"], blk["title"]
        if CONF["json_example_prefix"] and sec.startswith(CONF["json_example_prefix"]) and is_json_block(blk):
            out.extend(json_records(blk, sec, ttl))
            continue
        if is_table_block(blk):
            rows = parse_table(blk)
            header = rows[0]["cells"] if rows else []
            for ri, row in enumerate(rows):
                label = row["cells"][0][:60] if ri > 0 and len(row["cells"]) > 1 else ""
                context = " | ".join((f"{h}: {c}" if h else c) for h, c in zip(header, row["cells"]) if c)
                for ci, cell in enumerate(row["cells"]):
                    span = row["cell_lines"][ci]
                    if not cell or not span:
                        continue
                    rec_args = dict(para_lines=[blk["lines"][0][0], blk["lines"][-1][0]], paragraph=context,
                                    prev="", nxt="", path=label if ci > 0 else "", in_json=False, in_table=True)
                    for pos, sent, _lead in sentences_with_keywords(cell):
                        # a cell is a few lines; its line range is the best line information available
                        emit(sec, ttl, sent, pos, cell, None, rec_args, fixed=span)
            continue
        body = body_of(blk)
        prev_blk = blocks[bi - 1] if bi > 0 and blocks[bi - 1]["section"] == sec else None
        next_blk = blocks[bi + 1] if bi + 1 < len(blocks) and blocks[bi + 1]["section"] == sec else None
        starts, off, prev_t = [], 0, ""
        for no, _, t in blk["lines"]:
            if prev_t:
                off += 0 if (re.search(r"[A-Za-z0-9]-$", prev_t) and t[:1].islower()) else 1
            starts.append((off, no))
            off += len(t)
            prev_t = t

        def line_at(o):
            ln = starts[0][1]
            for so, no in starts:
                if so <= o:
                    ln = no
            return ln

        for pos, sent, lead in sentences_with_keywords(body):
            rec_args = dict(
                para_lines=[blk["lines"][0][0], blk["lines"][-1][0]], paragraph=body,
                prev=body_of(prev_blk) if prev_blk else "", nxt=body_of(next_blk) if next_blk else "",
                path=blk["label_path"] or (lead if lead != "o" else ""), in_json=False)  # "o" = bullet
            emit(sec, ttl, sent, pos, body, line_at, rec_args)

    unmatched = attach_examples(out)
    json.dump([{k: r[k] for k in ("section", "label_path", "lines", "keywords", "text")} for r in unmatched],
              open(UNATTACHED, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    out = [r for r in out if not r["in_json_example"]]
    for r in out:
        del r["in_json_example"]
    out.sort(key=lambda r: (r["lines"][0], r["lines"][1]))
    for i, e in enumerate(out, 1):
        e["n"] = i
    annotate_errata(out, ERRATA_APPLIED)
    json.dump(out, open(DST, "w", encoding="utf-8"), indent=2, ensure_ascii=False)

    by_level, by_kind = {}, {}
    for e in out:
        key = e["level"] + (" NOT" if e["polarity"] == "negative" else "")
        by_level[key] = by_level.get(key, 0) + 1
        by_kind[e["kind"]] = by_kind.get(e["kind"], 0) + 1
    print(f"{len(out)} normative sentences -> {DST}")
    print("by level:", by_level)
    print("by kind:", by_kind)
    print("still mixed:", [e["n"] for e in out if e["mixed"]])


if __name__ == "__main__":
    main()
