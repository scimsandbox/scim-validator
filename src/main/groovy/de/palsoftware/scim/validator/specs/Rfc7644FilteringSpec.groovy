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
 * One feature per requirement of requirements-rfc7644.yaml (area "Filtering"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644FilteringSpec extends ScimBaseSpec {

    // ─── helpers ─────────────────────────────────────────────────────────

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10)
    }

    /** Creates a user (201 required) and returns the response body as a map. */
    private Map mkUser(String userName, Map extra = [:]) {
        Map overrides = [userName: userName]
        overrides.putAll(extra)
        Response r = createUser(overrides)
        assert r.statusCode() == 201 :
            "setup: POST /Users must return 201 but was ${r.statusCode()}: ${r.body().asString()}"
        return (Map) r.jsonPath().get("")
    }

    private Response filterUsers(String filter) {
        return scimRequest().queryParam("filter", filter).get("/Users")
    }

    private static List<String> userNamesOf(Response r) {
        return r.jsonPath().getList("Resources.userName") ?: []
    }

    private static List<String> idsOf(Response r) {
        return r.jsonPath().getList("Resources.id") ?: []
    }

    /** Aborts the test (skip) when the server rejects the filter: it then does not offer the optional feature. */
    private void abortIfRejected(Response r, String what) {
        if (r.statusCode() == 400) {
            throw new TestAbortedException("Server does not offer ${what}: filter rejected with HTTP 400 (${r.jsonPath().getString('detail')})")
        }
    }

    private void assertInvalidFilter(String filter, String rfcNote) {
        Response r = filterUsers(filter)
        assert r.statusCode() == 400 :
            "RFC 7644 3.4.2.2: ${rfcNote}: filter '${filter}' must be rejected with HTTP 400 but was ${r.statusCode()}: ${r.body().asString().take(200)}"
        assertScimError(r, 400, "invalidFilter")
    }

    private static String swapCase(String s) {
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i)
            sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c))
        }
        return sb.toString()
    }

    private String findBinaryFilterAttribute() {
        Response schemaResp = scimRequestQuiet().get("/Schemas/${USER_SCHEMA}")
        if (schemaResp.statusCode() != 200) return null
        List<Map> attrs = schemaResp.jsonPath().getList("attributes") ?: []
        for (Map attr : attrs) {
            if (attr.type == "binary") return attr.name as String
            for (Map sub : (attr.subAttributes as List<Map> ?: [])) {
                if (sub.type == "binary") return "${attr.name}.${sub.name}".toString()
            }
        }
        return null
    }

    private void assertOrderingOperatorRejected(String op) {
        String bool = "active ${op} true"
        Response rb = filterUsers(bool)
        assert rb.statusCode() == 400 :
            "RFC 7644 3.4.2.2 (${op}): filter '${bool}' on a Boolean attribute must fail with HTTP 400 but was ${rb.statusCode()}"
        assertScimError(rb, 400, "invalidFilter")

        String binAttr = findBinaryFilterAttribute()
        if (binAttr != null) {
            String bin = "${binAttr} ${op} \"QUJD\""
            Response rbin = filterUsers(bin)
            assert rbin.statusCode() == 400 :
                "RFC 7644 3.4.2.2 (${op}): filter '${bin}' on a Binary attribute must fail with HTTP 400 but was ${rbin.statusCode()}"
            assertScimError(rbin, 400, "invalidFilter")
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 926-926
    // Filtering is an OPTIONAL parameter for SCIM service providers.
    @SpecRef("RFC7644-3.4.2.2-6a180d")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-6a180d: Filtering is an OPTIONAL parameter for SCIM service providers."() {
        expect:
        Response spc = scimRequest().get("/ServiceProviderConfig")
        assert spc.statusCode() == 200
        assert spc.jsonPath().get("filter.supported") == true :
            "RFC 7644 3.4.2.2: ServiceProviderConfig filter.supported must be true for the filter parameter to be offered"

        and: "a server that advertises filtering applies the filter parameter"
        String un = "flt_${tag()}"
        mkUser(un)
        mkUser("flt_other_${tag()}")
        Response r = filterUsers("userName eq \"${un}\"")
        assert r.statusCode() == 200 : "RFC 7644 3.4.2.2: advertised filter support, but GET /Users?filter= returned ${r.statusCode()}"
        assert r.jsonPath().getInt("totalResults") == 1 : "RFC 7644 3.4.2.2: filter must restrict the result set"
        assert userNamesOf(r) == [un]
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 927-929
    // Clients MAY discover service provider filter capabilities by looking at the "filter" attribute of the "ServiceProviderConfig" endpoint (see Section 4).
    @SpecRef("RFC7644-3.4.2.2-228889")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-228889: Clients MAY discover service provider filter capabilities by looking at the 'filter' attribute of the..."() {
        expect:
        Response spc = scimRequest().get("/ServiceProviderConfig")
        assert spc.statusCode() == 200 : "RFC 7644 4: GET /ServiceProviderConfig must return 200 but was ${spc.statusCode()}"
        def f = spc.jsonPath().get("filter")
        assert f instanceof Map : "RFC 7644 3.4.2.2 / 4: ServiceProviderConfig must contain a 'filter' object but had ${f}"
        assert f.supported instanceof Boolean : "RFC 7643 5: filter.supported must be a Boolean but was ${f.supported}"
        if (f.supported) {
            assert f.maxResults instanceof Integer && f.maxResults > 0 :
                "RFC 7643 5: filter.maxResults must be a positive integer when filtering is supported but was ${f.maxResults}"
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 929-931
    // Clients MAY request a subset of resources by specifying the "filter" query parameter containing a filter expression.
    @SpecRef("RFC7644-3.4.2.2-9a80bb")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-9a80bb: Clients MAY request a subset of resources by specifying the 'filter' query parameter containing a filter..."() {
        given:
        String prefix = "fsub_${tag()}"
        String u1 = "${prefix}_one"
        String u2 = "${prefix}_two"
        mkUser(u1)
        mkUser(u2)

        when:
        Response all = filterUsers("userName sw \"${prefix}\"")
        Response subset = filterUsers("userName eq \"${u1}\"")

        then:
        assert all.statusCode() == 200 : "RFC 7644 3.4.2.2: filter request returned ${all.statusCode()}"
        assert all.jsonPath().getInt("totalResults") == 2
        assert subset.statusCode() == 200 : "RFC 7644 3.4.2.2: filter request returned ${subset.statusCode()}"
        assert subset.jsonPath().getInt("totalResults") == 1 :
            "RFC 7644 3.4.2.2: filter must yield a subset of the resources"
        assert userNamesOf(subset) == [u1]
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 931-932
    // When specified, only those resources matching the filter expression SHALL be returned.
    @SpecRef("RFC7644-3.4.2.2-f5a131")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-f5a131: When specified, only those resources matching the filter expression SHALL be returned."() {
        given:
        String prefix = "fmatch_${tag()}"
        mkUser("${prefix}_1", [displayName: "Alpha ${prefix} 1"])
        mkUser("${prefix}_2", [displayName: "Beta ${prefix} 2"])
        mkUser("${prefix}_3", [displayName: "Alpha ${prefix} 3"])

        when:
        Response r = filterUsers("userName sw \"${prefix}\" and displayName sw \"Alpha\"")

        then:
        assert r.statusCode() == 200
        List<Map> resources = r.jsonPath().getList("Resources") ?: []
        assert resources.size() == 2 :
            "RFC 7644 3.4.2.2: only resources matching the filter SHALL be returned, got ${resources.collect { it.userName }}"
        resources.each { Map u ->
            assert u.userName.startsWith(prefix) && u.displayName.toLowerCase().startsWith("alpha") :
                "RFC 7644 3.4.2.2: returned resource ${u.userName} does not match the filter"
        }
        assert r.jsonPath().getInt("totalResults") == 2
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 944-945
    // The filter parameter MUST contain at least one valid expression (see Table 3).
    @SpecRef("RFC7644-3.4.2.2-d8aeaa")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-d8aeaa: The filter parameter MUST contain at least one valid expression (see Table 3)."() {
        expect:
        ["this is not a filter", "((", "and or", "\"unterminated"].each { String bad ->
            assertInvalidFilter(bad, "the filter parameter MUST contain at least one valid expression")
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 945-946
    // Each expression MUST contain an attribute name followed by an attribute operator and optional value.
    @SpecRef("RFC7644-3.4.2.2-9d4485")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-9d4485: Each expression MUST contain an attribute name followed by an attribute operator and optional value."() {
        given:
        String un = "fexp_${tag()}"
        mkUser(un)

        expect: "an expression without operator, without attribute or without a value for a comparison is invalid"
        assertInvalidFilter("userName", "expression lacks an attribute operator")
        assertInvalidFilter("eq \"${un}\"", "expression lacks an attribute name")
        assertInvalidFilter("userName eq", "comparison expression lacks a value")

        and: "the value is optional for the presence operator"
        Response pr = filterUsers("userName pr and userName eq \"${un}\"")
        assert pr.statusCode() == 200 : "RFC 7644 3.4.2.2: 'attrPath pr' without value is a valid expression, got ${pr.statusCode()}"
        assert userNamesOf(pr) == [un]
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 946-947
    // Multiple expressions MAY be combined using logical operators (see Table 4).
    @SpecRef("RFC7644-3.4.2.2-1508f0")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-1508f0: Multiple expressions MAY be combined using logical operators (see Table 4)."() {
        given:
        String prefix = "flog_${tag()}"
        mkUser("${prefix}_1", [displayName: "d1 ${prefix}"])
        mkUser("${prefix}_2", [displayName: "d2 ${prefix}"])

        when:
        Response orResp = filterUsers("userName eq \"${prefix}_1\" or userName eq \"${prefix}_2\"")
        Response andResp = filterUsers("userName sw \"${prefix}\" and displayName eq \"d1 ${prefix}\"")
        abortIfRejected(orResp, "logical operator 'or'")
        abortIfRejected(andResp, "logical operator 'and'")

        then:
        assert orResp.statusCode() == 200
        assert userNamesOf(orResp).sort() == ["${prefix}_1", "${prefix}_2"] :
            "RFC 7644 3.4.2.2: 'or' must match resources satisfying either expression, got ${userNamesOf(orResp)}"
        assert andResp.statusCode() == 200
        assert userNamesOf(andResp) == ["${prefix}_1"] :
            "RFC 7644 3.4.2.2: 'and' must match only resources satisfying both expressions, got ${userNamesOf(andResp)}"
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 947-948
    // Expressions MAY be grouped together using round brackets "(" and ")" (see Table 5).
    @SpecRef("RFC7644-3.4.2.2-789ea9")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-789ea9: Expressions MAY be grouped together using round brackets '(' and ')' (see Table 5)."() {
        given:
        String prefix = "fgrp_${tag()}"
        mkUser("${prefix}_1", [active: true])
        mkUser("${prefix}_2", [active: true])

        when:
        Response grouped = filterUsers("(userName eq \"${prefix}_1\" or userName eq \"${prefix}_2\") and active eq true")
        Response single = filterUsers("(userName eq \"${prefix}_1\")")
        abortIfRejected(grouped, "round bracket grouping")
        abortIfRejected(single, "round bracket grouping")

        then:
        assert grouped.statusCode() == 200
        assert userNamesOf(grouped).sort() == ["${prefix}_1", "${prefix}_2"] :
            "RFC 7644 3.4.2.2: grouped expression must evaluate as a whole, got ${userNamesOf(grouped)}"
        assert single.statusCode() == 200
        assert userNamesOf(single) == ["${prefix}_1"]
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 992-1004
    // Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with "scimType" of "invalidFilter".
    @SpecRef("RFC7644-3.4.2.2-a93ea0")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-a93ea0: gt: Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with 'scimType' of..."() {
        expect:
        assertOrderingOperatorRejected("gt")
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1015-1027
    // Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with "scimType" of "invalidFilter".
    @SpecRef("RFC7644-3.4.2.2-baa267")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-baa267: ge: Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with 'scimType' of..."() {
        expect:
        assertOrderingOperatorRejected("ge")
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1029-1040
    // Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with "scimType" of "invalidFilter".
    @SpecRef("RFC7644-3.4.2.2-e67674")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-e67674: lt: Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with 'scimType' of..."() {
        expect:
        assertOrderingOperatorRejected("lt")
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1042-1054
    // Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with "scimType" of "invalidFilter".
    @SpecRef("RFC7644-3.4.2.2-0f284b")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-0f284b: le: Boolean and Binary attributes SHALL cause a failed response (HTTP status code 400) with 'scimType' of..."() {
        expect:
        assertOrderingOperatorRejected("le")
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 1090-1094
    // Boolean expressions MAY be grouped using parentheses to change the standard order of operations, i.e., to evaluate logical "or" operators before logical "and" operators.
    @SpecRef("RFC7644-3.4.2.2-d9263c")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-d9263c: ( ): Boolean expressions MAY be grouped using parentheses to change the standard order of operations,..."() {
        given: "two active users"
        String prefix = "fpar_${tag()}"
        String u1 = "${prefix}_1"
        String u2 = "${prefix}_2"
        mkUser(u1, [active: true])
        mkUser(u2, [active: true])

        when:
        Response standard = filterUsers("userName eq \"${u1}\" or userName eq \"${u2}\" and active eq false")
        Response grouped = filterUsers("(userName eq \"${u1}\" or userName eq \"${u2}\") and active eq false")
        abortIfRejected(grouped, "round bracket precedence grouping")

        then:
        assert grouped.statusCode() == 200
        assert userNamesOf(grouped) == [] :
            "RFC 7644 3.4.2.2: with parentheses 'or' is evaluated before 'and', no user is inactive, got ${userNamesOf(grouped)}"
        assert standard.statusCode() == 200
        assert userNamesOf(standard) == [u1] :
            "RFC 7644 3.4.2.2: without parentheses 'and' binds tighter than 'or', got ${userNamesOf(standard)}"
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 1096-1106
    // Service providers MAY support complex filters
    @SpecRef("RFC7644-3.4.2.2-571b49")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-571b49: [ ]: Service providers MAY support complex filters"() {
        given:
        String un = "fval_${tag()}"
        mkUser(un, [emails: [[value: "w_${un}@test.com", type: "work"], [value: "h_${un}@test.com", type: "home"]]])

        when:
        Response match = filterUsers("userName eq \"${un}\" and emails[type eq \"work\"]")
        Response noMatch = filterUsers("userName eq \"${un}\" and emails[type eq \"fax\"]")

        then:
        assert match.statusCode() in [200, 400]
        if (match.statusCode() == 200) {
            assert userNamesOf(match) == [un] :
                "RFC 7644 3.4.2.2: emails[type eq \"work\"] must match a user with a work email, got ${userNamesOf(match)}"
            assert noMatch.statusCode() == 200
            assert userNamesOf(noMatch) == [] :
                "RFC 7644 3.4.2.2: emails[type eq \"fax\"] must not match a user without a fax email, got ${userNamesOf(noMatch)}"
        } else {
            assertScimError(match, 400, "invalidFilter")
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1096-1106
    // where expressions MUST be applied to the same value of a parent attribute specified immediately before the left square bracket ("[").
    @SpecRef("RFC7644-3.4.2.2-862c56")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-862c56: [ ]: where expressions MUST be applied to the same value of a parent attribute specified immediately..."() {
        given: "a user whose work email and home email differ"
        String un = "fsame_${tag()}"
        String workMail = "w_${un}@test.com"
        String homeMail = "h_${un}@test.com"
        mkUser(un, [emails: [[value: workMail, type: "work"], [value: homeMail, type: "home"]]])

        when:
        Response sameValue = filterUsers("userName eq \"${un}\" and emails[type eq \"work\" and value eq \"${workMail}\"]")
        Response mixedValues = filterUsers("userName eq \"${un}\" and emails[type eq \"work\" and value eq \"${homeMail}\"]")

        then:
        assert sameValue.statusCode() in [200, 400]
        if (sameValue.statusCode() == 200) {
            assert userNamesOf(sameValue) == [un] :
                "RFC 7644 3.4.2.2: both expressions hold for the same email value, the user must match, got ${userNamesOf(sameValue)}"
            assert mixedValues.statusCode() == 200
            assert userNamesOf(mixedValues) == [] :
                "RFC 7644 3.4.2.2: expressions in [ ] must apply to the same value; type=work and value=<home email> belong to different values, got ${userNamesOf(mixedValues)}"
        } else {
            assertScimError(sameValue, 400, "invalidFilter")
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1096-1106
    // The expression within square brackets ("[" and "]") MUST be a valid filter expression based upon sub-attributes of the parent attribute.
    @SpecRef("RFC7644-3.4.2.2-f97968")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-f97968: [ ]: The expression within square brackets ('[' and ']') MUST be a valid filter expression based upon..."() {
        given:
        String un = "fbr_${tag()}"
        mkUser(un)

        expect: "an expression within square brackets that is not a valid filter expression is rejected"
        assertInvalidFilter("userName eq \"${un}\" and emails[type eq]", "invalid expression within [ ]")
        assertInvalidFilter("userName eq \"${un}\" and emails[type eq \"work\"", "unterminated [ ]")
        assertInvalidFilter("userName eq \"${un}\" and emails[]", "empty [ ]")
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 1096-1106
    // Nested expressions MAY be used.
    @SpecRef("RFC7644-3.4.2.2-00617b")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-00617b: [ ]: Nested expressions MAY be used."() {
        given:
        String un = "fnest_${tag()}"
        mkUser(un, [emails: [[value: "w_${un}@test.com", type: "work"]]])

        when:
        Response match = filterUsers("userName eq \"${un}\" and emails[not (type eq \"fax\")]")
        Response noMatch = filterUsers("userName eq \"${un}\" and emails[not (type eq \"work\")]")

        then:
        assert match.statusCode() in [200, 400]
        if (match.statusCode() == 200) {
            assert userNamesOf(match) == [un] :
                "RFC 7644 3.4.2.2: emails[not (type eq \"fax\")] must match a user whose only email is of type work, got ${userNamesOf(match)}"
            assert noMatch.statusCode() == 200
            assert userNamesOf(noMatch) == [] :
                "RFC 7644 3.4.2.2: emails[not (type eq \"work\")] must not match a user whose only email is of type work, got ${userNamesOf(noMatch)}"
        } else {
            assertScimError(match, 400, "invalidFilter")
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1127-1128
    // SCIM filters MUST conform to the following ABNF [RFC5234] rules as specified below:
    @SpecRef("RFC7644-3.4.2.2-a8f5e2")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-a8f5e2: SCIM filters MUST conform to the following ABNF [RFC5234] rules as specified below:"() {
        given:
        String t = tag()
        String un = "Fabnf_${t}"
        Map created = mkUser(un, [displayName: "Disp ${t}", active: true,
                                   emails: [[value: "e_${t}@test.com", type: "work"]]])
        String miss = "zz_nomatch_${t}"

        expect: "filters that conform to the ABNF are accepted and evaluated"
        List<String> conforming = [
            "userName eq \"${un}\"",
            "userName ne \"${miss}\" and userName eq \"${un}\"",
            "userName co \"abnf_${t}\"",
            "userName sw \"Fabnf_${t}\"",
            "userName ew \"${t}\"",
            "displayName pr and userName eq \"${un}\"",
            "active eq true and userName eq \"${un}\"",
            "userName eq \"${miss}\" or userName eq \"${un}\"",
            "(userName eq \"${un}\")",
            "not (userName eq \"${miss}\") and userName eq \"${un}\"",
            "meta.created gt \"2000-01-01T00:00:00Z\" and userName eq \"${un}\"",
            "emails.value eq \"e_${t}@test.com\"",
            "${USER_SCHEMA}:userName eq \"${un}\"".toString()
        ]
        List<String> failures = []
        conforming.each { String f ->
            Response r = filterUsers(f)
            if (r.statusCode() != 200) {
                failures << "'${f}' -> HTTP ${r.statusCode()}"
            } else if (!(userNamesOf(r) == [un])) {
                failures << "'${f}' -> matched ${userNamesOf(r)} instead of [${un}]"
            }
        }
        assert failures.isEmpty() : "RFC 7644 3.4.2.2: filters conforming to the ABNF must be processed: ${failures}"

        and: "filters violating the ABNF are rejected"
        assertInvalidFilter("userName equals \"${un}\"", "unknown compareOp violates the ABNF")
        assertInvalidFilter("userName eq \"${un}\" and", "logExp lacks its right operand")
        assertInvalidFilter("(userName eq \"${un}\"", "unbalanced parenthesis")
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1183-1184
    // Filters MUST be evaluated using the following order of operations, in order of precedence:
    @SpecRef("RFC7644-3.4.2.2-4bde68")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-4bde68: Filters MUST be evaluated using the following order of operations, in order of precedence:"() {
        given: "two active users"
        String prefix = "fprec_${tag()}"
        String u1 = "${prefix}_1"
        String u2 = "${prefix}_2"
        mkUser(u1, [active: true])
        mkUser(u2, [active: true])

        when:
        Response andBeforeOr1 = filterUsers("userName eq \"${u1}\" or userName eq \"${u2}\" and active eq false")
        Response andBeforeOr2 = filterUsers("active eq false and userName eq \"${u1}\" or userName eq \"${u2}\"")
        Response grouping = filterUsers("(userName eq \"${u1}\" or userName eq \"${u2}\") and active eq false")

        then: "'and' takes precedence over 'or'"
        assert andBeforeOr1.statusCode() == 200
        assert userNamesOf(andBeforeOr1) == [u1] :
            "RFC 7644 3.4.2.2: 'A or B and C' must evaluate as 'A or (B and C)', got ${userNamesOf(andBeforeOr1)}"
        assert andBeforeOr2.statusCode() == 200
        assert userNamesOf(andBeforeOr2) == [u2] :
            "RFC 7644 3.4.2.2: 'C and A or B' must evaluate as '(C and A) or B', got ${userNamesOf(andBeforeOr2)}"

        and: "grouping operators take precedence over logical operators"
        assert grouping.statusCode() == 200
        assert userNamesOf(grouping) == [] :
            "RFC 7644 3.4.2.2: '(A or B) and C' must evaluate the group first, got ${userNamesOf(grouping)}"
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1197-1198
    // For complex attributes, a fully qualified sub-attribute MUST be specified using standard attribute notation (Section 3.10).
    @SpecRef("RFC7644-3.4.2.2-f5314c")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-f5314c: For complex attributes, a fully qualified sub-attribute MUST be specified using standard attribute..."() {
        given:
        String t = tag()
        String un = "fsub_${t}"
        String gn = "Gn${t}"
        mkUser(un, [name: [givenName: gn, familyName: "Fn${t}"]])

        when:
        Response qualified = filterUsers("name.givenName eq \"${gn}\"")
        Response unqualified = filterUsers("name eq \"${gn}\"")

        then: "the fully qualified sub-attribute works"
        assert qualified.statusCode() == 200
        assert userNamesOf(qualified) == [un] :
            "RFC 7644 3.4.2.2: name.givenName eq must match the user, got ${userNamesOf(qualified)}"

        and: "the complex attribute without a sub-attribute is not accepted as a value filter"
        if (unqualified.statusCode() == 200) {
            assert !userNamesOf(unqualified).contains(un) :
                "RFC 7644 3.4.2.2: a complex attribute needs a fully qualified sub-attribute; 'name eq' must not match on name.givenName"
        } else {
            assertScimError(unqualified, 400, "invalidFilter")
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1202-1205
    // When applying a comparison (e.g., "eq") or presence filter (e.g., "pr") to a defaulted attribute, the service provider SHALL use the value that was returned to the client that last created or modified the attribute.
    @SpecRef("RFC7644-3.4.2.2-a1f250")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-a1f250: When applying a comparison (e.g., 'eq') or presence filter (e.g., 'pr') to a defaulted attribute, the..."() {
        given: "a user created without 'active': the server defaults it"
        String t = tag()
        String un = "fdef_${t}"
        Map created = mkUser(un)
        String id = created.id
        if (created.active == null) {
            throw new TestAbortedException("Server returned no defaulted 'active' attribute on create")
        }
        boolean returned = created.active as boolean

        when: "filtering on the value returned to the client"
        Response same = filterUsers("id eq \"${id}\" and active eq ${returned}")
        Response other = filterUsers("id eq \"${id}\" and active eq ${!returned}")
        Response present = filterUsers("id eq \"${id}\" and active pr")

        then:
        assert same.statusCode() == 200
        assert idsOf(same) == [id] :
            "RFC 7644 3.4.2.2: comparison on a defaulted attribute must use the returned value (active=${returned}), got ${idsOf(same)}"
        assert other.statusCode() == 200
        assert idsOf(other) == [] :
            "RFC 7644 3.4.2.2: comparison on a defaulted attribute must use the returned value (active=${returned}), but active eq ${!returned} matched"
        assert present.statusCode() == 200
        assert idsOf(present) == [id] : "RFC 7644 3.4.2.2: 'active pr' must match a defaulted attribute that was returned"

        when: "the attribute is modified by the client"
        Response put = scimRequest().body(groovy.json.JsonOutput.toJson([schemas: [USER_SCHEMA], userName: un, active: !returned])).put("/Users/${id}")

        then:
        assert put.statusCode() == 200 : "setup: PUT /Users/{id} must return 200 but was ${put.statusCode()}: ${put.body().asString().take(300)}"
        assert put.jsonPath().getBoolean("active") == !returned
        Response afterSame = filterUsers("id eq \"${id}\" and active eq ${!returned}")
        Response afterOther = filterUsers("id eq \"${id}\" and active eq ${returned}")
        assert idsOf(afterSame) == [id] :
            "RFC 7644 3.4.2.2: comparison must use the value returned by the last modification (active=${!returned})"
        assert idsOf(afterOther) == [] :
            "RFC 7644 3.4.2.2: comparison must use the value returned by the last modification, but the old value active=${returned} still matches"
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 1207-1207
    // Providers MAY support additional filter operations if they choose.
    @SpecRef("RFC7644-3.4.2.2-2e8124")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-2e8124: Providers MAY support additional filter operations if they choose."() {
        given:
        String un = "fext_${tag()}"
        mkUser(un)

        when: "a client uses a comparison operator that is not defined by RFC 7644"
        Response r = filterUsers("userName regex \"${un}\"")

        then: "the server either implements the additional operation or declines it as the RFC requires"
        assert r.statusCode() in [200, 400] :
            "RFC 7644 3.4.2.2: an additional operator is either supported or declined with 400, got ${r.statusCode()}"
        if (r.statusCode() == 400) {
            assertScimError(r, 400, "invalidFilter")
        } else {
            assert r.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA) :
                "RFC 7644 3.4.2.2: an accepted additional operation must produce a ListResponse"
            assert r.jsonPath().getInt("totalResults") >= 0
        }
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1208-1211
    // Providers MUST decline to filter results if the specified filter operation is not recognized and return an HTTP 400 error with a "scimType" error of "invalidFilter" and an appropriate human-readable response as per Section 3.12.
    @SpecRef("RFC7644-3.4.2.2-ae3b76")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-ae3b76: Providers MUST decline to filter results if the specified filter operation is not recognized and return an..."() {
        given:
        String un = "funk_${tag()}"
        mkUser(un)

        when:
        Response r = filterUsers("userName regex \"${un}\"")

        then:
        assert r.statusCode() == 400 :
            "RFC 7644 3.4.2.2: unrecognized operator 'regex' must be declined with HTTP 400 but was ${r.statusCode()}: ${r.body().asString().take(200)}"
        assertScimError(r, 400, "invalidFilter")
        assert r.jsonPath().getString("detail")?.trim() :
            "RFC 7644 3.4.2.2/3.12: the error must carry a human-readable detail"
        assert !(r.jsonPath().getList("Resources")) : "RFC 7644 3.4.2.2: the provider must decline to filter, not return results"
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MUST, lines 1216-1218
    // When comparing attributes of type String, the case sensitivity for String type attributes SHALL be determined by the attribute's "caseExact" characteristic (see Section 2.2 of [RFC7643]).
    @SpecRef("RFC7644-3.4.2.2-4a0c12")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-4a0c12: When comparing attributes of type String, the case sensitivity for String type attributes SHALL be..."() {
        given:
        String t = tag()
        String un = "FcAse_${t}"
        String ext = "ExT-${t}"
        String disp = "DiSp ${t}"
        Map created = mkUser(un, [externalId: ext, displayName: disp])
        String id = created.id

        Response schemaResp = scimRequest().get("/Schemas/${USER_SCHEMA}")
        assert schemaResp.statusCode() == 200 : "setup: GET /Schemas/${USER_SCHEMA} returned ${schemaResp.statusCode()}"
        List<Map> attrs = schemaResp.jsonPath().getList("attributes")

        expect: "equality follows each attribute's caseExact characteristic"
        List<String> failures = []
        [[name: "userName", value: un], [name: "externalId", value: ext], [name: "displayName", value: disp]].each { Map a ->
            Map def_ = attrs.find { it.name == a.name }
            assert def_ != null : "schema for User must define ${a.name}"
            boolean caseExact = (def_.caseExact ?: false) as boolean
            String swapped = swapCase(a.value as String)
            Response eqResp = filterUsers("id eq \"${id}\" and ${a.name} eq \"${swapped}\"")
            if (eqResp.statusCode() != 200) {
                failures << "${a.name} eq '${swapped}' -> HTTP ${eqResp.statusCode()}"
                return
            }
            boolean matched = idsOf(eqResp).contains(id)
            if (matched == caseExact) {
                failures << "${a.name} (caseExact=${caseExact}): 'eq' with swapped case ${matched ? 'matched' : 'did not match'}"
            }
            Response sameResp = filterUsers("id eq \"${id}\" and ${a.name} eq \"${a.value}\"")
            if (!idsOf(sameResp).contains(id)) {
                failures << "${a.name}: 'eq' with the exact value did not match"
            }
        }
        assert failures.isEmpty() :
            "RFC 7644 3.4.2.2: case sensitivity of String comparison must follow caseExact: ${failures}"
    }

    // RFC 7644 section 3.4.2.2 (Filtering), MAY, lines 1220-1221
    // Clients MAY query by schema or schema extensions by using a filter expression including the "schemas" attribute (as shown in Figure 2).
    @SpecRef("RFC7644-3.4.2.2-a03895")
    @RequiresCapability("filter")
    def "RFC7644-3.4.2.2-a03895: Clients MAY query by schema or schema extensions by using a filter expression including the 'schemas'..."() {
        given:
        String un = "fsch_${tag()}"
        mkUser(un)

        when:
        Response match = filterUsers("userName eq \"${un}\" and schemas eq \"${USER_SCHEMA}\"")
        Response noMatch = filterUsers("userName eq \"${un}\" and schemas eq \"urn:example:params:scim:schemas:not-used:${tag()}\"")

        then:
        assert match.statusCode() in [200, 400]
        if (match.statusCode() == 200) {
            assert userNamesOf(match) == [un] :
                "RFC 7644 3.4.2.2: schemas eq <User schema> must match a User resource, got ${userNamesOf(match)}"
            assert noMatch.statusCode() == 200
            assert userNamesOf(noMatch) == [] :
                "RFC 7644 3.4.2.2: schemas eq <unused schema> must not match the User, got ${userNamesOf(noMatch)}"
        } else {
            assertScimError(match, 400, "invalidFilter")
        }
    }
}
