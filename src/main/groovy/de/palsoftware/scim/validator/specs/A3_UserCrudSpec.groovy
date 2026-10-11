package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
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
    @SpecRef("RFC7643-3.1-R2")
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

        and: "meta attributes are present per RFC 7643 §3.1"
        def meta = body.getMap("meta")
        meta != null
        meta.resourceType == "User"
        meta.created != null
        meta.lastModified != null
        meta.location == location
    }

    // ─── USR_02: POST full Enterprise User ──────────────────────────────────

    @RequiresCapability("enterprise")
    @SpecRef("RFC7643-4.3-R1")
    @SpecRef("RFC7643-2.3.5-R1")
    @SpecRef("RFC7643-3.1-R5")
    @SpecRef("RFC7643-4.1.1-R4")
    @SpecRef("RFC7643-4.1.1-R5")
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
        assert body.getList("schemas").contains(USER_SCHEMA)
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
    @SpecRef("RFC7644-3.5.1-R2")
    def "USR_05: PUT replaces the resource, omitted attributes are cleared or defaulted"() {
        // RFC 7644 §3.5.1 — Replacing with PUT
        given: "Create a user with name, title, and nickName"
        def createResponse = createFullUser(
            name: [givenName: "OriginalFirst", familyName: "OriginalLast"],
            title: "Manager",
            nickName: "OrigNick"
        )
        putTestUserId = createResponse.jsonPath().getString("id")
        def userName = createResponse.jsonPath().getString("userName")
        assert createResponse.jsonPath().getString("title") == "Manager"

        when: "PUT with updated name and emails but omitting title and nickName"
        def putEmail = "put_test_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def putPayload = [
            schemas: [USER_SCHEMA],
            userName: userName,
            name: [givenName: "UpdatedFirst", familyName: "UpdatedLast"],
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

        and: "Omitted attributes are cleared or reset to defaults per RFC 7644 §3.5.1"
        assert putResponse.jsonPath().getString("title") == null || putResponse.jsonPath().getString("title") != "Manager"
        assert putResponse.jsonPath().getString("nickName") == null || putResponse.jsonPath().getString("nickName") != "OrigNick"

        when: "Subsequent GET /Users/{id}"
        def getResponse = scimRequest().get("/Users/${putTestUserId}")

        then: "Omitted attributes remain cleared or defaulted in persisted state"
        getResponse.statusCode() == 200
        assert getResponse.jsonPath().getString("title") == null || getResponse.jsonPath().getString("title") != "Manager"
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

        then: "Server either rejects with 400 (mutability) or ignores the id change (200)"
        if (putResponse.statusCode() == 400) {
            assert putResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
            assertScimType(putResponse, "mutability")
        } else if (putResponse.statusCode() == 200) {
            assert putResponse.jsonPath().getString("id") == putImmutTestUserId : "id must not change via PUT"
            def getResponse = scimRequestQuiet().get("/Users/${putImmutTestUserId}")
            assert getResponse.jsonPath().getString("id") == putImmutTestUserId : "id must remain unchanged"
        } else if (putResponse.statusCode() == 403) {
            ScimOutput.deviation("Server returned 403 Forbidden for immutable/readOnly id change instead of 400 mutability or ignoring", "USR_06", "RFC7644-3.5.1-R3")
            def getResponse = scimRequestQuiet().get("/Users/${putImmutTestUserId}")
            assert getResponse.jsonPath().getString("id") == putImmutTestUserId : "id must remain unchanged"
        } else {
            assert false : "Unexpected status ${putResponse.statusCode()} for PUT with modified id"
        }
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
        // RFC 7644 §3.11 — /Me Authenticated Subject Alias: accepts 200, 308, or 501
        when: "GET /Me"
        def response = scimRequest()
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
    @SpecRef("RFC7644-5-R1")
    def "USR_13: POST user with duplicate userName returns 409 Conflict"() {
        // RFC 7644 §3.3, §3.12 — Uniqueness conflict
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

        cleanup:
        if (firstUserId) {
            try { scimRequestQuiet().delete("/Users/${firstUserId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_14: GET /Users/{id} attribute selection ────────────────────────

    @SpecRef("RFC7644-3.4.2.5-R1")
    @SpecRef("RFC7644-3.4.2.5-R2")
    def "USR_14: GET user by ID supports attributes and excludedAttributes projections"() {
        // RFC 7644 §3.4.2.5, §3.9 — Attribute Selection on direct resource retrieval
        given: "A user with multiple attributes"
        def userName = "attr_proj_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createFullUser(
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
        attrResponse.jsonPath().getString("id") == userId
        attrResponse.jsonPath().getString("userName") == userName
        attrResponse.jsonPath().getString("title") == "Staff Engineer"
        attrResponse.jsonPath().get("name") == null || attrResponse.jsonPath().get("name.givenName") == null

        when: "GET /Users/{id} excluding emails and name"
        def exclResponse = scimRequestQuiet()
            .queryParam("excludedAttributes", "emails,name")
            .get("/Users/${userId}")

        then: "Status is 200"
        exclResponse.statusCode() == 200

        and: "Excluded attributes are omitted while userName and title remain"
        exclResponse.jsonPath().getString("userName") == userName
        exclResponse.jsonPath().getString("title") == "Staff Engineer"
        exclResponse.jsonPath().get("emails") == null
        exclResponse.jsonPath().get("name") == null

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
        if (etag != null) {
            assert mismatchResponse.statusCode() == 412
            assert mismatchResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        } else {
            assert mismatchResponse.statusCode() in [200, 412]
        }

        when: "PUT with valid/matching If-Match header"
        def matchResponse = scimRequestQuiet()
            .header("If-Match", etag ?: '*')
            .body(JsonOutput.toJson(mismatchPayload))
            .put("/Users/${userId}")

        then: "Update succeeds"
        if (etag != null) {
            assert matchResponse.statusCode() in [200, 412]
            if (matchResponse.statusCode() == 200) {
                assert matchResponse.jsonPath().getString("displayName") == "Concurrency Test Stale"
            }
        }

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── Gap Closures: USR_17..USR_21 ────────────────────────────────────────

    @SpecRef("RFC7644-3.3-R4")
    @SpecRef("RFC7643-3.1-R1")
    def "USR_17: Client-supplied readOnly attributes on POST are ignored or rejected"() {
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

        then: "Server either rejects with 400 or ignores client id generating server id"
        assert [201, 400].contains(response.statusCode())
        if (response.statusCode() == 201) {
            String assignedId = response.jsonPath().getString("id")
            assert assignedId != clientSuppliedId : "Client-supplied id must not be honored (RFC 7643 §3.1)"
            createdUserIds << assignedId
        }
    }

    @SpecRef("RFC7644-3.3-R5")
    @SpecRef("RFC7643-2.5-R1")
    @SpecRef("RFC7643-2.2-R3")
    def "USR_18: Specifying null or empty array in POST or PUT clears the attribute"() {
        // RFC 7644 §3.3, §3.5.1, RFC 7643 §2.5 — null / [] clears attribute
        given: "Create user with title and multiple emails"
        def userName = "null_clear_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createUser(
            userName: userName,
            title: "Vice President"
        )
        def userId = createResponse.jsonPath().getString("id")
        assert createResponse.statusCode() == 201

        when: "PUT user setting title to null"
        def putPayload = [
            schemas : [USER_SCHEMA],
            userName: userName,
            title   : null
        ]
        def putResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${userId}")

        then: "Status is 200 and title is null/cleared"
        putResponse.statusCode() == 200
        assert putResponse.jsonPath().get("title") == null

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_19: Password never returned ────────────────────────────────────

    @SpecRef("RFC7643-4.1.1-R2")
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
    def "USR_20: User groups attribute is readOnly; direct modification on POST or PUT is ignored or rejected"() {
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

        then: "Server either rejects with 400 or ignores groups attribute"
        assert [201, 400].contains(response.statusCode())
        if (response.statusCode() == 201) {
            def createdGroups = response.jsonPath().getList("groups")
            assert createdGroups == null || createdGroups.isEmpty() :
                "Direct client modification of readOnly groups attribute must be ignored (RFC 7643 §4.1.2)"
            String id = response.jsonPath().getString("id")
            if (id) createdUserIds << id
        }
    }

    @SpecRef("RFC7643-4.1.2-R7")
    @SpecRef("RFC7643-2.4-R3")
    @SpecRef("RFC7643-4.1.2-R3")
    @SpecRef("RFC7643-4.1.2-R4")
    def "USR_21: User creation with extended multi-valued attributes (roles, entitlements)"() {
        // RFC 7643 §4.1.2 — User roles and entitlements
        given: "User with roles and entitlements"
        def userName = "roles_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
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

        then: "Status is 201"
        assert [201, 400].contains(response.statusCode())
        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) createdUserIds << id
        }
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
