package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 6 — Group Lifecycle & Referential Integrity
 *
 * Validates SCIM 2.0 Group CRUD operations, member relationships, cascading
 * referential integrity, reverse group reflection, optimistic concurrency,
 * and attribute projections per RFC 7643 §4.1–§4.2 and RFC 7644 §3.2–§3.6, §3.9, §3.12–§3.14.
 */
@Stepwise
class A6_GroupLifecycleSpec extends ScimBaseSpec {

    @Shared String memberUserId1
    @Shared String memberUserId2
    @Shared String groupId
    @Shared String groupDisplayName
    @Shared String groupExternalId

    def setupSpec() {
        loadServiceProviderConfig()

        // Create two users that will serve as group members
        def user1 = createUser(userName: "grp_member1_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert user1.statusCode() == 201 : "Setup: user1 creation failed: ${user1.body().asString()}"
        memberUserId1 = user1.jsonPath().getString("id")

        def user2 = createUser(userName: "grp_member2_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert user2.statusCode() == 201 : "Setup: user2 creation failed: ${user2.body().asString()}"
        memberUserId2 = user2.jsonPath().getString("id")
    }

    // ─── GRP_01: Create Group ───────────────────────────────────────────────

    def "GRP_01: POST /Groups creates a group with members and returns 201"() {
        // RFC 7644 §3.3 — Creating Resources, §3.14 — Response Headers
        given:
        groupDisplayName = "TestGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        groupExternalId = "ext_grp_${UUID.randomUUID().toString().substring(0, 8)}"
        Map payload = [
            schemas    : [GROUP_SCHEMA],
            displayName: groupDisplayName,
            externalId : groupExternalId,
            members    : [[value: memberUserId1]]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .post("/Groups")
        groupId = response.jsonPath().getString("id")
        if (groupId) createdGroupIds << groupId

        then: "Status is 201 Created with valid ID"
        response.statusCode() == 201
        groupId != null

        and: "Content-Type is application/scim+json"
        response.contentType().contains(SCIM_CONTENT_TYPE)

        and: "Location header is present"
        def locationHeader = response.header("Location")
        locationHeader != null
        locationHeader.contains("/Groups/${groupId}")

        and: "Response contains correct displayName, externalId, and schemas"
        response.jsonPath().getString("displayName") == groupDisplayName
        response.jsonPath().getString("externalId") == groupExternalId
        response.jsonPath().getList("schemas").contains(GROUP_SCHEMA)

        and: "Response contains meta with resourceType, created, lastModified"
        def meta = response.jsonPath().getMap("meta")
        meta != null
        meta.resourceType == "Group"
        meta.created != null
        meta.lastModified != null

        and: "Response contains the member"
        response.jsonPath().getList("members").size() == 1
        response.jsonPath().getString("members[0].value") == memberUserId1
    }

    // ─── GRP_02: Read Group ─────────────────────────────────────────────────

    def "GRP_02: GET /Groups/{id} retrieves the created group"() {
        // RFC 7644 §3.4.1 — Retrieving a Known Resource
        when:
        Response response = scimRequest()
            .get("/Groups/${groupId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getString("id") == groupId
        response.jsonPath().getString("displayName") == groupDisplayName
        response.jsonPath().getString("externalId") == groupExternalId
        response.jsonPath().getList("members").size() == 1
        response.jsonPath().getString("members[0].value") == memberUserId1
    }

    // ─── GRP_03: List Groups ────────────────────────────────────────────────

    def "GRP_03: GET /Groups lists groups and our group is included"() {
        // RFC 7644 §3.4.2 — Query Resources
        when:
        Response response = scimRequest()
            .queryParam("filter", "displayName eq \"${groupDisplayName}\"")
            .get("/Groups")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.id").contains(groupId)
    }

    // ─── GRP_04: Update Group via PUT ───────────────────────────────────────

    def "GRP_04: PUT /Groups/{id} replaces group, changing members and clearing omitted attributes"() {
        // RFC 7644 §3.5.1 — Replacing with PUT
        given: "Payload without externalId to verify omitted attribute clearing"
        Map payload = [
            schemas    : [GROUP_SCHEMA],
            displayName: groupDisplayName,
            members    : [
                [value: memberUserId1],
                [value: memberUserId2]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .put("/Groups/${groupId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getList("members").size() == 2
        def memberValues = response.jsonPath().getList("members.value")
        memberValues.contains(memberUserId1)
        memberValues.contains(memberUserId2)

        and: "Omitted externalId is cleared"
        response.jsonPath().getString("externalId") == null
    }

    // ─── GRP_05: PATCH add member ───────────────────────────────────────────

    def "GRP_05: PATCH add member to group"() {
        // RFC 7644 §3.5.2.1 — Add Operation
        given: "First PUT group back to only one member for this test"
        Map resetPayload = [
            schemas    : [GROUP_SCHEMA],
            displayName: groupDisplayName,
            members    : [
                [value: memberUserId1]
            ]
        ]
        scimRequestQuiet()
            .body(JsonOutput.toJson(resetPayload))
            .put("/Groups/${groupId}")

        when: "PATCH to add the second member"
        Map patchPayload = buildPatchOp([
            [op: "add", path: "members", value: [[value: memberUserId2]]]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Groups/${groupId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getList("members").size() == 2
        def memberValues = response.jsonPath().getList("members.value")
        memberValues.contains(memberUserId2)
    }

    // ─── GRP_06: PATCH remove member ────────────────────────────────────────

    def "GRP_06: PATCH remove member from group"() {
        // RFC 7644 §3.5.2.2 — Remove Operation
        when:
        Map patchPayload = buildPatchOp([
            [op: "remove", path: "members[value eq \"${memberUserId2}\"]"]
        ])
        Response response = scimRequest()
            .body(JsonOutput.toJson(patchPayload))
            .patch("/Groups/${groupId}")

        then:
        response.statusCode() == 200
        def members = response.jsonPath().getList("members")
        members.size() == 1
        members[0].value == memberUserId1
    }

    // ─── GRP_07: Delete Group ───────────────────────────────────────

    def "GRP_07: DELETE /Groups/{id} removes the group and GET returns 404"() {
        // RFC 7644 §3.6 — Deleting Resources
        when:
        Response deleteResponse = scimRequest()
            .delete("/Groups/${groupId}")

        then:
        deleteResponse.statusCode() == 204

        when: "Verify group is gone"
        Response getResponse = scimRequest()
            .get("/Groups/${groupId}")

        then:
        getResponse.statusCode() == 404
        getResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        getResponse.jsonPath().getString("status") == "404"

        cleanup: "Drop from cleanup tracking since the group is already deleted"
        createdGroupIds.remove(groupId)
    }

    // ─── GRP_08: Repeated DELETE returns 404 ────────────────────────────────

    def "GRP_08: Repeated DELETE on already-deleted group returns 404"() {
        // RFC 7644 §3.6 — Deleting a non-existent resource
        when: "DELETE the same group again"
        Response deleteResponse = scimRequestQuiet()
            .delete("/Groups/${groupId}")

        then: "Status is 404 with SCIM Error schema"
        deleteResponse.statusCode() == 404
        deleteResponse.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        deleteResponse.jsonPath().getString("status") == "404"
    }

    // ─── GRP_09: GET non-existent group returns 404 ─────────────────────────

    def "GRP_09: GET /Groups/{id} on non-existent group ID returns 404"() {
        // RFC 7644 §3.4.1 — Retrieving a non-existent resource
        given:
        String nonExistentId = UUID.randomUUID().toString()

        when:
        Response response = scimRequestQuiet()
            .get("/Groups/${nonExistentId}")

        then: "Status is 404 with SCIM Error schema"
        response.statusCode() == 404
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "404"
    }

    // ─── GRP_10: PUT non-existent group returns 404 ─────────────────────────

    def "GRP_10: PUT /Groups/{id} on non-existent group ID returns 404"() {
        // RFC 7644 §3.5.1 — Modifying a non-existent resource
        given:
        String nonExistentId = UUID.randomUUID().toString()
        Map payload = [
            schemas    : [GROUP_SCHEMA],
            displayName: "NonExistent_${UUID.randomUUID().toString().substring(0, 8)}"
        ]

        when:
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .put("/Groups/${nonExistentId}")

        then: "Status is 404 with SCIM Error schema"
        response.statusCode() == 404
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "404"
    }

    // ─── GRP_11: POST without displayName returns 400 ───────────────────────

    def "GRP_11: POST /Groups without required displayName returns 400 Bad Request"() {
        // RFC 7644 §3.3, RFC 7643 §4.2 — Missing required attribute
        when:
        Map payload = [
            schemas   : [GROUP_SCHEMA],
            externalId: "no-name-grp"
        ]
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Groups")

        then: "Status is 400 Bad Request with SCIM Error schema"
        assertScimError(response, 400, "invalidValue")
    }

    // ─── GRP_12: POST duplicate displayName returns 409 Conflict ────────────

    def "GRP_12: POST /Groups with duplicate displayName returns 409 Conflict or is accepted"() {
        // RFC 7644 §3.3, §3.12 — Uniqueness conflict.
        //
        // RFC 7643 §7 declares Group.displayName with "uniqueness": "none", so duplicate
        // group names are permitted. Servers that DO enforce uniqueness must report it the
        // RFC way (409 + scimType "uniqueness"); servers that don't are merely noted.
        given: "An existing group"
        String dupDisplayName = "DupGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        Response first = createGroup(dupDisplayName)
        assert first.statusCode() == 201
        String firstId = first.jsonPath().getString("id")

        when: "POST another group with identical displayName"
        Map payload = [
            schemas    : [GROUP_SCHEMA],
            displayName: dupDisplayName
        ]
        Response duplicateResponse = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Groups")

        then: "Either 409 Conflict with scimType uniqueness, or 201 (displayName uniqueness is 'none')"
        duplicateResponse.statusCode() in [201, 409]
        if (duplicateResponse.statusCode() == 409) {
            assertScimError(duplicateResponse, 409, "uniqueness")
        } else {
            ScimOutput.println "NOTE: Server permits duplicate Group displayName " +
                "(RFC 7643 §7 declares displayName uniqueness 'none')"
        }

        cleanup:
        if (duplicateResponse.statusCode() == 201) {
            String duplicateId = duplicateResponse.jsonPath().getString("id")
            if (duplicateId) deleteGroup(duplicateId)
        }
        if (firstId) {
            deleteGroup(firstId)
        }
    }

    // ─── GRP_13: Cascading Referential Integrity ────────────────────────────

    def "GRP_13: Cascading referential integrity — deleting user prunes from group members"() {
        // RFC 7644 §3.6 — Referential Integrity
        given: "A user created specifically to be deleted"
        Response userResp = createUser(userName: "cascade_user_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert userResp.statusCode() == 201
        String cascadeUserId = userResp.jsonPath().getString("id")

        and: "A group containing this user and memberUserId1"
        String cascadeGroupName = "CascadeGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        Response groupResp = createGroup(cascadeGroupName, [cascadeUserId, memberUserId1])
        assert groupResp.statusCode() == 201
        String cascadeGroupId = groupResp.jsonPath().getString("id")

        when: "The user is deleted"
        Response deleteUserResp = scimRequestQuiet().delete("/Users/${cascadeUserId}")
        assert deleteUserResp.statusCode() == 204
        createdUserIds.remove(cascadeUserId)

        and: "Retrieving the group after user deletion"
        Response getGroupResp = scimRequest().get("/Groups/${cascadeGroupId}")

        then: "Group retrieval succeeds with 200 OK"
        getGroupResp.statusCode() == 200

        and: "Deleted user is no longer listed in group members, remaining member is retained"
        def members = getGroupResp.jsonPath().getList("members")
        members != null
        !members.any { it.value == cascadeUserId }
        members.any { it.value == memberUserId1 }

        cleanup:
        if (cascadeGroupId) {
            deleteGroup(cascadeGroupId)
        }
    }

    // ─── GRP_14: Reverse Group Reflection ───────────────────────────────────

    def "GRP_14: Reverse group reflection — group membership reflects in GET /Users/{id}.groups"() {
        // RFC 7643 §4.1 — Read-only groups attribute on User
        given: "A user created for membership reflection"
        Response userResp = createUser(userName: "reflect_user_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert userResp.statusCode() == 201
        String reflectUserId = userResp.jsonPath().getString("id")

        and: "A group containing this user"
        String reflectGroupName = "ReflectGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        Response groupResp = createGroup(reflectGroupName, [reflectUserId])
        assert groupResp.statusCode() == 201
        String reflectGroupId = groupResp.jsonPath().getString("id")

        when: "GET /Users/{id}"
        Response getUserResp = scimRequest().get("/Users/${reflectUserId}")

        then: "User response contains groups list reflecting the group membership"
        getUserResp.statusCode() == 200
        def groups = getUserResp.jsonPath().getList("groups")
        groups != null
        groups.any { it.value == reflectGroupId && it.display == reflectGroupName }

        when: "Deleting the group"
        Response delGroupResp = scimRequestQuiet().delete("/Groups/${reflectGroupId}")
        assert delGroupResp.statusCode() == 204
        createdGroupIds.remove(reflectGroupId)

        and: "GET /Users/{id} after group deletion"
        Response getUserAfterResp = scimRequest().get("/Users/${reflectUserId}")

        then: "User response no longer includes the deleted group"
        getUserAfterResp.statusCode() == 200
        def groupsAfter = getUserAfterResp.jsonPath().getList("groups")
        groupsAfter == null || !groupsAfter.any { it.value == reflectGroupId }

        cleanup:
        if (reflectUserId) {
            deleteUser(reflectUserId)
        }
    }

    // ─── GRP_15: Invalid Member Reference ───────────────────────────────────

    def "GRP_15: Invalid member reference returns 400 or 404 SCIM Error"() {
        // RFC 7644 §3.3 — Member reference validation
        given: "A non-existent member UUID"
        String nonExistentMemberId = UUID.randomUUID().toString()
        Map payload = [
            schemas    : [GROUP_SCHEMA],
            displayName: "InvalidMember_${UUID.randomUUID().toString().substring(0, 8)}",
            members    : [[value: nonExistentMemberId, type: "User"]]
        ]

        when: "POST /Groups with non-existent member"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Groups")

        then: "Server rejects with 400 or 404 SCIM Error schema"
        response.statusCode() in [400, 404]
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") in ["400", "404"]
    }

    // ─── GRP_16: Nested Groups ──────────────────────────────────────────────

    def "GRP_16: Nested groups — adding a child Group as a member with type 'Group'"() {
        // RFC 7643 §4.2 — Group members can be 'User' or 'Group'
        given: "A child group"
        String childName = "ChildGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        Response childResp = createGroup(childName)
        assert childResp.statusCode() == 201
        String childGroupId = childResp.jsonPath().getString("id")

        and: "A parent group containing the child group as member with type Group"
        String parentName = "ParentGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        Map parentPayload = [
            schemas    : [GROUP_SCHEMA],
            displayName: parentName,
            members    : [
                [value: childGroupId, type: "Group", display: childName]
            ]
        ]

        when: "POST /Groups for parent group"
        Response parentResp = scimRequest()
            .body(JsonOutput.toJson(parentPayload))
            .post("/Groups")
        String parentGroupId = parentResp.jsonPath().getString("id")
        if (parentGroupId) createdGroupIds << parentGroupId

        then: "Parent group created successfully with 201"
        parentResp.statusCode() == 201
        parentGroupId != null

        when: "GET /Groups/{parentGroupId}"
        Response getParentResp = scimRequest().get("/Groups/${parentGroupId}")

        then: "Member is present with type Group"
        getParentResp.statusCode() == 200
        def members = getParentResp.jsonPath().getList("members")
        members != null
        members.size() == 1
        members[0].value == childGroupId
        members[0].type == "Group"

        cleanup:
        if (parentGroupId) deleteGroup(parentGroupId)
        if (childGroupId) deleteGroup(childGroupId)
    }

    // ─── GRP_17: Optimistic Concurrency Control ─────────────────────────────

    def "GRP_17: Optimistic concurrency control with If-Match ETag on PUT /Groups/{id}"() {
        // RFC 7644 §3.13, §3.14 — Concurrency Control
        given: "A group to update"
        String occName = "OCCGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        Response occResp = createGroup(occName)
        assert occResp.statusCode() == 201
        String occGroupId = occResp.jsonPath().getString("id")

        when: "GET group to retrieve current ETag"
        Response getResp = scimRequest().get("/Groups/${occGroupId}")
        String etag = getResp.header("ETag")

        then: "ETag header is returned"
        etag != null

        when: "PUT with mismatched If-Match"
        Map payload = [
            schemas    : [GROUP_SCHEMA],
            displayName: "${occName}_updated"
        ]
        Response mismatchResp = scimRequestQuiet()
            .header("If-Match", 'W/"999999"')
            .body(JsonOutput.toJson(payload))
            .put("/Groups/${occGroupId}")

        then: "Server returns 412 Precondition Failed"
        mismatchResp.statusCode() == 412
        mismatchResp.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        mismatchResp.jsonPath().getString("status") == "412"

        when: "PUT with matching If-Match"
        Response matchResp = scimRequest()
            .header("If-Match", etag)
            .body(JsonOutput.toJson(payload))
            .put("/Groups/${occGroupId}")

        then: "Server returns 200 OK and updates group"
        matchResp.statusCode() == 200
        matchResp.jsonPath().getString("displayName") == "${occName}_updated"

        cleanup:
        if (occGroupId) deleteGroup(occGroupId)
    }

    // ─── GRP_18: Attribute Selection ────────────────────────────────────────

    def "GRP_18: GET /Groups/{id} supports attributes and excludedAttributes projections"() {
        // RFC 7644 §3.9 — Attribute Selection
        given: "A group with members"
        String projName = "ProjGroup_${UUID.randomUUID().toString().substring(0, 8)}"
        Response projResp = createGroup(projName, [memberUserId1])
        assert projResp.statusCode() == 201
        String projGroupId = projResp.jsonPath().getString("id")

        when: "GET with attributes=displayName"
        Response attrResp = scimRequest()
            .queryParam("attributes", "displayName")
            .get("/Groups/${projGroupId}")

        then: "Status is 200, displayName is included, members is omitted"
        attrResp.statusCode() == 200
        attrResp.jsonPath().getString("displayName") == projName
        attrResp.jsonPath().get("members") == null
        attrResp.jsonPath().getList("schemas") != null
        attrResp.jsonPath().getString("id") == projGroupId

        when: "GET with excludedAttributes=members"
        Response exclResp = scimRequest()
            .queryParam("excludedAttributes", "members")
            .get("/Groups/${projGroupId}")

        then: "Status is 200, displayName is included, members is omitted"
        exclResp.statusCode() == 200
        exclResp.jsonPath().getString("displayName") == projName
        exclResp.jsonPath().get("members") == null

        cleanup:
        if (projGroupId) deleteGroup(projGroupId)
    }
}

