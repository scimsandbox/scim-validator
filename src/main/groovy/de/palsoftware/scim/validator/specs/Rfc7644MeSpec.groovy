package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimUrlSecurity
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import spock.lang.Ignore
import groovy.json.JsonSlurper
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "/Me endpoint"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644MeSpec extends ScimBaseSpec {

    // RFC 7644 section 3.11 ("/Me" Authenticated Subject Alias), MAY, lines 3666-3668
    // A client MAY use a URL of the form "<base-URI>/Me" as a URI alias for the User or other resource associated with the currently authenticated subject for any SCIM operation.
    @SpecRef("RFC7644-3.11-cd44de")
    def "RFC7644-3.11-cd44de: A client MAY use a URL of the form '<base-URI>/Me' as a URI alias for the User or other resource..."() {
        when: "the /Me alias is used for a GET"
        Response r = meGet()

        then: "the server answers with 501 (not supported), 308 (redirect), or 200 (the subject's resource)"
        assert r.statusCode() in [501, 308, 200] : "RFC 7644 3.11: /Me must be answered with 501, 308 or the resource, but status was ${r.statusCode()}"
        if (r.statusCode() == 308) {
            String location = r.header("Location")
            assert location : "RFC 7644 3.11: the 308 response for /Me must carry a Location header"
            Response followed = getAtLocation(location)
            assert followed.statusCode() == 200 : "RFC 7644 3.11: the Location of the /Me redirect must be readable but gave ${followed.statusCode()}"
            assert followed.jsonPath().getString("id") : "RFC 7644 3.11: the resource behind /Me must have an id"
        } else if (r.statusCode() == 200) {
            assert r.jsonPath().getString("id") : "RFC 7644 3.11: the resource returned for /Me must have an id: ${r.asString()?.take(200)}"
            assert r.jsonPath().getList("schemas") : "RFC 7644 3.11: the resource returned for /Me must have schemas"
        }
    }

    // RFC 7644 section 3.11 ("/Me" Authenticated Subject Alias), MAY, lines 3668-3669
    // A service provider MAY respond in one of three ways:
    @SpecRef("RFC7644-3.11-79fd56")
    def "RFC7644-3.11-79fd56: A service provider MAY respond in one of three ways:"() {
        when: "the /Me alias is used for a GET without following redirects"
        Response r = meGet()

        then: "the server answers in one of the three allowed ways: 501, 308 redirect, or direct processing"
        assert r.statusCode() in [501, 308, 200] :
            "RFC 7644 3.11: /Me must be answered with 501 (not supported), 308 (redirect) or the processed result (200) but status was ${r.statusCode()}: ${r.asString()?.take(200)}"
    }

    // RFC 7644 section 3.11 ("/Me" Authenticated Subject Alias), SHOULD, lines 3671-3672
    // A service provider that does NOT support this feature SHOULD respond with HTTP status code 501 (Not Implemented).
    @SpecRef("RFC7644-3.11-87600c")
    def "RFC7644-3.11-87600c: A service provider that does NOT support this feature SHOULD respond with HTTP status code 501 (Not..."() {
        when: "the /Me alias is used for a GET"
        Response r = meGet()

        then: "a server that does not support it answers 501 Not Implemented"
        if (r.statusCode() in [200, 308]) {
            throw new TestAbortedException("Server supports the /Me alias (HTTP ${r.statusCode()})")
        }
        assert r.statusCode() == 501 : "RFC 7644 3.11: a server without /Me support SHOULD answer 501 but status was ${r.statusCode()}"
    }

    // RFC 7644 section 3.11 ("/Me" Authenticated Subject Alias), MAY, lines 3674-3676
    // A service provider MAY choose to redirect the client using HTTP status code 308 (Permanent Redirect) to the resource associated with the authenticated subject.
    @SpecRef("RFC7644-3.11-76832a")
    def "RFC7644-3.11-76832a: A service provider MAY choose to redirect the client using HTTP status code 308 (Permanent Redirect) to..."() {
        when: "the /Me alias is used for a GET without following redirects"
        Response r = meGet()

        then: "the server answers with one of the three allowed statuses, and if 308 it provides a valid Location"
        assert r.statusCode() in [501, 308, 200] : "RFC 7644 3.11: /Me must return 501, 308 or 200 but was ${r.statusCode()}"
        if (r.statusCode() == 308) {
            String location = r.header("Location")
            assert location : "RFC 7644 3.11: the 308 redirect for /Me must carry a Location header"
            Response target = getAtLocation(location)
            assert target.statusCode() == 200 : "RFC 7644 3.11: the redirect target of /Me must be the subject's resource but returned ${target.statusCode()}"
            assert target.jsonPath().getString("id") : "RFC 7644 3.11: the redirect target of /Me must be a resource with an id"
        }
    }

    // RFC 7644 section 3.11 ("/Me" Authenticated Subject Alias), MAY, lines 3676-3677
    // The client MAY then repeat the request at the indicated location.
    @SpecRef("RFC7644-3.11-8d5f66")
    def "RFC7644-3.11-8d5f66: The client MAY then repeat the request at the indicated location."() {
        when: "the /Me alias is used and any redirect is repeated by hand"
        Response r = meGet()

        then: "the server answers with one of the three allowed statuses, and if 308 lets the client repeat the request at Location"
        assert r.statusCode() in [501, 308, 200] : "RFC 7644 3.11: /Me must return 501, 308 or 200 but was ${r.statusCode()}"
        if (r.statusCode() == 308) {
            String location = r.header("Location")
            assert location : "RFC 7644 3.11: the 308 redirect for /Me must carry a Location header"
            Response repeated = getAtLocation(location)
            assert repeated.statusCode() == 200 : "RFC 7644 3.11: repeating the request at the 308 Location must succeed but gave ${repeated.statusCode()}"
        }
    }

    // RFC 7644 section 3.11 ("/Me" Authenticated Subject Alias), MAY, lines 3679-3679
    // A service provider MAY process the SCIM request directly.
    @SpecRef("RFC7644-3.11-c8572b")
    def "RFC7644-3.11-c8572b: A service provider MAY process the SCIM request directly."() {
        when: "the /Me alias is used for a GET without following redirects"
        Response r = meGet()

        then: "the server answers with one of the three allowed statuses, and if 200 returns the subject's resource"
        assert r.statusCode() in [501, 308, 200] : "RFC 7644 3.11: /Me must return 501, 308 or 200 but was ${r.statusCode()}"
        if (r.statusCode() == 200) {
            Map body = new JsonSlurper().parseText(r.asString()) as Map
            assert body.id && body.schemas : "RFC 7644 3.11: the directly returned /Me resource must be a SCIM resource: ${r.asString()?.take(200)}"
            String metaLocation = body.meta?.location as String
            assert hasText(metaLocation) : "RFC 7644 3.11: the directly returned /Me resource must carry meta.location"
            Response byId = scimRequest().baseUri(resolveLocation(metaLocation)).basePath("").get()
            assert byId.statusCode() == 200 && byId.jsonPath().getString("id") == body.id :
                "RFC 7644 3.11: the /Me resource must be the resource at its own meta.location"
        }
    }

    /** GET <base>/Me without following alias redirects, allowing at most one same-endpoint http->https transport upgrade. */
    private Response meGet() {
        Response r = scimRequest().redirects().follow(false).get("/Me")
        if (r.statusCode() in [301, 302, 307, 308]) {
            String upgradedMeUrl = ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
                state().scimApiUrl,
                "${state().baseUrl}/Me",
                r.header("Location")
            )
            if (upgradedMeUrl != null) {
                return scimRequest().redirects().follow(false).baseUri(upgradedMeUrl).basePath("").get()
            }
        }
        return r
    }

    private Response getAtLocation(String location) {
        return scimRequest().baseUri(resolveLocation(location)).basePath("").get()
    }

    /** Resolve a Location value (absolute or relative) against the SCIM base URL, enforcing same-origin. */
    private static String resolveLocation(String location) {
        return ScimUrlSecurity.resolveScimReferenceUrl(state().scimApiUrl, state().baseUrl, state().basePath, location)
    }
}
