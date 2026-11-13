package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import spock.lang.Ignore
import de.palsoftware.scim.validator.base.ScimExchangeCaptureFilter
import groovy.json.JsonGenerator
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.restassured.RestAssured
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.opentest4j.TestAbortedException

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "Data formats and notation"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644FormatsSpec extends ScimBaseSpec {

    // RFC 7644 section 3.8 (Data Input/Output Formats), MUST, lines 3534-3535
    // Servers MUST accept requests and be able to return JSON-structured responses using UTF-8 encoding [RFC3629].
    @SpecRef("RFC7644-3.8-325a8b")
    def "RFC7644-3.8-325a8b: Servers MUST accept requests and be able to return JSON-structured responses using UTF-8 encoding [RFC3629]."() {
        given: "a user name with characters outside ASCII (2-, 3- and 4-byte UTF-8 sequences)"
        String userName = "w13fmt-${UUID.randomUUID().toString().substring(0, 8)}-ëñ-日本語-😀"

        when: "the request body is sent as UTF-8 bytes with an explicit charset=utf-8 and the resource is read back"
        Map result = utf8RoundTrip("application/scim+json; charset=utf-8", userName)

        then: "the server accepted the JSON and returns JSON that is valid UTF-8 carrying the same characters"
        assertUtf8Json(result.create as Response, userName, "POST response")
        assertUtf8Json(result.get as Response, userName, "GET response")
    }

    // RFC 7644 section 3.8 (Data Input/Output Formats), MUST, lines 3535-3536
    // UTF-8 SHALL be the default encoding format.
    @SpecRef("RFC7644-3.8-677ce9")
    def "RFC7644-3.8-677ce9: UTF-8 SHALL be the default encoding format."() {
        given: "a user name with characters outside ASCII"
        String userName = "w13fmt-${UUID.randomUUID().toString().substring(0, 8)}-üß-Жж-🚀"

        when: "the request body is UTF-8 and the Content-Type carries no charset parameter at all"
        Map result = utf8RoundTrip("application/scim+json", userName)

        then: "the server decodes the body as UTF-8 by default and answers in UTF-8"
        assertUtf8Json(result.create as Response, userName, "POST response")
        assertUtf8Json(result.get as Response, userName, "GET response")
        // the default response encoding must be UTF-8 as well: a charset parameter, when present, may only say utf-8
        [result.create, result.get].each { Response r ->
            String cs = charsetParam(r)
            assert cs == null || cs == "utf-8" :
                "RFC 7644 3.8: UTF-8 is the default encoding but the response declares charset=${cs}"
        }
    }

    // RFC 7644 section 3.8 (Data Input/Output Formats), MAY, lines 3536-3537
    // Other media types MAY be supported by service providers but are beyond the scope of this specification.
    @SpecRef("RFC7644-3.8-8c283c")
    def "RFC7644-3.8-8c283c: Other media types MAY be supported by service providers but are beyond the scope of this specification."() {
        given: "an existing user"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when: "the client asks for a media type other than JSON (XML)"
        Response r = bareRequest().accept("application/xml").get("/Users/${id}")
        int status = r.statusCode()
        String mt = mediaType(r)

        then: "the server either refuses (406) or, if it serves another media type, the body really is in the declared format"
        if (status != 406) {  // refusing unsupported media types with 406 is a valid answer
            assert status >= 200 && status < 300 :
                "RFC 7644 3.8: Accept application/xml must be answered with 406 or with a representation, but status was ${status}"
            if (mt == "application/json" || mt == "application/scim+json") {
                assert parseJson(r, "Content-Type ${mt}") instanceof Map
            } else if (mt != null && mt.contains("xml")) {
                try {
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(r.asByteArray()))
                } catch (Exception e) {
                    assert false : "RFC 7644 3.8: response declares ${mt} but the body is not well-formed XML (${e.message})"
                }
            } else {
                assert false : "RFC 7644 3.8: response declares unexpected media type ${r.contentType()}"
            }
        }
    }

    // RFC 7644 section 3.8 (Data Input/Output Formats), MAY, lines 3539-3543
    // MAY specify the desired response data format via an HTTP "Accept" header (Section 5.3.2 of [RFC7231]), e.g., "Accept: application/scim+json", or via URI suffix:
    @SpecRef("RFC7644-3.8-e065ed")
    def "RFC7644-3.8-e065ed: MAY specify the desired response data format via an HTTP 'Accept' header (Section 5.3.2 of [RFC7231]),..."() {
        given: "an existing user"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when: "the client asks for the format with an Accept header and, separately, with the URI suffix .scim"
        Response viaAccept = bareRequest().accept("application/scim+json").get("/Users/${id}")
        Response viaSuffix = bareRequest().get("/Users/${id}.scim")

        then: "the Accept header is honoured"
        assert viaAccept.statusCode() == 200 : "RFC 7644 3.8: GET with Accept application/scim+json must return 200 but was ${viaAccept.statusCode()}"
        assert mediaType(viaAccept) == "application/scim+json" : "RFC 7644 3.8: Accept application/scim+json must be answered with that media type, got ${viaAccept.contentType()}"

        and: "a server that offers the URI suffix serves the same resource through it"
        assert viaSuffix.statusCode() in [200, 400, 404, 406, 415, 501] : "RFC 7644 3.8: GET /Users/{id}.scim must return 200 or be unsupported but was ${viaSuffix.statusCode()}"
        if (viaSuffix.statusCode() == 200) {
            assert parseJson(viaSuffix, ".scim suffix response").id == id : "RFC 7644 3.8: the .scim suffix must address the same resource"
        }
    }

    // RFC 7644 section 3.8 (Data Input/Output Formats), MUST, lines 3548-3551
    // Service providers MUST support the "Accept" header "Accept: application/scim+json"
    @SpecRef("RFC7644-3.8-033fa1")
    def "RFC7644-3.8-033fa1: Service providers MUST support the 'Accept' header 'Accept: application/scim+json'"() {
        given: "an existing user"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when: "resources are requested with Accept: application/scim+json"
        Response user = bareRequest().accept("application/scim+json").get("/Users/${id}")
        Response spc = bareRequest().accept("application/scim+json").get("/ServiceProviderConfig")

        then: "the server answers 200 with a scim+json JSON document"
        [user, spc].each { Response r ->
            assert r.statusCode() == 200 : "RFC 7644 3.8: Accept application/scim+json must be supported (200) but status was ${r.statusCode()}"
            assert mediaType(r) == "application/scim+json" : "RFC 7644 3.8: expected Content-Type application/scim+json but was ${r.contentType()}"
            assert parseJson(r, "scim+json response") instanceof Map
        }
        assert parseJson(user, "user").id == id
    }

    // RFC 7644 section 3.8 (Data Input/Output Formats), SHOULD, lines 3548-3551
    // SHOULD support the header "Accept: application/json", both of which specify JSON documents conforming to [RFC7159].
    @SpecRef("RFC7644-3.8-6c502b")
    def "RFC7644-3.8-6c502b: SHOULD support the header 'Accept: application/json', both of which specify JSON documents conforming to..."() {
        given: "an existing user"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when: "the resource is requested with Accept: application/json"
        Response r = bareRequest().accept("application/json").get("/Users/${id}")

        then: "the server answers 200 with a JSON document (application/json or application/scim+json)"
        assert r.statusCode() == 200 : "RFC 7644 3.8: Accept application/json SHOULD be supported (200) but status was ${r.statusCode()}"
        assert mediaType(r) in ["application/json", "application/scim+json"] :
            "RFC 7644 3.8: Accept application/json must be answered with a JSON media type but Content-Type was ${r.contentType()}"
        assert parseJson(r, "application/json response").id == id
    }

    // RFC 7644 section 3.9 (Additional Operation Response Parameters), MAY, lines 3588-3591
    // Clients MAY request a partial resource representation on any operation that returns a resource within the response by specifying either of the mutually exclusive URL query parameters "attributes" or "excludedAttributes", as follows:
    @SpecRef("RFC7644-3.9-c96999")
    def "RFC7644-3.9-c96999: Clients MAY request a partial resource representation on any operation that returns a resource within the..."() {
        given: "a user with several attributes"
        Map u = createProjectionUser()
        String id = u.id

        when: "the attributes parameter is used on POST, GET, PUT and PATCH (every operation that returns the resource)"
        String name2 = "w13proj-${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Response post = scimRequest().queryParam("attributes", "userName")
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: name2, displayName: "W13 Post", title: "T", active: true,
                                     emails: [[value: name2, type: "work", primary: true]]]))
            .post("/Users")
        if (post.statusCode() == 201) createdUserIds << post.jsonPath().getString("id")
        Response get = scimRequest().queryParam("attributes", "userName").get("/Users/${id}")
        Response put = scimRequest().queryParam("attributes", "userName")
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: u.userName, displayName: "W13 Put", title: "Put title", active: true]))
            .put("/Users/${id}")
        Response patch = isPatchSupported() ? scimRequest().queryParam("attributes", "userName")
            .body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "title", value: "Patched title"]])))
            .patch("/Users/${id}") : null
        Response excluded = scimRequest().queryParam("excludedAttributes", "title,emails").get("/Users/${id}")

        then: "each returned resource is a partial representation"
        assert post.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${post.statusCode()}"
        assertOnlyUserName(post, "POST ?attributes=userName")
        assert get.statusCode() == 200 : "RFC 7644 3.4.1: GET must return 200 but was ${get.statusCode()}"
        assertOnlyUserName(get, "GET ?attributes=userName")
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}"
        assertOnlyUserName(put, "PUT ?attributes=userName")
        if (patch != null && patch.statusCode() == 200) {
            assertOnlyUserName(patch, "PATCH ?attributes=userName")
        } else if (patch != null) {
            assert patch.statusCode() == 204 : "RFC 7644 3.5.2: PATCH must return 200 or 204 but was ${patch.statusCode()}"
        }
        assert excluded.statusCode() == 200
        Map ex = parseJson(excluded, "excludedAttributes response")
        assert ex.id == id && ex.userName == u.userName : "RFC 7644 3.9: excludedAttributes must keep the other default attributes"
        assert !ex.containsKey("title") && !ex.containsKey("emails") : "RFC 7644 3.9: excludedAttributes=title,emails must remove them, got ${ex.keySet()}"
    }

    // RFC 7644 section 3.9 (Additional Operation Response Parameters), MUST, lines 3593-3597
    // When specified, the default list of attributes SHALL be overridden, and each resource returned MUST contain the minimum set of resource attributes and any attributes or sub-attributes explicitly requested by the "attributes" parameter.
    @SpecRef("RFC7644-3.9-0a40b1")
    def "RFC7644-3.9-0a40b1: attributes: When specified, the default list of attributes SHALL be overridden, and each resource returned..."() {
        given: "a user with several attributes"
        Map u = createProjectionUser()

        when: "attributes=userName,name.givenName is used on GET of the resource and on a filtered list"
        Response get = scimRequest().queryParam("attributes", "userName,name.givenName").get("/Users/${u.id}")
        Response list = scimRequest()
            .queryParam("filter", "userName eq \"${u.userName}\"")
            .queryParam("attributes", "userName,name.givenName").get("/Users")

        then: "the default list is overridden: only the minimum set (id) plus the requested attributes come back"
        assert get.statusCode() == 200 : "RFC 7644 3.4.1: GET must return 200 but was ${get.statusCode()}"
        assert list.statusCode() == 200 : "RFC 7644 3.4.2: list must return 200 but was ${list.statusCode()}"
        Map listed = (parseJson(list, "list response").Resources as List)?.find { it.id == u.id } as Map
        assert listed != null : "RFC 7644 3.4.2: the created user must be found by its userName filter"
        [[get.statusCode() == 200 ? parseJson(get, "GET response") : [:], "GET"], [listed, "list"]].each { pair ->
            Map r = pair[0] as Map
            String where = pair[1]
            assert r.id == u.id : "RFC 7644 3.9 (${where}): the minimum attribute set (id) MUST be returned"
            assert r.userName == u.userName : "RFC 7644 3.9 (${where}): requested attribute userName MUST be returned"
            assert r.name?.givenName == "Pat" : "RFC 7644 3.9 (${where}): requested sub-attribute name.givenName MUST be returned, got ${r.name}"
            assert r.name?.familyName == null : "RFC 7644 3.9 (${where}): name.familyName was not requested but returned"
            ["displayName", "title", "emails", "active"].each { String a ->
                assert !r.containsKey(a) : "RFC 7644 3.9 (${where}): default attribute '${a}' was not requested but is returned, attributes MUST override the default list"
            }
        }
    }

    // RFC 7644 section 3.9 (Additional Operation Response Parameters), MUST, lines 3602-3603
    // When specified, each resource returned MUST contain the minimum set of resource attributes.
    @SpecRef("RFC7644-3.9-6f81ed")
    def "RFC7644-3.9-6f81ed: excludedAttributes: When specified, each resource returned MUST contain the minimum set of resource..."() {
        given: "a user with several attributes"
        Map u = createProjectionUser()

        when: "excludedAttributes names the id and two other attributes"
        Response get = scimRequest().queryParam("excludedAttributes", "id,title,displayName").get("/Users/${u.id}")
        Response list = scimRequest()
            .queryParam("filter", "userName eq \"${u.userName}\"")
            .queryParam("excludedAttributes", "id,title,displayName").get("/Users")

        then: "the minimum set (id) is returned anyway, the named attributes are removed, the other defaults stay"
        assert get.statusCode() == 200 : "RFC 7644 3.4.1: GET must return 200 but was ${get.statusCode()}"
        assert list.statusCode() == 200 : "RFC 7644 3.4.2: list must return 200 but was ${list.statusCode()}"
        List resources = parseJson(list, "list response").Resources as List
        assert resources?.size() == 1 : "RFC 7644 3.4.2: filter on the unique userName must find exactly the created user"
        [[parseJson(get, "GET response"), "GET"], [resources[0] as Map, "list"]].each { pair ->
            Map r = pair[0] as Map
            String where = pair[1]
            assert r.id == u.id : "RFC 7644 3.9 (${where}): excludedAttributes MUST NOT remove the minimum set, id is missing"
            assert !r.containsKey("title") && !r.containsKey("displayName") :
                "RFC 7644 3.9 (${where}): excluded attributes title/displayName are still returned: ${r.keySet()}"
            assert r.userName == u.userName : "RFC 7644 3.9 (${where}): default attribute userName must still be returned"
            assert r.emails != null && r.name != null : "RFC 7644 3.9 (${where}): other default attributes must still be returned, got ${r.keySet()}"
        }
    }

    // RFC 7644 section 3.10 (Attribute Notation), MAY, lines 3651-3654
    // Clients MAY omit core schema attribute URN prefixes
    @SpecRef("RFC7644-3.10-8d8c10")
    def "RFC7644-3.10-8d8c10: Clients MAY omit core schema attribute URN prefixes"() {
        given: "a user"
        Map u = createProjectionUser()

        when: "core attributes are referenced without their schema URN prefix, in attributes and in a filter, and with it"
        Response bare = scimRequest().queryParam("attributes", "userName").get("/Users/${u.id}")
        Response qualified = scimRequest()
            .queryParam("attributes", "urn:ietf:params:scim:schemas:core:2.0:User:userName").get("/Users/${u.id}")
        Response filtered = scimRequest().queryParam("filter", "userName eq \"${u.userName}\"").get("/Users")

        then: "the unprefixed names are understood (and mean the same as the prefixed form)"
        assert bare.statusCode() == 200 : "RFC 7644 3.10: attributes=userName must be accepted, status ${bare.statusCode()}"
        assertOnlyUserName(bare, "unprefixed attributes=userName")
        assert qualified.statusCode() == 200 : "RFC 7644 3.10: fully qualified attribute name must be accepted, status ${qualified.statusCode()}"
        assertOnlyUserName(qualified, "fully qualified attributes")
        assert filtered.statusCode() == 200 : "RFC 7644 3.10: filter with unprefixed userName must be accepted, status ${filtered.statusCode()}"
        assert (parseJson(filtered, "list response").Resources as List)*.id == [u.id] : "RFC 7644 3.10: unprefixed userName filter must find the user"
    }

    private static final JsonGenerator RAW_UTF8_JSON = new JsonGenerator.Options().disableUnicodeEscaping().build()

    /** A request without the default Accept and Content-Type headers of scimRequest(). */
    private RequestSpecification bareRequest() {
        configureRestAssured()
        def s = state()
        return RestAssured.given()
            .config(SCIM_REST_ASSURED_CONFIG)
            .baseUri(s.scimApiUrl)
            .basePath(s.basePath)
            .filter(new ScimExchangeCaptureFilter())
            .header("Authorization", "Bearer ${s.authToken}")
    }

    private static String mediaType(Response r) {
        return r.contentType()?.split(";")?.getAt(0)?.trim()?.toLowerCase()
    }

    private static String charsetParam(Response r) {
        def m = (r.contentType() ?: "") =~ /(?i);\s*charset\s*=\s*"?([^";\s]+)"?/
        return m.find() ? m.group(1).toLowerCase() : null
    }

    private static Object parseJson(Response r, String what) {
        try {
            return new JsonSlurper().parseText(r.asString())
        } catch (Exception e) {
            assert false : "RFC 7644 3.8: ${what} is not a JSON document (${e.message}), body: ${r.asString()?.take(200)}"
        }
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (CharacterCodingException e) {
            return null
        }
    }

    /** POST a user whose body is written as raw UTF-8 bytes, then GET it back. */
    private Map utf8RoundTrip(String contentType, String userName) {
        byte[] body = RAW_UTF8_JSON.toJson([schemas: [USER_SCHEMA], userName: userName]).getBytes(StandardCharsets.UTF_8)
        assert body.length > userName.length() : "test bug: the request body must contain multi-byte UTF-8 sequences"
        Response create = scimRequest().contentType(contentType).body(body).post("/Users")
        String id = create.statusCode() == 201 ? create.jsonPath().getString("id") : null
        if (id) createdUserIds << id
        Response get = id ? scimRequest().get("/Users/${id}") : create
        return [create: create, get: get]
    }

    private void assertUtf8Json(Response r, String expectedUserName, String what) {
        assert r.statusCode() in [200, 201] :
            "RFC 7644 3.8: servers MUST accept UTF-8 JSON requests, ${what} had status ${r.statusCode()}: ${r.asString()?.take(200)}"
        assert mediaType(r) in ["application/scim+json", "application/json"] :
            "RFC 7644 3.8: ${what} must be JSON, Content-Type was ${r.contentType()}"
        String charset = charsetParam(r)
        assert charset == null || charset == "utf-8" : "RFC 7644 3.8: ${what} declares charset=${charset}, expected UTF-8"
        String text = strictUtf8(r.asByteArray())
        assert text != null : "RFC 7644 3.8: ${what} body is not valid UTF-8"
        def parsed = new JsonSlurper().parseText(text)
        assert parsed.userName == expectedUserName :
            "RFC 7644 3.8: ${what} userName '${parsed.userName}' differs from the UTF-8 value sent '${expectedUserName}'"
    }

    /** Create a user with a set of optional attributes for the attributes/excludedAttributes tests. */
    private Map createProjectionUser() {
        String userName = "w13proj-${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Response r = createUser(userName: userName, displayName: "W13 Projection", title: "Engineer", active: true,
            name: [givenName: "Pat", familyName: "Projection"],
            emails: [[value: userName, type: "work", primary: true]])
        assert r.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${r.statusCode()}: ${r.asString()?.take(200)}"
        return [id: r.jsonPath().getString("id"), userName: userName]
    }

    /** attributes=userName: id and userName come back, nothing else of the resource's own attributes. */
    private void assertOnlyUserName(Response r, String what) {
        Map body = parseJson(r, what) as Map
        assert body.id : "RFC 7644 3.9 (${what}): the minimum attribute set (id) MUST be returned"
        assert body.userName : "RFC 7644 3.9 (${what}): the requested attribute userName MUST be returned"
        ["displayName", "title", "name", "emails", "active"].each { String a ->
            assert !body.containsKey(a) :
                "RFC 7644 3.9 (${what}): attribute '${a}' was not requested but is returned (keys ${body.keySet()})"
        }
    }
}
