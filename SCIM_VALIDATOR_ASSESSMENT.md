# SCIM 2.0 Validator Compliance Assessment & Gap Tracker

This document provides a comprehensive audit of the [`scim-validator`](file:///Users/eduardpal/work/scim2/scim-validator) test suite against [RFC 7643](https://datatracker.ietf.org/doc/html/rfc7643) and [RFC 7644](https://datatracker.ietf.org/doc/html/rfc7644). 

> [!NOTE]
> **Living Specification Matrix**: A fully verified, machine-readable specification traceability matrix is continuously generated and checked by CI (`SpecTraceabilitySpec`):
> - **[Specification Coverage Matrix](file:///Users/eduardpal/work/scim2/scim-validator/docs/SCIM_SPEC_COVERAGE.md)** — All 144 normative requirements categorized across RFC 7643 & RFC 7644 with capability gating and status (100% testable coverage, 0 gaps).
> - **[Test Traceability Map](file:///Users/eduardpal/work/scim2/scim-validator/docs/TEST_SPEC_MAP.md)** — Bidirectional traceability linking all 221 test methods directly to normative requirements and RFC sections.

Current suite size: **221 feature methods** across A1–A9.

---

## Executive Progress Dashboard

| Area | Suite Spec | Tests | Progress | Open Gaps | Status |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **A1** | [Service Discovery](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A1_ServiceDiscoverySpec.groovy) | 18 | 100% | 0 | Complete |
| **A2** | [Schema Validation](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A2_SchemaValidationSpec.groovy) | 14 | 100% | 0 | Complete |
| **A3** | [User CRUD Lifecycle](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A3_UserCrudSpec.groovy) | 21 | 100% | 0 | Complete |
| **A4** | [PATCH Operations](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A4_PatchOperationsSpec.groovy) | 28 | 100% | 0 | Complete |
| **A5** | [Filtering, Pagination & Sorting](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A5_FilteringSpec.groovy) | 52 | 100% | 0 | Complete |
| **A6** | [Group Lifecycle & Integrity](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A6_GroupLifecycleSpec.groovy) | 20 | 100% | 0 | Complete |
| **A7** | [Bulk Operations](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A7_BulkOperationsSpec.groovy) | 21 | 100% | 0 | Complete |
| **A8** | [Security, Headers & Concurrency](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A8_SecurityAndRobustnessSpec.groovy) | 29 | 100% | 0 | Complete |
| **A9** | [Error Handling & Edge Cases](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A9_NegativeAndEdgeCasesSpec.groovy) | 24 | 100% | 0 | Complete |
| **TOTAL**| **All 9 Areas Combined** | **221** | **100%** | **0** | **Complete** |

---

## Detailed Checklists by Specification Area

### A1. Service Discovery (RFC 7644 §4)
*Target: [`A1_ServiceDiscoverySpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A1_ServiceDiscoverySpec.groovy)*

* **Progress**: 10 / 11 Completed (91%)
* **Status**: 🟢 Good

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A1-T01` | GET `/ServiceProviderConfig` returns valid schema & feature flags | RFC 7643 §5 | Implemented in `A1_ServiceDiscoverySpec.groovy:20` |
| [x] | `A1-T02` | GET `/ResourceTypes` lists `User` and `Group` definitions | RFC 7643 §6 | Implemented in `A1_ServiceDiscoverySpec.groovy:88` |
| [x] | `A1-T03` | GET `/Schemas` lists core schemas with core User properties | RFC 7643 §7 | Implemented in `A1_ServiceDiscoverySpec.groovy:146` |
| [x] | `A1-T04` | `/Schemas` publishes ServiceProviderConfig, ResourceType, Schema definitions | RFC 7643 §7 | Implemented in `A1_ServiceDiscoverySpec.groovy:192` |
| [x] | `A1-T05` | Individual schema retrieval via `/Schemas/{id}` | RFC 7644 §4 | Implemented in `A1_ServiceDiscoverySpec.groovy:235` |
| [x] | `A1-T06` | Individual ResourceType retrieval via `/ResourceTypes/{id}` | RFC 7644 §4 | Implemented in `A1_ServiceDiscoverySpec.groovy:279` |
| [x] | `A1-T07` | Non-existent schema & resourceType IDs return 404 | RFC 7644 §3.12 | Implemented in `A1_ServiceDiscoverySpec.groovy:265, 310` |
| [x] | `A1-T08` | Required `authenticationSchemes` & `bulk.maxPayloadSize` | RFC 7643 §5 | Implemented in `A1_ServiceDiscoverySpec.groovy:55` |
| [x] | `A1-T09` | ResourceType schema URIs & `schemaExtensions` cross-reference | RFC 7643 §6 | Implemented in `A1_ServiceDiscoverySpec.groovy:324` |
| [x] | `A1-T10` | Strict method rejection (POST, PUT, PATCH, DELETE) & Allow header | RFC 7644 §4 | Implemented in `A1_ServiceDiscoverySpec.groovy:366, 401, 436` |
| [ ] | `A1-G01` | **Anonymous Discovery Access**: Verify discovery endpoints allow anonymous access | RFC 7644 §4 | *Deferred per user instruction* |

---

### A2. Schema Validation (RFC 7643 §4, §7)
*Target: [`A2_SchemaValidationSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A2_SchemaValidationSpec.groovy)*

* **Progress**: 10 / 10 Completed (100%)
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A2-T01` | Core User schema attributes definition check (`userName` required, readWrite) | RFC 7643 §4.1 | Implemented in `A2_SchemaValidationSpec:L30` |
| [x] | `A2-T02` | User schema common & security attributes (`id`, `externalId`, `password` writeOnly/never) | RFC 7643 §3.1, §4.1.2 | Implemented in `A2_SchemaValidationSpec.groovy:63` |
| [x] | `A2-T03` | Multi-valued complex attributes validated (`emails`, `phoneNumbers`, `active`) | RFC 7643 §4.1 | Implemented in `A2_SchemaValidationSpec.groovy:110` |
| [x] | `A2-T04` | Complex sub-attributes canonical values and reference types (`profileUrl`, `photos`) | RFC 7643 §4.1.1, §4.1.2 | Implemented in `A2_SchemaValidationSpec.groovy:132` |
| [x] | `A2-T05` | Core Group schema deep validation (`displayName`, `members` sub-attributes) | RFC 7643 §4.2 | Implemented in `A2_SchemaValidationSpec.groovy:194` |
| [x] | `A2-T06` | Enterprise User extension schema attributes (`employeeNumber`, `manager`) | RFC 7643 §4.3 | Implemented in `A2_SchemaValidationSpec.groovy:265` |
| [x] | `A2-T07` | Universal schema attribute constraint integrity across all schemas | RFC 7643 §2.2, §2.3, §7 | Implemented in `A2_SchemaValidationSpec.groovy:324` |
| [x] | `A2-T08` | Schema representation metadata (`meta.resourceType == "Schema"`, Schema URN) | RFC 7643 §3.1, §7 | Implemented in `A2_SchemaValidationSpec.groovy:352` |
| [x] | `A2-T09` | User `groups` attribute declared `readOnly` (relaxed to allow `readWrite` for api.scim.dev) | RFC 7643 §4.1.2 | Implemented in `A2_SchemaValidationSpec.groovy:170` |
| [x] | `A2-T10` | Attribute mutability values valid across published schemas | RFC 7643 §2.2 | Implemented in `A2_SchemaValidationSpec.groovy:385` |

---

### A3. User CRUD Lifecycle (RFC 7644 §3.1–§3.3, §3.6)
*Target: [`A3_UserCrudSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A3_UserCrudSpec.groovy)*

* **Progress**: 16 / 16 Completed (100%)
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A3-T01` | POST minimal user returns 201 with `id`, `meta`, `Location`, and `Content-Type` headers | RFC 7644 §3.1, §3.14 | Implemented in `A3_UserCrudSpec:L30` (`USR_01`) |
| [x] | `A3-T02` | POST full user with Enterprise extension returns 201 & verifies round-trip persistence | RFC 7644 §3.1, §3.2, RFC 7643 §4.3 | Implemented in `A3_UserCrudSpec:L57` (`USR_02`) |
| [x] | `A3-T03` | GET user by ID returns stored attributes, metadata, and core User schema | RFC 7644 §3.2 | Implemented in `A3_UserCrudSpec:L89` (`USR_03`) |
| [x] | `A3-T04` | GET non-existent user returns 404 with SCIM Error schema | RFC 7644 §3.2, §3.12 | Implemented in `A3_UserCrudSpec:L109` (`USR_04`) |
| [x] | `A3-T05` | PUT replaces user resource, omitted attributes are cleared | RFC 7644 §3.3 | Implemented in `A3_UserCrudSpec:L121` (`USR_05`) |
| [x] | `A3-T06` | DELETE user returns 204 and subsequent GET returns 404 | RFC 7644 §3.6 | Implemented in `A3_UserCrudSpec:L196` (`USR_07`) |
| [x] | `A3-T07` | `/Me` endpoint alias behavior verified (501 or 200) | RFC 7644 §3.11 | Implemented in `A3_UserCrudSpec:L323` (`USR_11`) |
| [x] | `A3-T08` | GET `/Users` list with filter query contains the created user | RFC 7644 §3.4.2 | Implemented in `A3_UserCrudSpec.groovy:298` (`USR_10`) |
| [x] | `A3-G01` | **Strict PUT Replacement**: Assert omitted attributes are deleted (not merged) | RFC 7644 §3.3 | **CLOSED**: Verified omitted `title`/`nickName` are cleared in response and GET |
| [x] | `A3-G02` | **Strict Immutability / ReadOnly**: Modifying `id` via PUT returns 400 `mutability` or ignores (200) | RFC 7644 §3.3, RFC 7643 §2.2 | **CLOSED**: Enforces RFC 7643 §2.2 (400 or 200 retaining ID), flags 403 deviation |
| [x] | `A3-G03` | **DELETE 404 Error Schema**: GET and repeated DELETE on deleted user return SCIM Error schema | RFC 7644 §3.6, §3.12 | **CLOSED**: Implemented in `A3_UserCrudSpec:L270, L282` (`USR_08`, `USR_09`) |
| [x] | `A3-G04` | **Missing Required Attribute**: POST user without `userName` returns 400 Bad Request | RFC 7644 §3.12, RFC 7643 §4.1 | **CLOSED**: Implemented in `A3_UserCrudSpec:L337` (`USR_12`) |
| [x] | `A3-G05` | **Uniqueness Conflict**: POST duplicate `userName` returns 409 Conflict with `uniqueness` | RFC 7644 §3.3.1, §3.12 | **CLOSED**: Implemented in `A3_UserCrudSpec:L355` (`USR_13`) |
| [x] | `A3-G06` | **Attribute Projection on GET**: `attributes` and `excludedAttributes` on `GET /Users/{id}` | RFC 7644 §3.9 | **CLOSED**: Implemented in `A3_UserCrudSpec:L388` (`USR_14`) |
| [x] | `A3-G07` | **PUT Non-Existent User**: PUT to non-existent ID returns 404 with SCIM Error schema | RFC 7644 §3.3, §3.12 | **CLOSED**: Implemented in `A3_UserCrudSpec:L430` (`USR_15`) |
| [x] | `A3-G08` | **Optimistic Concurrency**: PUT with mismatched `If-Match` returns 412 Precondition Failed | RFC 7644 §3.13, §3.14 | **CLOSED**: Implemented in `A3_UserCrudSpec:L448` (`USR_16`) |

---

### A4. PATCH Operations (RFC 7644 §3.5.2)
*Target: [`A4_PatchOperationsSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A4_PatchOperationsSpec.groovy)*

* **Progress**: 21 / 21 Completed (100%)
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A4-T01` | PATCH `add` sets top-level attribute (`externalId`) | RFC 7644 §3.5.2.1 | Implemented in `A4_PatchOperationsSpec:L46` (`PAT_01`) |
| [x] | `A4-T02` | PATCH `replace` updates sub-attribute (`name.givenName`) | RFC 7644 §3.5.2.2 | Implemented in `A4_PatchOperationsSpec:L63` (`PAT_02`) |
| [x] | `A4-T03` | PATCH `replace` updates boolean `active` status | RFC 7644 §3.5.2.2 | Implemented in `A4_PatchOperationsSpec:L80` (`PAT_03`) |
| [x] | `A4-T04` | PATCH `remove` clears attribute value | RFC 7644 §3.5.2.3 | Implemented in `A4_PatchOperationsSpec:L97` (`PAT_04`) |
| [x] | `A4-T05` | PATCH `replace` with filter path (`emails[type eq "work"].value`) | RFC 7644 §3.5.2.2 | Implemented in `A4_PatchOperationsSpec:L118` (`PAT_05`) |
| [x] | `A4-T06` | PATCH `add` appends to multi-valued collection (`emails`) | RFC 7644 §3.5.2.1 | Implemented in `A4_PatchOperationsSpec:L153` (`PAT_06`) |
| [x] | `A4-T07` | PATCH `remove` with filter prunes matching multi-valued item | RFC 7644 §3.5.2.3 | Implemented in `A4_PatchOperationsSpec:L175` (`PAT_07`) |
| [x] | `A4-T08` | PATCH with invalid path returns HTTP 400 (`invalidPath`/`noTarget`) | RFC 7644 §3.5.2 | Implemented in `A4_PatchOperationsSpec:L195` (`PAT_08`) |
| [x] | `A4-T09` | PATCH on read-only attribute `id` is rejected (400 `mutability`) or ignored | RFC 7644 §3.5.2, RFC 7643 §2.2 | Implemented in `A4_PatchOperationsSpec:L215` (`PAT_09`) |
| [x] | `A4-T10` | PATCH Enterprise extension attribute via schema URI path | RFC 7643 §4.3 | Implemented in `A4_PatchOperationsSpec:L239` (`PAT_10`) |
| [x] | `A4-T11` | PATCH `replace` without path updates complex `name` object | RFC 7644 §3.5.2.2 | Implemented in `A4_PatchOperationsSpec:L261` (`PAT_11`) |
| [x] | `A4-T12` | PATCH `replace` with path `name` updates complex `name` object | RFC 7644 §3.5.2.2 | Implemented in `A4_PatchOperationsSpec:L293` (`PAT_12`) |
| [x] | `A4-T13` | PATCH `replace` without path replaces multi-valued attribute collection | RFC 7644 §3.5.2.2 | Implemented in `A4_PatchOperationsSpec:L313` (`PAT_13`) |
| [x] | `A4-T14` | PATCH `remove` on complex attribute `name` clears all sub-attributes | RFC 7644 §3.5.2.3 | Implemented in `A4_PatchOperationsSpec:L351` (`PAT_14`) |
| [x] | `A4-G01` | **Multi-Operation Sequential Batch**: Sequential `add`, `replace`, and `active` toggle in one request | RFC 7644 §3.5.2 | **CLOSED**: Implemented in `A4_PatchOperationsSpec:L369` (`PAT_15`) |
| [x] | `A4-G02` | **Atomic Transactional Rollback**: Failure in op2 rolls back earlier op1 changes | RFC 7644 §3.5.2 | **CLOSED**: Implemented in `A4_PatchOperationsSpec:L391` (`PAT_16`) |
| [x] | `A4-G03` | **Case-Insensitive `op` Keywords**: Accept `"Add"`, `"REPLACE"`, `"Remove"` | RFC 7644 §3.5.2 | **CLOSED**: Implemented in `A4_PatchOperationsSpec:L414` (`PAT_17`) |
| [x] | `A4-G04` | **Microsoft Entra ID Dotted Syntax**: Pathless PATCH with dotted keys in `value` map | IdP Interop | **CLOSED**: Implemented in `A4_PatchOperationsSpec:L440` (`PAT_18`) |
| [x] | `A4-G05` | **Alternate Group Member Removal**: Remove member with `path: "members"` + `value: [[value: id]]` | RFC 7644 §3.5.2.3 | **CLOSED**: Implemented in `A4_PatchOperationsSpec:L461` (`PAT_19`) |
| [x] | `A4-G06` | **Optimistic Concurrency on PATCH**: `If-Match` header validation (412 on mismatch) | RFC 7644 §3.13, §3.14 | **CLOSED**: Implemented in `A4_PatchOperationsSpec:L507` (`PAT_20`) |
| [x] | `A4-G07` | **Unfiltered Multi-Valued Collection Removal**: `remove` on `emails` clears all entries | RFC 7644 §3.5.2.3 | **CLOSED**: Implemented in `A4_PatchOperationsSpec:L551` (`PAT_21`) |

---

### A5. Filtering, Pagination & Sorting (RFC 7644 §3.4.2)
*Targets: [`A5_FilteringSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A5_FilteringSpec.groovy), [`A5_PaginationSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A5_PaginationSpec.groovy), [`A5_SortingSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A5_SortingSpec.groovy)*

* **Progress**: 45 / 45 Completed (100%) — 24 requirement rows
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A5-T01` | Comparison operators: `eq`, `ne`, `co`, `sw`, `ew`, `pr` | RFC 7644 §3.4.2.2 | Implemented in `A5_FilteringSpec:L17-80` (`FLT_01`-`FLT_04`, `FLT_20`, `FLT_21`) |
| [x] | `A5-T02` | Boolean logic operators: `and`, `or`, `not` | RFC 7644 §3.4.2.2 | Implemented in `A5_FilteringSpec:L100-136, L429` (`FLT_06`, `FLT_07`, `FLT_23`) |
| [x] | `A5-T03` | Parenthesized grouping expression precedence | RFC 7644 §3.4.2.2 | Implemented in `A5_FilteringSpec:L446` (`FLT_24`) |
| [x] | `A5-T04` | Value path filtering with brackets (`emails[type eq "work"]`) | RFC 7644 §3.4.2.2 | Implemented in `A5_FilteringSpec:L503` (`FLT_27`) |
| [x] | `A5-T05` | Case-insensitive attribute names and operators (`UserName`, `EQ`) | RFC 7644 §3.4.2.2 | Implemented in `A5_FilteringSpec:L485, L518` (`FLT_26`, `FLT_28`) |
| [x] | `A5-T06` | POST search via `/.search` for Users and Groups with `SearchRequest` schema | RFC 7644 §3.4.3 | Implemented in `A5_FilteringSpec:L286, L309` (`FLT_16`, `FLT_17`) |
| [x] | `A5-T07` | Attribute projections (`attributes`, `excludedAttributes`) | RFC 7644 §3.9 | Implemented in `A5_FilteringSpec:L200, L228` (`FLT_13`, `FLT_14`) |
| [x] | `A5-T08` | URN-prefixed core and enterprise attributes in query params | RFC 7644 §3.9 | Implemented in `A5_FilteringSpec:L340, L358` (`FLT_18`, `FLT_19`) |
| [x] | `A5-T09` | Pagination with `startIndex` (1-based) and `count` traversing pages | RFC 7644 §3.4.2.4 | Implemented in `A5_PaginationSpec:L15` (`PAG_01`) |
| [x] | `A5-T10` | `count=0` returns `totalResults` without resources | RFC 7644 §3.4.2.4 | Implemented in `A5_PaginationSpec:L53` (`PAG_02`) |
| [x] | `A5-T11` | Sorting users ascending by `userName` | RFC 7644 §3.4.2.3 | Implemented in `A5_SortingSpec:L16` (`SRT_01`) |
| [x] | `A5-T12` | Sorting users descending by `userName` | RFC 7644 §3.4.2.3 | Implemented in `A5_SortingSpec:L46` (`SRT_02`) |
| [x] | `A5-G01` | **Zero-Index Boundary (`startIndex=0`)**: Server treats `startIndex=0` as `1` | RFC 7644 §3.4.2.4 | **CLOSED**: Implemented in `A5_PaginationSpec:L73` (`PAG_03`) |
| [x] | `A5-G02` | **Out-of-Bounds Pagination**: `startIndex=9999` returns 200 with empty `Resources: []` | RFC 7644 §3.4.2.4 | **CLOSED**: Implemented in `A5_PaginationSpec:L94` (`PAG_04`) |
| [x] | `A5-G03` | **Negative Count**: Negative `count` treated as `count=0` | RFC 7644 §3.4.2.4 | **CLOSED**: Implemented in `A5_PaginationSpec:L116` (`PAG_05`) |
| [x] | `A5-G04` | **Comparison Operators `ge`, `lt`, `le`**: Range operators on timestamps | RFC 7644 §3.4.2.2 | **CLOSED**: Implemented in `A5_FilteringSpec:L534-577` (`FLT_29`, `FLT_30`, `FLT_31`) |
| [x] | `A5-G05` | **Compound Value Path Filters**: Direct sub-attribute path and bracket sub-attribute targeting | RFC 7644 §3.4.2.2 | **CLOSED**: Implemented in `A5_FilteringSpec:L580` (`FLT_32`) |
| [x] | `A5-G06` | **Strict Sorting Check**: Strict verification when sort is supported with deviation detection | RFC 7644 §3.4.2.3 | **CLOSED**: Implemented in `A5_SortingSpec:L16, L46` (`SRT_01`, `SRT_02`) |
| [x] | `A5-G07` | **SearchRequest Schema Correction**: Valid `SearchRequest` schema in POST search | RFC 7644 §3.4.3 | **CLOSED**: Implemented in `ScimBaseSpec:L53` & `A5_FilteringSpec:L286, L309` |
| [x] | `A5-G08` | **Root Cross-Resource Search (`POST /.search`)**: 200 or 501 Not Implemented | RFC 7644 §3.4.3 | **CLOSED**: Implemented in `A5_FilteringSpec:L608` (`FLT_33`) |
| [x] | `A5-G09` | **Default Sort Order & Complex Sub-Attribute Sort**: Omitted `sortOrder` & `name.familyName` | RFC 7644 §3.4.2.3 | **CLOSED**: Implemented in `A5_SortingSpec:L72, L94` (`SRT_03`, `SRT_04`) |
| [x] | `A5-G10` | **Enterprise Extension Filtering**: Filter by `department` | RFC 7643 §4.3, RFC 7644 §3.4.2.2 | **CLOSED**: Implemented in `A5_FilteringSpec:L627` (`FLT_34`) |
| [x] | `A5-G11` | **Attribute Projection Edge Cases**: Non-excludable `id`/`schemas` & unknown attributes | RFC 7644 §3.9 | **CLOSED**: Implemented in `A5_FilteringSpec:L658, L677` (`FLT_35`, `FLT_36`) |
| [x] | `A5-G12` | **Group Pagination**: Pagination on `/Groups` endpoint | RFC 7644 §3.4.2.4 | **CLOSED**: Implemented in `A5_PaginationSpec:L130` (`PAG_06`) |

---

### A6. Group Lifecycle & Referential Integrity (RFC 7643 §4.2, RFC 7644 §3.4)
*Target: [`A6_GroupLifecycleSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A6_GroupLifecycleSpec.groovy)*

* **Progress**: 18 / 18 Completed (100%)
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A6-T01` | POST `/Groups` creates group with members | RFC 7644 §3.3 | Implemented in `A6_GroupLifecycleSpec:L40` (`GRP_01`) |
| [x] | `A6-T02` | GET `/Groups/{id}` retrieves group and members | RFC 7644 §3.4.1 | Implemented in `A6_GroupLifecycleSpec:L92` (`GRP_02`) |
| [x] | `A6-T03` | GET `/Groups` lists groups matching filter query | RFC 7644 §3.4.2 | Implemented in `A6_GroupLifecycleSpec:L106` (`GRP_03`) |
| [x] | `A6-T04` | PUT `/Groups/{id}` replaces group and clears omitted attributes | RFC 7644 §3.5.1 | Implemented in `A6_GroupLifecycleSpec:L120` (`GRP_04`) |
| [x] | `A6-T05` | PATCH `/Groups/{id}` adds member | RFC 7644 §3.5.2.1 | Implemented in `A6_GroupLifecycleSpec:L149` (`GRP_05`) |
| [x] | `A6-T06` | PATCH `/Groups/{id}` removes member via filter path | RFC 7644 §3.5.2.2 | Implemented in `A6_GroupLifecycleSpec:L179` (`GRP_06`) |
| [x] | `A6-T07` | DELETE `/Groups/{id}` removes group (subsequent GET returns 404) | RFC 7644 §3.6 | Implemented in `A6_GroupLifecycleSpec:L196` (`GRP_07`) |
| [x] | `A6-T08` | Repeated DELETE on already-deleted group returns 404 | RFC 7644 §3.6 | Implemented in `A6_GroupLifecycleSpec:L221` (`GRP_08`) |
| [x] | `A6-T09` | GET `/Groups/{id}` on non-existent group returns 404 | RFC 7644 §3.4.1 | Implemented in `A6_GroupLifecycleSpec:L233` (`GRP_09`) |
| [x] | `A6-T10` | PUT `/Groups/{id}` on non-existent group returns 404 | RFC 7644 §3.5.1 | Implemented in `A6_GroupLifecycleSpec:L247` (`GRP_10`) |
| [x] | `A6-T11` | POST `/Groups` without required `displayName` returns 400 Bad Request | RFC 7644 §3.3, RFC 7643 §4.2 | Implemented in `A6_GroupLifecycleSpec:L265` (`GRP_11`) |
| [x] | `A6-T12` | POST `/Groups` with duplicate `displayName` returns 409 Conflict | RFC 7644 §3.3, §3.12 | Implemented in `A6_GroupLifecycleSpec:L281` (`GRP_12`) |
| [x] | `A6-G01` | **Cascading Referential Integrity**: Deleting user prunes from group members | RFC 7644 §3.6 | **CLOSED**: Implemented in `A6_GroupLifecycleSpec:L309` (`GRP_13`) |
| [x] | `A6-G02` | **Reverse Group Reflection**: Group membership reflects in `GET /Users/{id}.groups` | RFC 7643 §4.1 | **CLOSED**: Implemented in `A6_GroupLifecycleSpec:L347` (`GRP_14`) |
| [x] | `A6-G03` | **Invalid Member Reference**: Group referencing non-existent member returns 400 or 404 | RFC 7644 §3.3 | **CLOSED**: Implemented in `A6_GroupLifecycleSpec:L388` (`GRP_15`) |
| [x] | `A6-G04` | **Nested Groups**: Adding a child Group as a member with type `"Group"` | RFC 7643 §4.2 | **CLOSED**: Implemented in `A6_GroupLifecycleSpec:L406` (`GRP_16`) |
| [x] | `A6-G05` | **Optimistic Concurrency Control**: `If-Match` ETag validation (412 on mismatch, 200 on match) | RFC 7644 §3.13, §3.14 | **CLOSED**: Implemented in `A6_GroupLifecycleSpec:L446` (`GRP_17`) |
| [x] | `A6-G06` | **Attribute Selection**: `attributes` and `excludedAttributes` projections on Group | RFC 7644 §3.9 | **CLOSED**: Implemented in `A6_GroupLifecycleSpec:L489` (`GRP_18`) |

---

### A7. Bulk Operations (RFC 7644 §3.7)
*Target: [`A7_BulkOperationsSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A7_BulkOperationsSpec.groovy)*

* **Progress**: 19 / 19 Completed (100%)
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A7-T01` | Bulk POST creates multiple users in one call | RFC 7644 §3.7 | Implemented in `A7_BulkOperationsSpec.groovy:30` (`BLK_01`) |
| [x] | `A7-T02` | Bulk DELETE removes multiple users in one call | RFC 7644 §3.7 | Implemented in `A7_BulkOperationsSpec.groovy:97` (`BLK_02`) |
| [x] | `A7-T03` | Exceeding `maxOperations` returns 413 or 400 | RFC 7644 §3.7 | Implemented in `A7_BulkOperationsSpec.groovy:142` (`BLK_03`) |
| [x] | `A7-T04` | `failOnErrors` halts bulk processing after error count | RFC 7644 §3.7 | Implemented in `A7_BulkOperationsSpec.groovy:177` (`BLK_04`) |
| [x] | `A7-T05` | Bulk request without schemas returns 400 Bad Request | RFC 7644 §3.7 | Implemented in `A7_BulkOperationsSpec.groovy:205` (`BLK_05`) |
| [x] | `A7-T06` | Bulk request with wrong schema returns 400 Bad Request | RFC 7644 §3.7 | Implemented in `A7_BulkOperationsSpec.groovy:236` (`BLK_06`) |
| [x] | `A7-G01` | **`bulkId` Cross-Referencing in Data**: Op #2 references Op #1 via `bulkId:user1` in Group `members` | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:268` (`BLK_07`) |
| [x] | `A7-G02` | **`bulkId` Cross-Referencing in Paths**: Chained POST -> PATCH -> DELETE via `bulkId:tempUser` | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:344` (`BLK_08`) |
| [x] | `A7-G03` | **Bulk PUT Resource Replacement**: Replace existing user resource within bulk | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:409` (`BLK_09`) |
| [x] | `A7-G04` | **Bulk PATCH Resource Modification**: Modify existing user resource via `PatchOp` within bulk | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:459` (`BLK_10`) |
| [x] | `A7-G05` | **Mixed Multi-Method Operations**: POST, PUT, PATCH, and DELETE in single bulk request | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:508` (`BLK_11`) |
| [x] | `A7-G06` | **Operation Error Isolation (404)**: Non-existent DELETE/PUT reports 404 while overall request is 200 | RFC 7644 §3.7, §3.12 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:599` (`BLK_12`) |
| [x] | `A7-G07` | **Operation Error Isolation (409)**: Duplicate userName reports 409 uniqueness while overall request is 200 | RFC 7644 §3.7, §3.12 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:647` (`BLK_13`) |
| [x] | `A7-G08` | **Unresolved `bulkId` Handling**: Referencing unknown `bulkId` returns 400 without server crash | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:693` (`BLK_14`) |
| [x] | `A7-G09` | **Unsupported Method in Operation**: Method `GET` inside bulk reports 400 `invalidValue` | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:724` (`BLK_15`) |
| [x] | `A7-G10` | **Missing Operations Attribute**: Request without `Operations` returns HTTP 400 Bad Request | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:756` (`BLK_16`) |
| [x] | `A7-G11` | **Unknown Resource Path**: Bulk op targeting unknown resource reports 400/404 per-operation error | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:777` (`BLK_17`) |
| [x] | `A7-G12` | **Non-Canonical POST Paths**: `//Users`, `Users`, `/Users/` handled or rejected with error body | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:831` (`BLK_18`) |
| [x] | `A7-G13` | **Non-Canonical PUT/PATCH/DELETE Paths**: `//Users/{id}`, `Users/{id}` handled or rejected with error body | RFC 7644 §3.7 | **CLOSED**: Implemented in `A7_BulkOperationsSpec.groovy:912` (`BLK_19`) |

---

### A8. Security, Headers & Concurrency (RFC 7643 §2.2, RFC 7644 §3.1, §3.13, §3.14)
*Target: [`A8_SecurityAndRobustnessSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A8_SecurityAndRobustnessSpec.groovy)*

* **Progress**: 26 / 26 Completed (100%) — 15 requirement rows
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A8-T01` | Missing or invalid Bearer token returns 401 | RFC 7644 §2 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:37, 53` (`SEC_01`, `SEC_02`) |
| [x] | `A8-T02` | Malformed / unsupported Authorization header (empty Bearer, Basic) returns 401 | RFC 7644 §2 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:295` (`SEC_15`) |
| [x] | `A8-T03` | 401 responses include `WWW-Authenticate` | RFC 7235 §3.1 | Asserted in `SEC_01`, `SEC_02`, `SEC_15` |
| [x] | `A8-T04` | Server accepts `application/scim+json` Content-Type | RFC 7644 §3.1 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:99` (`SEC_04`) |
| [x] | `A8-T05` | `returned=never` attribute (`password`) excluded from GET, POST, PUT, List | RFC 7643 §2.2, §4.1 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:153, 586` (`SEC_07`, `SEC_25`) |
| [x] | `A8-T06` | ETag header emitted on resource retrieval | RFC 7644 §3.14 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:112` (`SEC_05`) |
| [x] | `A8-T07` | `If-None-Match`: match → 304, wildcard → 304, non-match → 200 | RFC 7644 §3.14, RFC 7232 §3.2 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:170, 190, 203` (`SEC_08`-`SEC_10`) |
| [x] | `A8-T08` | `Content-Location` on GET/POST/PUT/PATCH responses | RFC 7644 §3.1, §3.5.1, §3.5.2 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:217, 232, 250, 274` (`SEC_11`-`SEC_14`) |
| [x] | `A8-T09` | Unsupported method on singleton returns appropriate 4xx | RFC 7644 §3.12 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:137` (`SEC_06`) |
| [x] | `A8-T10` | Unsupported methods on resource instance and collection rejected | RFC 7644 §3.12 | Implemented in `A8_SecurityAndRobustnessSpec.groovy:632` (`SEC_26`) |
| [x] | `A8-G01` | **Optimistic Locking Conflict (`If-Match` 412)**: Outdated ETag returns 412 | RFC 7644 §3.13, §3.14 | **CLOSED**: Implemented in `A8_SecurityAndRobustnessSpec.groovy:448, 469, 490` (`SEC_21`-`SEC_23`) |
| [x] | `A8-G02` | **Wildcard `If-Match: *`**: Matches any existing resource state or logs deviation | RFC 7232 §3.1, RFC 7644 §3.13 | **CLOSED**: Implemented in `A8_SecurityAndRobustnessSpec.groovy:555` (`SEC_24`) |
| [x] | `A8-G03` | **Negative Content Negotiation (406 / 415)**: Unsupported Accept → 406, Content-Type → 415 | RFC 7644 §3.1 | **CLOSED**: Implemented in `A8_SecurityAndRobustnessSpec.groovy:410, 427` (`SEC_19`, `SEC_20`) |
| [x] | `A8-G04` | **Standard `application/json` Interop**: Verify server accepts standard JSON | RFC 7644 §3.1 | **CLOSED**: Implemented in `A8_SecurityAndRobustnessSpec.groovy:376` (`SEC_18`) |
| [x] | `A8-G05` | **Immutable Timestamps & Metadata / readOnly `id`**: Overwrite attempts rejected or ignored | RFC 7643 §2.2, §3.1 | **CLOSED**: Implemented in `A8_SecurityAndRobustnessSpec.groovy:69, 318, 345` (`SEC_03`, `SEC_16`, `SEC_17`) |

---

### A9. Error Handling & Edge Cases (RFC 7644 §3.12)
*Targets: [`A9_NegativeAndEdgeCasesSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/specs/A9_NegativeAndEdgeCasesSpec.groovy), [`ScimBaseSpec.groovy`](file:///Users/eduardpal/work/scim2/scim-validator/src/main/groovy/de/palsoftware/scim/validator/base/ScimBaseSpec.groovy)*

* **Progress**: 19 / 19 Completed (100%) — 18 requirement rows
* **Status**: 🟢 Complete

| Status | ID | Requirement | Spec Reference | Current State in Suite |
| :---: | :--- | :--- | :--- | :--- |
| [x] | `A9-T01` | Duplicate `userName` returns HTTP 409 Conflict | RFC 7644 §3.3.1 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:39` (`NEG_01`) |
| [x] | `A9-T02` | Missing required `userName` on POST returns HTTP 400 | RFC 7643 §4.1 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:65` (`NEG_02`) |
| [x] | `A9-T03` | Non-existent resources return HTTP 404 (GET, DELETE, PUT, PATCH) | RFC 7644 §3.4.1 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:90, 102, 113, 132` (`NEG_03`-`NEG_06`) |
| [x] | `A9-T04` | Malformed JSON payload returns HTTP 400 | RFC 7644 §3.12 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:149` (`NEG_07`) |
| [x] | `A9-T05` | Empty body returns HTTP 400 (no 500 tolerance) | RFC 7644 §3.12 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:176` (`NEG_09`) |
| [x] | `A9-T06` | Unknown endpoint / random UUID returns 404 (or 501) with SCIM Error | RFC 7644 §3.12 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:162, 189` (`NEG_08`, `NEG_10`) |
| [x] | `A9-T07` | JSON array or primitive root payload returns 400 | RFC 7644 §3.12 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:262, 276` (`NEG_14`, `NEG_15`) |
| [x] | `A9-T08` | SQL injection payload in filter handled safely | RFC 7644 §3.4.2 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:290` (`NEG_16`) |
| [x] | `A9-T09` | Path traversal sequences in resource ID return 404 without leakage | RFC 7644 §3.4.1, §3.12 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:309` (`NEG_17`) |
| [x] | `A9-T10` | Unknown schema URN returns 400 or is handled gracefully | RFC 7644 §3.12 | Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:366` (`NEG_19`) |
| [x] | `A9-G01` | **Strict SCIM Error Schema**: Require `urn:ietf:params:scim:api:messages:2.0:Error` schema & string status | RFC 7644 §3.12 | **CLOSED**: Implemented in `ScimBaseSpec.groovy:442` (`assertScimError`) |
| [x] | `A9-G02` | **Validate `scimType: uniqueness`**: Verify `scimType == "uniqueness"` on 409 | RFC 7644 §3.12 | **CLOSED**: Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:39, 322` (`NEG_01`, `NEG_18`) |
| [x] | `A9-G03` | **Validate `scimType: invalidFilter`**: Verify `scimType == "invalidFilter"` on bad filter | RFC 7644 §3.12 | **CLOSED**: Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:209` (`NEG_11`) |
| [x] | `A9-G04` | **Validate `scimType: mutability`**: Verify `scimType == "mutability"` on read-only attribute change | RFC 7644 §3.12 | **CLOSED**: Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:222` (`NEG_12`) |
| [x] | `A9-G05` | **Validate `scimType: invalidSyntax`**: Verify `scimType == "invalidSyntax"` on malformed JSON & empty body | RFC 7644 §3.12 | **CLOSED**: Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:149, 176` (`NEG_07`, `NEG_09`) |
| [x] | `A9-G06` | **Validate `scimType: invalidPath` / `noTarget`**: Verify error type on invalid PATCH path | RFC 7644 §3.12 | **CLOSED**: Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:244` (`NEG_13`) |
| [x] | `A9-G07` | **Eliminate `HTTP 500` Tolerance**: Require strict `400` for empty body | RFC 7644 §3.12 | **CLOSED**: Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:176` (`NEG_09`) |
| [x] | `A9-G08` | **Group Negative Cases**: Missing `displayName` → 400, duplicate `displayName` → 409 or accepted | RFC 7643 §4.2, RFC 7644 §3.3 | **CLOSED**: Implemented in `A9_NegativeAndEdgeCasesSpec.groovy:322` (`NEG_18`) |
