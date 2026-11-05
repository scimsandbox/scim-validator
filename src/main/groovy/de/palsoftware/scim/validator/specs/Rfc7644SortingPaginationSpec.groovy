package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Ignore

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "Sorting and pagination"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644SortingPaginationSpec extends ScimBaseSpec {

    // ─── helpers ─────────────────────────────────────────────────────────

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10)
    }

    /** Creates a user (201 required) and returns its id. */
    private String mkUser(String userName, Map extra = [:]) {
        Map overrides = [userName: userName]
        overrides.putAll(extra)
        Response r = createUser(overrides)
        assert r.statusCode() == 201 :
            "setup: POST /Users must return 201 but was ${r.statusCode()}: ${r.body().asString()}"
        return r.jsonPath().getString("id")
    }

    /** Lists the users whose userName starts with the (lower-case, unique) prefix. */
    private Response listUsers(String prefix, Map params = [:]) {
        def req = scimRequest().queryParam("filter", "userName sw \"${prefix}\"")
        params.each { k, v -> req = req.queryParam(k as String, v) }
        return req.get("/Users")
    }

    private static List<String> valuesOf(Response r, String attr) {
        return r.jsonPath().getList("Resources.${attr}") ?: []
    }

    private static List<String> ciSorted(List<String> values) {
        return values.sort(false) { String a, String b -> a.toLowerCase() <=> b.toLowerCase() }
    }

    private static List<String> csSorted(List<String> values) {
        return values.sort(false) { String a, String b -> a <=> b }
    }

    /** Three users with unique prefix; creation order differs from every sort order used by the tests. */
    private String prefixWithPage() {
        String prefix = "pg${tag()}_"
        ["b", "c", "a"].each { String n -> mkUser("${prefix}${n}") }
        return prefix
    }

    // RFC 7644 section 3.4.2.3 (Sorting), MAY, lines 1297-1297
    // Sort is OPTIONAL.
    @SpecRef("RFC7644-3.4.2.3-f280f2")
    @RequiresCapability("sort")
    def "RFC7644-3.4.2.3-f280f2: Sort is OPTIONAL."() {
        given:
        Response spc = scimRequest().get("/ServiceProviderConfig")
        assert spc.statusCode() == 200
        assert spc.jsonPath().get("sort.supported") == true :
            "RFC 7644 3.4.2.3: ServiceProviderConfig sort.supported must be true for sort to be offered"
        String prefix = "srt${tag()}_"
        mkUser("${prefix}b", [displayName: "m2"])
        mkUser("${prefix}c", [displayName: "m3"])
        mkUser("${prefix}a", [displayName: "m1"])

        when:
        Response r = listUsers(prefix, [sortBy: "displayName"])

        then: "a server that advertises sort honors sortBy"
        assert r.statusCode() == 200 : "RFC 7644 3.4.2.3: advertised sort support, but sortBy request returned ${r.statusCode()}"
        assert valuesOf(r, "displayName") == ["m1", "m2", "m3"] :
            "RFC 7644 3.4.2.3: advertised sort support, but resources are not ordered by displayName: ${valuesOf(r, 'displayName')}"
    }

    // RFC 7644 section 3.4.2.3 (Sorting), MAY, lines 1297-1299
    // Clients MAY discover sort capability by looking at the "sort" attribute of the service provider configuration (see Section 4).
    @SpecRef("RFC7644-3.4.2.3-d9cb7c")
    @RequiresCapability("sort")
    def "RFC7644-3.4.2.3-d9cb7c: Clients MAY discover sort capability by looking at the 'sort' attribute of the service provider..."() {
        expect:
        Response spc = scimRequest().get("/ServiceProviderConfig")
        assert spc.statusCode() == 200 : "RFC 7644 4: GET /ServiceProviderConfig must return 200 but was ${spc.statusCode()}"
        def sort = spc.jsonPath().get("sort")
        assert sort instanceof Map : "RFC 7644 3.4.2.3 / 4: ServiceProviderConfig must contain a 'sort' object but had ${sort}"
        assert sort.supported instanceof Boolean : "RFC 7643 5: sort.supported must be a Boolean but was ${sort.supported}"
    }

    // RFC 7644 section 3.4.2.3 (Sorting), MUST, lines 1303-1304
    // The "sortBy" parameter specifies the attribute whose value SHALL be used to order the returned responses.
    @SpecRef("RFC7644-3.4.2.3-f1660b")
    @RequiresCapability("sort")
    def "RFC7644-3.4.2.3-f1660b: sortBy: The 'sortBy' parameter specifies the attribute whose value SHALL be used to order the returned..."() {
        given: "users whose userName order differs from their displayName order"
        String prefix = "srt${tag()}_"
        mkUser("${prefix}b", [displayName: "m1"])
        mkUser("${prefix}c", [displayName: "m2"])
        mkUser("${prefix}a", [displayName: "m3"])

        when:
        Response byUserName = listUsers(prefix, [sortBy: "userName"])
        Response byDisplayName = listUsers(prefix, [sortBy: "displayName"])
        Response byDisplayNameDesc = listUsers(prefix, [sortBy: "displayName", sortOrder: "descending"])

        then:
        assert byUserName.statusCode() == 200
        assert valuesOf(byUserName, "userName") == ["${prefix}a", "${prefix}b", "${prefix}c"] :
            "RFC 7644 3.4.2.3: sortBy=userName must order by userName, got ${valuesOf(byUserName, 'userName')}"
        assert byDisplayName.statusCode() == 200
        assert valuesOf(byDisplayName, "displayName") == ["m1", "m2", "m3"] :
            "RFC 7644 3.4.2.3: sortBy=displayName must order by displayName, got ${valuesOf(byDisplayName, 'displayName')}"
        assert byDisplayNameDesc.statusCode() == 200
        assert valuesOf(byDisplayNameDesc, "displayName") == ["m3", "m2", "m1"] :
            "RFC 7644 3.4.2.3: sortBy=displayName&sortOrder=descending, got ${valuesOf(byDisplayNameDesc, 'displayName')}"
    }

    // RFC 7644 section 3.4.2.3 (Sorting), MUST, lines 1317-1319
    // If a value for "sortBy" is provided and no "sortOrder" is specified, "sortOrder" SHALL default to ascending.
    @SpecRef("RFC7644-3.4.2.3-73f3ba")
    @RequiresCapability("sort")
    def "RFC7644-3.4.2.3-73f3ba: sortOrder: If a value for 'sortBy' is provided and no 'sortOrder' is specified, 'sortOrder' SHALL default..."() {
        given:
        String prefix = "srt${tag()}_"
        mkUser("${prefix}x", [displayName: "m2"])
        mkUser("${prefix}y", [displayName: "m3"])
        mkUser("${prefix}z", [displayName: "m1"])

        when: "sortBy is given without sortOrder"
        Response noOrder = listUsers(prefix, [sortBy: "displayName"])
        Response ascending = listUsers(prefix, [sortBy: "displayName", sortOrder: "ascending"])

        then:
        assert noOrder.statusCode() == 200
        assert valuesOf(noOrder, "displayName") == ["m1", "m2", "m3"] :
            "RFC 7644 3.4.2.3: sortOrder must default to ascending, got ${valuesOf(noOrder, 'displayName')}"
        assert ascending.statusCode() == 200
        assert valuesOf(noOrder, "id") == valuesOf(ascending, "id") :
            "RFC 7644 3.4.2.3: omitted sortOrder must give the same order as sortOrder=ascending"
    }

    // RFC 7644 section 3.4.2.3 (Sorting), MUST, lines 1321-1326
    // "sortOrder" MUST sort according to the attribute type; i.e., for case-insensitive attributes, sort the result using case-insensitive Unicode alphabetic sort order with no specific locale implied, and for case-exact attribute types, sort the result using case-sensitive Unicode alphabetic sort order.
    @SpecRef("RFC7644-3.4.2.3-64672f")
    @RequiresCapability("sort")
    def "RFC7644-3.4.2.3-64672f: sortOrder: 'sortOrder' MUST sort according to the attribute type; i.e., for case-insensitive attributes,..."() {
        given: "values whose case-sensitive order differs from their case-insensitive order"
        String prefix = "srt${tag()}_"
        String t = tag()
        mkUser("${prefix}Zulu", [displayName: "Zulu ${t}", externalId: "B-${t}"])
        mkUser("${prefix}alpha", [displayName: "alpha ${t}", externalId: "a-${t}"])
        mkUser("${prefix}Mike", [displayName: "Mike ${t}", externalId: "C-${t}"])
        Response schemaResp = scimRequest().get("/Schemas/${USER_SCHEMA}")
        assert schemaResp.statusCode() == 200
        List<Map> attrs = schemaResp.jsonPath().getList("attributes")

        expect: "sorting follows the caseExact characteristic of each attribute"
        List<String> failures = []
        ["displayName", "externalId"].each { String attr ->
            boolean caseExact = ((attrs.find { it.name == attr }).caseExact ?: false) as boolean
            Response asc = listUsers(prefix, [sortBy: attr, sortOrder: "ascending"])
            Response desc = listUsers(prefix, [sortBy: attr, sortOrder: "descending"])
            if (asc.statusCode() != 200 || desc.statusCode() != 200) {
                failures << "${attr}: HTTP ${asc.statusCode()}/${desc.statusCode()}"
                return
            }
            List<String> ascValues = valuesOf(asc, attr)
            List<String> descValues = valuesOf(desc, attr)
            List<String> expectedAsc = caseExact ? csSorted(ascValues) : ciSorted(ascValues)
            List<String> expectedDesc = expectedAsc.reverse(false)
            if (ascValues != expectedAsc) {
                failures << "${attr} (caseExact=${caseExact}) ascending: got ${ascValues}, expected ${expectedAsc}"
            }
            if (descValues != expectedDesc) {
                failures << "${attr} (caseExact=${caseExact}) descending: got ${descValues}, expected ${expectedDesc}"
            }
        }
        Response asc = listUsers(prefix, [sortBy: "userName", sortOrder: "ascending"])
        assert asc.statusCode() == 200
        List<String> unAsc = valuesOf(asc, "userName")
        if (unAsc != ciSorted(unAsc)) {
            failures << "userName (caseExact=false) ascending: got ${unAsc}, expected ${ciSorted(unAsc)}"
        }
        assert failures.isEmpty() : "RFC 7644 3.4.2.3: sortOrder must sort according to the attribute type (case-insensitive vs case-exact): ${failures}"
    }

    // RFC 7644 section 3.4.2.4 (Pagination), MUST, lines 1356-1359
    // A value less than 1 SHALL be interpreted as 1.
    @SpecRef("RFC7644-3.4.2.4-fecb18")
    def "RFC7644-3.4.2.4-fecb18: startIndex: A value less than 1 SHALL be interpreted as 1."() {
        given:
        String prefix = prefixWithPage()

        when:
        Response baseline = listUsers(prefix, [startIndex: 1, count: 2])
        List<Response> lowered = [0, -1, -100].collect { Integer si -> listUsers(prefix, [startIndex: si, count: 2]) }

        then:
        assert baseline.statusCode() == 200
        List<String> baseIds = valuesOf(baseline, "id")
        assert baseIds.size() == 2
        lowered.eachWithIndex { Response r, int i ->
            int si = [0, -1, -100][i]
            assert r.statusCode() == 200 : "RFC 7644 3.4.2.4: startIndex=${si} must be accepted but returned ${r.statusCode()}"
            assert valuesOf(r, "id") == baseIds :
                "RFC 7644 3.4.2.4: startIndex=${si} must be interpreted as 1; got ${valuesOf(r, 'id')} instead of ${baseIds}"
            Integer reported = r.jsonPath().get("startIndex")
            assert reported == null || reported == 1 :
                "RFC 7644 3.4.2.4: startIndex=${si} is interpreted as 1, response reports startIndex=${reported}"
        }
    }

    // RFC 7644 section 3.4.2.4 (Pagination), MUST, lines 1361-1370
    // A negative value SHALL be interpreted as "0".
    @SpecRef("RFC7644-3.4.2.4-006405")
    def "RFC7644-3.4.2.4-006405: count: A negative value SHALL be interpreted as '0'."() {
        given:
        String prefix = prefixWithPage()

        expect: "a negative count is interpreted as 0: no resources, but totalResults"
        [-1, -100].each { Integer c ->
            Response r = listUsers(prefix, [count: c])
            assert r.statusCode() == 200 : "RFC 7644 3.4.2.4: count=${c} must be accepted but returned ${r.statusCode()}"
            assert valuesOf(r, "id").isEmpty() :
                "RFC 7644 3.4.2.4: count=${c} must be interpreted as 0 and return no resources, got ${valuesOf(r, 'userName')}"
            assert r.jsonPath().getInt("totalResults") == 3 :
                "RFC 7644 3.4.2.4: count=${c} (as 0) must still report totalResults=3"
            Integer perPage = r.jsonPath().get("itemsPerPage")
            assert perPage == null || perPage == 0 : "RFC 7644 3.4.2.4: itemsPerPage must be 0 for count=${c}, was ${perPage}"
        }
    }

    // RFC 7644 section 3.4.2.4 (Pagination), MUST NOT, lines 1361-1370
    // When specified, the service provider MUST NOT return more results than specified
    @SpecRef("RFC7644-3.4.2.4-41250b")
    def "RFC7644-3.4.2.4-41250b: count: When specified, the service provider MUST NOT return more results than specified"() {
        given:
        String prefix = prefixWithPage()

        expect: "never more resources than count"
        [0, 1, 2, 3].each { Integer c ->
            Response r = listUsers(prefix, [count: c])
            assert r.statusCode() == 200 : "RFC 7644 3.4.2.4: count=${c} returned ${r.statusCode()}"
            int returned = valuesOf(r, "id").size()
            assert returned <= c :
                "RFC 7644 3.4.2.4: count=${c} but ${returned} resources were returned"
            Integer perPage = r.jsonPath().get("itemsPerPage")
            assert perPage == null || perPage <= c : "RFC 7644 3.4.2.4: count=${c} but itemsPerPage=${perPage}"
            assert r.jsonPath().getInt("totalResults") == 3
        }
    }

    // RFC 7644 section 3.4.2.4 (Pagination), MAY, lines 1361-1370
    // although it MAY return fewer results.
    @SpecRef("RFC7644-3.4.2.4-25a5b1")
    def "RFC7644-3.4.2.4-25a5b1: count: although it MAY return fewer results."() {
        given:
        String prefix = prefixWithPage()
        Set<String> expected = valuesOf(listUsers(prefix), "id") as Set
        assert expected.size() == 3

        when: "pages are requested with count=2, the server may return fewer resources per page"
        List<String> collected = []
        int startIndex = 1
        for (int guard = 0; guard < 10 && collected.size() < 3; guard++) {
            Response page = listUsers(prefix, [startIndex: startIndex, count: 2])
            assert page.statusCode() == 200
            List<String> ids = valuesOf(page, "id")
            assert ids.size() <= 2
            if (ids.isEmpty()) {
                break
            }
            Integer perPage = page.jsonPath().get("itemsPerPage")
            assert perPage == null || perPage == ids.size() :
                "RFC 7644 3.4.2.4: itemsPerPage=${perPage} must equal the number of resources returned (${ids.size()})"
            collected.addAll(ids)
            startIndex += ids.size()
        }

        then: "paging by what was returned reaches every resource exactly once"
        assert collected.sort(false) == expected.toList().sort(false) :
            "RFC 7644 3.4.2.4: a page with fewer results than count must still allow reaching all resources: collected ${collected}, expected ${expected}"
    }
}
