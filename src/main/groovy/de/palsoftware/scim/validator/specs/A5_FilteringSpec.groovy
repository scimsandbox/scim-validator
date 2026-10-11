package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.A5_BaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import groovy.json.JsonOutput

/**
 * Area 5a — Filtering
 *
 * Validates SCIM 2.0 filtering (RFC 7644 §3.4.2) and related query parameters.
 */
@RequiresCapability("filter")
class A5_FilteringSpec extends A5_BaseSpec {

    // ─── FLT_01: eq operator ────────────────────────────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R1")
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

    @SpecRef("RFC7644-3.4.2.2-R4")
    @SpecRef("RFC7644-3.4.2-R4")
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

    @SpecRef("RFC7644-3.4.2.2-R3")
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

    @SpecRef("RFC7644-3.4.2.2-R6")
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

    @SpecRef("RFC7644-3.4.2.2-R1")
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

    @SpecRef("RFC7644-3.4.2.2-R8")
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
        assert response.jsonPath().getList("Resources").every { Map r ->
            (r.userName as String).startsWith(PREFIX) && r.active == true
        }
    }

    // ─── FLT_07: Logical OR filter ──────────────────────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R9")
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

    @SpecRef("RFC7644-3.4.2.2-R14")
    def "FLT_10: Invalid filter expression returns HTTP 400"() {
        // RFC 7644 §3.4.2.2 — Malformed filter expression returns 400 invalidFilter
        when:
        Response response = scimRequest()
            .queryParam("filter", "not a valid !!! filter [[")
            .get("/Users")

        then:
        response.statusCode() == 400
        if (response.jsonPath().getList("schemas")?.contains(ERROR_SCHEMA)) {
            assertScimType(response, "invalidFilter")
        } else {
            recordDeviation("RFC7644-3.12-R1", "RFC 7644 §3.12", "Error response missing urn:ietf:params:scim:api:messages:2.0:Error schema")
        }
    }

    // ─── FLT_11: Filter for non-existent user returns totalResults=0 ───────

    @SpecRef("RFC7644-3.4.2-R2")
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

    @SpecRef("RFC7644-3.4.2.2-R1")
    @SpecRef("RFC7643-2.2-R6")
    @SpecRef("RFC7644-5-R1")
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

    @SpecRef("RFC7644-3.4.2.5-R1")
    @SpecRef("RFC7644-3.9-R1")
    @SpecRef("RFC7643-2.2-R4")
    def "FLT_13: attributes parameter returns only minimum set plus requested"() {
        // RFC 7644 §3.4.2.5 — attributes overrides the default attribute set
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
            recordDeviation("RFC7644-3.4.2.5-R1", "RFC 7644 §3.4.2.5", "Server returns default attributes even when attributes=userName is specified")
        }
    }

    // ─── FLT_14: excludedAttributes parameter removes defaults ──────────────

    @SpecRef("RFC7644-3.4.2.5-R2")
    def "FLT_14: excludedAttributes removes requested default attributes"() {
        // RFC 7644 §3.4.2.5 — excludedAttributes removes attributes from default set
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
            recordDeviation("RFC7644-3.4.2.5-R2", "RFC 7644 §3.4.2.5", "Server does not honor excludedAttributes=emails on list responses")
        }
    }

    // ─── FLT_15: Filter groups by displayName eq ────────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R1")
    def "FLT_15: Filter groups by displayName eq returns exact match"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: eq on Groups
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
        assert response.jsonPath().getList("Resources").every { Map r ->
            (r.displayName as String) == groupName1
        }

        cleanup:
        if (gid1) deleteGroup(gid1)
        if (gid2) deleteGroup(gid2)
        createdGroupIds.remove(gid1)
        createdGroupIds.remove(gid2)
    }

    // ─── FLT_16: POST /.search on /Users ────────────────────────────────────

    @SpecRef("RFC7644-3.4.3-R1")
    def "FLT_16: POST /Users/.search returns filtered list response"() {
        // RFC 7644 §3.4.3 — POST-based query via /.search
        given:
        String targetUserName = userData[0].userName
        Map searchBody = [
            schemas: [SEARCH_REQUEST_SCHEMA],
            filter : "userName eq \"${targetUserName}\""
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(searchBody))
            .post("/Users/.search")

        then:
        if (response.statusCode() in [404, 501]) {
            throw new org.opentest4j.TestAbortedException("Server does not support POST /Users/.search (HTTP ${response.statusCode()})")
        }
        assert response.statusCode() == 200
        assert response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        assert response.jsonPath().getInt("totalResults") >= 1
        assert response.jsonPath().getList("Resources.userName").contains(targetUserName)
    }

    // ─── FLT_17: POST /.search on /Groups ───────────────────────────────────

    @RequiresCapability("search")
    @SpecRef("RFC7644-3.4.3-R1")
    def "FLT_17: POST /Groups/.search returns filtered list response"() {
        // RFC 7644 §3.4.3 — POST-based query via /.search
        given: "Create a test group"
        String groupName = "${PREFIX}SearchGroup"
        Response created = createGroup(groupName)
        assert created.statusCode() == 201
        String gid = created.jsonPath().getString("id")

        Map searchBody = [
            schemas: [SEARCH_REQUEST_SCHEMA],
            filter : "displayName eq \"${groupName}\""
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(searchBody))
            .post("/Groups/.search")

        then:
        if (response.statusCode() in [404, 501]) {
            throw new org.opentest4j.TestAbortedException("Server does not support POST /Groups/.search (HTTP ${response.statusCode()})")
        }
        assert response.statusCode() == 200
        assert response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        assert response.jsonPath().getInt("totalResults") >= 1
        assert response.jsonPath().getList("Resources.displayName").contains(groupName)

        cleanup:
        if (gid) deleteGroup(gid)
        createdGroupIds.remove(gid)
    }

    // ─── FLT_18: URN-prefixed core attribute in attributes parameter ────────

    @SpecRef("RFC7644-3.4.2.5-R1")
    def "FLT_18: URN-prefixed core attribute returns correct projection"() {
        // RFC 7644 §3.4.2.5 — attributes parameter supports URN-prefixed attribute names
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

    @SpecRef("RFC7644-3.4.2.5-R1")
    def "FLT_19: URN-prefixed enterprise extension returns extension block"() {
        // RFC 7644 §3.4.2.5 — Enterprise extension URN selects the extension sub-object
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

        cleanup:
        if (userId) deleteUser(userId)
        createdUserIds.remove(userId)
    }

    // ─── FLT_20: ne operator ────────────────────────────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R2")
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

    @SpecRef("RFC7644-3.4.2.2-R5")
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

    @SpecRef("RFC7644-3.4.2.2-R7")
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

    @SpecRef("RFC7644-3.4.2.2-R10")
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

    @SpecRef("RFC7644-3.4.2.2-R11")
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

    @SpecRef("RFC7644-3.4.2.2-R1")
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

    @SpecRef("RFC7644-3.4.2.2-R1")
    @SpecRef("RFC7643-2.1-R1")
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

    @SpecRef("RFC7644-3.4.2.2-R12")
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

    @SpecRef("RFC7644-3.4.2.2-R1")
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

    // ─── FLT_29: ge operator on meta.lastModified ───────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R7")
    def "FLT_29: Filter with ge on meta.lastModified returns matching users"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: ge
        when:
        Response response = scimRequest()
            .queryParam("filter", "meta.lastModified ge \"2000-01-01T00:00:00Z\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
    }

    // ─── FLT_30: lt operator on meta.lastModified ───────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R7")
    def "FLT_30: Filter with lt on meta.lastModified returns no matching users for ancient timestamp"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: lt
        when:
        Response response = scimRequest()
            .queryParam("filter", "meta.lastModified lt \"2000-01-01T00:00:00Z\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") == 0
        def resources = response.jsonPath().getList("Resources")
        resources == null || resources.isEmpty()
    }

    // ─── FLT_31: le operator on meta.lastModified ───────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R7")
    def "FLT_31: Filter with le on meta.lastModified returns all matching users for future timestamp"() {
        // RFC 7644 §3.4.2.2 — Comparison Operators: le
        when:
        Response response = scimRequest()
            .queryParam("filter", "meta.lastModified le \"2099-01-01T00:00:00Z\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
    }

    // ─── FLT_32: Compound value path filter ─────────────────────────────────

    @SpecRef("RFC7644-3.4.2.2-R12")
    def "FLT_32: Value path filter with sub-attribute targeting returns matching users"() {
        // RFC 7644 §3.4.2.2 — Value Path Filtering with sub-attribute targeting
        when: "Query with direct complex sub-attribute path (emails.value)"
        Response directSubAttr = scimRequest()
            .queryParam("filter", "emails.value co \"@test.com\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then: "Direct sub-attribute query returns all matching users"
        directSubAttr.statusCode() == 200
        directSubAttr.jsonPath().getInt("totalResults") >= 5
        assert directSubAttr.jsonPath().getList("Resources").every { Map r ->
            def emails = r.emails as List<Map>
            emails?.any { (it.value as String)?.contains("@test.com") }
        }

        when: "Query with bracketed value path targeting sub-attribute (emails[type eq \"work\"].value)"
        Response bracketSubAttr = scimRequest()
            .queryParam("filter", "emails[type eq \"work\"].value co \"@test.com\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then: "Server returns matching users or rejects with 400 invalidFilter"
        bracketSubAttr.statusCode() in [200, 400]
        if (bracketSubAttr.statusCode() == 200) {
            assert bracketSubAttr.jsonPath().getInt("totalResults") >= 5
        } else {
            recordDeviation("RFC7644-3.4.2.2-R12", "RFC 7644 §3.4.2.2", "Server does not support bracketed value path with dotted sub-attribute targeting 'emails[...].value'")
            assertScimError(bracketSubAttr, 400)
        }
    }

    // ─── FLT_33: Root global search POST /.search ───────────────────────────

    @SpecRef("RFC7644-3.4.3-R1")
    def "FLT_33: POST /.search root global search returns 200 or 501 Not Implemented"() {
        // RFC 7644 §3.4.3: "If a SCIM service provider does not support searching at the root,
        // the response status MUST be 501 (Not Implemented)."
        Map searchBody = [
            schemas: [SEARCH_REQUEST_SCHEMA],
            filter : "userName sw \"${PREFIX}\""
        ]

        when:
        Response response = scimRequest()
            .body(JsonOutput.toJson(searchBody))
            .post("/.search")

        then:
        response.statusCode() in [200, 501]
        if (response.statusCode() == 501) {
            assertScimError(response, 501)
        } else {
            assert response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        }
    }

    // ─── FLT_34: Filter by enterprise extension attribute ───────────────────

    @SpecRef("RFC7644-3.4.2.2-R1")
    def "FLT_34: Filter by enterprise extension attribute returns matching user"() {
        // RFC 7643 §4.3, RFC 7644 §3.4.2.2 — Extension attribute filtering
        given: "Create a user with distinct enterprise department"
        String dept = "Quality Assurance ${UUID.randomUUID().toString().substring(0, 6)}"
        Map userPayload = [
            schemas   : [USER_SCHEMA, ENTERPRISE_USER_SCHEMA],
            userName  : "${PREFIX}qa_user_${UUID.randomUUID().toString().substring(0, 6)}@test.com",
            (ENTERPRISE_USER_SCHEMA): [
                department    : dept,
                employeeNumber: "QA-${System.currentTimeMillis()}"
            ]
        ]
        Response created = createUser(userPayload)
        assert created.statusCode() == 201
        String uid = created.jsonPath().getString("id")

        when: "Filter by fully-qualified enterprise extension attribute"
        Response response = scimRequest()
            .queryParam("filter", "${ENTERPRISE_USER_SCHEMA}:department eq \"${dept}\"")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 1
        response.jsonPath().getList("Resources.id").contains(uid)

        cleanup:
        if (uid) deleteUser(uid)
        createdUserIds.remove(uid)
    }

    // ─── FLT_35: Mandatory attributes cannot be excluded ────────────────────

    @SpecRef("RFC7644-3.4.2.5-R2")
    @SpecRef("RFC7644-3.9-R1")
    def "FLT_35: Mandatory attributes id and schemas cannot be excluded via excludedAttributes"() {
        // RFC 7644 §3.4.2.5 — Characteristics: 'always'; id and schemas cannot be excluded
        when: "Query with excludedAttributes=id,schemas"
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("excludedAttributes", "id,schemas")
            .get("/Users")

        then:
        response.statusCode() == 200
        def resources = response.jsonPath().getList("Resources")
        resources.size() >= 5
        resources.every { Map r ->
            r.id != null && r.id != "" && r.schemas != null && !((List) r.schemas).isEmpty()
        }
    }

    // ─── FLT_36: Non-existent attribute in attributes projection ────────────

    @SpecRef("RFC7644-3.4.2.5-R1")
    def "FLT_36: Non-existent attribute in attributes query parameter does not error"() {
        // RFC 7644 §3.4.2.5 — Non-existent attributes in projection are ignored
        given:
        String targetId = userData[0].id

        when:
        Response response = scimRequest()
            .queryParam("attributes", "nonExistentAttributeXYZ123")
            .get("/Users/${targetId}")

        then:
        response.statusCode() == 200
        response.jsonPath().getString("id") == targetId
        response.jsonPath().getList("schemas").contains(USER_SCHEMA)
    }

    // ─── FLT_37: attributes and excludedAttributes are mutually exclusive ────

    @SpecRef("RFC7644-3.4.2.5-R3")
    def "FLT_37: Providing both attributes and excludedAttributes returns HTTP 400"() {
        // RFC 7644 §3.4.2.5: "The 'attributes' and 'excludedAttributes' query parameters
        // MUST NOT be specified in the same request."
        when:
        Response response = scimRequest()
            .queryParam("attributes", "userName")
            .queryParam("excludedAttributes", "emails")
            .get("/Users")

        then:
        if (response.statusCode() == 400) {
            assertScimError(response, 400)
        } else {
            recordDeviation("RFC7644-3.4.2.5-R3", "RFC 7644 §3.4.2.5", "Server returned ${response.statusCode()} instead of 400 when both attributes and excludedAttributes were specified")
        }
    }

    // ─── FLT_38: Unrecognized query parameters SHOULD be ignored ────────────

    @SpecRef("RFC7644-3.4.2-R3")
    def "FLT_38: Unrecognized query parameter is ignored by server"() {
        // RFC 7644 §3.4.2: "Undefined query parameters MUST be ignored."
        when:
        Response response = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\"")
            .queryParam("someUnknownCustomParamXYZ", "12345")
            .get("/Users")

        then:
        response.statusCode() == 200
        response.jsonPath().getInt("totalResults") >= 5
    }

    // ─── FLT_39: Filter on schemas attribute matches declaring resources ────

    @SpecRef("RFC7644-3.4.2.2-R13")
    def "FLT_39: Filter on schemas attribute matches resources declaring schema URN"() {
        // RFC 7644 §3.4.2.2: "e.g. schemas eq 'urn:ietf:params:scim:schemas:core:2.0:User'"
        when:
        Response response = scimRequest()
            .queryParam("filter", "schemas eq \"${USER_SCHEMA}\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then:
        if (response.statusCode() == 200) {
            assert response.jsonPath().getInt("totalResults") >= 5
        } else {
            recordDeviation("RFC7644-3.4.2.2-R13", "RFC 7644 §3.4.2.2", "Server rejected filter on 'schemas' attribute with HTTP ${response.statusCode()}")
            assertScimError(response, 400)
        }
    }

    // ─── FLT_40: Root query with meta.resourceType scoping ───────────────────

    @RequiresCapability("search")
    @SpecRef("RFC7644-3.4.2.1-R1")
    def "FLT_40: Root query with meta.resourceType filter restricts results"() {
        // RFC 7644 §3.4.2.1: "A SCIM service provider MAY support searching at the root...
        // filter=meta.resourceType eq \"User\""
        when:
        Response response = scimRequest()
            .queryParam("filter", "meta.resourceType eq \"User\"")
            .get("/")

        then:
        if (response.statusCode() in [404, 501]) {
            throw new org.opentest4j.TestAbortedException("Server does not support root search (HTTP ${response.statusCode()})")
        }
        assert response.statusCode() == 200
        assert response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        def resources = response.jsonPath().getList("Resources")
        if (resources != null && !resources.isEmpty()) {
            assert resources.every { Map r ->
                r.meta?.resourceType == "User" || r.schemas?.contains(USER_SCHEMA)
            }
        }
    }

    // ─── FLT_41: Logical operators case insensitivity ───────────────────────

    @SpecRef("RFC7644-3.4.2.2-R8")
    @SpecRef("RFC7644-3.4.2.2-R9")
    @SpecRef("RFC7644-3.4.2.2-R10")
    def "FLT_41: Logical operators AND, OR, NOT in uppercase are case-insensitive"() {
        // RFC 7644 §3.4.2.2: "Logical operators are case-insensitive."
        when: "Query with uppercase AND"
        Response andResp = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\" AND active eq true")
            .get("/Users")

        and: "Query with uppercase OR"
        String u1 = userData[0].userName
        String u2 = userData[1].userName
        Response orResp = scimRequest()
            .queryParam("filter", "userName eq \"${u1}\" OR userName eq \"${u2}\"")
            .get("/Users")

        and: "Query with uppercase NOT"
        Response notResp = scimRequest()
            .queryParam("filter", "userName sw \"${PREFIX}\" AND NOT (active eq false)")
            .get("/Users")

        then: "All queries succeed with 200 OK and match expected results"
        assert andResp.statusCode() == 200
        assert andResp.jsonPath().getInt("totalResults") >= 3

        assert orResp.statusCode() == 200
        assert orResp.jsonPath().getInt("totalResults") == 2
        def orNames = orResp.jsonPath().getList("Resources.userName")
        assert orNames.contains(u1) && orNames.contains(u2)

        assert notResp.statusCode() == 200
        assert notResp.jsonPath().getInt("totalResults") >= 3
    }

    // ─── FLT_42: Operator precedence not > and > or without parentheses ──────

    @SpecRef("RFC7644-3.4.2.2-R10")
    @SpecRef("RFC7644-3.4.2.2-R11")
    def "FLT_42: Operator precedence evaluates and before or without parentheses"() {
        // RFC 7644 §3.4.2.2: "The order of precedence for operators has not have the highest
        // precedence, followed by and, and then or."
        when: "Query with 'A or B and C' where B and C matches user 0, and A matches inactive user 2"
        String u0 = userData[0].userName // active
        String u2 = userData[2].userName // inactive
        Response response = scimRequest()
            .queryParam("filter", "userName eq \"${u2}\" or userName eq \"${u0}\" and active eq true")
            .get("/Users")

        then: "and takes precedence over or: both user 2 and user 0 are returned"
        assert response.statusCode() == 200
        assert response.jsonPath().getInt("totalResults") == 2
        def names = response.jsonPath().getList("Resources.userName")
        assert names.contains(u0) : "Must contain user 0"
        assert names.contains(u2) : "Must contain inactive user 2 due to 'or' precedence over 'and'"
    }

    // ─── FLT_43: Comparison operator boundaries gt vs ge ────────────────────

    @SpecRef("RFC7644-3.4.2.2-R7")
    def "FLT_43: Comparison operators gt and ge adhere to exact boundary values"() {
        // RFC 7644 §3.4.2.2: "gt: Greater than, ge: Greater than or equal to"
        when: "Query with ge on userName"
        String targetUserName = userData[0].userName
        Response geResponse = scimRequest()
            .queryParam("filter", "userName ge \"${targetUserName}\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        and: "Query with gt on the same boundary userName"
        Response gtResponse = scimRequest()
            .queryParam("filter", "userName gt \"${targetUserName}\" and userName sw \"${PREFIX}\"")
            .get("/Users")

        then: "ge includes the boundary item, gt strictly excludes it"
        assert geResponse.statusCode() == 200
        assert gtResponse.statusCode() == 200

        List<String> geNames = geResponse.jsonPath().getList("Resources.userName") ?: []
        List<String> gtNames = gtResponse.jsonPath().getList("Resources.userName") ?: []

        assert geNames.contains(targetUserName) : "ge must include boundary value ${targetUserName}"
        assert !gtNames.contains(targetUserName) : "gt must strictly exclude boundary value ${targetUserName}"
        assert geNames.size() == gtNames.size() + 1 : "ge count must be exactly gt count + 1"
    }
}

