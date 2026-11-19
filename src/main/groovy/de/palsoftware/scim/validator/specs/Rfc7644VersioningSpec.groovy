package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import spock.lang.Ignore
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.opentest4j.TestAbortedException

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "Protocol and resource versioning"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644VersioningSpec extends ScimBaseSpec {

    // RFC 7644 section 3.13 (SCIM Protocol Versioning), MAY, lines 3947-3948
    // The Base URL MAY be appended with a version identifier as a separate segment in the URL path.
    @SpecRef("RFC7644-3.13-7dcf1a")
    def "RFC7644-3.13-7dcf1a: The Base URL MAY be appended with a version identifier as a separate segment in the URL path."() {
        given: "an existing user"
        String id = newUserId()

        when: "the version identifier v2 is appended to the base URL"
        Response r = versionedRequest("v2").get("/Users/${id}")

        then: "a server that offers the version segment serves the resource through it"
        if (r.statusCode() in [400, 404, 501]) {
            throw new TestAbortedException("Server does not offer the optional version segment (HTTP ${r.statusCode()})")
        }
        assert r.statusCode() == 200 : "RFC 7644 3.13: GET <base>/v2/Users/{id} must return 200 or be unsupported but was ${r.statusCode()}"
        assert new JsonSlurper().parseText(r.asString()).id == id : "RFC 7644 3.13: the versioned URL must address the same resource"
    }

    // RFC 7644 section 3.13 (SCIM Protocol Versioning), MUST, lines 3949-3953
    // If specified, the version identifier MUST appear in the URL path immediately preceding the resource endpoint and conform to the following scheme: the character 'v' followed by the desired SCIM version number, e.g., a version 'v2' User request is specified as /v2/Users.
    @SpecRef("RFC7644-3.13-7682c8")
    def "RFC7644-3.13-7682c8: If specified, the version identifier MUST appear in the URL path immediately preceding the resource..."() {
        given: "an existing user"
        String id = newUserId()
        Response probe = versionedRequest("v2").get("/Users/${id}")
        if (probe.statusCode() in [400, 404, 501]) {
            throw new TestAbortedException("Server does not accept a version identifier in the URL path (HTTP ${probe.statusCode()})")
        }

        when: "the identifier v<number> directly precedes each kind of resource endpoint"
        String userEndpoint = "/Users/" + id
        Map<String, Response> responses = [
            (userEndpoint)          : versionedRequest("v2").get(userEndpoint),
            "/Users"                : versionedRequest("v2").queryParam("count", "1").get("/Users"),
            "/ServiceProviderConfig": versionedRequest("v2").get("/ServiceProviderConfig"),
            "/ResourceTypes"        : versionedRequest("v2").get("/ResourceTypes"),
            "/Schemas"              : versionedRequest("v2").get("/Schemas")
        ]

        then: "every endpoint works with the identifier in front of it"
        responses.each { String endpoint, Response r ->
            assert r.statusCode() == 200 : "RFC 7644 3.13: <base>/v2${endpoint} must be served but status was ${r.statusCode()}"
            assert new JsonSlurper().parseText(r.asString()) instanceof Map : "RFC 7644 3.13: <base>/v2${endpoint} must return a JSON object"
        }
    }

    // RFC 7644 section 3.13 (SCIM Protocol Versioning), MUST, lines 3953-3954
    // When specified, service providers MUST perform the operation using the desired version or reject the request.
    @SpecRef("RFC7644-3.13-636fed")
    def "RFC7644-3.13-636fed: When specified, service providers MUST perform the operation using the desired version or reject the request."() {
        given: "an existing user and a protocol version no server supports"
        String id = newUserId()

        when: "the request names version v99"
        Response r = versionedRequest("v99").get("/Users/${id}")

        then: "the server does not perform the operation (it cannot use version 99), it rejects the request with a SCIM error"
        assert r.statusCode() >= 400 && r.statusCode() < 500 :
            "RFC 7644 3.13: a request for the unsupported version v99 MUST be rejected but status was ${r.statusCode()}: ${r.asString()?.take(200)}"
        assertScimError(r, r.statusCode())
    }

    // RFC 7644 section 3.13 (SCIM Protocol Versioning), SHOULD, lines 3954-3956
    // When omitted, service providers SHOULD perform the operation using the most recent SCIM protocol version supported by the service provider.
    @SpecRef("RFC7644-3.13-159e35")
    def "RFC7644-3.13-159e35: When omitted, service providers SHOULD perform the operation using the most recent SCIM protocol version..."() {
        given: "an existing user"
        String id = newUserId()

        when: "the request carries no version identifier"
        Response plain = scimRequest().get("/Users/${id}")
        Response versioned = versionedRequest("v2").get("/Users/${id}")

        then: "it is served with SCIM 2.0 semantics, the same as the explicit v2 request where that is offered"
        assert plain.statusCode() == 200 : "RFC 7644 3.13: a request without version identifier must be served but status was ${plain.statusCode()}"
        Map body = new JsonSlurper().parseText(plain.asString()) as Map
        assert (body.schemas as List)?.contains(USER_SCHEMA) : "RFC 7644 3.13: a request without version must be served with the latest (2.0) User schema, got ${body.schemas}"
        if (versioned.statusCode() == 200) {
            assert new JsonSlurper().parseText(versioned.asString()).userName == body.userName :
                "RFC 7644 3.13: the request without version must behave like the latest version v2"
        }
    }

    // RFC 7644 section 3.14 (Versioning Resources), MAY, lines 3961-3964
    // Service providers MAY support weak ETags as the preferred mechanism for performing conditional retrievals and ensuring that clients do not inadvertently overwrite each other's changes, respectively.
    @SpecRef("RFC7644-3.14-d7fe9f")
    @RequiresCapability("etag")
    def "RFC7644-3.14-d7fe9f: Service providers MAY support weak ETags as the preferred mechanism for performing conditional retrievals..."() {
        given: "an existing user"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when: "it is read"
        Response r = scimRequest().get("/Users/${id}")

        then: "the server that supports versioning gives a weak ETag"
        String etag = r.header("ETag")
        assert etag : "RFC 7644 3.14: the server advertises etag support but GET returned no ETag header"
        assert etag ==~ /^W\/".*"$/ : "RFC 7644 3.14: the ETag is expected to be a weak ETag (W/\"...\") but was ${etag}"
    }

    // RFC 7644 section 3.14 (Versioning Resources), MUST, lines 3964-3966
    // When supported, SCIM ETags MUST be specified as an HTTP header
    @SpecRef("RFC7644-3.14-9216ff")
    @RequiresCapability("etag")
    def "RFC7644-3.14-9216ff: When supported, SCIM ETags MUST be specified as an HTTP header"() {
        when: "a user is created, read and replaced"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        Response get = scimRequest().get("/Users/${id}")
        Response put = scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, displayName: "W13 etag"])).put("/Users/${id}")

        then: "the version is specified as an ETag HTTP header on each response that returns the resource"
        [[created, "POST"], [get, "GET"], [put, "PUT"]].each { pair ->
            Response r = pair[0] as Response
            String etag = r.header("ETag")
            assert etag : "RFC 7644 3.14: the ${pair[1]} response must carry the version as an ETag HTTP header"
            assert etag ==~ /^(W\/)?"[^"]+"$/ : "RFC 7644 3.14: the ${pair[1]} ETag header must be a quoted entity tag but was ${etag}"
        }
    }

    // RFC 7644 section 3.14 (Versioning Resources), SHOULD, lines 3964-3966
    // SHOULD be specified within the 'version' attribute contained in the resource's 'meta' attribute.
    @SpecRef("RFC7644-3.14-1830df")
    @RequiresCapability("etag")
    def "RFC7644-3.14-1830df: SHOULD be specified within the 'version' attribute contained in the resource's 'meta' attribute."() {
        when: "a user is created and read"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        Response get = scimRequest().get("/Users/${id}")

        then: "meta.version carries the same version as the ETag header"
        [[created, "POST"], [get, "GET"]].each { pair ->
            Response r = pair[0] as Response
            String version = r.jsonPath().getString("meta.version")
            assert version : "RFC 7644 3.14: the ${pair[1]} response SHOULD carry the version in meta.version"
            assert version == r.header("ETag") : "RFC 7644 3.14: meta.version '${version}' differs from the ETag header '${r.header("ETag")}' in ${pair[1]}"
        }
    }

    // RFC 7644 section 3.14 (Versioning Resources), MAY, lines 4036-4037
    // With the returned ETag, clients MAY choose to retrieve the resource only if the resource has been modified.
    @SpecRef("RFC7644-3.14-03e418")
    @RequiresCapability("etag")
    def "RFC7644-3.14-03e418: With the returned ETag, clients MAY choose to retrieve the resource only if the resource has been modified."() {
        given: "a user and its ETag"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        String etag = created.header("ETag")
        assert etag : "RFC 7644 3.14: created resource has no ETag header"

        when: "the user is read conditionally with If-None-Match, before and after a change"
        Response unchanged = scimRequest().header("If-None-Match", etag).get("/Users/${id}")
        Response put = scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, displayName: "W13 changed"])).put("/Users/${id}")
        Response changed = scimRequest().header("If-None-Match", etag).get("/Users/${id}")

        then: "the unchanged resource gives 304 with an empty body, the modified one the full representation"
        assert unchanged.statusCode() == 304 : "RFC 7644 3.14: GET with the current ETag in If-None-Match must return 304 but was ${unchanged.statusCode()}"
        assert unchanged.asString().isEmpty() : "RFC 7644 3.14: the 304 response must have an empty body"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}"
        assert changed.statusCode() == 200 : "RFC 7644 3.14: GET with the outdated ETag must return the resource (200) but was ${changed.statusCode()}"
        assert changed.jsonPath().getString("id") == id
        assert changed.jsonPath().getString("displayName") == "W13 changed"
    }

    // RFC 7644 section 3.14 (Versioning Resources), MAY, lines 4051-4055
    // If the service provider supports versioning of resources, the client MAY supply an If-Match header (Section 3.1 of [RFC7232]) for PUT and PATCH operations to ensure that the requested operation succeeds only if the supplied ETag matches the latest service provider resource, e.g., If-Match: W/"e180ee84f0671b1".
    @SpecRef("RFC7644-3.14-8a6c49")
    @RequiresCapability("etag")
    def "RFC7644-3.14-8a6c49: If the service provider supports versioning of resources, the client MAY supply an If-Match header..."() {
        given: "a user and its ETag"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        String first = created.header("ETag")
        assert first : "RFC 7644 3.14: created resource has no ETag header"

        when: "PUT is sent with a matching If-Match, then again with the now outdated ETag"
        Response okPut = scimRequest().header("If-Match", first)
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, displayName: "W13 one"])).put("/Users/${id}")
        Response stalePut = scimRequest().header("If-Match", first)
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, displayName: "W13 two"])).put("/Users/${id}")
        String second = okPut.header("ETag")

        then: "the matching PUT succeeds, the outdated one fails with 412 and changes nothing"
        assert okPut.statusCode() == 200 : "RFC 7644 3.14: PUT with matching If-Match must succeed (200) but was ${okPut.statusCode()}"
        assert second && second != first : "RFC 7644 3.14: the modified resource must have a new ETag (was ${first}, now ${second})"
        assertScimError(stalePut, 412)
        assert scimRequest().get("/Users/${id}").jsonPath().getString("displayName") == "W13 one" :
            "RFC 7644 3.14: the PUT with the outdated If-Match must not have changed the resource"

        and: "the same holds for PATCH"
        if (isPatchSupported()) {
            Response stalePatch = scimRequest().header("If-Match", first)
                .body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "displayName", value: "W13 three"]]))).patch("/Users/${id}")
            assertScimError(stalePatch, 412)
            Response okPatch = scimRequest().header("If-Match", second)
                .body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "displayName", value: "W13 three"]]))).patch("/Users/${id}")
            assertPatchSuccess(okPatch)
        }
    }

    /** A request whose base path has a version identifier segment appended, e.g. <base>/v2. */
    private RequestSpecification versionedRequest(String version) {
        String basePath = state().basePath ?: ""
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1)
        basePath = basePath.replaceFirst(/\/v\d+$/, "")
        return scimRequest().basePath("${basePath}/${version}".toString())
    }

    private String newUserId() {
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        return created.jsonPath().getString("id")
    }
}
