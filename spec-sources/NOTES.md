# Notes on the extraction

## Scope decisions

- Only records with `kind: "keyword"` count as requirements. The other kinds (`notation`,
  `convention`, `default`, `informative`) are ignored.
- RFC 7643: the keyword sentences in the JSON schema examples (section 8.7.x, 34 of them) are not
  separate records. 32 are attached to the prose record they illustrate as `schema_examples`
  (context only, never counted); 2 have no prose counterpart (the `referenceTypes` descriptions) and
  are in `unattached-json-examples-rfc7643.json`. Four attached examples use a different keyword than
  the prose (`keyword_differs: true`): `EnterpriseUser.manager.value` and `.$ref` say REQUIRED where
  section 4.3 says RECOMMENDED, and both `canonicalValues` examples say MUST where section 7 says
  MAY. Prefer the prose.
- Only **Verified** errata are applied. 15 of 16 for RFC 7643 (6004 and the section 8.7.2 part of
  8475 skipped, JSON examples only), 5 of 5 for RFC 7644. See `errata-report-rfcNNNN.md`.

## Open item: implicit rules (deferred)

Rules stated without an upper-case RFC 2119 keyword are **not** in the extraction. For RFC 7643
roughly 100 candidates exist in typical forms:

- characteristic assignments: "has a mutability of readOnly", "caseExact as true",
  "All attributes have a mutability of readOnly", "returned characteristic of always"
- defaults (sections 2.2 and 7): `required=false`, `mutability=readWrite`, `uniqueness=none`, ...
- lower-case modals: "must be a unique URI", "should be resolved", "cannot modify"
- value formats and closed value sets: "Valid values are ...", "HTTP-addressable URL"
- "READ-ONLY." on sub-attributes with no keyword (display, manager.displayName)
- process/guidance text in sections 9.3 and 10.3 (not testable by a validator)

Status: **not adopted**. If this is picked up in the future, keep such records in a separate class
(for example `kind: "implicit"` with no `level` until a mapping convention is decided).

## Cross-RFC amendments

RFC 9865 and RFC 9967 both say "Updates: 7643, 7644". `find_amendment_candidates.py` lists
every place in them that cites or mentions the older RFCs (`amendment-candidates.json`, 37
candidates, deterministic). They are classified in `amendments.json` (13 amendments,
20 plain references), and `check_amendments.py` verifies mechanically that every quote exists at its
cited lines, target sections and records are real, and every candidate is covered once.
`amendments.schema.json` documents the fields. Relation types: `extends` (adds something, the old requirement stays
valid), `modifies` (changes an older requirement under a stated `condition`), `supersedes`
(unconditional replacement; none found).

Result: 12 `extends`, 1 `modifies`, 0 `supersedes`.
- The only real modification is RFC 9865 section 2: `totalResults` (REQUIRED in a ListResponse,
  RFC 7644 section 3.4.2, record 46) MAY be omitted when cursor pagination is used and the total
  cannot be estimated.
- Everything else adds optional features: cursor parameter, `nextCursor`/`previousCursor`, new
  scimType errors, `pagination` and `securityEvents` in ServiceProviderConfig, asynchronous requests
  and bulk, IANA registry entries.

The implementer rule: skip or adjust an older requirement only if an amendment with relation
`modifies`/`supersedes` targets it and its `condition` holds. With the current file that is
`totalResults` under cursor pagination.

## What is deterministic

Everything in the `normative-rfcNNNN.json` files is produced by the scripts here; run
`check_extraction.py NNNN` after any change. `normative.schema.json` documents the record fields.
`rfcNNNN.txt` stays the untouched original; the corrected copy is `rfcNNNN-fixed.txt`. Pipeline and
commands: see `README.md`.

After any change to `extract_normative.py`, compare the RFC 7643 output with a saved copy: it was
kept byte-identical through the generalization to the other RFCs.
