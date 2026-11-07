package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "Querying resources"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644QuerySpec extends ScimBaseSpec {

    // RFC 7644 section 3.4 (Retrieving Resources), MAY, lines 719-720
    // Resources MAY be retrieved via opaque, unique URLs or via queries (see Section 3.4.2).
    @SpecRef("RFC7644-3.4-1277b9")
    def "RFC7644-3.4-1277b9: Resources MAY be retrieved via opaque, unique URLs or via queries (see Section 3.4.2)."() {
        expect:
        Response created = createUser([userName: uniq("q1_")])
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST /Users must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")

        Response byUrl = scimRequest().get("/Users/${id}")
        assert byUrl.statusCode() == 200 : "RFC 7644 3.4.1: GET /Users/{id} must return 200 but was ${byUrl.statusCode()}"
        assert byUrl.jsonPath().getString("id") == id : "RFC 7644 3.4: retrieval by URL must return the resource ${id}"

        Response byQuery = userQuery([filter: "userName eq \"${userName}\""])
        assert byQuery.statusCode() == 200 : "RFC 7644 3.4.2: GET /Users?filter=... must return 200 but was ${byQuery.statusCode()}"
        assert resourceIds(byQuery) == [id] : "RFC 7644 3.4: retrieval by query must return exactly the resource ${id} but returned ${resourceIds(byQuery)}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MAY, lines 810-812
    // Queries MAY be made against a single resource or a resource type endpoint (e.g., "/Users"), or the service provider Base URI.
    @SpecRef("RFC7644-3.4.2-ac99e1")
    def "RFC7644-3.4.2-ac99e1: Queries MAY be made against a single resource or a resource type endpoint (e.g., '/Users'), or the service..."() {
        expect:
        Response created = createUser([userName: uniq("q2_")])
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST /Users must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")

        Response single = scimRequest().get("/Users/${id}")
        assert single.statusCode() == 200 : "RFC 7644 3.4.2: query against a single resource must return 200 but was ${single.statusCode()}"
        assert single.jsonPath().getString("id") == id : "RFC 7644 3.4.2: single resource query must return resource ${id}"

        Response endpoint = userQuery([filter: "userName eq \"${userName}\""])
        assert endpoint.statusCode() == 200 : "RFC 7644 3.4.2: query against the /Users endpoint must return 200 but was ${endpoint.statusCode()}"
        assert resourceIds(endpoint) == [id] : "RFC 7644 3.4.2: /Users query must return ${id} but returned ${resourceIds(endpoint)}"

        Response root = scimRequest().queryParam("filter", "userName eq \"${userName}\"").get("/")
        assert root.statusCode() in [200, 400, 403, 404, 405, 501] : "RFC 7644 3.4.2: query against the base URI must return 200 or be unsupported, but was ${root.statusCode()}"
        if (root.statusCode() == 200) {
            assert resourceIds(root) == [id] : "RFC 7644 3.4.2: base URI query must return ${id} but returned ${resourceIds(root)}"
        }
    }

    // RFC 7644 section 3.4.2 (Query Resources), SHOULD, lines 812-815
    // SHOULD ignore any query parameters they do not recognize instead of rejecting the query for versioning compatibility reasons.
    @SpecRef("RFC7644-3.4.2-e09c65")
    def "RFC7644-3.4.2-e09c65: SHOULD ignore any query parameters they do not recognize instead of rejecting the query for versioning..."() {
        expect:
        String prefix = uniq("q4_")
        Response created = createUser([userName: prefix])
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST /Users must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String filter = "userName eq \"${prefix}\""

        Response plain = userQuery([filter: filter])
        Response withUnknown = userQuery([filter: filter, ("scimValidatorUnknownParam" + prefix.take(6)): "someValue", "x-unknown": "1"])
        assert plain.statusCode() == 200 : "baseline query must return 200 but was ${plain.statusCode()}"
        assert withUnknown.statusCode() == 200 : "RFC 7644 3.4.2: service providers SHOULD ignore unrecognized query parameters instead of rejecting the query, but status was ${withUnknown.statusCode()}"
        assert resourceIds(withUnknown) == [id] : "RFC 7644 3.4.2: unrecognized query parameters must not change the result; expected [${id}] but got ${resourceIds(withUnknown)}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MUST, lines 817-818
    // Responses MUST be identified using the following URI: "urn:ietf:params:scim:api:messages:2.0:ListResponse".
    @SpecRef("RFC7644-3.4.2-315e52")
    def "RFC7644-3.4.2-315e52: Responses MUST be identified using the following URI: 'urn:ietf:params:scim:api:messages:2.0:ListResponse'."() {
        expect:
        String prefix = uniq("q5_")
        Response user = createUser([userName: prefix])
        assert user.statusCode() == 201
        Response group = createGroup(prefix)
        assert group.statusCode() == 201 : "RFC 7644 3.3: POST /Groups must return 201 but was ${group.statusCode()}"

        Response users = userQuery([filter: "userName eq \"${prefix}\""])
        assert users.statusCode() == 200
        assert users.jsonPath().getList("schemas") == [LIST_RESPONSE_SCHEMA] : "RFC 7644 3.4.2: /Users list response schemas must be [${LIST_RESPONSE_SCHEMA}] but was ${users.jsonPath().getList('schemas')}"

        Response groups = scimRequest().queryParam("filter", "displayName eq \"${prefix}\"").get("/Groups")
        assert groups.statusCode() == 200
        assert groups.jsonPath().getList("schemas") == [LIST_RESPONSE_SCHEMA] : "RFC 7644 3.4.2: /Groups list response schemas must be [${LIST_RESPONSE_SCHEMA}] but was ${groups.jsonPath().getList('schemas')}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MUST, lines 825-825
    // REQUIRED.
    @SpecRef("RFC7644-3.4.2-73b756")
    def "RFC7644-3.4.2-73b756: totalResults: REQUIRED."() {
        expect:
        String prefix = uniq("q6_")
        assert createUser([userName: prefix + "a"]).statusCode() == 201
        assert createUser([userName: prefix + "b"]).statusCode() == 201

        Response matching = userQuery([filter: "userName sw \"${prefix}\""])
        assert matching.statusCode() == 200
        def total = matching.jsonPath().get("totalResults")
        assert total instanceof Integer : "RFC 7644 3.4.2: totalResults is REQUIRED and must be an integer but was ${total}"
        assert total == 2 : "RFC 7644 3.4.2: totalResults must be 2 for two matching users but was ${total}"

        Response empty = userQuery([filter: "userName eq \"${prefix}_nomatch\""])
        assert empty.statusCode() == 200
        def zero = empty.jsonPath().get("totalResults")
        assert zero instanceof Integer && zero == 0 : "RFC 7644 3.4.2: totalResults is REQUIRED even for empty results, but was ${zero}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MAY, lines 828-829
    // This MAY be a subset of the full set of resources if pagination (Section 3.4.2.4) is requested.
    @SpecRef("RFC7644-3.4.2-f72e68")
    def "RFC7644-3.4.2-f72e68: Resources: This MAY be a subset of the full set of resources if pagination (Section 3.4.2.4) is requested."() {
        expect:
        String prefix = uniq("q7_")
        ["a", "b", "c"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }

        Response page = userQuery([filter: "userName sw \"${prefix}\"", count: 2])
        assert page.statusCode() == 200 : "RFC 7644 3.4.2.4: paginated query must return 200 but was ${page.statusCode()}"
        assert page.jsonPath().getInt("totalResults") == 3 : "RFC 7644 3.4.2: totalResults must count all matches (3) but was ${page.jsonPath().get('totalResults')}"
        List resources = page.jsonPath().getList("Resources")
        assert resources != null && resources.size() == 2 : "RFC 7644 3.4.2: with count=2 the Resources may be a subset of the full result set and must hold at most 2 of 3 matches, got ${resources?.size()}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MUST, lines 829-830
    // REQUIRED if "totalResults" is non-zero.
    @SpecRef("RFC7644-3.4.2-7e7ed1")
    def "RFC7644-3.4.2-7e7ed1: Resources: REQUIRED if 'totalResults' is non-zero."() {
        expect:
        String prefix = uniq("q8_")
        assert createUser([userName: prefix + "a"]).statusCode() == 201
        assert createGroup(prefix).statusCode() == 201

        Response users = userQuery([filter: "userName sw \"${prefix}\""])
        assert users.statusCode() == 200
        assert users.jsonPath().getInt("totalResults") > 0 : "precondition: totalResults must be non-zero"
        def userResources = users.jsonPath().get("Resources")
        assert userResources instanceof List && !userResources.isEmpty() : "RFC 7644 3.4.2: Resources is REQUIRED when totalResults is non-zero (/Users) but was ${userResources}"

        Response groups = scimRequest().queryParam("filter", "displayName eq \"${prefix}\"").get("/Groups")
        assert groups.statusCode() == 200
        assert groups.jsonPath().getInt("totalResults") > 0 : "precondition: totalResults must be non-zero"
        def groupResources = groups.jsonPath().get("Resources")
        assert groupResources instanceof List && !groupResources.isEmpty() : "RFC 7644 3.4.2: Resources is REQUIRED when totalResults is non-zero (/Groups) but was ${groupResources}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MUST, lines 833-834
    // REQUIRED when partial results are returned due to pagination.
    @SpecRef("RFC7644-3.4.2-233c4c")
    def "RFC7644-3.4.2-233c4c: startIndex: REQUIRED when partial results are returned due to pagination."() {
        expect:
        String prefix = uniq("q9_")
        ["a", "b", "c"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }
        String filter = "userName sw \"${prefix}\""

        Response first = userQuery([filter: filter, count: 2])
        assert first.statusCode() == 200
        assert first.jsonPath().getInt("totalResults") == 3
        assert first.jsonPath().getList("Resources").size() == 2 : "precondition: partial result expected"
        def startIndex = first.jsonPath().get("startIndex")
        assert startIndex instanceof Integer : "RFC 7644 3.4.2: startIndex is REQUIRED when partial results are returned, but was ${startIndex}"
        assert startIndex == 1 : "RFC 7644 3.4.2: startIndex of the first page must be 1 but was ${startIndex}"

        Response second = userQuery([filter: filter, startIndex: 3, count: 2])
        assert second.statusCode() == 200
        assert second.jsonPath().getList("Resources").size() == 1 : "precondition: partial result expected on last page"
        def secondStart = second.jsonPath().get("startIndex")
        assert secondStart instanceof Integer && secondStart == 3 : "RFC 7644 3.4.2: startIndex must echo the 1-based index of the first result (3) but was ${secondStart}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MUST, lines 837-838
    // REQUIRED when partial results are returned due to pagination.
    @SpecRef("RFC7644-3.4.2-0ae032")
    def "RFC7644-3.4.2-0ae032: itemsPerPage: REQUIRED when partial results are returned due to pagination."() {
        expect:
        String prefix = uniq("q10_")
        ["a", "b", "c"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }
        String filter = "userName sw \"${prefix}\""

        Response first = userQuery([filter: filter, count: 2])
        assert first.statusCode() == 200
        int returned = first.jsonPath().getList("Resources").size()
        assert returned == 2 : "precondition: partial result expected, got ${returned} resources"
        def items = first.jsonPath().get("itemsPerPage")
        assert items instanceof Integer : "RFC 7644 3.4.2: itemsPerPage is REQUIRED when partial results are returned, but was ${items}"
        assert items == returned : "RFC 7644 3.4.2: itemsPerPage must equal the number of resources in the page (${returned}) but was ${items}"

        Response last = userQuery([filter: filter, startIndex: 3, count: 2])
        assert last.statusCode() == 200
        def lastItems = last.jsonPath().get("itemsPerPage")
        assert lastItems instanceof Integer && lastItems == last.jsonPath().getList("Resources").size() : "RFC 7644 3.4.2: itemsPerPage must equal the page size on the last page but was ${lastItems}"
    }

    // RFC 7644 section 3.4.2 (Query Resources), MUST, lines 847-848
    // A query that does not return any matches SHALL return success (HTTP status code 200) with "totalResults" set to a value of 0.
    @SpecRef("RFC7644-3.4.2-eb7ca6")
    def "RFC7644-3.4.2-eb7ca6: A query that does not return any matches SHALL return success (HTTP status code 200) with 'totalResults'..."() {
        expect:
        String nomatch = uniq("q11_nomatch_")
        Response users = userQuery([filter: "userName eq \"${nomatch}\""])
        assert users.statusCode() == 200 : "RFC 7644 3.4.2: a query without matches must return 200 but was ${users.statusCode()}"
        assert users.jsonPath().get("totalResults") == 0 : "RFC 7644 3.4.2: totalResults must be 0 but was ${users.jsonPath().get('totalResults')}"
        def userResources = users.jsonPath().getList("Resources")
        assert userResources == null || userResources.isEmpty() : "RFC 7644 3.4.2: no Resources expected for an empty result"

        Response groups = scimRequest().queryParam("filter", "displayName eq \"${nomatch}\"").get("/Groups")
        assert groups.statusCode() == 200 : "RFC 7644 3.4.2: a /Groups query without matches must return 200 but was ${groups.statusCode()}"
        assert groups.jsonPath().get("totalResults") == 0 : "RFC 7644 3.4.2: /Groups totalResults must be 0 but was ${groups.jsonPath().get('totalResults')}"
    }

    // RFC 7644 section 3.4.2.1 (Query Endpoints), MAY, lines 882-883
    // Queries MAY be performed against a SCIM resource object, a resource type endpoint, or a SCIM server root.
    @SpecRef("RFC7644-3.4.2.1-afa90c")
    def "RFC7644-3.4.2.1-afa90c: Queries MAY be performed against a SCIM resource object, a resource type endpoint, or a SCIM server root."() {
        expect:
        String prefix = uniq("q12_")
        Response user = createUser([userName: prefix])
        assert user.statusCode() == 201
        String userId = user.jsonPath().getString("id")
        Response group = createGroup(prefix)
        assert group.statusCode() == 201
        String groupId = group.jsonPath().getString("id")

        Response resource = scimRequest().get("/Groups/${groupId}")
        assert resource.statusCode() == 200 : "RFC 7644 3.4.2.1: query against a SCIM resource object must return 200 but was ${resource.statusCode()}"
        assert resource.jsonPath().getString("id") == groupId
        Response endpoint = scimRequest().queryParam("filter", "displayName eq \"${prefix}\"").get("/Groups")
        assert endpoint.statusCode() == 200 : "RFC 7644 3.4.2.1: query against a resource type endpoint must return 200 but was ${endpoint.statusCode()}"
        assert resourceIds(endpoint) == [groupId] : "RFC 7644 3.4.2.1: /Groups query must return [${groupId}] but returned ${resourceIds(endpoint)}"

        Response root = scimRequest().queryParam("filter", "userName eq \"${prefix}\"").get("/")
        assert root.statusCode() in [200, 400, 403, 404, 405, 501] : "RFC 7644 3.4.2.1: query against the server root must return 200 or be unsupported, but was ${root.statusCode()}"
        if (root.statusCode() == 200) {
            assert resourceIds(root) == [userId] : "RFC 7644 3.4.2.1: server root query must return the matching user [${userId}] but returned ${resourceIds(root)}"
        }
    }

    // RFC 7644 section 3.4.2.1 (Query Endpoints), MUST, lines 903-904
    // A query against a server root indicates that all resources within the server SHALL be included, subject to filtering.
    @SpecRef("RFC7644-3.4.2.1-fbf07d")
    def "RFC7644-3.4.2.1-fbf07d: A query against a server root indicates that all resources within the server SHALL be included, subject to..."() {
        expect:
        String prefix = uniq("q13_")
        Response user = createUser([userName: prefix])
        assert user.statusCode() == 201
        String userId = user.jsonPath().getString("id")
        Response group = createGroup(prefix)
        assert group.statusCode() == 201
        String groupId = group.jsonPath().getString("id")

        Response root = scimRequest().queryParam("filter", "userName eq \"${prefix}\" or displayName eq \"${prefix}\"").get("/")
        if (root.statusCode() in [400, 403, 404, 405, 501]) {
            throw new TestAbortedException("Server does not offer queries against the server root (HTTP ${root.statusCode()}); cannot check root semantics")
        }
        assert root.statusCode() == 200 : "RFC 7644 3.4.2.1: server root query must return 200 but was ${root.statusCode()}"
        List ids = resourceIds(root)
        assert ids.contains(userId) : "RFC 7644 3.4.2.1: a server root query includes all resources (User ${userId} missing from ${ids})"
        assert ids.contains(groupId) : "RFC 7644 3.4.2.1: a server root query includes all resources (Group ${groupId} missing from ${ids})"

        Response onlyUser = scimRequest().queryParam("filter", "userName eq \"${prefix}\"").get("/")
        assert onlyUser.statusCode() == 200
        assert resourceIds(onlyUser) == [userId] : "RFC 7644 3.4.2.1: the filter must restrict the root query to [${userId}] but got ${resourceIds(onlyUser)}"
    }

    // RFC 7644 section 3.4.2.1 (Query Endpoints), MAY, lines 904-906
    // A filter expression using "meta.resourceType" MAY be used to restrict results to one or more specific resource types (to exclude others).
    @SpecRef("RFC7644-3.4.2.1-b1acaf")
    def "RFC7644-3.4.2.1-b1acaf: A filter expression using 'meta.resourceType' MAY be used to restrict results to one or more specific..."() {
        expect:
        String prefix = uniq("q14_")
        Response user = createUser([userName: prefix])
        assert user.statusCode() == 201
        String userId = user.jsonPath().getString("id")
        Response group = createGroup(prefix)
        assert group.statusCode() == 201
        String groupId = group.jsonPath().getString("id")
        String own = "(userName eq \"${prefix}\" or displayName eq \"${prefix}\")"

        Response onlyGroups = scimRequest().queryParam("filter", "${own} and meta.resourceType eq \"Group\"").get("/")
        assert onlyGroups.statusCode() in [200, 400, 403, 404, 405, 501]
        if (onlyGroups.statusCode() == 200) {
            assert !resourceIds(onlyGroups).contains(userId) : "RFC 7644 3.4.2.1: meta.resourceType eq Group must exclude the user ${userId} but got ${resourceIds(onlyGroups)}"

            Response onlyUsers = scimRequest().queryParam("filter", "${own} and meta.resourceType eq \"User\"").get("/")
            assert onlyUsers.statusCode() == 200
            assert resourceIds(onlyUsers).contains(userId) : "RFC 7644 3.4.2.1: meta.resourceType eq User must return the user ${userId} but got ${resourceIds(onlyUsers)}"
            assert !resourceIds(onlyUsers).contains(groupId) : "RFC 7644 3.4.2.1: meta.resourceType eq User must exclude the group ${groupId} but got ${resourceIds(onlyUsers)}"

            Response both = scimRequest().queryParam("filter", "${own} and (meta.resourceType eq \"User\" or meta.resourceType eq \"Group\")").get("/")
            assert both.statusCode() == 200
            assert resourceIds(both).contains(userId) : "RFC 7644 3.4.2.1: (User or Group) resourceType filter must return the user ${userId}; got ${resourceIds(both)}"
        } else {
            Response endpointUsers = userQuery([filter: "userName eq \"${prefix}\" and meta.resourceType eq \"User\""])
            assert endpointUsers.statusCode() in [200, 400]
            if (endpointUsers.statusCode() == 200) {
                assert resourceIds(endpointUsers) == [userId]
            }
        }
    }

    // RFC 7644 section 3.4.2.1 (Query Endpoints), MUST, lines 916-918
    // When processing query operations using endpoints that include more than one SCIM resource type (e.g., a query from the server root endpoint), filters MUST be processed as outlined in Section 3.4.2.2.
    @SpecRef("RFC7644-3.4.2.1-c83bbf")
    def "RFC7644-3.4.2.1-c83bbf: When processing query operations using endpoints that include more than one SCIM resource type (e.g., a..."() {
        expect:
        String prefix = uniq("q16_")
        Response a = createUser([userName: prefix + "a"])
        Response c = createUser([userName: prefix + "c"])
        assert a.statusCode() == 201 && c.statusCode() == 201
        String idA = a.jsonPath().getString("id")
        String idC = c.jsonPath().getString("id")

        Response probe = scimRequest().queryParam("filter", "userName eq \"${prefix}a\"").get("/")
        if (probe.statusCode() in [400, 403, 404, 405, 501]) {
            throw new TestAbortedException("Server does not offer queries against the server root (HTTP ${probe.statusCode()})")
        }

        Response eq = scimRequest().queryParam("filter", "userName eq \"${prefix}a\"").get("/")
        assert eq.statusCode() == 200 && resourceIds(eq) == [idA] : "RFC 7644 3.4.2.2 via root: 'eq' must match only ${idA} but got ${resourceIds(eq)} (HTTP ${eq.statusCode()})"

        Response sw = scimRequest().queryParam("filter", "userName sw \"${prefix}\"").get("/")
        assert sw.statusCode() == 200 && resourceIds(sw).toSet() == [idA, idC].toSet() : "RFC 7644 3.4.2.2 via root: 'sw' must match both users but got ${resourceIds(sw)} (HTTP ${sw.statusCode()})"

        Response and = scimRequest().queryParam("filter", "userName sw \"${prefix}\" and userName ew \"c\"").get("/")
        assert and.statusCode() == 200 && resourceIds(and) == [idC] : "RFC 7644 3.4.2.2 via root: 'and' must match only ${idC} but got ${resourceIds(and)} (HTTP ${and.statusCode()})"

        Response not = scimRequest().queryParam("filter", "userName sw \"${prefix}\" and not (userName eq \"${prefix}a\")").get("/")
        assert not.statusCode() == 200 && resourceIds(not) == [idC] : "RFC 7644 3.4.2.2 via root: 'not' must match only ${idC} but got ${resourceIds(not)} (HTTP ${not.statusCode()})"
    }

    // RFC 7644 section 3.4.2.1 (Query Endpoints), MUST, lines 919-921
    // For filtered attributes that are not part of a particular resource type, the service provider SHALL treat the attribute as if there is no attribute value.
    @SpecRef("RFC7644-3.4.2.1-4abd06")
    def "RFC7644-3.4.2.1-4abd06: For filtered attributes that are not part of a particular resource type, the service provider SHALL treat..."() {
        expect:
        String prefix = uniq("q17_")
        Response user = createUser([userName: prefix])
        assert user.statusCode() == 201
        String userId = user.jsonPath().getString("id")

        Response probe = scimRequest().queryParam("filter", "userName eq \"${prefix}\"").get("/")
        if (probe.statusCode() in [400, 403, 404, 405, 501]) {
            throw new TestAbortedException("Server does not offer queries against the server root (HTTP ${probe.statusCode()})")
        }
        assert resourceIds(probe) == [userId] : "precondition: root query must find the user, got ${resourceIds(probe)}"

        // members is an attribute of Group only: for a User it must behave as if there is no value
        Response presence = scimRequest().queryParam("filter", "userName eq \"${prefix}\" and members pr").get("/")
        assert presence.statusCode() == 200 : "RFC 7644 3.4.2.1: filtering on an attribute of another resource type must not fail, got HTTP ${presence.statusCode()}"
        assert resourceIds(presence).isEmpty() : "RFC 7644 3.4.2.1: 'members pr' must be false for a User, but got ${resourceIds(presence)}"

        Response equality = scimRequest().queryParam("filter", "userName eq \"${prefix}\" and members eq \"${UUID.randomUUID()}\"").get("/")
        assert equality.statusCode() == 200 : "RFC 7644 3.4.2.1: equality on an undefined attribute must not fail, got HTTP ${equality.statusCode()}"
        assert resourceIds(equality).isEmpty() : "RFC 7644 3.4.2.1: equality on an undefined attribute must be false, but got ${resourceIds(equality)}"

        Response notPresent = scimRequest().queryParam("filter", "userName eq \"${prefix}\" and not (members pr)").get("/")
        assert notPresent.statusCode() == 200
        assert resourceIds(notPresent) == [userId] : "RFC 7644 3.4.2.1: a User has no members value, so 'not (members pr)' must be true; got ${resourceIds(notPresent)}"

        Response either = scimRequest().queryParam("filter", "members eq \"${UUID.randomUUID()}\" or userName eq \"${prefix}\"").get("/")
        assert either.statusCode() == 200
        assert resourceIds(either) == [userId] : "RFC 7644 3.4.2.1: the undefined-attribute branch is false, the other branch of 'or' must still match; got ${resourceIds(either)}"
    }

    // RFC 7644 section 3.4.2.5 (Attributes), MUST, lines 1436-1437
    // The following attributes control which attributes SHALL be returned with a returned resource.
    @SpecRef("RFC7644-3.4.2.5-bf9c92")
    def "RFC7644-3.4.2.5-bf9c92: The following attributes control which attributes SHALL be returned with a returned resource."() {
        expect:
        Map u = createNamedUser(uniq("q18_"))
        String filter = "userName eq \"${u.userName}\""

        Response selected = userQuery([filter: filter, attributes: "userName,emails"])
        assert selected.statusCode() == 200 : "RFC 7644 3.4.2.5: attributes parameter must be accepted, got ${selected.statusCode()}"
        Map r = firstResource(selected)
        assert r.id == u.id : "RFC 7644 3.4.2.5: id is always returned"
        assert r.userName == u.userName : "RFC 7644 3.4.2.5: requested attribute userName must be returned"
        assert r.emails : "RFC 7644 3.4.2.5: requested attribute emails must be returned"
        assert !r.containsKey("name") && !r.containsKey("displayName") : "RFC 7644 3.4.2.5: attributes override the default set, name/displayName must not be returned but got ${r.keySet()}"

        Response excluded = userQuery([filter: filter, excludedAttributes: "emails,name"])
        assert excluded.statusCode() == 200 : "RFC 7644 3.4.2.5: excludedAttributes parameter must be accepted, got ${excluded.statusCode()}"
        Map e = firstResource(excluded)
        assert e.userName == u.userName && e.displayName : "RFC 7644 3.4.2.5: default attributes that are not excluded must still be returned"
        assert !e.containsKey("emails") && !e.containsKey("name") : "RFC 7644 3.4.2.5: excluded attributes must be removed but got ${e.keySet()}"
    }

    // RFC 7644 section 3.4.2.5 (Attributes), MUST, lines 1437-1439
    // which MUST be supported by SCIM service providers:
    @SpecRef("RFC7644-3.4.2.5-cf4476")
    def "RFC7644-3.4.2.5-cf4476: which MUST be supported by SCIM service providers:"() {
        expect:
        Map u = createNamedUser(uniq("q20_"))
        Response grp = createGroup(uniq("q20g_"), [u.id])
        assert grp.statusCode() == 201
        String groupId = grp.jsonPath().getString("id")
        String groupName = grp.jsonPath().getString("displayName")

        Response uAttrs = scimRequest().queryParam("attributes", "userName").get("/Users/${u.id}")
        assert uAttrs.statusCode() == 200 : "RFC 7644 3.4.2.5: 'attributes' must be supported on GET /Users/{id}, got ${uAttrs.statusCode()}"
        Map ua = uAttrs.jsonPath().getMap("")
        assert ua.userName == u.userName && ua.id == u.id
        assert !ua.containsKey("emails") && !ua.containsKey("name") : "RFC 7644 3.4.2.5: 'attributes=userName' must limit returned attributes, got ${ua.keySet()}"

        Response uExcl = scimRequest().queryParam("excludedAttributes", "emails").get("/Users/${u.id}")
        assert uExcl.statusCode() == 200 : "RFC 7644 3.4.2.5: 'excludedAttributes' must be supported on GET /Users/{id}, got ${uExcl.statusCode()}"
        Map ue = uExcl.jsonPath().getMap("")
        assert !ue.containsKey("emails") && ue.userName == u.userName : "RFC 7644 3.4.2.5: 'excludedAttributes=emails' must remove emails only, got ${ue.keySet()}"

        Response gAttrs = scimRequest().queryParam("filter", "displayName eq \"${groupName}\"").queryParam("attributes", "displayName").get("/Groups")
        assert gAttrs.statusCode() == 200
        Map ga = firstResource(gAttrs)
        assert ga.id == groupId && ga.displayName == groupName
        assert !ga.containsKey("members") : "RFC 7644 3.4.2.5: 'attributes=displayName' on /Groups must omit members, got ${ga.keySet()}"

        Response gExcl = scimRequest().queryParam("filter", "displayName eq \"${groupName}\"").queryParam("excludedAttributes", "members").get("/Groups")
        assert gExcl.statusCode() == 200
        Map ge = firstResource(gExcl)
        assert ge.id == groupId && ge.displayName == groupName && !ge.containsKey("members") : "RFC 7644 3.4.2.5: 'excludedAttributes=members' on /Groups must remove members, got ${ge.keySet()}"
    }

    // RFC 7644 section 3.4.2.5 (Attributes), MUST, lines 1443-1444
    // Attribute names MUST be in standard attribute notation (Section 3.10) form.
    @SpecRef("RFC7644-3.4.2.5-058744")
    def "RFC7644-3.4.2.5-058744: attributes: Attribute names MUST be in standard attribute notation (Section 3.10) form."() {
        expect:
        Map u = createNamedUser(uniq("q21_"))
        Response r = userQuery([filter: "userName eq \"${u.userName}\"", attributes: "name.givenName"])
        assert r.statusCode() == 200 : "RFC 7644 3.4.2.5: attributes in standard attribute notation (sub-attribute) must be accepted, got ${r.statusCode()}"
        Map res = firstResource(r)
        assert res.id == u.id
        assert res.name instanceof Map && res.name.givenName == "Given" : "RFC 7644 3.10: attributes=name.givenName must return name.givenName but got ${res.name}"
        assert !res.name.containsKey("familyName") : "RFC 7644 3.10: attributes=name.givenName must not return name.familyName but got ${res.name}"
        assert !res.containsKey("emails") : "attributes=name.givenName must override the default set, but emails was returned"
    }

    // RFC 7644 section 3.4.2.5 (Attributes), MUST, lines 1449-1451
    // This parameter SHALL have no effect on attributes whose schema "returned" setting is "always" (see Sections 2.2 and 7 of [RFC7643]).
    @SpecRef("RFC7644-3.4.2.5-13dfb5")
    def "RFC7644-3.4.2.5-13dfb5: excludedAttributes: This parameter SHALL have no effect on attributes whose schema 'returned' setting is..."() {
        expect:
        Map u = createNamedUser(uniq("q22_"))
        Response schema = scimRequest().get("/Schemas/${USER_SCHEMA}")
        assert schema.statusCode() == 200 : "GET /Schemas/${USER_SCHEMA} must return 200 but was ${schema.statusCode()}"
        List<String> always = schema.jsonPath().getList("attributes").findAll { it.returned == "always" }.collect { it.name as String }
        assert !always.isEmpty() : "RFC 7643 7: the User schema must define at least one attribute with returned=always (id)"

        Response r = userQuery([filter: "userName eq \"${u.userName}\"", excludedAttributes: (always + ["userName"]).join(",")])
        assert r.statusCode() == 200 : "RFC 7644 3.4.2.5: excludedAttributes must be accepted, got ${r.statusCode()}"
        Map res = firstResource(r)
        always.each { name ->
            assert res.containsKey(name) : "RFC 7644 3.4.2.5: excludedAttributes must have no effect on the returned=always attribute '${name}', got ${res.keySet()}"
        }
        assert !res.containsKey("userName") : "excludedAttributes=userName must remove the default attribute userName (proves excludedAttributes is applied), got ${res.keySet()}"
    }

    // RFC 7644 section 3.4.2.5 (Attributes), MUST, lines 1451-1452
    // Attribute names MUST be in standard attribute notation (Section 3.10) form.
    @SpecRef("RFC7644-3.4.2.5-358d12")
    def "RFC7644-3.4.2.5-358d12: excludedAttributes: Attribute names MUST be in standard attribute notation (Section 3.10) form."() {
        expect:
        Map u = createNamedUser(uniq("q23_"))
        Response r = userQuery([filter: "userName eq \"${u.userName}\"", excludedAttributes: "name.givenName"])
        assert r.statusCode() == 200 : "RFC 7644 3.4.2.5: excludedAttributes in standard attribute notation must be accepted, got ${r.statusCode()}"
        Map res = firstResource(r)
        assert res.id == u.id
        assert res.name instanceof Map && res.name.familyName == "Family" : "RFC 7644 3.10: excludedAttributes=name.givenName must keep name.familyName but got ${res.name}"
        assert !res.name.containsKey("givenName") : "RFC 7644 3.10: excludedAttributes=name.givenName must remove name.givenName but got ${res.name}"
        assert res.userName == u.userName
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1465-1466
    // Clients MAY execute queries without passing parameters on the URL by using the HTTP POST verb combined with the "/.search" path extension.
    @SpecRef("RFC7644-3.4.3-af15f2")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-af15f2: Clients MAY execute queries without passing parameters on the URL by using the HTTP POST verb combined..."() {
        expect:
        Response user = createUser([userName: uniq("s1_")])
        assert user.statusCode() == 201
        String id = user.jsonPath().getString("id")
        String userName = user.jsonPath().getString("userName")

        Response r = search("/Users/.search", [filter: "userName eq \"${userName}\""])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: POST /Users/.search must return 200 but was ${r.statusCode()}"
        assert r.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA) : "RFC 7644 3.4.3: response must be a ListResponse"
        assert resourceIds(r) == [id] : "RFC 7644 3.4.3: search must return [${id}] but returned ${resourceIds(r)}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MUST, lines 1467-1469
    // The inclusion of "/.search" on the end of a valid SCIM endpoint SHALL be used to indicate that the HTTP POST verb is intended to be a query operation.
    @SpecRef("RFC7644-3.4.3-170248")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-170248: The inclusion of '/.search' on the end of a valid SCIM endpoint SHALL be used to indicate that the HTTP..."() {
        expect:
        String prefix = uniq("s2_")
        Response user = createUser([userName: prefix])
        assert user.statusCode() == 201
        String id = user.jsonPath().getString("id")

        Response r = search("/Users/.search", [filter: "userName eq \"${prefix}\""])
        assert r.statusCode() != 201 : "RFC 7644 3.4.3: POST to /.search is a query operation and must not create a resource (got 201)"
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: POST /Users/.search must be treated as a query and return 200 but was ${r.statusCode()}"
        assert r.jsonPath().getList("schemas") == [LIST_RESPONSE_SCHEMA] : "RFC 7644 3.4.3: /.search must answer with a ListResponse, not a User resource, got ${r.jsonPath().getList('schemas')}"
        assert r.jsonPath().getInt("totalResults") == 1 && resourceIds(r) == [id]

        Response after = userQuery([filter: "userName sw \"${prefix}\""])
        assert after.jsonPath().getInt("totalResults") == 1 : "RFC 7644 3.4.3: the /.search POST must not have created any resource"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1473-1474
    // The body of the POST request MAY include any of the parameters defined in Section 3.4.2.
    @SpecRef("RFC7644-3.4.3-002106")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-002106: The body of the POST request MAY include any of the parameters defined in Section 3.4.2."() {
        expect:
        String prefix = uniq("s3_")
        ["a", "b", "c"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }

        Response r = search("/Users/.search", [
            filter: "userName sw \"${prefix}\"", sortBy: "userName", sortOrder: "descending",
            startIndex: 1, count: 2, attributes: ["userName"], excludedAttributes: null
        ].findAll { it.value != null })
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: a body with filter, sortBy, sortOrder, startIndex, count and attributes must be accepted, got ${r.statusCode()}"
        assert r.jsonPath().getInt("totalResults") == 3 : "RFC 7644 3.4.3: filter in the body must be applied, totalResults ${r.jsonPath().get('totalResults')}"
        assert r.jsonPath().getList("Resources").size() == 2 : "RFC 7644 3.4.3: count in the body must limit the page to 2"
        assert r.jsonPath().getInt("startIndex") == 1
        Map first = firstResource(r)
        assert first.userName && !first.containsKey("emails") : "RFC 7644 3.4.3: attributes in the body must be applied, got ${first.keySet()}"
        if (isSortSupported()) {
            assert r.jsonPath().getList("Resources.userName") == [prefix + "c", prefix + "b"] : "RFC 7644 3.4.3: sortBy/sortOrder in the body must be applied, got ${r.jsonPath().getList('Resources.userName')}"
        }
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MUST, lines 1482-1483
    // Attribute names MUST be in standard attribute notation (Section 3.10) form.
    @SpecRef("RFC7644-3.4.3-a4f3d6")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-a4f3d6: attributes: Attribute names MUST be in standard attribute notation (Section 3.10) form."() {
        expect:
        Map u = createNamedUser(uniq("s5_"))
        Response r = search("/Users/.search", [filter: "userName eq \"${u.userName}\"", attributes: ["name.givenName"]])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: attributes in standard attribute notation must be accepted, got ${r.statusCode()}"
        Map res = firstResource(r)
        assert res.id == u.id
        assert res.name instanceof Map && res.name.givenName == "Given" : "RFC 7644 3.10: attributes [name.givenName] must return name.givenName but got ${res.name}"
        assert !res.name.containsKey("familyName") : "RFC 7644 3.10: attributes [name.givenName] must not return name.familyName but got ${res.name}"
        assert !res.containsKey("emails") : "attributes [name.givenName] must override the default set, emails was returned"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1484-1484
    // OPTIONAL.
    @SpecRef("RFC7644-3.4.3-ff19d0")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-ff19d0: attributes: OPTIONAL."() {
        expect:
        Map u = createNamedUser(uniq("s6_"))
        Response r = search("/Users/.search", [filter: "userName eq \"${u.userName}\""])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: attributes is OPTIONAL, a request without it must return 200 but was ${r.statusCode()}"
        Map res = firstResource(r)
        assert res.id == u.id
        assert res.userName == u.userName && res.emails && res.name instanceof Map : "RFC 7644 3.4.2.5: without attributes the default attribute set must be returned, got ${res.keySet()}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MUST, lines 1488-1490
    // This parameter SHALL have no effect on attributes whose schema "returned" setting is "always" (see Sections 2.2 and 7 of [RFC7643]).
    @SpecRef("RFC7644-3.4.3-4b3e55")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-4b3e55: excludedAttributes: This parameter SHALL have no effect on attributes whose schema 'returned' setting is..."() {
        expect:
        Map u = createNamedUser(uniq("s7_"))
        Response schema = scimRequest().get("/Schemas/${USER_SCHEMA}")
        assert schema.statusCode() == 200
        List<String> always = schema.jsonPath().getList("attributes").findAll { it.returned == "always" }.collect { it.name as String }
        assert !always.isEmpty() : "RFC 7643 7: the User schema must define at least one attribute with returned=always (id)"

        Response r = search("/Users/.search", [filter: "userName eq \"${u.userName}\"", excludedAttributes: always + ["userName"]])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: excludedAttributes must be accepted, got ${r.statusCode()}"
        Map res = firstResource(r)
        always.each { name ->
            assert res.containsKey(name) : "RFC 7644 3.4.3: excludedAttributes must have no effect on the returned=always attribute '${name}', got ${res.keySet()}"
        }
        assert !res.containsKey("userName") : "excludedAttributes [userName] must remove userName (proves excludedAttributes is applied), got ${res.keySet()}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MUST, lines 1490-1491
    // Attribute names MUST be in standard attribute notation (Section 3.10) form.
    @SpecRef("RFC7644-3.4.3-93bf33")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-93bf33: excludedAttributes: Attribute names MUST be in standard attribute notation (Section 3.10) form."() {
        expect:
        Map u = createNamedUser(uniq("s8_"))
        Response r = search("/Users/.search", [filter: "userName eq \"${u.userName}\"", excludedAttributes: ["name.givenName"]])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: excludedAttributes in standard attribute notation must be accepted, got ${r.statusCode()}"
        Map res = firstResource(r)
        assert res.id == u.id
        assert res.name instanceof Map && res.name.familyName == "Family" : "RFC 7644 3.10: excludedAttributes [name.givenName] must keep name.familyName but got ${res.name}"
        assert !res.name.containsKey("givenName") : "RFC 7644 3.10: excludedAttributes [name.givenName] must remove name.givenName but got ${res.name}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1492-1492
    // OPTIONAL.
    @SpecRef("RFC7644-3.4.3-14f827")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-14f827: excludedAttributes: OPTIONAL."() {
        expect:
        Map u = createNamedUser(uniq("s9_"))
        Response r = search("/Users/.search", [filter: "userName eq \"${u.userName}\""])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: excludedAttributes is OPTIONAL, a request without it must return 200 but was ${r.statusCode()}"
        Map res = firstResource(r)
        assert res.id == u.id
        assert res.userName && res.emails && res.name && res.displayName : "RFC 7644 3.4.2.5: without excludedAttributes the default attribute set must be returned, got ${res.keySet()}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MUST, lines 1494-1495
    // The filter string MUST be a valid filter (Section 3.4.2.2) expression.
    @SpecRef("RFC7644-3.4.3-25d03c")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-25d03c: filter: The filter string MUST be a valid filter (Section 3.4.2.2) expression."() {
        expect:
        Response valid = search("/Users/.search", [filter: "userName eq \"${uniq('s10_')}\""])
        assert valid.statusCode() == 200 : "RFC 7644 3.4.3: a valid filter must be accepted, got ${valid.statusCode()}"

        Response invalid = search("/Users/.search", [filter: "userName bogusOperator \"x\""])
        assert invalid.statusCode() == 400 : "RFC 7644 3.4.3/3.4.2.2: an invalid filter expression must be rejected with 400 but was ${invalid.statusCode()}"
        assert invalid.jsonPath().getString("scimType") == "invalidFilter" : "RFC 7644 3.12: scimType must be invalidFilter but was '${invalid.jsonPath().getString('scimType')}'"

        Response unbalanced = search("/Users/.search", [filter: "(userName eq \"x\""])
        assert unbalanced.statusCode() == 400 : "RFC 7644 3.4.3: an unbalanced filter expression must be rejected with 400 but was ${unbalanced.statusCode()}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1496-1496
    // OPTIONAL.
    @SpecRef("RFC7644-3.4.3-8cda5b")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-8cda5b: filter: OPTIONAL."() {
        expect:
        String prefix = uniq("s11_")
        ["a", "b"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }

        Response r = search("/Users/.search", [count: 1])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: filter is OPTIONAL, a request without it must return 200 but was ${r.statusCode()}"
        assert r.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
        assert r.jsonPath().getInt("totalResults") >= 2 : "RFC 7644 3.4.3: without a filter all resources are returned, totalResults must be at least 2 but was ${r.jsonPath().get('totalResults')}"
        assert r.jsonPath().getList("Resources").size() == 1 : "count=1 must limit the page to one resource"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MUST, lines 1498-1499
    // A string indicating the attribute whose value SHALL be used to order the returned responses.
    @SpecRef("RFC7644-3.4.3-5e40ee")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-5e40ee: sortBy: A string indicating the attribute whose value SHALL be used to order the returned responses."() {
        expect:
        requireCapability("sort")
        String prefix = uniq("s12_")
        ["c", "a", "b"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }
        String filter = "userName sw \"${prefix}\""

        Response asc = search("/Users/.search", [filter: filter, sortBy: "userName", sortOrder: "ascending"])
        assert asc.statusCode() == 200 : "RFC 7644 3.4.3: sortBy must be accepted, got ${asc.statusCode()}"
        assert asc.jsonPath().getList("Resources.userName") == [prefix + "a", prefix + "b", prefix + "c"] : "RFC 7644 3.4.3: sortBy userName ascending must order the results, got ${asc.jsonPath().getList('Resources.userName')}"

        Response desc = search("/Users/.search", [filter: filter, sortBy: "userName", sortOrder: "descending"])
        assert desc.statusCode() == 200
        assert desc.jsonPath().getList("Resources.userName") == [prefix + "c", prefix + "b", prefix + "a"] : "RFC 7644 3.4.3: sortBy userName descending must order the results, got ${desc.jsonPath().getList('Resources.userName')}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MUST, lines 1499-1500
    // The "sortBy" attribute MUST be in standard attribute notation (Section 3.10) form.
    @SpecRef("RFC7644-3.4.3-7cbb09")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-7cbb09: sortBy: The 'sortBy' attribute MUST be in standard attribute notation (Section 3.10) form."() {
        expect:
        requireCapability("sort")
        String prefix = uniq("s13_")
        Map familyNames = [a: "Zulu", b: "Alpha", c: "Mike"]
        familyNames.each { k, v ->
            assert createUser([userName: prefix + k, name: [givenName: "G", familyName: v]]).statusCode() == 201
        }
        Response r = search("/Users/.search", [filter: "userName sw \"${prefix}\"", sortBy: "name.familyName", sortOrder: "ascending"])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: sortBy in standard attribute notation (name.familyName) must be accepted, got ${r.statusCode()}"
        assert r.jsonPath().getList("Resources.name.familyName") == ["Alpha", "Mike", "Zulu"] : "RFC 7644 3.4.3/3.10: sortBy name.familyName must order the results, got ${r.jsonPath().getList('Resources.name.familyName')}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1501-1501
    // OPTIONAL.
    @SpecRef("RFC7644-3.4.3-190de5")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-190de5: sortBy: OPTIONAL."() {
        expect:
        String prefix = uniq("s14_")
        ["a", "b", "c"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }
        Response r = search("/Users/.search", [filter: "userName sw \"${prefix}\""])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: sortBy is OPTIONAL, a request without it must return 200 but was ${r.statusCode()}"
        assert r.jsonPath().getInt("totalResults") == 3
        assert r.jsonPath().getList("Resources.userName").toSet() == [prefix + "a", prefix + "b", prefix + "c"].toSet() : "RFC 7644 3.4.3: all matching users must be returned without sortBy, got ${r.jsonPath().getList('Resources.userName')}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1505-1505
    // OPTIONAL.
    @SpecRef("RFC7644-3.4.3-73ff3d")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-73ff3d: sortOrder: OPTIONAL."() {
        expect:
        requireCapability("sort")
        String prefix = uniq("s15_")
        ["c", "a", "b"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }
        Response r = search("/Users/.search", [filter: "userName sw \"${prefix}\"", sortBy: "userName"])
        assert r.statusCode() == 200 : "RFC 7644 3.4.3: sortOrder is OPTIONAL, sortBy alone must return 200 but was ${r.statusCode()}"
        assert r.jsonPath().getList("Resources.userName") == [prefix + "a", prefix + "b", prefix + "c"] : "RFC 7644 3.4.2.3: sortOrder defaults to ascending, got ${r.jsonPath().getList('Resources.userName')}"
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1520-1520
    // OPTIONAL.
    @SpecRef("RFC7644-3.4.3-69ead0")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-69ead0: startIndex: OPTIONAL."() {
        expect:
        String prefix = uniq("s16_")
        ["a", "b", "c"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }
        String filter = "userName sw \"${prefix}\""

        Response omitted = search("/Users/.search", [filter: filter, count: 3])
        assert omitted.statusCode() == 200 : "RFC 7644 3.4.3: startIndex is OPTIONAL, a request without it must return 200 but was ${omitted.statusCode()}"
        assert omitted.jsonPath().get("startIndex") == 1 : "RFC 7644 3.4.2.4: startIndex defaults to 1 but response had ${omitted.jsonPath().get('startIndex')}"
        Response explicit = search("/Users/.search", [filter: filter, count: 3, startIndex: 1])
        assert resourceIds(omitted) == resourceIds(explicit) : "RFC 7644 3.4.2.4: omitting startIndex must equal startIndex=1"
        assert resourceIds(omitted).size() == 3
    }

    // RFC 7644 section 3.4.3 (Querying Resources Using HTTP POST), MAY, lines 1523-1523
    // OPTIONAL.
    @SpecRef("RFC7644-3.4.3-f16b2a")
    @RequiresCapability("search")
    def "RFC7644-3.4.3-f16b2a: count: OPTIONAL."() {
        expect:
        String prefix = uniq("s17_")
        ["a", "b", "c"].each { assert createUser([userName: prefix + it]).statusCode() == 201 }
        String filter = "userName sw \"${prefix}\""

        Response omitted = search("/Users/.search", [filter: filter])
        assert omitted.statusCode() == 200 : "RFC 7644 3.4.3: count is OPTIONAL, a request without it must return 200 but was ${omitted.statusCode()}"
        assert omitted.jsonPath().getInt("totalResults") == 3
        assert resourceIds(omitted).size() == 3 : "RFC 7644 3.4.2.4: without count the server's default page must hold the 3 matches, got ${resourceIds(omitted).size()}"

        Response limited = search("/Users/.search", [filter: filter, count: 1])
        assert limited.statusCode() == 200
        assert resourceIds(limited).size() == 1 : "RFC 7644 3.4.2.4: count=1 must return at most 1 resource, got ${resourceIds(limited).size()}"
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static String uniq(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
    }

    /** GET /Users with the given query parameters (values are stringified). */
    private Response userQuery(Map params) {
        def req = scimRequest()
        params.each { k, v -> req = req.queryParam(k.toString(), v.toString()) }
        return req.get("/Users")
    }

    /** POST a SearchRequest body (schemas added) to the given .search path. */
    private Response search(String path, Map body) {
        Map payload = [schemas: [SEARCH_REQUEST_SCHEMA]] + body
        return scimRequest().body(JsonOutput.toJson(payload)).post(path)
    }

    private static List<String> resourceIds(Response response) {
        List resources = response.jsonPath().getList("Resources")
        return resources == null ? [] : resources.collect { it.id as String }
    }

    private static Map firstResource(Response response) {
        List resources = response.jsonPath().getList("Resources")
        assert resources != null && !resources.isEmpty() : "expected at least one resource in the list response but got ${response.body().asString().take(300)}"
        return resources[0] as Map
    }

    /** Creates a user with name, displayName and email so projections have something to select. */
    private Map createNamedUser(String userName) {
        Response r = createUser([
            userName   : userName,
            displayName: "Display ${userName}",
            name       : [givenName: "Given", familyName: "Family"],
            emails     : [[value: "${userName}@test.com", type: "work", primary: true]]
        ])
        assert r.statusCode() == 201 : "RFC 7644 3.3: POST /Users must return 201 but was ${r.statusCode()}: ${r.body().asString().take(200)}"
        return [id: r.jsonPath().getString("id"), userName: userName]
    }
}
