package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared
import org.opentest4j.TestAbortedException

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

    @SpecRef("RFC7644-3.3-R2")
    @SpecRef("RFC7643-2.2-R5")
    def "NEG_01: POST /Users with duplicate userName returns 409 Conflict with uniqueness scimType"() {
        // RFC 7644 §3.3, §3.12 — uniqueness constraint violation → 409 with scimType: uniqueness
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

    @SpecRef("RFC7643-4.1.1-R1")
    @SpecRef("RFC7644-3.3-R3")
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
        assertScimErrorOptionalType(response, 400, "invalidValue")

        cleanup:
        if (response?.statusCode() in [200, 201]) {
            try {
                String id = response.jsonPath().getString("id")
                if (id) deleteUser(id)
            } catch (Exception ignored) {}
        }
    }

    // ─── NEG_03: GET non-existent User returns 404 ──────────────────────────

    @SpecRef("RFC7644-3.4.1-R2")
    def "NEG_03: GET /Users with non-existent ID returns 404"() {
        // RFC 7644 §3.4.1 — Resource not found → 404
        when:
        Response response = scimRequest()
            .get("/Users/non-existent-id-00000000")

        then:
        assertScimError(response, 404)
    }

    // ─── NEG_04: DELETE non-existent User returns 404 ───────────────────────

    @SpecRef("RFC7644-3.6-R2")
    def "NEG_04: DELETE /Users with non-existent ID returns 404"() {
        when:
        Response response = scimRequest()
            .delete("/Users/non-existent-id-00000000")

        then:
        assertScimError(response, 404)
    }

    // ─── NEG_05: PUT non-existent User returns 404 ──────────────────────────

    @SpecRef("RFC7644-3.2-R2")
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

        cleanup:
        if (response?.statusCode() in [200, 201]) {
            try {
                String id = response.jsonPath().getString("id") ?: "non-existent-id-00000000"
                if (id) deleteUser(id)
            } catch (Exception ignored) {}
        }
    }

    // ─── NEG_06: PATCH non-existent User returns 404 ────────────────────────

    @RequiresCapability("patch")
    @SpecRef("RFC7644-3.2-R1")
    @SpecRef("RFC7644-3.12-R1")
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

    @SpecRef("RFC7644-3.12-R1")
    def "NEG_07: POST with malformed JSON body returns 400 with invalidSyntax scimType"() {
        // RFC 7644 §3.12 — syntactically invalid JSON → 400 invalidSyntax
        when:
        String leakedId = null
        Response response = scimRequestQuiet()
            .body("{this is not valid json!!!")
            .post("/Users")
        if (response?.statusCode() in [200, 201]) {
            try {
                leakedId = response.jsonPath().getString("id")
                if (leakedId) createdUserIds << leakedId
            } catch (Exception ignored) {}
        }

        then:
        assertScimErrorOptionalType(response, 400, "invalidSyntax")

        cleanup:
        if (leakedId) {
            deleteUser(leakedId)
        }
    }

    // ─── NEG_08: Invalid endpoint returns 404 ───────────────────────────────

    @SpecRef("RFC7644-3.12-R1")
    def "NEG_08: GET on non-existent endpoint returns 404"() {
        when:
        Response response = scimRequestQuiet()
            .get("/NonExistentEndpoint")

        then:
        response.statusCode() in [404, 501]
        assertScimError(response, response.statusCode())
    }

    // ─── NEG_09: Empty body POST returns 400 ────────────────────────────────

    @SpecRef("RFC7644-3.12-R1")
    def "NEG_09: POST /Users with empty body returns 400 with invalidSyntax scimType"() {
        // RFC 7644 §3.12 — empty body is not valid JSON → 400 invalidSyntax
        when:
        String leakedId = null
        Response response = scimRequestQuiet()
            .body("")
            .post("/Users")
        if (response?.statusCode() in [200, 201]) {
            try {
                leakedId = response.jsonPath().getString("id")
                if (leakedId) createdUserIds << leakedId
            } catch (Exception ignored) {}
        }

        then: "Strict 400 Bad Request without server error"
        assertScimErrorOptionalType(response, 400, "invalidSyntax")

        cleanup:
        if (leakedId) {
            deleteUser(leakedId)
        }
    }

    // ─── NEG_10: Random UUID URL returns 404 with SCIM Error body ───────────

    @SpecRef("RFC7644-3.12-R1")
    def "NEG_10: GET /{random_uuid} returns 404 with SCIM Error schema"() {
        // RFC 7644 §3.12 — Error responses MUST include a SCIM Error body
        given: "A completely random UUID path"
        String randomPath = UUID.randomUUID().toString()

        when: "GET /{randomPath}"
        Response response = scimRequestQuiet()
            .get("/${randomPath}")

        then: "Status is 404 (or 501) with SCIM Error schema"
        response.statusCode() in [404, 501]
        assertScimError(response, response.statusCode())
    }

    // ─── NEG_11: Filter Syntax Error (invalidFilter) ────────────────────────

    @RequiresCapability("filter")
    @SpecRef("RFC7644-3.4.2.2-R14")
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

    @RequiresCapability("patch")
    @SpecRef("RFC7643-2.2-R3")
    @SpecRef("RFC7643-3.1-R1")
    def "NEG_12: PATCH on read-only attribute id returns 400 with scimType mutability"() {
        // RFC 7643 §2.2, RFC 7644 §3.12 — readOnly attribute modification → 400 mutability
        when: "PATCH targeting read-only attribute id"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "id", value: "fake-new-id"]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${existingUserId}")

        then: "Server returns 400 Bad Request with scimType mutability (RFC 7643 §2.2, RFC 7644 §3.12)"
        response.statusCode() == 400
        assertScimErrorOptionalType(response, 400, "mutability")
    }

    // ─── NEG_13: PATCH with Invalid Path (invalidPath or noTarget) ──────────

    @RequiresCapability("patch")
    @SpecRef("RFC7644-3.5.2.3-R1")
    @SpecRef("RFC7644-3.12-R1")
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
        assertOptionalScimType(response, "invalidPath", "noTarget", "invalidFilter", "invalidValue")
    }

    // ─── NEG_14: Root JSON Array Payload on POST User ───────────────────────

    @SpecRef("RFC7644-3.12-R1")
    def "NEG_14: POST /Users with JSON array root payload returns 400 Bad Request"() {
        // RFC 7644 §3.12 — Root payload must be JSON object, not array
        when: "POST array payload instead of single resource object"
        String leakedId = null
        Response response = scimRequestQuiet()
            .body("[{\"userName\": \"array_user_${UUID.randomUUID().toString().substring(0, 8)}@test.com\"}]")
            .post("/Users")
        if (response?.statusCode() in [200, 201]) {
            try {
                leakedId = response.jsonPath().getString("id")
                if (leakedId) createdUserIds << leakedId
            } catch (Exception ignored) {}
        }

        then: "Rejected with 400 Bad Request and SCIM Error schema"
        response.statusCode() == 400
        assertScimError(response, 400)

        cleanup:
        if (leakedId) {
            deleteUser(leakedId)
        }
    }

    // ─── NEG_15: Primitive JSON Payload on POST User ────────────────────────

    @SpecRef("RFC7644-3.12-R1")
    def "NEG_15: POST /Users with primitive JSON payload returns 400 Bad Request"() {
        // RFC 7644 §3.12 — Primitive values (string, number) are invalid resource representations
        when: "POST primitive string"
        String leakedId = null
        Response response = scimRequestQuiet()
            .body('"just a plain string"')
            .post("/Users")
        if (response?.statusCode() in [200, 201]) {
            try {
                leakedId = response.jsonPath().getString("id")
                if (leakedId) createdUserIds << leakedId
            } catch (Exception ignored) {}
        }

        then: "Rejected with 400 Bad Request and SCIM Error schema"
        response.statusCode() == 400
        assertScimError(response, 400)

        cleanup:
        if (leakedId) {
            deleteUser(leakedId)
        }
    }

    // ─── NEG_16: SQL Injection & Special Characters in Filter ───────────────

    @RequiresCapability("filter")
    @SpecRef("RFC7644-3.4.2.2-R1")
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

    @SpecRef("RFC7644-3.4.1-R2")
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

    // ─── NEG_18: Group Missing Required displayName ─────────────────────────

    @SpecRef("RFC7643-4.2-R1")
    def "NEG_18: Group creation missing required displayName returns 400 Bad Request"() {
        // RFC 7643 §4.2 — Group displayName is required
        when: "POST group without displayName"
        String strayGroupId = null
        Response noName = scimRequest()
            .body(JsonOutput.toJson([schemas: [GROUP_SCHEMA]]))
            .post("/Groups")
        if (noName?.statusCode() in [200, 201]) {
            try { strayGroupId = noName.jsonPath().getString("id") } catch (Exception ignored) {}
        }

        then: "Missing displayName returns 400 Bad Request with invalidValue"
        assertScimErrorOptionalType(noName, 400, "invalidValue")

        cleanup:
        if (strayGroupId) {
            try { deleteGroup(strayGroupId) } catch (Exception ignored) {}
        }
    }

    // ─── NEG_19: Unknown Schema URN in POST Payload ─────────────────────────

    @SpecRef("RFC7643-3-R1")
    def "NEG_19: POST /Users with unknown schema URN returns 400 or alters/ignores (201)"() {
        // RFC 7643 §3 — Schema validation on resource creation
        given: "Payload with unknown schema URN"
        Map payload = [
            schemas : ["urn:ietf:params:scim:schemas:core:2.0:NonExistentSchema"],
            userName: "unknown_schema_${UUID.randomUUID().toString().substring(0, 6)}@test.com"
        ]

        when: "POST with invalid schema URN"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server rejects with 400 Bad Request or alters/ignores unknown schema per RFC 7644 §3.3 returning 201"
        assert response.statusCode() in [201, 400] : "Expected 400 Bad Request or 201 Created per RFC 7644 §3.3, got ${response.statusCode()}"
        if (response.statusCode() == 400) {
            assertScimError(response, 400)
        } else {
            assert response.jsonPath().getString("id") != null
            assert response.jsonPath().getString("userName") == payload.userName
            def returnedSchemas = response.jsonPath().getList("schemas")
            assert returnedSchemas != null && returnedSchemas.contains(USER_SCHEMA)
            assert !returnedSchemas.contains("urn:ietf:params:scim:schemas:core:2.0:NonExistentSchema")
        }

        cleanup:
        if (response?.statusCode() == 201) {
            String strayId = response.jsonPath().getString("id")
            if (strayId) deleteUser(strayId)
        }
    }

    // ─── NEG_20: tooMany error keyword on excessive results ─────────────────

    @RequiresCapability("filter")
    @SpecRef("RFC7644-3.4.2.1-R2")
    def "NEG_20: Query exceeding maxResults returns 400 with scimType tooMany or caps results"() {
        // RFC 7644 §3.4.2.1: "If the service provider is unable to return all results
        // ... the service provider SHALL reject the request by returning HTTP status code
        // 400 (Bad Request) with 'scimType' set to 'tooMany' (see Section 3.12)."
        when: "Query with filter=userName pr and count beyond normal limits, e.g. 100000"
        Response response = scimRequest()
            .queryParam("filter", "userName pr")
            .queryParam("count", 100000)
            .get("/Users")

        then: "Server returns 400 with scimType tooMany when threshold exceeded, or aborts if threshold not reached"
        if (response.statusCode() == 400) {
            assertScimError(response, 400, "tooMany")
        } else if (response.statusCode() == 200) {
            int total = response.jsonPath().getInt("totalResults") ?: 0
            int items = response.jsonPath().getInt("itemsPerPage") ?: 0
            throw new TestAbortedException("Server returned 200 OK without triggering 'tooMany' rejection (totalResults: ${total}, itemsPerPage: ${items}); server result threshold not exceeded")
        } else {
            assert response.statusCode() in [200, 400]
        }
    }

    // ─── NEG_21: Non-matching If-Match ETag returns 412 ──────────────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R2")
    def "NEG_21: PUT with non-matching If-Match ETag returns 412 Precondition Failed"() {
        // RFC 7644 §3.14 — Precondition Failed on ETag mismatch
        when: "PUT with non-matching ETag"
        Map putPayload = [
            schemas    : [USER_SCHEMA],
            userName   : existingUserName,
            displayName: "NonMatchingETagTest"
        ]
        Response response = scimRequestQuiet()
            .header("If-Match", 'W/"999999_nonmatching_etag"')
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${existingUserId}")

        then: "Server rejects with 412 Precondition Failed"
        assertScimError(response, 412)
    }

    // ─── NEG_22: Error detail attribute ─────────────────────────────────────

    @SpecRef("RFC7644-3.12-R2")
    def "NEG_22: SCIM Error responses include human-readable detail attribute"() {
        // RFC 7644 §3.12: "detail: A detailed human-readable message."
        when: "Trigger standard 404 error"
        Response response = scimRequestQuiet()
            .get("/Users/${UUID.randomUUID()}")

        then:
        assertScimError(response, 404)
        String detail = response.jsonPath().getString("detail")
        if (detail == null || detail.isBlank()) {
            throw new TestAbortedException("Server omits optional error detail attribute (RFC 7644 §3.12 MAY)")
        }
    }

    // ─── NEG_23: Filtering on sensitive attributes ──────────────────────────

    @RequiresCapability("filter")
    @SpecRef("RFC7644-7.5.2-R1")
    def "NEG_23: Filtering on sensitive attribute password returns 403 Forbidden or 400 invalidFilter"() {
        // RFC 7644 §7.5.2: "Servers that receive HTTP GET requests using filters that contain
        // sensitive or confidential personal information SHOULD respond with HTTP status code 403
        // to indicate that the operation is forbidden."
        // RFC 7644 §3.12 Table 9: invalidFilter: "The specified filter syntax was invalid
        // (does not comply with Figure 1), or the specified attribute and filter comparison
        // combination is not supported."
        when: "Filter on password attribute"
        Response response = scimRequest()
            .queryParam("filter", "password pr")
            .get("/Users")

        then: "Server rejects request with 403 Forbidden or 400 invalidFilter"
        assert response.statusCode() in [400, 403] : "Expected 400 or 403 for sensitive attribute filter, got ${response.statusCode()}"
        if (response.statusCode() == 403) {
            assertScimError(response, 403)
        } else {
            assertScimError(response, 400, "invalidFilter")
        }
    }

    // ─── NEG_24: Data type mismatch validation ──────────────────────────────

    @SpecRef("RFC7643-2.3-R1")
    def "NEG_24: Value data type mismatch (string for boolean active) returns 400 or alters/ignores (201)"() {
        // RFC 7643 §2.3.2: "The literal 'true' or 'false'. The JSON format is defined in Section 3 of [RFC7159]."
        // RFC 7644 §3.12 Table 9: invalidValue: "or the value specified was not compatible with the operation or attribute type (see Section 2.2 of [RFC7643])"
        given: "User payload with string 'not-a-boolean' for boolean active attribute"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: "datatype_mismatch_${UUID.randomUUID().toString().substring(0, 8)}@test.com",
            active  : "not-a-boolean-value"
        ]

        when: "POST user with mismatched data type"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server rejects with 400 Bad Request or alters/ignores per RFC 7644 §3.3 returning 201"
        assert response.statusCode() in [201, 400] : "Expected 400 Bad Request or 201 Created per RFC 7644 §3.3, got ${response.statusCode()}"
        if (response.statusCode() == 400) {
            assertScimErrorOptionalType(response, 400, "invalidValue", "invalidSyntax")
        } else {
            assert response.jsonPath().getString("id") != null
            assert response.jsonPath().getString("userName") == payload.userName
            Object retActive = response.jsonPath().get("active")
            assert retActive == null || retActive instanceof Boolean
        }

        cleanup:
        if (response.statusCode() == 201) {
            String uid = response.jsonPath().getString("id")
            if (uid) deleteUser(uid)
        }
    }

    // ─── NEG_25: Payload data type validation for complex attribute ─────────

    @SpecRef("RFC7643-2.3-R1")
    def "NEG_25: Submitting primitive string where complex object is required returns 400 or alters/ignores (201)"() {
        // RFC 7643 §2.3.8: "A singular or multi-valued attribute whose value is a composition of
        // one or more simple attributes. The JSON format is defined in Section 4 of [RFC7159]."
        // RFC 7644 §3.12 Table 9: invalidValue: "or the value specified was not compatible with the operation or attribute type (see Section 2.2 of [RFC7643])"
        given: "Payload with primitive string for complex 'name' attribute"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: "primitive_name_${UUID.randomUUID().toString().substring(0, 8)}@test.com",
            name    : "InvalidPrimitiveStringValue"
        ]

        when: "POST user with primitive string for complex attribute"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server rejects with 400 invalidValue or invalidSyntax or alters/ignores per RFC 7644 §3.3 returning 201"
        assert response.statusCode() in [201, 400] : "Expected HTTP 400 Bad Request or 201 Created per RFC 7644 §3.3, got ${response.statusCode()}"
        if (response.statusCode() == 400) {
            assertScimErrorOptionalType(response, 400, "invalidValue", "invalidSyntax")
        } else {
            assert response.jsonPath().getString("id") != null
            assert response.jsonPath().getString("userName") == payload.userName
            Object retName = response.jsonPath().get("name")
            assert retName == null || retName instanceof Map
        }

        cleanup:
        if (response.statusCode() == 201) {
            String uid = response.jsonPath().getString("id")
            if (uid) deleteUser(uid)
        }
    }

    // ─── NEG_26: Malformed filter syntax rejection ──────────────────────────

    @RequiresCapability("filter")
    @SpecRef("RFC7644-3.4.2.2-R14")
    def "NEG_26: Malformed filter syntax with unterminated value path returns 400 invalidFilter"() {
        // RFC 7644 §3.12 Table 9: invalidFilter: "The specified filter syntax was invalid (does not comply
        // with Figure 1), or the specified attribute and filter comparison combination is not supported."
        // RFC 7644 §3.4.2.2: "Providers MUST decline to filter results if the specified filter operation is not
        // recognized and return an HTTP 400 error with a 'scimType' error of 'invalidFilter' and an appropriate human-readable response"
        when: "Filter with unterminated value path syntax"
        Response response = scimRequestQuiet()
            .queryParam("filter", 'emails[value eq "test"')
            .get("/Users")

        then: "Server rejects with 400 invalidFilter"
        assert response.statusCode() == 400 : "Expected HTTP 400 Bad Request, but got ${response.statusCode()}"
        assertScimError(response, 400, "invalidFilter")
    }

    // ─── NEG_27: Malformed dateTime format validation ───────────────────────

    @RequiresCapability("filter")
    @SpecRef("RFC7644-3.4.2.2-R14")
    def "NEG_27: Submitting malformed dateTime format in filter returns 400 invalidFilter"() {
        // RFC 7644 §3.4.2.2 — Incompatible filter values reject with 400
        when: "Filter with malformed dateTime string"
        Response response = scimRequestQuiet()
            .queryParam("filter", 'meta.lastModified gt "not-an-iso-8601-date"')
            .get("/Users")

        then: "Server rejects with 400 invalidFilter or invalidValue"
        response.statusCode() == 400
        assertScimErrorOptionalType(response, 400, "invalidFilter", "invalidValue")
    }

    // ─── NEG_28: Comparison operator on Boolean attribute ───────────────────

    @RequiresCapability("filter")
    @SpecRef("RFC7644-3.4.2.2-R14")
    def "NEG_28: Comparison operator gt on Boolean attribute returns 400 invalidFilter"() {
        // RFC 7644 §3.4.2.2 Table 3 — gt/ge/lt/le on Boolean or Binary attributes SHALL return HTTP 400 invalidFilter
        when: "Filter with active gt true"
        Response response = scimRequestQuiet()
            .queryParam("filter", "active gt true")
            .get("/Users")

        then: "Server rejects with 400 invalidFilter per RFC 7644 §3.4.2.2 Table 3"
        assert response.statusCode() == 400 : "Expected HTTP 400 invalidFilter for gt operator on Boolean attribute (RFC 7644 §3.4.2.2 Table 3), got ${response.statusCode()}"
        assertScimError(response, 400, "invalidFilter")
    }

    // ─── NEG_29: Unsupported SCIM protocol version ──────────────────────────

    @SpecRef("RFC7644-3.12-R4")
    def "NEG_29: Unsupported SCIM protocol version returns 400 with scimType invalidVers"() {
        // RFC 7644 §3.12: "invalidVers: The specified SCIM protocol version is not supported"
        when: "Request resource under unsupported protocol version /v1"
        URI base = URI.create(BASE_URL)
        String rawPath = (base.path ?: "").replaceAll('/+$', '')
        String v1Path = rawPath.contains("/v2") ? rawPath.replaceFirst('/v2(?=/|$)', '/v1') + "/Users" : (rawPath + "/v1/Users")
        Response response = scimRequestQuiet()
            .baseUri("${base.scheme}://${base.rawAuthority}")
            .basePath("")
            .get(v1Path)

        then: "Server rejects with 400 invalidVers (or aborts if security/router path rejects with 403 or 404)"
        if (response.statusCode() in [403, 404]) {
            throw new TestAbortedException("Server returned HTTP ${response.statusCode()} for /v1/Users at security/router layer before SCIM invalidVers evaluation")
        }
        response.statusCode() == 400
        assertScimError(response, 400, "invalidVers")
    }
}

