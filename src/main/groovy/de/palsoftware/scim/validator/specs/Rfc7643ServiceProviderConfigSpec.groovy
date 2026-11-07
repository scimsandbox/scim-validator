package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7643: SCIM Core Schema
 *
 * One feature per requirement of requirements-rfc7643.yaml (area "ServiceProviderConfig"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7643ServiceProviderConfigSpec extends ScimBaseSpec {

    // RFC 7643 section 5 (Service Provider Configuration Schema), MAY, lines 1495-1495
    // OPTIONAL.
    @SpecRef("RFC7643-5-77ab3c")
    def "RFC7643-5-77ab3c: documentationUri: OPTIONAL."() {
        expect:
        def value = spcValue("documentationUri")
        if (value != null) {
            assert value instanceof String && (value =~ /^https?:\/\/\S+$/).matches() : "RFC 7643 5: documentationUri must be an HTTP-addressable URL but was ${value}"
        }
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1499-1499
    // REQUIRED.
    @SpecRef("RFC7643-5-9b14e3")
    def "RFC7643-5-9b14e3: patch: REQUIRED."() {
        expect:
        def value = spcValue("patch")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'patch' is REQUIRED but is missing"
        assert value instanceof Map : "RFC 7643 5: 'patch' must be of type object but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1502-1502
    // REQUIRED.
    @SpecRef("RFC7643-5-4938d6")
    def "RFC7643-5-4938d6: patch.supported: REQUIRED."() {
        expect:
        def value = spcValue("patch.supported")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'patch.supported' is REQUIRED but is missing"
        assert value instanceof Boolean : "RFC 7643 5: 'patch.supported' must be of type boolean but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1506-1506
    // REQUIRED.
    @SpecRef("RFC7643-5-263233")
    def "RFC7643-5-263233: bulk: REQUIRED."() {
        expect:
        def value = spcValue("bulk")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'bulk' is REQUIRED but is missing"
        assert value instanceof Map : "RFC 7643 5: 'bulk' must be of type object but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1509-1509
    // REQUIRED.
    @SpecRef("RFC7643-5-cff4e6")
    def "RFC7643-5-cff4e6: bulk.supported: REQUIRED."() {
        expect:
        def value = spcValue("bulk.supported")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'bulk.supported' is REQUIRED but is missing"
        assert value instanceof Boolean : "RFC 7643 5: 'bulk.supported' must be of type boolean but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1520-1520
    // REQUIRED.
    @SpecRef("RFC7643-5-201855")
    def "RFC7643-5-201855: bulk.maxOperations: REQUIRED."() {
        expect:
        def value = spcValue("bulk.maxOperations")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'bulk.maxOperations' is REQUIRED but is missing"
        assert value instanceof Integer || value instanceof Long : "RFC 7643 5: 'bulk.maxOperations' must be of type integer but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1523-1523
    // REQUIRED.
    @SpecRef("RFC7643-5-d1bbea")
    def "RFC7643-5-d1bbea: bulk.maxPayloadSize: REQUIRED."() {
        expect:
        def value = spcValue("bulk.maxPayloadSize")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'bulk.maxPayloadSize' is REQUIRED but is missing"
        assert value instanceof Integer || value instanceof Long : "RFC 7643 5: 'bulk.maxPayloadSize' must be of type integer but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1526-1526
    // REQUIRED.
    @SpecRef("RFC7643-5-c251bc")
    def "RFC7643-5-c251bc: filter: REQUIRED."() {
        expect:
        def value = spcValue("filter")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'filter' is REQUIRED but is missing"
        assert value instanceof Map : "RFC 7643 5: 'filter' must be of type object but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1530-1530
    // REQUIRED.
    @SpecRef("RFC7643-5-d95e90")
    def "RFC7643-5-d95e90: filter.supported: REQUIRED."() {
        expect:
        def value = spcValue("filter.supported")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'filter.supported' is REQUIRED but is missing"
        assert value instanceof Boolean : "RFC 7643 5: 'filter.supported' must be of type boolean but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1533-1533
    // REQUIRED.
    @SpecRef("RFC7643-5-736962")
    def "RFC7643-5-736962: filter.maxResults: REQUIRED."() {
        expect:
        def value = spcValue("filter.maxResults")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'filter.maxResults' is REQUIRED but is missing"
        assert value instanceof Integer || value instanceof Long : "RFC 7643 5: 'filter.maxResults' must be of type integer but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1537-1537
    // REQUIRED.
    @SpecRef("RFC7643-5-39de0c")
    def "RFC7643-5-39de0c: changePassword: REQUIRED."() {
        expect:
        def value = spcValue("changePassword")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'changePassword' is REQUIRED but is missing"
        assert value instanceof Map : "RFC 7643 5: 'changePassword' must be of type object but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1540-1540
    // REQUIRED.
    @SpecRef("RFC7643-5-d73364")
    def "RFC7643-5-d73364: changePassword.supported: REQUIRED."() {
        expect:
        def value = spcValue("changePassword.supported")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'changePassword.supported' is REQUIRED but is missing"
        assert value instanceof Boolean : "RFC 7643 5: 'changePassword.supported' must be of type boolean but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1544-1544
    // REQUIRED.
    @SpecRef("RFC7643-5-250dbc")
    def "RFC7643-5-250dbc: sort: REQUIRED."() {
        expect:
        def value = spcValue("sort")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'sort' is REQUIRED but is missing"
        assert value instanceof Map : "RFC 7643 5: 'sort' must be of type object but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1547-1547
    // REQUIRED.
    @SpecRef("RFC7643-5-d408c6")
    def "RFC7643-5-d408c6: sort.supported: REQUIRED."() {
        expect:
        def value = spcValue("sort.supported")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'sort.supported' is REQUIRED but is missing"
        assert value instanceof Boolean : "RFC 7643 5: 'sort.supported' must be of type boolean but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1551-1551
    // REQUIRED.
    @SpecRef("RFC7643-5-7ec4fa")
    def "RFC7643-5-7ec4fa: etag: REQUIRED."() {
        expect:
        def value = spcValue("etag")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'etag' is REQUIRED but is missing"
        assert value instanceof Map : "RFC 7643 5: 'etag' must be of type object but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1554-1554
    // REQUIRED.
    @SpecRef("RFC7643-5-fc78b3")
    def "RFC7643-5-fc78b3: etag.supported: REQUIRED."() {
        expect:
        def value = spcValue("etag.supported")
        assert value != null : "RFC 7643 5: ServiceProviderConfig attribute 'etag.supported' is REQUIRED but is missing"
        assert value instanceof Boolean : "RFC 7643 5: 'etag.supported' must be of type boolean but was ${value}"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), SHOULD, lines 1580-1583
    // To enable seamless discovery of configurations, the service provider SHOULD, with the appropriate security considerations, make the authenticationSchemes attribute publicly accessible without prior authentication.
    @SpecRef("RFC7643-5-ef6dd9")
    def "RFC7643-5-ef6dd9: authenticationSchemes: To enable seamless discovery of configurations, the service provider SHOULD, with..."() {
        expect:
        Response response = scimRequestAnonymous().get("/ServiceProviderConfig")
        assert response.statusCode() == 200 : "RFC 7643 5: authenticationSchemes SHOULD be publicly accessible without prior authentication, but an unauthenticated GET /ServiceProviderConfig returned ${response.statusCode()}"
        def schemes = response.jsonPath().getList("authenticationSchemes")
        assert schemes != null && !schemes.isEmpty() : "RFC 7643 5: unauthenticated GET /ServiceProviderConfig must expose authenticationSchemes"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1583-1583
    // REQUIRED.
    @SpecRef("RFC7643-5-14bb62")
    def "RFC7643-5-14bb62: authenticationSchemes: REQUIRED."() {
        expect:
        def schemes = spcValue("authenticationSchemes")
        assert schemes != null : "RFC 7643 5: authenticationSchemes is REQUIRED but is missing"
        assert schemes instanceof List && !schemes.isEmpty() : "RFC 7643 5: authenticationSchemes must be a non-empty multi-valued attribute but was ${schemes}"
        assert schemes.every { it instanceof Map } : "RFC 7643 5: authenticationSchemes values must be complex objects"
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1588-1588
    // REQUIRED.
    @SpecRef("RFC7643-5-f6e486")
    def "RFC7643-5-f6e486: authenticationSchemes.type: REQUIRED."() {
        expect:
        List schemes = spcValue("authenticationSchemes") as List
        assert schemes != null && !schemes.isEmpty() : "RFC 7643 5: authenticationSchemes is REQUIRED and must not be empty"
        schemes.each { s ->
            def v = s instanceof Map ? s["type"] : null
            assert v != null : "RFC 7643 5: authenticationSchemes.type is REQUIRED but missing in ${s}"
            assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 5: authenticationSchemes.type must be a non-empty string but was ${v}"
        }
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1591-1591
    // REQUIRED.
    @SpecRef("RFC7643-5-dccaeb")
    def "RFC7643-5-dccaeb: authenticationSchemes.name: REQUIRED."() {
        expect:
        List schemes = spcValue("authenticationSchemes") as List
        assert schemes != null && !schemes.isEmpty() : "RFC 7643 5: authenticationSchemes is REQUIRED and must not be empty"
        schemes.each { s ->
            def v = s instanceof Map ? s["name"] : null
            assert v != null : "RFC 7643 5: authenticationSchemes.name is REQUIRED but missing in ${s}"
            assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 5: authenticationSchemes.name must be a non-empty string but was ${v}"
        }
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MUST, lines 1594-1594
    // REQUIRED.
    @SpecRef("RFC7643-5-56cd30")
    def "RFC7643-5-56cd30: authenticationSchemes.description: REQUIRED."() {
        expect:
        List schemes = spcValue("authenticationSchemes") as List
        assert schemes != null && !schemes.isEmpty() : "RFC 7643 5: authenticationSchemes is REQUIRED and must not be empty"
        schemes.each { s ->
            def v = s instanceof Map ? s["description"] : null
            assert v != null : "RFC 7643 5: authenticationSchemes.description is REQUIRED but missing in ${s}"
            assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 5: authenticationSchemes.description must be a non-empty string but was ${v}"
        }
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MAY, lines 1597-1597
    // OPTIONAL.
    @SpecRef("RFC7643-5-ea5a12")
    def "RFC7643-5-ea5a12: authenticationSchemes.specUri: OPTIONAL."() {
        expect:
        List schemes = spcValue("authenticationSchemes") as List
        assert schemes != null && !schemes.isEmpty() : "RFC 7643 5: authenticationSchemes is REQUIRED and must not be empty"
        def present = schemes.findAll { it instanceof Map && it["specUri"] != null }
        present.each { s ->
            def v = s["specUri"]
            assert v instanceof String && (v =~ /^https?:\/\/\S+$/).matches() : "RFC 7643 5: authenticationSchemes.specUri must be an HTTP-addressable URL but was ${v}"
        }
    }

    // RFC 7643 section 5 (Service Provider Configuration Schema), MAY, lines 1600-1600
    // OPTIONAL.
    @SpecRef("RFC7643-5-1b1ef1")
    def "RFC7643-5-1b1ef1: authenticationSchemes.documentationUri: OPTIONAL."() {
        expect:
        List schemes = spcValue("authenticationSchemes") as List
        assert schemes != null && !schemes.isEmpty() : "RFC 7643 5: authenticationSchemes is REQUIRED and must not be empty"
        def present = schemes.findAll { it instanceof Map && it["documentationUri"] != null }
        present.each { s ->
            def v = s["documentationUri"]
            assert v instanceof String && (v =~ /^https?:\/\/\S+$/).matches() : "RFC 7643 5: authenticationSchemes.documentationUri must be an HTTP-addressable URL but was ${v}"
        }
    }

    /** GET /ServiceProviderConfig (authenticated) and resolve a dotted path; null when missing. */
    private Object spcValue(String path) {
        Response response = scimRequestQuiet().get("/ServiceProviderConfig")
        assert response.statusCode() == 200 : "RFC 7643 5: GET /ServiceProviderConfig must return 200 but was ${response.statusCode()}"
        Object cur = response.jsonPath().getMap("\$")
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map)) return null
            cur = ((Map) cur).get(part)
        }
        return cur
    }
}
