package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 3 — User CRUD Lifecycle
 *
 * Full Create → Read → Update → Delete lifecycle for Users.
 * Uses DataFaker for unique userName/email per run to avoid uniqueness conflicts.
 *
 * RFC 7644 §3.3 (POST), §3.4.1 (GET), §3.5.1 (PUT), §3.6 (DELETE)
 */
@Stepwise
class A3_UserCrudSpec extends ScimBaseSpec {

    @Shared String minimalUserId
    @Shared String minimalUserName
    @Shared String fullUserId
    @Shared String putTestUserId
    @Shared String putImmutTestUserId
    @Shared String deleteTestUserId

    // ─── USR_01: POST minimal user ──────────────────────────────────────────

    @SpecRef("RFC7644-3.3-R1")
    @SpecRef("RFC7643-3.1-R3")
    @SpecRef("RFC7643-3-R1")
    @SpecRef("RFC7643-2.3.5-R1")
    @SpecRef("RFC7643-3.1-R5")
    def "USR_01: POST minimal user returns 201 with required meta fields and headers"() {
        // RFC 7644 §3.3 — Creating Resources, RFC 7643 §3.1 — meta.location
        given: "A minimal user payload with unique userName"
        minimalUserName = "usr01_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when: "POST to /Users"
        def response = createUser(userName: minimalUserName)
        minimalUserId = response.jsonPath().getString("id")

        then: "Status is 201 Created"
        response.statusCode() == 201

        and: "Location header is present and valid"
        def location = response.header("Location")
        location != null
        location.endsWith("/Users/${minimalUserId}")

        and: "Content-Type is application/scim+json"
        response.contentType().contains(SCIM_CONTENT_TYPE)

        and: "Response body contains required attributes"
        def body = response.jsonPath()
        body.getString("id") == minimalUserId
        body.getString("userName") == minimalUserName
        body.getList("schemas").contains(USER_SCHEMA)

        and: "meta attributes are present per RFC 7643 §3.1 and §2.3.5"
        def meta = body.getMap("meta")
        meta != null
        meta.resourceType == "User"
        meta.created != null
        meta.lastModified != null
        meta.created.toString() ==~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/
        meta.lastModified.toString() ==~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/
        meta.lastModified == meta.created
        meta.location == location
    }

    // ─── USR_02: POST full Enterprise User ──────────────────────────────────

    @RequiresCapability("enterprise")
    @SpecRef("RFC7643-4.3-R1")
    @SpecRef("RFC7643-2.4-R1")
    def "USR_02: POST full Enterprise User with extension attributes"() {
        // RFC 7643 §4.3 — Enterprise User Extension
        given: "A full user payload with enterprise extension"
        def userName = "usr02_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when: "POST to /Users"
        def response = createFullUser(userName: userName)
        fullUserId = response.jsonPath().getString("id")

        then: "Status is 201 Created"
        response.statusCode() == 201

        and: "Both core and enterprise schemas are returned"
        def schemas = response.jsonPath().getList("schemas")
        schemas.contains(USER_SCHEMA)
        schemas.contains(ENTERPRISE_USER_SCHEMA)

        and: "Enterprise extension attributes are preserved"
        def enterprise = response.jsonPath().getMap("'${ENTERPRISE_USER_SCHEMA}'")
        enterprise != null
        enterprise.employeeNumber != null
        enterprise.department != null

        and: "Complex name attributes are present"
        response.jsonPath().getString("name.givenName") != null
        response.jsonPath().getString("name.familyName") != null

        and: "Multi-valued emails are present with work and home types"
        def emails = response.jsonPath().getList("emails")
        emails.size() == 2
        emails.find { it.type == "work" && it.primary == true } != null
        emails.find { it.type == "home" && it.primary == false } != null
    }

    // ─── USR_03: GET user by ID ─────────────────────────────────────────────

    @SpecRef("RFC7644-3.4.1-R1")
    @SpecRef("RFC7644-3.4-R1")
    @SpecRef("RFC7643-3.1-R2")
    @SpecRef("RFC7643-2.4-R1")
    def "USR_03: GET user by ID returns the created user"() {
        // RFC 7644 §3.4.1 — Retrieving a Known Resource
        when: "GET /Users/{id}"
        def response = scimRequest()
            .get("/Users/${minimalUserId}")

        then: "Status is 200 OK"
        assert response.statusCode() == 200

        and: "Content-Type is application/scim+json"
        assert response.contentType().contains(SCIM_CONTENT_TYPE)

        and: "User representation matches what was created"
        def body = response.jsonPath()
        assert body.getString("id") == minimalUserId
        assert body.getString("userName") == minimalUserName
        assert body.get("externalId") == null : "externalId was omitted on creation and must not be synthesized (RFC 7643 §3.1)"
        assert body.getList("schemas").contains(USER_SCHEMA)
        def emails = body.getList("emails")
        assert emails == null || emails.count { it.primary == true } <= 1
        def meta = body.getMap("meta")
        assert meta != null
        assert meta.resourceType == "User"
        assert meta.created != null
        assert meta.location != null
    }

    // ─── USR_04: GET non-existent user ──────────────────────────────────────

    @SpecRef("RFC7644-3.4.1-R2")
    def "USR_04: GET non-existent user returns 404 with SCIM Error schema"() {
        // RFC 7644 §3.4.1, §3.12 — Error responses
        when: "GET /Users/{fake-id}"
        def response = scimRequest()
            .get("/Users/00000000-0000-0000-0000-000000000000")

        then: "Status is 404 Not Found"
        response.statusCode() == 404

        and: "Response conforms to SCIM Error schema (RFC 7644 §3.12)"
        assertScimError(response, 404)
    }

    // ─── USR_05: PUT full replacement ───────────────────────────────────────

    @SpecRef("RFC7644-3.5.1-R1")
    @SpecRef("RFC7643-4.1.1-R4")
    @SpecRef("RFC7643-4.1.1-R5")
    def "USR_05: PUT replaces the resource with supplied attributes"() {
        // RFC 7644 §3.5.1 — Replacing with PUT
        given: "Create a user with name, title, and nickName"
        def createResponse = createFullUser(
            includeEnterprise: false,
            name: [givenName: "OriginalFirst", familyName: "OriginalLast"],
            title: "Manager",
            nickName: "OrigNick"
        )
        putTestUserId = createResponse.jsonPath().getString("id")
        def userName = createResponse.jsonPath().getString("userName")
        assert createResponse.jsonPath().getString("title") == "Manager"
        if (createResponse.jsonPath().get("nickName") != null) {
            assert createResponse.jsonPath().getString("nickName") == "OrigNick"
        }

        when: "PUT with updated name, localization/profile attributes, and emails while omitting title and nickName"
        def putEmail = "put_test_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def putPayload = [
            schemas: [USER_SCHEMA],
            userName: userName,
            name: [givenName: "UpdatedFirst", familyName: "UpdatedLast"],
            displayName: "UpdatedFirst UpdatedLast",
            userType: "Employee",
            preferredLanguage: "en-US",
            locale: "en-US",
            timezone: "America/Los_Angeles",
            profileUrl: "https://example.com/users/profile",
            emails: [[value: putEmail, type: "work", primary: true]]
        ]
        def putResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${putTestUserId}")

        then: "Status is 200"
        putResponse.statusCode() == 200

        and: "Supplied attributes are updated"
        putResponse.jsonPath().getString("name.givenName") == "UpdatedFirst"
        putResponse.jsonPath().getString("name.familyName") == "UpdatedLast"
        putResponse.jsonPath().getString("emails[0].value") == putEmail
        if (putResponse.jsonPath().get("displayName") != null) {
            assert putResponse.jsonPath().getString("displayName") == "UpdatedFirst UpdatedLast"
        }
        if (putResponse.jsonPath().get("userType") != null) {
            assert putResponse.jsonPath().getString("userType") == "Employee"
        }
        if (putResponse.jsonPath().get("profileUrl") != null) {
            assert putResponse.jsonPath().getString("profileUrl") == "https://example.com/users/profile"
        }
        if (putResponse.jsonPath().get("preferredLanguage") != null) {
            assert putResponse.jsonPath().getString("preferredLanguage") == "en-US"
        }
        if (putResponse.jsonPath().get("locale") != null) {
            assert putResponse.jsonPath().getString("locale") == "en-US"
        }
        if (putResponse.jsonPath().get("timezone") != null) {
            assert putResponse.jsonPath().getString("timezone") == "America/Los_Angeles"
        }

        when: "Subsequent GET /Users/{id}"
        def getResponse = scimRequest().get("/Users/${putTestUserId}")

        then: "Supplied attributes remain updated in persisted state"
        getResponse.statusCode() == 200
        getResponse.jsonPath().getString("name.givenName") == "UpdatedFirst"
    }

    // ─── USR_06: PUT immutability ───────────────────────────────────────────

    @SpecRef("RFC7644-3.5.1-R3")
    @SpecRef("RFC7643-3.1-R1")
    def "USR_06: PUT with different id value is rejected or ignored"() {
        // RFC 7644 §3.5.1, RFC 7643 §3.1 — ReadOnly/Immutable attribute modification
        given: "Create a user"
        def createResponse = createUser()
        putImmutTestUserId = createResponse.jsonPath().getString("id")
        def userName = createResponse.jsonPath().getString("userName")

        when: "PUT with a different id"
        def putPayload = [
            schemas : [USER_SCHEMA],
            id      : "different-fake-id-12345",
            userName: userName,
            emails  : [[value: "immut_${UUID.randomUUID().toString().substring(0, 8)}@test.com", type: "work", primary: true]]
        ]
        def putResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${putImmutTestUserId}")

        then: "Server ignores the client-supplied readOnly id and returns 200 OK (RFC 7644 §3.5.1)"
        assert putResponse.statusCode() == 200 : "Expected 200 OK ignoring client-supplied id, got ${putResponse.statusCode()}"
        assert putResponse.jsonPath().getString("id") == putImmutTestUserId : "id must not change via PUT"
        def getResponse = scimRequestQuiet().get("/Users/${putImmutTestUserId}")
        assert getResponse.jsonPath().getString("id") == putImmutTestUserId : "id must remain unchanged"
    }

    // ─── USR_07: DELETE user ────────────────────────────────────────────────

    @SpecRef("RFC7644-3.6-R1")
    def "USR_07: DELETE user returns 204 with no body"() {
        // RFC 7644 §3.6 — Deleting Resources
        given: "Create a user to delete"
        def createResponse = createUser()
        deleteTestUserId = createResponse.jsonPath().getString("id")

        when: "DELETE /Users/{id}"
        def response = scimRequest()
            .delete("/Users/${deleteTestUserId}")

        then: "Status is 204 No Content"
        response.statusCode() == 204

        and: "Response body is empty"
        response.body().asString().isBlank()
    }

    // ─── USR_08: GET after DELETE ───────────────────────────────────────────

    @SpecRef("RFC7644-3.6-R2")
    def "USR_08: GET after DELETE returns 404"() {
        // RFC 7644 §3.6 — Deleting Resources
        when: "GET deleted user"
        def response = scimRequest()
            .get("/Users/${deleteTestUserId}")

        then: "Status is 404 Not Found"
        response.statusCode() == 404
        assertScimError(response, 404)
    }

    // ─── USR_09: DELETE already-deleted user ─────────────────────────────────

    @SpecRef("RFC7644-3.6-R2")
    def "USR_09: DELETE already-deleted user returns 404"() {
        // RFC 7644 §3.6 — Deleting Resources
        when: "DELETE the already deleted user again"
        def response = scimRequest()
            .delete("/Users/${deleteTestUserId}")

        then: "Status is 404 Not Found"
        response.statusCode() == 404
        assertScimError(response, 404)
    }

    // ─── USR_10: GET /Users list ────────────────────────────────────────────

    @SpecRef("RFC7644-3.4.2-R1")
    def "USR_10: GET /Users list contains a created user"() {
        // RFC 7644 §3.4.2 — Query Resources
        given: "Create a user with known userName"
        def listTestUserName = "usr10_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createUser(userName: listTestUserName)
        def userId = createResponse.jsonPath().getString("id")

        when: "GET /Users?filter=userName eq \"...\""
        def response = scimRequest()
            .queryParam("filter", "userName eq \"${listTestUserName}\"")
            .get("/Users")

        then: "Status is 200 OK"
        response.statusCode() == 200

        and: "ListResponse contains the created user"
        def body = response.jsonPath()
        body.getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        body.getInt("totalResults") >= 1
        def resources = body.getList("Resources")
        resources.find { it.userName == listTestUserName } != null

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_11: GET /Me Authenticated Subject Alias ────────────────────────

    @SpecRef("RFC7644-3.11-R1")
    def "USR_11: GET /Me returns the authenticated subject"() {
        // RFC 7644 §3.11 — /Me Authenticated Subject Alias: accepts 200, 308, 400 (unresolvable subject), or 501
        when: "GET /Me without following redirects"
        def response = scimRequest()
            .redirects().follow(false)
            .get("/Me")

        then: "Server returns 200, 308, or 501 per RFC 7644 §3.11"
        assert [200, 308, 501].contains(response.statusCode()) :
            "RFC 7644 §3.11 requires /Me to return 200, 308, or 501, got ${response.statusCode()}"

        if (response.statusCode() == 501) {
            assertScimError(response, 501)
        } else if (response.statusCode() == 308) {
            assert response.header("Location") != null : "308 Permanent Redirect must include Location header"
        } else if (response.statusCode() == 200) {
            assert response.jsonPath().getList("schemas")?.contains(USER_SCHEMA) : "200 OK must return User representation"
            def loc = response.header("Location") ?: response.header("Content-Location") ?: response.jsonPath().getString("meta.location")
            assert loc != null && !loc.isBlank() : "200 OK on /Me must provide subject resource URI via Location/Content-Location or meta.location (RFC 7644 §3.11)"
        }
    }

    // ─── USR_12: POST without required userName ─────────────────────────────

    @SpecRef("RFC7644-3.3-R3")
    @SpecRef("RFC7643-4.1.1-R1")
    def "USR_12: POST user without required userName returns 400 Bad Request"() {
        // RFC 7644 §3.3, RFC 7643 §4.1.1 — Missing required attribute
        when: "POST to /Users without userName"
        def payload = [
            schemas: [USER_SCHEMA],
            name   : [givenName: "NoUserName", familyName: "Test"]
        ]
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Status is 400 Bad Request"
        response.statusCode() == 400

        and: "Response conforms to SCIM Error schema"
        assertScimError(response, 400)
    }

    // ─── USR_13: POST duplicate userName ────────────────────────────────────

    @SpecRef("RFC7644-3.3-R2")
    @SpecRef("RFC7643-2.2-R5")
    @SpecRef("RFC7643-4.1.1-R1")
    @SpecRef("RFC7644-5-R1")
    def "USR_13: POST user with duplicate userName returns 409 Conflict"() {
        // RFC 7644 §3.3, §3.12, §5 — Uniqueness conflict (including case-insensitive PRECIS comparison)
        given: "An existing user"
        def duplicateUserName = "dup_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def firstResponse = createUser(userName: duplicateUserName)
        assert firstResponse.statusCode() == 201
        def firstUserId = firstResponse.jsonPath().getString("id")

        when: "POST another user with the same userName"
        def payload = [
            schemas : [USER_SCHEMA],
            userName: duplicateUserName
        ]
        def duplicateResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Status is 409 Conflict"
        duplicateResponse.statusCode() == 409

        and: "Response conforms to SCIM Error schema with scimType 'uniqueness'"
        assertScimError(duplicateResponse, 409, "uniqueness")

        when: "POST another user with uppercase variant of the same userName (RFC 7644 §5 case-insensitive uniqueness)"
        def upperPayload = [
            schemas : [USER_SCHEMA],
            userName: duplicateUserName.toUpperCase()
        ]
        def upperDupResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(upperPayload))
            .post("/Users")

        then: "Case-insensitive duplicate also returns 409 Conflict"
        if (upperDupResponse.statusCode() == 201) {
            String strayId = upperDupResponse.jsonPath().getString("id")
            if (strayId) createdUserIds << strayId
        }
        assert upperDupResponse.statusCode() == 409 : "userName uniqueness MUST be case-insensitive per RFC 7643 §4.1.1 and RFC 7644 §5"
        assertScimError(upperDupResponse, 409, "uniqueness")

        when: "Create user with internationalized Unicode userName and test PRECIS case-folding (RFC 7644 §5)"
        def precisBase = "müller_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def precisFirst = createUser(userName: precisBase)
        String precisUserId = precisFirst.statusCode() == 201 ? precisFirst.jsonPath().getString("id") : null
        if (precisUserId) createdUserIds << precisUserId
        def precisUpperResp = precisUserId != null ? scimRequestQuiet()
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: precisBase.toUpperCase(Locale.ROOT)]))
            .post("/Users") : null

        then: "PRECIS internationalized case-insensitive duplicate returns 409 Conflict when Unicode userName is accepted"
        if (precisUpperResp != null) {
            if (precisUpperResp.statusCode() == 201) {
                String strayPrecisId = precisUpperResp.jsonPath().getString("id")
                if (strayPrecisId) createdUserIds << strayPrecisId
            }
            assert precisUpperResp.statusCode() == 409 : "PRECIS case-folded Unicode userName duplicate MUST return 409 Conflict (RFC 7644 §5)"
            assertScimError(precisUpperResp, 409, "uniqueness")
        }

        cleanup:
        if (firstUserId) {
            try { scimRequestQuiet().delete("/Users/${firstUserId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_14: GET /Users/{id} attribute selection ────────────────────────

    @SpecRef("RFC7644-3.4.2.5-R1")
    @SpecRef("RFC7644-3.9-R1")
    @SpecRef("RFC7643-2.2-R4")
    def "USR_14: GET user by ID supports attributes projection"() {
        // RFC 7644 §3.4.2.5, §3.9 — Attribute Selection on direct resource retrieval
        given: "A user with multiple attributes"
        def userName = "attr_proj_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createFullUser(
            includeEnterprise: false,
            userName: userName,
            name: [givenName: "ProjectedFirst", familyName: "ProjectedLast"],
            title: "Staff Engineer"
        )
        def userId = createResponse.jsonPath().getString("id")
        assert createResponse.statusCode() == 201

        when: "GET /Users/{id} requesting only userName and title"
        def attrResponse = scimRequestQuiet()
            .queryParam("attributes", "userName,title")
            .get("/Users/${userId}")

        then: "Status is 200"
        attrResponse.statusCode() == 200

        and: "Requested and always/required attributes are present, non-requested are omitted"
        attrResponse.jsonPath().getList("schemas")?.contains(USER_SCHEMA)
        attrResponse.jsonPath().getString("id") == userId
        attrResponse.jsonPath().getString("userName") == userName
        attrResponse.jsonPath().getString("title") == "Staff Engineer"
        attrResponse.jsonPath().get("name") == null || attrResponse.jsonPath().get("name.givenName") == null

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_15: PUT to non-existent user ───────────────────────────────────

    @SpecRef("RFC7644-3.2-R2")
    def "USR_15: PUT to non-existent user ID returns 404 Not Found"() {
        // RFC 7644 §3.2 Table 1 — PUT MUST NOT be used to create new resources
        when: "PUT to fake UUID"
        def putPayload = [
            schemas : [USER_SCHEMA],
            userName: "ghost_${UUID.randomUUID().toString().substring(0, 8)}@test.com",
            emails  : [[value: "ghost@test.com", type: "work", primary: true]]
        ]
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/nonexistent-uuid-00000000-0000-0000-0000-000000000000")

        then: "Status is 404 Not Found"
        response.statusCode() == 404

        and: "Response conforms to SCIM Error schema"
        assertScimError(response, 404)
    }

    // ─── USR_16: PUT optimistic concurrency with If-Match ───────────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R1")
    @SpecRef("RFC7644-3.14-R2")
    def "USR_16: PUT supports optimistic locking with If-Match"() {
        // RFC 7644 §3.14 — Concurrency and ETags
        requireCapability("etag")
        given: "A user created to test concurrency"
        def createResponse = createUser()
        def userId = createResponse.jsonPath().getString("id")
        def userName = createResponse.jsonPath().getString("userName")
        def etag = createResponse.header("ETag") ?: createResponse.jsonPath().getString("meta.version")

        when: "PUT with mismatched If-Match header"
        def mismatchPayload = [
            schemas    : [USER_SCHEMA],
            userName   : userName,
            displayName: "Concurrency Test Stale"
        ]
        def mismatchResponse = scimRequestQuiet()
            .header("If-Match", 'W/"999999"')
            .body(JsonOutput.toJson(mismatchPayload))
            .put("/Users/${userId}")

        then: "Server supporting ETags returns 412 Precondition Failed"
        assert etag != null : "Server advertising etag.supported=true must return ETag header or meta.version (RFC 7644 §3.14)"
        assert mismatchResponse.statusCode() == 412
        assertScimError(mismatchResponse, 412)

        when: "PUT with valid/matching If-Match header"
        def matchResponse = scimRequestQuiet()
            .header("If-Match", etag)
            .body(JsonOutput.toJson(mismatchPayload))
            .put("/Users/${userId}")

        then: "Update succeeds"
        assert matchResponse.statusCode() == 200
        assert matchResponse.jsonPath().getString("displayName") == "Concurrency Test Stale"

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── Gap Closures: USR_17..USR_21 ────────────────────────────────────────

    @SpecRef("RFC7644-3.3-R4")
    @SpecRef("RFC7643-3.1-R1")
    @SpecRef("RFC7643-2.2-R3")
    def "USR_17: Client-supplied readOnly attributes on POST are ignored"() {
        // RFC 7644 §3.3 & RFC 7643 §3.1 — Client MUST NOT specify id; readOnly attributes ignored
        given: "User payload with client-supplied id"
        def clientSuppliedId = "client-id-${UUID.randomUUID().toString()}"
        def uniqueUserName = "ro_test_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def payload = [
            schemas : [USER_SCHEMA],
            id      : clientSuppliedId,
            userName: uniqueUserName
        ]

        when: "POST to /Users"
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server ignores client id and generates server id with HTTP 201"
        assert response.statusCode() == 201 : "Expected 201 Created ignoring client-supplied id, got ${response.statusCode()}"
        String assignedId = response.jsonPath().getString("id")
        assert assignedId != clientSuppliedId : "Client-supplied id must not be honored (RFC 7643 §3.1)"
        createdUserIds << assignedId
    }

    @SpecRef("RFC7644-3.3-R5")
    @SpecRef("RFC7643-2.5-R1")
    def "USR_18: Specifying null or empty array in POST or PUT clears the attribute"() {
        // RFC 7644 §3.3, §3.5.1, RFC 7643 §2.5 — null / [] clears attribute
        given: "Create user with title, nickName=null, and phoneNumbers=[] on POST"
        def userName = "null_clear_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createUser(
            userName: userName,
            title: "Vice President",
            nickName: null,
            phoneNumbers: []
        )
        def userId = createResponse.jsonPath().getString("id")
        assert createResponse.statusCode() == 201
        assert createResponse.jsonPath().get("nickName") == null
        def createdPhones = createResponse.jsonPath().getList("phoneNumbers")
        assert createdPhones == null || createdPhones.isEmpty()

        when: "PUT user setting title to null and emails to empty array"
        def putPayload = [
            schemas : [USER_SCHEMA],
            userName: userName,
            title   : null,
            emails  : []
        ]
        def putResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${userId}")

        then: "Status is 200 and title and emails are cleared"
        putResponse.statusCode() == 200
        assert putResponse.jsonPath().get("title") == null
        def putEmails = putResponse.jsonPath().getList("emails")
        assert putEmails == null || putEmails.isEmpty()

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_19: Password never returned ────────────────────────────────────

    @SpecRef("RFC7643-4.1.1-R2")
    @SpecRef("RFC7643-2.2-R4")
    @SpecRef("RFC7644-3.4-R1")
    def "USR_19: Password attribute is never returned even when requested via attributes parameter"() {
        // RFC 7643 §4.1.1 & RFC 7644 §3.9 — password returned=never
        given: "Create a user with password"
        def userName = "pwd_test_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createUser(
            userName: userName,
            password: "SuperSecretPassword123!"
        )
        def userId = createResponse.jsonPath().getString("id")
        assert createResponse.statusCode() == 201
        assert createResponse.jsonPath().getString("userName") == userName
        assert createResponse.jsonPath().get("password") == null

        when: "GET user explicitly requesting attributes=password"
        def response = scimRequestQuiet()
            .queryParam("attributes", "userName,password")
            .get("/Users/${userId}")

        then: "Response does not include password value"
        response.statusCode() == 200
        assert response.jsonPath().get("password") == null :
            "Password must never be returned even when explicitly requested (RFC 7643 §4.1.1, RFC 7644 §3.9)"

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    @SpecRef("RFC7643-4.1.2-R6")
    @SpecRef("RFC7644-3.3-R4")
    def "USR_20: User groups attribute is readOnly; direct modification on POST or PUT is ignored"() {
        // RFC 7643 §4.1.2 — User groups attribute is readOnly
        given: "Attempt to create User with direct groups attribute"
        def userName = "grp_ro_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def payload = [
            schemas : [USER_SCHEMA],
            userName: userName,
            groups  : [[value: "fake-group-id", display: "AdminGroup"]]
        ]

        when: "POST /Users with groups"
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server ignores groups attribute on POST and returns 201"
        assert response.statusCode() == 201 : "Expected 201 Created ignoring groups, got ${response.statusCode()}"
        def createdGroups = response.jsonPath().getList("groups")
        assert createdGroups == null || createdGroups.isEmpty() :
            "Direct client modification of readOnly groups attribute must be ignored (RFC 7643 §4.1.2)"
        String id = response.jsonPath().getString("id")
        assert id != null
        createdUserIds << id

        when: "PUT /Users/{id} with groups"
        def putResp = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .put("/Users/${id}")

        then: "Server ignores groups attribute on PUT and returns 200"
        assert putResp.statusCode() == 200 : "Expected 200 OK ignoring groups via PUT, got ${putResp.statusCode()}"
        def putGroups = putResp.jsonPath().getList("groups")
        assert putGroups == null || putGroups.isEmpty() :
            "Direct client modification of readOnly groups attribute via PUT must be ignored (RFC 7643 §4.1.2)"
    }

    @SpecRef("RFC7643-4.1.2-R3")
    @SpecRef("RFC7643-4.1.2-R4")
    @SpecRef("RFC7643-2.4-R1")
    @SpecRef("RFC7643-2.4-R2")
    def "USR_21: User creation with multi-valued ims and photos attributes"() {
        // RFC 7643 §4.1.2 — User multi-valued attributes (ims, photos)
        given: "User with ims and photos attributes"
        def userName = "ims_photos_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def payload = [
            schemas : [USER_SCHEMA],
            userName: userName,
            ims     : [[value: "dev_im_handle", type: "xmpp"]],
            photos  : [[value: "https://example.com/photos/user.png", type: "photo"]]
        ]

        when: "POST /Users"
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Status is 201 and supplied multi-valued attributes round-trip"
        assert response.statusCode() == 201 : "Expected 201 Created for ims and photos, got ${response.statusCode()}"
        String id = response.jsonPath().getString("id")
        if (id) createdUserIds << id
        def ims = response.jsonPath().getList("ims")
        if (ims != null) {
            assert ims.any { it.value == "dev_im_handle" }
        }
        def photos = response.jsonPath().getList("photos")
        if (photos != null) {
            assert photos.any { it.value == "https://example.com/photos/user.png" }
        }
    }

    // ─── USR_22: Multi-valued duplicate (type, value) de-duplication ────────

    @SpecRef("RFC7643-2.4-R3")
    def "USR_22: Multi-valued attributes with duplicate type and value are de-duplicated"() {
        // RFC 7643 §2.4: "Service providers SHOULD NOT return duplicate (type, value) entries"
        given: "User with duplicate email entries"
        def dupEmail = "dup_val_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def userName = "dedup_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: userName,
            emails  : [
                [value: dupEmail, type: "work"],
                [value: dupEmail, type: "work"]
            ]
        ]

        when: "POST /Users with duplicate entries"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server returns 201 or 400"
        response.statusCode() in [201, 400]
        if (response.statusCode() == 201) {
            String uid = response.jsonPath().getString("id")
            if (uid) createdUserIds << uid
            def emails = response.jsonPath().getList("emails")
            if (emails != null && emails.size() > 1) {
                def workDups = emails.findAll { it.value == dupEmail && it.type == "work" }
                assert workDups.size() <= 1 : "Server returned duplicate (type, value) entries contrary to RFC 7643 §2.4 SHOULD recommendation"
            }
        }
    }

    // ─── USR_23: Duplicate schema URNs in schemas array ─────────────────────

    @SpecRef("RFC7643-3-R1")
    def "USR_23: Specifying duplicate schema URNs in schemas array is handled cleanly"() {
        // RFC 7643 §3: "Each schema URI MUST be unique."
        given: "User payload with duplicate schema URNs"
        def userName = "dup_schema_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map payload = [
            schemas : [USER_SCHEMA, USER_SCHEMA],
            userName: userName
        ]

        when: "POST /Users with duplicate schemas"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server returns 201 or 400"
        response.statusCode() in [201, 400]
        if (response.statusCode() == 201) {
            String uid = response.jsonPath().getString("id")
            if (uid) createdUserIds << uid
            def respSchemas = response.jsonPath().getList("schemas")
            assert respSchemas.size() == respSchemas.unique(false).size() :
                "Response schemas array must not contain duplicate URNs (RFC 7643 §3)"
        }
    }

    // ─── USR_24: Extension attributes do not leak to top level ──────────────

    @RequiresCapability("enterprise")
    @SpecRef("RFC7643-3-R2")
    def "USR_24: Enterprise extension attributes are scoped to URN and do not leak to top level"() {
        // RFC 7643 §3: "Extended attributes MUST be in their own JSON sub-structure keyed by schema URI; no top-level leak."
        given: "Create user with enterprise extension"
        def userName = "leak_check_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Response response = createFullUser(userName: userName)
        assert response.statusCode() == 201
        String uid = response.jsonPath().getString("id")

        when: "Inspect GET representation"
        Response getResp = scimRequest().get("/Users/${uid}")

        then: "Status is 200"
        assert getResp.statusCode() == 200

        and: "Extension attribute employeeNumber is inside extension object and NOT top level"
        assert getResp.jsonPath().get("'${ENTERPRISE_USER_SCHEMA}'.employeeNumber") != null
        assert getResp.jsonPath().get("employeeNumber") == null :
            "Extension attribute 'employeeNumber' must not leak into top-level JSON structure (RFC 7643 §3)"

        cleanup:
        if (uid) deleteUser(uid)
    }

    // ─── USR_25: Change user password via PUT ────────────────────────────────

    @RequiresCapability("changePassword")
    @SpecRef("RFC7643-4.1.1-R7")
    def "USR_25: Change user password via PUT succeeds and never returns password"() {
        // RFC 7643 §4.1.1 (password mutability writeOnly, returned never, changePassword capability)
        // RFC 7644 §3.5.1 (PUT)
        given: "Create a user with an initial password"
        def userName = "pwd_change_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def initialPassword = "InitialPassword123!"
        def createResponse = createUser(
            userName: userName,
            password: initialPassword
        )
        assert createResponse.statusCode() == 201
        def userId = createResponse.jsonPath().getString("id")
        assert createResponse.jsonPath().get("password") == null : "Password must not be returned in POST response"

        when: "Change password via PUT full replacement"
        def putPassword = "PutPassword789!"
        def putPayload = [
            schemas: [USER_SCHEMA],
            userName: userName,
            password: putPassword
        ]
        def putResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${userId}")

        then: "PUT succeeds with HTTP 200 and password is not returned"
        assert putResponse.statusCode() == 200 : "PUT password must return 200 OK (RFC 7644 §3.5.1)"
        assert putResponse.jsonPath().get("password") == null : "Password must not be returned in PUT response"
        assert !putResponse.body().asString().contains(putPassword)

        when: "Verify user via GET after PUT"
        def getResponse2 = scimRequestQuiet().get("/Users/${userId}")

        then: "GET returns 200 and password attribute remains null"
        assert getResponse2.statusCode() == 200
        assert getResponse2.jsonPath().get("password") == null : "Password must never be returned in GET response after PUT"
        assert !getResponse2.body().asString().contains(putPassword)

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_26: PUT clears or defaults omitted readWrite attributes ────────

    @SpecRef("RFC7644-3.5.1-R2")
    def "USR_26: PUT clears or defaults omitted readWrite attributes"() {
        // RFC 7644 §3.5.1 MAY — Omitted readWrite attributes may be cleared or reset to defaults
        given: "A user created with title and nickName"
        def userName = "omitted_rw_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createUser(
            userName: userName,
            title: "Manager",
            nickName: "OrigNick"
        )
        assert createResponse.statusCode() == 201
        def userId = createResponse.jsonPath().getString("id")
        createdUserIds << userId

        when: "PUT user omitting title and nickName"
        def putPayload = [
            schemas : [USER_SCHEMA],
            userName: userName
        ]
        def putResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${userId}")

        then: "PUT returns 200 OK"
        assert putResponse.statusCode() == 200

        and: "Check if omitted readWrite attributes were cleared or defaulted"
        String putTitle = putResponse.jsonPath().getString("title")
        String putNick = putResponse.jsonPath().getString("nickName")
        boolean clearedOrDefaulted = (putTitle == null || putTitle != "Manager") &&
                                     (putNick == null || putNick != "OrigNick")

        when: "Verify via subsequent GET"
        def getResponse = scimRequestQuiet().get("/Users/${userId}")
        assert getResponse.statusCode() == 200
        String getTitle = getResponse.jsonPath().getString("title")
        String getNick = getResponse.jsonPath().getString("nickName")
        boolean persistedCleared = (getTitle == null || getTitle != "Manager") &&
                                   (getNick == null || getNick != "OrigNick")

        then: "If server retains omitted attributes, abort via TestAbortedException"
        if (!clearedOrDefaulted || !persistedCleared) {
            throw new TestAbortedException("Server retains omitted readWrite attributes on PUT contrary to RFC 7644 §3.5.1 MAY")
        }
    }

    // ─── USR_27: Deleted User is omitted from subsequent GET queries ─────────

    @SpecRef("RFC7644-3.6-R3")
    def "USR_27: Deleted User is omitted from subsequent GET /Users queries"() {
        // RFC 7644 §3.6 — Deleted resource is omitted from subsequent query results
        given: "A created and then deleted user"
        String uniqueUserName = "del_qry_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Response cr = createUser(userName: uniqueUserName)
        assert cr.statusCode() == 201
        String uid = cr.jsonPath().getString("id")
        Response del = scimRequestQuiet().delete("/Users/${uid}")
        assert del.statusCode() in [200, 204]
        createdUserIds.remove(uid)

        when: "Querying /Users"
        Response queryResp = scimRequestQuiet().get("/Users")

        then: "Deleted user is not returned in query results"
        assert queryResp.statusCode() == 200
        def resources = queryResp.jsonPath().getList("Resources")
        assert resources == null || !resources.any { it.id == uid }
    }

    // ─── USR_28: Re-creating User with same userName succeeds ───────────────

    @SpecRef("RFC7644-3.6-R4")
    def "USR_28: Re-creating User with same userName succeeds after deletion"() {
        // RFC 7644 §3.6 — Service provider SHOULD NOT consider deleted resource in conflict calculation
        given: "A created and then deleted user"
        String uniqueUserName = "recreate_usr_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Response cr = createUser(userName: uniqueUserName)
        assert cr.statusCode() == 201
        String oldId = cr.jsonPath().getString("id")
        Response del = scimRequestQuiet().delete("/Users/${oldId}")
        assert del.statusCode() in [200, 204]
        createdUserIds.remove(oldId)

        when: "Re-creating user with same userName"
        Response reCr = scimRequestQuiet()
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: uniqueUserName]))
            .post("/Users")

        then: "Re-creation succeeds with 201 Created and new id"
        assert reCr.statusCode() == 201 : "Re-creating deleted user should succeed with 201 (RFC 7644 §3.6), got ${reCr.statusCode()}"
        String newId = reCr.jsonPath().getString("id")
        assert newId != null && newId != oldId

        cleanup:
        if (newId) deleteUser(newId)
    }

    // ─── USR_29: GET user by ID supports excludedAttributes projection ──────

    @SpecRef("RFC7644-3.4.2.5-R2")
    @SpecRef("RFC7644-3.9-R1")
    @SpecRef("RFC7643-2.2-R4")
    def "USR_29: GET user by ID supports excludedAttributes projection"() {
        // RFC 7644 §3.4.2.5, §3.9 — Attribute Selection on direct resource retrieval
        given: "A user with multiple attributes"
        def userName = "excl_proj_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createFullUser(
            includeEnterprise: false,
            userName: userName,
            name: [givenName: "ExclFirst", familyName: "ExclLast"],
            title: "Staff Engineer"
        )
        assert createResponse.statusCode() == 201
        def userId = createResponse.jsonPath().getString("id")

        when: "GET /Users/{id} excluding emails and name"
        def exclResponse = scimRequestQuiet()
            .queryParam("excludedAttributes", "emails,name")
            .get("/Users/${userId}")

        then: "Status is 200"
        assert exclResponse.statusCode() == 200

        and: "Excluded attributes are omitted while minimum set (schemas, id) and userName/title remain"
        assert exclResponse.jsonPath().getList("schemas")?.contains(USER_SCHEMA)
        assert exclResponse.jsonPath().getString("id") == userId
        assert exclResponse.jsonPath().getString("userName") == userName
        assert exclResponse.jsonPath().getString("title") == "Staff Engineer"
        assert exclResponse.jsonPath().get("emails") == null
        assert exclResponse.jsonPath().get("name") == null

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_30: Mutual exclusion of attributes and excludedAttributes ──────

    @SpecRef("RFC7644-3.4.2.5-R3")
    def "USR_30: GET /Users/{id} with mutually exclusive attributes and excludedAttributes returns 400"() {
        // RFC 7644 §3.9 — attributes and excludedAttributes are mutually exclusive
        given: "An existing user"
        def userName = "mut_excl_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def cr = createUser(userName: userName)
        assert cr.statusCode() == 201
        def uid = cr.jsonPath().getString("id")

        when: "GET /Users/{id} with both attributes and excludedAttributes"
        def response = scimRequestQuiet()
            .queryParam("attributes", "userName")
            .queryParam("excludedAttributes", "name")
            .get("/Users/${uid}")

        then: "Server rejects with HTTP 400 Bad Request"
        assert response.statusCode() == 400 : "Expected 400 Bad Request for mutually exclusive params, got ${response.statusCode()}"
        assertScimError(response, 400)

        cleanup:
        if (uid) deleteUser(uid)
    }

    // ─── USR_31: Unrecognized query parameters ignored ──────────────────────

    @SpecRef("RFC7644-3.4.2-R3")
    def "USR_31: GET /Users ignores unrecognized query parameters and returns 200"() {
        // RFC 7644 §3.4.2 — Unrecognized query parameters SHOULD be ignored
        when: "GET /Users with unrecognized query parameter"
        def response = scimRequestQuiet()
            .queryParam("unknownQueryParamNonExistent", "testValue123")
            .get("/Users")

        then: "Server ignores the unknown query param and returns 200 OK"
        assert response.statusCode() == 200 : "Expected 200 OK ignoring unknown parameter, got ${response.statusCode()}"
        assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)
    }

    // ─── USR_32: Multi-valued addresses with valid ISO 3166-1 country code ──

    @SpecRef("RFC7643-4.1.2-R5")
    @SpecRef("RFC7643-2.4-R1")
    @SpecRef("RFC7643-2.4-R2")
    def "USR_32: User creation with multi-valued addresses and valid ISO 3166-1 country code"() {
        // RFC 7643 §4.1.2 & §2.4 — User multi-valued addresses with ISO 3166-1 country code
        given: "User with addresses"
        def userName = "addr_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def payload = [
            schemas  : [USER_SCHEMA],
            userName : userName,
            addresses: [
                [locality: "Austin", region: "TX", country: "US", type: "work", primary: true]
            ]
        ]

        when: "POST /Users"
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Status is 201 and address attributes round-trip"
        assert response.statusCode() == 201
        String id = response.jsonPath().getString("id")
        if (id) createdUserIds << id
        def addresses = response.jsonPath().getList("addresses")
        if (addresses != null && !addresses.isEmpty()) {
            assert addresses[0].country == "US"
            assert addresses[0].locality == "Austin"
            assert addresses[0].region == "TX"
        }
    }

    // ─── USR_33: Multi-valued roles and entitlements ────────────────────────

    @SpecRef("RFC7643-4.1.2-R7")
    @SpecRef("RFC7643-2.4-R1")
    @SpecRef("RFC7643-2.4-R2")
    def "USR_33: User creation with multi-valued roles and entitlements"() {
        // RFC 7643 §4.1.2 & §2.4 — User multi-valued roles and entitlements
        given: "User with roles and entitlements"
        def userName = "roles_ent_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def payload = [
            schemas     : [USER_SCHEMA],
            userName    : userName,
            roles       : [[value: "Developer", primary: true]],
            entitlements: [[value: "AdminAccess"]]
        ]

        when: "POST /Users"
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Status is 201 and supplied multi-valued attributes round-trip"
        assert response.statusCode() == 201
        String id = response.jsonPath().getString("id")
        if (id) createdUserIds << id
        def roles = response.jsonPath().getList("roles")
        if (roles != null) {
            assert roles.any { it.value == "Developer" }
        }
        def entitlements = response.jsonPath().getList("entitlements")
        if (entitlements != null) {
            assert entitlements.any { it.value == "AdminAccess" }
        }
    }

    // ─── USR_34: Binary x509Certificates canonical DER round-trip ───────────

    @SpecRef("RFC7643-4.1.2-R8")
    @SpecRef("RFC7643-2.3.6-R1")
    def "USR_34: User creation with binary x509Certificates canonical DER round-trip"() {
        // RFC 7643 §4.1.2 & §2.3.6 — x509Certificates is binary (Base64 DER)
        given: "User with valid binary DER encoded x509Certificate"
        def userName = "cert_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        byte[] derBytes = [0x30, 0x03, 0x02, 0x01, 0x01] as byte[]
        String sampleCertBase64 = Base64.encoder.encodeToString(derBytes)
        def payload = [
            schemas         : [USER_SCHEMA],
            userName        : userName,
            x509Certificates: [[value: sampleCertBase64]]
        ]

        when: "POST /Users"
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Status is 201 and binary DER certificate bytes round-trip canonically"
        assert response.statusCode() == 201
        String id = response.jsonPath().getString("id")
        if (id) createdUserIds << id
        def certs = response.jsonPath().getList("x509Certificates")
        if (certs != null && !certs.isEmpty()) {
            String returnedVal = certs[0].value
            assert returnedVal != null
            byte[] returnedBytes = Base64.decoder.decode(returnedVal.trim())
            assert returnedBytes == derBytes
        }
    }

    // ─── USR_35: Invalid ISO 3166-1 country code returns 400 Bad Request ────

    @SpecRef("RFC7643-4.1.2-R5")
    def "USR_35: POST /Users with non-ISO-3166-1 alpha-2 country code returns 400 Bad Request"() {
        // RFC 7643 §4.1.2 — addresses.country MUST be a valid ISO 3166-1 alpha-2 country code
        given: "User payload with 3-letter country code 'USA'"
        def userName = "bad_country_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def payload = [
            schemas  : [USER_SCHEMA],
            userName : userName,
            addresses: [
                [country: "USA", type: "work"]
            ]
        ]

        when: "POST /Users with non-ISO 3166-1 alpha-2 country"
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Server rejects with HTTP 400 Bad Request"
        assert response.statusCode() == 400 : "Expected 400 Bad Request for non-ISO-3166-1 country code, got ${response.statusCode()}"
        assertScimErrorOptionalType(response, 400, "invalidValue")
    }

    // ─── Cleanup ────────────────────────────────────────────────────────────

    def cleanupSpec() {
        [minimalUserId, fullUserId, putTestUserId, putImmutTestUserId].each { id ->
            if (id) {
                try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
            }
        }
        createdUserIds.each { id ->
            if (id) {
                try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
            }
        }
        createdUserIds.clear()
    }
}
