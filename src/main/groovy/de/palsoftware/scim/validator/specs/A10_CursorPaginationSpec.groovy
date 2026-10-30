package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.CapabilityState
import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 10 — Cursor-Based Pagination (RFC 9865)
 *
 * Validates cursor pagination semantics per RFC 9865 when supported
 * by the SCIM service provider (discovered via /ServiceProviderConfig pagination.cursor).
 */
@Stepwise
@RequiresCapability("cursorPagination")
class A10_CursorPaginationSpec extends ScimBaseSpec {

    @Shared String firstNextCursor
    @Shared List<String> firstPageIds = []
    @Shared String testPrefix = "cur_${UUID.randomUUID().toString().substring(0, 8)}"

    def setupSpec() {
        ensureServiceProviderConfigLoaded()
        if (getCapabilityState("cursorPagination") == CapabilityState.UNSUPPORTED) {
            return
        }
        // Ensure at least 3 users exist for cursor navigation
        3.times { i ->
            def resp = createUser(userName: "${testPrefix}_${i}@test.com")
            if (resp.statusCode() == 201) {
                createdUserIds << resp.jsonPath().getString("id")
            }
        }
        // Verify that target server actually implements cursor pagination when capability is UNKNOWN
        if (getCapabilityState("cursorPagination") == CapabilityState.UNKNOWN) {
            Response probe = scimRequestQuiet().queryParam("cursor", "").queryParam("count", 1).get("/Users")
            if (probe.statusCode() != 200 || !probe.jsonPath().getString("nextCursor")) {
                setCapabilityState("cursorPagination", CapabilityState.UNSUPPORTED)
            }
        }
    }

    // ─── CUR_01: GET with empty cursor and count ────────────────────────────

    @SpecRef("RFC9865-2-R1")
    @SpecRef("RFC9865-2-R2")
    @SpecRef("RFC9865-2-R3")
    def "CUR_01: GET /Users with empty cursor initiates cursor pagination returning nextCursor without previousCursor"() {
        // RFC 9865 §2 & Table 1: Initial query specifies cursor="" (or omitted).
        // Table 2: nextCursor returned when more results remain; previousCursor omitted on first page.
        when: "Request users with empty cursor parameter and count=1"
        Response response = scimRequest()
            .queryParam("cursor", "")
            .queryParam("count", 1)
            .get("/Users")
        firstNextCursor = response.jsonPath().getString("nextCursor")
        firstPageIds = response.jsonPath().getList("Resources.id") ?: []

        if (getCapabilityState("cursorPagination") == CapabilityState.UNKNOWN && (firstNextCursor == null || firstNextCursor.trim().isEmpty())) {
            throw new TestAbortedException("Server does not return nextCursor on initial cursor page")
        }

        then: "Status is 200 OK"
        assert response.statusCode() == 200

        and: "Response is a valid ListResponse"
        assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)

        and: "previousCursor is absent or null on the initial page (RFC 9865 §2 Table 2)"
        assert response.jsonPath().get("previousCursor") == null :
            "previousCursor must not be returned on the initial page (RFC 9865 §2)"

        and: "nextCursor is returned since more resources exist"
        assert firstNextCursor != null && !firstNextCursor.trim().isEmpty() :
            "nextCursor must be returned when more results exist (RFC 9865 §2)"

        and: "Initial page contains resources"
        assert !firstPageIds.isEmpty() : "First page must contain at least one resource"
    }

    // ─── CUR_02: Page forward with nextCursor ───────────────────────────────

    @SpecRef("RFC9865-2-R4")
    def "CUR_02: GET /Users passing cursor returns next page of distinct resources"() {
        // RFC 9865 §2 — Fetching subsequent page using opaque cursor value
        given: "Verify nextCursor availability from initial page"
        if (!firstNextCursor) {
            throw new TestAbortedException("Skipped: no nextCursor obtained from CUR_01")
        }

        when: "Request next page using cursor"
        Response response = scimRequest()
            .queryParam("cursor", firstNextCursor)
            .queryParam("count", 1)
            .get("/Users")

        then: "Status is 200 OK"
        assert response.statusCode() == 200

        and: "Second page resources are returned and distinct from first page"
        List<String> secondPageIds = response.jsonPath().getList("Resources.id") ?: []
        assert !secondPageIds.isEmpty() : "Second page must contain at least one resource"
        assert !firstPageIds.contains(secondPageIds.first()) :
            "Second page resource must be distinct from first page (server must not ignore cursor)"
    }

    // ─── CUR_03: Last page omits nextCursor ─────────────────────────────────

    @SpecRef("RFC9865-2-R5")
    def "CUR_03: Reaching final page of query results omits nextCursor"() {
        // RFC 9865 §2 & Table 2 — nextCursor omitted when no more results exist
        given: "Verify nextCursor availability"
        if (!firstNextCursor) {
            throw new TestAbortedException("Skipped: no nextCursor obtained from CUR_01")
        }

        when: "Initiate a fresh cursor query with high count=100 and advance until final page"
        String currentCursor = ""
        Response lastResponse = null
        int maxPages = 10
        while (currentCursor != null && maxPages-- > 0) {
            lastResponse = scimRequest()
                .queryParam("cursor", currentCursor)
                .queryParam("count", 100)
                .get("/Users")
            assert lastResponse.statusCode() == 200
            currentCursor = lastResponse.jsonPath().getString("nextCursor")
        }

        then: "On the final page, nextCursor is absent or null"
        assert lastResponse != null && lastResponse.statusCode() == 200
        assert lastResponse.jsonPath().get("nextCursor") == null :
            "nextCursor must be omitted on final page of results (RFC 9865 §2)"
    }

    // ─── CUR_04: Invalid cursor returns 400 invalidCursor ───────────────────

    @SpecRef("RFC9865-2.1-R1")
    def "CUR_04: GET /Users with unrecognized cursor returns 400 with scimType invalidCursor"() {
        // RFC 9865 §2.1 & Table 3 — invalidCursor error
        when: "Request users with an unparseable/bogus cursor"
        Response response = scimRequestQuiet()
            .queryParam("cursor", "invalid-opaque-cursor-value-999")
            .get("/Users")

        then: "Server returns HTTP 400 Bad Request with scimType invalidCursor (RFC 9865 §2.1)"
        assertScimError(response, 400, "invalidCursor")
    }

    // ─── CUR_05: SearchRequest with cursor ──────────────────────────────────

    @RequiresCapability("search")
    @SpecRef("RFC9865-3-R1")
    def "CUR_05: POST /.search with cursor attribute in SearchRequest body"() {
        // RFC 9865 §3 — cursor attribute in POST /.search
        given: "SearchRequest payload with cursor and count"
        Map payload = [
            schemas: [SEARCH_REQUEST_SCHEMA],
            cursor : "",
            count  : 1
        ]

        when: "POST to /Users/.search"
        Response response = scimRequest()
            .body(JsonOutput.toJson(payload))
            .post("/Users/.search")

        then: "Status is 200 OK (or aborts if endpoint returns 501)"
        if (response.statusCode() == 501) {
            throw new TestAbortedException("Server does not implement POST /Users/.search (HTTP 501)")
        }
        assert response.statusCode() == 200
        assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)
        assert response.jsonPath().get("previousCursor") == null
        String nextCur = response.jsonPath().getString("nextCursor")
        assert nextCur != null && !nextCur.trim().isEmpty() : "nextCursor must be non-empty in POST /.search cursor response (RFC 9865 §3)"
    }

    // ─── CUR_06: ServiceProviderConfig pagination schema ────────────────────

    @SpecRef("RFC9865-4-R1")
    def "CUR_06: ServiceProviderConfig advertises pagination configuration schema"() {
        // RFC 9865 §4 — pagination discovery attribute.
        // A10_CursorPaginationSpec is gated by @RequiresCapability("cursorPagination"), and setupSpec()
        // marks cursorPagination UNSUPPORTED if cursor probing fails.
        given: "Cursor pagination capability is supported by the target server"
        requireCapability("cursorPagination")

        when: "Fetch /ServiceProviderConfig"
        Response response = scimRequest().get("/ServiceProviderConfig")

        then: "Status is 200 OK"
        assert response.statusCode() == 200

        and: "pagination complex attribute SHOULD be present per RFC 9865 §4 with cursor=true"
        def pag = response.jsonPath().get("pagination")
        assert pag != null : "ServiceProviderConfig SHOULD include 'pagination' configuration attribute (RFC 9865 §4)"
        assert pag.cursor instanceof Boolean : "'pagination.cursor' must be a Boolean"
        assert pag.cursor == true : "'pagination.cursor' must be true when cursor pagination is supported (RFC 9865 §4)"
        assert pag.index instanceof Boolean : "'pagination.index' must be a Boolean"
    }

    def cleanupSpec() {
        createdUserIds.each { id ->
            if (id) {
                try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
            }
        }
    }
}
