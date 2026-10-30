package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import groovy.json.JsonOutput
import org.opentest4j.TestAbortedException

/**
 * Area 7 — Bulk Operations
 *
 * Validates SCIM 2.0 Bulk operations per RFC 7644 §3.7 and RFC 7643 §5.
 * Covers bulk POST, PUT, PATCH, DELETE, bulkId cross-referencing in data and paths,
 * operation error isolation, failOnErrors threshold, maxOperations enforcement,
 * unsupported methods, and schema validation.
 *
 * Each feature method is self-contained and cleans up any created or leaked resources
 * so individual failures do not cascade or skip subsequent bulk compliance checks.
 * References:
 *   - RFC 7644 §3.7 (Bulk Operations)
 *   - RFC 7643 §5 (ServiceProviderConfig bulk capability)
 */
@RequiresCapability("bulk")
class A7_BulkOperationsSpec extends ScimBaseSpec {

    def setupSpec() {
        loadServiceProviderConfig()
    }

    // ─── BLK_01: Bulk POST creates multiple users ──────────────────────────

    @SpecRef("RFC7644-3.7-R1")
    @SpecRef("RFC7644-3.7-R3")
    def "BLK_01: Bulk POST creates multiple users in a single request"() {
        // RFC 7644 §3.7 — Bulk Operations
        given:
        String suffix1 = UUID.randomUUID().toString().substring(0, 8)
        String suffix2 = UUID.randomUUID().toString().substring(0, 8)
        String id1 = null
        String id2 = null

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

        when: "Capture created user IDs"
        operations.each { Map op ->
            String location = op.location as String
            if (location) {
                String id = location.tokenize("/").last()
                if (op.bulkId == "user1") {
                    id1 = id
                } else if (op.bulkId == "user2") id2 = id
                createdUserIds << id
            }
        }

        then: "Both user IDs were resolved and returned in location"
        id1 != null
        id2 != null
    }

    // ─── BLK_02: Bulk DELETE removes multiple users ─────────────────────────

    @SpecRef("RFC7644-3.7-R1")
    def "BLK_02: Bulk DELETE removes multiple users in a single request"() {
        // RFC 7644 §3.7 — Bulk Operations
        given:
        Response u1 = createUser()
        Response u2 = createUser()
        assert u1.statusCode() == 201 && u2.statusCode() == 201
        String delId1 = u1.jsonPath().getString("id")
        String delId2 = u2.jsonPath().getString("id")

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [method: "DELETE", path: "/Users/${delId1}"],
                [
                    method: "DELETE",
                    path  : "/Users/${delId2}"
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
        createdUserIds.remove(delId1)
        createdUserIds.remove(delId2)

        Response get1 = scimRequestQuiet().get("/Users/${delId1}")
        Response get2 = scimRequestQuiet().get("/Users/${delId2}")

        then: "Both users are confirmed deleted (404)"
        get1.statusCode() == 404
        get2.statusCode() == 404
    }

    // ─── BLK_03: Bulk respects maxOperations ────────────────────────────────

    @SpecRef("RFC7644-3.7.4-R1")
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

        then: "Server returns 413 Payload Too Large (RFC 7644 §3.7.4)"
        response.statusCode() == 413
        assertScimError(response, 413)
        String detail = response.jsonPath().getString("detail")
        assert detail != null && !detail.isBlank() : "413 error response MUST specify the exceeded limit in detail (RFC 7644 §3.7.4)"
    }

    // ─── BLK_04: failOnErrors stops processing after N errors ───────────────

    @SpecRef("RFC7644-3.7-R6")
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

        then: "Server returns 200 with operations, but stops after error threshold is reached"
        response.statusCode() == 200
        def operations = response.jsonPath().getList("Operations")
        // Server stops after 1 error per RFC 7644 §3.7.3
        operations.size() == 1
        (operations[0].status as String) in ["404", "400"]
    }

    // ─── BLK_05: Missing BulkRequest schema returns 400 ────────────────────

    @SpecRef("RFC7644-3.7-R1")
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
        response.jsonPath().get("status") instanceof String
        response.jsonPath().getString("status") == "400"
    }

    // ─── BLK_06: Wrong schema in BulkRequest returns 400 ───────────────────

    @SpecRef("RFC7644-3.7-R1")
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
        response.jsonPath().get("status") instanceof String
        response.jsonPath().getString("status") == "400"
    }

    // ─── BLK_07: bulkId Cross-Referencing in Data (User -> Group) ───────────

    @SpecRef("RFC7644-3.7-R4")
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

    @SpecRef("RFC7644-3.7-R4")
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
                    method: "PUT",
                    path  : "/Users/bulkId:chainUser",
                    data  : [
                        schemas    : [USER_SCHEMA],
                        userName   : userName,
                        displayName: "Chained Updated Name",
                        emails     : [[value: userName, type: "work", primary: true]]
                    ]
                ],
                [
                    method: "DELETE",
                    path  : "/Users/bulkId:chainUser"
                ]
            ]
        ]

        when: "POST creates, PUT updates, and DELETE removes the user in one bulk request"
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

    @SpecRef("RFC7644-3.7-R1")
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

    @RequiresCapability("patch")
    @SpecRef("RFC7644-3.7-R1")
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

        then: "Status is 200 and operation reports 200 OK or 204 No Content"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 1
        (ops[0].status as String) in ["200", "204"]

        when: "Verify active flag via GET"
        Response getResp = scimRequest().get("/Users/${userId}")

        then: "User active status was updated to false"
        getResp.statusCode() == 200
        getResp.jsonPath().getBoolean("active") == false

        cleanup:
        if (userId) deleteUser(userId)
    }

    // ─── BLK_11: Mixed Operations in Single Request ─────────────────────────

    @SpecRef("RFC7644-3.7-R1")
    def "BLK_11: Bulk request with mixed operations (POST, PUT, DELETE) all succeed"() {
        // RFC 7644 §3.7 — Mixed methods in single Bulk request
        given: "Pre-create users for PUT and DELETE operations"
        Response uPut = createUser(userName: "mixed_put_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        Response uDel = createUser(userName: "mixed_del_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert uPut.statusCode() == 201 && uDel.statusCode() == 201

        String putId = uPut.jsonPath().getString("id")
        String putName = uPut.jsonPath().getString("userName")
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
        ops.size() == 3

        and: "Operation 0 (POST) succeeded with 201"
        (ops[0].status as String) == "201"
        String createdPostId = (ops[0].location as String)?.tokenize("/")?.last()
        if (createdPostId) createdUserIds << createdPostId

        and: "Operation 1 (PUT) succeeded with 200"
        (ops[1].status as String) == "200"

        and: "Operation 2 (DELETE) succeeded with 204"
        (ops[2].status as String) == "204"
        createdUserIds.remove(delId)

        when: "Verify deleted user is 404"
        Response getDel = scimRequestQuiet().get("/Users/${delId}")

        then:
        getDel.statusCode() == 404

        cleanup:
        if (putId) deleteUser(putId)
        if (createdPostId) deleteUser(createdPostId)
    }

    // ─── BLK_12: Operation Error Isolation on 404 ───────────────────────────

    @SpecRef("RFC7644-3.7-R5")
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

    @SpecRef("RFC7644-3.7-R5")
    def "BLK_13: Duplicate userName in bulk POST reports 409 uniqueness while overall request returns 200"() {
        // RFC 7644 §3.7, §3.12 — Uniqueness conflict inside bulk
        given: "An existing user"
        String dupName = "dup_bulk_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String secondName = "valid_bulk_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
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
                ],
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "subsequentValidUser",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: secondName
                    ]
                ]
            ]
        ]

        when: "POST duplicate userName followed by a valid user via bulk"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall HTTP status is 200 OK"
        response.statusCode() == 200

        and: "First operation reports 409 uniqueness and second operation succeeds with 201"
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 2
        (ops[0].status as String) == "409"
        ops[0].response != null
        (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
        (ops[0].response.status as String) == "409"
        assertOperationScimType(ops[0] as Map, "uniqueness")

        (ops[1].status as String) == "201"
        String secondId = (ops[1].location as String)?.tokenize("/")?.last()
        if (secondId) createdUserIds << secondId

        cleanup:
        if (secondId) deleteUser(secondId)
        if (existingId) deleteUser(existingId)
    }

    // ─── BLK_14: Unresolved bulkId Reference Handling ───────────────────────

    @SpecRef("RFC7644-3.7-R4")
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

    @SpecRef("RFC7644-3.7-R1")
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

        then: "Overall request returns 200 OK with operation 400 or top-level 400 Bad Request"
        response.statusCode() in [200, 400]
        if (response.statusCode() == 400) {
            assertScimError(response, 400)
        } else {
            def ops = response.jsonPath().getList("Operations")
            assert ops.size() == 1
            assert (ops[0].status as String) == "400"
            assert ops[0].response != null
            assert (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
            assertOperationOptionalScimType(ops[0] as Map, "invalidValue")
        }
    }

    // ─── BLK_16: Missing Operations Attribute ───────────────────────────────

    @SpecRef("RFC7644-3.7-R1")
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
        response.jsonPath().get("status") instanceof String
        response.jsonPath().getString("status") == "400"
    }

    // ─── BLK_17: Unknown Resource Path in Bulk Operation ────────────────────

    @SpecRef("RFC7644-3.7-R1")
    def "BLK_17: Bulk operation targeting an unknown resource path reports error (400 or 404)"() {
        // RFC 7644 §3.7, §3.12 — Unknown resource path inside bulk operation
        given: "Bulk request with unknown resource path"
        String randomId = UUID.randomUUID().toString()
        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/UnknownResource",
                    bulkId: "unknownPost",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "unknown_resource@test.com"
                    ]
                ],
                [
                    method: "PUT",
                    path  : "/InvalidEndpoint/${randomId}",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "unknown_put@test.com"
                    ]
                ]
            ]
        ]

        when: "Execute bulk request with unknown resource path"
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall response is 200 OK"
        response.statusCode() == 200

        and: "Operations report 400 or 404 with SCIM Error schema"
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 2

        // POST /UnknownResource
        (ops[0].status as String) in ["400", "404"]
        ops[0].response != null
        (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
        if ((ops[0].status as String) == "400") {
            assertOperationOptionalScimType(ops[0] as Map, ["invalidValue", "invalidPath"])
        }

        // PUT /InvalidEndpoint/{id}
        assert (ops[1].status as String) in ["400", "404"] :
            "Second operation in bulk response MUST include status 400 or 404 (RFC 7644 §3.7)"
        assert ops[1].response != null
        assert (ops[1].response.schemas as List)?.contains(ERROR_SCHEMA)
        if ((ops[1].status as String) == "400") {
            assertOperationOptionalScimType(ops[1] as Map, ["invalidValue", "invalidPath"])
        }
    }

    // ─── BLK_18: Bulk POST with Non-Canonical Paths ─────────────────────────

    @SpecRef("RFC7644-3.7-R1")
    def "BLK_18: Bulk POST with non-canonical paths (double slashes '//Users', relative 'Users', trailing slash '/Users/')"() {
        // RFC 7644 §3.7 defines '/Users'; non-canonical forms are optional leniency (regression test for the double-slash issue)
        given: "Build bulk request with non-canonical paths: double slash, no leading slash, trailing slash"
        String suffix1 = UUID.randomUUID().toString().substring(0, 8)
        String suffix2 = UUID.randomUUID().toString().substring(0, 8)
        String suffix3 = UUID.randomUUID().toString().substring(0, 8)

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "//Users",
                    bulkId: "doubleSlashUser",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "noncanonical_double_${suffix1}@test.com",
                        emails  : [[value: "noncanonical_double_${suffix1}@test.com", type: "work", primary: true]]
                    ]
                ],
                [
                    method: "POST",
                    path  : "Users",
                    bulkId: "noSlashUser",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "noncanonical_noslash_${suffix2}@test.com",
                        emails  : [[value: "noncanonical_noslash_${suffix2}@test.com", type: "work", primary: true]]
                    ]
                ],
                [
                    method: "POST",
                    path  : "/Users/",
                    bulkId: "trailingSlashUser",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: "noncanonical_trailing_${suffix3}@test.com",
                        emails  : [[value: "noncanonical_trailing_${suffix3}@test.com", type: "work", primary: true]]
                    ]
                ]
            ]
        ]

        when: "Execute bulk request with non-canonical paths"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")
        def operations = response.jsonPath().getList("Operations") ?: []
        operations.each { Map op ->
            String location = op.location as String
            if (location) {
                String id = location.tokenize("/").last()
                createdUserIds << id
            }
        }

        then: "Status is 200 OK and response contains BulkResponse schema"
        response.statusCode() == 200
        response.jsonPath().getList("schemas")?.contains(BULK_RESPONSE_SCHEMA)
        operations.size() == 3

        and: "Each operation succeeded with 201 or was rejected with a valid SCIM Error response"
        operations.eachWithIndex { Map op, int idx ->
            String status = op.status as String
            String path = bulkPayload.Operations[idx].path
            if (status == "201") {
                assert op.location != null : "Expected location header for created resource at path '${path}'"
            } else {
                assert status in ["400", "404"] :
                    "Expected 201 or 4xx for non-canonical path '${path}', but got status ${status}"
                assert op.response != null :
                    "Error response missing for rejected non-canonical path '${path}'"
                assert (op.response.schemas as List)?.contains(ERROR_SCHEMA) :
                    "Error response missing Error schema for rejected non-canonical path '${path}'"
            }
        }
    }

    // ─── BLK_19: Bulk PUT and DELETE with Non-Canonical Paths ───────

    @SpecRef("RFC7644-3.7-R1")
    def "BLK_19: Bulk PUT and DELETE with non-canonical paths ('//Users/{id}', 'Users/{id}')"() {
        // RFC 7644 §3.7 — Non-canonical path handling on resource updates
        given: "Pre-create users for PUT and DELETE operations"
        Response uPut1 = createUser(userName: "noncanon_put1_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        Response uPut2 = createUser(userName: "noncanon_put2_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        Response uDel = createUser(userName: "noncanon_del_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert uPut1.statusCode() == 201 && uPut2.statusCode() == 201 && uDel.statusCode() == 201

        String put1Id = uPut1.jsonPath().getString("id")
        String put1Name = uPut1.jsonPath().getString("userName")
        String put2Id = uPut2.jsonPath().getString("id")
        String put2Name = uPut2.jsonPath().getString("userName")
        String delId = uDel.jsonPath().getString("id")

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "PUT",
                    path  : "//Users/${put1Id}",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: put1Name,
                        title   : "Updated via Double Slash PUT"
                    ]
                ],
                [
                    method: "PUT",
                    path  : "Users/${put2Id}",
                    data  : [
                        schemas : [USER_SCHEMA],
                        userName: put2Name,
                        title   : "Updated via Relative PUT"
                    ]
                ],
                [
                    method: "DELETE",
                    path  : "//Users/${delId}"
                ]
            ]
        ]

        when: "Execute bulk operations with non-canonical paths"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")
        def ops = response.jsonPath().getList("Operations") ?: []

        then: "Overall status is 200 OK and response contains BulkResponse schema"
        response.statusCode() == 200
        response.jsonPath().getList("schemas")?.contains(BULK_RESPONSE_SCHEMA)
        ops.size() == 3

        and: "Operation 0 (PUT) succeeded or reported standard SCIM Error"
        String put1Status = ops[0].status as String
        if (put1Status == "200") {
            assert (ops[0].location as String)?.contains(put1Id)
        } else {
            assert put1Status in ["400", "404"] : "Expected 200 or 4xx for non-canonical PUT, got ${put1Status}"
            assert ops[0].response != null && (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
        }

        and: "Operation 1 (PUT) succeeded or reported standard SCIM Error"
        String put2Status = ops[1].status as String
        if (put2Status == "200") {
            assert (ops[1].location as String)?.contains(put2Id)
        } else {
            assert put2Status in ["400", "404"] : "Expected 200 or 4xx for non-canonical PUT, got ${put2Status}"
            assert ops[1].response != null && (ops[1].response.schemas as List)?.contains(ERROR_SCHEMA)
        }

        and: "Operation 2 (DELETE) succeeded (204) or reported standard SCIM Error"
        String delStatus = ops[2].status as String
        if (delStatus == "204") {
            createdUserIds.remove(delId)
        } else {
            assert delStatus in ["400", "404"] : "Expected 204 or 4xx for non-canonical DELETE, got ${delStatus}"
            assert ops[2].response != null && (ops[2].response.schemas as List)?.contains(ERROR_SCHEMA)
        }

        cleanup:
        if (put1Id) deleteUser(put1Id)
        if (put2Id) deleteUser(put2Id)
        if (delId) deleteUser(delId)
    }

    // ─── BLK_20: bulkId is REQUIRED for each operation when method is POST ───

    @SpecRef("RFC7644-3.7-R2")
    def "BLK_20: Bulk POST operation missing required bulkId returns 400 or operation error"() {
        // RFC 7644 §3.7: "bulkId: The transient identifier of a newly created resource,
        // unique within a bulk request and created by the client. The bulkId attribute is
        // mandatory for 'POST' operations."
        given:
        String noBulkIdUserName = "nobulkid_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        List<String> leakedUserIds = []
        Map bulkPayload = [schemas: [BULK_REQUEST_SCHEMA], Operations: [[method: "POST", path: "/Users", data: [schemas: [USER_SCHEMA], userName: noBulkIdUserName]]]]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server rejects either the whole request (400) or reports 400 for the operation (RFC 7644 §3.7)"
        assert response.statusCode() in [200, 400] : "Expected status 200 or 400, got ${response.statusCode()}"
        if (response.statusCode() == 400) {
            assertScimError(response, 400)
        } else {
            def ops = response.jsonPath().getList("Operations")
            assert ops != null && ops.size() == 1
            String createdId = (ops[0].location as String)?.tokenize("/")?.last() ?: (ops[0].response?.id as String)
            if (createdId) leakedUserIds << createdId
            assert (ops[0].status as String) == "400"
            assert ops[0].response != null
            assert (ops[0].response.schemas as List)?.contains(ERROR_SCHEMA)
        }
        cleanup:
        if (leakedUserIds.isEmpty() && isFilterSupported()) {
            try {
                Response r = scimRequestQuiet().queryParam("filter", "userName eq \"${noBulkIdUserName}\"").get("/Users")
                if (r.statusCode() == 200) leakedUserIds.addAll(r.jsonPath().getList("Resources.id")?.findAll { it != null } ?: [])
            } catch (Exception ignored) {}
        }
        leakedUserIds.unique().each { String id -> try { deleteUser(id) } catch (Exception ignored) {} }
    }

    // ─── BLK_21: Per-operation version attribute for conditional updates ────

    @SpecRef("RFC7644-3.7-R7")
    def "BLK_21: Bulk operation supports version attribute for conditional updates"() {
        // RFC 7644 §3.7: "version: The current resource version. Each operation MAY include a
        // version attribute... If the current version does not match, the operation fails."
        given: "Create a user to update conditionally via bulk"
        Response cr = createUser(userName: "bulk_ver_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
        assert cr.statusCode() == 201
        String uid = cr.jsonPath().getString("id")
        createdUserIds << uid

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method : "PUT",
                    path   : "/Users/${uid}",
                    version: 'W/"999999_mismatch"',
                    data   : [
                        schemas : [USER_SCHEMA],
                        userName: "bulk_ver_updated_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
                    ]
                ]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Operation returns 412 Precondition Failed (or aborts if optional version precondition unsupported)"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops != null && ops.size() == 1
        String status = ops[0].status as String
        if (status == "412") {
            assert (ops[0].response?.schemas as List)?.contains(ERROR_SCHEMA)
        } else {
            throw new TestAbortedException("Server does not support optional bulk version precondition (status: ${status})")
        }

        cleanup:
        if (uid) deleteUser(uid)
    }

    // ─── BLK_22: Continue after partial failure ─────────────────────────────

    @SpecRef("RFC7644-3.7-R5")
    def "BLK_22: Bulk operations continue and execute subsequent operations after partial failure"() {
        // RFC 7644 §3.7: "The service provider MUST continue performing as many changes as
        // possible and disregard partial failures."
        given: "Bulk request with Success -> Failure -> Success operations"
        String u1 = "bulk_cont_1_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String u3 = "bulk_cont_3_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        String fakeId = UUID.randomUUID().toString()

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "contUser1",
                    data  : [schemas: [USER_SCHEMA], userName: u1]
                ],
                [
                    method: "DELETE",
                    path  : "/Users/${fakeId}"
                ],
                [
                    method: "POST",
                    path  : "/Users",
                    bulkId: "contUser3",
                    data  : [schemas: [USER_SCHEMA], userName: u3]
                ]
            ]
        ]

        when: "POST to /Bulk"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Overall status is 200 OK"
        response.statusCode() == 200
        def ops = response.jsonPath().getList("Operations")
        ops.size() == 3

        and: "First operation succeeded with 201"
        (ops[0].status as String) == "201"
        String id1 = (ops[0].location as String)?.tokenize("/")?.last()
        if (id1) createdUserIds << id1

        and: "Second operation failed with 404"
        (ops[1].status as String) == "404"

        and: "Third operation succeeded with 201 despite prior failure"
        (ops[2].status as String) == "201"
        String id3 = (ops[2].location as String)?.tokenize("/")?.last()
        if (id3) createdUserIds << id3

        cleanup:
        if (id1) deleteUser(id1)
        if (id3) deleteUser(id3)
    }

    // ─── BLK_23: Circular bulkId cross-references ───────────────────────────

    @SpecRef("RFC7644-3.7-R4")
    def "BLK_23: Circular bulkId cross-reference between resources is handled gracefully"() {
        // RFC 7644 §3.7 — Circular bulkId reference handling
        given: "Two groups referencing each other via bulkId"
        String g1 = "grp_circ_1_${UUID.randomUUID().toString().substring(0, 8)}"
        String g2 = "grp_circ_2_${UUID.randomUUID().toString().substring(0, 8)}"
        List<String> createdGroupIds = []

        Map bulkPayload = [
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [
                    method: "POST",
                    path  : "/Groups",
                    bulkId: "circG1",
                    data  : [
                        schemas    : [GROUP_SCHEMA],
                        displayName: g1,
                        members    : [[value: "bulkId:circG2", type: "Group"]]
                    ]
                ],
                [
                    method: "POST",
                    path  : "/Groups",
                    bulkId: "circG2",
                    data  : [
                        schemas    : [GROUP_SCHEMA],
                        displayName: g2,
                        members    : [[value: "bulkId:circG1", type: "Group"]]
                    ]
                ]
            ]
        ]

        when: "POST circular bulk request"
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server resolves circular references or rejects with 409/per-op 400 per §3.7.1 / §3.7.3"
        assert response.statusCode() in [200, 409] : "Expected HTTP 200 or 409 per RFC 7644 §3.7.1, but got ${response.statusCode()}"
        if (response.statusCode() == 409) {
            assertScimError(response, 409)
        } else {
            List<Map> ops = response.jsonPath().getList("Operations")
            assert ops != null && ops.size() == 2
            ops.each { Map op ->
                String id = (op.location as String)?.tokenize("/")?.last() ?: (op.response?.id as String)
                if (id) createdGroupIds << id
            }
            if (ops.every { (it.status as String) == "201" }) {
                String id1 = ops[0].location ? (ops[0].location as String).tokenize("/").last() : (ops[0].response?.id as String)
                String id2 = ops[1].location ? (ops[1].location as String).tokenize("/").last() : (ops[1].response?.id as String)
                if (id1 && id2) {
                    Response g1Resp = scimRequestQuiet().get("/Groups/${id1}")
                    Response g2Resp = scimRequestQuiet().get("/Groups/${id2}")
                    assert g1Resp.statusCode() == 200
                    assert g2Resp.statusCode() == 200
                    def m1 = g1Resp.jsonPath().getList("members.value")
                    def m2 = g2Resp.jsonPath().getList("members.value")
                    assert m1?.contains(id2) : "Group 1 members must contain resolved Group 2 ID (RFC 7644 §3.7.1)"
                    assert m2?.contains(id1) : "Group 2 members must contain resolved Group 1 ID (RFC 7644 §3.7.1)"
                }
            } else {
                assert ops.every { (it.status as String) in ["400", "409"] } :
                    "Expected all circular bulkId operations to either resolve (201) or fail with 400/409, but got statuses ${ops.collect { it.status }} (RFC 7644 §3.7.1 / §3.7.3)"
                assert createdGroupIds.isEmpty() : "Failed circular bulkId operations must not create resources, but found IDs ${createdGroupIds}"
                ops.each { Map op ->
                    assert op.location == null : "Failed bulk operation must not return a location"
                    assert (op.response?.schemas as List)?.contains(ERROR_SCHEMA) :
                        "Failed bulk operation must include SCIM Error schema in response (RFC 7644 §3.7.3)"
                }
                if (isFilterSupported()) {
                    Response checkResp = scimRequestQuiet()
                        .queryParam("filter", "displayName eq \"${g1}\" or displayName eq \"${g2}\"")
                        .get("/Groups")
                    assert checkResp.statusCode() == 200
                    List<String> leakedIds = checkResp.jsonPath().getList("Resources.id")?.findAll { it != null } ?: []
                    createdGroupIds.addAll(leakedIds)
                    assert checkResp.jsonPath().getInt("totalResults") == 0 :
                        "Failed circular bulkId operations must not persist group resources"
                }
            }
        }

        cleanup:
        if (createdGroupIds.isEmpty() && isFilterSupported()) {
            try {
                Response cleanupResp = scimRequestQuiet()
                    .queryParam("filter", "displayName eq \"${g1}\" or displayName eq \"${g2}\"")
                    .get("/Groups")
                if (cleanupResp.statusCode() == 200) {
                    List<String> leakedIds = cleanupResp.jsonPath().getList("Resources.id")?.findAll { it != null } ?: []
                    createdGroupIds.addAll(leakedIds)
                }
            } catch (Exception ignored) {}
        }
        createdGroupIds.unique().each { String id ->
            try { scimRequestQuiet().delete("/Groups/${id}") } catch (Exception ignored) {}
        }
    }

    /**
     * Assert the scimType carried inside a nested BulkResponse operation error (required scimType).
     */
    private void assertOperationScimType(Map operation, Collection<String> expectedScimTypes) {
        String scimType = operation.response?.scimType as String
        assert scimType != null && !scimType.isBlank() :
            "Expected bulk operation to carry scimType in ${expectedScimTypes} (RFC 7644 §3.12)"
        assert scimType in expectedScimTypes :
            "Expected bulk operation scimType in ${expectedScimTypes} but got '${scimType}' (RFC 7644 §3.12)"
    }

    private void assertOperationScimType(Map operation, String expectedScimType) {
        assertOperationScimType(operation, [expectedScimType])
    }

    /**
     * Assert the scimType carried inside a nested BulkResponse operation error when scimType is optional.
     */
    private void assertOperationOptionalScimType(Map operation, Collection<String> allowedScimTypes) {
        String scimType = operation.response?.scimType as String
        if (scimType != null && !scimType.isBlank()) {
            assert scimType in allowedScimTypes :
                "Expected bulk operation scimType in ${allowedScimTypes} but got '${scimType}' (RFC 7644 §3.12)"
        }
    }

    private void assertOperationOptionalScimType(Map operation, String allowedScimType) {
        assertOperationOptionalScimType(operation, [allowedScimType])
    }

}

