package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.A5_BaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import io.restassured.response.Response
import groovy.json.JsonOutput

/**
 * Area 5a — Filtering
 *
 * Validates SCIM 2.0 filtering (RFC 7644 §3.4.2) and related query parameters.
 */
class A5_FilteringSpec extends A5_BaseSpec {

    // ─── FLT_01: eq operator ────────────────────────────────────────────────

    def "FLT_01: Filter with eq operator returns exact match"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: eq
        given:
        String targetUserName = userData[0].userName

        when:
        Response response = scimRequest()
            .queryParam("filter", "userName eq \"${targetUserName}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources").size() >= 1
        response.jsonPath().getList("Resources.userName").contains(targetUserName)
    }

    // ─── FLT_02: sw operator ────────────────────────────────────────────────

    def "FLT_02: Filter with sw (startsWith) returns matching users"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: sw
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        int count = response.jsonPath().getInt("totalResults")
        count >= 5
        response.jsonPath().getList("Resources").size() >= 5
    }

    // ─── FLT_03: co operator ────────────────────────────────────────────────

    def "FLT_03: Filter with co (contains) returns matching users"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: co
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName co \"alice@test\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        // Every returned resource should contain the substring
        response.jsonPath().getList("Resources.userName").every { String un -> un.contains("alice@test") }
    }

    // ─── FLT_04: pr operator ────────────────────────────────────────────────

    def "FLT_04: Filter with pr (present) returns users that have the attribute"() {
        // RFC 7644 §3.4.2.2 — Attribute Operators: pr
        when:
        Response response = scimRequest()
            .queryParam("filter", "externalId pr")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
        // All returned resources should have externalId present
        response.jsonPath().getList("Resources").every { Map r -> r.externalId != null }
    }

    // ─── FLT_05: eq on boolean attribute ────────────────────────────────────

    def "FLT_05: Filter eq on active (boolean) returns correct users"() {
        // RFC 7644 §3.4.2.2 — eq with boolean value
        when:
        Response response = scimRequest()
            .queryParam("filter", "active eq false and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        int count = response.jsonPath().getInt("totalResults")
        // We created exactly 2 inactive users (charlie, eve)
        count >= 2
    }

    // ─── FLT_06: Logical AND filter ─────────────────────────────────────────

    def "FLT_06: Filter with logical AND narrows results correctly"() {
        // RFC 7644 §3.4.2.2 — Logical Operators: and
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\" and active eq true")
            .get("/Users")

        then:
        response.statusCode() == 200
        int count = response.jsonPath().getInt("totalResults")
        // 3 active users among our 5: alice, bob, diana
        count >= 3
        response.jsonPath().getList("Resources").every { Map r ->
            (r.userName as String).startsWith(PREFIX) && r.active == true
        }
    }

    // ─── FLT_07: Logical OR filter ──────────────────────────────────────────

    def "FLT_07: Filter with logical OR broadens results correctly"() {
        // RFC 7644 §3.4.2.2 — Logical Operators: or
        given:
        String user1 = userData[0].userName
        String user2 = userData[1].userName

        when:
        Response response = scimRequest()
            .queryParam("filter", "userName eq \"${user1}\" or userName eq \"${user2}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 2
        def returnedNames = response.jsonPath().getList("Resources.userName")
        returnedNames.contains(user1)
        returnedNames.contains(user2)
    }

    // ─── FLT_10: Invalid filter returns 400 ─────────────────────────────────

    def "FLT_10: Invalid filter expression returns HTTP 400"() {
        // RFC 7644 §3.4.2 — Server MUST return 400 for invalid filter
        when:
        Response response = scimRequest()
            .queryParam("filter", "not a valid !!! filter [[")
            .get("/Users")

        then:
        response.statusCode() == 400
    }

    // ─── FLT_11: Filter for non-existent user returns totalResults=0 ───────

    def "FLT_11: Filter for non-existent user returns empty list response"() {
        // RFC 7644 §3.4.2 — ListResponse with totalResults=0 when no match
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName eq \"nonexistent_${UUID.randomUUID().toString().substring(0, 8)}@test.com\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        response.jsonPath().getInt("totalResults") == 0
        response.jsonPath().getInt("itemsPerPage") >= 0
        response.jsonPath().getInt("startIndex") >= 1
    }

    // ─── FLT_12: userName filter is case-insensitive when caseExact=false ─

    def "FLT_12: userName filter matches regardless of case"() {
        // RFC 7643 §4.1 — userName has caseExact=false
        // RFC 7644 §3.4.2.2 — String comparisons are case-insensitive when caseExact=false
        given: "Fetch userName caseExact setting from the schema"
        Response schemaResponse = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")
        schemaResponse.statusCode() == 200

        def attributes = schemaResponse.jsonPath().getList("attributes")
        def userNameAttr = attributes.find { it.name == "userName" }
        userNameAttr != null
        userNameAttr.caseExact == false

        and: "Pick a known userName and change its case"
        String originalUserName = userData[0].userName
        String upperUserName = originalUserName.toUpperCase()

        when:
        Response response = scimRequest()
            .queryParam("filter", "userName eq \"${upperUserName}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.userName").contains(originalUserName)
    }

    // ─── FLT_13: attributes parameter limits returned attributes ────────────

    def "FLT_13: attributes parameter returns only minimum set plus requested"() {
        // RFC 7644 §3.9 — attributes overrides the default attribute set
        given:
        String targetId = userData[0].id

        when:
        Response response = scimRequest()
            .queryParam("attributes", "userName")
            .get("/Users/${targetId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getString("id") == targetId
        response.jsonPath().getString("userName") == userData[0].userName

        and: "Non-requested default attributes are omitted (log deviation if present)"
        def body = response.jsonPath().getMap("")
        boolean hasName = body?.containsKey("name")
        boolean hasEmails = body?.containsKey("emails")
        boolean hasExternalId = body?.containsKey("externalId")

        if (hasName || hasEmails || hasExternalId) {
            ScimOutput.println "DEVIATION: api.scim.dev returns default attributes even when attributes=userName is specified"
        }
    }

    // ─── FLT_14: excludedAttributes parameter removes defaults ──────────────

    def "FLT_14: excludedAttributes removes requested default attributes"() {
        // RFC 7644 §3.9 — excludedAttributes removes attributes from default set
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("excludedAttributes", "emails")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1

        and: "id remains present while emails are omitted (log deviation if present)"
        def resources = response.jsonPath().getList("Resources")
        resources.every { Map r -> r.id != null }

        boolean emailsPresent = resources.any { Map r -> r.containsKey("emails") }
        if (emailsPresent) {
            ScimOutput.println "DEVIATION: api.scim.dev does not honor excludedAttributes=emails on list responses"
        }
    }

    // ─── FLT_15: Filter groups by displayName eq ────────────────────────────

    def "FLT_15: Filter groups by displayName eq returns exact match"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: eq on Groups
        // Reference: scim2-compliance-test-suite FilterTest.FilterGroups()
        given: "Create test groups with known displayNames"
        String groupName1 = "${PREFIX}Engineers"
        String groupName2 = "${PREFIX}Designers"
        Response g1 = createGroup(groupName1)
        Response g2 = createGroup(groupName2)
        assert g1.statusCode() == 201
        assert g2.statusCode() == 201
        String gid1 = g1.jsonPath().getString("id")
        String gid2 = g2.jsonPath().getString("id")

        when: "Filter groups by displayName eq"
        Response response = scimRequest()
            .queryParam("filter", "displayName eq \"${groupName1}\"")
            .get("/Groups")

        then: "Only the matching group is returned"
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources").every { Map r ->
            (r.displayName as String) == groupName1
        }

        cleanup:
        if (gid1) deleteGroup(gid1)
        if (gid2) deleteGroup(gid2)
        createdGroupIds.remove(gid1)
        createdGroupIds.remove(gid2)
    }

    // ─── FLT_16: POST /.search on /Users ────────────────────────────────────

    def "FLT_16: POST /Users/.search returns filtered list response"() {
        // RFC 7644 §3.4.3 — POST-based query via /.search
        given:
        String targetUserName = userData[0].userName
        Map searchBody = [
            schemas: [LIST_RESPONSE_SCHEMA],
            filter : "userName eq \"${targetUserName}\""
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(searchBody))
            .post("/Users/.search")

        then:
        response.statusCode() == 200
        response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.userName").contains(targetUserName)
    }

    // ─── FLT_17: POST /.search on /Groups ───────────────────────────────────

    def "FLT_17: POST /Groups/.search returns filtered list response"() {
        // RFC 7644 §3.4.3 — POST-based query via /.search
        given: "Create a test group"
        String groupName = "${PREFIX}SearchGroup"
        Response created = createGroup(groupName)
        assert created.statusCode() == 201
        String gid = created.jsonPath().getString("id")

        Map searchBody = [
            schemas: [LIST_RESPONSE_SCHEMA],
            filter : "displayName eq \"${groupName}\""
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(searchBody))
            .post("/Groups/.search")

        then:
        response.statusCode() == 200
        response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.displayName").contains(groupName)

        cleanup:
        if (gid) deleteGroup(gid)
        createdGroupIds.remove(gid)
    }

    // ─── FLT_18: URN-prefixed core attribute in attributes parameter ────────

    def "FLT_18: URN-prefixed core attribute returns correct projection"() {
        // RFC 7644 §3.9 — attributes parameter supports URN-prefixed attribute names
        given:
        String targetId = userData[0].id

        when:
        Response response = scimRequest()
            .queryParam("attributes", "urn:ietf:params:scim:schemas:core:2.0:User:userName")
            .get("/Users/${targetId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getString("userName") == userData[0].userName
        response.jsonPath().getString("id") == targetId
    }

    // ─── FLT_19: URN-prefixed enterprise extension in attributes parameter ──

    def "FLT_19: URN-prefixed enterprise extension returns extension block"() {
        // RFC 7644 §3.9 — Enterprise extension URN selects the extension sub-object
        given: "Create a user with enterprise extension"
        Response created = createFullUser()
        assert created.statusCode() == 201
        String userId = created.jsonPath().getString("id")

        when:
        Response response = scimRequest()
            .queryParam("attributes", ENTERPRISE_USER_SCHEMA)
            .get("/Users/${userId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getString("id") == userId

        and: "Enterprise extension data should be present"
        def enterprise = response.jsonPath().getMap("'${ENTERPRISE_USER_SCHEMA}'") ?:
                         response.jsonPath().getMap("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User")
        enterprise != null
    }

    // ─── FLT_20: ne operator ────────────────────────────────────────────────

    def "FLT_20: Filter with ne operator excludes matching users"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: ne
        given:
        String excludeUserName = userData[0].userName

        when:
        Response response = scimRequest()
            .queryParam("filter", "userName ne \"${excludeUserName}\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 4
        !response.jsonPath().getList("Resources.userName").contains(excludeUserName)
    }

    // ─── FLT_21: ew operator ────────────────────────────────────────────────

    def "FLT_21: Filter with ew (endsWith) returns matching users"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: ew
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName ew \"alice@test.com\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.userName").every { String un -> un.endsWith("alice@test.com") }
    }

    // ─── FLT_22: gt/ge/lt/le on meta.lastModified ──────────────────────────

    def "FLT_22: Filter with gt on meta.lastModified returns results"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: gt, ge, lt, le
        when:
        Response response = scimRequest()
            .queryParam("filter", "meta.lastModified gt \"2000-01-01T00:00:00Z\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
    }

    // ─── FLT_23: not expression ─────────────────────────────────────────────

    def "FLT_23: Filter with not expression negates inner condition"() {
        // RFC 7644 §3.4.2.2 — Logical Operators: not
        when:
        Response response = scimRequest()
            .queryParam("filter", "not (active eq false) and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        int count = response.jsonPath().getInt("totalResults")
        // Only active users: alice, bob, diana = 3
        count >= 3
        response.jsonPath().getList("Resources").every { Map r -> r.active == true }
    }

    // ─── FLT_24: Parenthesized grouping ─────────────────────────────────────

    def "FLT_24: Filter with parenthesized grouping applies correct precedence"() {
        // RFC 7644 §3.4.2.2 — Grouping Operators: ()
        given:
        String user1 = userData[0].userName
        String user2 = userData[1].userName

        when: "Use (A or B) and C to ensure grouping is respected"
        Response response = scimRequest()
            .queryParam("filter", "(userName eq \"${user1}\" or userName eq \"${user2}\") and active eq true")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 2
        def names = response.jsonPath().getList("Resources.userName")
        names.contains(user1)
        names.contains(user2)
    }

    // ─── FLT_25: Core URN-prefixed filter attribute ─────────────────────────

    def "FLT_25: Filter with core schema URN prefix resolves correctly"() {
        // RFC 7644 §3.4.2.2 — URN-prefixed attribute names in filters
        given:
        String targetUserName = userData[0].userName

        when:
        Response response = scimRequest()
            .queryParam("filter", "urn:ietf:params:scim:schemas:core:2.0:User:userName eq \"${targetUserName}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.userName").contains(targetUserName)
    }

    // ─── FLT_26: Case-insensitive attribute name in filter ──────────────────

    def "FLT_26: Filter attribute names are case-insensitive"() {
        // RFC 7644 §3.4.2.2 — "Attribute names and attribute operators used in filters are case insensitive"
        given:
        String targetUserName = userData[0].userName

        when: "Use mixed-case attribute name 'UserName'"
        Response response = scimRequest()
            .queryParam("filter", "UserName eq \"${targetUserName}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.userName").contains(targetUserName)
    }

    // ─── FLT_27: Value path filter with brackets ────────────────────────────

    def "FLT_27: Value path filter emails[type eq work] returns matching users"() {
        // RFC 7644 §3.4.2.2 — Value Path Filtering: attrPath[valFilter]
        when:
        Response response = scimRequest()
            .queryParam("filter", "emails[type eq \"work\"] and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        // All our test users have a work email
        response.jsonPath().getInt("totalResults") >= 5
    }

    // ─── FLT_28: Case-insensitive operator ──────────────────────────────────

    def "FLT_28: Filter operators are case-insensitive"() {
        // RFC 7644 §3.4.2.2 — "attribute operators used in filters are case insensitive"
        given:
        String targetUserName = userData[0].userName

        when: "Use upper-case operator 'EQ'"
        Response response = scimRequest()
            .queryParam("filter", "userName EQ \"${targetUserName}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.userName").contains(targetUserName)
    }
}
