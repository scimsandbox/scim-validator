package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 7 — Bulk Operations
 *
 * Validates SCIM 2.0 Bulk operations per RFC 7644 §3.7 and RFC 7643 §5.
 * Covers bulk POST, PUT, PATCH, DELETE, bulkId cross-referencing in data and paths,
 * operation error isolation, failOnErrors threshold, maxOperations enforcement,
 * unsupported methods, and schema validation.
 */
@Stepwise
class A7_BulkOperationsSpec extends ScimBaseSpec {

    @Shared String bulkCreatedUserId1
    @Shared String bulkCreatedUserId2

    def setupSpec() {
        loadServiceProviderConfig()
    }

    // ─── BLK_01: Bulk POST creates multiple users ──────────────────────────

    def "BLK_01: Bulk POST creates multiple users in a single request"() {
        // RFC 7644 §3.7 — Bulk Operations
        given:
        String suffix1 = UUID.randomUUID().toString().substring(0, 8)
        String suffix2 = UUID.randomUUID().toString().substring(0, 8)

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "user1",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "bulk_user1_${suffix1}@test.com",
                        emails  : [[value: "bulk1_${suffix1}@test.com", type: "work", primary: true]]
                    ]
                ],
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "user2",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "bulk_user2_${suffix2}@test.com",
                        emails  : [[value: "bulk2_${suffix2}@test.com", type: "work", primary: true]]
                    ]
                ]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Status is 200 OK and response contains BulkResponse schema"
        response.statusCode() == 200
        response.jsonPath().getList("schemas")?.contains(BULK_RESPONSE_SCHEMA)

        and: "Both operations should succeed with status 201"
        def operations = response.jsonPath().getList("Operations")
        operations.size() == 2
        operations.every { Map op -> (op.status as String) == "201" }

        when: "Capture created user IDs for subsequent tests"
        operations.each { Map op ->
            String location = op.location as String
            if (location) {
                String id = location.tokenize("/").last()
                if (op.bulkId == "user1") {
                    bulkCreatedUserId1 = id
                } else {
                    bulkCreatedUserId2 = id
                }
                createdUserIds << id
            }
        }

        then: "Both user IDs were resolved and returned in location"
        bulkCreatedUserId1 != null
        bulkCreatedUserId2 != null
    }

    // ─── BLK_02: Bulk DELETE removes multiple users ─────────────────────────

    def "BLK_02: Bulk DELETE removes multiple users in a single request"() {
        // RFC 7644 §3.7 — Bulk Operations
        given:
        assert bulkCreatedUserId1 != null : "BLK_01 must pass first"
        assert bulkCreatedUserId2 != null : "BLK_01 must pass first"

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "DELETE",
                    path  : "/Users/${bulkCreatedUserId1}"
                ],
                [
                    method: "DELETE",
                    path  : "/Users/${bulkCreatedUserId2}"
                ]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Status is 200 and operations report status 204"
        response.statusCode() == 200
        def operations = response.jsonPath().getList("Operations")
        operations.size() == 2
        operations.every { Map op -> (op.status as String) == "204" }

        when: "Remove from cleanup since already deleted and verify via GET"
        createdUserIds.remove(bulkCreatedUserId1)
        createdUserIds.remove(bulkCreatedUserId2)

        Response get1 = scimRequestQuiet().get("/Users/${bulkCreatedUserId1}")
        Response get2 = scimRequestQuiet().get("/Users/${bulkCreatedUserId2}")

        then: "Both users are confirmed deleted (404)"
        get1.statusCode() == 404
        get2.statusCode() == 404
    }

    // ─── BLK_03: Bulk respects maxOperations ────────────────────────────────

    def "BLK_03: Bulk request exceeding maxOperations returns 413"() {
        // RFC 7644 §3.7 — Server MAY return 413 if too many operations
        given: "Build a bulk request with more operations than maxOperations"
        int maxOps = bulkMaxOperations ?: 1000
        int opsCount = maxOps + 1

        List<Map> operations = (1..opsCount).collect { int i ->
            [
                method: "POST",
                path  : "/Users",
                bulkId: "excess_${i}",
                data  : [
                    schemas : [USER_SCHEMA],
                    userName: "excess_${i}_${UUID.randomUUID().toString().substring(0, 6)}@test.com",
                    emails  : [[value: "excess_${i}@test.com", type: "work", primary: true]]
                ]
            ]
        }

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: operations
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server should return 413 (PayloadTooLarge) or 400"
        response.statusCode() in [413, 400]
    }

    // ─── BLK_04: failOnErrors stops processing after N errors ───────────────

    def "BLK_04: Bulk with failOnErrors stops after specified error count"() {
        // RFC 7644 §3.7 — failOnErrors: processing stops when error count >= failOnErrors
        given: "Build a bulk request with 3 invalid DELETEs and failOnErrors=1"
        Map bulkPayload = [
            schemas     : [BULK_REQUEST_SCHEMA],
            failOnErrors: 1,
            Operations  : [
                [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
                [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
                [method: "DELETE", path: "/Users/${UUID.randomUUID()}"]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server returns 200 with operations, but stops after first error"
        response.statusCode() == 200
        def operations = response.jsonPath().getList("Operations")
        // Only 1 operation should be processed since failOnErrors=1
        operations.size() == 1
        (operations[0].status as String) in ["404", "400"]
    }

    // ─── BLK_05: Missing BulkRequest schema returns 400 ────────────────────

    def "BLK_05: Bulk request without proper schema returns 400"() {
        // RFC 7644 §3.7 — BulkRequest MUST include the BulkRequest schema URI
        given: "Build a bulk request with missing schemas"
        Map bulkPayload = [
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "noschem_1",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "noschem_${UUID.randomUUID().toString().substring(0, 6)}@test.com",
                        emails  : [[value: "noschem@test.com", type: "work", primary: true]]
                    ]
                ]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server should return 400 Bad Request with SCIM Error schema"
        response.statusCode() == 400
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "400"
    }

    // ─── BLK_06: Wrong schema in BulkRequest returns 400 ───────────────────

    def "BLK_06: Bulk request with wrong schema returns 400"() {
        // RFC 7644 §3.7 — BulkRequest requires urn:ietf:params:scim:api:messages:2.0:BulkRequest
        given: "Build a bulk request with wrong schema"
        Map bulkPayload = [
            schemas   : [USER_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "wrongschem_1",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "wrongschem_${UUID.randomUUID().toString().substring(0, 6)}@test.com",
                        emails  : [[value: "wrongschem@test.com", type: "work", primary: true]]
                    ]
                ]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server should return 400 Bad Request with SCIM Error schema"
        response.statusCode() == 400
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "400"
    }

    // ─── BLK_07: bulkId Cross-Referencing in Data (User -> Group) ───────────

    def "BLK_07: bulkId cross-referencing in data resolves user UUID into group members"() {
        // RFC 7644 §3.7 — bulkId circular reference resolution in data
        given:
        String suffix = UUID.randomUUID().toString().substring(0, 8)
        String userName = "cross_u1_${suffix}@test.com"
        String groupName = "CrossGroup_${suffix}"

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "bulkUser1",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: userName,
                        emails  : [[value: userName, type: "work", primary: true]]
                    ]
                ],
                [
                    method: "POST",
                    path  : "/Groups",
                    bulkId: "bulkGroup1",
                    data  : [
                        schemas    : [GROUP_SCHEMA],
                        displayName: groupName,
                        members    : [
                            [value: "bulkId:bulkUser1", type: "User"]
                        ]
                    ]
                ]
            ]
        ]

        when: "Execute bulk request creating user and referencing via bulkId in group"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Bulk request succeeds with 200 OK"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 2
        (ops[0].status as String) == "201"
        (ops[1].status as String) == "201"

        and: "Extract resolved resource locations"
        String userLoc = ops[0].location as String
        String groupLoc = ops[1].location as String
        userLoc != null
        groupLoc != null

        String createdUserId = userLoc.tokenize("/").last()
        String createdGroupId = groupLoc.tokenize("/").last()
        createdUserIds << createdUserId
        createdGroupIds << createdGroupId

        when: "Retrieve the created group to verify member was resolved to real UUID"
        Response groupResp = scimRequest().get("/Groups/${createdGroupId}")

        then: "Group contains member with the real User UUID, not the transient bulkId"
        groupResp.statusCode() == 200
        def members = groupResp.jsonPath().getList("members")
        members != null
        members.size() == 1
        members[0].value == createdUserId
        members[0].type == "User"

        cleanup:
        if (createdGroupId) deleteGroup(createdGroupId)
        if (createdUserId) deleteUser(createdUserId)
    }

    // ─── BLK_08: bulkId Cross-Referencing in Path (POST -> PATCH -> DELETE) ─

    def "BLK_08: bulkId cross-referencing in path resolves in sequential operations"() {
        // RFC 7644 §3.7 — bulkId path resolution
        given:
        String suffix = UUID.randomUUID().toString().substring(0, 8)
        String userName = "chain_${suffix}@test.com"

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "chainUser",
                    data  : [
                        schemas    : [USER_SCHEMA],
                        userName   : userName,
                        displayName: "Original Name",
                        emails     : [[value: userName, type: "work", primary: true]]
                    ]
                ],
                [
                    method: "PATCH",
                    path  : "/Users/bulkId:chainUser",
                    data  : [
                        schemas   : [PATCH_OP_SCHEMA],
                        Operations: [
                            [op: "replace", path: "displayName", value: "Chained Updated Name"]
                        ]
                    ]
                ],
                [
                    method: "DELETE",
                    path  : "/Users/bulkId:chainUser"
                ]
            ]
        ]

        when: "POST creates, PATCH updates, and DELETE removes the user in one bulk request"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Status is 200 and all three operations succeed sequentially"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 3
        (ops[0].status as String) == "201"
        (ops[1].status as String) == "200"
        (ops[2].status as String) == "204"

        when: "Extract created user ID from op 0 location"
        String createdUserId = (ops[0].location as String)?.tokenize("/")?.last()

        then: "Created user ID is valid"
        createdUserId != null

        when: "Subsequent GET to verify user is completely deleted"
        Response getResp = scimRequestQuiet().get("/Users/${createdUserId}")

        then: "User is not found (404)"
        getResp.statusCode() == 404
    }

    // ─── BLK_09: Bulk PUT Resource Replacement ──────────────────────────────

    def "BLK_09: Bulk PUT replaces an existing user resource"() {
        // RFC 7644 §3.7 — PUT within Bulk
        given: "Pre-create a user to replace"
        String suffix = UUID.randomUUID().toString().substring(0, 8)
        String userName = "bulk_put_${suffix}@test.com"
        Response preUser = createUser(userName: userName, title: "Original Title")
        assert preUser.statusCode() == 201
        String userId = preUser.jsonPath().getString("id")

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "PUT",
                    path  : "/Users/${userId}",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: userName,
                        title   : "Bulk Replaced Title",
                        emails  : [[value: userName, type: "work", primary: true]]
                    ]
                ]
            ]
        ]

        when: "Execute PUT via Bulk"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Status is 200 and operation reports 200 OK"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 1
        (ops[0].status as String) == "200"
        (ops[0].location as String)?.contains("/Users/${userId}")

        when: "Verify attribute change via GET"
        Response getResp = scimRequest().get("/Users/${userId}")

        then: "Title attribute was updated by the bulk PUT"
        getResp.statusCode() == 200
        getResp.jsonPath().getString("title") == "Bulk Replaced Title"

        cleanup:
        if (userId) deleteUser(userId)
    }

    // ─── BLK_10: Bulk PATCH Resource Modification ───────────────────────────

    def "BLK_10: Bulk PATCH modifies an existing user resource"() {
        // RFC 7644 §3.7 — PATCH within Bulk
        given: "Pre-create an active user"
        String suffix = UUID.randomUUID().toString().substring(0, 8)
        String userName = "bulk_patch_${suffix}@test.com"
        Response preUser = createUser(userName: userName, active: true)
        assert preUser.statusCode() == 201
        String userId = preUser.jsonPath().getString("id")

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "PATCH",
                    path  : "/Users/${userId}",
                    data  : [
                        schemas   : [PATCH_OP_SCHEMA],
                        Operations: [
                            [op: "replace", path: "active", value: false]
                        ]
                    ]
                ]
            ]
        ]

        when: "Execute PATCH via Bulk"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Status is 200 and operation reports 200 OK"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 1
        (ops[0].status as String) == "200"

        when: "Verify active flag via GET"
        Response getResp = scimRequest().get("/Users/${userId}")

        then: "User active status was updated to false"
        getResp.statusCode() == 200
        getResp.jsonPath().getBoolean("active") == false

        cleanup:
        if (userId) deleteUser(userId)
    }

    // ─── BLK_11: Mixed Operations in Single Request ─────────────────────────

    def "BLK_11: Bulk request with mixed operations (POST, PUT, PATCH, DELETE) all succeed"() {
        // RFC 7644 §3.7 — Mixed methods in single Bulk request
        given: "Pre-create users for PUT, PATCH, and DELETE operations"
        Response uPut = createUser(userName: "mixed_put_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        Response uPatch = createUser(userName: "mixed_patch_${UUID.randomUUID().toString().substring(0, 8)}@test.com", active: true)
        Response uDel = createUser(userName: "mixed_del_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert uPut.statusCode() == 201 && uPatch.statusCode() == 201 && uDel.statusCode() == 201

        String putId = uPut.jsonPath().getString("id")
        String putName = uPut.jsonPath().getString("userName")
        String patchId = uPatch.jsonPath().getString("id")
        String delId = uDel.jsonPath().getString("id")

        String newUserName = "mixed_post_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "newMixUser",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: newUserName,
                        emails  : [[value: newUserName, type: "work", primary: true]]
                    ]
                ],
                [
                    method: "PUT",
                    path  : "/Users/${putId}",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: putName,
                        title   : "Mixed PUT Title"
                    ]
                ],
                [
                    method: "PATCH",
                    path  : "/Users/${patchId}",
                    data  : [
                        schemas   : [PATCH_OP_SCHEMA],
                        Operations: [[op: "replace", path: "active", value: false]]
                    ]
                ],
                [
                    method: "DELETE",
                    path  : "/Users/${delId}"
                ]
            ]
        ]

        when: "Execute mixed Bulk request"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall status is 200 OK"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 4

        and: "Operation 0 (POST) succeeded with 201"
        (ops[0].status as String) == "201"
        String createdPostId = (ops[0].location as String)?.tokenize("/")?.last()
        if (createdPostId) createdUserIds << createdPostId

        and: "Operation 1 (PUT) succeeded with 200"
        (ops[1].status as String) == "200"

        and: "Operation 2 (PATCH) succeeded with 200"
        (ops[2].status as String) == "200"

        and: "Operation 3 (DELETE) succeeded with 204"
        (ops[3].status as String) == "204"
        createdUserIds.remove(delId)

        when: "Verify deleted user is 404"
        Response getDel = scimRequestQuiet().get("/Users/${delId}")

        then:
        getDel.statusCode() == 404

        cleanup:
        if (putId) deleteUser(putId)
        if (patchId) deleteUser(patchId)
        if (createdPostId) deleteUser(createdPostId)
    }

    // ─── BLK_12: Operation Error Isolation on 404 ───────────────────────────

    def "BLK_12: Failed operation returns 404 within bulk response while overall request returns 200"() {
        // RFC 7644 §3.7 — Error reporting in bulk operations
        given: "A non-existent resource UUID"
        String ghostId = UUID.randomUUID().toString()

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "DELETE",
                    path  : "/Users/${ghostId}"
                ],
                [
                    method: "PUT",
                    path  : "/Users/${ghostId}",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "ghost_${ghostId.substring(0, 8)}@test.com"
                    ]
                ]
            ]
        ]

        when: "Execute bulk request against non-existent resources"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall request returns 200 OK"
        response.statusCode() == 200

        and: "Both operations report status 404 with embedded SCIM Error schema"
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 2
        (ops[0].status as String) == "404"
        ops[0].response != null
        (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
        (ops[0].response.status as String) == "404"

        and: "Second operation also reports status 404 with embedded SCIM Error"
        (ops[1].status as String) == "404"
        ops[1].response != null
        (ops[1].response.schemas as List)?.contains(ERROR_SCHEMA)
        (ops[1].response.status as String) == "404"
    }

    // ─── BLK_13: Operation Error Isolation on 409 (Duplicate userName) ───────

    def "BLK_13: Duplicate userName in bulk POST reports 409 uniqueness while overall request returns 200"() {
        // RFC 7644 §3.7, §3.12 — Uniqueness conflict inside bulk
        given: "An existing user"
        String dupName = "dup_bulk_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Response existingUser = createUser(userName: dupName)
        assert existingUser.statusCode() == 201
        String existingId = existingUser.jsonPath().getString("id")

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "conflictUser",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: dupName
                    ]
                ]
            ]
        ]

        when: "POST duplicate userName via bulk"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall HTTP status is 200 OK"
        response.statusCode() == 200

        and: "Operation result reports status 409 and scimType uniqueness"
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 1
        (ops[0].status as String) == "409"
        ops[0].response != null
        (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
        (ops[0].response.status as String) == "409"
        assertOperationScimType(ops[0] as Map, "uniqueness")

        cleanup:
        if (existingId) deleteUser(existingId)
    }

    // ─── BLK_14: Unresolved bulkId Reference Handling ───────────────────────

    def "BLK_14: Unresolved bulkId reference returns 400 error without server failure"() {
        // RFC 7644 §3.7 — Circular reference or missing bulkId
        given: "Operation referencing an unregistered bulkId"
        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "DELETE",
                    path  : "/Users/bulkId:nonExistentTransientId"
                ]
            ]
        ]

        when: "Execute bulk with non-existent bulkId"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall request returns 200 OK"
        response.statusCode() == 200

        and: "Operation reports 400 (or 404) with SCIM Error schema"
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 1
        (ops[0].status as String) in ["400", "404"]
        ops[0].response != null
        (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
    }

    // ─── BLK_15: Unsupported Method in Operation ────────────────────────────

    def "BLK_15: Unsupported HTTP method in bulk operation reports 400 invalidValue"() {
        // RFC 7644 §3.7 — Operation method validation
        given: "Operation with GET method which is invalid inside bulk"
        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "GET",
                    path  : "/Users"
                ]
            ]
        ]

        when: "Execute bulk request with GET operation"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall request returns 200 OK"
        response.statusCode() == 200

        and: "Operation result reports status 400 with scimType invalidValue"
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 1
        (ops[0].status as String) == "400"
        ops[0].response != null
        (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
        assertOperationScimType(ops[0] as Map, "invalidValue")
    }

    // ─── BLK_16: Missing Operations Attribute ───────────────────────────────

    def "BLK_16: BulkRequest missing Operations attribute returns 400 Bad Request"() {
        // RFC 7644 §3.7 — BulkRequest schema validation
        given: "A bulk payload without Operations"
        Map bulkPayload = [
            schemas     : [BULK_REQUEST_SCHEMA],
            failOnErrors: 1
        ]

        when: "POST /Bulk without Operations"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Status is 400 Bad Request with SCIM Error schema"
        response.statusCode() == 400
        response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)
        response.jsonPath().getString("status") == "400"
    }

    /**
     * Assert the scimType carried inside a nested BulkResponse operation error.
     *
     * RFC 7644 §3.12 marks "scimType" OPTIONAL, so an omitted keyword is reported as a
     * deviation rather than failing a server that is otherwise compliant.
     */
    private void assertOperationScimType(Map operation, String expectedScimType) {
        String scimType = operation.response?.scimType as String
        if (scimType == null || scimType.isBlank()) {
            ScimOutput.println "DEVIATION: Bulk operation error omits optional scimType " +
                "(expected '${expectedScimType}') (RFC 7644 §3.12)"
            return
        }
        assert scimType == expectedScimType :
            "Expected bulk operation scimType '${expectedScimType}' but got '${scimType}' (RFC 7644 §3.12)"
    }

}

