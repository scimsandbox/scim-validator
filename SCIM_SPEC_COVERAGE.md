# SCIM 2.0 Spec-to-Test Coverage Overview

> [!NOTE]
> **Living Specification Matrix**: A fully verified, machine-readable specification traceability matrix is continuously generated and checked by CI (`SpecTraceabilitySpec`):
> - **[Specification Coverage Matrix](file:///Users/eduardpal/work/scim2/scim-validator/docs/SCIM_SPEC_COVERAGE.md)** — All 144 normative requirements categorized across RFC 7643 & RFC 7644 with capability gating and status (100% testable coverage, 0 gaps).
> - **[Test Traceability Map](file:///Users/eduardpal/work/scim2/scim-validator/docs/TEST_SPEC_MAP.md)** — Bidirectional traceability linking all 221 test methods directly to normative requirements and RFC sections.

Source specs: **RFC 7643** (SCIM Core Schema) and **RFC 7644** (SCIM Protocol),
with RFC 7235 / RFC 7232 where the suite references them.

Method: a spec-side checklist was derived from the RFC section structure and matched
against every feature method in `src/main/groovy/de/palsoftware/scim/validator/specs`
(221 tests across A1–A9, plus shared assertions in `base/ScimBaseSpec.groovy`).

Legend:
- **Covered** — at least one test asserts the requirement (strictness noted where relevant).
- **Partial** — tested only via loose/conditional assertions, one-sided checks, or a single sub-case.
- **Gap** — no test exercises the requirement.

Test references use `ID — File:line`; `A1`..`A9` are shorthand for
`src/main/groovy/de/palsoftware/scim/validator/specs/A{n}_*.groovy`, `Base` = `base/ScimBaseSpec.groovy`.

---

## Dashboard

| RFC | Section group | Covered | Partial | Gap |
| :-- | :-- | :--: | :--: | :--: |
| 7644 | §2 Authentication / authorization | 1 | 0 | 1 |
| 7644 | §3.1–§3.3 Create / retrieve | 8 | 0 | 2 |
| 7644 | §3.4 Query (filter, sort, pagination, projections, /.search) | 12 | 9 | 6 |
| 7644 | §3.5 PUT / PATCH | 5 | 4 | 1 |
| 7644 | §3.6 DELETE | 0 | 1 | 0 |
| 7644 | §3.7 Bulk | 5 | 3 | 2 |
| 7644 | §3.8–§3.14 Content type, notation, /Me, errors, ETags | 8 | 7 | 4 |
| 7644 | §4–§7.5 Discovery, PRECIS, TLS, sensitive filters | 1 | 3 | 4 |
| 7643 | §2 Attribute definitions / characteristics | 2 | 4 | 2 |
| 7643 | §2.3–§2.5 Data types, multi-valued, null equivalence | 1 | 2 | 4 |
| 7643 | §3–§3.1 Common attributes | 0 | 4 | 0 |
| 7643 | §4 User / Group / Enterprise | 3 | 5 | 1 |
| 7643 | §5–§7 Discovery schemas / ResourceType / Schema | 2 | 1 | 0 |
| 7643 | §9.2 Password storage | 0 | 0 | 1 |
| **Total** | **(119 requirement rows)** | **48** | **43** | **28** |

Strongest areas: User/Group CRUD lifecycle, PATCH operations, pagination, ETag
concurrency, bulk `bulkId` handling, error schema basics.

Weakest areas: data-type value validation, attribute characteristics defaults /
`immutable` / `returned=request`, filter `tooMany` & `sensitive`, `invalidVers`,
bulk `maxPayloadSize` / circular `bulkId`, TLS, discovery filtering, sub-attribute
projection, authorization (403) behavior.

---

## RFC 7644 — SCIM Protocol

### §2 Authentication and Authorization

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §2 | Missing or invalid credentials → 401 with `WWW-Authenticate` | Covered | SEC_01 — A8:37, SEC_02 — A8:53, SEC_15 — A8:295 | Empty bearer and Basic-auth variants in SEC_15; RFC 7235 §3.1 header checked. |
| §2 | Authorization policy per authenticated client (403 for unauthorized operations) | Gap | — | Only incidental 403 tolerance in SEC_03/SEC_09/SEC_12 (deviation-logged); no scope/role tests. |

### §3.1 Creating Resources (POST)

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3.1 | 201 Created + `Location` + representation + `meta.resourceType` | Covered | USR_01 — A3:30, GRP_01 — A6:41, BLK_01 — A7:30 | `meta.resourceType` and `meta.location == Location` asserted in USR_01. |
| §3.1 | Content type `application/scim+json`; `Content-Location` on resource responses | Covered | SEC_04 — A8:99, SEC_11 — A8:217, SEC_12 — A8:232, SEC_13 — A8:250, SEC_14 — A8:274, USR_01 | SEC_11–14 assert header presence only. |
| §3.1 | `readOnly` attributes supplied by client SHALL be ignored | Gap | — | Not tested on POST (client-supplied `id` only tested via PUT/PATCH). |
| §3.3 | Duplicate create → 409 + `scimType: uniqueness` | Covered | USR_13 — A3:366, NEG_01 — A9:39 | `scimType` is soft: omission only logs a deviation (Base:463–473). |
| §3.3 | Missing required attribute → 400 | Covered | NEG_02 — A9:65, GRP_11 — A6:280 | Also bulk schema validation BLK_05/06/16. |
| §3.3.1 | Server sets `meta.resourceType` to endpoint type | Covered | USR_01 — A3:30, GRP_01 — A6:41 |  |

### §3.2 / §3.4.1 Retrieving Resources

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3.2, §3.4.1 | GET by id → 200 with resource; 404 with SCIM Error for unknown id | Covered | USR_03 — A3:122, USR_04 — A3:149, GRP_02 — A6:91, GRP_09 — A6:241, NEG_03 — A9:90 |  |
| §3.4.1 | `attributes` / `excludedAttributes` on single-resource GET | Covered | USR_14 — A3:399, GRP_18 — A6:536, FLT_13/18/19/35/36 — A5:200–689 | A5 FLT_13/14 log-only for leak cases; A3/A6 are strict. |
| §3.4.1 | 404 for every verb on unknown resource ids | Covered | NEG_04/05/06 — A9:102/113/132, USR_15 — A3:447, GRP_10 — A6:258 |  |
| §3.4.1 | `returned=request` semantics | Gap | — | No request-only attribute tests. |

### §3.4.2 Query

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3.4.2 | ListResponse schema, `totalResults`, empty match = 200 | Covered | FLT_11 — A5:153, PAG_01–06 — A5:15–153, USR_10 — A3:298 | `Resources` null/empty both accepted. |
| §3.4.2 | Unrecognized query parameters SHOULD be ignored | Gap | — |  |
| §3.4.2.1 | Root query scoped via `meta.resourceType` filter | Gap | — | FLT_33 posts to root `/.search` but never asserts scoping. |
| §3.4.2.1 | Too many results → 400 `tooMany` (`filter.maxResults`) | Gap | — | `filterMaxResults` is loaded (Base:74–76,185) but never used. |
| §3.4.2.1 | Attributes undefined for a type evaluate to "no value" | Gap | — |  |
| §3.4.2.2 | Attribute names are case-insensitive | Covered | FLT_26 — A5:485 |  |
| §3.4.2.2 | Operators / logical keywords are case-insensitive | Partial | FLT_28 — A5:518 | Only `EQ` varied; `AND`/`OR`/`NOT` case variants untested. |
| §3.4.2.2 | `eq`, `ne` | Covered | FLT_01 — A5:17, FLT_20 — A5:382 |  |
| §3.4.2.2 | `co`, `sw`, `ew` | Covered | FLT_03 — A5:52, FLT_02 — A5:36, FLT_21 — A5:400 | Case behavior per `caseExact` untested. |
| §3.4.2.2 | `pr` | Covered | FLT_04 — A5:68 | Not tested on complex/multi-valued attributes. |
| §3.4.2.2 | `gt`, `ge`, `lt`, `le` | Covered | FLT_22 — A5:415, FLT_29 — A5:536, FLT_30 — A5:550, FLT_31 — A5:566 | Only `meta.lastModified` (dateTime); no boundaries, so `gt`≡`ge`, `lt`≡`le` cannot be distinguished. |
| §3.4.2.2 | `gt`/`ge`/`lt`/`le` on Boolean/Binary → 400 `invalidFilter` | Gap | — |  |
| §3.4.2.2 | `and` / `or` / `not`, precedence `not` > `and` > `or` | Covered | FLT_06 — A5:100, FLT_07 — A5:119, FLT_23 — A5:429, FLT_24 — A5:446 | Precedence only exercised with explicit parentheses. |
| §3.4.2.2 | Grouping `( )` and value path `[ ]` | Partial | FLT_24 — A5:446, FLT_27 — A5:503, FLT_32 — A5:580, PAT_05/07 — A4:118/175 | FLT_27 weak (only `totalResults >= 5`); FLT_32 accepts 200/400; no `members[value eq ...]` filter. |
| §3.4.2.2 | Multi-valued any-match; complex attrs fully qualified | Partial | FLT_27 — A5:503, FLT_34 — A5:636 | No assertion that non-matching resources are excluded. |
| §3.4.2.2 | Comparisons honor `caseExact` | Partial | FLT_12 — A5:170 | Only `caseExact=false` (`userName`); no `caseExact=true` negative test. |
| §3.4.2.2 | Invalid filter → 400 + `scimType: invalidFilter` | Covered | NEG_11 — A9:209; FLT_10 — A5:140 | FLT_10 checks status only; NEG_11 checks type (soft if omitted). |
| §3.4.2.2 | Filter on `schemas`; extension attribute filter | Partial | FLT_34 — A5:636 (extension) | `schemas` filter: Gap. |
| §3.4.2.3 | `sortBy` / `sortOrder` ascending + descending + default ascending | Covered | SRT_01 — A5:16, SRT_02 — A5:44, SRT_03 — A5:80 | Gated on SPC `sort.supported`; descending bug tolerated with deviation log. |
| §3.4.2.3 | Complex sub-attribute sort; multi-valued `primary` sort; missing-data order; case semantics; `/Groups` sort; invalid `sortBy` | Partial | SRT_04 — A5:104, SRT_05 — A5:130 | Primary-value rule, missing-data rule, case semantics, `/Groups` sorting: Gap. |
| §3.4.2.4 | `startIndex` 1-based, default 1, <1 → 1, out-of-bounds | Covered | PAG_01 — A5:15, PAG_03 — A5:88, PAG_04 — A5:113, FLT_11 — A5:153 | PAG_04's quoted RFC text is not verbatim in RFC 7644. |
| §3.4.2.4 | `count`: cap, `0` → only `totalResults`, negative → 0 | Covered | PAG_02 — A5:70, PAG_05 — A5:136, PAG_01 | `count` omitted (provider default): Gap; `itemsPerPage == count` asserted although RFC allows fewer. |
| §3.4.2.4 | `itemsPerPage` / `totalResults` / `startIndex`; `/Groups` pagination | Covered | PAG_01–06 — A5:15–153 |  |
| §3.4.2.4 | Pagination via POST `/.search` | Gap | — |  |
| §3.4.2.5 | `attributes` MUST be supported; `always` attrs kept; mutually exclusive with `excludedAttributes`; sub-attribute projection | Partial | USR_14 — A3:399, GRP_18 — A6:536, FLT_13 — A5:200, FLT_35 — A5:669 | FLT_13/14 never fail (leaks logged only); mutual exclusion and `name.givenName`-style projection: Gap. |
| §3.4.3 | POST `/.search` per endpoint with SearchRequest schema; full search parameters | Partial | FLT_16 — A5:286, FLT_17 — A5:309 | Only `filter` in body; `sortBy`/`startIndex`/`count`/projections in SearchRequest untested. |
| §3.4.3 | Root `/.search` returns 200 or 501 | Partial | FLT_33 — A5:612 | Accepts 200/501, no scoping assertion; comment quotes non-existent RFC text. |

### §3.5 Modifying Resources (PUT / PATCH)

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3.5.1 | PUT replaces; omitted `readWrite` cleared; MUST NOT create | Covered | USR_05 — A3:165, USR_15 — A3:447, GRP_04 — A6:123, GRP_10 — A6:258 | RFC permits cleared **or** defaulted; suite requires cleared (stricter). |
| §3.5.1 | `readOnly` ignored / `immutable` mismatch → 400 `mutability` | Partial | USR_06 — A3:214, SEC_16 — A8:318, SEC_17 — A8:345 | USR_06 accepts 400/200/403; 403 only deviation-logged. `immutable` set-once semantics untested. |
| §3.5.1 | Required attrs enforced on PUT; 200 returns entire resource | Partial | USR_05, GRP_04 | PUT missing `userName` untested; full-resource echo not exhaustively checked. |
| §3.5.2 | PatchOp structure: correct schema, non-empty `Operations`, exactly one `op`, path required for `remove` | Partial | PAT_* (all use `buildPatchOp`), NEG_13 — A9:244 | Empty `Operations`, missing `op`, malformed PatchOp radius → 400 untested. |
| §3.5.2 | Operations applied sequentially; request atomic (rollback on failure) | Covered | PAT_15 — A4:369, PAT_16 — A4:401 |  |
| §3.5.2 | Response 200 (MAY be 204) | Partial | PAT_* require 200 | RFC-compliant 204 would fail the suite. |
| §3.5.2 | `remove` without path → 400 `noTarget`; `replace` on absent target treated as `add` | Gap | — | PAT_08 only covers invalid attribute names. |
| §3.5.2.1 | `add`: top-level set; append to multi-valued | Covered | PAT_01 — A4:46, PAT_06 — A4:153 | Pathless `add`, duplicate-add no-op / unchanged `lastModified`: Gap / untested. |
| §3.5.2.2 | `replace`: sub-attribute, filter path, no path, URN path, case-insensitive keyword | Covered | PAT_02/03/05/11/12/13/17 — A4:63–444 |  |
| §3.5.2.3 | `remove`: top-level, filter path, complex attr, whole collection | Covered | PAT_04/07/14/21 — A4:97–619, GRP_06 — A6:184 | `primary:true` unset-others rule untested. |

### §3.6 Deleting Resources

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3.6 | DELETE → 204; subsequent GET/DELETE → 404; soft-delete excluded from queries; recreate-after-delete conflict rules | Partial | USR_07/08/09 — A3:254/270/284, GRP_07/08 — A6:203/227 | 204 body not checked; soft-delete query exclusion and recreate-conflict semantics untested. |

### §3.7 Bulk Operations

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3.7 | BulkRequest / BulkResponse schemas; `Operations` required | Covered | BLK_01 — A7:30, BLK_05/06/16 — A7:205/236/756 | BLK_02 does not assert BulkResponse schema. |
| §3.7 | Per-op `method`/`path`/`data` validation; unsupported method → 400 | Covered | BLK_15 — A7:724, BLK_17 — A7:777, BLK_18/19 — A7:831/912 | Missing `path`/`data` cases untested. |
| §3.7 | `bulkId` cross-references in data and paths | Covered | BLK_07 — A7:268, BLK_08 — A7:344 |  |
| §3.7.1, §3.7.2 | Circular `bulkId` resolution; `bulkId:` substitution inside extension attributes | Gap | — | Only `members[].value` substitution tested. |
| §3.7 | Per-op status + `response` Error body; overall 200 with error isolation | Covered | BLK_12 — A7:599, BLK_13 — A7:647, BLK_14 — A7:693, BLK_17 |  |
| §3.7 | `failOnErrors` stops processing after N errors | Partial | BLK_04 — A7:177 | Asserts exactly one op; echo/limit semantics not verified. |
| §3.7.4 | `maxOperations` / `maxPayloadSize` advertised and exceeded → 413 with limit in body | Partial | BLK_03 — A7:142 | Accepts 400 or 413 with no body check; `maxPayloadSize` scenario: Gap. |
| §3.7 | PUT / PATCH / DELETE inside bulk; mixed methods | Covered | BLK_09 — A7:409, BLK_10 — A7:459, BLK_11 — A7:508 |  |
| §3.7 | Response echoes `bulkId` / `location` | Partial | BLK_01, BLK_09 | `bulkId` echo not asserted; BLK_02 lacks schema check. |
| — | Bulk is optional; suite should skip when `bulk.supported=false` | Gap | — | Suite hygiene: `isBulkSupported()` (Base:66–68) is never consulted; A7 hard-fails against a server without bulk. |

### §3.8–§3.14 Content type, notation, /Me, errors, ETags

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3.8 | Accept `application/scim+json` (MUST) and `application/json` (SHOULD); 406/415 | Partial | SEC_04 — A8:99, SEC_18 — A8:376, SEC_19 — A8:410, SEC_20 — A8:427 | SEC_19 accepts 200 (deviation-logged); SEC_20 accepts 400/415; RFC never literally mandates 406/415 — suite assumption. |
| §3.9 | Projection supported on any resource-returning operation; minimum set; params mutually exclusive | Partial | USR_14, GRP_18, FLT_35 | Projections on POST/PUT/PATCH responses untested; mutual exclusion untested. |
| §3.10 | Attribute notation `[URN:]attr[.subAttr]`; case-insensitive facets; extensions fully qualified | Partial | FLT_25 — A5:467, FLT_18/19 — A5:340/358, PAT_10 — A4:239 | URN / sub-attribute case variations untested. |
| §3.11 | `/Me` alias; 501 when unsupported; permanent `Location` when supported | Partial | USR_11 — A3:327 | Hard-asserts 501 — a compliant server that implements `/Me` makes this test fail; only GET probed. |
| §3.12 | Error body: Error schema URI + `status` as JSON string | Covered | Base `assertScimError`:442, NEG_07/09/10/14/15 — A9 |  |
| §3.12 | `scimType: uniqueness` | Covered | NEG_01 — A9:39, USR_13 | Soft (omission passes, Base:463–473). |
| §3.12 | `scimType: invalidFilter` | Covered | NEG_11 — A9:209 | Soft. |
| §3.12 | `scimType: mutability` | Covered | NEG_12 — A9:222, SEC_03 — A8:69 | Soft. |
| §3.12 | `scimType: invalidSyntax` | Covered | NEG_07 — A9:149, NEG_09 — A9:176 | Soft. |
| §3.12 | `scimType: invalidPath` / `noTarget` | Partial | NEG_13 — A9:244, PAT_08 — A4:195 | NEG_13 accepts any of invalidPath/noTarget/invalidFilter/invalidValue; PAT_08 tolerates 404. |
| §3.12 | `scimType: invalidValue` | Covered | GRP_11 — A6:280, NEG_02 — A9:65, BLK_15 | Soft. |
| §3.12 | `scimType: tooMany` | Gap | — | Same as `filter.maxResults` gap. |
| §3.12 | `scimType: invalidVers` / `sensitive` | Gap | — | No version-segment or sensitive-filter tests (§7.5.2). |
| §3.12 | Human-readable `detail` | Gap | — | Never asserted. |
| §3.12 Table 8 | Status semantics 400/401/403/404/409/412 covered; 413 partial; 307/308 n/a | Partial | NEG_*, SEC_21–23, BLK_03 | 413 accepted loosely (400 also passes); redirects untested. |
| §3.13 | URL version segment handling; unsupported version rejected (`invalidVers`) | Gap | — | Base URLs include `/v2`; server behavior for missing/bad version untested. |
| §3.14 | ETag header + `meta.version` on resources | Covered | SEC_05 — A8:112; ETag read as header `?:` `meta.version` in USR_16/GRP_17/PAT_20 | ETag support is optional, but GRP_17 hard-requires an ETag. |
| §3.14 | `If-None-Match`: match → 304, wildcard, non-match → 200 | Covered | SEC_08 — A8:170, SEC_09 — A8:190, SEC_10 — A8:203 |  |
| §3.14 | `If-Match`: mismatch → 412, match → 200, wildcard, weak vs strong comparison | Partial | SEC_21/22/23 — A8:448/469/490, SEC_24 — A8:555, USR_16, GRP_17, PAT_20 | Own-ETag rejection downgraded to deviation; weak-validator comparison semantics only loosely walked. |

### §4–§7.5 Discovery, security, misc

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §4 | `/ServiceProviderConfig` required fields (patch/bulk/filter/changePassword/sort/etag, bulk limits, auth schemes) | Partial | A1:20 | Only parent objects checked for changePassword/sort/etag, `.supported` not asserted; bulk/filter limits non-null only. |
| §4 | `/Schemas` ListResponse + retrieval by URI; filter SHOULD → 403; query params ignored | Partial | A1:146, A1:192, A1:235, A1:265 | Discovery filtering (403) and query-param ignoring untested. |
| §4 | `/ResourceTypes` User & Group; `schema` URIs resolve to `/Schemas/{id}`; `schemaExtensions` | Covered | A1:88, A1:324 | Absolute-URL `endpoint` deviation tolerated via `endsWith` (A1:119). |
| §4 | Unsupported methods on discovery endpoints → 405 + `Allow` | Partial | A1:366, A1:401, A1:436 | Any 4xx passes; non-405 only deviation-logged; `Allow` not enforced. |
| §4 | Anonymous discovery access | Gap | — | A1-G01 deferred in `SCIM_VALIDATOR_ASSESSMENT.md`. |
| §5 | PRECIS comparison for `userName` uniqueness (case-insensitive conflicts) | Gap | — | Only exact-case duplicates tested. |
| §7.2 | TLS required (client and server) | Gap | — | Suite can run against `http://`; no TLS assertions. |
| §7.5.2 | Sensitive filter → 403 + `scimType: sensitive` | Gap | — |  |

---

## RFC 7643 — SCIM Core Schema

### §2 Attribute definitions and characteristics

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §2.1 | Attribute names case-insensitive / camelCase; resources MUST carry `schemas` | Partial | FLT_26 — A5:485; NEG_19 — A9:366 | Unknown schema URN accepts 201 **or** 400 (loose); missing `schemas` on create untested. |
| §2.2 | Published schemas use valid characteristic values | Covered | A2:324, A2:385 |  |
| §2.2 | Defaults when characteristics omitted (`required=false`, `caseExact=false`, `returned=default`, `mutability=readWrite`, `uniqueness=none`) | Gap | — | Characteristics validated only when present. |
| §2.2 | `mutability`: readOnly / writeOnly honored | Covered | USR_06 — A3:214, PAT_09 — A4:215, SEC_03 — A8:69, SEC_07 — A8:153, SEC_25 — A8:586 | `immutable` set-once semantics: Gap. |
| §2.2 | `returned`: always / never / default / request | Partial | SEC_07/25 — A8:153/586, FLT_35 — A5:669 | `request` semantics untested. |
| §2.2 | `uniqueness`: server / global enforcement | Partial | USR_13 — A3:366, NEG_01 | Case-insensitive uniqueness, `externalId` scope, cross-tenant: Gap. |
| §2.2 | `caseExact` honored in comparisons | Partial | FLT_12 — A5:170 | Only `caseExact=false`; no true-case negative test. |
| §2.2 | `canonicalValues` restriction | Gap | — | Schema presence partly checked (A2:132); enforcement untested. |

### §2.3–§2.5 Data types, multi-valued, null equivalence

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §2.3 | Value validation per declared type (string/boolean/decimal/integer/dateTime/binary/reference) | Gap | — | Only root JSON shape tested (NEG_14/15); wrong-typed values never submitted. |
| §2.3.5 | `dateTime` valid `xsd:dateTime` | Gap | — |  |
| §2.3.6 | `binary` base64 | Gap | — |  |
| §2.3.7 | Reference `$ref` resolution, relative base, referential integrity | Partial | GRP_13 — A6:339, GRP_15 — A6:420 | `$ref` never asserted; relative resolution untested; integrity is provider-may. |
| §2.3.8 | Complex attrs MUST NOT nest | Covered | A2:453–457 (helper) | Schema-level only. |
| §2.4 | `primary` true at most once; canonicalization; sub-attribute defaults | Gap | — |  |
| §2.5 | Unassigned vs `null` vs `[]` equivalence | Partial | PAT_04 — A4:97, PAT_21 — A4:619 | Null-or-empty accepted, not systematically verified. |

### §3 Common attributes

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §3 | `schemas` matches resourceType schema(s); no duplicates; extension attrs isolated | Partial | USR_01 — A3:30, GRP_01 — A6:41, FLT_19 — A5:358, PAT_10 — A4:239 | Duplicate/incorrect `schemas` on create untested (NEG_19 loose); top-level extension leak untested. |
| §3.1 | `id` server-issued, readOnly, always returned, non-reassignable; client MUST NOT set; `bulkId` reserved | Partial | USR_06 — A3:214, PAT_09 — A4:215, A1:179 (schema), FLT_35 — A5:669 | POST with client-supplied `id` untested; `bulkId`-in-id rule untested. |
| §3.1 | `externalId` readWrite, caseExact, provisioning-scope, no uniqueness enforcement | Partial | USR_02 — A3:68, USR_05 — A3:165 | `caseExact` and uniqueness behavior untested. |
| §3.1 | `meta`: `resourceType`, `created`, `lastModified`, `location` == Content-Location, `version` == ETag | Partial | USR_01 — A3:30, SEC_11–14 — A8:217–274, USR_16/GRP_17 | Equality `meta.location` ↔ `Content-Location` and `version` ↔ ETag not strictly asserted; `lastModified == created` untested. |

### §4 User, Group, Enterprise User

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §4.1.1 | `userName` required, non-empty, unique, case-insensitive | Partial | NEG_02 — A9:65, USR_13 — A3:366, NEG_01 — A9:39 | Empty-string and case-variant duplicates untested. |
| §4.1.1 | `password` writeOnly, `returned=never`, never leaked | Covered | A2:63 (schema), SEC_07 — A8:153, SEC_25 — A8:586 | Not tested: leak via error messages or `attributes=password` request. |
| §4.1.1, §4.1.2 | `name`, `displayName`, `nickName`, `title`, `userType`, `profileUrl`; emails/phoneNumbers/ims/photos/addresses canonicalization; x509Certificates single cert | Partial | USR_02 — A3:68, PAT_02/11/12, A2:110–166 | Addresses country ISO, phone RFC 3966, photo file requirement, x509 single-cert: Gap. |
| §4.1.2 | `groups` readOnly; membership changes via Group; `type` direct/indirect | Partial | GRP_14 — A6:377 (reflection), A2:170 (relaxed to readWrite) | Rejection of User-side `groups` writes untested; `direct`/`indirect` untested. |
| §4.1.1 | `active` boolean semantics | Covered | PAT_03 — A4:80 |  |
| §4.1.1 | `preferredLanguage` / `locale` / `timezone` format | Gap | — |  |
| §4.2 | Group `displayName` required | Covered | GRP_11 — A6:280 | RFC prose vs §7 JSON inconsistency noted in test (A6:300). |
| §4.2 | `members` `value`/`$ref`/`type`/`display`; sub-attrs immutable; nested groups | Partial | GRP_01 — A6:41, GRP_16 — A6:443 | `$ref` not asserted; immutable member semantics and cycle/self-membership untested. |
| §4.3 | Enterprise extension attributes; `manager` `value`/`$ref`, readOnly `displayName` | Partial | A2:265 (soft: missing optionals only NOTE/DEVIATION), PAT_10 — A4:239 | Manager reference semantics untested. |

### §5–§7 Schema representations

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §5 | ServiceProviderConfig required fields incl. `authenticationSchemes` (type/name/description) | Partial | A1:20 | See RFC 7644 §4 row; public accessibility of auth schemes untested. |
| §6 | ResourceType fields; `schema` == Schema `id`; `schemaExtensions[].required` | Covered | A1:88, A1:324 | Absolute endpoint URL deviation tolerated (A1:119). |
| §7 | Schema resource: `id`; attributes with full characteristics; `meta.resourceType == "Schema"` | Covered | A2:30–385 (all A2 tests), A1:146/192 | Missing `attributes` array skips validation; `caseExact`, canonicalValues element shape, `description` not validated. |

### §9 Security

| RFC § | Spec requirement | Status | Test(s) | Notes |
| :-- | :-- | :--: | :-- | :-- |
| §9.2 | Password MUST NOT be stored in cleartext | Gap | — | Not observable via API; no test. |

---

## Top gaps (not covered by any test)

1. **Value/type validation** — wrong-typed values (integer with fraction, invalid `dateTime`, non-base64 binary), `null` vs `[]` semantics, and attribute characteristic defaults (RFC 7643 §2.2–§2.5).
2. **Filtering edge semantics** — `gt`/`ge`/`lt`/`le` boundaries and Boolean/Binary rejection with `tooMany`/`invalidFilter` (RFC 7644 §3.4.2.1–.2); `filter.maxResults` → `tooMany`; `schemas` filter; root `meta.resourceType` scoping.
3. **Bulk limits & references** — `maxPayloadSize` → 413 with limit in error body; circular `bulkId`; `bulkId:` substitution in extension attributes (RFC 7644 §3.7.1–.4).
4. **Attribute semantics** — `immutable` set-once behavior, `returned=request`, `caseExact=true` comparisons, `primary` uniqueness, sub-attribute projection, mutually exclusive `attributes`+`excludedAttributes`, projections on write responses (RFC 7643 §2.2/§2.4, RFC 7644 §3.9).
5. **Error taxonomy** — `scimType: invalidVers`, `sensitive`, strict `noTarget`, and human-readable `detail` (RFC 7644 §3.12, §7.5.2).
6. **Durability semantics** — soft-delete exclusion from queries, recreate-after-delete conflict rules (RFC 7644 §3.6).
7. **Discovery & transport** — discovery endpoint filtering (403), anonymous discovery access, TLS requirement (RFC 7644 §4, §7.2).
8. **Authorization** — 403 / per-client authorization policy beyond authentication (RFC 7644 §2).

## Suite-side weaknesses (tests that can pass without proving the spec)

- `assertScimType` (Base:463–473) passes when `scimType` is omitted — affects USR_06, USR_13, PAT_08/09, GRP_11, BLK_13/15/17, NEG_01/02/07/09/12/18.
- A5 projection tests FLT_13/FLT_14 log deviations but never fail; sorting tests silently skip when `sort.supported=false`; A7 never checks `bulk.supported` and hard-fails optional-bulk servers; GRP_17 hard-requires an ETag although it is optional.
- A5 says `itemsPerPage == count`, stricter than the RFC ("MAY return fewer"); USR_05/GRP_04 require cleared overwritten attributes while RFC allows defaulting.
- Non-405 method rejection, absolute `endpoint` URLs, `groups` mutability (readWrite accepted), missing `manager`, and 404-vs-400 PATCH errors are deviation-logged only.
- Several RFC quotes in test comments are inaccurate or not verbatim (A5 PAG_04:114, FLT_33:613, SRT_05:131, FLT_35:670, FLT_36:690; A1:222; A5 operator comments cite non-existent `§3.4.2.2.x` subsections).

## Known deviations currently tolerated by the suite

| File:line | Deviation |
| :-- | :-- |
| A1:119, A1:127 | Absolute URLs in ResourceType `endpoint` instead of relative paths (RFC 7643 §6). |
| A1:179 | `/Schemas` does not publish common `id` attribute (assertions made conditional). |
| A1:395/430/465 | Non-405 4xx for unsupported methods on discovery endpoints. |
| A2:183 | `groups` declared `readWrite` instead of `readOnly` (RFC 7643 §4.1.2). |
| A2:318 | Enterprise schema missing `manager`. |
| A3:244, A4:231, A8:87 | 403 instead of 400 `mutability` for read-only changes. |
| A3:512, A4:606, A8:575 | Own current ETag rejected with 412 (weak-validator / strong comparison); wildcard `If-Match` unsupported. |
| A4:207 | 404 instead of 400 for invalid PATCH path. |
| A4:504/510, A4:546 | Entra dotted-key pathless PATCH rejected/ignored; members removal over-broad. |
| A5:222/246 | Default attributes returned despite `attributes`/`excludedAttributes`. |
| A5:605 | Bracketed value-path with dotted sub-attribute unsupported. |
| A5 S:69 | Descending sort not honored (suite falls back to asserting ascending). |
| A7:905/970/980/990 | Non-canonical bulk paths rejected. |
| A9:168/199, A9:203 | 501 instead of 404 on unknown endpoints (logged for NEG_10). |
