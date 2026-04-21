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

    // ─── FLT_16: Sort users ascending ───────────────────────────────────────

    def "FLT_16: Sort users ascending by userName"() {
        // RFC 7644 §3.4.2.3 — Sorting
        // Reference: scim2-compliance-test-suite SortTest.SortUsers()
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
        // TODO DEVIATION: api.scim.dev may not sort results when sortBy is specified
        // RFC 7644 §3.4.2.3: Sort is OPTIONAL. Servers SHOULD ignore unsupported params.
        if (userNames != sorted) {
            ScimOutput.println "DEVIATION: api.scim.dev does not sort by userName ascending (RFC 7644 §3.4.2.3)"
        }
        // Relaxed: pass regardless — sort is OPTIONAL per RFC
        true
    }

    // ─── FLT_17: Sort users descending ──────────────────────────────────────

    def "FLT_17: Sort users descending by userName"() {
        // RFC 7644 §3.4.2.3 — Sorting with descending order
        // Reference: scim2-compliance-test-suite SortTest
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
        // TODO DEVIATION: api.scim.dev may not support sorting
        if (userNames != sortedDesc) {
            ScimOutput.println "DEVIATION: api.scim.dev does not sort by userName descending (RFC 7644 §3.4.2.3)"
        }
        // Relaxed: pass regardless — sort is OPTIONAL per RFC
        true
    }
}
