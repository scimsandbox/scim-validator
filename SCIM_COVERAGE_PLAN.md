# Plan: Full SCIM 2.0 Spec Coverage for scim-validator

Status: proposed. Inputs used: `SCIM_SPEC_COVERAGE.md` and `SCIM_VALIDATOR_ASSESSMENT.md`
(the current, audited artifacts), `reports/SCIM validator spec coverage.md` and the four notes
files in `research_notes/SCIM validator spec coverage/` (verified findings: 7 material RFC 7644
citations errors, ~35 requirements with no matrix row, 21 soft `scimType` sites, optional
features hard-required, deviations stdout-only and capture-suppressed).

---

## 1. Goal and Definition of Done

Goal: **every normative requirement of the SCIM specifications is either tested, conditionally
tested when the corresponding optional capability is advertised, or explicitly registered as
not applicable — and every test declares which spec requirement(s) it exercises.**

Done means:

1. A machine-readable requirement catalog exists for RFC 7643 and RFC 7644 (every normative
   statement and field rule), each row carrying: stable ID, RFC, section, level
   (MUST/SHOULD/MAY), capability, observability, disposition.
2. Every feature method in `src/main/groovy/de/palsoftware/scim/validator/specs/` carries at
   least one `@SpecRef("<requirement-id>")`.
3. CI fails on: a test without a ref, a ref to an unknown requirement, a MUST/SHOULD
   requirement with no test and no `na` disposition, and drift in generated docs.
4. Two docs are generated, never hand-edited:
   - `docs/TEST_SPEC_MAP.md` — **test → spec** (what each test proves).
   - `docs/SCIM_SPEC_COVERAGE.md` — **spec → test** (regenerated from the catalog).
5. A green run means "conformant on every path exercised", not "passed with tolerated
   deviations": deviations are recorded to `target/scim-run/deviations.json`, summarized at
   the end of the run, and fail the run in strict mode unless allowlisted.
6. The current docs stop being maintained by hand and are replaced by the generated pair
   (root `SCIM_SPEC_COVERAGE.md` / `SCIM_VALIDATOR_ASSESSMENT.md` become pointer stubs or
   are deleted).

---

## 2. Scope — What "All SCIM Specifications" Means

| Track | Content | Treatment |
| :-- | :-- | :-- |
| Core | RFC 7643, RFC 7644; HTTP semantics (RFC 7231, 7232, 7235) where the suite relies on them; RFC 7642 for terminology only | In scope, blocking |
| Errata | Verified errata as of the catalog date, notably 5368 (Group `displayName.required=true`), 7916 (bulk status string), 8415/8417/8435/8475 (Schema/ResourceType details), 7319/7322 (`not (` ABNF) | Catalog states its exact basis |
| Optional server features | filters, sort, ETag, bulk, changePassword, `/Me` (SHOULD), PATCH (SHOULD) | `optional` disposition: capability-gated tests that skip **with a recorded reason** when the server declares no support, and assert strictly when it does |
| Non-observable requirements | password storage (RFC 7643 §9.2), TLS server configuration (RFC 7644 §7.2), client-only rules | `na` with written justification, except what can be checked from the run environment (e.g. TLS enforced when the base URL is `https`) |
| Extensions | RFC 9865 (cursor pagination), RFC 9967 (async operations/events) | Phase 5, non-blocking; separate capability gates |

---

## 3. Baseline (Verified, Not Assumed)

- 186 feature methods across A1–A9; 119-row matrix claims 48 Covered / 43 Partial / 28 Gap.
- The audit corrected the matrix: §3.5.2.2/.3 swapped in docs *and* test comments; "§3.1 create"
  should be §3.3; `Content-Location` is RFC 7643 §3.1, not 7644; `externalId` row cites tests
  that never touch it; four test comments quote RFC text that does not exist (PAG_04, FLT_33,
  SRT_05, A1:222); ~35 normative requirements have no row at all (22 RFC 7644, ~20 RFC 7643,
  minus overlaps).
- Signal problems: 21 `scimType` call sites pass on keyword omission; FLT_13/14, SRT_02,
  A1 non-405, PAT_18/19, BLK_18/19, SEC_19/20/24 log a deviation and pass; PATCH, bulk, ETag,
  `/.search` and the Enterprise extension are required although optional; `/Me` hard-asserts
  501; pagination requires `itemsPerPage == count`; PUT tests require cleared attributes where
  the RFC allows defaulting; deviations never leave stdout and are suppressed entirely when
  capture mode is on (`ScimOutput.groovy:5–15`).
- Docs are hand-maintained and already drifted (stale line refs in A3–A6, wrong §3.3.1
  uniqueness citation, A2 marked complete with conditional-only checks).

---

## 4. Traceability Model — How Every Test Declares Its Spec Basis

### 4.1 Requirement catalog (new, authored once, reviewed like code)

Location: `src/main/resources/spec/rfc7643-requirements.yaml`,
`src/main/resources/spec/rfc7644-requirements.yaml`.
`groovy-yaml` is already a dependency (pom.xml:59–63), and shipping the catalog in the JAR lets
library consumers render coverage for their own runs.

Row schema:

```yaml
- id: RFC7644-3.5.2.2-R1            # stable, never reused
  rfc: "7644"
  section: "3.5.2.2"                # Remove Operation (note: .2=remove, .3=replace)
  title: "PATCH remove without path returns 400 with scimType noTarget"
  level: MUST
  capability: patch                 # none|filter|sort|etag|bulk|patch|search|changePassword|enterprise
  observability: response           # response|behavior|internal|client
  disposition: gap                  # tested|optional|na|gap
  note: "No test sends remove without a path"
  tests: []                         # generated by the matrix task; never hand-edited
```

Requirement IDs are derived from section + sequence (`RFC7644-3.4.2.2-R1` = `eq`), so the
"where in the spec does this come from" column is intrinsic to every reference. The catalog is
seeded by a normative-keyword sweep of the RFC texts (the audit already produced these sweeps —
use `research_notes/SCIM validator spec coverage/01_*.md` and `02_*.md` as the starting lists).

### 4.2 Test annotation

```groovy
import de.palsoftware.scim.validator.base.spec.SpecRef

@SpecRef("RFC7644-3.4.2.2-R1")        // eq comparison
def "FLT_01: Filter with eq operator returns exact match"() { ... }
```

- `@SpecRef` is a repeatable, runtime-retained Groovy annotation defined in
  `base/spec/SpecRef.groovy`; one method may reference several requirements.
- Existing IDs (`USR_01`, `PAT_01`, `FLT_01`, `GRP_01`, `BLK_01`, `SEC_01`, `NEG_01`) stay;
  A1 and A2 tests get IDs (`DSC_01…` discovery, `SCH_01…` schema) as part of the retrofit.
- Capability-gated tests also get `@RequiresCapability("bulk")` (or the generator derives it
  from the referenced catalog rows); when the SPC declares the capability unsupported the base
  spec skips with a recorded reason instead of failing.
- Rule enforced by CI: no unannotated feature method; no annotation pointing at a missing or
  `na` requirement.

### 4.3 Generator and CI gates

- `SpecMatrixGenerator` (Groovy main in `src/main`) reads catalog + annotations and writes:
  - `docs/TEST_SPEC_MAP.md` (test → spec): columns `Test ID | Test | File:line | RFC |
    Section | Requirement ID | Requirement | Mode (strict/gated)`.
  - `docs/SCIM_SPEC_COVERAGE.md` (spec → test): the corrected, regenerated matrix.
- Maven profile `verify-spec-coverage`: generate → `git diff --exit-code` (docs current) →
  run validation rules. Runs in CI on every PR.
- `SpecTraceabilitySpec` in `src/test` for a fast local check (no server needed; does not
  extend `ScimBaseSpec`).
- Deviation sink: `ScimOutput.deviation(...)` appends to a structured collector, written to
  `target/scim-run/deviations.json` at run end (works in capture mode too), printed as a
  summary count; strict mode fails on any non-allowlisted deviation.

Per-test spec knowledge therefore becomes a build product, not documentation effort: every test
carries its refs, and `TEST_SPEC_MAP.md` is always current by construction.

---

## 5. Workstreams

### W0 — Mechanize traceability (do first; everything else depends on it)

- Seed the catalog from the audit sweeps; record basis/errata date in each file header.
- Add `SpecRef`/`RequiresCapability`, assign A1/A2 IDs, retrofit annotations (mechanical).
- Build the generator + CI profile + traceability spec.
- Publish `docs/TEST_SPEC_MAP.md` for all 186 current tests; retire the hand-written root docs.

### W1 — Make the existing signal trustworthy (the P0 set)

1. `scim.strictScimType=true` mode: keyword omission is a recorded deviation (and failure in
   strict mode); keyword mismatch always fails. Default strict for validator runs, with an
   explicit lenient escape hatch.
2. Capability gating driven by a fail-loud SPC loader with three states
   (supported / unsupported / unknown); no silent `false` on fetch failure.
3. Structured deviations (see §4.3); capture mode must not suppress them.
4. Correct the artifacts and test comments: swapped §3.5.2.2/.3, "§3.1" → §3.3,
   `Content-Location` → RFC 7643 §3.1, `externalId` refs, `bulkId` echo claim, 403 note,
   remove fabricated quotes, fix FLT_36 and PAT_17, stale tracker line refs.
5. RFC-alignment fixes: `/Me` accepts {200, 308, 501}; `itemsPerPage <= count`; PUT accepts
   cleared **or** defaulted attributes; auth scheme discovered from SPC instead of hard-wired
   Bearer; reject NEG_19's 201-on-unknown-schema branch or demote it to a recorded deviation.
6. Data integrity: per-class `PREFIX` (A5), exact result-set assertions, cleanup verification.

### W2 — Close RFC 7644 requirements

Grouped additions (full enumeration in `research_notes/.../01_rfc7644_protocol_coverage.md`):

- **Create/POST**: message schemas not discoverable at `/Schemas`; client-supplied `id`
  rejected/ignored; `null`/`[]` semantics; default returned set.
- **Query**: root `meta.resourceType` scoping; `tooMany` via `filter.maxResults`; filter on
  `schemas`; unknown query params ignored; full `SearchRequest` (sort/pagination/projection);
  pagination via `/.search`; `gt/ge/lt/le` boundary + Boolean/Binary → 400 `invalidFilter`.
- **PATCH** (structure suite): empty `Operations`, missing/multiple `op`, `value` required for
  `add`, pathless `add`, `remove` without path → `noTarget`, `replace` on absent target → add,
  `primary:true` unsets others, 204 response branch, 200 when `attributes` present.
- **DELETE**: `If-Match` support; soft-delete exclusion from queries; recreate-after-delete.
- **Bulk** (§3.7.1–.4): `bulkId` required on POST; per-op `version`; `maxPayloadSize` → 413
  with limit in body; circular refs; continue-after-failure (fail then succeed in one request);
  `failOnErrors` echo semantics.
- **Errors/§3.12**: error bodies on 401/403; `detail`; `invalidVers`; `sensitive`; Table 8
  status semantics.
- **Versioning/§3.13**: `/v2` present, omitted, and unsupported version.
- **Content/§3.8**: UTF-8, default media type, Accept rules; keep 406/415 as documented
  suite assumptions rather than RFC MUSTs.

### W3 — Close RFC 7643 requirements

- Extend `validateAttributeCharacteristics` to enforce §2.2 defaults, applicability and
  `caseExact`/`canonicalValues`, and to validate all sub-attributes (list from
  `research_notes/.../02_rfc7643_schema_coverage.md`).
- Behavioral tests for: attribute type validation (wrong-typed values → 400), `caseExact=true`
  comparisons, canonical value rejection, `primary` at-most-once, `schemas` array rules
  (duplicates, unknown URNs), `id` case-exactness and reserved `bulkId`, `externalId`
  case-exactness and provisioning scope, `meta` relations (`location` = `Content-Location`,
  `version` = ETag, `lastModified == created`), User multi-valued families (`roles`,
  `entitlements`, `ims`, `photos`, `x509Certificates` single DER cert, `addresses.country`
  ISO 3166-1), password never leaked through errors or `attributes=password`, `manager`
  `$ref` semantics, User `groups` read-only write rejection.
- Errata handling: Group `displayName.required` per erratum 5368; ResourceType `name`
  uniqueness/case per 8475; Schema `meta` per 8417.
- Security: `id` client-supplied on POST; PRECIS case-insensitive `userName` conflicts
  (RFC 7644 §5).

### W4 — Test hygiene and isolation

- Remove `@Stepwise` ordering where independence is achievable; per-class data prefixes;
  cleanup asserts; pin Testcontainers image digests; document external-target behavior.
- Split gated optional-feature tests from core MUST tests so a green core result is meaningful
  on minimal servers (Entra/Okta profile servers skip cleanly).

### W5 — Extension track (after core is green)

- RFC 9865 cursor pagination (`cursor`/`nextCursor`), RFC 9967 async operations.
- Optional: interop-profile conformance checks (draft-zollner), fuzz/injection suite expansion.

---

## 6. Milestones

| Phase | Content | Acceptance | Rough effort |
| :-- | :-- | :-- | :--: |
| M0 | W0 traceability | Catalog seeded; 186 tests annotated; generator + CI gate green; `docs/TEST_SPEC_MAP.md` published | 4–6 d |
| M1 | W1 signal fixes | Strict run on reference target with zero silent deviations; corrected generated matrix | 6–8 d |
| M2 | W2 protocol gaps | All RFC 7644 MUST/SHOULD rows are `tested`, `optional` (gated) or `na`; no `gap` left | 8–12 d |
| M3 | W3 schema gaps | Same for RFC 7643 | 6–10 d |
| M4 | W4 hygiene | Independent tests, no shared-state collisions, pinned images | 3–5 d |
| M5 | W5 extensions | RFC 9865/9967 capability-gated suites | 5–8 d |

Single-developer estimate for M0–M4: roughly 6–8 weeks. M0 is the only hard dependency for
everything else; M1 can run in parallel with catalog seeding.

---

## 7. Decisions Needed (with recommendation)

1. **Strict default**: recommend strict for validator/CI runs (`scim.strictScimType` default
   true), `-Dscim.lenient=true` retained for exploratory runs against known-partial servers.
2. **Optional feature policy**: gate on the advertised SPC; if the SPC cannot be loaded, run
   the probes but mark the whole run `capabilities-unknown` (never a silent pass).
3. **Catalog basis**: pin "RFC as published + verified errata on <date>"; extension RFCs live
   in separate catalog files so core results stay stable.
4. **Docs**: generated `docs/*` become the only source; root `SCIM_SPEC_COVERAGE.md` and
   `SCIM_VALIDATOR_ASSESSMENT.md` are replaced by short pointers (or deleted once generated
   docs cover them).
5. **`na` register owner**: one reviewer signs off each `na` row (security/observability
   exclusions are the only place real gaps can hide).

---

## 8. What the Output Looks Like

`docs/TEST_SPEC_MAP.md` sample:

| Test ID | Test | File:line | RFC | Section | Requirement ID | Requirement | Mode |
| :-- | :-- | :-- | :--: | :-- | :-- | :-- | :-- |
| FLT_01 | eq operator returns exact match | A5_FilteringSpec.groovy:17 | 7644 | 3.4.2.2 | RFC7644-3.4.2.2-R1 | `eq` comparison (case per `caseExact`) | strict |
| PAT_04 | remove clears externalId | A4_PatchOperationsSpec.groovy:97 | 7644 | 3.5.2.2 | RFC7644-3.5.2.2-R2 | remove single value | strict |
| GRP_01 | POST group creates with members | A6_GroupLifecycleSpec.groovy:41 | 7644 | 3.3 | RFC7644-3.3-R1 | 201 + `Location` + representation | strict |
| SRT_02 | sort descending | A5_SortingSpec.groovy:44 | 7644 | 3.4.2.3 | RFC7644-3.4.2.3-R2 | `sortOrder=descending` honored | gated (sort) |

`target/scim-run/deviations.json` sample:

```json
[{"test": "SRT_02", "requirement": "RFC7644-3.4.2.3-R2", "observed": "ascending",
  "expected": "descending", "severity": "deviation", "target": "api.scim.dev"}]
```

Regenerated coverage row sample (corrected):

```
| RFC 7644 §3.5.2.2 | PATCH remove without path → 400 noTarget | Gap | — |
| RFC 7644 §3.5.2.2 | PATCH remove single value / filter path | Covered | PAT_04, PAT_07 |
| RFC 7644 §3.5.2.3 | PATCH replace / valuePath / absent target treated as add | Partial | PAT_02 … |
```

---

## 9. First PRs

1. **PR1 — traceability skeleton**: `SpecRef` + `SpecTraceabilitySpec` + generator; catalog
   seeded for RFC 7644 §3.4.2.2 and §3.5.2–.3 only; A4/A5 annotated for those sections;
   CI `verify-spec-coverage` profile green.
2. **PR2 — strict signal**: strict `scimType`, structured deviations, capture-mode fix,
   `/Me` 200/308/501, `itemsPerPage <= count`.
3. **PR3 — capability gating**: three-state SPC loader, `@RequiresCapability`, gated PATCH /
   bulk / ETag / sort / `/.search` / enterprise suites.
4. **PR4 — catalog completion**: full RFC 7643/7644 sweeps, retrofit all 186 tests, publish
   `docs/TEST_SPEC_MAP.md`.
5. **PR5+ — gap closure**: W2 then W3, one grouped PR per subsection family (PATCH structure,
   filtering edges, bulk limits, schema semantics, error taxonomy).
