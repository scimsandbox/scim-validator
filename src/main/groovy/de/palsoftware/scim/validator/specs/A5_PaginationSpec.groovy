package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.A5_BaseSpec
import io.restassured.response.Response

/**
 * Area 5b — Pagination
 *
 * Validates SCIM 2.0 pagination (RFC 7644 §3.4.2.4).
 */
class A5_PaginationSpec extends A5_BaseSpec {

    // ─── FLT_08: Pagination with startIndex and count ───────────────────────

    def "FLT_08: Pagination with startIndex and count returns correct page"() {
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
        page2.jsonPath().getList("Resources").size() == 2
        page2.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)

        and: "Pages return different users"
        def page1Ids = page1.jsonPath().getList("Resources.id")
        def page2Ids = page2.jsonPath().getList("Resources.id")
        page1Ids.intersect(page2Ids).isEmpty()
    }

    // ─── FLT_09: count=0 returns totalResults but no Resources ──────────────

    def "FLT_09: Requesting count=0 returns totalResults but no resources"() {
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
}
