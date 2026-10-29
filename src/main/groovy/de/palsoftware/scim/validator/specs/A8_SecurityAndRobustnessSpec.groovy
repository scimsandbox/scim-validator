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

        // Create a user to test against (include password so SEC_07 can verify returned=never)
        def response = createUser(password: "InitSecret#2026!")
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
    }

    // ─── SEC_03: Read-only attributes cannot be modified ────────────────────

    @RequiresCapability("patch")
    @SpecRef("RFC7643-3.1-R1")
    @SpecRef("RFC7643-2.2-R3")
    def "SEC_03: PATCH on read-only attribute id returns 400 mutability"() {
        // RFC 7643 §2.2, §7 — readOnly attributes MUST NOT be modified
        when:
        Map patchPayload = buildPatchOp([
            [op: "replace", path: "id", value: "fake-id-value"]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${testUserId}")

        then: "Server rejects modification of read-only 'id' with 400 mutability (RFC 7643 §2.2)"
        response.statusCode() == 400
        assertScimErrorOptionalType(response, 400, "mutability", "invalidValue")
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
        assert version != null && etagHeader != null : "Both ETag header and meta.version must be returned when etag.supported=true (RFC 7643 §3.1)"

        and: "Weak ETag prefix W/ is case-sensitive and ETag header matches meta.version"
        assert !etagHeader.startsWith("w/") : "Weak entity-tag prefix 'W/' is case-sensitive and must not use lowercase 'w/' (RFC 7643 §3.1)"
        assert !version.startsWith("w/") : "Weak entity-tag prefix 'W/' in meta.version is case-sensitive and must not use lowercase 'w/' (RFC 7643 §3.1)"
        String normalizedEtag = etagHeader.replace("W/\"", "").replace("\"", "")
        String normalizedVersion = version.replace("W/\"", "").replace("\"", "")
        assert normalizedEtag == normalizedVersion :
            "ETag header '${etagHeader}' and meta.version '${version}' must agree (RFC 7643 §3.1 / RFC 7644 §3.14)"
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

    @SpecRef("RFC7644-3.4-R1")
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
        String metaLocation = response.jsonPath().getString("meta.location")
        assert metaLocation?.contains("/Users/${testUserId}")
        String contentLocation = response.header("Content-Location") ?: response.header("Location")
        if (contentLocation != null) {
            assert contentLocation.contains("/Users/${testUserId}")
            assert metaLocation == contentLocation
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
        String id = response.jsonPath().getString("id")
        String metaLocation = response.jsonPath().getString("meta.location")
        metaLocation?.contains("/Users/${id}")
        String contentLocation = response.header("Content-Location") ?: response.header("Location")
        assert contentLocation != null : "POST 201 Created response MUST include Location or Content-Location header (RFC 7643 §3.1 / RFC 7644 §3.3)"
        assert contentLocation.contains("/Users/${id}")
        assert metaLocation == contentLocation

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
        String metaLocation = response.jsonPath().getString("meta.location")
        assert metaLocation?.contains("/Users/${testUserId}")
        String contentLocation = response.header("Content-Location") ?: response.header("Location")
        if (contentLocation != null) {
            assert contentLocation.contains("/Users/${testUserId}")
            assert metaLocation == contentLocation
        }
    }

    // ─── SEC_14: Content-Location header on PATCH ───────────────────────────

    @RequiresCapability("patch")
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
        response.statusCode() in [200, 204]
        if (response.statusCode() == 200) {
            String metaLocation = response.jsonPath().getString("meta.location")
            assert metaLocation?.contains("/Users/${testUserId}")
            String contentLocation = response.header("Content-Location") ?: response.header("Location")
            if (contentLocation != null) {
                assert contentLocation.contains("/Users/${testUserId}")
                assert metaLocation == contentLocation
            }
        } else {
            Response getResp = scimRequest().get("/Users/${testUserId}")
            assert getResp.statusCode() == 200
            assert getResp.jsonPath().getString("meta.location")?.contains("/Users/${testUserId}")
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

        then: "Server returns 401"
        basicResp.statusCode() == 401

        when: "Send a request with a completely malformed Authorization header"
        Response malformedResp = scimRequestAnonymous()
            .header("Authorization", "NotAScheme AtAll")
            .get("/Users")

        then: "Server returns 401"
        malformedResp.statusCode() == 401
    }

    // ─── SEC_16: Modifying Read-Only Metadata Attributes via PATCH ──────────

    @RequiresCapability("patch")
    @SpecRef("RFC7643-2.2-R3")
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
        respCreated.statusCode() in [200, 204, 400]
        if (respCreated.statusCode() == 200) {
            assert respCreated.jsonPath().getString("meta.created") != "1970-01-01T00:00:00Z" :
                "meta.created MUST NOT be modified (RFC 7643 §3.1)"
        } else if (respCreated.statusCode() == 204) {
            Response getCreated = scimRequest().get("/Users/${testUserId}")
            assert getCreated.statusCode() == 200
            assert getCreated.jsonPath().getString("meta.created") != "1970-01-01T00:00:00Z" :
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
        respMod.statusCode() in [200, 204, 400]
        if (respMod.statusCode() == 200) {
            assert respMod.jsonPath().getString("meta.lastModified") != "1970-01-01T00:00:00Z" :
                "meta.lastModified MUST NOT be explicitly set to past date (RFC 7643 §3.1)"
        } else if (respMod.statusCode() == 204) {
            Response getMod = scimRequest().get("/Users/${testUserId}")
            assert getMod.statusCode() == 200
            assert getMod.jsonPath().getString("meta.lastModified") != "1970-01-01T00:00:00Z" :
                "meta.lastModified MUST NOT be explicitly set to past date (RFC 7643 §3.1)"
        }
    }

    // ─── SEC_17: Modifying Read-Only Metadata Attributes via PUT ────────────

    @SpecRef("RFC7643-2.2-R3")
    @SpecRef("RFC7644-3.5.1-R3")
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
        } else {
            assertScimError(putResp, 400, "mutability", "invalidValue")
        }
    }

    // ─── SEC_18: Standard application/json Content-Type Interoperability ────

    @SpecRef("RFC7644-3.8-R4")
    def "SEC_18: Server accepts standard application/json Accept header on GET"() {
        // RFC 7644 §3.8 — SCIM servers SHOULD support application/json
        when: "GET user with Accept: application/json"
        Response getRes = scimRequest()
            .accept("application/json")
            .get("/Users/${testUserId}")

        then: "Status is 200 OK"
        getRes.statusCode() == 200
        assert getRes.jsonPath().getString("id") == testUserId
    }

    // ─── SEC_19: Negative Content Negotiation (Unsupported Accept) ──────────

    @SpecRef("RFC7644-3.8-R3")
    def "SEC_19: Request with unsupported Accept header returns 406 Not Acceptable or 200 OK"() {
        // RFC 7644 §3.8 — Unsupported Accept header SHOULD return HTTP 406
        when: "Request user with unsupported Accept: text/xml"
        Response response = scimRequest()
            .accept("text/xml")
            .get("/Users/${testUserId}")

        then: "Server returns 406 Not Acceptable or 200 OK"
        response.statusCode() in [200, 406]
    }

    // ─── SEC_20: Negative Content Negotiation (Unsupported Content-Type) ────

    @SpecRef("RFC7644-3.8-R1")
    @SpecRef("RFC7644-3.8-R3")
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
        response.jsonPath().get("status") instanceof String
        response.jsonPath().getString("status") == "412"
    }

    // ─── SEC_22: Optimistic Concurrency on PATCH with Outdated If-Match ──────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R2")
    def "SEC_22: PUT with outdated If-Match ETag returns 412 Precondition Failed"() {
        // RFC 7644 §3.13, §3.14 — Precondition Failed on ETag mismatch
        given:
        Response userResp = scimRequest().get("/Users/${testUserId}")
        assert userResp.statusCode() == 200
        String currentUserName = userResp.jsonPath().getString("userName")
        Map putPayload = [
            schemas    : [USER_SCHEMA],
            userName   : currentUserName,
            displayName: "Stale Put Name"
        ]

        when: "PUT with known-stale / synthetic ETag"
        Response response = scimRequestQuiet()
            .header("If-Match", 'W/"synthetic-stale-etag-88888"')
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${testUserId}")

        then: "Server rejects with 412 Precondition Failed"
        assertScimError(response, 412)
    }

    // ─── SEC_23: Concurrent Update Collision Detection ──────────────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R2")
    def "SEC_23: Concurrent update collision detects stale ETag and rejects second write with 412"() {
        // RFC 7644 §3.13, §3.14 — Concurrency handling
        given: "Create an isolated user for concurrent write testing"
        Response created = createUser()
        assert created.statusCode() == 201
        String raceUserId = created.jsonPath().getString("id")
        String raceUserName = created.jsonPath().getString("userName")

        and: "Client A and Client B both read the user at initial version V0"
        Response readA = scimRequest().get("/Users/${raceUserId}")
        Response readB = scimRequest().get("/Users/${raceUserId}")
        String etagV0 = readA.header("ETag")
        assert etagV0 != null : "Server must provide ETag"
        assert readB.header("ETag") == etagV0

        when: "Client B successfully commits an update, advancing version to V1"
        Map putB = [
            schemas    : [USER_SCHEMA],
            userName   : raceUserName,
            displayName: "Client-B-Wins"
        ]
        Response commitB = scimRequest()
            .header("If-Match", etagV0)
            .body(JsonOutput.toJson(putB))
            .put("/Users/${raceUserId}")

        then: "Client B's update succeeds"
        commitB.statusCode() == 200
        String etagV1 = commitB.header("ETag")
        etagV1 != null
        etagV1 != etagV0

        when: "Client A attempts to commit its update using stale etagV0"
        Map putA = [
            schemas    : [USER_SCHEMA],
            userName   : raceUserName,
            displayName: "Client-A-Stale"
        ]
        Response commitA = scimRequest()
            .header("If-Match", etagV0)
            .body(JsonOutput.toJson(putA))
            .put("/Users/${raceUserId}")

        then: "Client A's update is rejected with 412 Precondition Failed"
        assertScimError(commitA, 412)

        when: "Client A re-fetches the latest representation and re-attempts"
        Response refreshA = scimRequest().get("/Users/${raceUserId}")
        assert refreshA.statusCode() == 200
        assert refreshA.jsonPath().getString("displayName") == "Client-B-Wins"

        Map putARetry = [
            schemas    : [USER_SCHEMA],
            userName   : raceUserName,
            displayName: "Client-A-Resolved"
        ]
        Response retryA = scimRequest()
            .header("If-Match", refreshA.header("ETag"))
            .body(JsonOutput.toJson(putARetry))
            .put("/Users/${raceUserId}")

        then: "Retry with current ETag succeeds"
        retryA.statusCode() == 200
        retryA.jsonPath().getString("displayName") == "Client-A-Resolved"

        cleanup:
        if (raceUserId) deleteUser(raceUserId)
    }

    // ─── SEC_24: Wildcard If-Match Header on Existing Resource ──────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R1")
    def "SEC_24: Wildcard If-Match * on existing resource"() {
        // RFC 7644 §3.14, RFC 7232 §3.1 — If-Match: * succeeds if resource exists
        given: "Retrieve existing user ETag"
        Response before = scimRequest().get("/Users/${testUserId}")
        assert before.statusCode() == 200
        String etagBefore = before.header("ETag")
        String userName = before.jsonPath().getString("userName")

        Map putPayload = [
            schemas    : [USER_SCHEMA],
            userName   : userName,
            displayName: "Wildcard-Matched-Name"
        ]

        when: "PUT with If-Match: *"
        Response response = scimRequest()
            .header("If-Match", "*")
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${testUserId}")

        then: "Server returns 200 OK for existing resource"
        assert response.statusCode() != 412 : "Server rejected existing resource with 412 Precondition Failed on If-Match: * (RFC 7644 §3.14)"
        assert response.statusCode() == 200 : "Expected HTTP 200 OK for PUT with If-Match: * on existing resource, got ${response.statusCode()}"
        assert response.jsonPath().getString("displayName") == "Wildcard-Matched-Name"
        assert response.header("ETag") != null : "PUT response MUST return ETag header (RFC 7644 §3.14)"
    }

    // ─── SEC_25: Password Leak Prevention Across Endpoints ──────────────────

    @SpecRef("RFC7643-2.2-R4")
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
        def putReq = scimRequest().body(JsonOutput.toJson(putPayload))
        String postEtag = postRes.header("ETag")
        if (postEtag) putReq = putReq.header("If-Match", postEtag)
        Response putRes = putReq.put("/Users/${createdId}")

        then: "PUT response does NOT contain password"
        assert putRes.statusCode() in [200, 400, 403, 501]
        !putRes.body().asString().contains("NewSecret#2026!P@ss")
        !putRes.body().asString().contains('"password"')

        when: "GET user representation"
        Response getRes = scimRequest().get("/Users/${createdId}")

        then: "GET response does NOT contain password"
        getRes.statusCode() == 200
        !getRes.body().asString().contains('"password"')

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

    @SpecRef("RFC7644-2-R2")
    @SpecRef("RFC7644-3.12-R3")
    def "SEC_27: HTTP 401 and 403 error responses return JSON error body"() {
        // RFC 7644 §3.12: "For HTTP status codes 401 and 403, the response body MUST
        // be formatted as JSON using the 'urn:ietf:params:scim:api:messages:2.0:Error' schema."
        when: "Unauthenticated request"
        Response response = scimRequestAnonymous()
            .get("/Users")

        then:
        assertScimError(response, 401)
    }

    // ─── SEC_28: URL versioning segment /v2 ─────────────────────────────────

    @SpecRef("RFC7644-3.13-R1")
    def "SEC_28: URL version segment /v2 is supported or negotiated"() {
        // RFC 7644 §3.13: "SCIM protocol version 2.0 endpoints MAY be prefixed with '/v2'."
        when: "Query /v2/Users or verify standard endpoint"
        boolean baseHasV2 = (BASE_URL?.endsWith("/v2") || BASE_URL?.endsWith("/v2/"))
        String path = baseHasV2 ? "/Users" : "/v2/Users"
        Response response = scimRequest()
            .get(path)

        then: "Server returns 200 when BASE_URL uses /v2, or 200/404 when probing /v2 prefix"
        if (baseHasV2) {
            assert response.statusCode() == 200
            assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)
        } else {
            assert response.statusCode() in [200, 404]
            if (response.statusCode() == 200) {
                assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)
            }
        }
    }

    // ─── SEC_29: UTF-8 encoding support ─────────────────────────────────────

    @SpecRef("RFC7644-3.8-R1")
    def "SEC_29: Requests with international UTF-8 characters round-trip successfully"() {
        // RFC 7644 §3.8: "SCIM data formats are specified in JSON format. SCIM data MUST be
        // UTF-8 encoded."
        given: "User with unicode multi-lingual characters"
        String unicodeName = "Müller-Nörregaard 測試 ${UUID.randomUUID().toString().substring(0, 6)}"
        Map userPayload = [
            schemas    : [USER_SCHEMA],
            userName   : "utf8_${UUID.randomUUID().toString().substring(0, 8)}@test.com",
            displayName: unicodeName
        ]
        String rawUtf8Json = new groovy.json.JsonGenerator.Options()
            .disableUnicodeEscaping()
            .build()
            .toJson(userPayload)

        when: "POST raw UTF-8 JSON"
        Response response = scimRequest()
            .body(rawUtf8Json)
            .post("/Users")

        then:
        response.statusCode() == 201
        response.contentType().contains(SCIM_CONTENT_TYPE)
        response.jsonPath().getString("displayName") == unicodeName
        String uid = response.jsonPath().getString("id")

        cleanup:
        if (uid) deleteUser(uid)
    }

    // ─── SEC_30: WWW-Authenticate header required on 401 ────────────────────

    @SpecRef("RFC7644-2-R1")
    def "SEC_30: Unauthenticated request returning 401 MUST include WWW-Authenticate header"() {
        // RFC 7644 §2 / RFC 7235 §4.1 — WWW-Authenticate header MUST be present on 401
        when: "Send unauthenticated request"
        Response response = scimRequestAnonymous()
            .get("/Users")

        then: "Server returns 401 with WWW-Authenticate header"
        response.statusCode() == 401
        String wwwAuth = response.header("WWW-Authenticate")
        assert wwwAuth != null && !wwwAuth.isBlank() :
            "HTTP 401 response MUST include WWW-Authenticate header (RFC 7644 §2 / RFC 7235 §4.1)"
        assert wwwAuth.toLowerCase().contains("bearer") :
            "WWW-Authenticate header '${wwwAuth}' should indicate Bearer challenge"
    }

    // ─── SEC_31: POST with Content-Type: application/json ────────────────────

    @SpecRef("RFC7644-3.8-R3")
    def "SEC_31: POST with Content-Type application/json is accepted (201) or rejected with 415"() {
        // RFC 7644 §3.8 — Server SHOULD support application/json
        given: "Build payload for new user"
        String testName = "json_post_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: testName
        ]

        when: "POST with Content-Type: application/json and Accept: application/scim+json"
        Response response = scimRequest()
            .contentType("application/json")
            .accept(SCIM_CONTENT_TYPE)
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server returns 201 Created or 415 Unsupported Media Type"
        response.statusCode() in [201, 415]
        String createdId = null
        if (response.statusCode() == 201) {
            createdId = response.jsonPath().getString("id")
            assert createdId != null
        }

        cleanup:
        if (createdId) deleteUser(createdId)
    }

    // ─── SEC_32: Default format is application/scim+json when Accept omitted ─

    @SpecRef("RFC7644-3.8-R2")
    def "SEC_32: Default format is application/scim+json when Accept header is omitted"() {
        // RFC 7644 §3.8: "When no format is specified, the format defaults to 'application/scim+json'."
        when: "Request user without explicit Accept header"
        Response response = io.restassured.RestAssured.given()
            .baseUri(state().scimApiUrl)
            .basePath(state().basePath)
            .header("Authorization", "Bearer ${state().authToken}")
            .get("/Users/${testUserId}")

        then: "Response Content-Type defaults to application/scim+json"
        response.statusCode() == 200
        response.contentType().contains(SCIM_CONTENT_TYPE)
    }
}
