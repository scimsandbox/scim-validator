package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import io.restassured.RestAssured
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 4 — PATCH Operations
 *
 * Validates granular PATCH operations per RFC 7644 §3.5.2.
 * DEVIATION: api.scim.dev only supports these User attributes:
 *   userName, externalId, name (complex), active, password, emails, groups, roles
 *   (no title, displayName, phoneNumbers, addresses, etc.)
 */
@Stepwise
class A4_PatchOperationsSpec extends ScimBaseSpec {

    @Shared String testUserId
    @Shared String testUserName
    @Shared String multiEmailUserId

    def setupSpec() {
        loadServiceProviderConfig()

        def response = createFullUser(
            enterprise: [employeeNumber: faker.number().digits(6)]
        )
        assert response.statusCode() == 201 : "Setup failed: ${response.body().asString()}"
        testUserId = response.jsonPath().getString("id")
        testUserName = response.jsonPath().getString("userName")
    }

    private Response patchUser(String userId, List<Map> operations) {
        def payload = buildPatchOp(operations)
        return scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .patch("/Users/${userId}")
    }

    // ─── PAT_01: Add attribute ──────────────────────────────────────────────

    def "PAT_01: PATCH add sets a new attribute value (externalId)"() {
        // RFC 7644 §3.5.2.1 — Add Operation
        when:
        def response = patchUser(testUserId, [
            [op: "add", path: "externalId", value: "EXT-001"]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("externalId") == "EXT-001"
    }

    // ─── PAT_02: Replace attribute ──────────────────────────────────────────

    def "PAT_02: PATCH replace updates name.givenName"() {
        // RFC 7644 §3.5.2.2 — Replace Operation
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "name.givenName", value: "PatchedFirst"]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("name.givenName") == "PatchedFirst"
    }

    // ─── PAT_03: Replace active ─────────────────────────────────────────────

    def "PAT_03: PATCH replace active deactivates user"() {
        // RFC 7644 §3.5.2.2 — Replace boolean attribute
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "active", value: false]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        !getResponse.jsonPath().getBoolean("active")
    }

    // ─── PAT_04: Remove attribute ───────────────────────────────────────────

    def "PAT_04: PATCH remove clears externalId"() {
        // RFC 7644 §3.5.2.3 — Remove Operation
        given:
        patchUser(testUserId, [[op: "add", path: "externalId", value: "TO-REMOVE"]])

        when:
        def response = patchUser(testUserId, [
            [op: "remove", path: "externalId"]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        def extId = getResponse.jsonPath().getString("externalId")
        extId == null || extId == ""
    }

    // ─── PAT_05: Filtered update (work email) ──────────────────────────────

    def "PAT_05: PATCH replace with filter updates only matching email"() {
        // RFC 7644 §3.5.2.2 — Replace with path filter
        given:
        String workEmail = "work_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String homeEmail = "home_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createFullUser(
            emails: [
                [value: workEmail, type: "work", primary: true],
                [value: homeEmail, type: "home", primary: false]
            ]
        )
        multiEmailUserId = createResponse.jsonPath().getString("id")

        when:
        String newWorkEmail = "newwork_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def response = patchUser(multiEmailUserId, [
            [op: "replace", path: 'emails[type eq "work"].value', value: newWorkEmail]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${multiEmailUserId}")
        def emails = getResponse.jsonPath().getList("emails")
        def workEntry = emails.find { it.type == "work" }
        workEntry != null
        workEntry.value == newWorkEmail
        def homeEntry = emails.find { it.type == "home" }
        homeEntry != null
        homeEntry.value == homeEmail
    }

    // ─── PAT_06: Add to multi-valued ────────────────────────────────────────

    def "PAT_06: PATCH add appends to multi-valued emails"() {
        // RFC 7644 §3.5.2.1 — Add to multi-valued
        given:
        String newEmail = "extra_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when:
        def response = patchUser(multiEmailUserId, [
            [op: "add", path: "emails", value: [[value: newEmail, type: "other"]]]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${multiEmailUserId}")
        def emails = getResponse.jsonPath().getList("emails")
        emails.size() >= 3
        emails.find { it.type == "other" && it.value == newEmail } != null
    }

    // ─── PAT_07: Remove from multi-valued with filter ───────────────────────

    def "PAT_07: PATCH remove with filter removes only matching email"() {
        // RFC 7644 §3.5.2.3 — Remove with path filter
        when:
        def response = patchUser(multiEmailUserId, [
            [op: "remove", path: 'emails[type eq "other"]']
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${multiEmailUserId}")
        def emails = getResponse.jsonPath().getList("emails")
        emails.find { it.type == "other" } == null
        emails.find { it.type == "work" } != null
        emails.find { it.type == "home" } != null
    }

    // ─── PAT_08: Invalid path ───────────────────────────────────────────────

    def "PAT_08: PATCH with invalid path returns error"() {
        // RFC 7644 §3.5.2 — Error handling for invalid paths
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "nonExistentAttr", value: "x"]
        ])

        then:
        // RFC expects 400 with scimType "noTarget" or "invalidPath"
        // TODO DEVIATION: api.scim.dev returns 404 with "Unknown path" message
        response.statusCode() in [400, 404]
    }

    // ─── PAT_09: Patch read-only attribute ──────────────────────────────────

    def "PAT_09: PATCH on read-only attribute id is rejected or ignored"() {
        // RFC 7644 §3.5.2 — Mutability enforcement
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "id", value: "new-id-12345"]
        ])

        then:
        if (response.statusCode() in [400, 403, 404]) {
            true
        } else {
            def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
            assert getResponse.statusCode() == 200
            assert getResponse.jsonPath().getString("id") == testUserId
        }
    }

    // ─── PAT_10: Patch enterprise extension ─────────────────────────────────

    def "PAT_10: PATCH enterprise extension attribute via URI path"() {
        // RFC 7644 §3.5.2 — Extension attribute PATCH
        when:
        def response = patchUser(testUserId, [
            [op: "replace",
             path: "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber",
             value: "EMP-999"]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.body().asString().contains("EMP-999")
    }

    def cleanupSpec() {
        [testUserId, multiEmailUserId].each { id ->
            if (id) deleteUser(id)
        }
        createdUserIds.each { id -> if (id) deleteUser(id) }
        createdUserIds.clear()
    }
}
