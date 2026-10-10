package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 10 — Cursor-Based Pagination (RFC 9865)
 *
 * Validates cursor pagination semantics per RFC 9865 when supported
 * by the SCIM service provider (discovered via ServiceProviderConfig or target config).
 */
@Stepwise
@RequiresCapability("cursorPagination")
class A10_CursorPaginationSpec extends ScimBaseSpec {

    @Shared String firstNextCursor

    def setupSpec() {
        loadServiceProviderConfig()
        // Ensure at least 3 users exist for cursor navigation
        3.times { i ->
            def resp = createUser(userName: "cursor_${i}_${UUID.randomUUID().toString().substring(0, 8)}@test.com")
            if (resp.statusCode() == 201) {
                createdUserIds << resp.jsonPath().getString("id")
            }
        }
    }

    // ─── CUR_01: GET with cursor and count ──────────────────────────────────

    @SpecRef("RFC9865-2-R1")
    @SpecRef("RFC9865-2-R2")
    def "CUR_01: GET /Users with cursor parameter returns ListResponse with nextCursor"() {
        // RFC 9865 §2 — Cursor-based query
        when: "Request users with count=1 to force pagination cursor"
        Response response = scimRequest()
            .queryParam("count", 1)
            .get("/Users")
        firstNextCursor = response.jsonPath().getString("nextCursor")

        then: "Status is 200 OK"
        response.statusCode() == 200

        and: "Response contains ListResponse schema"
        response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)

        and: "nextCursor recorded if present or logged as deviation"
        if (firstNextCursor == null) {
            ScimOutput.deviation("Server did not return nextCursor despite count=1 and remaining resources", "CUR_01", "RFC9865-2-R2")
        }
    }

    // ─── CUR_02: Page forward with nextCursor ───────────────────────────────

    @SpecRef("RFC9865-2-R1")
    def "CUR_02: GET /Users passing cursor returns next page of resources"() {
        // RFC 9865 §2 — Fetching subsequent page using cursor
        given: "Check cursor availability"
        if (!firstNextCursor) {
            ScimOutput.println "NOTE: Skipping CUR_02 because nextCursor was not returned in CUR_01"
            return
        }

        when: "Request next page using cursor"
        Response response = scimRequest()
            .queryParam("cursor", firstNextCursor)
            .queryParam("count", 1)
            .get("/Users")

        then: "Status is 200"
        response.statusCode() == 200
        assert response.jsonPath().getList("Resources") != null
    }

    // ─── CUR_03: SearchRequest with cursor ──────────────────────────────────

    @RequiresCapability("search")
    @SpecRef("RFC9865-2-R1")
    def "CUR_03: POST /.search with cursor attribute in SearchRequest body"() {
        // RFC 9865 §2 — cursor in SearchRequest
        given: "SearchRequest payload with cursor and count"
        Map payload = [
            schemas: [SEARCH_REQUEST_SCHEMA],
            count  : 1
        ]
        if (firstNextCursor) {
            payload.cursor = firstNextCursor
        }

        when: "POST to /.search"
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .post("/.search")

        then: "Status is 200"
        response.statusCode() in [200, 501]
        if (response.statusCode() == 200) {
            assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)
        }
    }

    def cleanupSpec() {
        createdUserIds.each { id ->
            if (id) {
                try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
            }
        }
    }
}
