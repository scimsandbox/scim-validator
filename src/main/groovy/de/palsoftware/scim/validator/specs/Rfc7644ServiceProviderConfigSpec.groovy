package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import org.opentest4j.TestAbortedException
import io.restassured.response.Response

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "ServiceProviderConfig"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644ServiceProviderConfigSpec extends ScimBaseSpec {

    // RFC 7644 section 4 (Service Provider Configuration Endpoints), MAY, lines 4059-4060
    // SCIM defines three endpoints to facilitate discovery of SCIM service provider features and schema that MAY be retrieved using HTTP GET:
    @SpecRef("RFC7644-4-2b0f28")
    def "RFC7644-4-2b0f28: SCIM defines three endpoints to facilitate discovery of SCIM service provider features and schema that MAY..."() {
        expect: "each discovery endpoint that the server offers answers an HTTP GET with a JSON object"
        ["/ServiceProviderConfig", "/Schemas", "/ResourceTypes"].each { String path ->
            Response response = scimRequest().get(path)
            if (response.statusCode() != 200) {
                throw new TestAbortedException("Server does not offer GET ${path} (HTTP ${response.statusCode()}); retrieval is a MAY")
            }
            def root = response.jsonPath().get("")
            assert root instanceof Map :
                "RFC 7644 4: GET ${path} must return a JSON object but returned ${root?.getClass()?.simpleName}"
            assert response.contentType()?.toLowerCase()?.contains("json") :
                "RFC 7644 4: GET ${path} must return a JSON media type but was ${response.contentType()}"
            List schemas = response.jsonPath().getList("schemas")
            assert schemas != null && !schemas.isEmpty() :
                "RFC 7644 4: the JSON object returned by GET ${path} must carry a schemas attribute"
        }
    }

    // RFC 7644 section 4 (Service Provider Configuration Endpoints), MUST, lines 4065-4067
    // This endpoint SHALL return responses with a JSON object using a "schemas" attribute of "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig".
    @SpecRef("RFC7644-4-9c8720")
    def "RFC7644-4-9c8720: This endpoint SHALL return responses with a JSON object using a 'schemas' attribute of..."() {
        when: "the ServiceProviderConfig endpoint is retrieved"
        Response response = scimRequest().get("/ServiceProviderConfig")

        then: "the JSON object uses the ServiceProviderConfig schema URI"
        assert response.statusCode() == 200 :
            "RFC 7644 4: GET /ServiceProviderConfig must return 200 but was ${response.statusCode()}"
        assert response.jsonPath().get("") instanceof Map :
            "RFC 7644 4: GET /ServiceProviderConfig must return a JSON object"
        List schemas = response.jsonPath().getList("schemas")
        assert schemas == [SPC_SCHEMA] :
            "RFC 7644 4: schemas must be [${SPC_SCHEMA}] but was ${schemas}"
    }

    // RFC 7644 section 4 (Service Provider Configuration Endpoints), MUST, lines 4094-4096
    // An HTTP GET to the endpoint "/Schemas" SHALL return all supported schemas in ListResponse format (see Figure 3).
    @SpecRef("RFC7644-4-6dbf7b")
    def "RFC7644-4-6dbf7b: An HTTP GET to the endpoint '/Schemas' SHALL return all supported schemas in ListResponse format (see..."() {
        when: "the Schemas endpoint is retrieved"
        Response response = scimRequest().get("/Schemas")

        then: "a ListResponse with all supported schemas is returned"
        assert response.statusCode() == 200 :
            "RFC 7644 4: GET /Schemas must return 200 but was ${response.statusCode()}"
        assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA) :
            "RFC 7644 4: GET /Schemas must use the ListResponse schema but schemas was ${response.jsonPath().getList('schemas')}"
        List resources = response.jsonPath().getList("Resources")
        assert resources != null && !resources.isEmpty() :
            "RFC 7644 4: GET /Schemas must return the supported schemas in Resources"
        assert asInt(response.jsonPath().get("totalResults"), -1) == resources.size() :
            "RFC 7644 4: totalResults ${response.jsonPath().get('totalResults')} must equal the number of schemas returned (${resources.size()}), all schemas are returned"
        List<String> ids = response.jsonPath().getList("Resources.id")
        assert ids.contains(USER_SCHEMA) && ids.contains(GROUP_SCHEMA) :
            "RFC 7644 4: GET /Schemas must return the core User and Group schemas but returned ${ids}"
    }

    // RFC 7644 section 4 (Service Provider Configuration Endpoints), MUST, lines 4117-4121
    // When returning multiple ResourceTypes or Schemas, the message form described by the "urn:ietf:params:scim:api:messages:2.0:ListResponse" (ListResponse) form SHALL be used as shown in Figure 3 and in Figure 9 below.
    @SpecRef("RFC7644-4-85421e")
    def "RFC7644-4-85421e: When returning multiple ResourceTypes or Schemas, the message form described by the..."() {
        expect: "both multi-valued discovery endpoints answer in ListResponse form"
        ["/Schemas", "/ResourceTypes"].each { String path ->
            Response response = scimRequest().get(path)
            assert response.statusCode() == 200 :
                "RFC 7644 4: GET ${path} must return 200 but was ${response.statusCode()}"
            assert response.jsonPath().getList("schemas")?.contains(LIST_RESPONSE_SCHEMA) :
                "RFC 7644 4: GET ${path} must use ${LIST_RESPONSE_SCHEMA} but schemas was ${response.jsonPath().getList('schemas')}"
            assert response.jsonPath().get("Resources") instanceof List :
                "RFC 7644 4: GET ${path} ListResponse must carry a Resources array"
            assert response.jsonPath().get("totalResults") instanceof Integer :
                "RFC 7644 4: GET ${path} ListResponse must carry an integer totalResults but was ${response.jsonPath().get('totalResults')}"
        }
    }

    // RFC 7644 section 4 (Service Provider Configuration Endpoints), MUST, lines 4121-4122
    // Query parameters described in Section 3.4.2, such as filtering, sorting, and pagination, SHALL be ignored.
    @SpecRef("RFC7644-4-a7b711")
    def "RFC7644-4-a7b711: Query parameters described in Section 3.4.2, such as filtering, sorting, and pagination, SHALL be ignored."() {
        expect: "pagination and sorting parameters have no effect on the discovery lists"
        ["/Schemas", "/ResourceTypes"].each { String path ->
            Response plain = scimRequest().get(path)
            Response withParams = scimRequest()
                .queryParam("count", 1)
                .queryParam("startIndex", 2)
                .queryParam("sortBy", "id")
                .queryParam("sortOrder", "descending")
                .get(path)
            assert plain.statusCode() == 200 : "RFC 7644 4: GET ${path} must return 200 but was ${plain.statusCode()}"
            assert withParams.statusCode() == 200 :
                "RFC 7644 4: GET ${path} with query parameters must return 200 (parameters are ignored) but was ${withParams.statusCode()}"
            assert withParams.jsonPath().getList("Resources.id") == plain.jsonPath().getList("Resources.id") :
                "RFC 7644 4: GET ${path} with count/startIndex/sortBy must return the same resources in the same order as without them"
            assert withParams.jsonPath().get("totalResults") == plain.jsonPath().get("totalResults") :
                "RFC 7644 4: totalResults of GET ${path} must not change with query parameters"
        }
    }

    // RFC 7644 section 4 (Service Provider Configuration Endpoints), SHOULD, lines 4122-4125
    // If a "filter" is provided, the service provider SHOULD respond with HTTP status code 403 (Forbidden) to ensure that clients cannot incorrectly assume that any matching conditions specified in a filter are true.
    @SpecRef("RFC7644-4-54391c")
    def "RFC7644-4-54391c: If a 'filter' is provided, the service provider SHOULD respond with HTTP status code 403 (Forbidden) to..."() {
        expect: "a filter on a discovery list is answered with 403"
        ["/Schemas", "/ResourceTypes"].each { String path ->
            Response response = scimRequest()
                .queryParam("filter", 'id eq "nothing-matches-this"')
                .get(path)
            assert response.statusCode() == 403 :
                "RFC 7644 4: GET ${path} with a filter should return 403 but was ${response.statusCode()}"
        }
    }


}
