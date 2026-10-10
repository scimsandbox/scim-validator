package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Area 8 — Security, Headers and Concurrency
 *
 * Validates authentication enforcement, mutability rules, content-type and content negotiation,
 * ETag caching and optimistic concurrency control per RFC 7643 §2.2, §3.1, §4.1 and
 * RFC 7644 §2, §3.8, §3.12, §3.13, §3.14.
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

    @SpecRef("RFC7644-2-R1")
    def "SEC_01: Request without Authorization header returns 401"() {
        // RFC 7644 §2 — Authentication and Authorization
        when:
        Response response = scimRequestAnonymous()
            .get("/Users")

        then:
        response.statusCode() == 401

        and: "WWW-Authenticate header MUST be present on 401 (RFC 7235 §4.1)"
        response.header("WWW-Authenticate") != null
        response.header("WWW-Authenticate").contains("Bearer")
    }

    // ─── SEC_02: Invalid token returns 401 ──────────────────────────────────

    @SpecRef("RFC7644-2-R1")
    def "SEC_02: Request with invalid Bearer token returns 401"() {
        when:
        Response response = scimRequestAnonymous()
            .header("Authorization", "Bearer INVALID_TOKEN_12345")
            .get("/Users")

        then:
        response.statusCode() == 401

        and: "WWW-Authenticate header MUST be present on 401 (RFC 7235 §4.1)"
        response.header("WWW-Authenticate") != null
        response.header("WWW-Authenticate").contains("Bearer")
    }

    // ─── SEC_03: Read-only attributes cannot be modified ────────────────────

    @SpecRef("RFC7643-2.2-R1")
    @SpecRef("RFC7644-2-R2")
    @SpecRef("RFC7643-2.2-R3")
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
            recordDeviation("RFC7643-2.2-R1", "RFC 7643 §2.2", "Server returned 403 Forbidden for readOnly id change instead of 400 mutability or ignoring")
            ScimOutput.println "DEVIATION: Server returned 403 Forbidden for readOnly id change instead of 400 mutability or ignoring"
        }

        and: "If 200, the id should NOT have changed"
        if (response.statusCode() == 200) {
            assert response.jsonPath().getString("id") == testUserId :
                "readOnly id must not change (RFC 7643 §2.2)"
        }
    }

    // ─── SEC_04: Content-Type application/scim+json is accepted ─────────────

    @SpecRef("RFC7644-3.8-R3")
    def "SEC_04: Server accepts application/scim+json content type"() {
        // RFC 7644 §3.8 — Servers MUST support Accept application/scim+json
        when:
        Response response = scimRequest()
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        response.contentType().contains("application/scim+json")
    }

    // ─── SEC_05: ETag support ───────────────────────────────────────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7643-3.1-R4")
    def "SEC_05: Server returns ETag header on resource retrieval"() {
        // RFC 7643 §3.1, RFC 7644 §3.14 — ETag support
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

    @SpecRef("RFC7644-3.2-R1")
    def "SEC_06: Unsupported HTTP method on singleton returns appropriate error"() {
        // RFC 7644 §3.2 — Endpoint action table semantics
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

    // ─── SEC_07: Attributes with returned=never are not in GET responses ───

    @SpecRef("RFC7643-2.2-R5")
    @SpecRef("RFC7643-4.1.1-R2")
    @SpecRef("RFC7643-2.2-R4")
    def "SEC_07: Attributes with returned=never (password) are not in GET responses"() {
        // RFC 7643 §2.2, §4.1 — returned=never attributes like password are never returned
        when:
        Response response = scimRequest()
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        !response.body().asString().contains('"password"')
    }

    // ─── SEC_08: If-None-Match with matching ETag returns 304 ───────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R3")
    def "SEC_08: GET with matching If-None-Match returns 304 Not Modified"() {
        // RFC 7644 §3.14 — If-None-Match conditional retrieval
        given: "Retrieve the current ETag"
        Response initial = scimRequest()
            .get("/Users/${testUserId}")
        String etag = initial.header("ETag")

        when: "GET with matching If-None-Match"
        Response response = scimRequest()
            .header("If-None-Match", etag)
            .get("/Users/${testUserId}")

        then: "Server returns 304 Not Modified"
        etag != null
        response.statusCode() == 304
    }

    // ─── SEC_09: If-None-Match wildcard returns 304 ─────────────────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R3")
    def "SEC_09: GET with If-None-Match wildcard returns 304"() {
        // RFC 7644 §3.14 — If-None-Match: * on existing resource returns 304
        when:
        Response response = scimRequest()
            .header("If-None-Match", "*")
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 304
    }

    // ─── SEC_10: If-None-Match with non-matching ETag returns 200 ───────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R3")
    def "SEC_10: GET with non-matching If-None-Match returns 200"() {
        // RFC 7644 §3.14 — Non-matching ETag proceeds normally
        when:
        Response response = scimRequest()
            .header("If-None-Match", 'W/"non-matching-etag-value"')
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getString("id") == testUserId
    }

    // ─── SEC_11: Content-Location header on GET ─────────────────────────────

    @SpecRef("RFC7643-3.1-R3")
    def "SEC_11: GET single resource includes Content-Location header"() {
        // RFC 7643 §3.1 — meta.location matches Content-Location / Location
        when:
        Response response = scimRequest()
            .get("/Users/${testUserId}")

        then:
        response.statusCode() == 200
        String contentLocation = response.header("Content-Location") ?: response.header("Location")
        if (contentLocation == null) {
            recordDeviation("RFC7643-3.1-R3", "RFC 7643 §3.1", "Response missing Content-Location and Location headers")
            assert response.jsonPath().getString("meta.location")?.contains("/Users/${testUserId}")
        } else {
            assert contentLocation.contains("/Users/${testUserId}")
        }
    }

    // ─── SEC_12: Content-Location header on POST ────────────────────────────

    @SpecRef("RFC7643-3.1-R3")
    def "SEC_12: POST new resource response includes Content-Location header"() {
        // RFC 7643 §3.1 — meta.location matches Content-Location / Location
        when:
        Response response = createUser()

        then:
        response.statusCode() == 201
        String contentLocation = response.header("Content-Location") ?: response.header("Location")
        String id = response.jsonPath().getString("id")
        if (contentLocation == null) {
            recordDeviation("RFC7643-3.1-R3", "RFC 7643 §3.1", "POST response missing Content-Location and Location headers")
            assert response.jsonPath().getString("meta.location")?.contains("/Users/${id}")
        } else {
            assert contentLocation.contains("/Users/${id}")
        }

        cleanup:
        if (id) deleteUser(id)
    }

    // ─── SEC_13: Content-Location header on PUT ─────────────────────────────

    @SpecRef("RFC7643-3.1-R3")
    def "SEC_13: PUT replace resource response includes Content-Location header"() {
        // RFC 7643 §3.1, RFC 7644 §3.5.1 — Replacing with PUT
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
        String contentLocation = response.header("Content-Location") ?: response.header("Location")
        if (contentLocation == null) {
            recordDeviation("RFC7643-3.1-R3", "RFC 7643 §3.1", "PUT response missing Content-Location and Location headers")
            assert response.jsonPath().getString("meta.location")?.contains("/Users/${testUserId}")
        } else {
            assert contentLocation.contains("/Users/${testUserId}")
        }
    }

    // ─── SEC_14: Content-Location header on PATCH ───────────────────────────

    @SpecRef("RFC7643-3.1-R3")
    def "SEC_14: PATCH resource response includes Content-Location header"() {
        // RFC 7643 §3.1, RFC 7644 §3.5.2 — Modifying with PATCH
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
        String contentLocation = response.header("Content-Location") ?: response.header("Location")
        if (contentLocation == null) {
            recordDeviation("RFC7643-3.1-R3", "RFC 7643 §3.1", "PATCH response missing Content-Location and Location headers")
            assert response.jsonPath().getString("meta.location")?.contains("/Users/${testUserId}")
        } else {
            assert contentLocation.contains("/Users/${testUserId}")
        }
    }

    // ─── SEC_15: Malformed and Unsupported Authorization Headers ────────────

    @SpecRef("RFC7644-2-R1")
    def "SEC_15: Malformed or unsupported Authorization header returns 401 with WWW-Authenticate"() {
        // RFC 7644 §2 — Authentication and Authorization
        when: "Send a request with Basic auth instead of Bearer"
        Response basicResp = scimRequestAnonymous()
            .header("Authorization", "Basic dXNlcjpwYXNz")
            .get("/Users")

        then: "Server returns 401 with WWW-Authenticate header"
        basicResp.statusCode() == 401
        basicResp.header("WWW-Authenticate") != null

        when: "Send a request with a completely malformed Authorization header"
        Response malformedResp = scimRequestAnonymous()
            .header("Authorization", "NotAScheme AtAll")
            .get("/Users")

        then: "Server returns 401 with WWW-Authenticate header"
        malformedResp.statusCode() == 401
        malformedResp.header("WWW-Authenticate") != null
    }

    // ─── SEC_16: Modifying Read-Only Metadata Attributes via PATCH ──────────

    @SpecRef("RFC7643-2.2-R1")
    @SpecRef("RFC7643-3.1-R1")
    @SpecRef("RFC7643-3.1-R5")
    def "SEC_16: PATCH attempting to modify read-only metadata attributes is rejected or ignored"() {
        // RFC 7643 §2.2, §3.1 — meta attributes have mutability: readOnly
        when: "Attempt to PATCH meta.created"
        Map patchCreated = buildPatchOp([
            [op: "replace", path: "meta.created", value: "1970-01-01T00:00:00Z"]
        ])
        Response respCreated = scimRequest()
            .body(JsonOutput.toJson(patchCreated))
            .patch("/Users/${testUserId}")

        then: "Server rejects with 400 (mutability/invalidValue) or ignores change"
        respCreated.statusCode() in [200, 400]
        if (respCreated.statusCode() == 200) {
            assert respCreated.jsonPath().getString("meta.created") != "1970-01-01T00:00:00Z" :
                "meta.created MUST NOT be modified (RFC 7643 §3.1)"
        }

        when: "Attempt to PATCH meta.lastModified"
        Map patchMod = buildPatchOp([
            [op: "replace", path: "meta.lastModified", value: "1970-01-01T00:00:00Z"]
        ])
        Response respMod = scimRequest()
            .body(JsonOutput.toJson(patchMod))
            .patch("/Users/${testUserId}")

        then: "Server rejects with 400 (mutability/invalidValue) or ignores change"
        respMod.statusCode() in [200, 400]
        if (respMod.statusCode() == 200) {
            assert respMod.jsonPath().getString("meta.lastModified") != "1970-01-01T00:00:00Z" :
                "meta.lastModified MUST NOT be explicitly set to past date (RFC 7643 §3.1)"
        }
    }

    // ─── SEC_17: Modifying Read-Only Metadata Attributes via PUT ────────────

    @SpecRef("RFC7643-3.1-R1")
    @SpecRef("RFC7643-3.1-R5")
    def "SEC_17: PUT replacement with modified meta.created preserves original timestamp"() {
        // RFC 7643 §3.1 — meta.created is managed by the server and MUST NOT be changed
        given: "Retrieve existing user representation"
        Response existing = scimRequest().get("/Users/${testUserId}")
        assert existing.statusCode() == 200
        String originalCreated = existing.jsonPath().getString("meta.created")
        String etag = existing.header("ETag")

        Map userBody = existing.jsonPath().getMap("")
        userBody.meta = userBody.meta ?: [:]
        userBody.meta.created = "1970-01-01T00:00:00Z"

        when: "PUT with forged meta.created"
        def reqSpec = scimRequest().body(JsonOutput.toJson(userBody))
        if (etag) reqSpec = reqSpec.header("If-Match", etag)
        Response putResp = reqSpec.put("/Users/${testUserId}")

        then: "Server either rejects with 400 or ignores the forged meta.created"
        putResp.statusCode() in [200, 400]
        if (putResp.statusCode() == 200) {
            String returnedCreated = putResp.jsonPath().getString("meta.created")
            assert returnedCreated != "1970-01-01T00:00:00Z" :
                "Server MUST NOT accept client-supplied meta.created on PUT (RFC 7643 §3.1)"
            if (originalCreated != null) {
                assert returnedCreated == originalCreated :
                    "meta.created should remain unchanged after PUT"
            }
        }
    }

    // ─── SEC_18: Standard application/json Content-Type Interoperability ────

    @SpecRef("RFC7644-3.8-R3")
    def "SEC_18: Server accepts standard application/json Content-Type and Accept headers"() {
        // RFC 7644 §3.8 — SCIM servers SHOULD support application/json
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

    @SpecRef("RFC7644-3.8-R3")
    def "SEC_19: Request with unsupported Accept header returns 406 Not Acceptable or logs deviation"() {
        // RFC 7644 §3.8 — Unsupported Accept header SHOULD return HTTP 406
        when: "Request user with unsupported Accept: text/xml"
        Response response = scimRequest()
            .accept("text/xml")
            .get("/Users/${testUserId}")

        then: "Server returns 406 Not Acceptable (or logs deviation if 200)"
        response.statusCode() in [200, 406]

        if (response.statusCode() == 200) {
            recordDeviation("RFC7644-3.8-R3", "RFC 7644 §3.8", "Server returned 200 instead of 406 Not Acceptable for unsupported Accept: text/xml")
            ScimOutput.println "DEVIATION: Server returned 200 instead of 406 Not Acceptable for unsupported Accept: text/xml (RFC 7644 §3.8)"
        }
    }

    // ─── SEC_20: Negative Content Negotiation (Unsupported Content-Type) ────

    @SpecRef("RFC7644-3.8-R1")
    def "SEC_20: Request with unsupported Content-Type returns 415 or 400 Bad Request"() {
        // RFC 7644 §3.8 — Unsupported Content-Type SHOULD return HTTP 415
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
            recordDeviation("RFC7644-3.8-R1", "RFC 7644 §3.8", "Server returned 400 instead of 415 Unsupported Media Type for unsupported Content-Type")
            ScimOutput.println "DEVIATION: Server returned 400 instead of 415 Unsupported Media Type for unsupported Content-Type (RFC 7644 §3.8)"
        }
    }

    // ─── SEC_21: Optimistic Concurrency on PUT with Outdated If-Match ────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R2")
    def "SEC_21: PUT with outdated If-Match ETag returns 412 Precondition Failed"() {
        // RFC 7644 §3.13, §3.14 — Precondition Failed on ETag mismatch
        given: "Retrieve existing user representation"
        Response existing = scimRequest().get("/Users/${testUserId}")
        assert existing.statusCode() == 200
        Map userBody = existing.jsonPath().getMap("")
        userBody.title = "Stale Update"

        when: "PUT with known-stale / synthetic ETag"
        Response response = scimRequestQuiet()
            .header("If-Match", 'W/"synthetic-stale-etag-99999"')
            .body(JsonOutput.toJson(userBody))
            .put("/Users/${testUserId}")

        then: "Server rejects with 412 Precondition Failed"
        response.statusCode() == 412
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "412"
    }

    // ─── SEC_22: Optimistic Concurrency on PATCH with Outdated If-Match ──────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R2")
    def "SEC_22: PATCH with outdated If-Match ETag returns 412 Precondition Failed"() {
        // RFC 7644 §3.13, §3.14 — Precondition Failed on ETag mismatch
        given:
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "displayName", value: "Stale Patch Name"]
        ])

        when: "PATCH with known-stale / synthetic ETag"
        Response response = scimRequestQuiet()
            .header("If-Match", 'W/"synthetic-stale-etag-88888"')
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Server rejects with 412 Precondition Failed"
        response.statusCode() == 412
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "412"
    }

    // ─── SEC_23: Concurrent Update Collision Detection ──────────────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R2")
    def "SEC_23: Concurrent update collision detects stale ETag and rejects second write with 412"() {
        // RFC 7644 §3.14 — Optimistic Concurrency Collision
        given: "Client A and Client B both read the user concurrently"
        Response clientARead = scimRequest().get("/Users/${testUserId}")
        assert clientARead.statusCode() == 200
        String etagA = clientARead.header("ETag")

        Response clientBRead = scimRequest().get("/Users/${testUserId}")
        assert clientBRead.statusCode() == 200
        String etagB = clientBRead.header("ETag")

        assert etagA != null && etagB != null : "ETag required for OCC collision test"
        assert etagA == etagB : "Both clients read same state so ETags must match"

        when: "Client A updates the user successfully"
        Map patchA = buildPatchOp([
            [op: "replace", path: "displayName", value: "Updated by Client A"]
        ])
        Response respA = scimRequest()
            .header("If-Match", etagA)
            .body(JsonOutput.toJson(patchA))
            .patch("/Users/${testUserId}")

        then: "Client A's write succeeds with 200 OK"
        respA.statusCode() == 200

        when: "Client B attempts to update with the now-stale ETag"
        Map patchB = buildPatchOp([
            [op: "replace", path: "displayName", value: "Updated by Client B (should collide)"]
        ])
        Response respB = scimRequestQuiet()
            .header("If-Match", etagB)
            .body(JsonOutput.toJson(patchB))
            .patch("/Users/${testUserId}")

        then: "Client B's write is rejected with 412 Precondition Failed"
        respB.statusCode() == 412
        respB.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        respB.jsonPath().getString("status") == "412"
    }

    // ─── SEC_24: Wildcard If-Match Header on Existing Resource ──────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R1")
    def "SEC_24: Wildcard If-Match * on existing resource"() {
        // RFC 7644 §3.14, RFC 7232 §3.1 — If-Match: * succeeds if resource exists
        given:
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "displayName", value: "Wildcard-Matched-Name"]
        ])

        when: "PATCH with If-Match: *"
        Response response = scimRequest()
            .header("If-Match", "*")
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Server returns 200 OK"
        response.statusCode() == 200
        response.jsonPath().getString("displayName") == "Wildcard-Matched-Name"
    }

    // ─── SEC_25: Password Leak Prevention Across Endpoints ──────────────────

    @SpecRef("RFC7643-2.2-R5")
    @SpecRef("RFC7643-4.1.1-R2")
    def "SEC_25: password attribute is never returned in POST, PUT, or List responses"() {
        // RFC 7643 §2.2, §4.1 — returned: never
        given: "Create user with explicit password"
        String secretPass = "SuperSecret#2026!P@ss"
        String uniqueName = "sec25_pwleak_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: uniqueName,
            password: secretPass
        ]

        when: "POST create user with password"
        Response postRes = scimRequest()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "POST response does NOT contain password"
        postRes.statusCode() == 201
        !postRes.body().asString().contains(secretPass)
        !postRes.body().asString().contains('"password"')
        String createdId = postRes.jsonPath().getString("id")

        when: "PUT user representation with password"
        Map putPayload = [
            schemas : [USER_SCHEMA],
            userName: uniqueName,
            password: "NewSecret#2026!P@ss"
        ]
        Response putRes = scimRequest()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${createdId}")

        then: "PUT response does NOT contain password"
        putRes.statusCode() == 200
        !putRes.body().asString().contains("NewSecret#2026!P@ss")
        !putRes.body().asString().contains('"password"')

        when: "List users filtered to this user"
        Response listRes = scimRequest()
            .queryParam("filter", "userName eq \"${uniqueName}\"")
            .get("/Users")

        then: "List response does NOT contain password"
        listRes.statusCode() == 200
        !listRes.body().asString().contains('"password"')

        cleanup:
        if (createdId) deleteUser(createdId)
    }

    // ─── SEC_26: Unsupported HTTP Methods on Resource Endpoints ─────────────

    @SpecRef("RFC7644-3.2-R1")
    def "SEC_26: Unsupported HTTP methods on resource instance and collection return 405 Method Not Allowed"() {
        // RFC 7644 §3.2 — HTTP method rejection
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

    // ─── SEC_27: 401 and 403 return JSON error body ─────────────────────────

    @SpecRef("RFC7644-3.12-R3")
    def "SEC_27: HTTP 401 and 403 error responses return JSON error body"() {
        // RFC 7644 §3.12: "For HTTP status codes 401 and 403, the response body MUST
        // be formatted as JSON using the 'urn:ietf:params:scim:api:messages:2.0:Error' schema."
        when: "Unauthenticated request"
        Response response = scimRequestAnonymous()
            .get("/Users")

        then:
        response.statusCode() == 401
        if (response.contentType()?.contains("json") && response.body().asString()?.trim()) {
            response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
            response.jsonPath().getString("status") == "401"
        } else {
            recordDeviation("RFC7644-3.12-R3", "RFC 7644 §3.12", "HTTP 401 response omits SCIM Error JSON body")
            ScimOutput.println "DEVIATION: HTTP 401 response omits SCIM Error JSON body"
        }
    }

    // ─── SEC_28: URL versioning segment /v2 ─────────────────────────────────

    @SpecRef("RFC7644-3.13-R1")
    def "SEC_28: URL version segment /v2 is supported or negotiated"() {
        // RFC 7644 §3.13: "SCIM protocol version 2.0 endpoints MAY be prefixed with '/v2'."
        when: "Query /v2/Users or verify standard unversioned endpoint"
        Response response = scimRequest()
            .get("/v2/Users")

        then: "Server returns 200 (if /v2 supported) or 404 (if not prefixed)"
        response.statusCode() in [200, 404]
        if (response.statusCode() == 200) {
            response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)
        }
    }

    // ─── SEC_29: UTF-8 encoding and default format negotiation ──────────────

    @SpecRef("RFC7644-3.8-R1")
    @SpecRef("RFC7644-3.8-R2")
    def "SEC_29: Requests with international UTF-8 characters round-trip and default format is application/scim+json"() {
        // RFC 7644 §3.8: "SCIM data formats are specified in JSON format. SCIM data MUST be
        // UTF-8 encoded... When no format is specified, the format defaults to 'application/scim+json'."
        given: "User with unicode multi-lingual characters"
        String unicodeName = "Müller-Nörregaard 測試 ${UUID.randomUUID().toString().substring(0, 6)}"
        Map userPayload = [
            schemas    : [USER_SCHEMA],
            userName   : "utf8_${UUID.randomUUID().toString().substring(0, 8)}@test.com",
            displayName: unicodeName
        ]

        when: "POST without explicit Accept header"
        Response response = scimRequest()
            .body(JsonOutput.toJson(userPayload))
            .post("/Users")

        then:
        response.statusCode() == 201
        response.contentType().contains(SCIM_CONTENT_TYPE)
        response.jsonPath().getString("displayName") == unicodeName
        String uid = response.jsonPath().getString("id")

        cleanup:
        if (uid) deleteUser(uid)
    }
}
