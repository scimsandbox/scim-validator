# RFC 7643 (SCIM Core Schema) Coverage by the scim-validator Test Suite

Scope/method (2026-10-10, repo working tree inspected read-only; no repo files modified):
- RFC text: https://www.rfc-editor.org/rfc/rfc7643.txt (line numbers below refer to that text; section anchors to https://www.rfc-editor.org/rfc/rfc7643#section-N).
- Errata: https://www.rfc-editor.org/errata/rfc7643 (status sections: Verified 16, Reported 8, Held for Document Update 12, Rejected 5). RFC 7643 is flagged "Updated by RFC9865, RFC9967".
- Local artifacts: `SCIM_SPEC_COVERAGE.md` (matrix), `SCIM_VALIDATOR_ASSESSMENT.md` (tracker), `src/main/groovy/de/palsoftware/scim/validator/base/ScimBaseSpec.groovy` (`Base`), `.../specs/A1..A9_*.groovy`.
- Housekeeping: grep over `specs/` + `base/` shows **zero** occurrences of `phoneNumbers`, `addresses`, `country`, `x509Certificates`, `ims`, `locale`, `timezone`, `preferredLanguage`, `userType`, `entitlements` outside comments (`A2:111`, `A4:17`); `roles` occurs only in an A4 class comment; `nickName` occurs only via PATCH round-trips (PAT_17/USR_05).

---

## KQ1: Is each RFC 7643 normative requirement correctly classified in SCIM_SPEC_COVERAGE.md as Covered/Partial/Gap with the right test references?

### Takeaway
The 7643 matrix (SCIM_SPEC_COVERAGE.md:186–248) is directionally right at section granularity but has four citation errors, one mislabeled inconsistency reference, and materially understates/overstates several statuses — above all `§3.1 externalId` (cited tests never touch `externalId`) and `§4.1.1 password` (all schema assertions are conditional). Several "Covered" rows rest on conditional or soft assertions described below.

### Cited Findings

**§2 Attribute definitions / characteristics (matrix rows at SCIM_SPEC_COVERAGE.md:190–199)**

| RFC § | Matrix row (line) | Matrix verdict | Verified against tests | Correction / precise scope |
| :-- | :-- | :-- | :-- | :-- |
| §2.1 names case-insensitive; resources MUST carry `schemas` | :192 | Partial | FLT_26 (A5:485–499) tests only a mixed-case `UserName` filter. NEG_19 (A9:366–388) posts an unknown `schemas` URN and accepts **201 or 400**. | Keep Partial. No test for a missing `schemas` array; no ABNF test; `schemas` content rules untested (see KQ2). |
| §2.2 published schemas use valid characteristic values | :193 | Covered | A2:324–350 runs `validateAttributeCharacteristics` (A2:419–471) over every schema's `attributes`. A2:385–407 only iterates the **User** schema top-level attributes. | Keep Covered for enum membership *when present*, but note helper limits (KQ3): `caseExact`/`canonicalValues`/`description` never validated; defaults never enforced. |
| §2.2 defaults (`required=false`, `caseExact=false`, `returned=default`, `mutability=readWrite`, `uniqueness=none`) | :194 | Gap | No test asserts behaviour or presence of defaults; helper asserts nothing for omitted characteristics (A2:428/433/438/448 guard on non-null). | Correct as Gap. RFC text: §2.2 lines 419–436. |
| §2.2 mutability honored | :195 | Covered | SEC_03 (A8:69–95, 200/400/403), SEC_16 (A8:318–341), SEC_17 (A8:345–372), PAT_09 (A4:215–235, 200-ignore path), USR_06 (A3:214–250, 403 logged as deviation at A3:242–246). | Correct but over-broad "Covered": every test may pass by ignoring the write with 200; only SEC_16/17 verify the value survived (`meta.created`). `immutable` unenforced. |
| §2.2 returned always/never/default/request | :196 | Partial | Only `never` is exercised: SEC_07 (A8:153–166, substring `"password"` check on one GET) and SEC_25 (A8:586–628, POST/PUT/List bodies). `always` for `id` is only indirectly exercised by FLT_35 (A5:669–685) and conditionally by A2:82–84. | Correct as Partial; `request` is a Gap; no `attributes=password` or filter-by-password test. |
| §2.2 uniqueness server/global | :197 | Partial | USR_13 (A3:366–395) and NEG_01 (A9:39–61) require 409 + (soft) `uniqueness` for duplicate `userName`. GRP_12 (A6:297–335) and NEG_18 (A9:322–354) accept 409 **or** 201 for duplicate `displayName`. | Correct. `global` never tested; case-insensitive uniqueness untested (RFC 7644 §5 PRECIS, rfc7644.txt:4201–4210). |
| §2.2 caseExact honored | :198 | Partial | FLT_12 (A5:170–196) requires `caseExact == false` on the published `userName` and a case-varied match. | Correct. Two caveats: (1) no `caseExact=true` attribute tested, although `id`/`externalId`/`meta.resourceType`/references are case-exact per §3.1/§2.3.7; (2) A5:181 hard-requires the characteristic to be **present** — a server omitting it (default false per §2.2) fails. |
| §2.2 canonicalValues restriction | :199 | Gap | A2:159–167 checks `emails.type.canonicalValues.containsAll(work,home,other)` **only if** `canonicalValues != null`; A2:249–253 same for `members.type`; A2:250–260 only for `display` if present. | Matrix note "Schema presence partly checked (A2:132)" is inaccurate: when a server omits `canonicalValues`, everything passes. Restriction/enforcement untested. |

**§2.3–§2.5 (rows at :203–211)**

| RFC § | Matrix verdict | Verified | Correction |
| :-- | :-- | :-- | :-- |
| §2.3 value validation per declared type | Gap (:205) | NEG_14/15 (A9:262–286) only reject array/primitive roots. | Correct. Also no test for decimal-with-fraction, integer exponent, boolean non-literal, invalid `dateTime`, non-base64 `binary`. |
| §2.3.5 dateTime | Gap (:206) | none | Correct. |
| §2.3.6 binary | Gap (:207) | none | Correct. Note §2.3.6 also requires case-exactness (rfc7643.txt:545–546) — untested. |
| §2.3.7 reference | Partial (:208) | GRP_13 (A6:339–373), GRP_15 (A6:420–439). | Both test referential integrity, which RFC 7643 §2.3.7 says the provider **MAY** enforce (rfc7643.txt:579–581). `$ref` values, relative-URI resolution (base URI rules, rfc7643.txt:550–575) and `referenceTypes` are never asserted. Neither test touches §2.3.7's normative content; **Gap would be arguable**, and both tests are *stricter than the RFC* (GRP_15 hard-requires 400/404, A6:436–438; GRP_13 hard-requires member pruning, A6:366). |
| §2.3.8 complex must not nest | Covered (:209) | A2:453–457. | Correct; the Schema-resource exception (A2:454) mirrors §7's "Schema resource MAY contain a complex object within a sub-attribute" (rfc7643.txt:1676–1678). Note helper also hard-requires non-empty `subAttributes` for complex — stricter than §7's SHOULD (see KQ3). |
| §2.4 primary uniqueness + sub-attribute defaults | Gap (:210) | `primary` appears only as request payload data (A4:125–126, :320–321, :624–625; A9:45/70/118; A7 passim); never asserted. | Correct as Gap. Also missing: `primary` at-most-once rule, default `false`, `display` sub-attribute semantics, `(type,value)` de-dup SHOULD, returned-value canonicalization SHOULD (rfc7643.txt:630–664). |
| §2.5 unassigned/null/[] equivalence | Partial (:211) | PAT_04 (A4:97–114) checks `externalId` null-or-`""` after remove; PAT_21 (A4:619–648) checks `emails` null-or-empty after remove. | Only post-remove representation equivalence; no create/PUT with `null`/`[]` and no equivalence-across-operations test. Partial is generous but defensible. |

**§3–§3.1 common attributes (rows at :215–220)**

| RFC § | Matrix verdict | Verified | Correction |
| :-- | :-- | :-- | :-- |
| §3 `schemas` matches resourceType, no duplicates, extension isolation | Partial (:217) | USR_01 (A3:30–64, response `schemas` contains User), GRP_01 (A6:41–87), FLT_19 (A5:358–378, extension selection), PAT_10 (A4:239–257, URN PATCH path). | Correct as Partial. No assertions on duplicate URIs, order-insensitivity, "only schema/schemaExtensions of the resourceType", extension namespace isolation, or top-level leak of extension attributes. NEG_19 (A9:366) accepts 201 for a bogus schema array. |
| §3.1 `id` server-issued/readOnly/always/non-reassignable; client MUST NOT set; `bulkId` reserved | Partial (:218) | USR_06 (A3:214–250), PAT_09 (A4:215–235), A2:74–88 (conditional), FLT_35 (A5:669–685). | Correct as Partial, but note A1:179–185 (schema `id` check) is conditional; no POST-with-client-`id` test; `id` case-exactness (rfc7643.txt:874) untested; `bulkId`-in-id (rfc7643.txt:872–874) untested. |
| §3.1 `externalId` readWrite/caseExact/no uniqueness | Partial (:219) | Cited tests **USR_02 (A3:68–118) and USR_05 (A3:165–210) never set or read `externalId`.** Real coverage: PAT_01 (A4:46–59, add + GET round-trip), GRP_01 (A6:41–87, Group externalId round-trip), GRP_04 (A6:123–148, cleared on PUT). | **Citation correction needed.** `caseExact=true` (rfc7643.txt:903–905) and no-server-uniqueness behaviour untested; provisioning-domain scoping untested. |
| §3.1 `meta` relations (`location`==Content-Location, `version`==ETag, `lastModified`==`created`) | Partial (:220) | USR_01 (A3:55–63) asserts `meta.location == Location` header (not Content-Location); SEC_11–14 (A8:217–291) assert a Content-Location header exists and contains `/Users/{id}` only; SEC_05 (A8:112–133) weakly cross-checks `meta.version` vs ETag by normalized `contains`; USR_16 (A3:469)/PAT_20 (A4:566) fall back to `meta.version`; GRP_17 (A6:490–532) uses one ETag header and asserts `meta.version` nowhere. | Correct as Partial with precision: **the normative `meta.location` == `Content-Location` equality is never asserted**; `lastModified == created` for never-modified resources (rfc7643.txt:922–925) untested; DateTime validity of `created`/`lastModified` untested; `meta.version` weak-validator marking (`W/`, rfc7643.txt:936–942) untested. |

**§4 User/Group/Enterprise (rows at :224–234)**

| RFC § | Matrix verdict | Verified | Correction |
| :-- | :-- | :-- | :-- |
| §4.1.1 `userName` required/non-empty/unique/case-insensitive | Partial (:226) | A2:42–50 (required/type/readWrite/uniqueness server-or-global), NEG_02 (A9:65–86), USR_12 (A3:345–362), USR_13/NEG_01, FLT_12. | Correct as Partial. Empty-string `userName` untested; case-variant duplicate untested; PRECIS comparison (RFC 7644 §5) untested. A2:50 also accepts `global` although §8.7.1 publishes `server`. |
| §4.1.1 `password` writeOnly/`returned=never`/never leaked | Covered (:227) | A2:97–107 (**conditional**: passes with NOTE if `password` absent), SEC_07 (A8:153–166), SEC_25 (A8:586–628). | Verdict should be **Partial**: schema checks only run when the server publishes `password`; no test of error bodies, filters, or `attributes=password`; body check is a `"password"` substring scan (would also catch unrelated text). |
| §4.1.1/§4.1.2 `name`/`displayName`/`nickName`/`title`/`userType`/`profileUrl`; canonicalization; x509 single cert | Partial (:228) | USR_02 (A3:68–118, `name.givenName/familyName`), PAT_02/11/12, A2:110–166. | Correct as Partial but citation is vague: A2 only checks `userName`, `name` (as complex with only `familyName`+`givenName` sub-attrs), `emails`, `active`, `profileUrl`/`photos` conditionally, `emails.type` conditionally. `name.formatted/middleName/honorificPrefix/honorificSuffix`, User `displayName`, `nickName`, `title`, `userType` have no schema assertions. |
| §4.1.2 `groups` readOnly; `type` direct/indirect | Partial (:229) | GRP_14 (A6:377–416, reflection incl. `display`), A2:170–190 (accepts `readOnly` **or** `readWrite`; the TODO at A2:183 says deviation but no deviation is logged). | Correct as Partial. User-side write rejection untested; `$ref` never asserted; `direct`/`indirect` untested. |
| §4.1.1 `active` boolean semantics | Covered (:230) | PAT_03 (A4:80–93), a:false then GET boolean false; FLT_05/06 filter on `active`. | Correct (round-trip semantics only; provider-defined meaning is untestable). |
| §4.1.1 `preferredLanguage`/`locale`/`timezone` format | Gap (:231) | no occurrences of these strings in tests/base. | Correct as Gap. Note `locale` accepts RFC 5646 tags, underscore MAY be accepted (rfc7643.txt:1131–1150); `timezone` is IANA/Olson (rfc7643.txt:1152–1155); `preferredLanguage` is HTTP Accept-Language format (rfc7643.txt:1109–1129). |
| §4.2 `displayName` required | Covered (:232) | GRP_11 (A6:280–293, `assertScimError(400,"invalidValue")`), NEG_18 (A9:348–354). A2:214–220 accepts `required:false` with a NOTE. | Behaviourally Covered; note the matrix's parenthetical "(…noted in test (A6:300))" is a **misreference** — A6:300 concerns `uniqueness:"none"`, not `required`; the required-vs-JSON inconsistency is noted at A2:214–219. Erratum 5368 (Verified) now says `required` should be `true`. |
| §4.2 `members` sub-attrs immutable; nested groups | Partial (:233) | GRP_01 (A6:41–87, create+members), GRP_04/05/06 (membership changes via Group resource), GRP_16 (A6:443–486, single-level nested Group), GRP_14. | Correct as Partial. `$ref` never asserted; §4.2's "sub-attributes of members are immutable" (rfc7643.txt:1417–1418) untested; no cycles/self-membership; GRP_16 checks one level only. |
| §4.3 Enterprise attrs; `manager` value/`$ref`, readOnly `displayName` | Partial (:234) | A2:265–320: `employeeNumber` hard-checked (string); `department/costCenter/organization/division` NOTE if absent; `manager` absence logs DEVIATION (A2:318) and passes; `manager.value`/`$ref` only checked if sub-attributes present; `manager.displayName` type only (no readOnly check). PAT_10 (A4:239–257) patches `employeeNumber` via URN. | Correct as Partial (soft on all optional/manager aspects, as the matrix says). |

**§5–§7 (rows at :238–242) and §9 (row :246)**

| RFC § | Matrix verdict | Verified | Correction |
| :-- | :-- | :-- | :-- |
| §5 ServiceProviderConfig incl. `authenticationSchemes` | Partial (:240) | A1:20–84: `schemas` contains SPC URN (hard); `patch.supported` non-null; `bulk.supported/maxOperations/maxPayloadSize` non-null; `filter.maxResults` non-null; `etag`/`changePassword`/`sort` parent objects present; `authenticationSchemes` non-empty with `name`/`description` non-blank and `type` in the §5 canonical list (A1:58–65). | Correct. Unasserted REQUIRED fields (§5 lines 1497–1600): `filter.supported`, `changePassword.supported`, `sort.supported`, `etag.supported`; "all attributes have mutability readOnly" (rfc7643.txt:1486); public accessibility of `authenticationSchemes` (SHOULD, rfc7643.txt:1580–1583). |
| §6 ResourceType fields; schema == Schema id; schemaExtensions.required | Covered (:241) | A1:88–142 (name/schema strict, `endpoint` `endsWith` only, schemaExtensions `schema`+`required` when present, meta.resourceType if present), A1:279–306 (individual retrieval), A1:324–362 (schema URI resolvable via `/Schemas/{uri}`), A1:310–320 (404). | Correct, with caveats: absolute `endpoint` tolerated (A1:119–128); `id` optional per §6 not asserted; `description` unasserted; ResourceType `name`/`endpoint` uniqueness `server` + case-exact (erratum 8475, Verified) untested. |
| §7 Schema resource: `id`; attributes with full characteristics; `meta.resourceType=="Schema"` | Covered (:242) | A2:30–407 (all 10 tests, details in KQ3), A1:146–188, A1:192–231, A1:235–261. | Correct for the subset; matrix note is accurate ("Missing `attributes` array skips validation; `caseExact`, canonicalValues element shape, `description` not validated"). Add: A2:340 **requires** a non-blank `name` although §7 says `name` is OPTIONAL (rfc7643.txt:1698–1701); A2:352–380 treats `meta` as optional although Schema resources are not excepted from common attributes in §3.1 (rfc7643.txt:849–857) and erratum 8417 (Verified) requires `schemas`+`meta` on Schema representations; "Schema resources are not modifiable" unchecked. |
| §9.2 password not stored cleartext | Gap (:246) | No API-observable test. | Correct. Erratum 8450 (Verified) reworded hashing/encryption language; RFC 7644 §5 PRECIS preparation for password comparison is also untested for the schema side. |

### Inferences
- At section granularity the matrix is usable, but "Covered" frequently means "enum values validated when the server happens to publish them"; only `userName`, `name`, `emails`, `active`, Group `displayName`/`members`, and Enterprise `employeeNumber` are hard-required schema checks.
- Because API tests can only test a specific server (api.scim.dev, per A2/A4 comments), many 7643 "requirements" can only be verified against published `/Schemas`; the suite's own deviations comments (A2:183, A2:318) show it knowingly tests a partially non-conformant target.
- The matrix's A6 citations for `§2.3.7` and `§3.1 externalId` are the two most misleading references; both point to tests that assert something else.

### Gaps
- No repo run was performed (this is static analysis); statuses verified by reading code, not execution.
- RFC 7644-side rows (auth, filters, etc.) were only spot-checked where they cite 7643 (e.g., A1 discovery); the 7644 half of the matrix is out of scope.
- A5_BaseSpec.groovy and A7 bulk internals were not exhaustively read; 7643-relevant evidence there is limited to `primary` payloads and `externalId` round-trips.

---

## KQ2: Requirements entirely missing from the matrix

### Takeaway
The matrix omits roughly a dozen normative 7643 requirements outright — most notably attribute-name ABNF (§2.1), `primary`/`display` sub-attribute semantics (§2.4), the `schemas` array content rules (§3), the `id` case-exactness/`bulkId` reservation and `meta` assignment rules (§3.1), and most of the User multi-valued attribute set (`roles`, `entitlements`, `ims`, `photos` type/canonicalization, `x509Certificates` DER rule, `addresses.country` ISO 3166-1 alpha-2).

### Cited Findings

| RFC § (text lines) | Missing requirement (quote/paraphrase) | Evidence of absence in suite | Suggested status |
| :-- | :-- | :-- | :-- |
| §2.1 (rfc7643.txt:364–380) | Attribute names MUST match ABNF `ATTRNAME = ALPHA *(nameChar)`; names are case-insensitive. | No test submits malformed attribute names, and no test validates `$ref`-style names. Erratum 8924 (Reported) notes the ABNF cannot express `$ref`. | Gap (add row) |
| §2.2 (419–436) | Default table incl. `type` default `"string"`, `canonicalValues` none. | Helper skips all when absent (A2:428/433/438/448). | Covered by existing Gap row; extend wording for `type`/`multiValued`. |
| §2.3.1 (485–492) | `string` + `canonicalValues` MAY restrict accepted values. | A2 checks only conditional `.containsAll` (A2:159–167). | Gap (fold into canonicalValues row). |
| §2.3.2–2.3.4 (494–522) | Boolean is literal true/false; decimal has digits both sides of period; integer MUST NOT contain fraction/exponent. | No wrong-typed submissions anywhere (NEG_14/15 cover only root shape). | Gap rows (or one "per-type value validation" row). |
| §2.3.6 (535–546) | Binary MUST be base64 (RFC 4648) and **is case exact**. | `x509Certificates`/`binary` never appear in tests; no base64 test. | Gap (splits existing §2.3 "value validation" gap). |
| §2.3.7 (548–585) | Reference values are absolute/relative URIs; relative resolution base excludes Endpoint URI; `$ref` is conventional; provider MAY enforce integrity. | `$ref` never asserted (matrix admits); relative resolution untested; GRP_13/15 over-assert a MAY. | Gap for URI rules; add over-strict note to existing Partial row. |
| §2.4 (623–664) | Multi-valued defaults: `type`, `primary` (at most one true; default false), `display` (mutability immutable), `value`, `$ref`; server SHOULD canonicalize returned values; SHOULD NOT return duplicate `(type,value)`. | `primary` only appears in request payloads; never asserted. `display` only conditionally type-checked (A2:256–260). | Split existing Gap row into: primary rule; display semantics; canonicalization; de-dup. |
| §2.5 (679–687) | `null`/`[]` make attribute unassigned; MAY be omitted for compactness. | Only post-remove null/empty checks (PAT_04, PAT_21). | Partial (existing) + add create/PUT equivalence sub-case. |
| §3 (702–715) | `schemas` MUST be non-empty; each URI unique; duplicates MUST NOT; only the resourceType's `schema`/`schemaExtensions`; order not significant. | No negative test for duplicate URIs/mismatched arrays; NEG_19 accepts 201 for a bogus URN. | Gap rows. |
| §3 (743–749) | Extended attributes MUST be in their own JSON sub-structure keyed by schema URI; no top-level leak. | No isolation-leak test (matrix notes untested). | Gap row. |
| §3.1 (864–877) | `id` case-exact; always issued by the SP and **MUST NOT be specified by the client**; `bulkId` reserved keyword; stable/non-reassignable. | POST with client `id` untested; `bulkId` untested (matrix notes both). | Extend existing Partial row (add `caseExact`). |
| §3.1 (879–905) | `externalId` is case-exact and scoped to the provisioning domain; server does not enforce uniqueness. | Untested (matrix says so). | Extend existing Partial row. |
| §3.1 (907–942) | `meta` values assigned by SP on create; SHALL be ignored when provided by clients; `meta.version` weak-marking rule. | SEC_16/17 partially test `meta.created`; "ignore client meta" untested; weak marking untested. | Extend existing Partial row. |
| §4.1.1 (1049–1077) | `name` sub-attributes `formatted`, `middleName`, `honorificPrefix`, `honorificSuffix`. | A2 only checks `familyName`, `givenName` (A2:52–60). | Add to Partial row. |
| §4.1.1 (1079–1155) | User `displayName`, `nickName`, `title`, `userType`, `preferredLanguage`, `locale`, `timezone` semantics/format. | No schema assertions; zero grep hits except comment strings. | Gap rows (preferredLanguage/locale/timezone exist as one Gap row; add the rest). |
| §4.1.1 (1211–1224) | Password equality filtering MAY be supported via salted hash. | Untested. | Gap (fold into §9.2/password). |
| §4.1.2 (1243–1253) | `emails` SHOULD be RFC 5321-canonicalized; `display` used for canonical form; type work/home/other. | A2 checks only `emails.type` canonicalValues (conditional); value canonicalization untested. | Partial → refine. |
| §4.1.2 (1255–1264) | `phoneNumbers` SHOULD be RFC 3966 format; types work/home/mobile/fax/pager/other. | `phoneNumbers` absent from tests. | Gap row. |
| §4.1.2 (1266–1276) | `ims`: whitespace removal/lowercase SHOULD; type canonical values aim/gtalk/icq/xmpp/msn/skype/qq/yahoo/other. | `ims` absent from tests. | Gap row. |
| §4.1.2 (1278–1299) | `photos` MUST be a file (not a web page); type canonical values `photo`/`thumbnail`. | A2 only conditionally checks `photos.value` referenceTypes (A2:150–157). | Gap row. |
| §4.1.2 (1301–1323) | `addresses` is complex with formatted/streetAddress/locality/region/postalCode/country; **country MUST be ISO 3166-1 alpha-2** (e.g., US/SE). | `addresses`/`country` absent from tests; errata 5990/5991 fix "USA" in examples. | Gap row. |
| §4.1.2 (1325–1356) | `groups`: `value` MUST be the `id`, `$ref` MUST be the URI of the Group resource; mutability readOnly; changes MUST go via Group resource. | GRP_14 checks `value`+`display`; no `$ref`; no User-side write-rejection; no `direct`/`indirect`. | Extend existing Partial row. |
| §4.1.2 (1358–1373) | `entitlements` and `roles` multi-valued attributes. | Zero test occurrences (`roles` only in A4 class comment). | Gap row. |
| §4.1.2 (1375–1381) | `x509Certificates`: each value exactly one DER-encoded cert, base64; a single value MUST NOT contain multiple certs ("SEQUENCE OF Certificate"). | Zero test occurrences. | Gap row. |
| §4.2 (1416–1426) | `members`: values MAY be added/removed but sub-attributes are **immutable**; `value` = `id` of a SCIM resource; `$ref` = URI; SP MAY require non-empty sub-attribute. | Immutability/`$ref` untested. | Extend existing Partial row. |
| §4.3 (1463–1475) | `manager.value`/`$ref` RECOMMENDED; `displayName` OPTIONAL and readOnly. | A2 checks presence only conditionally; no mutability check. Errata 6403/8359 (Held) dispute required-ness. | Extend existing Partial row. |
| §5 (1486–1600) | All SPC attributes mutability readOnly; `.supported` REQUIRED for patch/filter/changePassword/sort/etag; `authenticationSchemes` SHOULD be publicly accessible. | A1:20–84 never asserts `.supported` for filter/changePassword/sort/etag nor readOnly; public accessibility deferred (tracker A1-G01). | Extend existing Partial row. |
| §6 (1614–1658) | `name`/`endpoint`: uniqueness `server`, case-exact (erratum 8475 Verified); all ResourceType attrs REQUIRED unless noted; `id` not required. | A1 checks neither uniqueness/case nor `id` absence. | Extend existing Covered row. |
| §7 (1660–1846) | Schema resources are not modifiable (readOnly); every schema URI in a resource has a corresponding Schema resource; `name`/`description` OPTIONAL; `attributes` sub-schema rules: `canonicalValues` only for string, `referenceTypes` only for reference, `subAttributes` SHOULD for complex and shares the same schema; `type` valid values (incl. binary per erratum 8435). | Covered partially by A2 (see KQ3); applicability/absence not validated; Schema resource's own readOnly nature untested. | Extend existing Covered row / add sub-rows. |
| §9.2 (5111–5135) | Passwords MUST NOT be stored cleartext; hashing/encryption best practices (erratum 8450 wording). | Not API-observable; no test. | Keep Gap; note non-observability. |

### Inferences
- Adding ~20 rows would align the matrix with the RFC's normative surface. The highest-value additions are the missing User schema attribute families (`roles`, `entitlements`, `ims`, `photos`, `x509Certificates`, `addresses`, plus `name` sub-attributes) because they are cheap to test against `/Schemas` (schema-side) and currently completely absent.
- The A2 schema test could be extended mechanically for most of these (it already iterates `attributes`), so missing coverage is not a tooling limitation.

### Gaps
- Whether api.scim.dev actually publishes `phoneNumbers`/`addresses`/`roles` etc. is unknown from this static read; the A4 class comment (A4:15–17) claims it does not, which may explain (but not excuse) the omissions.
- RFC 7643 §8.7.1 line-level validation of every JSON example is out of scope for a test suite; only normative prose was listed.

---

## KQ3: How does `validateAttributeCharacteristics` implement §2.2/§7, and what does that mean for coverage?

### Takeaway
`A2_SchemaValidationSpec.validateAttributeCharacteristics` (A2:419–471) is an enum/type validator with a few structural checks. It never enforces: any §2.2 default, `caseExact`, `canonicalValues`, `description`, characteristic/type applicability, or `referenceTypes` content. Consequently the matrix's "§2.2 Covered"/"§7 Covered" statuses mean "the enum members that a server chose to publish are spelled correctly", not "the schema conforms to RFC 7643".

### Cited Findings

**What the helper validates** (all guards are `if (attr.X != null)`, so omission always passes):
- `name` non-null/non-blank — A2:420–422.
- `type` non-null and in `{string, boolean, decimal, integer, dateTime, binary, reference, complex}` (`VALID_TYPES`, A2:15–17; check A2:424–426). This is post-erratum 8435-correct (binary included).
- `mutability` in `{readOnly, readWrite, immutable, writeOnly}` when present — A2:428–431.
- `returned` in `{always, never, default, request}` when present — A2:433–436.
- `uniqueness` in `{none, server, global}` when present — A2:438–441.
- `multiValued` is a JSON boolean when present — A2:443–446.
- `required` is a JSON boolean when present — A2:448–451.
- For `type == "complex"`: recursive descent into `subAttributes`, which must be non-null and non-empty; a complex attribute nested inside a sub-attribute is a hard failure for every schema **except** the Schema resource itself (`schemaId != "...:Schema"` exception) — A2:453–464.
- For `type == "reference"`: `referenceTypes` must be non-null and non-empty — A2:466–470.

**What the helper does not validate** (each is an omitted branch or absent check):
- `caseExact`: never read, never type-checked, never validated as boolean, never checked for applicability (string only). RFC §2.2 (line 427, default false) and §7 (1747–1754, filter semantics).
- `canonicalValues`: never read. §7 calls them "suggested" (1743–1745); applicability to string attributes is unenforced.
- `description`: never checked (presence, type, or content); §7 (1728–1729).
- Defaults: none of `required=false`, `caseExact=false`, `returned=default`, `mutability=readWrite`, `uniqueness=none`, `multiValued=false`, `type=string` are evaluated when omitted; the helper only asserts on present values. §2.2 (419–436).
- Applicability errors in the other direction: `referenceTypes` on a non-reference attribute, `subAttributes` on a non-complex attribute, and `uniqueness` on a complex attribute (erratum 6004 Verified says complex has no uniqueness) all pass silently.
- `subAttributes` content beyond recursion: not validated against the same schema; children are checked by the same rules, which is correct, but the RFC's parent/child `type` canonical-value list (Schema schema; errata 5606/8415) is never compared.
- Attribute-name ABNF (§2.1) is unrelated to this helper and untested.
- `referenceTypes` element values (only SCIM resource type names, `external`, `uri` per §7 lines 1833–1846) are not validated, only non-emptiness.
- Stricter-than-RFC checks embedded in the helper: complex attributes **must** have non-empty `subAttributes` (A2:459) although §7 says SHOULD (1717–1719); `type` is mandatory (A2:425) although §2.2 gives a default (436); `referenceTypes` mandatory for reference types (A2:468) although the Schema-schema JSON marks it `required:false` (erratum 5607 only changes `multiValued`).

**Scope of invocation**: A2:337–349 loops every schema from `/Schemas` and calls the helper for the top-level `attributes` only when `schema.attributes != null` (A2:343–348), so a schema resource that omits `attributes` skips validation entirely. A2:385–407 ("Attribute mutability values are valid per RFC 7643 §2.2") fetches only the **User** schema and checks top-level `mutability` only — the test title's "across published schemas" is inaccurate (sub-attributes are not covered by this test, though A2:461–463 covers them for schemas with `attributes`).

### Inferences
- The helper is a schema-self-description integrity check, not a §2.2 conformance check. The matrix's defaults Gap row (SCIM_SPEC_COVERAGE.md:194) is the accurate statement; the "§2.2 Covered" row should be read in that light.
- Two helper rules can falsely reject otherwise compliant servers: mandatory `subAttributes` for complex (SHOULD in §7) and mandatory non-empty `referenceTypes` for reference (the RFC schema marks it `required:false`). Conversely, the Schema-resource exception at A2:454 is required precisely because §7 permits the Schema resource to contain a complex object within a sub-attribute (rfc7643.txt:1676–1678), an internal contradiction with §2.3.8 (rfc7643.txt:595–596).
- Tracker overstatements: `SCIM_VALIDATOR_ASSESSMENT.md:64` (`A2-T03` "emails, phoneNumbers, active") claims `phoneNumbers` validation that does not exist in A2:110–130; `:68` (`A2-T07`) implies full §2.2/§2.3/§7 integrity; `:71` (`A2-T10`) claims mutability validity "across published schemas" but only the User schema is checked.

### Gaps
- I did not run the suite, so I cannot report which deviations the target server actually triggers.
- Sub-attribute enforcement for servers that publish common attributes (`id`, `externalId`, `meta`) inside the User schema is only exercised through the same helper; there is no dedicated test artefact to cite beyond A2:324.

---

## KQ4: Tests whose comments misquote the RFC or whose assertions are weaker than claimed

### Takeaway
Seven test comments cite the wrong section or paraphrase non-existent RFC text, and several assertions are conditional in ways that let a non-conformant server pass. The most consequential are the "§7 declares Group.displayName required:false / uniqueness none" comments (the Group JSON is §8.7.1; erratum 5368 has since verified required must be true) and the A2 password checks (all conditional despite "MUST").

### Cited Findings

| Location | Comment / assertion | RFC reality | Impact |
| :-- | :-- | :-- | :-- |
| A2:75–77 | "the normative User schema in §7 does not repeat [id] inside attributes … (matches the RFC 7643 §7 schema representation)" | The User schema JSON representation is §8.7.1 (rfc7643.txt:2590+), not §7; §7 is the Schema-resource definition (1660–1846). The JSON does omit `id`; common attributes are §3.1. | Citation wrong; conclusion right. |
| A1:178–180 | "Per RFC 7643 §2, 'id' is a common attribute on all resources, may not be in per-schema attributes" | Common attributes are defined in §3.1 (864–877); §2.1 only requires the `schemas` attribute. | Citation wrong. |
| A2:214–219; A6:300–302, :324; A9:338–339, :344–345 | "RFC 7643 §7 declares Group.displayName with required:false" / "uniqueness:none"; A2 accepts both required values | Group JSON is §8.7.1 (rfc7643.txt:3818–3832); pre-erratum it said `required:false`, but erratum 5368 (Verified, 2025-10-28) changes it to `required:true`; §4.2 prose says REQUIRED (1410–1411). | Tests intentionally tolerate (A2) or allow (A6/A9) duplicates; matrix note references A6:300 for the required inconsistency but A6:300 is about uniqueness. Should cite A2:214–219 and erratum 5368. |
| A6:339–340 ("RFC 7644 §3.6 — Referential Integrity"), A6:420–421 ("RFC 7644 §3.3 — Member reference validation"), GRP_15 asserts 400/404 (A6:436–438) | Referential integrity/MUST language | Referential integrity appears only in RFC 7643 §2.3.7 and is explicitly "MAY choose to enforce" (rfc7643.txt:579–581). RFC 7644 §3.6 only mandates 404 on subsequent ops and omission from queries (verified by reading rfc7644.txt §3.6). | Suite requires MAY behaviour as MUST; compliant servers that keep dangling members or return 201 fail GRP_13/GRP_15. |
| A1:227 (comment) | "Per RFC 7644 §4: 'Service providers MUST provide Schema definitions for ServiceProviderConfig, ResourceType, and Schema itself'" | Grep of RFC 7644 shows no such sentence (and no "Schema itself" / "Schema Resources"); §4 only says `/Schemas` "SHALL return all supported schemas in ListResponse" (rfc7644.txt:4095–4103). The normative basis is RFC 7643 §7 ("for every schema URI used in a resource object, there is a corresponding Schema resource", 1666–1667) plus registrations in §10. | Non-verbatim quote attributed to the wrong RFC/section; the hard assertion at A1:228–230 is defensible but not from §4. |
| A1:367, :402, :437 and A1:388 | "Discovery endpoints only support GET" / "405 expected per RFC 7644 §4" | The string "405" appears **nowhere** in RFC 7644 (grep count 0 over rfc7644.txt). The suite itself only asserts 4xx and logs non-405 as a deviation (A1:386–396). | Comment overstates RFC requirements; matrix's "405 + Allow" phrasing is suite folklore, not RFC text. Similar status-only assumptions exist for 406/415 in SEC_19/20 (A8:410–444, comments claim MUST). |
| A2:97–107 | "password attribute is string, writeOnly, and returned never (RFC 7643 §4.1.2)" | Correct in content (§4.1.1 lines 1221–1224) and §8.7.1 JSON (password writeOnly/never). | But every assertion is inside `if (passwordAttr != null)`; a server omitting `password` passes with a NOTE (A2:105–107). Test and matrix call this "Covered". |
| A2:236–237 | members.value mutability "should be immutable or readWrite", accepts `readOnly` | §8.7.1: `members.value`/`$ref`/`type` are `immutable` (rfc7643.txt:3848+; §4.2 prose "sub-attributes of members are immutable", 1417–1418). Accepting `readOnly` is a deviation (no log). | Weaker than the comment implies. |
| A2:227 | `membersAttr.mutability in ["readWrite","readOnly"]` | §8.7.1 Group `members` is `readWrite` (head of the members block, ~3833–3847). | `readOnly` acceptance is wrong but harmless (no log). |
| A2:256–260 | `display` sub-attribute allowed mutability `{readOnly, readWrite}` | §2.4 says the predefined `display` sub-attribute has mutability **immutable** (rfc7643.txt:637–639); erratum 8358 (Held) notes §8.7.1 descriptions say READ-ONLY while JSON says readWrite; erratum 6011 (Reported) proposes adding `display` (readOnly) to Group members. | A server publishing `display` as `immutable` (the §2.4 definition) would **fail** A2:259; allowed set should at least include `immutable`. |
| A2:340 | Every schema must have a non-blank `name` | §7: `name` is OPTIONAL ("When applicable … OPTIONAL", rfc7643.txt:1698–1701). | Over-strict vs §7. |
| A2:459 | Complex attribute "must have non-empty subAttributes" | §7: "When an attribute is of type complex, there SHOULD be a corresponding schema attribute subAttributes" (1717–1719) — SHOULD, not MUST. | Over-strict; false failure for a complex attr with no subs. |
| A2:352–380 | Schema `meta.resourceType` asserted only "if meta is present" | §3.1 says common attributes (incl. `meta`) MUST be defined for all resources except SPC/ResourceType endpoints (849–857); Schema resources are not excepted; erratum 8417 (Verified) requires `schemas`+`meta` on Schema representations. | Weaker than required; a Schema resource with no `meta` passes. |
| A5:181 (FLT_12) | `userNameAttr.caseExact == false` as a hard precondition | §2.2: `caseExact` default is false when omitted (427). | A server that omits `caseExact` (legal) yields null and fails FLT_12. |
| A3:387–389; A4:428 | "userName IS uniqueness 'server' per RFC 7643 §7" | Value is right but it is in §8.7.1 (User JSON), not §7. | Citation nit; affects grep-ability of traces. |
| A6:300–302 and NEG_18 comment A9:338–339 | "§7 declares displayName uniqueness 'none'" | §8.7.1, not §7. | Citation nit. |
| A8:154, :156; A9:66, :79 | "RFC 7643 §4.1 — password returned=never / §4.1 — missing required userName" | §4.1.1. | Citation nit. |
| Tracker `:64`, `:68`, `:71` | A2-T03 claims `phoneNumbers` validation; A2-T07 claims §2.2/§2.3/§7 integrity; A2-T10 claims mutability valid "across published schemas" | A2:110–130 tests `emails`+`active` only; helper limits per KQ3; A2:385 checks the User schema only. | Tracker materially overstates A2 coverage; tracker marks A2 100% complete. |

### Inferences
- The pattern is systematic: comments describe the intended (strict) check, while the executable assertion is looser (conditional, `in [...]` alternative sets, or deviation-logged). The matrix's "Suite-side weaknesses" section (SCIM_SPEC_COVERAGE.md:263–269) captures some but attributes the required/JSON inconsistency to A6:300 instead of A2:214 and omits the A2 comment issues above.
- Comments that attribute Group/User JSON to "§7" appear throughout A2/A3/A4/A6/A9; a bulk correction to "§8.7.1" would make the test suite's traceability to the RFC accurate.
- The referential-integrity over-assertions (GRP_13/GRP_15) are not just comment nits: they turn a provider MAY into a test MUST and would fail compliant servers.

### Gaps
- This review did not run the tests; "would fail" statements are logical consequences of the assertions, not observed failures.
- A7 bulk test comments were only skimmed for 7643-relevant claims (none found); a full 7644-oriented comment audit is a separate task.

---

## KQ5: Errata and internal inconsistencies that affect interpretation

### Takeaway
RFC 7643 has 16 Verified errata and is updated by RFC 9865 (cursor pagination) and RFC 9967 (SCIM event SETs, which adds SPC attributes). Three verified errata bear directly on suite interpretation: 5368 (Group `displayName.required` must be `true`), 8417 (Schema representations need `schemas`+`meta`), and 8475 (ResourceType `name`/`endpoint` are `server`-unique and case-exact). The suite's justifications that lean on the pre-erratum §8.7.1 JSON (Group displayName, members display, manager value) are now partially outdated. Source: https://www.rfc-editor.org/errata/rfc7643 (statuses as of 2026-10-10).

### Cited Findings

**Verified errata directly relevant to 7643 tests**

| Erratum | Section | Change (status) | Effect on suite interpretation |
| :-- | :-- | :-- | :-- |
| [5368](https://www.rfc-editor.org/errata/eid5368) | §8.7.1 | Group `displayName` `required:false` → `true` (Verified, Technical, 2025-10-28) | A2:214–219 explicitly accepts `required:false` citing the JSON; A6:300/A9:338 justify duplicate-name acceptance by `uniqueness:none` (that part is unaffected). GRP_11/NEG_18 already enforce POST 400. Matrix §4.2 row should note the verified correction instead of "both readings are in the RFC". |
| [8435](https://www.rfc-editor.org/errata/eid8435) | §7 | `type` valid values list gains `binary` (Verified, Editorial) | Helper `VALID_TYPES` already includes `binary` (A2:15–17) — correct post-erratum. |
| [8417](https://www.rfc-editor.org/errata/eid8417) | §8.7.2 (and Reported [5999](https://www.rfc-editor.org/errata/eid5999) for §8.7.1) | Schema representations for SPC/ResourceType/Schema must include `schemas` and `meta` (Verified, Technical) | A2:352–380 checks `schemas` (hard) but treats `meta` as optional; A1:192–231 does not check per-schema metadata. |
| [8475](https://www.rfc-editor.org/errata/eid8475) | §6 / §8.7.2 | ResourceType `name`/`endpoint` must be `uniqueness:server`, `caseExact:true` (Verified, Technical) | A1:88–142 checks neither; no test cites this. |
| [8450](https://www.rfc-editor.org/errata/eid8450) | §4.1.1 | "encrypted (e.g., hashed)" → "hashed or encrypted" (Verified) | §9.2 remains unobservable; wording affects any future storage test. |
| [5606](https://www.rfc-editor.org/errata/eid5606) / [8415](https://www.rfc-editor.org/errata/eid8415) | §8.7.2 | Schema `type`/`subAttributes.type` canonicalValues gain `binary`; 8415 also removes `complex` from sub-attribute canonical values per §2.3.8 | Helper exempts the Schema schema from its nested-complex ban (A2:454), which is required because the RFC's own Schema schema lists `complex` there pre-erratum. If a server republished the RFC JSON verbatim, helper passes due to the exemption. |
| [6004](https://www.rfc-editor.org/errata/eid6004) | §8.7.1 | Remove `uniqueness` from complex `name`/`emails`/`addresses` | Helper never enforces absence or presence of `uniqueness` on complex; no impact but relevant to any future strict check. |
| [5607](https://www.rfc-editor.org/errata/eid5607) | §8.7.2 | `referenceTypes` `multiValued:false` → `true` | Helper never inspects `multiValued` of sub-attributes named `referenceTypes`; untested. |
| [7522](https://www.rfc-editor.org/errata/eid7522) | §8.7.2 | `schemaExtensions.multiValued:false` → `true` | A1 only iterates the array; server's declared `multiValued` value is not asserted. |
| [8471](https://www.rfc-editor.org/errata/eid8471) | §8.7.1 | User `groups.$ref` `referenceTypes` should be `["Group"]`, not `["User","Group"]` | A2 checks Group `members.$ref` (correctly `User`+`Group`, A2:242–245); User `groups.$ref` is never checked. A server republishing pre-erratum JSON passes. |
| [8472](https://www.rfc-editor.org/errata/eid8472) | §8.7.1 | Enterprise `manager.value` `caseExact:true` | `caseExact` is never validated by the helper (KQ3); untested. |
| [5990](https://www.rfc-editor.org/errata/eid5990) / [5991](https://www.rfc-editor.org/errata/eid5991) | §8.2/§8.3 | Examples' `country:"USA"` → `"US"` to match §4.1.2 ISO 3166-1 alpha-2 | No test uses `country`; if one is added, use `US`, not the pre-erratum example. |
| [8361](https://www.rfc-editor.org/errata/eid8361) / [6727](https://www.rfc-editor.org/errata/eid6727) | §3.1 / §8.7.2 | "/ResourceTypes" naming; `description` attribute text corrected | Editorial; no test impact. |

**Reported errata (not yet verified) relevant to future tests**

| Erratum | Change proposed | Relevance |
| :-- | :-- | :-- |
| [6000](https://www.rfc-editor.org/errata/eid6000) | `x509Certificates.value` `caseExact` false → true | Matches §2.3.6 ("binary is case exact"); no x509 test exists. |
| [6007](https://www.rfc-editor.org/errata/eid6007) | `preferredLanguage` example `en_US` → `en-US` | Matches §4.1.1 Accept-Language format; no preferredLanguage test. |
| [6011](https://www.rfc-editor.org/errata/eid6011) | Add Group `members.display` sub-attribute to §8.7.1 (readOnly) | §2.4 defines `display` universally; A2:256–260 checks it only if present and allows readOnly/readWrite but **not** `immutable`. |
| [8011](https://www.rfc-editor.org/errata/eid8011) | Add SPC `authenticationSchemes.type` to §8.7.2 | §5 already defines `type` (rfc7643.txt:1586–1588); A1:58–65 tests it against §5. |
| [8924](https://www.rfc-editor.org/errata/eid8924) | ABNF must allow `$ref` (`ATTRNAME = ["$"] ALPHA *nameChar`) | Relevant to any name-validation test; no test today. |
| [9018](https://www.rfc-editor.org/errata/eid9018) | SPC schema representation omits `etag` although §5 marks it REQUIRED | A1:46–47 checks only that the `etag` parent object exists in the SPC resource, not the Schema representation. |
| [9190](https://www.rfc-editor.org/errata/eid9190) | `password.caseExact:false` conflicts with password comparison rules (RFC 7644 §5 PRECIS) | No password-filter test exists (SEC_07/SEC_25 only check non-return). |

**Held-for-Document-Update / Rejected errata worth noting**
- Held: [6001](https://www.rfc-editor.org/errata/eid6001) (reference attrs incorrectly `caseExact:false`), [6403](https://www.rfc-editor.org/errata/eid6403) and [8359](https://www.rfc-editor.org/errata/eid8359) (manager `value`/`$ref` "REQUIRED" in descriptions vs `required:false` JSON), [8279](https://www.rfc-editor.org/errata/eid8279)–[8281](https://www.rfc-editor.org/errata/eid8281) (`required` semantics ambiguity), [8358](https://www.rfc-editor.org/errata/eid8358) (display sub-attributes described READ-ONLY but declared readWrite), [8363](https://www.rfc-editor.org/errata/eid8363)/[8366](https://www.rfc-editor.org/errata/eid8366) (Schema/ResourceType `id` uniqueness; `endpoint` uniqueness), [8418](https://www.rfc-editor.org/errata/eid8418)/[8419](https://www.rfc-editor.org/errata/eid8419) (Schema/ResourceType JSON should not list common `id`), [6005](https://www.rfc-editor.org/errata/eid6005) (`addresses` missing `primary` sub-attribute in JSON).
- Rejected: [4979](https://www.rfc-editor.org/errata/eid4979) (`/ServiceProviderConfigs` plural), [7921](https://www.rfc-editor.org/errata/eid7921) (primary on authenticationSchemes), [8360](https://www.rfc-editor.org/errata/eid8360) (Schema `description` caseExact), [8362](https://www.rfc-editor.org/errata/eid8362) (ResourceType referenced by name not id), [6438](https://www.rfc-editor.org/errata/eid6438) (hyperlink fix) — none normative.

**Internal inconsistencies that affect interpretation (independent of errata)**
- Group `displayName`: §4.2 says REQUIRED (rfc7643.txt:1410–1411); §8.7.1 says `required:false` (3818–3832); erratum 5368 resolves in favour of REQUIRED. A2 intentionally accepts both (A2:214–219) and logs a NOTE.
- `Schema` resource self-description vs §2.3.8: the Schema-schema JSON contains a complex `subAttributes` attribute nested inside complex `attributes`, which §2.3.8 forbids; §7 explicitly excepts the Schema resource ("MAY contain a complex object within a sub-attribute", rfc7643.txt:1676–1678); erratum 8415 is related. The helper's exemption (A2:454) is therefore correct.
- `display` sub-attribute mutability: §2.4 says `immutable`; §8.7.1 User attribute descriptions say READ-ONLY while JSON says readWrite (erratum 8358, Held); A2:259 permits only readOnly/readWrite. Any choice can contradict some part of the RFC; tests should accept the union `{immutable, readOnly, readWrite}` until the errata process settles it.
- `manager.value`: §4.3 says the sub-attribute is "RECOMMENDED" (1468–1472) while §8.7.1 descriptions say REQUIRED (errata 6403/8359, Held). A2 only requires its type when present.
- RFC 1000-line-level detail: §3.1's exception list says "ServiceProviderConfig and ResourceType server discovery endpoints and their associated resources" (849–852) — Schema resources are **not** excepted, hence the erratum-8417 requirement that Schema representations include `schemas`/`meta`.

**RFC 7643 updates**
- [RFC 9865](https://www.rfc-editor.org/rfc/rfc9865) (Oct 2025): cursor-based pagination adds query parameters/result attributes (`cursor`, `nextCursor`, etc.) to 7643/7644 structures. The suite's A5 pagination tests assert specific fields but not absence of extras, so a cursor-capable server is not rejected; no 9865 coverage exists.
- [RFC 9967](https://www.rfc-editor.org/rfc/rfc9967) (May 2026): defines SCIM Security Event Tokens and "additional attributes for the urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig schema". A1:20 does not reject extra SPC fields, so no conflict; no 9967 coverage.

### Inferences
- The matrix should label RFC 7643 as "RFC 7643 as updated by RFC 9865/RFC 9967, consulted alongside verified errata (notably 5368, 8415, 8417, 8435, 8475)" to avoid the current state where tests reason from superseded JSON examples.
- Erratum 5368 changes the correct answer for the matrix's §4.2 displayName row: the strict interpretation is `required:true`; A2's tolerance should be documented as a deliberate server-deviation accommodation, not as "both readings are in the RFC".
- Verified errata 8417/8475 expose two easy test additions: Schema representations must carry `schemas`+`meta`, and ResourceType `name`/`endpoint` are server-unique/case-exact.

### Gaps
- Errata 9190 and 9018 were read only partially (the errata page output was truncated after 9018); their exact proposed text should be re-checked at https://www.rfc-editor.org/errata/eid9190 and /eid9018 before quoting in a final report.
- The status counts (Verified 16 / Reported 8 / Held 12 / Rejected 5) are as of 2026-10-10; new errata may have been filed since.
- Whether RFC 9865/9967 alter any normative 7643 text beyond additive attributes/query parameters was not examined in full; only their abstracts and headers were read.
