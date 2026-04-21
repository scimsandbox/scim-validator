package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import io.restassured.RestAssured
import io.restassured.response.Response
import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Stepwise
import spock.lang.IgnoreIf

/**
 * Area 7 — Bulk Operations
 *
 * Validates SCIM 2.0 Bulk operations per RFC 7644 §3.7.
 * Only runs if the ServiceProviderConfig indicates bulk.supported == true.
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

        then:
        response.statusCode() == 200

        and: "Both operations should succeed"
        def operations = response.jsonPath().getList("Operations")
        operations.size() == 2
        operations.every { Map op -> op.status in ["201", 201] }

        when: "Capture created user IDs for subsequent tests"
        operations.each { Map op ->
            // The id might be in location or response data
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

        then:
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

        then:
        response.statusCode() == 200
        def operations = response.jsonPath().getList("Operations")
        operations.size() == 2

        when: "Remove from cleanup since already deleted and verify via GET"
        createdUserIds.remove(bulkCreatedUserId1)
        createdUserIds.remove(bulkCreatedUserId2)

        Response get1 = scimRequestQuiet().get("/Users/${bulkCreatedUserId1}")
        Response get2 = scimRequestQuiet().get("/Users/${bulkCreatedUserId2}")

        then:
        get1.statusCode() == 404
        get2.statusCode() == 404
    }

    // ─── BLK_03: Bulk respects maxOperations ────────────────────────────────

    def "BLK_03: Bulk request exceeding maxOperations returns 413"() {
        // RFC 7644 §3.7 — Server MAY return 413 if too many operations
        given: "Build a bulk request with more operations than maxOperations"
        int maxOps = bulkMaxOperations ?: 100
        int opsCount = maxOps + 1

        List<Map> operations = (1..opsCount).collect { int i ->
            [
                method: "POST",
                path  : "/Users",
                bulkId: "excess_${i}",
                data  : [
                    schemas : [USER_SCHEMA],
                    userName: "excess_${i}_${UUID.randomUUID().toString().substring(0,6)}@test.com",
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
                [method: "DELETE", path: "/Users/nonexistent-id-1"],
                [method: "DELETE", path: "/Users/nonexistent-id-2"],
                [method: "DELETE", path: "/Users/nonexistent-id-3"]
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
                        userName: "noschem_${UUID.randomUUID().toString().substring(0,6)}@test.com",
                        emails  : [[value: "noschem@test.com", type: "work", primary: true]]
                    ]
                ]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server should return 400 Bad Request"
        response.statusCode() == 400
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
                        userName: "wrongschem_${UUID.randomUUID().toString().substring(0,6)}@test.com",
                        emails  : [[value: "wrongschem@test.com", type: "work", primary: true]]
                    ]
                ]
            ]
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(bulkPayload))
            .post("/Bulk")

        then: "Server should return 400 Bad Request"
        response.statusCode() == 400
    }
}
