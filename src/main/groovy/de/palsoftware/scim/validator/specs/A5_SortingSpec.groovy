package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.A5_BaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response

/**
 * Area 5c — Sorting
 *
 * Validates SCIM 2.0 sorting (RFC 7644 §3.4.2.3).
 */
@RequiresCapability("sort")
class A5_SortingSpec extends A5_BaseSpec {

    // ─── SRT_01: Sort users ascending ───────────────────────────────────────

    @SpecRef("RFC7644-3.4.2.3-R1")
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

        assert userNames == sorted : "Results MUST be sorted by userName ascending when sort is supported (RFC 7644 §3.4.2.3)"
    }

    // ─── SRT_02: Sort users descending ──────────────────────────────────────

    @SpecRef("RFC7644-3.4.2.3-R2")
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

        and: "Results MUST be sorted by userName descending"
        def userNames = resources.collect { it.userName as String }
        def sortedDesc = new ArrayList<String>(userNames)
        sortedDesc.sort { String a, String b -> b.compareToIgnoreCase(a) }

        assert userNames == sortedDesc : "Results MUST be sorted by userName descending when sort is supported (RFC 7644 §3.4.2.3): returned ${userNames} instead of ${sortedDesc}"
    }

    // ─── SRT_03: Default sortOrder is ascending when omitted ────────────────

    @SpecRef("RFC7644-3.4.2.3-R3")
    def "SRT_03: Default sortOrder is ascending when parameter is omitted"() {
        // RFC 7644 §3.4.2.3: If omitted, the default sort order is ascending
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

        assert userNames == sorted : "Results MUST default to ascending order when sortOrder is omitted (RFC 7644 §3.4.2.3)"
    }

    // ─── SRT_04: Sort by complex sub-attribute name.familyName ──────────────

    @SpecRef("RFC7644-3.4.2.3-R1")
    def "SRT_04: Sort users by complex sub-attribute name.familyName"() {
        // RFC 7644 §3.4.2.3: complex attribute sortBy is qualified by sub-attribute
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
        assert familyNames.size() >= 5 : "All 5 setup users have name.familyName populated"
        def sortedFamilyNames = new ArrayList<String>(familyNames)
        sortedFamilyNames.sort { String a, String b -> a.compareToIgnoreCase(b) }

        assert familyNames == sortedFamilyNames : "Results MUST be sorted by name.familyName ascending (RFC 7644 §3.4.2.3)"
    }

    // ─── SRT_05: Invalid/unsupported sortBy attribute handling ──────────────

    @SpecRef("RFC7644-3.4.2.3-R1")
    def "SRT_05: Invalid sortBy attribute is gracefully ignored or returns 400"() {
        // Robustness check for invalid sortBy attribute
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("sortBy", "nonExistentAttributeXYZ123")
            .get("/Users")

        then:
        response.statusCode() in [200, 400]
        if (response.statusCode() == 200) {
            assert response.jsonPath().getInt("totalResults") >= 5
        } else {
            assertScimError(response, 400)
        }
    }

    // ─── SRT_06: Sort order places missing/null data last for ascending and first for descending ───

    @SpecRef("RFC7644-3.4.2.3-R4")
    def "SRT_06: Sort order places missing/null data last for ascending and first for descending"() {
        // RFC 7644 §3.4.2.3: "When the sort attribute is missing or has a null value,
        // the record MUST be ordered last in ascending sort order and first in descending sort order."
        given: "Users with title populated and one user without title"
        String prefix = "srt06_" + UUID.randomUUID().toString().substring(0, 6) + "_"
        String uNull = "${prefix}null@test.com"
        String uAlpha = "${prefix}alpha@test.com"
        String uOmega = "${prefix}omega@test.com"
        List<String> createdIds = []

        Response r1 = createUser([userName: uAlpha, title: "Alpha"])
        assert r1.statusCode() == 201
        createdIds << r1.jsonPath().getString("id")

        Response r2 = createUser([userName: uOmega, title: "Omega"])
        assert r2.statusCode() == 201
        createdIds << r2.jsonPath().getString("id")

        Response r3 = createUser([userName: uNull])
        assert r3.statusCode() == 201
        createdIds << r3.jsonPath().getString("id")

        when: "Query with sortBy=title and sortOrder=ascending"
        Response ascResp = scimRequest()
            .queryParam("filter", "userName sw \"${prefix}\"")
            .queryParam("sortBy", "title")
            .queryParam("sortOrder", "ascending")
            .get("/Users")

        then: "Status is 200"
        ascResp.statusCode() == 200
        List<String> ascNames = ascResp.jsonPath().getList("Resources.userName") ?: []
        ascNames.size() == 3

        and: "Missing/null title is ordered last in ascending sort order"
        assert ascNames.last() == uNull :
            "User with missing/null title MUST be last in ascending order, got: ${ascNames}"
        assert ascNames.indexOf(uAlpha) < ascNames.indexOf(uOmega) :
            "Populated titles MUST be in ascending order"

        when: "Query with sortBy=title and sortOrder=descending"
        Response descResp = scimRequest()
            .queryParam("filter", "userName sw \"${prefix}\"")
            .queryParam("sortBy", "title")
            .queryParam("sortOrder", "descending")
            .get("/Users")

        then: "Status is 200"
        descResp.statusCode() == 200
        List<String> descNames = descResp.jsonPath().getList("Resources.userName") ?: []
        descNames.size() == 3

        and: "Missing/null title is ordered first in descending sort order"
        assert descNames.first() == uNull :
            "User with missing/null title MUST be first in descending order, got: ${descNames}"
        assert descNames.indexOf(uOmega) < descNames.indexOf(uAlpha) :
            "Populated titles MUST be in descending order"

        cleanup:
        createdIds.each { id -> if (id) deleteUser(id) }
    }

    // ─── SRT_07: Sort by multi-valued attribute orders by primary value ──────

    @SpecRef("RFC7644-3.4.2.3-R5")
    def "SRT_07: Sort by multi-valued attribute orders by primary value"() {
        // RFC 7644 §3.4.2.3: "When sorting by a multi-valued attribute,
        // results MUST be ordered according to the attribute value whose
        // 'primary' sub-attribute is set to 'true', if any, or else the first value in the list."
        given: "Users where primary/first email ordering [u2, u3, u1] differs from insertion and userName order [u1, u2, u3]"
        String prefix = "srt07_" + UUID.randomUUID().toString().substring(0, 6) + "_"
        String u1 = "${prefix}user1@test.com"
        String u2 = "${prefix}user2@test.com"
        String u3 = "${prefix}user3@test.com"
        List<String> createdIds = []

        // User 1 (created 1st, userName 1st): first email is 'a', but primary email is 'z' (sorts 3rd)
        Response r1 = createUser([
            userName: u1,
            emails  : [
                [value: "${prefix}a_secondary@test.com", type: "work", primary: false],
                [value: "${prefix}z_primary@test.com", type: "home", primary: true]
            ]
        ])
        assert r1.statusCode() == 201
        createdIds << r1.jsonPath().getString("id")

        // User 2 (created 2nd, userName 2nd): first email is 'z', but primary email is 'a' (sorts 1st)
        Response r2 = createUser([
            userName: u2,
            emails  : [
                [value: "${prefix}z_secondary@test.com", type: "work", primary: false],
                [value: "${prefix}a_primary@test.com", type: "home", primary: true]
            ]
        ])
        assert r2.statusCode() == 201
        createdIds << r2.jsonPath().getString("id")

        // User 3 (created 3rd, userName 3rd): no primary email, first email is 'm' (sorts 2nd via first-element fallback)
        Response r3 = createUser([
            userName: u3,
            emails  : [
                [value: "${prefix}m_first@test.com", type: "work", primary: false],
                [value: "${prefix}y_second@test.com", type: "home", primary: false]
            ]
        ])
        assert r3.statusCode() == 201
        createdIds << r3.jsonPath().getString("id")

        when: "Query with sortBy=emails and sortOrder=ascending"
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${prefix}\"")
            .queryParam("sortBy", "emails")
            .queryParam("sortOrder", "ascending")
            .get("/Users")

        then: "Status is 200"
        response.statusCode() == 200
        List<String> names = response.jsonPath().getList("Resources.userName") ?: []
        names.size() == 3

        and: "Ordering reflects primary email value (or first value when no primary): [u2, u3, u1]"
        assert names == [u2, u3, u1] :
            "Results MUST be ordered by primary email (or first value when no primary): expected [${u2}, ${u3}, ${u1}], got ${names}"

        cleanup:
        createdIds.each { id -> if (id) deleteUser(id) }
    }
}

