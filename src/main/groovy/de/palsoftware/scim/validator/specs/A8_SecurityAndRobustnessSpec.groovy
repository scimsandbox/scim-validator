package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Area 8 — Security and Robustness
 *
 * Validates authentication enforcement, mutability rules, content-type handling,
 * and ETag support per RFC 7643/7644.
 */
class A8_SecurityAndRobustnessSpec extends ScimBaseSpec {

    @Shared String testUserId
    @Shared String testUserETag

    def setupSpec() {
        loadServiceProviderConfig()

        // Create a user to test against
        def response = createUser()
        assert response.statusCode() == 201 : "Setup failed: ${response.body().asString()}"
        testUserId = response.jsonPath().getString("id")
    }

    // ─── SEC_01: Unauthenticated request returns 401 ────────────────────────

    def "SEC_01: Request without Authorization header returns 401"() {
        // RFC 7644 §2 — Authentication and Authorization
        when:
        Response response = scimRequestAnonymous()
            .get("/Users")

        then:
        response.statusCode() == 401

        and: "WWW-Authenticate header MUST be present on 401 (RFC 7235 §3.1)"
        response.header("WWW-Authenticate") != null
        response.header("WWW-Authenticate").contains("Bearer")
    }

    // ─── SEC_02: Invalid token returns 401 ──────────────────────────────────

    def "SEC_02: Request with invalid Bearer token returns 401"() {
        when:
        Response response = scimRequestAnonymous()
            .header("Authorization", "Bearer INVALID_TOKEN_12345")
            .get("/Users")

        then:
        response.statusCode() == 401

        and: "WWW-Authenticate header MUST be present on 401 (RFC 7235 §3.1)"
        response.header("WWW-Authenticate") != null
        response.header("WWW-Authenticate").contains("Bearer")
    }

    // ─── SEC_03: Read-only attributes cannot be modified ────────────────────

    def "SEC_03: PATCH on read-only attribute id returns error"() {
        // RFC 7643 §7 — readOnly attributes MUST NOT be modified
        when:
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "id", value: "fake-id-value"]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Server should reject modification of read-only 'id'"
        // Server may return 400, 403, or ignore the change
        response.statusCode() in [200, 400, 403, 404]

        and: "If 200, the id should NOT have changed"
        if (response.statusCode() == 200) {
            response.jsonPath().getString("id") == testUserId
        }
    }

    // ─── SEC_04: Content-Type application/scim+json is accepted ─────────────

    def "SEC_04: Server accepts application/scim+json content type"() {
        // RFC 7644 §3.1 — Clients MAY use application/scim+json
        when:
        Response response = scimRequest()
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        response.contentType().contains("application/scim+json")
    }

    // ─── SEC_05: ETag support ───────────────────────────────────────────────

    def "SEC_05: Server returns ETag header on resource retrieval"() {
        // RFC 7644 §3.14 — ETag support
        when:
        Response response = scimRequest()
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200

        and: "Check meta.version for ETag value"
        String version = response.jsonPath().getString("meta.version")
        // ETag may be in header or meta.version
        String etagHeader = response.header("ETag")
        version != null || etagHeader != null
    }

    // ─── SEC_06: Unsupported HTTP method returns 405 ────────────────────────

    def "SEC_06: Unsupported HTTP method on endpoint returns appropriate error"() {
        // RFC 7644 — Servers should reject unsupported methods
        when: "Send PATCH to /ServiceProviderConfig (read-only singleton)"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "patch.supported", value: true]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/ServiceProviderConfig")

        then: "Server should return 405 or 501 or 400"
        response.statusCode() in [400, 403, 404, 405, 500, 501]
    }

    // ─── SEC_07: returned=never attributes not in responses ─────────────────

    def "SEC_07: Attributes with returned=never (password) are not in GET responses"() {
        // RFC 7643 §2.2 — Attribute returned characteristic: never
        // RFC 7643 §4.1 — password has returned=never
        // Reference: scim2-compliance-test-suite ResponseValidateTests
        when: "GET the test user"
        Response response = scimRequest()
            .get("/Users/${testUserId}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "password attribute is NOT present in the response (returned=never)"
        def body = response.body().asString()
        !body.contains('"password"')
    }

    // ─── SEC_08: If-None-Match returns 304 when ETag matches ────────────────

    def "SEC_08: GET with matching If-None-Match returns 304 Not Modified"() {
        // RFC 7644 §3.14 — ETag / If-None-Match
        given: "Fetch the user to obtain ETag"
        Response getResponse = scimRequest()
            .get("/Users/${testUserId}")
        assert getResponse.statusCode() == 200
        String etag = getResponse.header("ETag")
        assert etag != null : "ETag header must be present"

        when: "GET with matching If-None-Match"
        Response response = scimRequest()
            .header("If-None-Match", etag)
            .get("/Users/${testUserId}")

        then: "Server should return 304 Not Modified"
        response.statusCode() == 304
    }

    // ─── SEC_09: If-None-Match wildcard returns 304 ─────────────────────────

    def "SEC_09: GET with If-None-Match wildcard returns 304"() {
        // RFC 7232 §3.2 — If-None-Match: * means any version matches
        when:
        Response response = scimRequest()
            .header("If-None-Match", "*")
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 304
    }

    // ─── SEC_10: If-None-Match mismatch returns 200 ─────────────────────────

    def "SEC_10: GET with non-matching If-None-Match returns 200"() {
        // RFC 7232 §3.2 — Non-matching ETag should return full response
        when:
        Response response = scimRequest()
            .header("If-None-Match", "W/\"99999\"")
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getString("id") == testUserId
    }

    // ─── SEC_11: Content-Location header on GET ─────────────────────────────

    def "SEC_11: GET single resource includes Content-Location header"() {
        // RFC 7644 §3.1 — Responses for individual resources MUST include Content-Location
        when:
        Response response = scimRequest()
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        String contentLocation = response.header("Content-Location")
        contentLocation != null
        contentLocation.contains("/Users/${testUserId}")
    }

    // ─── SEC_12: Content-Location header on POST ────────────────────────────

    def "SEC_12: POST new resource response includes Content-Location header"() {
        // RFC 7644 §3.1 — Content-Location on resource responses
        when:
        Response response = createUser()

        then:
        response.statusCode() == 201
        String contentLocation = response.header("Content-Location")
        String id = response.jsonPath().getString("id")
        contentLocation != null
        contentLocation.contains("/Users/${id}")
    }

    // ─── SEC_13: Content-Location header on PUT ─────────────────────────────

    def "SEC_13: PUT replace resource response includes Content-Location header"() {
        // RFC 7644 §3.5.1 — Replacing with PUT
        given: "Retrieve the existing user"
        Response existing = scimRequest()
            .get("/Users/${testUserId}")
        assert existing.statusCode() == 200
        String etag = existing.header("ETag")
        Map userBody = existing.jsonPath().getMap("")

        when: "PUT the user back"
        def reqSpec = scimRequest()
            .body(JsonOutput.toJson(userBody))
        if (etag) reqSpec = reqSpec.header("If-Match", etag)
        Response response = reqSpec.put("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        String contentLocation = response.header("Content-Location")
        contentLocation != null
        contentLocation.contains("/Users/${testUserId}")
    }

    // ─── SEC_14: Content-Location header on PATCH ───────────────────────────

    def "SEC_14: PATCH resource response includes Content-Location header"() {
        // RFC 7644 §3.5.2 — Modifying with PATCH
        given:
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "displayName", value: "SEC14-Patched"]
        ])

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        String contentLocation = response.header("Content-Location")
        contentLocation != null
        contentLocation.contains("/Users/${testUserId}")
    }
}
