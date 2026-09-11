package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
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
        if (response.statusCode() == 400) {
            assert response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
            assert response.jsonPath().getString("scimType") in ["invalidPath", "noTarget", null]
        } else if (response.statusCode() == 404) {
            ScimOutput.println "DEVIATION: Server returned 404 instead of RFC-expected 400 with scimType 'invalidPath'/'noTarget' for invalid PATCH path"
        } else {
            assert false : "Unexpected status ${response.statusCode()} for PATCH with invalid path"
        }
    }

    // ─── PAT_09: Patch read-only attribute ──────────────────────────────────

    def "PAT_09: PATCH on read-only attribute id is rejected or ignored"() {
        // RFC 7644 §3.5.2, RFC 7643 §2.2 — Mutability enforcement
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "id", value: "new-id-12345"]
        ])

        then:
        if (response.statusCode() == 400) {
            assert response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
            assert response.jsonPath().getString("scimType") in ["mutability", null]
        } else if (response.statusCode() == 200) {
            def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
            assert getResponse.statusCode() == 200
            assert getResponse.jsonPath().getString("id") == testUserId
        } else if (response.statusCode() in [403, 404]) {
            ScimOutput.println "DEVIATION: Server returned ${response.statusCode()} for readOnly attribute change instead of 400 mutability or ignoring"
        } else {
            assert false : "Unexpected status ${response.statusCode()} for PATCH with readOnly id"
        }
    }

    // ─── PAT_10: Patch enterprise extension ─────────────────────────────────

    def "PAT_10: PATCH enterprise extension attribute via URI path"() {
        // RFC 7644 §3.5.2, RFC 7643 §4.3 — Extension attribute PATCH
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
        def ent = getResponse.jsonPath().getMap("'${ENTERPRISE_USER_SCHEMA}'") ?:
            getResponse.jsonPath().getMap(ENTERPRISE_USER_SCHEMA)
        ent != null
        ent.employeeNumber == "EMP-999"
    }

    // ─── PAT_11: Replace complex attribute without path ──────────────────────

    def "PAT_11: PATCH replace without path updates complex name object"() {
        // RFC 7644 §3.5.2.1 / §3.5.2.3 — Complex attribute replace without path
        given:
        // Ensure initial name has known givenName and familyName
        patchUser(testUserId, [
            [op: "replace", path: "name.givenName", value: "InitialFirst"],
            [op: "replace", path: "name.familyName", value: "InitialLast"]
        ])

        when:
        def response = patchUser(testUserId, [
            [op: "replace", value: [
                name: [
                    familyName: "UpdatedLast",
                    formatted : "InitialFirst UpdatedLast"
                ]
            ]]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("name.familyName") == "UpdatedLast"
        getResponse.jsonPath().getString("name.formatted") == "InitialFirst UpdatedLast"
        // Unspecified sub-attribute (givenName) must be preserved per RFC 7644 §3.5.2.1/3.5.2.3
        getResponse.jsonPath().getString("name.givenName") == "InitialFirst"
    }

    // ─── PAT_12: Replace complex attribute with path ─────────────────────────

    def "PAT_12: PATCH replace with path name updates complex name object"() {
        // RFC 7644 §3.5.2.3 — Complex attribute replace with path
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "name", value: [
                familyName: "PathLast"
            ]]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("name.familyName") == "PathLast"
        getResponse.jsonPath().getString("name.givenName") == "InitialFirst"
    }

    // ─── PAT_13: Replace multi-valued attribute without path ─────────────────

    def "PAT_13: PATCH replace without path replaces multi-valued attribute collection"() {
        // RFC 7644 §3.5.2.3 — Replace multi-valued attribute without filter replaces all values
        given:
        String email1 = "rep1_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String email2 = "rep2_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createFullUser(
            emails: [
                [value: email1, type: "work", primary: true],
                [value: email2, type: "home", primary: false]
            ]
        )
        String userId = createResponse.jsonPath().getString("id")

        when:
        String newEmail = "sole_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def response = patchUser(userId, [
            [op: "replace", value: [
                emails: [
                    [value: newEmail, type: "work", primary: true]
                ]
            ]]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${userId}")
        def emails = getResponse.jsonPath().getList("emails")
        emails.size() == 1
        emails[0].value == newEmail

        cleanup:
        if (userId) deleteUser(userId)
    }

    // ─── PAT_14: Remove complex attribute ────────────────────────────────────

    def "PAT_14: PATCH remove on complex attribute name clears all name sub-attributes"() {
        // RFC 7644 §3.5.2.2 — Remove on complex attribute removes all sub-attributes
        when:
        def response = patchUser(testUserId, [
            [op: "remove", path: "name"]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        def nameObj = getResponse.jsonPath().get("name")
        nameObj == null || (nameObj.familyName == null && nameObj.givenName == null && nameObj.formatted == null)
    }

    // ─── PAT_15: Multi-operation batch ──────────────────────────────────────

    def "PAT_15: PATCH multi-operation sequential batch applies all operations"() {
        // RFC 7644 §3.5.2 — Operations sequence
        when:
        def response = patchUser(testUserId, [
            [op: "add", path: "title", value: "Principal Architect"],
            [op: "replace", path: "name.givenName", value: "MultiBatchFirst"],
            [op: "replace", path: "active", value: false]
        ])

        then:
        response.statusCode() == 200

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.statusCode() == 200
        getResponse.jsonPath().getString("title") == "Principal Architect"
        getResponse.jsonPath().getString("name.givenName") == "MultiBatchFirst"
        !getResponse.jsonPath().getBoolean("active")
    }

    // ─── PAT_16: Atomic batch rollback on failure ───────────────────────────

    def "PAT_16: PATCH failure rolls back earlier operations in the same request"() {
        // RFC 7644 §3.5.2 — Atomic execution
        given: "Ensure a known displayName"
        patchUser(testUserId, [[op: "replace", path: "displayName", value: "StableNameBeforeRollback"]])
        def initialGet = scimRequestQuiet().get("/Users/${testUserId}")
        assert initialGet.jsonPath().getString("displayName") == "StableNameBeforeRollback"

        when: "Execute batch with valid first operation and invalid second operation"
        def response = patchUser(testUserId, [
            [op: "replace", path: "displayName", value: "ShouldRollBackName"],
            [op: "replace", path: "invalidTargetLocationAttrXYZ", value: "fail"]
        ])

        then: "Request fails with 400 (or deviation 404)"
        response.statusCode() in [400, 404]

        and: "Valid operation from step 1 was not persisted (atomic rollback)"
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("displayName") == "StableNameBeforeRollback"
    }

    // ─── PAT_17: Case-insensitive op keywords ───────────────────────────────

    def "PAT_17: PATCH accepts case-insensitive op keywords (Add, REPLACE, Remove)"() {
        // RFC 7644 §3.5.2 — op attribute is not case sensitive
        when: "Execute mixed/uppercase op keywords"
        def addResp = patchUser(testUserId, [
            [op: "Add", path: "nickName", value: "CaseNick"]
        ])
        def repResp = patchUser(testUserId, [
            [op: "REPLACE", path: "title", value: "Distinguished Engineer"]
        ])
        def remResp = patchUser(testUserId, [
            [op: "Remove", path: "nickName"]
        ])

        then: "All operations succeed"
        addResp.statusCode() == 200
        repResp.statusCode() == 200
        remResp.statusCode() == 200

        and: "Final state matches"
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("title") == "Distinguished Engineer"
        getResponse.jsonPath().getString("nickName") == null
    }

    // ─── PAT_18: Dotted syntax in pathless PATCH ─────────────────────────────

    def "PAT_18: PATCH replace without path supports dotted sub-attributes in value map"() {
        // Microsoft Entra ID (Azure AD) interoperability pattern
        when: "Pathless replace with dotted keys in value"
        def response = patchUser(testUserId, [
            [op: "replace", value: [
                "name.givenName" : "EntraFirst",
                "name.familyName": "EntraLast"
            ]]
        ])

        then: "Status is 200"
        response.statusCode() == 200

        and: "Sub-attributes are updated"
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("name.givenName") == "EntraFirst"
        getResponse.jsonPath().getString("name.familyName") == "EntraLast"
    }

    // ─── PAT_19: Alternate group member removal ─────────────────────────────

    def "PAT_19: PATCH remove group member with path 'members' and value list"() {
        // RFC 7644 §3.5.2.3 / Microsoft Entra ID interop: remove member by value list
        given: "A group with two members"
        def user1 = createUser()
        def user2 = createUser()
        def user1Id = user1.jsonPath().getString("id")
        def user2Id = user2.jsonPath().getString("id")
        def groupResp = createGroup("PatchGroup_${UUID.randomUUID().toString().substring(0, 8)}", [user1Id, user2Id])
        assert groupResp.statusCode() == 201
        def grpId = groupResp.jsonPath().getString("id")

        when: "PATCH /Groups/{id} with path 'members' and value specifying user1"
        def patchPayload = buildPatchOp([
            [op: "remove", path: "members", value: [[value: user1Id]]]
        ])
        def patchResp = scimRequestQuiet()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Groups/${grpId}")

        then: "Status is 200"
        patchResp.statusCode() == 200

        and: "Specified member is removed (or all members cleared in servers with path matching deviation)"
        def getResponse = scimRequestQuiet().get("/Groups/${grpId}")
        def members = getResponse.jsonPath().getList("members")
        if (members == null || members.isEmpty()) {
            ScimOutput.println "DEVIATION: Server removed all members on path 'members' with value list instead of only specified member"
        } else {
            assert members.size() == 1
            assert members[0].value == user2Id
        }

        cleanup:
        if (grpId) {
            try { scimRequestQuiet().delete("/Groups/${grpId}") } catch (Exception ignored) {}
        }
        if (user1Id) {
            try { scimRequestQuiet().delete("/Users/${user1Id}") } catch (Exception ignored) {}
        }
        if (user2Id) {
            try { scimRequestQuiet().delete("/Users/${user2Id}") } catch (Exception ignored) {}
        }
    }

    // ─── PAT_20: PATCH optimistic concurrency with If-Match ─────────────────

    def "PAT_20: PATCH supports optimistic locking with If-Match"() {
        // RFC 7644 §3.13, §3.14 — Versioning and Concurrency on PATCH
        given: "A user created to test concurrency"
        def createResponse = createUser()
        def userId = createResponse.jsonPath().getString("id")
        def etag = createResponse.header("ETag") ?: createResponse.jsonPath().getString("meta.version")

        when: "PATCH with mismatched If-Match header"
        def mismatchPayload = buildPatchOp([
            [op: "replace", path: "displayName", value: "Stale Patch Name"]
        ])
        def mismatchResponse = scimRequestQuiet()
            .header("If-Match", 'W/"999999"')
            .body(JsonOutput.toJson(mismatchPayload))
            .patch("/Users/${userId}")

        then: "Server supporting ETags returns 412 Precondition Failed, or 200 if unsupported"
        if (etag != null) {
            assert mismatchResponse.statusCode() == 412
            assert mismatchResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        } else {
            assert mismatchResponse.statusCode() in [200, 412]
        }

        when: "PATCH with matching/valid If-Match"
        def matchResponse = scimRequestQuiet()
            .header("If-Match", etag ?: '*')
            .body(JsonOutput.toJson(mismatchPayload))
            .patch("/Users/${userId}")

        then: "Update succeeds"
        if (etag != null) {
            assert matchResponse.statusCode() == 200
            assert matchResponse.jsonPath().getString("displayName") == "Stale Patch Name"
        }

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── PAT_21: Remove multi-valued collection without filter ──────────────

    def "PAT_21: PATCH remove on multi-valued collection without path filter clears all values"() {
        // RFC 7644 §3.5.2.3 — Remove multi-valued attribute without filter
        given: "User with multiple emails"
        def user = createFullUser(
            emails: [
                [value: "clear1_${UUID.randomUUID().toString().substring(0, 8)}@test.com", type: "work", primary: true],
                [value: "clear2_${UUID.randomUUID().toString().substring(0, 8)}@test.com", type: "home", primary: false]
            ]
        )
        def userId = user.jsonPath().getString("id")
        assert user.statusCode() == 201

        when: "PATCH remove with path: 'emails'"
        def patchResp = patchUser(userId, [
            [op: "remove", path: "emails"]
        ])

        then: "Status is 200"
        patchResp.statusCode() == 200

        and: "Emails collection is cleared"
        def getResponse = scimRequestQuiet().get("/Users/${userId}")
        def emails = getResponse.jsonPath().getList("emails")
        emails == null || emails.isEmpty()

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    def cleanupSpec() {
        [testUserId, multiEmailUserId].each { id ->
            if (id) deleteUser(id)
        }
        createdUserIds.each { id -> if (id) deleteUser(id) }
        createdUserIds.clear()
    }
}
