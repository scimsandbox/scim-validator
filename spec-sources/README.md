# RFC source texts and the normative-statement extraction

This folder turns RFC texts and their published errata into lists of the RFCs' normative
statements (`normative-rfcNNNN.json`). Everything in the JSON is produced deterministically by the
scripts below, and each step is checked. Four RFCs are processed:

| RFC | Title | JSON records | of which requirements (`kind: keyword`) |
|---|---|---|---|
| 7643 | SCIM: Core Schema | 201 | 195 |
| 7644 | SCIM: Protocol | 276 | 275 |
| 9865 | SCIM: Cursor-Based Pagination (updates 7643, 7644) | 32 | 31 |
| 9967 | SCIM Profile for Security Event Tokens (updates 7643, 7644) | 50 | 49 |

```
 downloaded from the internet                 generated here (NNNN = RFC number)
 ───────────────────────────                  ──────────────────────────────────
 fetch_sources.py NNNN ─► rfcNNNN.txt          ─┐
                          rfcNNNN-errata.json  ─┴► apply_errata.py ─► rfcNNNN-fixed.txt ─► extract_normative.py ─► normative-rfcNNNN.json
                                                   (+ errata-applied-rfcNNNN.json,                              (+ unattached-json-examples-rfcNNNN.json)
                                                      errata-report-rfcNNNN.md)                                         │
                                                                                          check_extraction.py NNNN ◄─────┘
```

Run the whole pipeline for one RFC (all scripts take the RFC number, default 7643):

```
python3 -I fetch_sources.py 7643 7644 9865 9967     # once, needs network; existing files are kept
python3 -I apply_errata.py 7644
python3 -I extract_normative.py 7644
python3 -I check_extraction.py 7644
```

A separate step tracks which requirements of RFC 7643/7644 the newer RFCs 9865 and 9967 change
(amendments, section 6a):

```
python3 -I find_amendment_candidates.py     # needs the extraction of all four RFCs
python3 -I check_amendments.py              # validates amendments.json
```

## 1. The downloaded inputs (`fetch_sources.py`)

| File | What it is |
|---|---|
| `rfcNNNN.txt` | The RFC text byte-for-byte from `https://www.rfc-editor.org/rfc/rfcNNNN.txt` |
| `rfcNNNN-errata.json` | The errata filed against that RFC: the entries of `https://www.rfc-editor.org/api/v1/errata.json` whose `doc-id` is `RFCNNNN` (7643: 41, 7644: 18, 9865: 1, 9967: none) |

The script never overwrites an existing file unless you pass `--force`; it tells you whether the
new download is identical (it is, for the RFC 7643 files that were fetched first).
`SHA256SUMS` records checksums of every file here.

## 2. Step 1: apply the errata (`apply_errata.py`)

The published RFC text still contains mistakes the RFC Editor has since confirmed as errata. This
step produces a corrected copy.

- **Which errata:** only status **Verified**. Held for Document Update, Reported and Rejected errata
  are not applied. Override with `--status "Verified,Held for Document Update"`.
- **How:** an erratum is applied only if its "original text" is found **exactly once** in the RFC
  (whitespace and page headers ignored); otherwise it is skipped and the reason reported. It never
  guesses. Text covering whole lines replaces those lines (re-indented to the RFC layout); text
  starting or ending mid-line changes only the words that differ. Errata using `...` separators
  or `---` parts are split into segments and aligned; unalignable parts are skipped.
- **Result:** RFC 7643: 15 of 16 Verified errata applied (18 edits); skipped are erratum 6004 (79
  candidate matches) and the section 8.7.2 half of erratum 8475, both only JSON examples. RFC 7644:
  all 5 Verified errata applied (6893, 7898, 7916, 8096, 8365); they fix JSON examples and an
  editorial slip and touch no keyword sentence. RFC 9865 has one Reported erratum and RFC 9967 none,
  so their fixed text is a plain copy.
- **Outputs:** `rfcNNNN-fixed.txt`; `errata-report-rfcNNNN.md` (before/after diff of every edit and
  skip reasons); `errata-applied-rfcNNNN.json` (edits, original/fixed line ranges, line map).

## 3. Step 2: extract the normative statements (`extract_normative.py`)

One record per sentence containing an **upper-case** RFC 2119 keyword (MUST, MUST NOT, SHALL,
SHALL NOT, REQUIRED, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, OPTIONAL). In order:

1. **Paragraphs.** Strip page headers/footers (matched by pattern, so it works for any RFC), split on
   blank lines, rejoin a sentence a page break cut in two (same indent only), rejoin words wrapped
   at their hyphen (`cursor-` + `paginated`).
2. **Section and title** from the RFC's numbered headings.
3. **Tables.** ASCII tables (RFC 7644, 9865) are parsed by their `+---+` borders; rows end at a border
   or an empty `| |` line; each cell is its own text. A record from a cell has `in_table: true`,
   `label_path` = the row's first-column cell (for example `count`, `nextCursor`, `eq`) and
   `paragraph` = the whole row as `Header: cell | Header: cell`.
4. **Subject (`label_path`).** RFC 7643 writes attribute names on their own lines with sub-attributes
   indented further; the script tracks that indentation to give paths such as `bulk.maxOperations`.
   For the other RFCs the subject is the label of a simple definition list (`POST   Depending on ...`,
   `externalId: If known ...`) when there is one, otherwise empty (use `section_title`).
5. **Sentences.** Split with exceptions for "e.g.", "Ms." and similar, and extra boundaries for
   sentences ending in `}`, two spaces after "etc.", or a following camelCase name. Leading bullet
   and definition labels are removed from `text`.
6. **Level and polarity** from the first keyword through a fixed table: `MUST NOT` is level MUST,
   polarity negative.
7. **Mixed sentences** ("MAY ... but SHOULD NOT ...") are split into one record per clause
   (`split: true`; `text` stays the whole sentence, `clause` is the part the keyword belongs to).
8. **JSON examples (RFC 7643 only).** The keyword sentences inside the section 8.7 example schemas are
   attached as `schema_examples` to the prose record they illustrate (never counted as requirements);
   two with no prose counterpart are in `unattached-json-examples-rfc7643.json`.
9. **Errata and original lines.** A record gets `errata` if a Verified erratum changed one of its own
   lines; `original_lines` is always its position in the unmodified `rfcNNNN.txt`.
10. **Kind.** `keyword` (a real requirement), `notation` (the BCP 14 boilerplate), `convention`,
    `default` or `informative` (a few RFC 7643 cases).

Per-RFC settings (which sections have attribute labels, where JSON examples are) are in
`rfc_config.py`; the other three RFCs use the defaults.

## 4. Step 3: check the result (`check_extraction.py`)

Deterministic sanity checks, no model involved:

- every upper-case keyword in the text is accounted for (raw count = records + attached examples +
  unattached examples): 253, 295, 42 and 62 for the four RFCs;
- every record's (and attached example's) cited lines contain its sentence;
- the sections found match the RFC's table of contents (deeper headings than the TOC lists are
  ignored);
- no unexpectedly long or unterminated sentences.

Known harmless leftovers: a few RFC 7643 bullet records have no final full stop, and RFC 7643
section `10.1` is reported as not in its table of contents.

## 5. Reading the JSON

`normative.schema.json` is a JSON Schema describing every field, written so a person or an AI agent
can read the files correctly. The essentials:

- **`kind`**: use `keyword` records only.
- **`level` and `polarity`**: how strong the rule is and whether it forbids something.
- **`section`, `section_title`, `label_path`, `text`, `paragraph`**: where it is, what it concerns,
  the sentence and its paragraph (for table cells, the row). Bare fragments like "REQUIRED." only
  make sense with `label_path`.
- **`lines` / `original_lines`**: position in `rfcNNNN-fixed.txt` / `rfcNNNN.txt`.
- **`errata`**: present if a Verified erratum changed this record's wording; the text is corrected.
- **`schema_examples`**: context only (RFC 7643).

## 6. What is not covered

- **Rules without an upper-case keyword** ("has a mutability of readOnly", lower-case "must"):
  see `NOTES.md`.
- **Cross-RFC amendments** are tracked separately (section 6a), not inside the normative files.
- **Non-Verified errata.**
- **Judgment about inconsistencies or ambiguity.** Only mechanical flags exist (`errata`,
  `keyword_differs`, `mixed`).

## 6a. Amendments of the older RFCs by the newer ones

RFC 9865 and 9967 say "Updates: 7643, 7644". Which requirements they change is recorded apart from the
generated extraction, because classifying them is judgment:

```
python3 -I find_amendment_candidates.py     # deterministic: every citation/mention of RFC 7643/7644 in 9865/9967
python3 -I check_amendments.py              # validates amendments.json mechanically
```

`amendment-candidates.json` (37 candidates) is generated; `amendments.json` (13 amendments: 12
`extends`, 1 `modifies`, plus 20 candidates that are plain references) is curated and
validated by `check_amendments.py` (quotes exist at the cited lines, target sections/records exist,
every candidate covered once). `amendments.schema.json` describes the fields.
An implementer should skip or adjust an older requirement only for a `modifies`/`supersedes` entry whose
`condition` holds (currently: `totalResults` may be omitted under cursor pagination). Details in `NOTES.md`.

## 6b. The generated requirements catalog

`src/main/resources/spec/rfcNNNN-requirements.yaml` (shipped in the jar, the file the validator UI reads) is generated from `normative-rfcNNNN.json`, one entry per keyword record
(7643: 195, 7644: 275, 9865: 31, 9967: 49), so coverage is complete by construction. It is meant to
replace the earlier hand-written catalog of the same name, which covered only part of the keyword
sentences and described them by paraphrase (it is still in git history).

```
python3 -I generate_requirements.py 7643    # deterministic; never edit the output by hand
python3 -I check_requirements.py 7643       # id form, verbatim quote at the cited lines, level/polarity, coverage
```

Each entry carries the verbatim `quote`, `subject`, `level`, `polarity`, `lines`, `original_lines`,
`record`, `area`, `capability`, `disposition`, and `errata`/`amended_by`/`in_table` where they apply.

- **Ids** are `RFC<N>-<section>-<hash>`: the first 6 hex digits of a SHA-256 over rfc, section, subject
  and the normalized quote (the occurrence number is added if a section repeats a subject and quote).
  An id does not change when other sentences move; it changes only if the sentence's own wording
  changes, which is when its test has to be reviewed. A test tagged with a vanished id then fails the
  traceability check. `check_requirements.py` recomputes every hash.
- **`area`** is what the requirement is about, used to group tests into spec classes: `general`,
  `attribute-model`, `resource-schemas`, `discovery`, `auth`, `resources`, `query`, `patch`, `bulk`, `me`,
  `formats`, `errors`, `versioning`, `multi-tenancy`, `security`, `registry`, `cursor-pagination`,
  `events`, `async`. Derived from the section by `AREA_RULES` in `rfc_config.py`, like `capability`.
- **`capability`** is the server feature a test needs, in the validator UI's vocabulary (`core`,
  `patch`, `filter`, `sort`, `etag`, `bulk`, `search`, `changePassword`, `enterprise`,
  `cursorPagination`, `async`). It is derived from the section by the rules in `rfc_config.py`
  (`CAPABILITY_RULES`): an approximate grouping, not a judgment of each sentence. Currently 357 of 550
  entries are `core`; `changePassword` is not assigned by any rule.
- **`disposition`** is test-planning state that is not in the RFC. It starts as `unassigned`, then
  `tested`, `optional`, `na` (not testable by a client, with a reason) or `gap`.

`check_requirements.py` was tested against an altered quote, a wrong level, a wrong id, a tampered
hash, a wrong capability, an invalid disposition and a missing entry; each is reported.

## 6c. The Spock specs (one feature per requirement)

```
python3 -I generate_skeletons.py 7643       # creates Rfc7643<Area>Spec classes with one @Ignore'd feature per requirement
python3 -I check_spec_coverage.py           # catalog and specs correspond exactly
```

The classes go to `src/main/groovy/de/palsoftware/scim/validator/specs/`, one per RFC and `area`
(`Rfc7643AttributeModelSpec`, `Rfc7644QuerySpec`, ...). When a catalog rule changes, rerunning the generator also corrects the `@RequiresCapability` of existing features. Every feature is named `"<id>: <label>"`, carries
exactly one `@SpecRef("<id>")` and, if the capability is not `core`, `@RequiresCapability("<capability>")`.
A skeleton is `@Ignore`d, so it reports as skipped until its body is written and the `@Ignore` removed.
Requirements with `disposition: na` get no feature. The generator never rewrites a class that exists
(the files are edited by hand afterwards); it only appends skeletons for requirements that have none.
`check_spec_coverage.py` fails on: a missing or doubled `@SpecRef`, an id not in the catalog, a display name
that does not start with the id, a feature in the wrong class, a capability that differs from the catalog,
a requirement without a feature (or an `na` one with a feature). It also counts implemented features
against skeletons. Tested by altering each of these in turn.

## 7. Files in this folder

| File | Role |
|---|---|
| `fetch_sources.py`, `apply_errata.py`, `extract_normative.py`, `check_extraction.py`, `rfc_config.py` | the scripts and shared settings |
| `rfcNNNN.txt`, `rfcNNNN-errata.json` | downloaded inputs |
| `rfcNNNN-fixed.txt`, `errata-applied-rfcNNNN.json`, `errata-report-rfcNNNN.md` | step 1 outputs |
| `normative-rfcNNNN.json` | **step 2 outputs: the normative statements** |
| `unattached-json-examples-rfcNNNN.json` | step 2: RFC 7643 JSON-example sentences with no prose counterpart (empty for the others) |
| `normative.schema.json` | description of the fields of the JSON |
| `find_amendment_candidates.py`, `amendment-candidates.json` | amendment candidates (generated) |
| `check_amendments.py`, `amendments.json`, `amendments.schema.json` | curated amendments, their validator and schema |
| `generate_requirements.py`, `check_requirements.py`, `../src/main/resources/spec/rfcNNNN-requirements.yaml` | generated requirements catalog, its checker |
| `generate_skeletons.py`, `check_spec_coverage.py` | Spock spec classes (one feature per requirement) and their 1:1 checker |
| `suggest_quotes.py`, `quote-suggestions-rfc7643.json` | scoring helper that proposes which RFC sentence an old catalog entry stands for (to fill `legacy_ids`; only suggests) |
| `NOTES.md` | scope decisions, open items, cross-RFC amendments |
| `SHA256SUMS` | checksums |
