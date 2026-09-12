package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
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
 * RFC 7644 §3.1 (POST), §3.2 (GET), §3.3 (PUT), §3.6 (DELETE)
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

    def "USR_01: POST minimal user returns 201 with required meta fields and headers"() {
        // RFC 7644 §3.1 — Creating Resources, §3.14 — Response Headers
        given: "A minimal user payload with unique userName"
        minimalUserName = "usr01_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when: "POST to /Users"
        def response = createUser(userName: minimalUserName)
        minimalUserId = response.jsonPath().getString("id")

        then: "Status is 201 Created"
        response.statusCode() == 201

        and: "Content-Type is application/scim+json"
        response.contentType().contains(SCIM_CONTENT_TYPE)

        and: "Response contains Location header matching resource location"
        def locationHeader = response.header("Location")
        locationHeader != null
        locationHeader.contains("/Users/${minimalUserId}")

        and: "Response contains id and core User schema"
        minimalUserId != null
        minimalUserId.length() > 0
        response.jsonPath().getList("schemas")?.contains(USER_SCHEMA)

        and: "Response contains meta with created, lastModified, location, resourceType"
        def meta = response.jsonPath().getMap("meta")
        meta != null
        meta.resourceType == "User"
        meta.created != null
        meta.lastModified != null
        meta.location != null
        meta.location == locationHeader
        // version (ETag) may or may not be present depending on server config
    }

    // ─── USR_02: POST full Enterprise User ──────────────────────────────────

    def "USR_02: POST full Enterprise User with extension attributes"() {
        // RFC 7644 §3.1 + RFC 7643 §4.3 — Enterprise User Extension
        given: "Enterprise extension attributes"
        def empNum = "EMP-${faker.number().digits(6)}"
        def dept = "Engineering"

        when: "POST a full user with enterprise extension"
        def response = createFullUser(
            title: "Senior Engineer",
            enterprise: [
                employeeNumber: empNum,
                department: dept
            ]
        )
        fullUserId = response.jsonPath().getString("id")

        then: "Status is 201 Created"
        response.statusCode() == 201

        and: "Response includes Enterprise extension URI in schemas"
        def schemas = response.jsonPath().getList("schemas")
        schemas.contains(ENTERPRISE_USER_SCHEMA)

        and: "Response preserves nested attributes"
        fullUserId != null
        response.jsonPath().getString("name.givenName") != null
        response.jsonPath().getString("name.familyName") != null

        and: "Emails round-trip correctly"
        def emails = response.jsonPath().getList("emails")
        emails != null
        emails.size() >= 2

        and: "Enterprise extension attributes are present in POST response"
        def postEnterprise = response.jsonPath().getMap("'${ENTERPRISE_USER_SCHEMA}'") ?:
            response.jsonPath().getMap(ENTERPRISE_USER_SCHEMA)
        postEnterprise != null
        postEnterprise.employeeNumber == empNum
        postEnterprise.department == dept

        when: "GET /Users/{id} to verify enterprise extension persistence"
        def getResponse = scimRequest().get("/Users/${fullUserId}")

        then: "Status is 200 and enterprise extension attributes match"
        getResponse.statusCode() == 200
        def getEnterprise = getResponse.jsonPath().getMap("'${ENTERPRISE_USER_SCHEMA}'") ?:
            getResponse.jsonPath().getMap(ENTERPRISE_USER_SCHEMA)
        getEnterprise != null
        getEnterprise.employeeNumber == empNum
        getEnterprise.department == dept
    }

    // ─── USR_03: GET by ID ──────────────────────────────────────────────────

    def "USR_03: GET user by ID returns the created user"() {
        // RFC 7644 §3.2 — Retrieving a Known Resource
        when: "GET /Users/{id} for the minimal user"
        def response = scimRequest()
            .get("/Users/${minimalUserId}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Content-Type is application/scim+json"
        response.contentType().contains(SCIM_CONTENT_TYPE)

        and: "Returned user matches created state"
        response.jsonPath().getString("id") == minimalUserId
        response.jsonPath().getString("userName") == minimalUserName
        response.jsonPath().getList("schemas")?.contains(USER_SCHEMA)

        and: "Meta fields are present"
        def meta = response.jsonPath().getMap("meta")
        meta != null
        meta.resourceType == "User"
        meta.created != null
        meta.location != null
    }

    // ─── USR_04: GET non-existent user ──────────────────────────────────────

    def "USR_04: GET non-existent user returns 404 with SCIM Error schema"() {
        // RFC 7644 §3.2, §3.12 — Error handling for unknown resource
        when: "GET /Users with a fake UUID"
        def response = scimRequest()
            .get("/Users/nonexistent-uuid-00000000-0000-0000-0000-000000000000")

        then: "Status is 404"
        response.statusCode() == 404

        and: "Response conforms to SCIM Error schema"
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "404"
    }

    // ─── USR_05: PUT full replacement ───────────────────────────────────────

    def "USR_05: PUT replaces the resource, omitted attributes are cleared"() {
        // RFC 7644 §3.3 — Replacing with PUT
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

        and: "Omitted attributes are cleared in PUT response"
        // RFC 7644 §3.3: PUT replaces the entire resource; omitted attributes must be removed
        putResponse.jsonPath().getString("title") == null
        putResponse.jsonPath().getString("nickName") == null

        when: "Subsequent GET /Users/{id}"
        def getResponse = scimRequest().get("/Users/${putTestUserId}")

        then: "Omitted attributes remain cleared in persisted state"
        getResponse.statusCode() == 200
        getResponse.jsonPath().getString("title") == null
        getResponse.jsonPath().getString("nickName") == null
        getResponse.jsonPath().getString("name.givenName") == "UpdatedFirst"
    }

    // ─── USR_06: PUT immutability ───────────────────────────────────────────

    def "USR_06: PUT with different id value is rejected or ignored"() {
        // RFC 7644 §3.3, RFC 7643 §2.2 — ReadOnly/Immutable attribute modification
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
            // RFC 7644 §3.3: Rejected with 400 mutability
            assert putResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
            assertScimType(putResponse, "mutability")
        } else if (putResponse.statusCode() == 200) {
            // RFC 7643 §2.2: Server ignored the readOnly attribute modification — id remains unchanged
            assert putResponse.jsonPath().getString("id") == putImmutTestUserId : "id must not change via PUT"
            def getResponse = scimRequestQuiet().get("/Users/${putImmutTestUserId}")
            assert getResponse.jsonPath().getString("id") == putImmutTestUserId : "id must remain unchanged"
        } else if (putResponse.statusCode() == 403) {
            // Documented vendor deviation (e.g. api.scim.dev)
            ScimOutput.println "DEVIATION: Server returned 403 Forbidden for immutable/readOnly id change instead of 400 mutability or ignoring"
            def getResponse = scimRequestQuiet().get("/Users/${putImmutTestUserId}")
            assert getResponse.jsonPath().getString("id") == putImmutTestUserId : "id must remain unchanged"
        } else {
            assert false : "Unexpected status ${putResponse.statusCode()} for PUT with modified id"
        }
    }

    // ─── USR_07: DELETE standard ────────────────────────────────────────────

    def "USR_07: DELETE user returns 204 with no body"() {
        // RFC 7644 §3.6 — Deleting Resources
        given: "Create a user to delete"
        def createResponse = createUser()
        deleteTestUserId = createResponse.jsonPath().getString("id")

        when: "DELETE /Users/{id}"
        def deleteResponse = scimRequestQuiet()
            .delete("/Users/${deleteTestUserId}")

        then: "Status is 204 No Content"
        deleteResponse.statusCode() == 204
    }

    // ─── USR_08: DELETE verification ────────────────────────────────────────

    def "USR_08: GET after DELETE returns 404"() {
        // RFC 7644 §3.6 — Verify deletion
        when: "GET the deleted user"
        def getResponse = scimRequestQuiet()
            .get("/Users/${deleteTestUserId}")

        then: "Status is 404 with SCIM Error schema"
        getResponse.statusCode() == 404
        getResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        getResponse.jsonPath().getString("status") == "404"
    }

    // ─── USR_09: DELETE non-existent ────────────────────────────────────────

    def "USR_09: DELETE already-deleted user returns 404"() {
        // RFC 7644 §3.6 — Deleting a non-existent resource
        when: "DELETE the same user again"
        def deleteResponse = scimRequestQuiet()
            .delete("/Users/${deleteTestUserId}")

        then: "Status is 404 with SCIM Error schema"
        deleteResponse.statusCode() == 404
        deleteResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        deleteResponse.jsonPath().getString("status") == "404"
    }

    // ─── USR_10: GET /Users list contains created user ──────────────────────

    def "USR_10: GET /Users list contains a created user"() {
        // RFC 7644 §3.4.2 — Query Resources
        given: "A new user is created"
        def userName = "list-check-${faker.name().username()}-${System.currentTimeMillis()}"
        def response = createUser(userName: userName)
        def userId = response.jsonPath().getString("id")

        when: "GET /Users with filter for the created userName"
        def listResponse = scimRequest()
            .queryParam("filter", "userName eq \"${userName}\"")
            .get("/Users")

        then: "Status is 200 and list response contains the user"
        listResponse.statusCode() == 200
        def body = listResponse.jsonPath()
        body.getInt("totalResults") >= 1

        def resources = body.getList("Resources")
        resources != null
        resources.any { it.id == userId }

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_11: GET /Me Authenticated Subject Alias ────────────────────────

    def "USR_11: GET /Me returns the authenticated subject"() {
        // RFC 7644 §3.11 — /Me Authenticated Subject Alias
        // Reference: scim2-compliance-test-suite MeTest
        when: "GET /Me"
        def response = scimRequest()
            .get("/Me")

        then: "Server returns 501 Not Implemented per RFC 7644 §3.11"
        // RFC 7644 §3.11: A service provider that does NOT support /Me SHOULD respond with 501.
        response.statusCode() == 501

        and: "Response follows SCIM error schema"
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "501"
    }

    // ─── USR_12: POST without required userName ─────────────────────────────

    def "USR_12: POST user without required userName returns 400 Bad Request"() {
        // RFC 7644 §3.12, RFC 7643 §4.1 — Missing required attribute
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
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "400"
    }

    // ─── USR_13: POST duplicate userName ────────────────────────────────────

    def "USR_13: POST user with duplicate userName returns 409 Conflict"() {
        // RFC 7644 §3.3.1, §3.12 — Uniqueness conflict
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
        // userName IS uniqueness "server" per RFC 7643 §7, so 409 is mandatory here; the
        // scimType keyword itself stays optional per RFC 7644 §3.12.
        assertScimError(duplicateResponse, 409, "uniqueness")

        cleanup:
        if (firstUserId) {
            try { scimRequestQuiet().delete("/Users/${firstUserId}") } catch (Exception ignored) {}
        }
    }

    // ─── USR_14: GET /Users/{id} attribute selection ────────────────────────

    def "USR_14: GET user by ID supports attributes and excludedAttributes projections"() {
        // RFC 7644 §3.9 — Attribute Selection on direct resource retrieval
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

    def "USR_15: PUT to non-existent user ID returns 404 Not Found"() {
        // RFC 7644 §3.3 — Replacing non-existent resource
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
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "404"
    }

    // ─── USR_16: PUT optimistic concurrency with If-Match ───────────────────

    def "USR_16: PUT supports optimistic locking with If-Match"() {
        // RFC 7644 §3.13, §3.14 — Versioning and Concurrency
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

        then: "Server supporting ETags returns 412 Precondition Failed, or 200 if ETags unsupported"
        if (etag != null) {
            assert mismatchResponse.statusCode() == 412
            assert mismatchResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        } else {
            assert mismatchResponse.statusCode() in [200, 412]
        }

        when: "PUT with valid/matching If-Match header (if supported)"
        def matchResponse = scimRequestQuiet()
            .header("If-Match", etag ?: '*')
            .body(JsonOutput.toJson(mismatchPayload))
            .put("/Users/${userId}")

        then: "Update succeeds"
        // RFC 7644 §3.14 recommends weak ETags while RFC 7232 §3.1 mandates strong comparison
        // for If-Match. Servers that apply strong comparison literally reject their own weak
        // validator, so a 412 here is reported as a deviation rather than failing the suite.
        if (etag != null) {
            assert matchResponse.statusCode() in [200, 412] :
                "If-Match with the current ETag must be honoured or refused, got ${matchResponse.statusCode()}"
            if (matchResponse.statusCode() == 200) {
                assert matchResponse.jsonPath().getString("displayName") == "Concurrency Test Stale"
            } else {
                ScimOutput.println "DEVIATION: Server rejected If-Match carrying its own current ETag " +
                    "'${etag}' with 412 (strong comparison applied to a weak validator, RFC 7232 §3.1)"
            }
        }

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── Cleanup ────────────────────────────────────────────────────────────

    def cleanupSpec() {
        // Delete all users created during this spec (defensive cleanup)
        [minimalUserId, fullUserId, putTestUserId, putImmutTestUserId].each { id ->
            if (id) {
                try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
            }
        }
        // deleteTestUserId already deleted in USR_07
        createdUserIds.each { id ->
            if (id) {
                try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
            }
        }
        createdUserIds.clear()
    }
}
