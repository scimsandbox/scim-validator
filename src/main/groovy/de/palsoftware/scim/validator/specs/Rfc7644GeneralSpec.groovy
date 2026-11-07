package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.SpecRef
import org.opentest4j.TestAbortedException
import groovy.json.JsonOutput

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "General"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644GeneralSpec extends ScimBaseSpec {

    // RFC 7644 section 1.2 (Notational Conventions), MUST, lines 186-187
    // Implementers MUST percent-encode URLs as described in Section 2.1 of [RFC3986].
    @SpecRef("RFC7644-1.2-258cd0")
    def "RFC7644-1.2-258cd0: Implementers MUST percent-encode URLs as described in Section 2.1 of [RFC3986]."() {
        given: "a user whose userName contains characters that must be percent-encoded in a URL"
        String marker = UUID.randomUUID().toString().substring(0, 8)
        String userName = "pct ${marker} a&b+c=d@test.com"
        def created = createUser(userName: userName)
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String filter = "userName eq \"${userName}\""
        // percent-encode per RFC 3986 section 2.1 (space as %20, not '+')
        String encoded = URLEncoder.encode(filter, "UTF-8").replace("+", "%20")

        when:
        def response = scimRequestQuiet().urlEncodingEnabled(false).get("/Users?filter=${encoded}")

        then:
        assert response.statusCode() == 200 : "RFC 7644 1.2: percent-encoded filter URL must be accepted (200) but was ${response.statusCode()}"
        assert response.jsonPath().getInt("totalResults") == 1 : "RFC 7644 1.2: percent-encoded filter must be decoded to match exactly the created user"
        assert response.jsonPath().getString("Resources[0].id") == id
        assert response.jsonPath().getString("Resources[0].userName") == userName
    }

    // RFC 7644 section 1.3 (Definitions), MAY, lines 203-205
    // as clients MAY append additional path information and query parameters as part of forming the request.
    @SpecRef("RFC7644-1.3-4e1682")
    def "RFC7644-1.3-4e1682: as clients MAY append additional path information and query parameters as part of forming the request."() {
        given:
        def created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when:
        def byPath = scimRequestQuiet().get("/Users/${id}")
        def withQuery = scimRequestQuiet().queryParam("attributes", "userName").get("/Users/${id}")
        def listWithQuery = scimRequestQuiet().queryParam("filter", "id eq \"${id}\"").queryParam("count", 1).get("/Users")

        then:
        assert byPath.statusCode() == 200 : "RFC 7644 1.3: base URI + path must work but was ${byPath.statusCode()}"
        assert withQuery.statusCode() == 200 : "RFC 7644 1.3: appended query parameters must be accepted but status was ${withQuery.statusCode()}"
        assert withQuery.jsonPath().getString("id") == id
        assert withQuery.jsonPath().getString("userName") != null
        assert listWithQuery.statusCode() == 200
        assert listWithQuery.jsonPath().getInt("totalResults") == 1
    }

    // RFC 7644 section 1.3 (Definitions), MAY, lines 205-207
    // Additionally, a client MAY apply a version number to the server root prefix (see Section 3.13).
    @SpecRef("RFC7644-1.3-9c02a0")
    def "RFC7644-1.3-9c02a0: Additionally, a client MAY apply a version number to the server root prefix (see Section 3.13)."() {
        given:
        String basePath = state().basePath
        // server root prefix = base path without a trailing version segment
        String root = basePath.replaceFirst(/\/v2\/?$/, "")
        if (root == basePath && !basePath.endsWith("/")) {
            throw new TestAbortedException("Configured base path '${basePath}' has no version segment to verify")
        }

        when:
        def response = scimRequestQuiet().basePath(root + "/v2").get("/Users?count=1")

        then:
        if (response.statusCode() == 404) {
            throw new TestAbortedException("Server does not offer version-prefixed URLs")
        }
        assert response.statusCode() == 200 : "RFC 7644 1.3/3.13: ${root}/v2/Users offered but status was ${response.statusCode()}"
        assert response.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA)
    }

    // RFC 7644 section 3.1 (Background), MAY, lines 366-369
    // Specific information about what attributes are defined within a schema MAY be obtained by querying a SCIM service provider's "/Schemas" endpoint for a schema definition (see Section 8.7 of [RFC7643]).
    @SpecRef("RFC7644-3.1-75f5ba")
    def "RFC7644-3.1-75f5ba: Specific information about what attributes are defined within a schema MAY be obtained by querying a SCIM..."() {
        when:
        def response = scimRequestQuiet().get("/Schemas/${USER_SCHEMA}")

        then:
        if (response.statusCode() == 404 || response.statusCode() == 501) {
            throw new TestAbortedException("Server does not offer schema definitions at /Schemas (HTTP ${response.statusCode()})")
        }
        assert response.statusCode() == 200 : "RFC 7644 3.1: GET /Schemas/{id} offered but status was ${response.statusCode()}"
        assert response.jsonPath().getString("id") == USER_SCHEMA
        List attrs = response.jsonPath().getList("attributes")
        assert attrs != null && attrs.any { it.name == "userName" } : "RFC 7644 3.1/RFC 7643 8.7: schema definition must list its attributes including userName"
    }

    // RFC 7644 section 3.1 (Background), MUST, lines 369-372
    // As with a SCIM resource, a SCIM message is a JSON object [RFC7159] that contains a "schemas" attribute with a URI whose namespace prefix MUST begin with "urn:ietf:params:scim:api:".
    @SpecRef("RFC7644-3.1-8f8796")
    def "RFC7644-3.1-8f8796: As with a SCIM resource, a SCIM message is a JSON object [RFC7159] that contains a 'schemas' attribute..."() {
        given:
        String prefix = "urn:ietf:params:scim:api:"

        when:
        def list = scimRequestQuiet().queryParam("count", 1).get("/Users")
        def error = scimRequestQuiet().get("/Users/does-not-exist-${UUID.randomUUID()}")

        then:
        assert list.statusCode() == 200
        List listSchemas = list.jsonPath().getList("schemas")
        assert listSchemas && listSchemas.every { it.startsWith(prefix) } : "RFC 7644 3.1: ListResponse schemas must begin with ${prefix} but was ${listSchemas}"
        assert error.statusCode() == 404
        List errSchemas = error.jsonPath().getList("schemas")
        assert errSchemas && errSchemas.every { it.startsWith(prefix) } : "RFC 7644 3.1: error message schemas must begin with ${prefix} but was ${errSchemas}"
    }

    // RFC 7644 section 3.1 (Background), MUST NOT, lines 372-375
    // As SCIM protocol messages are fixed and defined by SCIM specifications and registered extensions, SCIM message schemas using the above prefix URN SHALL NOT be discoverable using the "/Schemas" endpoint.
    @SpecRef("RFC7644-3.1-b3c159")
    def "RFC7644-3.1-b3c159: As SCIM protocol messages are fixed and defined by SCIM specifications and registered extensions, SCIM..."() {
        when:
        def response = scimRequestQuiet().get("/Schemas")
        if (response.statusCode() != 200) {
            throw new TestAbortedException("Server does not offer /Schemas (HTTP ${response.statusCode()})")
        }
        List ids = response.jsonPath().getList("Resources.id")

        then:
        assert ids : "RFC 7643 8.7: /Schemas returned no schemas"
        List api = ids.findAll { it.startsWith("urn:ietf:params:scim:api:") }
        assert api.isEmpty() : "RFC 7644 3.1: API message schemas must not be discoverable via /Schemas but found ${api}"
        for (String msg : [LIST_RESPONSE_SCHEMA, ERROR_SCHEMA, PATCH_OP_SCHEMA]) {
            def direct = scimRequestQuiet().get("/Schemas/${msg}")
            assert direct.statusCode() == 404 : "RFC 7644 3.1: GET /Schemas/${msg} must not return the message schema (status ${direct.statusCode()})"
        }
    }

    // RFC 7644 section 3.1 (Background), MUST, lines 378-380
    // SCIM responses SHALL reflect resource state as interpreted by the SCIM service provider.
    @SpecRef("RFC7644-3.1-4fbdc7")
    def "RFC7644-3.1-4fbdc7: SCIM responses SHALL reflect resource state as interpreted by the SCIM service provider."() {
        given: "a creation request with a readOnly attribute (id) and a value the server may normalize"
        String clientId = "client-chosen-${UUID.randomUUID()}"
        String userName = "State${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        def created = createUser(userName: userName, id: clientId)
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when:
        def fetched = scimRequestQuiet().get("/Users/${id}")

        then: "the POST response shows the server's interpretation (not a client echo) and matches stored state"
        assert fetched.statusCode() == 200
        assert id != clientId : "RFC 7644 3.1/RFC 7643 3.1: id is assigned by the service provider; response must not echo the client-supplied id"
        assert created.jsonPath().getString("userName") == fetched.jsonPath().getString("userName") :
            "RFC 7644 3.1: POST response userName '${created.jsonPath().getString("userName")}' differs from stored '${fetched.jsonPath().getString("userName")}'"
        assert created.jsonPath().getMap("meta").subMap(["resourceType", "created"]) == fetched.jsonPath().getMap("meta").subMap(["resourceType", "created"])
        assert created.jsonPath().getList("emails") == fetched.jsonPath().getList("emails")
    }

    // RFC 7644 section 5 (Preparation and Comparison of Internationalized Strings), MUST, lines 4513-4518
    // Before comparing or evaluating the uniqueness of a "userName" or "password" attribute, service providers MUST use the preparation, enforcement, and comparison of internationalized strings (PRECIS) preparation and comparison rules described in Sections 3 and 4, respectively, of [RFC7613], which is based on the PRECIS framework specification [RFC7564].
    @SpecRef("RFC7644-5-a9696d")
    def "RFC7644-5-a9696d: Before comparing or evaluating the uniqueness of a 'userName' or 'password' attribute, service providers..."() {
        given: "the User schema's caseExact declaration for userName and a first user"
        def schemaResp = scimRequestQuiet().get("/Schemas/${USER_SCHEMA}")
        Boolean userNameCaseExact = null
        if (schemaResp.statusCode() == 200) {
            def unAttr = (schemaResp.jsonPath().getList("attributes") ?: []).find { it instanceof Map && it.name == "userName" }
            if (unAttr instanceof Map && unAttr.caseExact instanceof Boolean) {
                userNameCaseExact = unAttr.caseExact as Boolean
            }
        }
        String u = UUID.randomUUID().toString().substring(0, 8)
        def first = createUser(userName: "Precis${u}@Test.com")
        assert first.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${first.statusCode()}"

        when:
        def second = createUser(userName: "precis${u}@test.com")

        then: "userName uniqueness is evaluated according to the server's PRECIS profile (UsernameCasePreserved when caseExact=true, UsernameCaseMapped otherwise)"
        if (userNameCaseExact == true) {
            assert second.statusCode() == 201 : "RFC 7644 5/RFC 7613 3.3: userName declared caseExact=true (UsernameCasePreserved) must allow distinct case (201) but was ${second.statusCode()}"
        } else {
            assert second.statusCode() == 409 : "RFC 7644 5/RFC 7613 3.2: userName differing only in case must conflict (409) when caseExact is false/default, but was ${second.statusCode()}"
            assertScimType(second, "uniqueness")
        }
    }
}
