# Suite Trustworthiness Audit — SCIM Validator Spec Coverage (false-pass/false-fail, loose assertions, deviation handling)

Scope: read-only audit of `scim-validator` (Groovy/Spock, 186 feature methods across A1–A9) at
`/Users/eduardpal/work/scim2/scim-validator`. All claims verified directly against source; no repo
file was modified. Verified suite size: exactly **186** feature methods
(A1 12, A2 10, A3 16, A4 21, A5 34+6+5, A6 18, A7 19, A8 26, A9 19) by counting `def "` in
`src/main/groovy/de/palsoftware/scim/validator/specs/*.groovy`; matches
[SCIM_VALIDATOR_ASSESSMENT.md:7](file:///Users/eduardpal/work/scim2/scim-validator/SCIM_VALIDATOR_ASSESSMENT.md)
and the inline count in SCIM_SPEC_COVERAGE.md.

Key file paths used below (relative to repo root):
- `base/ScimBaseSpec.groovy`, `base/A5_BaseSpec.groovy`, `base/ScimOutput.groovy`,
  `base/ScimExchangeCaptureFilter.groovy`, `base/ScimRunContext.groovy`,
  `base/ValidatorConfiguration.groovy`
- `specs/A1_ServiceDiscoverySpec.groovy` … `specs/A9_NegativeAndEdgeCasesSpec.groovy`
- `ScimValidatorSuite.groovy`, `src/test/groovy/.../base/ScimValidatorEnvironment.groovy`,
  `src/test/groovy/.../base/TestcontainersBootstrapProvider.groovy`
- `src/main/resources/validator-application.yml`, `README.md`, `pom.xml`

---

## Q1. Are the documented "Suite-side weaknesses" real, and what additional false-pass / false-fail risks exist?

### Takeaway
Every weakness listed in SCIM_SPEC_COVERAGE.md:263–269 is verified. The soft `scimType` check is the
single largest credibility leak: 11 `assertScimError(..., expectedType)` call sites, 6 direct
`assertScimType(...)` calls, and 4 bulk `assertOperationScimType(...)` calls all pass when the server
omits `scimType`. Beyond the documented list there are additional false-passes (wide status ranges,
log-only branches, weak/one-sided counts, null-tolerant matching, zero gating for the mandatory-only
cases that actually test errors) and additional false-fails (A2's strict mutability loop, ungated
ETag tests, ungated PATCH/bulk/`/.search`/enterprise tests, Bearer-only auth assertions, `/Me` 501).

### Cited Findings — A. Documented weaknesses verified

1. **Soft `assertScimType`** — `base/ScimBaseSpec.groovy:463–473`: when `scimType` is null/blank it
   prints `DEVIATION:` and returns; only wrong-but-present values fail. Reached indirectly from
   `assertScimError` (`ScimBaseSpec.groovy:442–454`, call at 451–453).
   All 21 call sites:
   - via `assertScimError(resp, status, type)`: `A3_UserCrudSpec.groovy:389`,
     `A6_GroupLifecycleSpec.groovy:292,321`, `A9_NegativeAndEdgeCasesSpec.groovy:54,79,157,184,217,235,342,354`
   - direct `assertScimType`: `A3_UserCrudSpec.groovy:236`, `A4_PatchOperationsSpec.groovy:205,225`,
     `A8_SecurityAndRobustnessSpec.groovy:85,334`, `A9_NegativeAndEdgeCasesSpec.groovy:257`
   - bulk operation errors via a second soft helper `A7_BulkOperationsSpec.groovy:1005–1014`:
     `A7:685,751,820,826`
   Note: Omission is RFC-legal — `scimType` is "OPTIONAL" (RFC 7644 §3.12,
   <https://www.rfc-editor.org/rfc/rfc7644#section-3.12>). The defect is not RFC strictness per se; it
   is that tests whose declared purpose is to validate specific keywords (e.g. NEG_01 "with uniqueness
   scimType", A9:39–54) cannot fail when the keyword is absent, while
   `SCIM_VALIDATOR_ASSESSMENT.md:272–278` marks A9-G02/G03/G04/G05/G06 as "CLOSED ... Verify scimType".
   This overstates proof.

2. **A5 projection tests are log-only** — `A5_FilteringSpec.groovy:215–223` (FLT_13): after checking
   `id`/`userName`, leaked default attributes only print a DEVIATION; no `assert`. Same in
   `A5_FilteringSpec.groovy:240–247` (FLT_14, excludedAttributes). `SCIM_SPEC_COVERAGE.md:75,107`
   records this.

3. **Sorting: descending bug tolerated** — `A5_SortingSpec.groovy:65–72` (SRT_02): if the server
   returns ascending instead of descending, a DEVIATION is printed and the test asserts ascending
   instead. `SCIM_SPEC_COVERAGE.md:101,286`.

4. **A1 non-405 tolerated** — `A1_ServiceDiscoverySpec.groovy:386–396,421–431,456–466`: any 4xx
   passes; non-405 prints DEVIATION. `Allow` header is only a NOTE
   (`A1:390–393,425–428,460–463`). Matches `SCIM_SPEC_COVERAGE.md:178,277`.

5. **A2 `groups` readWrite tolerated** — `A2_SchemaValidationSpec.groovy:182–190`: accepts
   `mutability in ["readOnly","readWrite"]`; if `groups` is absent the test still reaches `true` at
   `A2:189`. `SCIM_SPEC_COVERAGE.md:278`.

6. **A2 missing `manager` tolerated** — `A2_SchemaValidationSpec.groovy:296–319`; deviation printed
   at `A2:318` and the test passes. `SCIM_SPEC_COVERAGE.md:279`.

7. **Feature gating is partial** — only sort is gated: `A5_SortingSpec.groovy:35,65,97,123` consult
   `isSortSupported()`. `isPatchSupported()` (`ScimBaseSpec.groovy:62–64`), `isBulkSupported()`
   (`:66–68`), `isEtagSupported()` (`:78–80`), `isSpcLoaded()` (`:58–60`) and `getFilterMaxResults()`
   (`:74–76`) are **never called anywhere** — verified by exhaustive search of `src/main` and
   `src/test` (only `isSortSupported` and the `bulkMaxOperations` property in A7 have uses). A4
   (PATCH) and A7 (bulk) run unconditionally; RFC makes PATCH "an OPTIONAL server function"
   (<https://www.rfc-editor.org/rfc/rfc7644#section-3.5.2>) and bulk "an optional server feature"
   (<https://www.rfc-editor.org/rfc/rfc7644#section-3.7>). `SCIM_SPEC_COVERAGE.md:145` lists the A7
   hygiene gap but not A4.

8. **ETag hard-required in GRP_17 though optional** — `A6_GroupLifecycleSpec.groovy:500–503`
   (`etag != null` unconditional), `:515–518` (hard 412), `:526–528` (200). RFC 7644 §3.14:
   "Service providers MAY support weak ETags" (<https://www.rfc-editor.org/rfc/rfc7644#section-3.14>).
   `SCIM_SPEC_COVERAGE.md:167`.

9. **Pagination stricter than RFC** — `A5_PaginationSpec.groovy:28,42` (`itemsPerPage == 2` and
   `Resources.size() == 2`), `:107` (PAG_03 `itemsPerPage == 2`), `:180` (PAG_06). RFC 7644 §3.4.2.4
   Table 6 says the server "MUST NOT return more results than specified, although it MAY return
   fewer results" (<https://www.rfc-editor.org/rfc/rfc7644#section-3.4.2.4>). `SCIM_SPEC_COVERAGE.md:104`.

10. **PUT omission stricter than RFC** — `A3_UserCrudSpec.groovy:197–208` (USR_05) and
    `A6_GroupLifecycleSpec.groovy:147–148` (GRP_04) require omitted `title`/`nickName`/`externalId`
    to be cleared. RFC 7644 §3.5.1: omitted readWrite attributes "MAY ... be cleared, or the service
    provider MAY assign a default value" (<https://www.rfc-editor.org/rfc/rfc7644#section-3.5.1>).
    `SCIM_SPEC_COVERAGE.md:115,267`.

11. **`/Me` hard-asserts 501** — `A3_UserCrudSpec.groovy:334–340` requires status 501 plus Error
    schema. RFC 7644 §3.11 gives three legal responses: 501 (not supported), 308 redirect, or
    aliasing to the subject resource (<https://www.rfc-editor.org/rfc/rfc7644#section-3.11>). A
    compliant /Me implementation fails USR_11. `SCIM_SPEC_COVERAGE.md:154`.

12. **Failure-path tests pass on wrong-but-broad outcomes** — e.g. `A4_PatchOperationsSpec.groovy:203–210`
    (PAT_08 accepts 400 **or** 404 with only a log for 404), `A8_SecurityAndRobustnessSpec.groovy:80,148`
    (SEC_03 `[200,400,403]`; SEC_06 `[400,403,404,405,501]`), `A9_NegativeAndEdgeCasesSpec.groovy:168,199,380`
    (NEG_08/10 404 or 501; NEG_19 201 **or** 400), `A7_BulkOperationsSpec.groovy:172` (BLK_03 `[413,400]`,
    no error-body limit check), `:717` (BLK_14 `[400,404]`). Some ranges are semantically fine, but they
    demonstrably weaken the "Covered" labels in the coverage doc.

### Cited Findings — B. Additional false-pass risks (not in the coverage doc)

13. **A2 mutability loop requires a value that RFC marks optional (this is a false-fail, see C) but
    the helper is inconsistent**: `A2_SchemaValidationSpec.groovy:394–399` asserts
    `VALID_MUTABILITIES.contains(attr.mutability)` with no null guard, while the universal helper
    `validateAttributeCharacteristics` (`A2:428–431`) only checks when non-null. Two tests can
    disagree on the same server.

14. **A5 seed/prefix counts are one-sided**: `A5_BaseSpec.groovy:14` defines one JVM-wide
    `PREFIX = "flt_<uuid6>_"` shared by all three A5 spec classes (suite order:
    `ScimValidatorSuite.groovy:23–25`). Filters like `userName sw PREFIX` therefore match up to 15
    seed users (5 per spec class) depending on cleanup timing, while assertions only check
    `>= 5`/`>= 3`/`>= 2` (A5_FilteringSpec.groovy:46–47,77,111,394,424,513,545,589;
    A5_PaginationSpec.groovy:26) and never assert the expected exact result set. A server that
    returns extra non-matching resources is not caught.

15. **Several operator/feature tests never assert exclusions**: FLT_05
    (`A5_FilteringSpec.groovy:93–95`) only checks `count >= 2`; FLT_27 (`:513`) only
    `totalResults >= 5`; FLT_22/29/31 (`:424,545,575`) only `>= 5`. A server ignoring the operator or
    filter clause entirely can pass (FLT_06/FLT_03 do use `.every{}` but only as a positive check).

16. **Null/empty-tolerant assertions**: `A3_UserCrudSpec.groovy:423`
    (`name == null || name.givenName == null` — either branch passes), `A4_PatchOperationsSpec.groovy:113`
    (PAT_04 accepts `externalId == ""` as removed), `A4:364` (PAT_14 accepts any all-null map),
    `A5_PaginationSpec.groovy:83,129,148` (`Resources == null || isEmpty()`), `A6:410`
    (`groupsAfter == null || ...`), `A5_FilteringSpec.groovy:561` (`resources == null || isEmpty()`).
    Individually minor; collectively they inflate "Covered".

17. **Silent ignore and deviation-only branches**: `A4_PatchOperationsSpec.groovy:509–512` (PAT_18:
    200 but dotted keys silently ignored → NOTE, pass), `A4:545–546` (PAT_19: server removed *all*
    members → DEVIATION, pass), `A7_BulkOperationsSpec.groovy:893–906,965–990` (BLK_18/19:
    4xx per-op → DEVIATION, pass), `A8:420–422,441–443,574–576` (SEC_19 accepts 200; SEC_20 accepts
    400; SEC_24 accepts 412 — each with a log only), `A1:223–225` (log then assert), `A9:200–204`
    (NEG_10 501 logged; the 501 branch performs no error-schema assertion at all), `A6:323–325`
    (duplicate group displayName accepted with a NOTE).

18. **Error-path tests that only check status/echo**: `A5_FilteringSpec.groovy:140–149` (FLT_10 checks
    only 400, no error schema/scimType), `A9:316–317` (NEG_17 asserts
    `assertScimError(response, response.statusCode())` — self-referential expected status),
    `A3:159–160` and other hand-rolled error checks bypass the shared `assertScimError` and omit
    `schemas`/`status` string assertions (USR_04, USR_08, USR_09, USR_12, USR_15, GRP_07/08/09/10,
    A6:218–219,252–253,274–275,235–236). The shared helper exists (`ScimBaseSpec.groovy:442`), is
    used in A9/A1, but not consistently elsewhere.

19. **Password-leak tests are substring checks only** — `A8:164–165,604,615,624`
    (`!body.contains('"password"')`); `attributes=password` projection (RFC 7644 §3.9) and error-body
    leaks are untested (SCIM_SPEC_COVERAGE.md:227 already notes the projection case).

20. **`NEG_19` accepts 201 for an unknown schema URN** (`A9:379–383`): a server that silently accepts
    `urn:...:NonExistentSchema` passes; RFC 7643 §3 requires `schemas` to identify the resource's
    schemas, so the 201 branch is a false-pass (coverage doc calls NEG_19 "loose" at
    SCIM_SPEC_COVERAGE.md:192).

21. **Deviations are not machine-readable** — all deviation/NOTE output goes through
    `ScimOutput.println` to stdout; there is no structured report, count, or summary anywhere.
    Worse, `ScimOutput.groovy:5–15` **suppresses all output when capture is enabled**
    (`ScimRunContext.isCaptureEnabled()`), and messages are not recorded in the capture either
    (`ScimRunContext.record` only stores HTTP exchanges). A library consumer running with capture on
    loses every DEVIATION/NOTE, so the suite can report "green" with zero visibility of tolerated
    non-compliance.

### Cited Findings — C. Additional false-fail risks (compliant servers that fail)

22. **PATCH is optional but A4 is ungated** — 21 PAT tests run regardless of
    `patch.supported=false`; `isPatchSupported()` unused. RFC 7644 §3.5.2
    (<https://www.rfc-editor.org/rfc/rfc7644#section-3.5.2>).

23. **Bulk is optional but A7 is ungated** — 19 BLK tests run regardless of `bulk.supported=false`;
    `isBulkSupported()` unused. RFC 7644 §3.7
    (<https://www.rfc-editor.org/rfc/rfc7644#section-3.7>). `SCIM_SPEC_COVERAGE.md:145`.

24. **ETag optional but multiple hard requirements**: SEC_05 requires ETag or `meta.version`
    (`A8:121–124`); SEC_08 requires an ETag (`A8:176–185`); SEC_09 requires 304 for `If-None-Match: *`
    (`A8:190–198`); SEC_23 requires ETag presence and change (`A8:505–520`); GRP_17 requires ETag +
    412 (`A6:500–518`); SEC_21/22 always require 412 (`A8:448–465,469–486`) even for servers that do
    not advertise `etag.supported` (contrast USR_16 `A3:475–515` and PAT_20 `A4:569–609`, which
    self-gate on `etag != null`). RFC 7644 §3.14 makes ETag support optional
    (<https://www.rfc-editor.org/rfc/rfc7644#section-3.14>).

25. **A2 requires the Enterprise User extension schema** — `A2:274–277` hard-fails if
    `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User` is not published. The extension is
    an optional capability (servers declare it via ResourceType `schemaExtensions`). USR_02
    (`A3:68–118`) and `createFullUser` (`ScimBaseSpec.groovy:329–368`) further assume extension
    round-trip on servers that may not implement it. This risk is not listed in the coverage doc.

26. **A2 mutability loop rejects omitted `mutability`** — `A2:396–399` fails when `attr.mutability`
    is null, but RFC 7643 §2.2 defines defaults when characteristics are omitted (coverage doc
    itself lists the defaults as a Gap at SCIM_SPEC_COVERAGE.md:194). A server exposing attributes
    without `mutability` is compliant but fails this test. (Inconsistent with `A2:428–431`.)

27. **`/.search` per-endpoint is assumed** — FLT_16/17 hard-require 200
    (`A5_FilteringSpec.groovy:301,328`). RFC 7644 §3.4.3 describes the client MAY use POST queries;
    only root behavior (501) is tolerated (`A5_FilteringSpec.groovy:626–631`). A server without
    POST-search fails FLT_16/17.

28. **Bearer-only auth assumptions** — the suite always sends `Authorization: Bearer`
    (`ScimBaseSpec.groovy:229,251`) and requires `WWW-Authenticate` to contain "Bearer" in
    SEC_01/02 (`A8:47–48,63–64`) and to be present in SEC_15 (`A8:304,313`). A server using HTTP
    Basic (or any non-Bearer scheme — listed as a
    valid SPC auth type at `A1:58`) fails SEC_01/02/15 even though A1 accepts the scheme. SEC_15 also
    requires Basic to be rejected (`A8:306–313`).

29. **`/Me` false-fail** — see finding 11.

30. **Environment coupling can turn vendor deviations into failures** — deviation comments and
    tolerances are explicitly tuned to api.scim.dev: `A1:119–128` (absolute endpoint URLs),
    `A1:179–185` (missing `id` in schema attributes), `A2:183–187` (`groups` readWrite),
    `A4:15` (comment: api.scim.dev supports only a subset of User attributes), `A4:207,231,546,606`,
    `A5:222,246,605`. Running the same specs against the default Testcontainers target (or any other
    server) exercises different code, so results and deviation sets are not comparable across
    targets; conversely, features the Go testcontainers server lacks (e.g. sort) silently skip
    assertions via `isSortSupported()`. There is no per-target expected-deviation baseline, so
    "suite passes" is target-dependent and unversioned (see also Q2).

31. **Hard-coded non-existent IDs** — `A9:94,105,124,141` (`non-existent-id-00000000`), `A3:153`
    (`nonexistent-uuid-00000000-...`), `A7:184–186` (random UUIDs). Low risk, but not collision-proof
    on servers with natural keys.

### Cited Findings — D. Deviation-handling mechanics

32. The coverage doc's own weakness bullets (`SCIM_SPEC_COVERAGE.md:263–269`) and deviation table
    (`:271–288`) are accurate for what they list, but **incomplete**: they omit the soft bulk
    `assertOperationScimType` (`A7:1005–1014`), the additional soft call sites (SEC_03/SEC_16,
    NEG_11/NEG_13), the ETag/enterprise/`/.search`/Bearer false-fails above, and A2's strict
    mutability loop. Conversely the assessment doc (`SCIM_VALIDATOR_ASSESSMENT.md:26,272–278`)
    claims 99% completion and marks scimType validations "CLOSED", which the code does not support.
33. `SCIM_SPEC_COVERAGE.md` itself is untracked (`git status`: `?? SCIM_SPEC_COVERAGE.md`), so the
    report is not under review history; the assessment is committed.

### Inferences
- The suite is genuinely useful as a smoke/fitness harness, but its pass signal cannot be read as
  "server conforms" wherever `assertScimType` is soft, wherever a DEVIATION/NOTE branch is taken, or
  wherever a simple `>=` count is used. The coverage doc's Covered/Partial labels are more reliable
  than the assessment's "CLOSED/100%" rows.
- The RFC is explicit that omitting `scimType` is legal, so making the soft check hard-fail by
  default would itself be wrong for some cases; the correct fix is a strict mode/report that
  separates "keyword omitted" from "keyword validated".
- Because capability flags other than sort are never consulted, the suite's behavior against a
  minimal-but-compliant server is materially different from its behavior against the two targets it
  was developed against; the "100%" claims likely reflect only those targets.

### Gaps
- No CI logs/surefire reports exist in the repo (`target/surefire-reports` absent; `reports/` empty),
  so actual pass/fail rates of the 186 tests against the default Testcontainers target could not be
  verified; the assessment's 99% claim is unsubstantiated by artifacts in this repo.
- Could not verify whether api.scim.dev was used for the deviations recorded in the docs or whether
  those are historical (comments name it; no run metadata is stored).

---

## Q2. Isolation, flakiness, state dependence, and Testcontainers vs external target

### Takeaway
Isolation is fragile in three ways: `@Stepwise` chains with shared IDs (A3/A4/A6/A7) let one failure
skip the rest and leave later tests state-dependent; the A5 seed block creates five same-named users
per A5 spec class behind a JVM-wide prefix, so a single swallowed cleanup failure can turn the next
class's `setupSpec` into a 409 failure; and cleanup failures are silently discarded. Testcontainers
gives a fresh tenant per run (good) but trusts mutable `latest` images and direct SQL schema coupling;
the external target gives no isolation from prior runs and tuned deviation logging.

### Cited Findings

**State dependence / @Stepwise**
1. `@Stepwise` classes: `A1:15`, `A3:18`, `A4:19`, `A6:17`, `A7:18`. In Spock, the first failed
   feature skips all remaining features in that class, so a single failure replaces N test results
   with skips and can hide independent defects (and A7:100–101 makes the dependency explicit:
   `assert bulkCreatedUserId1 != null : "BLK_01 must pass first"`).
2. Shared-ID chains: A3 `@Shared` IDs set in USR_01/02/05/06 (`A3:21–26,37,82,173,218`) consumed by
   USR_03/08/09 (`A3:126,274,288`). A4 `multiEmailUserId` set in PAT_05 (`A4:129`) is used by PAT_06/07
   (`A4:159,178`) with no re-check. A6 `groupId` is created in GRP_01 (`A6:57`), deleted in GRP_07
   (`A6:207`), re-deleted in GRP_08 (`A6:230`). A7 `bulkCreatedUserId1/2` are set in BLK_01
   (`A7:81–86`) and only used by BLK_02 (`A7:108–112`).
3. A4's shared `testUserId` accumulates mutations across features (PAT_01 externalId, PAT_04 removal,
   PAT_11–14 name rewrites) — e.g. PAT_12 asserts `name.givenName == "InitialFirst"` seeded by PAT_11
   (`A4:266–268,308`); running PF tests individually is not supported.
4. A8/A9 keep one @Shared `testUserId`/`existingUserId` mutated by many features
   (`A8:18,26`; `A9:18,27`), and several tests PUT/PATCH the whole resource back
   (`A8:257–263,352–362`) — tolerable but order-sensitive.

**Seed data and cleanup**
5. `A5_BaseSpec.groovy:14`: `PREFIX` is a single `static final` shared by A5_FilteringSpec,
   A5_PaginationSpec and A5_SortingSpec (all extend the same base). `setupSpec` (`:18–41`) creates the
   same five usernames (`flt_<uuid>_alice@…` … `eve@…`) in each class. Cross-class duplicate creates
   only succeed because `cleanupSpec` (`ScimBaseSpec.groovy:416–421`) deletes them first; but
   `deleteUser`/`deleteGroup` swallow every exception (`ScimBaseSpec.groovy:396–411`), so one failed
   delete (network/5xx) makes the next class's `setupSpec` 201-assert fail (`A5_BaseSpec.groovy:37`)
   with a misleading 409.
6. `userData` index assumptions: `A5_FilteringSpec.groovy:20,122–123,184,203,343,384,470,488,692` and
   `A5_PaginationSpec` use `userData[0]`/`[1]`; if seeding partially succeeds (assert aborts
   setupSpec) the class errors rather than failing meaningfully.
7. Cleanup is best-effort and unverified: 11 `catch (Exception ignored)` occurrences in A3/A4 specs
   (A3 6×, A4 5×) plus the two base helpers (`ScimBaseSpec.groovy:396–411`); `cleanupSpec` clears the
   tracking lists even if deletes failed
   (`ScimBaseSpec.groovy:417–420`), orphaning resources with no report. Feature-level cleanups also
   swallow (`A3:321,393,441,519`, `A7:338–339,552–561`, `A4:554–561`).
8. Test-level deletes are not asserted (e.g. `A3:264–265` asserts 204 for USR_07, but USR_10,
   USR_13, USR_14 cleanups ignore responses). Orphans can alter later `totalResults`-based `>=`
   counts.

**Environment coupling**
9. Default run is Testcontainers (`README.md:63–69,118`; `validator-application.yml:7`), which starts
   Postgres + Flyway init + API (`ScimValidatorEnvironment.groovy:49–90`), seeds a workspace/token via
   direct SQL inserts into `workspaces`/`workspace_tokens` (`:105–153`). This is a strong schema
   coupling to the server's migrations; an init-image schema change breaks bootstrap with an opaque
   failure (`:99–102`).
10. Images are mutable tags: `validator-application.yml:9–10` (`edipal/scim-server-db:latest`,
    `edipal/scim-server-impl-go:latest`) and `README.md:119–121`; no digest pinning. Identical
    source runs can target different server builds over time; `pom.xml` pins library versions but not
    images.
11. Bootstrap is required unless an explicit target is set: `ScimBaseSpec.groovy:113–137` throws if
    Testcontainers is disabled/absent and no target is configured; provider discovery is
    `ServiceLoader` (`:115–124`, provider in
    `src/test/groovy/.../base/TestcontainersBootstrapProvider.groovy:10–15`). Startup can take up to
    3 minutes (`ScimValidatorEnvironment.groovy:89`); CI timeout is 30 minutes
    (`.github/workflows/build-test.yml:20`).
12. External-target runs (api.scim.dev or any shared server) are not isolated from other tenants or
    prior runs: tests filter by `PREFIX`/unique names for A5/A6/A7 but several assertions are global
    counts (`A5_FilteringSpec.groovy:77` etc.), while NEG_16 asserts `totalResults == 0` on a global
    query only if 200 (`A9:299–301`) and is otherwise fine; the main exposure is latency, rate
    limits, and data drift (no timeouts configured: `configureRestAssured()` is a no-op,
    `ScimBaseSpec.groovy:165–167`).
13. `RuntimeState` (base URL, token, capability flags) is `InheritableThreadLocal`
    (`ScimBaseSpec.groovy:23,270–292`) and `ValidatorConfiguration` overrides are thread-local
    (`ValidatorConfiguration.groovy:19–27`); focused tests validate this
    (`src/test/groovy/.../base/ValidatorRunIsolationSpec.groovy:15–80`). Parallel spec execution is
    not configured (`pom.xml:192–210`), so shared-target parallelism is untested.

### Inferences
- A5's shared static `PREFIX` + per-class seeding + swallowed cleanup is the highest-probability
  self-inflicted flake: one transient cleanup error converts into an unrelated setup failure in the
  next spec class.
- Testcontainers delivers per-run tenant isolation (good for repeatability) at the cost of
  reproducibility (floating images, direct DB seeding, network dependency); external-target runs are
  reproducible against a fixed server but not isolated.
- The deviation log's api.scim.dev references suggest the documented tolerances were derived from an
  external run, while the default CI path is Testcontainers — the docs do not record which target
  produced which deviation.

### Gaps
- No historical run metadata (reports, CI logs) in-repo to quantify flake frequency.
- Could not confirm the current SPC of either target (no live servers invoked per task constraints),
  so which gated tests actually skip in practice is unknown.

---

## Q3. Base infrastructure review and prioritized remediation list (P0/P1/P2)

### Takeaway
`loadServiceProviderConfig` is fail-soft in a way that silently disables only the sort gate (and
records nothing); the other capability flags are dead code. Capture-mode redaction removes
Authorization but records request bodies (which can contain passwords) and suppresses all deviation
diagnostics via `ScimOutput`. Configuration precedence is sane but static after first load. The
remediation list below is ordered by impact on the credibility of the coverage report, then effort.

### Cited Findings — Base infrastructure

1. **SPC loading silently fails / leaves flags false** — `ScimBaseSpec.groovy:172–193`: only
   `statusCode() == 200` sets flags; any other status returns without warning and without
   `spcLoaded = true`. Exceptions print one warning (`:190–192`). `asBoolean(..., false)` defaults
   every capability to false (`:195–204`); `bulkMaxOperations`/`filterMaxResults` default 0
   (`:184–185`; `asInt` at `:206–217`). The only consumer of these flags in the entire suite is
   `isSortSupported()` in A5_SortingSpec; `filterMaxResults` is loaded and never used, so the
   `tooMany`/`filter.maxResults` behavior is untestable as written.
2. **Capability getters are unused** — declarations `ScimBaseSpec.groovy:58–84`; exhaustive search
   finds zero call sites for `isSpcLoaded`, `isPatchSupported`, `isBulkSupported`, `isEtagSupported`,
   `getFilterMaxResults`; `getBulkMaxOperations` is used once indirectly (`A7:145`, Groovy property
   access).
3. **Assertion helper semantics** — `assertScimError` (`ScimBaseSpec.groovy:442–454`) hard-checks
   status, Error schema URI, and string `status`; `assertScimType` (`:463–473`) is soft by design
   (RFC-legal omission) but is the mechanism by which keyword tests pass vacuously.
4. **Capture redaction is partial** — `ScimExchangeCaptureFilter.groovy:50–56` removes the
   `Authorization` request header only. Request bodies (`:26`), response bodies (`:29`) and response
   headers (`:28`) are captured verbatim; SEC_25 (`A8:586–615`) and PAT tests send `password` values,
   which would be stored in exchanges. The focused test only covers the Authorization header
   (`src/test/groovy/.../base/ScimExchangeCaptureFilterSpec.groovy:18–54`); README's claim
   (`README.md:136–137`) is accurate but incomplete about payloads.
5. **Capture-mode output suppression** — `ScimOutput.groovy:5–21`: every `println` is dropped while
   capture is enabled, and nothing routes the text into `ScimRunContext` exchanges
   (`ScimRunContext.groovy:53–68` records only HTTP exchanges). Deviation/NOTE evidence vanishes in
   capture mode with no structured substitute. Note also that `ScimRunContext.beginRun/beginTest` are
   never called from suite code (only from focused unit tests), so the default suite compares
   `isCaptureEnabled() == false` and prints to stdout.
6. **Configuration precedence** — `ValidatorConfiguration.resolveValue` (`:111–135`): exact system
   property (`-DSCIM_BASE_URL`) → aliased system property (`-Dscim.baseUrl`, map at `:8–17`) →
   environment variable → YAML default. `DEFAULT_CONFIG` is loaded once at class init (`:18`) and
   `useRunOverrides` only replaces `baseUrl`/`authToken` (`:29–43`, focused coverage in
   `ValidatorTargetConfigurationSpec.groovy`). `SCIM_BASE_URL` precedence over api-url/workspace is
   implemented in `ScimBaseSpec.refreshConfiguration` (`:104–159`) and documented
   (`README.md:112–125`).
7. **No request timeouts** — `configureRestAssured()` (`ScimBaseSpec.groovy:165–167`) is effectively
   a no-op; no connection/socket timeout is set on `scimRequest*` (`:222–268`), so a stalled target
   can hang the suite until the CI timeout.
8. **Test count / report claims** — 186 feature methods verified; `SCIM_VALIDATOR_ASSESSMENT.md:17–26`
   says 186 and 99% with one deferred gap, but several "CLOSED" rows (A9-G02…G06, A5-G06, A3-G02 etc.)
   are only soft-checked per findings above.

### Prioritized remediation list

**P0 — fix before the coverage report can be trusted (low/medium effort, high credibility impact)**

| # | Problem | Evidence | Recommendation |
|---|---|---|---|
| P0-1 | Keyword tests pass when `scimType` omitted; assessment marks them CLOSED | `ScimBaseSpec.groovy:463–473`; `A7:1005–1014`; call sites listed in finding 1 | Add `scim.strictScimType` (default true for keyword-specific tests). On omission: fail in strict runs, and in tolerant runs emit a structured deviation record (test id, expected keyword, observed omission). Never edit `SCIM_VALIDATOR_ASSESSMENT.md` to say "validated" unless strict mode passed. |
| P0-2 | Optional features hard-fail compliant servers (PATCH, bulk, ETag, /.search, enterprise schema, /Me) | Findings 8, 22–27; unused `isPatchSupported`/`isBulkSupported`/`isEtagSupported` (`ScimBaseSpec.groovy:62–80`) | Gate each test class/feature: skip (Spock `@Requires`/`Assumptions.assumeTrue`) when the SPC flag is false/absent, and fail the run loudly if SPC could not be loaded (see P0-3). For ETag, gate SEC_05/08/09/21/22/23 and GRP_17 on `etag.supported` (or on observed `meta.version`); for enterprise, gate A2's enterprise assertions and USR_02. |
| P0-3 | SPC load failure is silent; capability flags stay false; 5 of 6 getters dead | `ScimBaseSpec.groovy:172–193,58–84`; finding 1/2 | Log/fail on non-200; distinguish `unknown` from `false`; add a per-run capability banner. Use `filter.maxResults` to add the missing `tooMany` test or delete the dead getter. |
| P0-4 | Log-only deviations can never fail the run and are invisible in capture mode | FLT_13/14 (`A5_FilteringSpec.groovy:215–223,240–247`), SRT_02 (`A5_SortingSpec.groovy:65–72`), A1 (`:386–396` etc.), PAT_18/19, SEC_19/20/24, BLK_18/19; `ScimOutput.groovy:5–15` | Make deviations a first-class outcome: collect them (`ScimRunContext` or a report sink), emit a machine-readable summary with test ids, and support `scim.strictDeviations=true` to fail. Fix `ScimOutput` to buffer rather than drop when capture is on. |
| P0-5 | `/Me` test asserts 501 only | `A3_UserCrudSpec.groovy:334–340` | Accept 200 (alias), 308 (redirect), or 501; only require the Error schema in the 501 case. |
| P0-6 | `itemsPerPage == count` stricter than RFC | `A5_PaginationSpec.groovy:28,42,107,180`; RFC 7644 §3.4.2.4 | Assert `itemsPerPage <= count`, `itemsPerPage == Resources.size()`, and `totalResults` consistent; reserve `== count` for the case where `totalResults - startIndex + 1 >= count` and note server `filter.maxResults`. |

**P1 — material coverage-quality fixes (medium effort)**

| # | Problem | Evidence | Recommendation |
|---|---|---|---|
| P1-1 | PUT omission tests require clearing; RFC allows defaults | `A3:197–208`, `A6:147–148`; RFC 7644 §3.5.1 | Accept either cleared or defaulted (for externalId, accept absent/null or the server's default), and compare against the previous value to prove replacement. |
| P1-2 | A2 strict mutability loop + enterprise schema requirement | `A2:394–399` vs `A2:428–431`; `A2:274–277` | Null-guard mutability (assert only when present); gate enterprise schema assertions on its publication or mark as capability-specific. |
| P1-3 | Weak one-sided counts and no exclusion checks | FLT_02/05/22/27/29/31 (`A5_FilteringSpec.groovy:46–47,93–95,424,513,545,575`); A5 prefix shared across 3 classes (`A5_BaseSpec.groovy:14`) | Assert exact result sets (or exact membership + no extras) using the identities created by the test; make `PREFIX` per spec class (move to instance/`@Shared` per class or include class name) so counts are deterministic. |
| P1-4 | `@Stepwise`/shared-ID chains hide failures and make tests non-runnable independently | Finding 1–4; `A7:100–101`, `A4:129→159,178` | Refactor cross-feature dependencies into `@Shared` fixtures created in `setupSpec`, or explicit per-feature setup; at minimum report "skipped after failure" prominently and treat skip counts as suspect in the coverage doc. |
| P1-5 | Cleanup failures swallowed; possible cross-class setup cascade | `ScimBaseSpec.groovy:396–411,416–421`; A5 findings 5–6 | Record delete failures as deviations/warnings; consider verifying cleanup (GET→404) in teardown; never clear tracking lists without logging failures. |
| P1-6 | Error-path tests skip `assertScimError` structure and only check status/echo | USR_04/08/09/12/15, GRP_07/08/09/10, FLT_10, NEG_17 (finding 18) | Route all error checks through `assertScimError` (or a shared strict variant) and drop the self-referential status trick in NEG_17. |
| P1-7 | `NEG_19` accepts 201 for unknown schema URN | `A9:379–383` | Treat acceptance as a deviation at minimum; strict mode should require 400 `invalidValue`/`invalidSyntax` for an undefined schema. |
| P1-8 | Bulk `maxOperations` accepts 400 without body check; RFC mandates 413 + limit in body | `A7:172`; RFC 7644 §3.7.4 | Assert 413 and verify the error body names the exceeded limit (or document acceptance of 400 as an explicit deviation). |
| P1-9 | Bearer-only auth assumptions | `A8:47–48,62–64,304,313`; `ScimBaseSpec.groovy:229,251` | Discover the advertised scheme from SPC `authenticationSchemes` and/or accept any `WWW-Authenticate` scheme; make the Basic-rejection test conditional on the server advertising Bearer. |

**P2 — hygiene / reproducibility (low effort or operational)**

| # | Problem | Evidence | Recommendation |
|---|---|---|---|
| P2-1 | Capture body/header redaction incomplete | `ScimExchangeCaptureFilter.groovy:22–31,50–56` | Redact `password` in request/response JSON, `Authorization` (already), `Set-Cookie`, and other sensitive headers; add focused tests like `ScimExchangeCaptureFilterSpec.groovy:18–54`. |
| P2-2 | No request timeouts | `ScimBaseSpec.groovy:165–167,222–268` | Configure connect/socket/read timeouts from config; fail with a clear bootstrap/target error. |
| P2-3 | Mutable `latest` container images + direct DB seeding | `validator-application.yml:8–10`; `ScimValidatorEnvironment.groovy:51,70,105–153` | Pin image digests per release; document migration coupling; consider API-driven seeding instead of SQL. |
| P2-4 | Non-405 discovery method handling only logged; `Allow` not enforced | `A1:386–396,421–431,456–466` | Enforce 405 + `Allow: GET` when `strictDeviations` is on; keep tolerant mode for legacy targets. |
| P2-5 | Inaccurate RFC quotes in comments | `A5_PaginationSpec.groovy:114–116` (PAG_04 text not verbatim), `A5_FilteringSpec.groovy:613–614` (FLT_33), `A5_SortingSpec.groovy:131–132` (SRT_05), `A1:222`; listed at `SCIM_SPEC_COVERAGE.md:269` | Correct quotes against RFC 7644 text; add section anchors. |
| P2-6 | Deviation set differs per target, unrecorded | Findings 30; `A4:15` and api.scim.dev comments | Store per-target expected-deviation baselines and fail when a new deviation appears; record target identity/version in reports. |
| P2-7 | Assessment doc overstates closure | `SCIM_VALIDATOR_ASSESSMENT.md:26,272–278` | Regenerate the assessment from the suite's structured results (once P0-1/P0-4 exist), not from test titles. |

### Inferences
- P0-1…P0-4 are mutually reinforcing: a strict scimType mode, real feature gating with a fail-loud
  SPC load, `/Me` flexibility, and a structured deviation sink together convert the suite from
  "green unless visibly wrong" into "green only for validated requirements", which is the minimum
  change needed for the coverage report's Covered/Partial/Gap labels to be defensible.
- P1-3 and P1-4 are the highest-value false-pass fixes beyond the documented weaknesses because they
  multiply across ~20 A5/A3/A4 tests.
- Most P2 items are operations/security hygiene; they matter for reproducibility and for using the
  suite as a library but do not change per-run verdicts.

### Gaps
- Effort estimates assume access to the Testcontainers target for regression runs; no CI logs exist
  in-repo to calibrate how often the A5 cleanup cascade or stepwise skips actually occur.
- The true effect of making scimType strict cannot be quantified without a target run: some servers
  may legitimately omit it, so the recommendation is a configurable strict mode plus structured
  reporting rather than an unconditional hard fail.
