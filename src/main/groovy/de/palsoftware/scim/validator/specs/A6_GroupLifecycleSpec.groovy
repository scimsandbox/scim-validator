package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import io.restassured.RestAssured
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 6 — Group Lifecycle
 *
 * Validates SCIM 2.0 Group CRUD operations and referential integrity
 * per RFC 7643 §4.2 and RFC 7644 §3.2–§3.6.
 */
@Stepwise
class A6_GroupLifecycleSpec extends ScimBaseSpec {

    @Shared String memberUserId1
    @Shared String memberUserId2
    @Shared String groupId
    @Shared String groupDisplayName

    def setupSpec() {
        loadServiceProviderConfig()

        // Create two users that will serve as group members
        def user1 = createUser(userName: "grp_member1_${UUID.randomUUID().toString().substring(0,8)}@test.com")
        assert user1.statusCode() == 201 : "Setup: user1 creation failed: ${user1.body().asString()}"
        memberUserId1 = user1.jsonPath().getString("id")

        def user2 = createUser(userName: "grp_member2_${UUID.randomUUID().toString().substring(0,8)}@test.com")
        assert user2.statusCode() == 201 : "Setup: user2 creation failed: ${user2.body().asString()}"
        memberUserId2 = user2.jsonPath().getString("id")
    }

    // ─── GRP_01: Create Group ───────────────────────────────────────────────

    def "GRP_01: POST /Groups creates a group with members and returns 201"() {
        // RFC 7644 §3.3 — Creating Resources
        given:
        groupDisplayName = "TestGroup_${UUID.randomUUID().toString().substring(0,8)}"

        when:
        Response response = createGroup(groupDisplayName, [memberUserId1])
        groupId = response.jsonPath().getString("id")

        then:
        response.statusCode() == 201
        groupId != null

        and: "Response contains correct displayName and schemas"
        response.jsonPath().getString("displayName") == groupDisplayName
        response.jsonPath().getList("schemas").contains(GROUP_SCHEMA)

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
        response.jsonPath().getList("members").size() == 1
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

    def "GRP_04: PUT /Groups/{id} replaces group, changing members"() {
        // RFC 7644 §3.5.1 — Replacing with PUT
        given:
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

    // ─── GRP_07: Delete Group ───────────────────────────────────────────────

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

        and: "Remove from cleanup tracking since already deleted"
        createdGroupIds.remove(groupId)
        true // need this for valid then block
    }
}
