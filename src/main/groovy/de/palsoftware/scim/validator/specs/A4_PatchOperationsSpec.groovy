package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import spock.lang.Shared

/**
 * Area 4 — PATCH Operations
 *
 * Validates granular PATCH operations per RFC 7644 §3.5.2.
 * Note: §3.5.2.1 is Add, §3.5.2.2 is Remove, §3.5.2.3 is Replace.
 */
@RequiresCapability("patch")
class A4_PatchOperationsSpec extends ScimBaseSpec {

    @Shared String testUserId
    @Shared String testUserName
    @Shared String multiEmailUserId

    def setupSpec() {
        requireCapability("patch")
        loadServiceProviderConfig()

        def response = createFullUser(includeEnterprise: false)
        assert response.statusCode() == 201 : "Setup failed: ${response.body().asString()}"
        testUserId = response.jsonPath().getString("id")
        testUserName = response.jsonPath().getString("userName")
    }

    protected String ensureTestUser() {
        if (testUserId != null) {
            def getResp = scimRequestQuiet().get("/Users/${testUserId}")
            if (getResp.statusCode() == 200) {
                return testUserId
            }
        }
        def response = createFullUser(includeEnterprise: false)
        if (response.statusCode() == 201) {
            testUserId = response.jsonPath().getString("id")
            testUserName = response.jsonPath().getString("userName")
        }
        return testUserId
    }

    protected String ensureMultiEmailUser() {
        if (multiEmailUserId != null) {
            def getResp = scimRequestQuiet().get("/Users/${multiEmailUserId}")
            if (getResp.statusCode() == 200) {
                return multiEmailUserId
            }
        }
        String workEmail = "work_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String homeEmail = "home_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createFullUser(
            emails: [
                [value: workEmail, type: "work", primary: true],
                [value: homeEmail, type: "home", primary: false]
            ]
        )
        if (createResponse.statusCode() == 201) {
            multiEmailUserId = createResponse.jsonPath().getString("id")
        }
        return multiEmailUserId
    }

    protected String ensureMultiEmailUserWithOther() {
        if (multiEmailUserId != null) {
            def getResp = scimRequestQuiet().get("/Users/${multiEmailUserId}")
            if (getResp.statusCode() == 200) {
                def emails = getResp.jsonPath().getList("emails")
                if (emails?.any { it.type == "other" }) {
                    return multiEmailUserId
                }
                String otherEmail = "extra_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
                patchUser(multiEmailUserId, [
                    [op: "add", path: "emails", value: [[value: otherEmail, type: "other"]]]
                ])
                return multiEmailUserId
            }
        }
        String workEmail = "work_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String homeEmail = "home_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String otherEmail = "extra_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def createResponse = createFullUser(
            emails: [
                [value: workEmail, type: "work", primary: true],
                [value: homeEmail, type: "home", primary: false],
                [value: otherEmail, type: "other"]
            ]
        )
        if (createResponse.statusCode() == 201) {
            multiEmailUserId = createResponse.jsonPath().getString("id")
        }
        return multiEmailUserId
    }

    def setup() {
        ensureTestUser()
    }

    private Response patchUser(String userId, List<Map> operations) {
        def payload = buildPatchOp(operations)
        return scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .patch("/Users/${userId}")
    }

    // ─── PAT_01: Add attribute ──────────────────────────────────────────────

    @SpecRef("RFC7644-3.5.2.1-R2")
    @SpecRef("RFC7644-3.5.2-R4")
    def "PAT_01: PATCH add sets a new attribute value (externalId)"() {
        // RFC 7644 §3.5.2.1 — Add Operation
        when:
        def response = patchUser(testUserId, [
            [op: "add", path: "externalId", value: "EXT-001"]
        ])

        then:
        response.statusCode() in [200, 204]
        if (response.statusCode() == 200) {
            assert response.jsonPath().getList("schemas")?.contains(USER_SCHEMA)
            assert response.jsonPath().getString("id") == testUserId
            assert response.jsonPath().getString("externalId") == "EXT-001"
        }

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("externalId") == "EXT-001"
    }

    // ─── PAT_02: Replace attribute ──────────────────────────────────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_02: PATCH replace updates name.givenName"() {
        // RFC 7644 §3.5.2.3 — Replace Operation
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "name.givenName", value: "PatchedFirst"]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("name.givenName") == "PatchedFirst"
    }

    // ─── PAT_03: Replace active ─────────────────────────────────────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_03: PATCH replace active deactivates user"() {
        // RFC 7644 §3.5.2.3 — Replace boolean attribute
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "active", value: false]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        assert getResponse.jsonPath().get("active") == false : "Expected 'active' to be false, got ${getResponse.jsonPath().get('active')}"
    }

    // ─── PAT_04: Remove attribute ───────────────────────────────────────────

    @SpecRef("RFC7644-3.5.2.2-R2")
    def "PAT_04: PATCH remove clears externalId"() {
        // RFC 7644 §3.5.2.2 — Remove Operation
        given:
        patchUser(testUserId, [[op: "add", path: "externalId", value: "TO-REMOVE"]])

        when:
        def response = patchUser(testUserId, [
            [op: "remove", path: "externalId"]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        def extId = getResponse.jsonPath().getString("externalId")
        extId == null || extId == ""
    }

    // ─── PAT_05: Filtered update (work email) ──────────────────────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_05: PATCH replace with filter updates only matching email"() {
        // RFC 7644 §3.5.2.3 — Replace with path filter
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
        response.statusCode() in [200, 204]

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

    @SpecRef("RFC7644-3.5.2.1-R2")
    def "PAT_06: PATCH add appends to multi-valued emails"() {
        // RFC 7644 §3.5.2.1 — Add to multi-valued
        given:
        ensureMultiEmailUser()
        String newEmail = "extra_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when:
        def response = patchUser(multiEmailUserId, [
            [op: "add", path: "emails", value: [[value: newEmail, type: "other"]]]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${multiEmailUserId}")
        def emails = getResponse.jsonPath().getList("emails")
        emails.size() >= 3
        emails.find { it.type == "other" && it.value == newEmail } != null
    }

    // ─── PAT_07: Remove from multi-valued with filter ───────────────────────

    @SpecRef("RFC7644-3.5.2.2-R2")
    def "PAT_07: PATCH remove with filter removes only matching email"() {
        // RFC 7644 §3.5.2.2 — Remove with path filter
        given: "Ensure multi-email user has other email"
        ensureMultiEmailUserWithOther()

        when:
        def response = patchUser(multiEmailUserId, [
            [op: "remove", path: 'emails[type eq "other"]']
        ])

        then:
        assert response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${multiEmailUserId}")
        def emails = getResponse.jsonPath().getList("emails")
        assert emails.find { it.type == "other" } == null
        assert emails.find { it.type == "work" } != null
        assert emails.find { it.type == "home" } != null
    }

    // ─── PAT_08: Invalid path returns error ──────────────────────────────────

    @SpecRef("RFC7644-3.12-R1")
    def "PAT_08: PATCH with invalid path returns error"() {
        // RFC 7644 §3.12 — Error Response Handling
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "invalidNonExistentPath.subField", value: "foo"]
        ])

        then:
        assert response.statusCode() in [400, 404]
        assertScimError(response, response.statusCode())
    }

    // ─── PAT_09: Mutability violation on read-only attribute ────────────────

    @SpecRef("RFC7643-2.2-R3")
    @SpecRef("RFC7643-3.1-R1")
    def "PAT_09: PATCH on read-only attribute id is rejected with 400 mutability"() {
        // RFC 7643 §2.2, §3.1 — Mutability violation
        when:
        def response = patchUser(testUserId, [
            [op: "replace", path: "id", value: "different-id"]
        ])

        then:
        assert response.statusCode() == 400 : "Expected 400 Bad Request for read-only id PATCH, got ${response.statusCode()}"
        assertScimErrorOptionalType(response, 400, "mutability")
    }

    // ─── PAT_10: Target attribute via full schema URN ───────────────────────

    @RequiresCapability("enterprise")
    @SpecRef("RFC7643-3-R2")
    def "PAT_10: PATCH enterprise extension attribute via URI path"() {
        // RFC 7643 §3 — Schema extension attribute path
        given: "A user created with enterprise extension"
        def entUserResp = createFullUser(includeEnterprise: true)
        assert entUserResp.statusCode() == 201
        String entUserId = entUserResp.jsonPath().getString("id")

        when:
        def response = patchUser(entUserId, [
            [
                op   : "replace",
                path : "${ENTERPRISE_USER_SCHEMA}:employeeNumber",
                value: "EMP-9999"
            ]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${entUserId}")
        def enterprise = getResponse.jsonPath().getMap("'${ENTERPRISE_USER_SCHEMA}'")
        enterprise.employeeNumber == "EMP-9999"
        getResponse.jsonPath().get("employeeNumber") == null

        cleanup:
        if (entUserId) {
            try { scimRequestQuiet().delete("/Users/${entUserId}") } catch (Exception ignored) {}
        }
    }

    // ─── PAT_11: Replace complex attribute without path ─────────────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_11: PATCH replace without path updates complex name object"() {
        // RFC 7644 §3.5.2.3 — Pathless Replace of complex attribute
        when:
        def response = patchUser(testUserId, [
            [
                op   : "replace",
                value: [name: [givenName: "PathlessFirst", familyName: "PathlessLast"]]
            ]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("name.givenName") == "PathlessFirst"
        getResponse.jsonPath().getString("name.familyName") == "PathlessLast"
    }

    // ─── PAT_12: Replace complex attribute with path ────────────────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_12: PATCH replace with path name updates complex name object"() {
        // RFC 7644 §3.5.2.3 — Replace with path targeting complex attribute
        when:
        def response = patchUser(testUserId, [
            [
                op   : "replace",
                path : "name",
                value: [givenName: "PathFirst", familyName: "PathLast"]
            ]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("name.givenName") == "PathFirst"
        getResponse.jsonPath().getString("name.familyName") == "PathLast"
    }

    // ─── PAT_13: Replace multi-valued collection without path ───────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_13: PATCH replace without path replaces multi-valued attribute collection"() {
        // RFC 7644 §3.5.2.3 — Pathless Replace of multi-valued attribute
        given:
        String freshEmail = "pathless_coll_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when:
        def response = patchUser(testUserId, [
            [
                op   : "replace",
                value: [emails: [[value: freshEmail, type: "work", primary: true]]]
            ]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        def emails = getResponse.jsonPath().getList("emails")
        emails.size() == 1
        emails[0].value == freshEmail
        emails[0].primary == true
    }

    // ─── PAT_14: Remove on complex attribute clears all sub-attributes ──────

    @SpecRef("RFC7644-3.5.2.2-R2")
    def "PAT_14: PATCH remove on complex attribute name clears all name sub-attributes"() {
        // RFC 7644 §3.5.2.2 — Remove entire complex attribute
        when:
        def response = patchUser(testUserId, [
            [op: "remove", path: "name"]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        def name = getResponse.jsonPath().getMap("name")
        name == null || name.every { k, v -> v == null }
    }

    // ─── PAT_15: Sequential multi-operation batch ───────────────────────────

    @SpecRef("RFC7644-3.5.2-R1")
    def "PAT_15: PATCH multi-operation sequential batch applies all operations"() {
        // RFC 7644 §3.5.2 — Batch operations applied in sequence
        when:
        def response = patchUser(testUserId, [
            [op: "add", path: "title", value: "Junior Engineer"],
            [op: "replace", path: "title", value: "Senior Engineer"],
            [op: "replace", path: "active", value: true],
            [op: "add", path: "name.givenName", value: "BatchFirst"]
        ])

        then:
        response.statusCode() in [200, 204]

        and:
        def getResponse = scimRequestQuiet().get("/Users/${testUserId}")
        getResponse.jsonPath().getString("title") == "Senior Engineer"
        getResponse.jsonPath().getBoolean("active") == true
        getResponse.jsonPath().getString("name.givenName") == "BatchFirst"
    }

    // ─── PAT_16: Atomic failure rollback ────────────────────────────────────

    @SpecRef("RFC7644-3.5.2-R1")
    def "PAT_16: PATCH failure rolls back earlier operations in the same request"() {
        // RFC 7644 §3.5.2 — "the server MUST apply the entire set of operations or none"
        //
        // The failing operation must fail at APPLY time, not at parse time: a server that
        // merely pre-validates paths before touching storage would pass a test built on a
        // bogus path without ever performing a rollback. A duplicate userName can only be
        // detected once the write is attempted, so it exercises the real transaction.
        given: "A target user and a second user whose userName will be collided with"
        def target = createFullUser(displayName: "StableNameBeforeRollback")
        assert target.statusCode() == 201
        def targetId = target.jsonPath().getString("id")
        def targetUserName = target.jsonPath().getString("userName")

        def other = createUser()
        assert other.statusCode() == 201
        def otherId = other.jsonPath().getString("id")
        def takenUserName = other.jsonPath().getString("userName")

        def initialGet = scimRequestQuiet().get("/Users/${targetId}")
        assert initialGet.jsonPath().getString("displayName") == "StableNameBeforeRollback"

        when: "Batch with a valid first operation and a second that fails when applied"
        def response = patchUser(targetId, [
            [op: "replace", path: "displayName", value: "ShouldRollBackName"],
            [op: "replace", path: "userName", value: takenUserName]
        ])

        then: "The request is rejected — userName uniqueness is 'server' per RFC 7643 §7"
        assert response.statusCode() in [400, 409] :
            "Duplicate userName must be rejected, got ${response.statusCode()}"

        and: "Neither operation was persisted (atomic rollback)"
        def getResponse = scimRequestQuiet().get("/Users/${targetId}")
        assert getResponse.jsonPath().getString("displayName") == "StableNameBeforeRollback"
        assert getResponse.jsonPath().getString("userName") == targetUserName

        cleanup:
        if (targetId) deleteUser(targetId)
        if (otherId) deleteUser(otherId)
    }

    // ─── PAT_17: Case-insensitive op keywords ───────────────────────────────

    @SpecRef("RFC7644-3.5.2-R3")
    def "PAT_17: PATCH accepts case-insensitive op keywords (Add, REPLACE, Remove)"() {
        // Vendor robustness check for op keyword casing
        given: "A user dedicated to this feature"
        def user = createFullUser()
        assert user.statusCode() == 201
        def userId = user.jsonPath().getString("id")

        when: "Execute mixed/uppercase op keywords"
        def addResp = patchUser(userId, [
            [op: "Add", path: "nickName", value: "CaseNick"]
        ])
        def afterAddResp = addResp.statusCode() == 200 ? addResp : (addResp.statusCode() == 204 ? scimRequestQuiet().get("/Users/${userId}") : null)
        def repResp = patchUser(userId, [
            [op: "REPLACE", path: "title", value: "Distinguished Engineer"]
        ])
        def afterRepResp = repResp.statusCode() == 200 ? repResp : (repResp.statusCode() == 204 ? scimRequestQuiet().get("/Users/${userId}") : null)
        def remResp = patchUser(userId, [
            [op: "Remove", path: "nickName"]
        ])

        then: "Operations succeed or return 400"
        assert [200, 204, 400].contains(addResp.statusCode())
        if (addResp.statusCode() in [200, 204]) {
            assert afterAddResp != null && afterAddResp.statusCode() == 200
            assert afterAddResp.jsonPath().getString("nickName") == "CaseNick" :
                "Mixed-case 'Add' operation must set nickName to 'CaseNick' before 'Remove' clears it"
            assert repResp.statusCode() in [200, 204]
            assert afterRepResp != null && afterRepResp.statusCode() == 200
            assert afterRepResp.jsonPath().getString("title") == "Distinguished Engineer"
            assert remResp.statusCode() in [200, 204]
            def getResp = scimRequestQuiet().get("/Users/${userId}")
            assert getResp.statusCode() == 200
            assert getResp.jsonPath().getString("title") == "Distinguished Engineer"
            assert getResp.jsonPath().get("nickName") == null
        } else {
            assertScimError(addResp, 400)
        }

        cleanup:
        if (userId) deleteUser(userId)
    }

    // ─── PAT_18: Dotted syntax in pathless PATCH ─────────────────────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_18: PATCH replace without path handles Entra ID dotted sub-attribute keys"() {
        // Microsoft Entra ID (Azure AD) interoperability pattern — NOT an RFC requirement.
        //
        // RFC 7644 §3.5.2.3 defines the pathless "value" map as attribute names, so a
        // strictly compliant server may reject or ignore "name.givenName". Supporting the
        // quirk is reported as interoperable; not supporting it is reported as a note.
        given: "A user dedicated to this feature"
        def user = createFullUser()
        assert user.statusCode() == 201
        def userId = user.jsonPath().getString("id")
        String origGivenName = user.jsonPath().getString("name.givenName")
        String origFamilyName = user.jsonPath().getString("name.familyName")

        when: "Pathless replace with dotted keys in value"
        def response = patchUser(userId, [
            [op: "replace", value: [
                "name.givenName" : "EntraFirst",
                "name.familyName": "EntraLast"
            ]]
        ])

        then: "The server either applies the dotted keys or rejects/ignores them cleanly"
        assert response.statusCode() in [200, 204, 400]

        and: "When accepted, the sub-attributes are actually updated (or preserved intact if ignored)"
        if (response.statusCode() == 400) {
            assertScimError(response, 400)
        } else {
            def getResponse = scimRequestQuiet().get("/Users/${userId}")
            assert getResponse.statusCode() == 200
            if (response.statusCode() == 200 && response.jsonPath().getString("name.givenName") == "EntraFirst") {
                assert response.jsonPath().getString("name.familyName") == "EntraLast"
            }
            String actualGiven = getResponse.jsonPath().getString("name.givenName")
            String actualFamily = getResponse.jsonPath().getString("name.familyName")
            if (actualGiven == "EntraFirst" || actualFamily == "EntraLast") {
                assert actualGiven == "EntraFirst" && actualFamily == "EntraLast" :
                    "Dotted keys in pathless PATCH must be applied consistently to both name.givenName and name.familyName"
            } else {
                assert actualGiven == origGivenName && actualFamily == origFamilyName :
                    "When server ignores non-standard dotted keys in pathless PATCH, existing name sub-attributes must remain intact"
            }
        }

        cleanup:
        if (userId) deleteUser(userId)
    }

    // ─── PAT_19: Alternate group member removal ─────────────────────────────

    @SpecRef("RFC7644-3.5.2.2-R2")
    def "PAT_19: PATCH remove group member with path 'members' and value list"() {
        // RFC 7644 §3.5.2.2 / Microsoft Entra ID interop: remove member by value list
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

        then: "Status is 200, 204, or 400 (RFC 7644 §3.5.2.2 standard vs vendor interop)"
        assert patchResp.statusCode() in [200, 204, 400]

        and: "When accepted, specified member is removed (or all members cleared per RFC 7644 §3.5.2.2 when path has no filter)"
        if (patchResp.statusCode() in [200, 204]) {
            def getResponse = scimRequestQuiet().get("/Groups/${grpId}")
            def members = getResponse.jsonPath().getList("members")
            if (members != null && !members.isEmpty()) {
                assert members.size() == 1
                assert members[0].value == user2Id
            }
        } else {
            assertScimError(patchResp, 400)
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

    // ─── PAT_20: Optimistic concurrency with If-Match ───────────────────────

    @RequiresCapability("etag")
    @SpecRef("RFC7644-3.14-R1")
    @SpecRef("RFC7644-3.14-R2")
    def "PAT_20: PATCH supports optimistic locking with If-Match"() {
        requireCapability("etag")
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

        then: "Server supporting ETags returns 412 Precondition Failed"
        assert etag != null : "Server advertising etag.supported=true must return ETag header or meta.version (RFC 7644 §3.14)"
        assert mismatchResponse.statusCode() == 412
        assertScimError(mismatchResponse, 412)

        when: "PATCH with matching/valid If-Match"
        def matchResponse = scimRequestQuiet()
            .header("If-Match", etag)
            .body(JsonOutput.toJson(mismatchPayload))
            .patch("/Users/${userId}")

        then: "Update succeeds"
        if (etag != null) {
            assert matchResponse.statusCode() in [200, 204] :
                "If-Match with matching ETag must return 200 or 204, got ${matchResponse.statusCode()} (HTTP 412 rejected on matching weak ETag)"
            if (matchResponse.statusCode() == 200) {
                assert matchResponse.jsonPath().getString("displayName") == "Stale Patch Name"
            } else if (matchResponse.statusCode() == 204) {
                def getResponse = scimRequestQuiet().get("/Users/${userId}")
                assert getResponse.statusCode() == 200
                assert getResponse.jsonPath().getString("displayName") == "Stale Patch Name"
            }
        }

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── PAT_21: Remove multi-valued collection without filter ──────────────

    @SpecRef("RFC7644-3.5.2.2-R2")
    def "PAT_21: PATCH remove on multi-valued collection without path filter clears all values"() {
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

        then: "Status is 200 or 204"
        assert patchResp.statusCode() in [200, 204]

        and: "Emails collection is cleared"
        def getResponse = scimRequestQuiet().get("/Users/${userId}")
        def emails = getResponse.jsonPath().getList("emails")
        emails == null || emails.isEmpty()

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── Gap Closures: PAT_22..PAT_28 ────────────────────────────────────────

    @SpecRef("RFC7644-3.5.2-R2")
    def "PAT_22: PATCH with empty Operations array returns 400 Bad Request"() {
        // RFC 7644 §3.5.2 — Operations array MUST contain one or more operations
        when: "PATCH with empty Operations array"
        def response = patchUser(testUserId, [])

        then: "Status is 400 Bad Request"
        response.statusCode() == 400
        assertScimError(response, 400)
    }

    @SpecRef("RFC7644-3.5.2-R3")
    def "PAT_23: PATCH operation missing op member returns 400 Bad Request"() {
        // RFC 7644 §3.5.2 — Each operation object MUST have exactly one 'op' member
        when: "PATCH operation without 'op' member"
        def payload = [
            schemas   : [PATCH_OP_SCHEMA],
            Operations: [[path: "title", value: "NoOpValue"]]
        ]
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .patch("/Users/${testUserId}")

        then: "Status is 400 Bad Request"
        response.statusCode() == 400
        assertScimError(response, 400)
    }

    @SpecRef("RFC7644-3.5.2.1-R1")
    def "PAT_24: PATCH add operation missing value member returns 400 Bad Request"() {
        // RFC 7644 §3.5.2.1 — The operation MUST contain a 'value' member
        when: "PATCH add without 'value'"
        def payload = [
            schemas   : [PATCH_OP_SCHEMA],
            Operations: [[op: "add", path: "title"]]
        ]
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .patch("/Users/${testUserId}")

        then: "Status is 400 Bad Request"
        assert response.statusCode() == 400 : "Expected 400 Bad Request for PATCH add missing required 'value' member, got ${response.statusCode()}"
        assertScimError(response, 400)
    }

    @SpecRef("RFC7644-3.5.2.2-R1")
    def "PAT_25: PATCH remove operation without path returns 400 with scimType noTarget"() {
        // RFC 7644 §3.5.2.2 — If 'path' is unspecified, fails with 400 and scimType noTarget
        when: "PATCH remove without path"
        def payload = [
            schemas   : [PATCH_OP_SCHEMA],
            Operations: [[op: "remove"]]
        ]
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .patch("/Users/${testUserId}")

        then: "Status is 400 with scimType noTarget"
        response.statusCode() == 400
        assertScimError(response, 400, "noTarget")
    }

    @SpecRef("RFC7644-3.5.2.3-R2")
    def "PAT_26: PATCH replace on absent target attribute is treated as add operation"() {
        // RFC 7644 §3.5.2.3 — If target attribute does not exist, replace treated as add
        given: "Create user without nickName"
        def user = createUser()
        def userId = user.jsonPath().getString("id")

        when: "PATCH replace on absent attribute 'nickName'"
        def response = patchUser(userId, [
            [op: "replace", path: "nickName", value: "NewlyAddedNick"]
        ])

        then: "Status is 200 or 204 and attribute is created"
        assert response.statusCode() in [200, 204]
        def getResp = scimRequestQuiet().get("/Users/${userId}")
        getResp.jsonPath().getString("nickName") == "NewlyAddedNick"

        cleanup:
        if (userId) deleteUser(userId)
    }

    @SpecRef("RFC7644-3.5.2-R6")
    @SpecRef("RFC7643-2.4-R1")
    def "PAT_27: PATCH setting primary:true on multi-valued item automatically unsets primary on other items"() {
        // RFC 7644 §3.5.2 — Setting primary to true SHALL cause server to automatically set primary to false for other values
        given: "User with 2 emails where first is primary"
        def email1 = "prim1_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def email2 = "prim2_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def user = createFullUser(
            emails: [
                [value: email1, type: "work", primary: true],
                [value: email2, type: "home", primary: false]
            ]
        )
        def userId = user.jsonPath().getString("id")
        assert user.statusCode() == 201

        when: "PATCH second email to have primary: true"
        def response = patchUser(userId, [
            [op: "replace", path: 'emails[type eq "home"].primary', value: true]
        ])

        then: "Status is 200 or 204"
        assert response.statusCode() in [200, 204]

        and: "Second email is primary and first email has primary false (or omitted), exactly one primary email"
        def getResp = scimRequestQuiet().get("/Users/${userId}")
        def emails = getResp.jsonPath().getList("emails")
        def homeEntry = emails.find { it.type == "home" }
        def workEntry = emails.find { it.type == "work" }

        assert homeEntry?.primary == true : "Updated item must be primary"
        assert workEntry?.primary != true : "Previous primary must have primary unset (RFC 7644 §3.5.2)"
        def primaryCount = emails.count { it.primary == true }
        assert primaryCount == 1 : "Expected exactly one primary email (RFC 7643 §2.4, RFC 7644 §3.5.2), found ${primaryCount}"

        cleanup:
        if (userId) deleteUser(userId)
    }

    @SpecRef("RFC7644-3.5.2-R5")
    @SpecRef("RFC7644-3.5.2-R4")
    def "PAT_28: PATCH with attributes query parameter MUST return 200 OK"() {
        // RFC 7644 §3.5.2 — MUST return 200 OK if the 'attributes' parameter is specified
        when: "PATCH with attributes query parameter"
        def response = scimRequestQuiet()
            .queryParam("attributes", "userName,title")
            .body(JsonOutput.toJson(buildPatchOp([
                [op: "replace", path: "title", value: "Architect"]
            ])))
            .patch("/Users/${testUserId}")

        then: "Status is 200 OK (server cannot return 204) and unrequested attributes are excluded"
        response.statusCode() == 200
        assert response.jsonPath().getList("schemas")?.contains(USER_SCHEMA)
        assert response.jsonPath().getString("id") == testUserId
        assert response.jsonPath().getString("userName") == testUserName
        assert response.jsonPath().getString("title") == "Architect"
        assert response.jsonPath().get("emails") == null :
            "Unrequested attribute 'emails' must be omitted when attributes=userName,title on PATCH (RFC 7644 §3.5.2, §3.9)"
        assert response.jsonPath().get("active") == null :
            "Unrequested attribute 'active' must be omitted when attributes=userName,title on PATCH (RFC 7644 §3.5.2, §3.9)"
    }

    // ─── PAT_29: Sub-attribute removal on complex attribute ──────────────────

    @SpecRef("RFC7644-3.5.2.2-R2")
    def "PAT_29: PATCH remove targeting name.givenName clears only the sub-attribute"() {
        // RFC 7644 §3.5.2.2 — Removing sub-attribute of complex attribute
        given: "User with full name"
        def user = createFullUser(name: [givenName: "SubRemoveFirst", familyName: "KeepFamily"])
        def uid = user.jsonPath().getString("id")

        when: "PATCH remove with path name.givenName"
        def patchResp = patchUser(uid, [
            [op: "remove", path: "name.givenName"]
        ])

        then: "Status is 200 or 204"
        assert patchResp.statusCode() in [200, 204]

        and: "givenName is removed while familyName remains"
        def getResp = scimRequestQuiet().get("/Users/${uid}")
        getResp.jsonPath().getString("name.familyName") == "KeepFamily"
        getResp.jsonPath().get("name.givenName") == null

        cleanup:
        if (uid) deleteUser(uid)
    }

    // ─── PAT_30: Value-path replacement on multi-valued item ────────────────

    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_30: PATCH replace targeting sub-attribute via value-path filter"() {
        // RFC 7644 §3.5.2.3 — Value path filter replacement
        given: "User with work email"
        def user = createFullUser(emails: [[value: "orig_valpath@test.com", type: "work", primary: true]])
        def uid = user.jsonPath().getString("id")

        when: "PATCH replace emails[type eq \"work\"].value"
        def response = patchUser(uid, [
            [op: "replace", path: 'emails[type eq "work"].value', value: "updated_valpath@test.com"]
        ])

        then: "Status is 200 or 204 and email value is updated"
        assert response.statusCode() in [200, 204]
        def getResp = scimRequestQuiet().get("/Users/${uid}")
        def emails = getResp.jsonPath().getList("emails")
        emails.find { it.value == "updated_valpath@test.com" } != null

        cleanup:
        if (uid) deleteUser(uid)
    }

    // ─── PAT_31: Pathless add merges attributes ─────────────────────────────

    @SpecRef("RFC7644-3.5.2.1-R3")
    def "PAT_31: PATCH add operation without path merges attributes into resource"() {
        // RFC 7644 §3.5.2.1 — Pathless add operation merges attributes into resource
        when: "PATCH add without path specifying title and nickName"
        def payload = [
            schemas   : [PATCH_OP_SCHEMA],
            Operations: [
                [op: "add", value: [title: "PathlessTitle", nickName: "PathlessNick"]]
            ]
        ]
        def response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .patch("/Users/${testUserId}")

        then: "Status is 200 or 204 and attributes are added"
        assert response.statusCode() in [200, 204]
        def getResp = scimRequestQuiet().get("/Users/${testUserId}")
        getResp.jsonPath().getString("title") == "PathlessTitle"
        getResp.jsonPath().getString("nickName") == "PathlessNick"
    }

    // ─── PAT_32: Change user password via PATCH ─────────────────────────────

    @RequiresCapability("changePassword")
    @SpecRef("RFC7643-4.1.1-R7")
    @SpecRef("RFC7644-3.5.2.3-R1")
    def "PAT_32: Change user password via PATCH replace succeeds and never returns password"() {
        // RFC 7643 §4.1.1 (password mutability writeOnly, returned never, changePassword capability)
        // RFC 7644 §3.5.2.3 (PATCH replace)
        given: "Create a user with an initial password"
        def userName = "pwd_patch_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def initialPassword = "InitialPassword123!"
        def createResponse = createUser(
            userName: userName,
            password: initialPassword
        )
        assert createResponse.statusCode() == 201
        def userId = createResponse.jsonPath().getString("id")
        assert createResponse.jsonPath().get("password") == null : "Password must not be returned in POST response"

        when: "Change password via PATCH replace operation"
        def patchedPassword = "PatchedPassword456!"
        def patchPayload = buildPatchOp([
            [op: "replace", path: "password", value: patchedPassword]
        ])
        def patchResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Users/${userId}")

        then: "PATCH succeeds with HTTP 200 or 204 and password is not returned"
        assert patchResponse.statusCode() in [200, 204] : "PATCH password must return 200 or 204, got ${patchResponse.statusCode()}"
        if (patchResponse.statusCode() == 200 && patchResponse.body().asString()?.trim()) {
            assert patchResponse.jsonPath().get("password") == null : "Password must not be returned in PATCH response"
            assert !patchResponse.body().asString().contains(patchedPassword)
        }

        when: "Verify user via GET that password is not returned"
        def getResponse = scimRequestQuiet().get("/Users/${userId}")

        then: "GET returns 200 and password attribute is null"
        assert getResponse.statusCode() == 200
        assert getResponse.jsonPath().get("password") == null : "Password must never be returned in GET response"
        assert !getResponse.body().asString().contains(initialPassword)
        assert !getResponse.body().asString().contains(patchedPassword)

        cleanup:
        if (userId) {
            try { scimRequestQuiet().delete("/Users/${userId}") } catch (Exception ignored) {}
        }
    }

    // ─── Cleanup ────────────────────────────────────────────────────────────

    def cleanupSpec() {
        [testUserId, multiEmailUserId].each { id ->
            if (id) deleteUser(id)
        }
        createdUserIds.each { id -> if (id) deleteUser(id) }
        createdUserIds.clear()
    }
}
