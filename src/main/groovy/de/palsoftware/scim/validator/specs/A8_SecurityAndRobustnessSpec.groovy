package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Area 8 — Security, Headers and Concurrency
 *
 * Validates authentication enforcement, mutability rules, content-type and content negotiation,
 * ETag caching and optimistic concurrency control per RFC 7643 §2.2, §3.1, §4.1 and
 * RFC 7644 §2, §3.1, §3.13, §3.14.
 */
class A8_SecurityAndRobustnessSpec extends ScimBaseSpec {

    @Shared String testUserId

    def setupSpec() {
        loadServiceProviderConfig()

        // Create a user to test against
        def response = createUser()
        assert response.statusCode() == 201 : "Setup failed: ${response.body().asString()}"
        testUserId = response.jsonPath().getString("id")
    }

    def cleanupSpec() {
        if (testUserId) {
            deleteUser(testUserId)
        }
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

    def "SEC_03: PATCH on read-only attribute id returns error or ignores change"() {
        // RFC 7643 §2.2, §7 — readOnly attributes MUST NOT be modified
        when:
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "id", value: "fake-id-value"]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Server should reject modification of read-only 'id' or ignore"
        response.statusCode() in [200, 400, 403]

        and: "If 400, error schema should reflect mutability constraint"
        if (response.statusCode() == 400) {
            assert response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
            assertScimType(response, "mutability", "invalidValue")
        } else if (response.statusCode() == 403) {
            ScimOutput.println "DEVIATION: Server returned 403 Forbidden for readOnly id change instead of 400 mutability or ignoring"
        }

        and: "If 200, the id should NOT have changed"
        if (response.statusCode() == 200) {
            assert response.jsonPath().getString("id") == testUserId :
                "readOnly id must not change (RFC 7643 §2.2)"
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

        and: "Check meta.version and ETag header for consistency"
        String version = response.jsonPath().getString("meta.version")
        String etagHeader = response.header("ETag")
        version != null || etagHeader != null

        and: "When both present, ETag header should match or reflect meta.version"
        if (etagHeader != null && version != null) {
            String normalizedEtag = etagHeader.replace("W/\"", "").replace("\"", "")
            String normalizedVersion = version.replace("W/\"", "").replace("\"", "")
            assert normalizedEtag.contains(normalizedVersion) || normalizedVersion.contains(normalizedEtag) :
                "ETag header '${etagHeader}' and meta.version '${version}' must agree (RFC 7644 §3.14)"
        }
    }

    // ─── SEC_06: Unsupported HTTP method returns 405 ────────────────────────

    def "SEC_06: Unsupported HTTP method on singleton returns appropriate error"() {
        // RFC 7644 — Servers should reject unsupported methods
        when: "Send PATCH to /ServiceProviderConfig (read-only singleton)"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "patch.supported", value: true]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/ServiceProviderConfig")

        then: "Server should return 405 or 501 or 400"
        response.statusCode() in [400, 403, 404, 405, 501]
    }

    // ─── SEC_07: returned=never attributes not in responses ─────────────────

    def "SEC_07: Attributes with returned=never (password) are not in GET responses"() {
        // RFC 7643 §2.2 — Attribute returned characteristic: never
        // RFC 7643 §4.1 — password has returned=never
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

        cleanup:
        if (id) deleteUser(id)
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

    // ─── SEC_15: Malformed and Unsupported Authorization Headers ────────────

    def "SEC_15: Malformed or unsupported Authorization header returns 401 with WWW-Authenticate"() {
        // RFC 7644 §2, RFC 7235 §3.1 — Authorization header validation
        when: "Request with empty Bearer token"
        Response emptyBearer = scimRequestAnonymous()
            .header("Authorization", "Bearer ")
            .get("/Users")

        then: "Empty Bearer token returns 401"
        emptyBearer.statusCode() == 401
        emptyBearer.header("WWW-Authenticate") != null

        when: "Request with unsupported Basic auth scheme"
        Response basicAuth = scimRequestAnonymous()
            .header("Authorization", "Basic dXNlcjpwYXNzd29yZA==")
            .get("/Users")

        then: "Unsupported auth scheme returns 401"
        basicAuth.statusCode() == 401
        basicAuth.header("WWW-Authenticate") != null
    }

    // ─── SEC_16: PATCH on Read-Only Metadata Attributes ─────────────────────

    def "SEC_16: PATCH attempting to modify read-only metadata attributes is rejected or ignored"() {
        // RFC 7643 §2.2, §3.1 — meta sub-attributes are readOnly
        when: "PATCH targeting meta.created"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "meta.created", value: "1999-01-01T00:00:00Z"]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Server rejects with 400 (scimType: mutability) or ignores change"
        response.statusCode() in [200, 400]

        and: "If 400, verify SCIM error schema"
        if (response.statusCode() == 400) {
            assert response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
            assertScimType(response, "mutability", "invalidValue", "invalidPath")
        }

        and: "If 200, meta.created was not changed to 1999"
        if (response.statusCode() == 200) {
            !response.jsonPath().getString("meta.created").startsWith("1999")
        }
    }

    // ─── SEC_17: PUT Preserves Read-Only meta.created Timestamp ──────────────

    def "SEC_17: PUT replacement with modified meta.created preserves original timestamp"() {
        // RFC 7643 §2.2 — readOnly attributes cannot be modified via PUT
        given: "Retrieve current user"
        Response existing = scimRequest().get("/Users/${testUserId}")
        assert existing.statusCode() == 200
        String originalCreated = existing.jsonPath().getString("meta.created")
        String etag = existing.header("ETag")
        Map userBody = existing.jsonPath().getMap("")

        and: "Attempt to forge meta.created in the PUT payload"
        if (userBody.containsKey("meta")) {
            ((Map) userBody.get("meta")).put("created", "1970-01-01T00:00:00Z")
        }

        when: "Execute PUT replacement"
        def req = scimRequest().body(JsonOutput.toJson(userBody))
        if (etag) req = req.header("If-Match", etag)
        Response response = req.put("/Users/${testUserId}")

        then: "PUT succeeds or rejects"
        response.statusCode() in [200, 400]

        and: "If 200, original meta.created is preserved and was not overwritten"
        if (response.statusCode() == 200) {
            assert response.jsonPath().getString("meta.created") == originalCreated :
                "meta.created is readOnly and must survive a PUT that tries to overwrite it (RFC 7643 §2.2)"
        }
    }

    // ─── SEC_18: Standard application/json Interoperability ─────────────────

    def "SEC_18: Server accepts standard application/json Content-Type and Accept headers"() {
        // RFC 7644 §3.1 — SCIM servers SHOULD support application/json
        when: "GET user with Accept: application/json"
        Response getRes = scimRequest()
            .accept("application/json")
            .get("/Users/${testUserId}")

        then: "Status is 200 OK and body is valid JSON"
        getRes.statusCode() == 200
        getRes.jsonPath().getString("id") == testUserId

        when: "Create user with Content-Type: application/json and Accept: application/json"
        String testName = "json_interop_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: testName
        ]
        Response postRes = scimRequest()
            .contentType("application/json")
            .accept("application/json")
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "User created with 201 Created"
        postRes.statusCode() == 201
        String createdId = postRes.jsonPath().getString("id")
        createdId != null

        cleanup:
        if (createdId) deleteUser(createdId)
    }

    // ─── SEC_19: Negative Content Negotiation (Unsupported Accept) ──────────

    def "SEC_19: Request with unsupported Accept header returns 406 Not Acceptable or logs deviation"() {
        // RFC 7644 §3.1 — Unsupported Accept header MUST return HTTP 406
        when: "Request user with unsupported Accept: text/xml"
        Response response = scimRequest()
            .accept("text/xml")
            .get("/Users/${testUserId}")

        then: "Server returns 406 Not Acceptable (or logs deviation if 200)"
        response.statusCode() in [200, 406]

        if (response.statusCode() == 200) {
            ScimOutput.println "DEVIATION: Server returned 200 instead of 406 Not Acceptable for unsupported Accept: text/xml (RFC 7644 §3.1)"
        }
    }

    // ─── SEC_20: Negative Content Negotiation (Unsupported Content-Type) ────

    def "SEC_20: Request with unsupported Content-Type returns 415 or 400 Bad Request"() {
        // RFC 7644 §3.1 — Unsupported Content-Type MUST return HTTP 415
        when: "POST with unsupported Content-Type: application/xml"
        Response response = scimRequestQuiet()
            .contentType("application/xml")
            .body("<User><userName>xml_user@test.com</userName></User>")
            .post("/Users")

        then: "Server returns 415 Unsupported Media Type or 400 Bad Request"
        response.statusCode() in [400, 415]

        and: "Server response includes SCIM Error schema"
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)

        if (response.statusCode() == 400) {
            ScimOutput.println "DEVIATION: Server returned 400 instead of 415 Unsupported Media Type for unsupported Content-Type (RFC 7644 §3.1)"
        }
    }

    // ─── SEC_21: Optimistic Concurrency on PUT with Outdated If-Match ────────

    def "SEC_21: PUT with outdated If-Match ETag returns 412 Precondition Failed"() {
        // RFC 7644 §3.13, §3.14 — Precondition Failed on ETag mismatch
        given: "Retrieve existing user representation"
        Response getRes = scimRequest().get("/Users/${testUserId}")
        assert getRes.statusCode() == 200
        Map userBody = getRes.jsonPath().getMap("")

        when: "Execute PUT with intentionally mismatched / outdated ETag"
        Response response = scimRequest()
            .header("If-Match", "W/\"999999999\"")
            .body(JsonOutput.toJson(userBody))
            .put("/Users/${testUserId}")

        then: "Server returns 412 Precondition Failed with SCIM Error schema"
        response.statusCode() == 412
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "412"
    }

    // ─── SEC_22: Optimistic Concurrency on PATCH with Outdated If-Match ──────

    def "SEC_22: PATCH with outdated If-Match ETag returns 412 Precondition Failed"() {
        // RFC 7644 §3.13, §3.14 — Precondition Failed on ETag mismatch
        given: "A valid PATCH payload"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "displayName", value: "SEC22-Mismatch"]
        ])

        when: "Execute PATCH with mismatched ETag"
        Response response = scimRequest()
            .header("If-Match", "W/\"999999999\"")
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Server returns 412 Precondition Failed with SCIM Error schema"
        response.statusCode() == 412
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "412"
    }

    // ─── SEC_23: Concurrent Update Race Simulation ──────────────────────────

    def "SEC_23: Concurrent update collision detects stale ETag and rejects second write with 412"() {
        // RFC 7644 §3.13 — Concurrency handling.
        //
        // Unlike USR_16/PAT_20, the If-Match match path is held strict here: optimistic
        // concurrency IS the subject of this test, and RFC 7644 §3.13 requires a server to
        // honour If-Match carrying the version it issued — even though §3.14 recommends weak
        // ETags and RFC 7232 §3.1 asks for strong comparison in the general HTTP case.
        given: "Create an isolated user for concurrent write testing"
        Response created = createUser()
        assert created.statusCode() == 201
        String raceUserId = created.jsonPath().getString("id")

        and: "Client A and Client B both read the user at initial version V0"
        Response readA = scimRequest().get("/Users/${raceUserId}")
        Response readB = scimRequest().get("/Users/${raceUserId}")
        String etagV0 = readA.header("ETag")
        assert etagV0 != null : "Server must provide ETag"
        assert readB.header("ETag") == etagV0

        when: "Client B successfully commits an update, advancing version to V1"
        Map patchB = buildPatchOp([[op: "replace", path: "displayName", value: "Client-B-Wins"]])
        Response commitB = scimRequest()
            .header("If-Match", etagV0)
            .body(JsonOutput.toJson(patchB))
            .patch("/Users/${raceUserId}")

        then: "Client B's update succeeds"
        commitB.statusCode() == 200
        String etagV1 = commitB.header("ETag")
        etagV1 != null
        etagV1 != etagV0

        when: "Client A attempts to commit its update using stale etagV0"
        Map patchA = buildPatchOp([[op: "replace", path: "displayName", value: "Client-A-Stale"]])
        Response commitA = scimRequest()
            .header("If-Match", etagV0)
            .body(JsonOutput.toJson(patchA))
            .patch("/Users/${raceUserId}")

        then: "Client A's update is rejected with 412 Precondition Failed"
        commitA.statusCode() == 412
        commitA.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        commitA.jsonPath().getString("status") == "412"

        when: "Client A re-fetches the latest representation and re-attempts"
        Response refreshA = scimRequest().get("/Users/${raceUserId}")
        assert refreshA.statusCode() == 200
        assert refreshA.jsonPath().getString("displayName") == "Client-B-Wins"

        Map patchARetry = buildPatchOp([[op: "replace", path: "displayName", value: "Client-A-Resolved"]])
        Response retryA = scimRequest()
            .header("If-Match", refreshA.header("ETag"))
            .body(JsonOutput.toJson(patchARetry))
            .patch("/Users/${raceUserId}")

        then: "Retry with current ETag succeeds"
        retryA.statusCode() == 200
        retryA.jsonPath().getString("displayName") == "Client-A-Resolved"

        cleanup:
        if (raceUserId) deleteUser(raceUserId)
    }

    // ─── SEC_24: Wildcard If-Match: * on Existing Resource ──────────────────

    def "SEC_24: Wildcard If-Match * on existing resource"() {
        // RFC 7232 §3.1, RFC 7644 §3.13 — If-Match: * matches any existing state
        given: "Retrieve existing user ETag"
        Response before = scimRequest().get("/Users/${testUserId}")
        assert before.statusCode() == 200
        String etagBefore = before.header("ETag")

        when: "PATCH with If-Match: *"
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "displayName", value: "Wildcard-Updated-${UUID.randomUUID().toString().substring(0, 4)}"]
        ])
        Response response = scimRequest()
            .header("If-Match", "*")
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Status is 200 OK (or 412 if server does not support wildcard on updates)"
        response.statusCode() in [200, 412]

        if (response.statusCode() == 412) {
            ScimOutput.println "DEVIATION: Server does not support wildcard '*' for If-Match header on PATCH/PUT (RFC 7232 §3.1, RFC 7644 §3.13)"
        } else {
            String etagAfter = response.header("ETag")
            if (etagBefore != null && etagAfter != null) {
                assert etagAfter != etagBefore
            }
        }
    }

    // ─── SEC_25: returned=never (password) Excluded Across All Operations ────

    def "SEC_25: password attribute is never returned in POST, PUT, or List responses"() {
        // RFC 7643 §2.2, §4.1 — password has returned=never
        given: "Create a user with explicit password"
        String testUser = "sec25_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map createPayload = [
            schemas : [USER_SCHEMA],
            userName: testUser,
            password: "TopSecretPassword123!"
        ]

        when: "POST create user with password"
        Response postRes = scimRequest()
            .body(JsonOutput.toJson(createPayload))
            .post("/Users")

        then: "User created and password is NOT in response body"
        postRes.statusCode() == 201
        String createdId = postRes.jsonPath().getString("id")
        !postRes.body().asString().contains('"password"')

        when: "PUT replace user with new password"
        Map putPayload = postRes.jsonPath().getMap("")
        putPayload["password"] = "AnotherSecretPassword456!"
        Response putRes = scimRequest()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${createdId}")

        then: "User replaced and password is NOT in response body"
        putRes.statusCode() == 200
        !putRes.body().asString().contains('"password"')

        when: "Query user via List endpoint"
        Response listRes = scimRequest()
            .queryParam("filter", "userName eq \"${testUser}\"")
            .get("/Users")

        then: "List response does NOT contain password"
        listRes.statusCode() == 200
        !listRes.body().asString().contains('"password"')

        cleanup:
        if (createdId) deleteUser(createdId)
    }

    // ─── SEC_26: Unsupported HTTP Methods on Resource Endpoints ─────────────

    def "SEC_26: Unsupported HTTP methods on resource instance and collection return 405 Method Not Allowed"() {
        // RFC 7644 §3.12 — HTTP method rejection
        //
        // NOTE: this test deliberately never issues DELETE (or any other destructive verb)
        // against a collection endpoint. The suite runs against live third-party targets,
        // and a server that honoured "DELETE /Users" would wipe the tenant before the
        // assertion below could report it. POST and PUT on a collection/instance are the
        // undefined-but-harmless verbs, so method rejection is probed with those.
        given: "A uniquely named payload so an unexpectedly accepted write can be cleaned up"
        String strayUserName = "sec26_stray_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when: "POST to existing resource instance endpoint (invalid)"
        Response postInstance = scimRequestQuiet()
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: strayUserName]))
            .post("/Users/${testUserId}")

        then: "Rejected with 405 (or 400/404)"
        postInstance.statusCode() in [400, 404, 405]

        when: "PUT on entire collection endpoint (invalid — PUT targets an instance)"
        Response putCollection = scimRequestQuiet()
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: strayUserName]))
            .put("/Users")

        then: "Rejected with 405 (or 400/404/501)"
        putCollection.statusCode() in [400, 404, 405, 501]

        cleanup: "Remove any resource a non-compliant server created from the rejected writes"
        [postInstance, putCollection].each { Response r ->
            if (r?.statusCode() in [200, 201]) {
                String strayId = r.jsonPath().getString("id")
                if (strayId) deleteUser(strayId)
            }
        }
    }
}

