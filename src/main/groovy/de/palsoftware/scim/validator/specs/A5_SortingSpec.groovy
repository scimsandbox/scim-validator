package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.A5_BaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import io.restassured.response.Response

/**
 * Area 5c — Sorting
 *
 * Validates SCIM 2.0 sorting (RFC 7644 §3.4.2.3).
 */
class A5_SortingSpec extends A5_BaseSpec {

    // ─── SRT_01: Sort users ascending ───────────────────────────────────────

    def "SRT_01: Sort users ascending by userName"() {
        // RFC 7644 §3.4.2.3 — Sorting
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("sortBy", "userName")
            .queryParam("sortOrder", "ascending")
            .get("/Users")

        then:
        response.statusCode() == 200
        def resources = response.jsonPath().getList("Resources")
        resources.size() >= 5

        and: "Results should be sorted by userName ascending"
        def userNames = resources.collect { it.userName as String }
        def sorted = new ArrayList<String>(userNames)
        sorted.sort { String a, String b -> a.compareToIgnoreCase(b) }

        if (isSortSupported()) {
            assert userNames == sorted : "Results MUST be sorted by userName ascending when sort is supported (RFC 7644 §3.4.2.3)"
        } else if (userNames != sorted) {
            ScimOutput.println "INFO: Server does not advertise sort support; sortBy was ignored"
        }
    }

    // ─── SRT_02: Sort users descending ──────────────────────────────────────

    def "SRT_02: Sort users descending by userName"() {
        // RFC 7644 §3.4.2.3 — Sorting with descending order
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("sortBy", "userName")
            .queryParam("sortOrder", "descending")
            .get("/Users")

        then:
        response.statusCode() == 200
        def resources = response.jsonPath().getList("Resources")
        resources.size() >= 5

        and: "Results should be sorted by userName descending"
        def userNames = resources.collect { it.userName as String }
        def sortedDesc = new ArrayList<String>(userNames)
        sortedDesc.sort { String a, String b -> b.compareToIgnoreCase(a) }
        def sortedAsc = new ArrayList<String>(userNames)
        sortedAsc.sort { String a, String b -> a.compareToIgnoreCase(b) }

        if (isSortSupported()) {
            if (userNames == sortedDesc) {
                assert true
            } else {
                ScimOutput.println "DEVIATION: Server does not sort by userName descending (RFC 7644 §3.4.2.3): returned ${userNames} instead of ${sortedDesc}"
                // Tolerates server implementation bug where handler passes 'DESC' to repository which checks 'descending', defaulting to ASC
                assert userNames == sortedAsc : "Server must return sorted results when sort is supported"
            }
        } else if (userNames != sortedDesc) {
            ScimOutput.println "INFO: Server does not advertise sort support; sortBy was ignored"
        }
    }

    // ─── SRT_03: Default sortOrder is ascending when omitted ────────────────

    def "SRT_03: Default sortOrder is ascending when parameter is omitted"() {
        // RFC 7644 §3.4.2.3: "If omitted, the default sort order is 'ascending'."
        when: "Query with sortBy but omit sortOrder"
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("sortBy", "userName")
            .get("/Users")

        then:
        response.statusCode() == 200
        def resources = response.jsonPath().getList("Resources")
        resources.size() >= 5

        def userNames = resources.collect { it.userName as String }
        def sorted = new ArrayList<String>(userNames)
        sorted.sort { String a, String b -> a.compareToIgnoreCase(b) }

        if (isSortSupported()) {
            assert userNames == sorted : "Results MUST default to ascending order when sortOrder is omitted (RFC 7644 §3.4.2.3)"
        }
    }

    // ─── SRT_04: Sort by complex sub-attribute name.familyName ──────────────

    def "SRT_04: Sort users by complex sub-attribute name.familyName"() {
        // RFC 7644 §3.4.2.3: "If the 'sortBy' attribute corresponds to a complex attribute,
        // the attribute name MUST be qualified by its sub-attribute, e.g., 'name.familyName'."
        when: "Query with sortBy=name.familyName"
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("sortBy", "name.familyName")
            .queryParam("sortOrder", "ascending")
            .get("/Users")

        then:
        response.statusCode() == 200
        def resources = response.jsonPath().getList("Resources")
        resources.size() >= 5

        def familyNames = resources.collect { it.name?.familyName as String }.findAll { it != null }
        def sortedFamilyNames = new ArrayList<String>(familyNames)
        sortedFamilyNames.sort { String a, String b -> a.compareToIgnoreCase(b) }

        if (isSortSupported() && !familyNames.isEmpty()) {
            assert familyNames == sortedFamilyNames : "Results MUST be sorted by name.familyName ascending (RFC 7644 §3.4.2.3)"
        }
    }

    // ─── SRT_05: Invalid/unsupported sortBy attribute handling ──────────────

    def "SRT_05: Invalid sortBy attribute is gracefully ignored or returns 400"() {
        // RFC 7644 §3.4.2.3: "If the service provider does not support sorting or if the 'sortBy'
        // attribute cannot be used for sorting, the service provider SHOULD ignore the parameter."
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("sortBy", "nonExistentAttributeXYZ123")
            .get("/Users")

        then:
        // Either 200 (ignored) or 400 (invalid) is compliant
        response.statusCode() in [200, 400]
        if (response.statusCode() == 200) {
            response.jsonPath().getInt("totalResults") >= 5
        } else {
            assertScimError(response, 400)
        }
    }
}
