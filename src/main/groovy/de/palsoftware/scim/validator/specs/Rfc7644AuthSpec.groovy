package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Ignore

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "Authentication and authorization"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644AuthSpec extends ScimBaseSpec {

    // RFC 7644 section 2 (Authentication and Authorization), MAY, lines 253-255
    // Bearer Tokens Bearer tokens [RFC6750] MAY be used when combined with TLS and a token framework such as OAuth 2.0 [RFC6749].
    @SpecRef("RFC7644-2-9e4c19")
    def "RFC7644-2-9e4c19: Bearer Tokens Bearer tokens [RFC6750] MAY be used when combined with TLS and a token framework such as..."() {
        given: "the advertised authentication schemes"
        Response spc = scimRequest().get("/ServiceProviderConfig")
        List<Map> schemes = spc.jsonPath().getList("authenticationSchemes") ?: []
        if (!schemes.any { it.type == "oauthbearertoken" }) {
            throw new TestAbortedException("Server does not advertise the oauthbearertoken authentication scheme")
        }

        when: "a request carries the valid bearer token and another one an invalid bearer token"
        Response valid = scimRequest().get("/Users?count=1")
        Response invalid = scimRequestAnonymous().header("Authorization", "Bearer invalid-${UUID.randomUUID()}").get("/Users?count=1")

        then: "the valid bearer token is accepted and the invalid one is not"
        assert valid.statusCode() == 200 : "RFC 7644 2: server advertises oauthbearertoken but rejected a valid bearer token, status ${valid.statusCode()}"
        assert invalid.statusCode() == 401 : "RFC 7644 2: invalid bearer token must be rejected with 401 but was ${invalid.statusCode()}"
    }

    // RFC 7644 section 2 (Authentication and Authorization), SHOULD NOT, lines 255-259
    // Tokens that are issued based on weak or no authentication of authorizing users and/or OAuth clients SHOULD NOT be used, unless, for example, they are being used as single-use tokens to permit one-time requests such as anonymous registration (see Section 3.3).
    @SpecRef("RFC7644-2-556b3a")
    def "RFC7644-2-556b3a: Tokens that are issued based on weak or no authentication of authorizing users and/or OAuth clients SHOULD..."() {
        given: "weak or empty credentials"
        List<String> weak = ["Bearer ", "Bearer null", "Bearer undefined", "Bearer 0", "Bearer admin", "Bearer test", "bearer"]

        expect: "none of them is accepted for reading"
        weak.each { String header ->
            Response r = scimRequestAnonymous().header("Authorization", header).get("/Users?count=1")
            assert r.statusCode() == 401 : "RFC 7644 2: weak credential '${header}' must not be accepted, status ${r.statusCode()}"
        }

        and: "an anonymous request cannot create a resource"
        Response anon = scimRequestAnonymous().body(JsonOutput.toJson([
            schemas : [USER_SCHEMA],
            userName: "w7_weak_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        ])).post("/Users")
        if (anon.statusCode() == 201) {
            String id = anon.jsonPath().getString("id")
            if (id) createdUserIds << id
        }
        assert anon.statusCode() == 401 : "RFC 7644 2: anonymous create must be rejected with 401 but was ${anon.statusCode()}"
    }

    // RFC 7644 section 2 (Authentication and Authorization), MUST, lines 307-309
    // As per Section 4.1 of [RFC7235], a SCIM service provider SHALL indicate supported HTTP authentication schemes via the "WWW-Authenticate" header.
    @SpecRef("RFC7644-2-a8b9bb")
    def "RFC7644-2-a8b9bb: As per Section 4.1 of [RFC7235], a SCIM service provider SHALL indicate supported HTTP authentication..."() {
        given: "the authentication schemes the server advertises"
        List<Map> schemes = scimRequest().get("/ServiceProviderConfig").jsonPath().getList("authenticationSchemes") ?: []
        assert !schemes.isEmpty() : "RFC 7644 2: ServiceProviderConfig must advertise authenticationSchemes"

        when: "an unauthenticated request is made"
        Response response = scimRequestAnonymous().get("/Users")

        then: "the 401 response names a supported scheme in WWW-Authenticate"
        assert response.statusCode() == 401 : "RFC 7644 2: unauthenticated request must be rejected with 401 but was ${response.statusCode()}"
        String challenge = response.header("WWW-Authenticate")
        assert challenge != null && !challenge.isBlank() : "RFC 7644 2 / RFC 7235 4.1: 401 response must carry a WWW-Authenticate header"
        Map<String, String> challengeFor = [oauthbearertoken: "bearer", httpbasic: "basic", httpdigest: "digest", oauth: "bearer"]
        List<String> expectedSchemes = schemes.collect { challengeFor[it.type as String] }.findAll { it }
        if (!expectedSchemes.isEmpty()) {
            assert expectedSchemes.any { challenge.toLowerCase().startsWith(it) || challenge.toLowerCase().contains(it) } :
                "RFC 7644 2: WWW-Authenticate '${challenge}' does not indicate any advertised scheme ${expectedSchemes}"
        }
    }

    // RFC 7644 section 2 (Authentication and Authorization), MUST, lines 311-314
    // Regardless of methodology, the SCIM service provider MUST be able to map the authenticated client to an access control policy in order to determine the client's authorization to retrieve and update SCIM resources.
    @SpecRef("RFC7644-2-b897c4")
    def "RFC7644-2-b897c4: Regardless of methodology, the SCIM service provider MUST be able to map the authenticated client to an..."() {
        given: "a client holding the valid token, and one without it"
        String userName = "w7_acl_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

        when: "the authenticated client creates and reads a User"
        Response created = createUser(userName: userName)
        String id = created.statusCode() == 201 ? created.jsonPath().getString("id") : null
        Response read = id ? scimRequest().get("/Users/${id}") : null

        and: "unauthenticated and wrongly authenticated clients try the same"
        Response anonCreate = scimRequestAnonymous().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: "w7_acl_anon_${UUID.randomUUID().toString().substring(0, 8)}@test.com"])).post("/Users")
        if (anonCreate.statusCode() == 201) {
            String anonId = anonCreate.jsonPath().getString("id")
            if (anonId) createdUserIds << anonId
        }
        Response anonRead = id ? scimRequestAnonymous().get("/Users/${id}") : null
        Response badRead = id ? scimRequestAnonymous().header("Authorization", "Bearer invalid-${UUID.randomUUID()}").get("/Users/${id}") : null

        then: "the authenticated client is authorized to retrieve and update, the others are not"
        assert created.statusCode() == 201 : "RFC 7644 2: authenticated client must be authorized to create, status ${created.statusCode()}"
        assert read.statusCode() == 200 : "RFC 7644 2: authenticated client must be authorized to retrieve, status ${read.statusCode()}"
        assert anonCreate.statusCode() == 401 : "RFC 7644 2: unauthenticated client must not be authorized to create, status ${anonCreate.statusCode()}"
        assert anonRead.statusCode() == 401 : "RFC 7644 2: unauthenticated client must not be authorized to retrieve, status ${anonRead.statusCode()}"
        assert badRead.statusCode() == 401 : "RFC 7644 2: client with an unknown token must not be authorized, status ${badRead.statusCode()}"
    }

    // RFC 7644 section 2.2 (Anonymous Requests), MAY, lines 382-386
    // In some SCIM deployments, it MAY be acceptable to permit unauthenticated (anonymous) requests -- for example, a user self-registration request where the service provider chooses to accept a SCIM Create request (see Section 3.3) from an anonymous client.
    @SpecRef("RFC7644-2.2-59c61f")
    def "RFC7644-2.2-59c61f: In some SCIM deployments, it MAY be acceptable to permit unauthenticated (anonymous) requests -- for..."() {
        when: "an anonymous client sends a Create request"
        String userName = "w7_anon_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Response response = scimRequestAnonymous().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName])).post("/Users")
        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) createdUserIds << id
        }

        then: "the server either accepts the anonymous Create (201) or rejects it with 401"
        assert response.statusCode() in [201, 401] : "RFC 7644 2.2: anonymous Create must be answered with 201 when accepted, or 401 when not, but was ${response.statusCode()}"
        if (response.statusCode() == 201) {
            assert response.jsonPath().getString("id") : "RFC 7644 2.2: accepted anonymous Create must return the created resource with an id"
            assert response.jsonPath().getString("userName") == userName : "RFC 7644 2.2: created resource must carry the requested userName"
        } else {
            assertScimError(response, 401)
        }
    }

}
