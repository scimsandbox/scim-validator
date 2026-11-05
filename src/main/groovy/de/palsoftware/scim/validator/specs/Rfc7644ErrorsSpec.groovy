package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import spock.lang.Ignore
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "HTTP status and error handling"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644ErrorsSpec extends ScimBaseSpec {

    // RFC 7644 section 3.12 (HTTP Status and Error Response Handling), MUST, lines 3703-3706
    // In addition to returning an HTTP response code, implementers MUST return the errors in the body of the response in a JSON format, using the attributes described below.
    @SpecRef("RFC7644-3.12-2ff503")
    def "RFC7644-3.12-2ff503: In addition to returning an HTTP response code, implementers MUST return the errors in the body of the..."() {
        given: "several different failing requests"
        List<Map> samples = sampleErrors()

        expect: "every error response carries a JSON SCIM error body in addition to the HTTP status"
        samples.each { Map s ->
            Response r = s.response as Response
            assert r.statusCode() >= 400 && r.statusCode() < 600 : "test setup: '${s.name}' should fail but status was ${r.statusCode()}"
            String mt = r.contentType()?.split(";")?.getAt(0)?.trim()?.toLowerCase()
            assert mt in ["application/scim+json", "application/json"] :
                "RFC 7644 3.12: error body for '${s.name}' must be JSON but Content-Type was ${r.contentType()}"
            assertScimError(r, r.statusCode())
        }
    }

    // RFC 7644 section 3.12 (HTTP Status and Error Response Handling), MUST, lines 3713-3713
    // REQUIRED.
    @SpecRef("RFC7644-3.12-f6e413")
    def "RFC7644-3.12-f6e413: REQUIRED."() {
        given: "several different failing requests"
        List<Map> samples = sampleErrors()

        expect: "every error body has a status attribute, as a JSON string, equal to the HTTP status code"
        samples.each { Map s ->
            Response r = s.response as Response
            Map body = errorBody(r, s.name as String)
            assert body.containsKey("status") : "RFC 7644 3.12: error body for '${s.name}' lacks the REQUIRED status attribute: ${body}"
            assert body.status instanceof String :
                "RFC 7644 3.12: status of '${s.name}' must be a JSON string but was ${body.status?.getClass()?.simpleName} (${body.status})"
            assert body.status == r.statusCode().toString() :
                "RFC 7644 3.12: status '${body.status}' of '${s.name}' differs from the HTTP status ${r.statusCode()}"
        }
    }

    // RFC 7644 section 3.12 (HTTP Status and Error Response Handling), MAY, lines 3716-3716
    // OPTIONAL.
    @SpecRef("RFC7644-3.12-4d3b9d")
    def "RFC7644-3.12-4d3b9d: OPTIONAL."() {
        given: "several different failing requests"
        List<Map> samples = sampleErrors()

        expect: "scimType is optional, and wherever the server sends it, it is a string Table 9 keyword"
        samples.each { Map s ->
            Map body = errorBody(s.response as Response, s.name as String)
            if (body.containsKey("scimType")) {
                assert body.scimType instanceof String && TABLE_9_KEYWORDS.contains(body.scimType) :
                    "RFC 7644 3.12: scimType '${body.scimType}' of '${s.name}' is not a keyword of Table 9 ${TABLE_9_KEYWORDS}"
            }
        }
    }

    // RFC 7644 section 3.12 (HTTP Status and Error Response Handling), MAY, lines 3719-3719
    // OPTIONAL.
    @SpecRef("RFC7644-3.12-79433f")
    def "RFC7644-3.12-79433f: OPTIONAL."() {
        given: "several different failing requests"
        List<Map> samples = sampleErrors()

        expect: "detail is optional, and wherever the server sends it, it is a readable string"
        samples.each { Map s ->
            Map body = errorBody(s.response as Response, s.name as String)
            if (body.containsKey("detail")) {
                assert body.detail instanceof String && !(body.detail as String).isBlank() :
                    "RFC 7644 3.12: detail of '${s.name}' must be a non-empty human-readable string but was ${body.detail}"
            }
        }
    }

    // RFC 7644 section 3.12 (HTTP Status and Error Response Handling), SHOULD, lines 3721-3722
    // Implementers SHOULD handle the identified HTTP status codes as described below.
    @SpecRef("RFC7644-3.12-c3e92c")
    def "RFC7644-3.12-c3e92c: Implementers SHOULD handle the identified HTTP status codes as described below."() {
        given: "a user that exists"
        Response created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: setup user must be created, status was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")

        when: "requests that provoke the documented error statuses are sent"
        Response badFilter = scimRequest().queryParam("filter", "userName bogus \"x\"").get("/Users")
        Response badSyntax = scimRequest().body('{"schemas": [').post("/Users")
        Response noAuth = scimRequestAnonymous().get("/Users/${id}")
        Response notFound = scimRequest().get("/Users/${UUID.randomUUID()}")
        Response duplicate = createUser(userName: userName)
        Response stale = isEtagSupported() ? scimRequest().header("If-Match", 'W/"w13-stale-version"')
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName])).put("/Users/${id}") : null

        then: "each is handled with the status of the table and a SCIM error body"
        assertScimError(badFilter, 400, "invalidFilter")
        assertScimError(badSyntax, 400, "invalidSyntax")
        assertScimError(noAuth, 401)
        assertScimError(notFound, 404)
        assertScimError(duplicate, 409, "uniqueness")
        if (stale != null) {
            assertScimError(stale, 412)
        }
    }

    // RFC 7644 section 3.12 (HTTP Status and Error Response Handling), MAY, lines 3910-3912
    // Note that in Table 9 above, the information in the Applicability column applies to the normal HTTP method but MAY apply within a SCIM bulk operation (via HTTP POST).
    @SpecRef("RFC7644-3.12-175ffa")
    def "RFC7644-3.12-175ffa: Note that in Table 9 above, the information in the Applicability column applies to the normal HTTP method..."() {
        given: "a server with bulk support and a user name that will be created twice inside one bulk request"
        if (!isBulkSupported()) {
            throw new TestAbortedException("Server does not support bulk, so errors inside bulk operations cannot be observed")
        }
        String userName = "w13bulk-${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map userData = [schemas: [USER_SCHEMA], userName: userName]

        when: "a bulk request contains a successful create, a duplicate create and a delete of a missing user"
        Response r = scimRequest().body(JsonOutput.toJson([
            schemas   : [BULK_REQUEST_SCHEMA],
            Operations: [
                [method: "POST", path: "/Users", bulkId: "first", data: userData],
                [method: "POST", path: "/Users", bulkId: "dup", data: userData],
                [method: "DELETE", path: "/Users/${UUID.randomUUID()}", bulkId: "gone"]
            ]
        ])).post("/Bulk")
        Map body = r.statusCode() == 200 ? new JsonSlurper().parseText(r.asString()) as Map : [:]
        Map byId = (body.Operations as List ?: []).collectEntries { [(it.bulkId): it] }
        String createdId = (byId.first?.location as String)?.tokenize("/")?.last()
        if (createdId) createdUserIds << createdId

        then: "the errors of the table apply to the failed operations: 409 uniqueness and 404, each with an error response"
        assert r.statusCode() == 200 : "RFC 7644 3.7: bulk request must return 200 but was ${r.statusCode()}: ${r.asString()?.take(300)}"
        assert byId.first?.status == "201" : "RFC 7644 3.7: first bulk create must give status 201 but was ${byId.first?.status}"
        assert byId.dup?.status == "409" : "RFC 7644 3.12: duplicate userName inside a bulk operation must give status 409 but was ${byId.dup?.status}"
        assert byId.dup.response?.status == "409" && byId.dup.response?.scimType == "uniqueness" :
            "RFC 7644 3.12: bulk operation error response must carry status 409 and scimType uniqueness: ${byId.dup.response}"
        assert (byId.dup.response?.schemas as List)?.contains(ERROR_SCHEMA) : "RFC 7644 3.12: bulk operation error response must be a SCIM Error: ${byId.dup.response}"
        assert byId.gone?.status == "404" : "RFC 7644 3.12: deleting a missing user inside a bulk operation must give status 404 but was ${byId.gone?.status}"
        assert byId.gone.response?.status == "404" : "RFC 7644 3.12: bulk operation error response must carry status 404: ${byId.gone.response}"
    }

    private static final List<String> TABLE_9_KEYWORDS = [
        "invalidFilter", "tooMany", "uniqueness", "mutability", "invalidSyntax",
        "invalidPath", "noTarget", "invalidValue", "invalidVers", "sensitive"
    ]

    /** Failing requests of different kinds: [name:, response:]. */
    private List<Map> sampleErrors() {
        List<Map> samples = []
        samples << [name: "GET of a missing user", response: scimRequest().get("/Users/${UUID.randomUUID()}")]
        samples << [name: "invalid filter", response: scimRequest().queryParam("filter", "userName bogus \"x\"").get("/Users")]
        samples << [name: "POST without userName", response: scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA]])).post("/Users")]
        samples << [name: "request without credentials", response: scimRequestAnonymous().get("/Users")]
        Response first = createUser()
        if (first.statusCode() == 201) {
            samples << [name: "duplicate userName", response: createUser(userName: first.jsonPath().getString("userName"))]
        }
        return samples
    }

    /** The parsed JSON error body of a response (fails if the body is not a JSON object). */
    private static Map errorBody(Response r, String name) {
        String raw = r.asString()
        assert raw != null && !raw.isBlank() : "RFC 7644 3.12: error response for '${name}' (HTTP ${r.statusCode()}) has an empty body"
        def parsed
        try {
            parsed = new JsonSlurper().parseText(raw)
        } catch (Exception e) {
            assert false : "RFC 7644 3.12: error body for '${name}' is not JSON (${e.message}): ${raw.take(200)}"
        }
        assert parsed instanceof Map : "RFC 7644 3.12: error body for '${name}' must be a JSON object: ${raw.take(200)}"
        return parsed as Map
    }
}
