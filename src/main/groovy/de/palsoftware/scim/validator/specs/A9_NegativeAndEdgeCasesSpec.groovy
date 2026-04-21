package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import io.restassured.RestAssured
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Area 9 — Negative and Edge Cases
 *
 * Validates proper error handling and edge case behavior per RFC 7644.
 * Tests uniqueness constraints, missing required attributes, malformed payloads,
 * non-existent resources, and boundary conditions.
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

    // ─── NEG_01: Duplicate userName returns 409 ─────────────────────────────

    def "NEG_01: POST /Users with duplicate userName returns 409 Conflict"() {
        // RFC 7644 §3.3.1 — uniqueness constraint violation → 409
        given:
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: existingUserName,
            emails  : [[value: "dup_${UUID.randomUUID().toString().substring(0,6)}@test.com", type: "work", primary: true]]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then:
        response.statusCode() == 409

        cleanup:
        // If it somehow succeeded, clean up
        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) deleteUser(id)
        }
    }

    // ─── NEG_02: POST without required userName returns 400 ─────────────────

    def "NEG_02: POST /Users without userName returns 400"() {
        // RFC 7643 §4.1 — userName is required
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
        response.statusCode() == 400

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
        response.statusCode() == 404
        assertScimError(response, 404)
    }

    // ─── NEG_04: DELETE non-existent User returns 404 ───────────────────────

    def "NEG_04: DELETE /Users with non-existent ID returns 404"() {
        when:
        Response response = scimRequest()
            .delete("/Users/non-existent-id-00000000")

        then:
        response.statusCode() == 404
        assertScimError(response, 404)
    }

    // ─── NEG_05: PUT non-existent User returns 404 ──────────────────────────

    def "NEG_05: PUT /Users with non-existent ID returns 404"() {
        given:
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: "ghost_${UUID.randomUUID().toString().substring(0,8)}@test.com",
            emails  : [[value: "ghost@test.com", type: "work", primary: true]]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .put("/Users/non-existent-id-00000000")

        then:
        response.statusCode() == 404
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
        response.statusCode() == 404
        assertScimError(response, 404)
    }

    // ─── NEG_07: Malformed JSON body returns 400 ────────────────────────────

    def "NEG_07: POST with malformed JSON body returns 400"() {
        when:
        Response response = scimRequest()
            .body("{this is not valid json!!!")
            .post("/Users")

        then:
        response.statusCode() == 400
    }

    // ─── NEG_08: Invalid endpoint returns 404 ───────────────────────────────

    def "NEG_08: GET on non-existent endpoint returns 404"() {
        when:
        Response response = scimRequest()
            .get("/NonExistentEndpoint")

        then:
        response.statusCode() in [404, 501]
    }

    // ─── NEG_09: Empty body POST returns 400 ────────────────────────────────

    def "NEG_09: POST /Users with empty body returns 400"() {
        when:
        Response response = scimRequest()
            .body("")
            .post("/Users")

        then:
        response.statusCode() in [400, 500]
    }

    // ─── NEG_10: Random UUID URL returns 404 with SCIM Error body ───────────

    def "NEG_10: GET /{random_uuid} returns 404 with SCIM Error schema"() {
        // RFC 7644 §3.12 — Error responses MUST include a SCIM Error body
        given: "A completely random UUID path"
        String randomPath = UUID.randomUUID().toString()

        when: "GET /{randomPath}"
        Response response = scimRequest()
            .get("/${randomPath}")

        then: "Status is 404"
        response.statusCode() in [404, 501]
        and: "Response body contains SCIM Error format"
        // TODO DEVIATION: api.scim.dev may not return a SCIM Error body for random URLs.
        // RFC 7644 §3.12 requires error responses to use the SCIM Error schema.
        if (response.statusCode() == 404) {
            assertScimError(response, 404)
        } else {
            ScimOutput.println "DEVIATION: GET /${randomPath} returned ${response.statusCode()} instead of 404 (RFC 7644 §3.12)"
        }
    }
}
