package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Area 9 — Negative and Edge Cases
 *
 * Validates proper error handling, edge cases, and protocol robustness per RFC 7644 §3.12.
 * Tests uniqueness constraints, missing required attributes, malformed payloads,
 * scimType error keywords, non-existent resources, and security boundary conditions.
 */
class A9_NegativeAndEdgeCasesSpec extends ScimBaseSpec {

    @Shared String existingUserId
    @Shared String existingUserName

    def setupSpec() {
        loadServiceProviderConfig()

        // Create a user for uniqueness and reference tests
        def response = createUser()
        assert response.statusCode() == 201 : "Setup failed: ${response.body().asString()}"
        existingUserId = response.jsonPath().getString("id")
        existingUserName = response.jsonPath().getString("userName")
    }

    def cleanupSpec() {
        if (existingUserId) {
            deleteUser(existingUserId)
        }
    }

    // ─── NEG_01: Duplicate userName returns 409 ─────────────────────────────

    def "NEG_01: POST /Users with duplicate userName returns 409 Conflict with uniqueness scimType"() {
        // RFC 7644 §3.3.1, §3.12 — uniqueness constraint violation → 409 with scimType: uniqueness
        given:
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: existingUserName,
            emails  : [[value: "dup_${UUID.randomUUID().toString().substring(0, 6)}@test.com", type: "work", primary: true]]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then:
        assertScimError(response, 409, "uniqueness")

        cleanup:
        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) deleteUser(id)
        }
    }

    // ─── NEG_02: POST without required userName returns 400 ─────────────────

    def "NEG_02: POST /Users without userName returns 400 with invalidValue scimType"() {
        // RFC 7643 §4.1, RFC 7644 §3.12 — missing required userName → 400 invalidValue
        given:
        Map payload = [
            schemas: [USER_SCHEMA],
            emails : [[value: "no_username@test.com", type: "work", primary: true]]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then:
        assertScimError(response, 400, "invalidValue")

        cleanup:
        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) deleteUser(id)
        }
    }

    // ─── NEG_03: GET non-existent User returns 404 ──────────────────────────

    def "NEG_03: GET /Users with non-existent ID returns 404"() {
        // RFC 7644 §3.4.1 — Resource not found → 404
        when:
        Response response = scimRequest()
            .get("/Users/non-existent-id-00000000")

        then:
        assertScimError(response, 404)
    }

    // ─── NEG_04: DELETE non-existent User returns 404 ───────────────────────

    def "NEG_04: DELETE /Users with non-existent ID returns 404"() {
        when:
        Response response = scimRequest()
            .delete("/Users/non-existent-id-00000000")

        then:
        assertScimError(response, 404)
    }

    // ─── NEG_05: PUT non-existent User returns 404 ──────────────────────────

    def "NEG_05: PUT /Users with non-existent ID returns 404"() {
        given:
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: "ghost_${UUID.randomUUID().toString().substring(0, 8)}@test.com",
            emails  : [[value: "ghost@test.com", type: "work", primary: true]]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .put("/Users/non-existent-id-00000000")

        then:
        assertScimError(response, 404)
    }

    // ─── NEG_06: PATCH non-existent User returns 404 ────────────────────────

    def "NEG_06: PATCH /Users with non-existent ID returns 404"() {
        given:
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "active", value: false]
        ])

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/non-existent-id-00000000")

        then:
        assertScimError(response, 404)
    }

    // ─── NEG_07: Malformed JSON body returns 400 ────────────────────────────

    def "NEG_07: POST with malformed JSON body returns 400 with invalidSyntax scimType"() {
        // RFC 7644 §3.12 — syntactically invalid JSON → 400 invalidSyntax
        when:
        Response response = scimRequestQuiet()
            .body("{this is not valid json!!!")
            .post("/Users")

        then:
        assertScimError(response, 400, "invalidSyntax")
    }

    // ─── NEG_08: Invalid endpoint returns 404 ───────────────────────────────

    def "NEG_08: GET on non-existent endpoint returns 404"() {
        when:
        Response response = scimRequestQuiet()
            .get("/NonExistentEndpoint")

        then:
        response.statusCode() in [404, 501]
        if (response.statusCode() == 404) {
            assertScimError(response, 404)
        }
    }

    // ─── NEG_09: Empty body POST returns 400 ────────────────────────────────

    def "NEG_09: POST /Users with empty body returns 400 with invalidSyntax scimType"() {
        // RFC 7644 §3.12 — empty body is not valid JSON → 400 invalidSyntax (eliminating 500 tolerance)
        when:
        Response response = scimRequestQuiet()
            .body("")
            .post("/Users")

        then: "Strict 400 Bad Request without server error"
        assertScimError(response, 400, "invalidSyntax")
    }

    // ─── NEG_10: Random UUID URL returns 404 with SCIM Error body ───────────

    def "NEG_10: GET /{random_uuid} returns 404 with SCIM Error schema"() {
        // RFC 7644 §3.12 — Error responses MUST include a SCIM Error body
        given: "A completely random UUID path"
        String randomPath = UUID.randomUUID().toString()

        when: "GET /{randomPath}"
        Response response = scimRequestQuiet()
            .get("/${randomPath}")

        then: "Status is 404 with SCIM Error schema"
        response.statusCode() in [404, 501]
        if (response.statusCode() == 404) {
            assertScimError(response, 404)
        } else {
            ScimOutput.println "DEVIATION: GET /${randomPath} returned ${response.statusCode()} instead of 404 (RFC 7644 §3.12)"
        }
    }

    // ─── NEG_11: Filter Syntax Error (invalidFilter) ────────────────────────

    def "NEG_11: Filter syntax error returns 400 with scimType invalidFilter"() {
        // RFC 7644 §3.4.2.2, §3.12 — invalid filter syntax → 400 invalidFilter
        when: "Filter query with invalid operator keyword"
        Response response = scimRequest()
            .queryParam("filter", "userName invalidOp \"test\"")
            .get("/Users")

        then: "400 Bad Request with scimType invalidFilter"
        assertScimError(response, 400, "invalidFilter")
    }

    // ─── NEG_12: PATCH on Read-Only Attribute (mutability) ──────────────────

    def "NEG_12: PATCH on read-only attribute id returns 400 with scimType mutability or ignores"() {
        // RFC 7643 §2.2, RFC 7644 §3.12 — readOnly attribute modification → 400 mutability
        when: "PATCH targeting read-only attribute id"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "id", value: "fake-new-id"]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${existingUserId}")

        then: "400 mutability or ignored with 200"
        response.statusCode() in [200, 400]
        if (response.statusCode() == 400) {
            assertScimError(response, 400, "mutability")
        } else {
            assert response.jsonPath().getString("id") == existingUserId :
                "readOnly id must not change when PATCH is accepted (RFC 7643 §2.2)"
        }
    }

    // ─── NEG_13: PATCH with Invalid Path (invalidPath or noTarget) ──────────

    def "NEG_13: PATCH with invalid path returns 400 with scimType invalidPath or noTarget"() {
        // RFC 7644 §3.5.2, §3.12 — invalid path in PATCH → 400 invalidPath or noTarget
        when: "PATCH targeting malformed filter path"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "emails[invalid filter syntax].value", value: "test@test.com"]
        ])
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${existingUserId}")

        then: "400 Bad Request with SCIM Error schema"
        response.statusCode() == 400
        assertScimError(response, 400)
        assertScimType(response, "invalidPath", "noTarget", "invalidFilter", "invalidValue")
    }

    // ─── NEG_14: Root JSON Array Payload on POST User ───────────────────────

    def "NEG_14: POST /Users with JSON array root payload returns 400 Bad Request"() {
        // RFC 7644 §3.12 — Root payload must be JSON object, not array
        when: "POST array payload instead of single resource object"
        Response response = scimRequestQuiet()
            .body('[{"userName": "array_user@test.com"}]')
            .post("/Users")

        then: "Rejected with 400 Bad Request and SCIM Error schema"
        response.statusCode() == 400
        assertScimError(response, 400)
    }

    // ─── NEG_15: Primitive JSON Payload on POST User ────────────────────────

    def "NEG_15: POST /Users with primitive JSON payload returns 400 Bad Request"() {
        // RFC 7644 §3.12 — Primitive values (string, number) are invalid resource representations
        when: "POST primitive string"
        Response response = scimRequestQuiet()
            .body('"just a plain string"')
            .post("/Users")

        then: "Rejected with 400 Bad Request and SCIM Error schema"
        response.statusCode() == 400
        assertScimError(response, 400)
    }

    // ─── NEG_16: SQL Injection & Special Characters in Filter ───────────────

    def "NEG_16: SQL injection payload in filter parameter is handled safely"() {
        // RFC 7644 §3.4.2 — Filter sanitization and injection prevention
        when: "Filter with SQL injection payload"
        Response response = scimRequest()
            .queryParam("filter", "userName eq \"' OR '1'='1\"")
            .get("/Users")

        then: "Server processes request safely without 500 Internal Server Error"
        response.statusCode() in [200, 400]
        if (response.statusCode() == 200) {
            assert response.jsonPath().getInt("totalResults") == 0 :
                "Injection payload must be treated as a literal value and match no users"
        } else {
            assertScimError(response, 400)
        }
    }

    // ─── NEG_17: Path Traversal in Resource ID ──────────────────────────────

    def "NEG_17: Path traversal sequences in resource ID return 404 without leakage"() {
        // RFC 7644 §3.4.1, §3.12 — Resource not found and path safety
        when: "Request resource with path traversal sequence"
        Response response = scimRequestQuiet()
            .get("/Users/..%2f..%2fetc%2fpasswd")

        then: "Server returns 404 (or 400) with SCIM Error schema, not 500"
        response.statusCode() in [400, 404]
        assertScimError(response, response.statusCode())
    }

    // ─── NEG_18: Group Negative Cases ───────────────────────────────────────

    def "NEG_18: Group negative cases return appropriate 400 and 409 error representations"() {
        // RFC 7643 §4.2, RFC 7644 §3.3, §3.12 — Group validation
        given: "An existing group"
        String groupName = "neg_grp_${UUID.randomUUID().toString().substring(0, 8)}"
        Response created = scimRequest()
            .body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: groupName]))
            .post("/Groups")
        assert created.statusCode() == 201
        String grpId = created.jsonPath().getString("id")

        when: "POST duplicate group displayName"
        Response dupRes = scimRequest()
            .body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: groupName]))
            .post("/Groups")

        then: "Duplicate group returns 409 uniqueness, or 201 (displayName uniqueness is 'none')"
        // RFC 7643 §7 declares Group.displayName with "uniqueness": "none", so accepting the
        // duplicate is compliant; enforcing it must be reported as 409 + scimType uniqueness.
        dupRes.statusCode() in [201, 409]
        if (dupRes.statusCode() == 409) {
            assertScimError(dupRes, 409, "uniqueness")
        } else {
            ScimOutput.println "NOTE: Server permits duplicate Group displayName " +
                "(RFC 7643 §7 declares displayName uniqueness 'none')"
        }

        when: "POST group without displayName"
        Response noName = scimRequest()
            .body(JsonOutput.toJson([schemas: [GROUP_SCHEMA]]))
            .post("/Groups")

        then: "Missing displayName returns 400 Bad Request with invalidValue"
        assertScimError(noName, 400, "invalidValue")

        cleanup:
        if (dupRes?.statusCode() == 201) {
            String dupId = dupRes.jsonPath().getString("id")
            if (dupId) deleteGroup(dupId)
        }
        if (grpId) deleteGroup(grpId)
    }

    // ─── NEG_19: Unknown Schema URN in POST Payload ─────────────────────────

    def "NEG_19: POST /Users with unknown schema URN returns 400 or handles gracefully"() {
        // RFC 7644 §3.12 — Schema validation on resource creation
        given: "Payload with unknown schema URN"
        Map payload = [
            schemas : ["urn:ietf:params:scim:schemas:core:2.0:NonExistentSchema"],
            userName: "unknown_schema_${UUID.randomUUID().toString().substring(0, 6)}@test.com"
        ]

        when: "POST with invalid schema URN"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server rejects with 400 Bad Request or accepts"
        response.statusCode() in [201, 400]
        if (response.statusCode() == 400) {
            assertScimError(response, 400)
        }
        String id = response.statusCode() == 201 ? response.jsonPath().getString("id") : null

        cleanup:
        if (id) deleteUser(id)
    }
}

