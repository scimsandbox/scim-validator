package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.A5_BaseSpec
import io.restassured.response.Response

/**
 * Area 5b — Pagination
 *
 * Validates SCIM 2.0 pagination (RFC 7644 §3.4.2.4).
 */
class A5_PaginationSpec extends A5_BaseSpec {

    // ─── PAG_01: Pagination traversal with startIndex and count ─────────────

    def "PAG_01: Pagination with startIndex and count returns correct page"() {
        // RFC 7644 §3.4.2.4 — Pagination
        when: "Request page of 2 starting at index 1"
        Response page1 = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("startIndex", 1)
            .queryParam("count", 2)
            .get("/Users")

        then:
        page1.statusCode() == 200
        page1.jsonPath().getInt("totalResults") >= 5
        page1.jsonPath().getInt("startIndex") == 1
        page1.jsonPath().getInt("itemsPerPage") == 2
        page1.jsonPath().getList("Resources").size() == 2
        page1.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)

        when: "Request second page starting at index 3"
        Response page2 = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("startIndex", 3)
            .queryParam("count", 2)
            .get("/Users")

        then:
        page2.statusCode() == 200
        page2.jsonPath().getInt("startIndex") == 3
        page2.jsonPath().getInt("itemsPerPage") == 2
        page2.jsonPath().getList("Resources").size() == 2
        page2.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)

        when: "Request third page starting at index 5"
        Response page3 = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("startIndex", 5)
            .queryParam("count", 2)
            .get("/Users")

        then:
        page3.statusCode() == 200
        page3.jsonPath().getInt("startIndex") == 5
        page3.jsonPath().getInt("itemsPerPage") >= 1
        page3.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)

        and: "Pages return mutually disjoint users"
        def page1Ids = page1.jsonPath().getList("Resources.id")
        def page2Ids = page2.jsonPath().getList("Resources.id")
        def page3Ids = page3.jsonPath().getList("Resources.id")
        page1Ids.intersect(page2Ids).isEmpty()
        page1Ids.intersect(page3Ids).isEmpty()
        page2Ids.intersect(page3Ids).isEmpty()
    }

    // ─── PAG_02: count=0 returns totalResults but no Resources ──────────────

    def "PAG_02: Requesting count=0 returns totalResults but no resources"() {
        // RFC 7644 §3.4.2.4 — count of zero indicates a request for no Resources
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("count", 0)
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
        // Resources should be empty or absent
        def resources = response.jsonPath().getList("Resources")
        resources == null || resources.isEmpty()
    }

    // ─── PAG_03: startIndex=0 boundary treated as 1 ─────────────────────────

    def "PAG_03: startIndex=0 is interpreted as 1"() {
        // RFC 7644 §3.4.2.4: "A value less than 1 SHALL be interpreted as 1."
        when: "Query with startIndex=0"
        Response response0 = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("startIndex", 0)
            .queryParam("count", 2)
            .get("/Users")

        and: "Query with startIndex=1 for comparison"
        Response response1 = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("startIndex", 1)
            .queryParam("count", 2)
            .get("/Users")

        then:
        response0.statusCode() == 200
        response0.jsonPath().getInt("startIndex") == 1
        response0.jsonPath().getInt("itemsPerPage") == 2
        response0.jsonPath().getList("Resources.id") == response1.jsonPath().getList("Resources.id")
    }

    // ─── PAG_04: Out-of-bounds startIndex returns empty Resources ───────────

    def "PAG_04: startIndex greater than totalResults returns empty Resources"() {
        // RFC 7644 §3.4.2.4: "If the specified startIndex is greater than the number of items of results,
        // the service provider MUST return no results (empty "Resources" element) and the "totalResults"
        // MUST be the number of items that match the specified filter."
        when: "Query with very high startIndex"
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("startIndex", 9999)
            .queryParam("count", 10)
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
        response.jsonPath().getInt("startIndex") == 9999
        def resources = response.jsonPath().getList("Resources")
        resources == null || resources.isEmpty()
        def itemsPerPage = response.jsonPath().get("itemsPerPage")
        itemsPerPage == null || itemsPerPage == 0
    }

    // ─── PAG_05: Negative count is treated as 0 ─────────────────────────────

    def "PAG_05: Negative count is interpreted as 0"() {
        // RFC 7644 §3.4.2.4: "A negative value SHALL be interpreted as '0'."
        when: "Query with count=-1"
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("count", -1)
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
        def resources = response.jsonPath().getList("Resources")
        resources == null || resources.isEmpty()
    }

    // ─── PAG_06: Pagination on /Groups ──────────────────────────────────────

    def "PAG_06: Pagination on Groups endpoint supports startIndex and count"() {
        // RFC 7644 §3.4.2.4 — Pagination applies to all searchable resource endpoints
        given: "Create 3 groups"
        String g1 = "${PREFIX}GrpPageA"
        String g2 = "${PREFIX}GrpPageB"
        String g3 = "${PREFIX}GrpPageC"
        Response r1 = createGroup(g1)
        Response r2 = createGroup(g2)
        Response r3 = createGroup(g3)
        assert r1.statusCode() == 201
        assert r2.statusCode() == 201
        assert r3.statusCode() == 201
        String gid1 = r1.jsonPath().getString("id")
        String gid2 = r2.jsonPath().getString("id")
        String gid3 = r3.jsonPath().getString("id")

        when: "Request first page of 2 groups"
        Response page1 = scimRequest()
            .queryParam("filter", "displayName sw \"${PREFIX}GrpPage\"")
            .queryParam("startIndex", 1)
            .queryParam("count", 2)
            .get("/Groups")

        then:
        page1.statusCode() == 200
        page1.jsonPath().getInt("totalResults") >= 3
        page1.jsonPath().getInt("startIndex") == 1
        page1.jsonPath().getInt("itemsPerPage") == 2
        page1.jsonPath().getList("Resources").size() == 2

        when: "Request second page of groups"
        Response page2 = scimRequest()
            .queryParam("filter", "displayName sw \"${PREFIX}GrpPage\"")
            .queryParam("startIndex", 3)
            .queryParam("count", 2)
            .get("/Groups")

        then:
        page2.statusCode() == 200
        page2.jsonPath().getInt("startIndex") == 3
        page2.jsonPath().getInt("itemsPerPage") >= 1
        page2.jsonPath().getList("Resources").size() >= 1

        and: "Group pages return different items"
        def p1Ids = page1.jsonPath().getList("Resources.id")
        def p2Ids = page2.jsonPath().getList("Resources.id")
        p1Ids.intersect(p2Ids).isEmpty()

        cleanup:
        if (gid1) deleteGroup(gid1)
        if (gid2) deleteGroup(gid2)
        if (gid3) deleteGroup(gid3)
        createdGroupIds.remove(gid1)
        createdGroupIds.remove(gid2)
        createdGroupIds.remove(gid3)
    }
}
