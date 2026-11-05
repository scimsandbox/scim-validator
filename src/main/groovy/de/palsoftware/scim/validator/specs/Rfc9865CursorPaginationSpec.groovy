package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import org.opentest4j.TestAbortedException
import io.restassured.response.Response

/**
 * RFC 9865: SCIM Cursor-Based Pagination
 *
 * One feature per requirement of requirements-rfc9865.yaml (area "Cursor-based pagination"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc9865CursorPaginationSpec extends ScimBaseSpec {

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MUST, lines 133-138
    // The cursor value MUST be empty or omitted for the first request of a cursor-paginated query.
    @SpecRef("RFC9865-2-351699")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-351699: cursor: The cursor value MUST be empty or omitted for the first request of a cursor-paginated query."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

when: "the first request of a cursor-paginated query carries an empty cursor"
        Response response = getPage(prefix, "", 1)

        then: "it is accepted as the start of a cursor-paginated query"
        assert response.statusCode() == 200 :
            "RFC 9865 2: a first request with an empty cursor must return 200 but was ${response.statusCode()}"
        assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA) :
            "RFC 9865 2: the response must be a ListResponse"
        assert response.jsonPath().getList("Resources.id")?.size() == 1 :
            "RFC 9865 2: count=1 with an empty cursor must return the first page of one resource"
        assert hasText(response.jsonPath().getString("nextCursor")) :
            "RFC 9865 2: the first page of a cursor-paginated query with more results must carry nextCursor"
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MUST, lines 140-147
    // A negative value SHALL be interpreted as "0".
    @SpecRef("RFC9865-2-11e103")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-11e103: count: A negative value SHALL be interpreted as '0'."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "a negative count is sent"
        Response response = getPage(prefix, "", -5)

        then: "it is interpreted as 0: no resources are returned"
        assert response.statusCode() == 200 :
            "RFC 9865 2: a negative count must be interpreted as 0 and return 200 but was ${response.statusCode()}"
        assert (response.jsonPath().getList("Resources") ?: []).isEmpty() :
            "RFC 9865 2: count=-5 must be interpreted as 0 and return no resources but returned ${response.jsonPath().getList('Resources.id')}"
        assert asInt(response.jsonPath().get("itemsPerPage"), 0) == 0 :
            "RFC 9865 2: itemsPerPage must be 0 for count=-5 but was ${response.jsonPath().get('itemsPerPage')}"
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MUST NOT, lines 140-147
    // When specified, the service provider MUST NOT return more
    @SpecRef("RFC9865-2-c265b6")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-c265b6: count: When specified, the service provider MUST NOT return more"() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        expect: "no page holds more resources than count"
        [1, 2].each { int count ->
            Response response = getPage(prefix, "", count)
            assert response.statusCode() == 200 :
                "RFC 9865 2: GET with count=${count} must return 200 but was ${response.statusCode()}"
            int returned = (response.jsonPath().getList("Resources") ?: []).size()
            assert returned <= count :
                "RFC 9865 2: with count=${count} the service provider MUST NOT return more, but returned ${returned} resources"
            assert asInt(response.jsonPath().get("itemsPerPage"), 0) <= count :
                "RFC 9865 2: itemsPerPage ${response.jsonPath().get('itemsPerPage')} exceeds count=${count}"
        }
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MAY, lines 140-147
    // although it MAY return fewer, results.
    @SpecRef("RFC9865-2-b48f77")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-b48f77: count: although it MAY return fewer, results."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "all pages are traversed with count=2"
        List<Response> pages = traverse(prefix, 2)

        then: "pages may be shorter than count, but each page is consistent and no resource is lost or repeated"
        pages.each { Response page ->
            int returned = (page.jsonPath().getList("Resources") ?: []).size()
            assert returned <= 2 : "RFC 9865 2: a page must not hold more than count=2 resources but held ${returned}"
            if (page.jsonPath().getMap("").containsKey("itemsPerPage")) {
                assert asInt(page.jsonPath().get("itemsPerPage"), -1) == returned :
                    "RFC 9865 2: itemsPerPage ${page.jsonPath().get('itemsPerPage')} must equal the number of resources returned (${returned})"
            }
        }
        List<String> seen = pages.collectMany { it.jsonPath().getList("Resources.id") ?: [] }
        assert seen.size() == seen.toSet().size() : "RFC 9865 2: a resource was returned on more than one page: ${seen}"
        assert seen.toSet() == created.toSet() :
            "RFC 9865 2: returning fewer results per page must not lose resources; expected ${created} but pages held ${seen}"
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MAY, lines 158-165
    // A cursor value string that MAY be used in a subsequent request to obtain the next page of results.
    @SpecRef("RFC9865-2-34fdba")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-34fdba: nextCursor: A cursor value string that MAY be used in a subsequent request to obtain the next page of results."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "the nextCursor of the first page is used in a subsequent request"
        Response first = getPage(prefix, "", 1)
        String nextCursor = first.jsonPath().getString("nextCursor")
        assert hasText(nextCursor) : "RFC 9865 2: first page with more results must carry nextCursor"
        Response second = getPage(prefix, nextCursor, 1)

        then: "it yields the next page of results"
        assert second.statusCode() == 200 :
            "RFC 9865 2: nextCursor must be usable in a subsequent request (200) but status was ${second.statusCode()}"
        List<String> firstIds = first.jsonPath().getList("Resources.id")
        List<String> secondIds = second.jsonPath().getList("Resources.id") ?: []
        assert secondIds.size() == 1 : "RFC 9865 2: the next page must hold one resource but held ${secondIds}"
        assert !firstIds.contains(secondIds[0]) && created.contains(secondIds[0]) :
            "RFC 9865 2: nextCursor must lead to the next page, not repeat the first page (first ${firstIds}, second ${secondIds})"
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MUST, lines 158-165
    // Service providers supporting cursor-based pagination MUST include nextCursor in all paged query responses except when returning the last page.
    @SpecRef("RFC9865-2-5686ee")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-5686ee: nextCursor: Service providers supporting cursor-based pagination MUST include nextCursor in all paged..."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "three resources are paged with count=1"
        List<Response> pages = traverse(prefix, 1)

        then: "every page except the last carries nextCursor (traversal only continues while nextCursor is present)"
        assert pages.size() == 3 :
            "RFC 9865 2: 3 resources with count=1 need 3 pages and nextCursor on the first two, but traversal ended after ${pages.size()} page(s)"
        pages.take(2).eachWithIndex { Response page, int i ->
            assert hasText(page.jsonPath().getString("nextCursor")) :
                "RFC 9865 2: page ${i + 1} is not the last page and must include nextCursor"
        }
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MUST, lines 158-165
    // nextCursor MUST be omitted from a response only to indicate that there are no more result pages.
    @SpecRef("RFC9865-2-8c501a")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-8c501a: nextCursor: nextCursor MUST be omitted from a response only to indicate that there are no more result pages."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "the results are traversed with count=2 and with a count that holds everything"
        List<Response> pages = traverse(prefix, 2)
        Response everything = getPage(prefix, "", 10)

        then: "nextCursor is omitted only after the last resource has been returned"
        List<String> seen = pages.collectMany { it.jsonPath().getList("Resources.id") ?: [] }
        assert !pages.last().jsonPath().getMap("").containsKey("nextCursor") :
            "RFC 9865 2: the traversal ended on a page that still carries nextCursor"
        assert seen.toSet() == created.toSet() :
            "RFC 9865 2: nextCursor was omitted although resources were not yet returned; expected ${created} but saw ${seen}"
        assert everything.jsonPath().getList("Resources.id").toSet() == created.toSet() :
            "RFC 9865 2: count=10 must return all 3 resources on one page but returned ${everything.jsonPath().getList('Resources.id')}"
        assert !everything.jsonPath().getMap("").containsKey("nextCursor") :
            "RFC 9865 2: a page that contains all remaining resources must omit nextCursor"
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MAY, lines 167-171
    // A cursor value string that MAY be used in a subsequent request to obtain the previous page of results.
    @SpecRef("RFC9865-2-5892b9")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-5892b9: previousCursor: A cursor value string that MAY be used in a subsequent request to obtain the previous page..."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "the second page is requested and its previousCursor (if returned) is followed back"
        Response first = getPage(prefix, "", 1)
        Response second = getPage(prefix, first.jsonPath().getString("nextCursor"), 1)
        assert second.statusCode() == 200
        String previousCursor = second.jsonPath().getString("previousCursor")

        then: "if previousCursor is returned, the previous page of results is obtained"
        if (hasText(previousCursor)) {
            Response back = getPage(prefix, previousCursor, 1)
            assert back.statusCode() == 200 :
                "RFC 9865 2: previousCursor must be usable in a subsequent request (200) but status was ${back.statusCode()}"
            assert back.jsonPath().getList("Resources.id") == first.jsonPath().getList("Resources.id") :
                "RFC 9865 2: previousCursor of page 2 must return page 1 (${first.jsonPath().getList('Resources.id')}) but returned ${back.jsonPath().getList('Resources.id')}"
        }
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MAY, lines 167-171
    // Returning previousCursor is OPTIONAL.
    @SpecRef("RFC9865-2-893493")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-893493: previousCursor: Returning previousCursor is OPTIONAL."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "all pages are traversed with count=1"
        List<Response> pages = traverse(prefix, 1)
        List<Response> later = pages.drop(1)

        then: "returning previousCursor is optional, and wherever it is returned it is a usable cursor string"
        assert !later.isEmpty()
        later.each { Response page ->
            if (page.jsonPath().getMap("").containsKey("previousCursor")) {
                assert page.jsonPath().get("previousCursor") instanceof String && hasText(page.jsonPath().getString("previousCursor")) :
                    "RFC 9865 2: previousCursor must be a non-empty cursor value string but was ${page.jsonPath().get('previousCursor')}"
            }
        }
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MUST NOT, lines 167-171
    // previousCursor MUST NOT be returned with the first page.
    @SpecRef("RFC9865-2-70bc13")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-70bc13: previousCursor: previousCursor MUST NOT be returned with the first page."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        expect: "the first page carries no previousCursor"
        [1, 2, 10].each { int count ->
            Response first = getPage(prefix, "", count)
            assert first.statusCode() == 200 : "RFC 9865 2: first page must return 200 but was ${first.statusCode()}"
            assert !first.jsonPath().getMap("").containsKey("previousCursor") :
                "RFC 9865 2: previousCursor MUST NOT be returned with the first page (count=${count}) but was ${first.jsonPath().get('previousCursor')}"
        }
    }

    // RFC 9865 section 2 (Query Parameters and Response Attributes), MAY, lines 239-241
    // Service providers implementing cursor pagination that are unable to estimate totalResults MAY choose to omit the totalResults attribute.
    @SpecRef("RFC9865-2-4cc5ce")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2-4cc5ce: Service providers implementing cursor pagination that are unable to estimate totalResults MAY choose to..."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "a cursor page is requested"
        Response response = getPage(prefix, "", 1)
        assert response.statusCode() == 200 : "RFC 9865 2: cursor page must return 200 but was ${response.statusCode()}"

        then: "totalResults is either omitted or accurate"
        if (response.jsonPath().getMap("").containsKey("totalResults")) {
            assert asInt(response.jsonPath().get("totalResults"), -1) == 3 :
                "RFC 7644 3.4.1 / RFC 9865 2: totalResults must be the total number of matching resources (3) but was ${response.jsonPath().get('totalResults')}"
        }
    }

    // RFC 9865 section 2.1 (Pagination Errors), SHOULD, lines 245-249
    // If a service provider encounters invalid pagination query parameters (invalid cursor value, count value, etc) or other error conditions, the service provider SHOULD return the appropriate HTTP response status code and detailed JSON error response as defined in Section 3.12 of [RFC7644].
    @SpecRef("RFC9865-2.1-38c026")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2.1-38c026: If a service provider encounters invalid pagination query parameters (invalid cursor value, count value,..."() {
        when: "pagination parameters are invalid"
        Response badCursor = scimRequest().queryParam("cursor", "invalid-opaque-cursor-value-999").queryParam("count", 1).get("/Users")
        Response badCount = scimRequest().queryParam("cursor", "").queryParam("count", "not-a-number").get("/Users")

        then: "an HTTP error status and a detailed SCIM error are returned"
        assertScimError(badCursor, 400)
        assert hasText(badCursor.jsonPath().getString("detail")) :
            "RFC 9865 2.1: the error for an invalid cursor should carry a detail message"
        assertScimError(badCount, 400)
        assert hasText(badCount.jsonPath().getString("detail")) :
            "RFC 9865 2.1: the error for an invalid count should carry a detail message"
    }

    // RFC 9865 section 2.1 (Pagination Errors), SHOULD, lines 259-263
    // Cursor value SHOULD be empty to request the first page and set to the nextCursor or previousCursor value for subsequent queries.
    @SpecRef("RFC9865-2.1-a66a36")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2.1-a66a36: invalidCursor: Cursor value SHOULD be empty to request the first page and set to the nextCursor or..."() {
        expect: "cursor values that were never issued are reported as invalidCursor with status 400"
        ["invalid-opaque-cursor-value-999", "AAAA", "0"].each { String cursor ->
            Response response = scimRequest().queryParam("cursor", cursor).queryParam("count", 1).get("/Users")
            assertScimError(response, 400, "invalidCursor")
        }
    }

    // RFC 9865 section 2.3 (Implementing Cursors as the Only Pagination Method), MAY, lines 286-288
    // A service provider MAY require cursor-based pagination to retrieve all results for a query by including a nextCursor value in the response, even when the query does not include the cursor parameter.
    @SpecRef("RFC9865-2.3-12c2fe")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2.3-12c2fe: A service provider MAY require cursor-based pagination to retrieve all results for a query by including a..."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "a query without any cursor parameter is sent"
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${prefix}_\"")
            .queryParam("count", 1)
            .get("/Users")
        assert response.statusCode() == 200 : "RFC 7644 3.4.2: GET /Users must return 200 but was ${response.statusCode()}"
        String nextCursor = response.jsonPath().getString("nextCursor")

        then: "if the server forces cursor pagination on a request without cursor, following nextCursor yields all results; otherwise a valid ListResponse is returned"
        assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA)
        if (hasText(nextCursor)) {
            List<String> seen = new ArrayList<>(response.jsonPath().getList("Resources.id") ?: [])
            int guard = 0
            while (hasText(nextCursor) && guard++ < 10) {
                Response next = getPage(prefix, nextCursor, 1)
                assert next.statusCode() == 200 : "RFC 9865 2.3: following nextCursor must return 200 but was ${next.statusCode()}"
                seen.addAll(next.jsonPath().getList("Resources.id") ?: [])
                nextCursor = next.jsonPath().getString("nextCursor")
            }
            assert seen.toSet() == created.toSet() :
                "RFC 9865 2.3: following nextCursor must return all results; expected ${created} but saw ${seen}"
        }
    }

    // RFC 9865 section 2.4 (Implementing Both Cursors and Index Pagination), MUST, lines 317-320
    // Additionally, service providers supporting both pagination methods MUST choose a default pagination method to use when responding to requests that have not specified a pagination query parameter.
    @SpecRef("RFC9865-2.4-65723e")
    @RequiresCapability("cursorPagination")
    def "RFC9865-2.4-65723e: Additionally, service providers supporting both pagination methods MUST choose a default pagination method..."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "the server advertises both methods"
        def pagination = scimRequest().get("/ServiceProviderConfig").jsonPath().get("pagination")
        if (!(pagination instanceof Map) || pagination.cursor != true || pagination.index != true) {
            throw new TestAbortedException("Server does not advertise support for both pagination methods")
        }
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${prefix}_\"")
            .queryParam("count", 1)
            .get("/Users")
        boolean cursorStyle = response.jsonPath().getMap("").containsKey("nextCursor")
        boolean indexStyle = response.jsonPath().getMap("").containsKey("startIndex")

        then: "a request without pagination parameter is answered with exactly one method"
        assert response.statusCode() == 200 : "RFC 7644 3.4.2: GET /Users must return 200 but was ${response.statusCode()}"
        assert cursorStyle != indexStyle :
            "RFC 9865 2.4: the default pagination method must be one definite method; response has nextCursor=${cursorStyle} and startIndex=${indexStyle}"
        String used = cursorStyle ? "cursor" : "index"
        if (pagination.containsKey("defaultPaginationMethod")) {
            assert pagination.defaultPaginationMethod == used :
                "RFC 9865 2.4/4: advertised defaultPaginationMethod ${pagination.defaultPaginationMethod} differs from the method used (${used})"
        }
    }

    private String newPrefix() {
        return "cur_${UUID.randomUUID().toString().substring(0, 8)}"
    }

    private List<String> createPagedUsers(String prefix, int n) {
        if (!isFilterSupported()) {
            throw new TestAbortedException("filter is not supported; cannot isolate the test data")
        }
        return (1..n).collect { int i ->
            Response response = createUser(userName: "${prefix}_${i}@test.com")
            assert response.statusCode() == 201 : "Setup: user creation must return 201 but was ${response.statusCode()}"
            response.jsonPath().getString("id")
        }
    }

    private Response getPage(String prefix, Object cursor, Object count) {
        def req = scimRequest().queryParam("filter", "userName sw \"${prefix}_\"")
        if (cursor != null) req = req.queryParam("cursor", cursor)
        if (count != null) req = req.queryParam("count", count)
        return req.get("/Users")
    }

    private List<Response> traverse(String prefix, int count) {
        List<Response> pages = []
        String cursor = ""
        while (cursor != null && pages.size() < 10) {
            Response page = getPage(prefix, cursor, count)
            assert page.statusCode() == 200 : "RFC 9865 2: cursor page must return 200 but was ${page.statusCode()}"
            pages << page
            String next = page.jsonPath().getString("nextCursor")
            cursor = hasText(next) ? next : null
        }
        assert cursor == null : "Traversal did not end within 10 pages"
        return pages
    }
}
