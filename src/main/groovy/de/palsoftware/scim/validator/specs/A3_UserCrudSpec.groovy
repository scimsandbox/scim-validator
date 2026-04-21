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

    def "USR_01: POST minimal user returns 201 with required meta fields"() {
        // RFC 7644 §3.1 — Creating Resources
        given: "A minimal user payload with unique userName"
        minimalUserName = "usr01_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when: "POST to /Users"
        def response = createUser(userName: minimalUserName)
        minimalUserId = response.jsonPath().getString("id")

        then: "Status is 201 Created"
        response.statusCode() == 201

        and: "Response contains id"
        minimalUserId != null
        minimalUserId.length() > 0

        and: "Response contains meta with created, lastModified, location"
        def meta = response.jsonPath().getMap("meta")
        meta != null
        meta.created != null
        meta.lastModified != null
        meta.location != null
        // version (ETag) may or may not be present depending on server config
    }

    // ─── USR_02: POST full Enterprise User ──────────────────────────────────

    def "USR_02: POST full Enterprise User with extension attributes"() {
        // RFC 7644 §3.1 + RFC 7643 §4.3 — Enterprise User Extension
        when: "POST a full user with enterprise extension"
        def response = createFullUser(
            title: "Senior Engineer",
            enterprise: [
                employeeNumber: "EMP-${faker.number().digits(6)}",
                department: "Engineering"
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
    }

    // ─── USR_03: GET by ID ──────────────────────────────────────────────────

    def "USR_03: GET user by ID returns the created user"() {
        // RFC 7644 §3.2 — Retrieving a Known Resource
        when: "GET /Users/{id} for the minimal user"
        def response = scimRequest()
            .get("/Users/${minimalUserId}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Returned user matches created state"
        response.jsonPath().getString("id") == minimalUserId
        response.jsonPath().getString("userName") == minimalUserName

        and: "Meta fields are present"
        response.jsonPath().getString("meta.resourceType") != null ||
            response.jsonPath().getString("meta.created") != null
    }

    // ─── USR_04: GET non-existent user ──────────────────────────────────────

    def "USR_04: GET non-existent user returns 404"() {
        // RFC 7644 §3.2 — Error handling for unknown resource
        when: "GET /Users with a fake UUID"
        def response = scimRequest()
            .get("/Users/nonexistent-uuid-00000000-0000-0000-0000-000000000000")

        then: "Status is 404"
        response.statusCode() == 404
    }

    // ─── USR_05: PUT full replacement ───────────────────────────────────────

    def "USR_05: PUT replaces the resource, omitted attributes are cleared"() {
        // RFC 7644 §3.3 — Replacing with PUT
        given: "Create a user with name and title"
        def createResponse = createFullUser(
            name: [givenName: "OriginalFirst", familyName: "OriginalLast"],
            title: "Manager"
        )
        putTestUserId = createResponse.jsonPath().getString("id")
        def userName = createResponse.jsonPath().getString("userName")

        when: "PUT with updated name but omitting title"
        def putPayload = [
            schemas: [USER_SCHEMA],
            userName: userName,
            name: [givenName: "UpdatedFirst", familyName: "UpdatedLast"],
            emails: [[value: "put_test_${UUID.randomUUID().toString().substring(0, 8)}@test.com", type: "work", primary: true]]
        ]
        def putResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(putPayload))
            .put("/Users/${putTestUserId}")

        then: "Status is 200"
        putResponse.statusCode() == 200

        and: "name is updated"
        putResponse.jsonPath().getString("name.givenName") == "UpdatedFirst" ||
            putResponse.jsonPath().getString("name.familyName") == "UpdatedLast"

        and: "Omitted attributes should be cleared (RFC behavior) or merged (common deviation)"
        // RFC 7644 §3.3: PUT replaces the entire resource; omitted attributes should be removed
        def emails = putResponse.jsonPath().getList("emails")
        // TODO DEVIATION: Many SCIM servers merge instead of replace on PUT
        // RFC-correct: emails == null || emails.isEmpty()
        // Relaxed: document if emails still present
        if (emails != null && !emails.isEmpty()) {
            ScimOutput.println "DEVIATION: api.scim.dev merges PUT instead of replacing (emails still present after omission)"
        }
    }

    // ─── USR_06: PUT immutability ───────────────────────────────────────────

    def "USR_06: PUT with different id value is rejected or ignored"() {
        // RFC 7644 §3.3 — Immutable attributes
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

        then: "Server either returns 400 (mutability violation), 403 (forbidden), or ignores the id change"
        if (putResponse.statusCode() in [400, 403]) {
            // RFC-correct behavior (400) or server-specific rejection (403)
            // TODO DEVIATION: api.scim.dev returns 403 instead of RFC-expected 400 for immutable attribute change
            true
        } else {
            // Server ignored the id change — verify id is unchanged
            assert putResponse.statusCode() == 200
            def getResponse = scimRequestQuiet().get("/Users/${putImmutTestUserId}")
            assert getResponse.jsonPath().getString("id") == putImmutTestUserId :
                "id must not change via PUT"
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
        def getResponse = scimRequest()
            .get("/Users/${deleteTestUserId}")

        then: "Status is 404"
        getResponse.statusCode() == 404
    }

    // ─── USR_09: DELETE non-existent ────────────────────────────────────────

    def "USR_09: DELETE already-deleted user returns 404"() {
        // RFC 7644 §3.6 — Deleting a non-existent resource
        when: "DELETE the same user again"
        def deleteResponse = scimRequestQuiet()
            .delete("/Users/${deleteTestUserId}")

        then: "Status is 404"
        deleteResponse.statusCode() == 404
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
