package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import org.opentest4j.TestAbortedException
import io.restassured.response.Response

/**
 * RFC 9865: SCIM Cursor-Based Pagination
 *
 * One feature per requirement of requirements-rfc9865.yaml (area "ServiceProviderConfig"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc9865ServiceProviderConfigSpec extends ScimBaseSpec {

    // RFC 9865 section 4 (Service Provider Configuration), SHOULD, lines 374-377
    // A SCIM service provider implementing cursor-based pagination SHOULD include the following additional attribute in a JSON document returned by the /ServiceProviderConfig endpoint:
    @SpecRef("RFC9865-4-16cf55")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-16cf55: A SCIM service provider implementing cursor-based pagination SHOULD include the following additional..."() {
        when:
        def pagination = loadPagination()

        then:
        assert pagination instanceof Map :
            "RFC 9865 4: a service provider implementing cursor pagination should include the pagination attribute in /ServiceProviderConfig but it was ${pagination}"
    }

    // RFC 9865 section 4 (Service Provider Configuration), MAY, lines 380-380
    // OPTIONAL.
    @SpecRef("RFC9865-4-083c69")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-083c69: pagination: OPTIONAL."() {
        when:
        def pagination = loadPagination()

        then: "when present it is a complex attribute"
        if (pagination != null) {
            assert pagination instanceof Map :
                "RFC 9865 4: pagination is a complex type but was ${pagination.getClass().simpleName}"
        }
    }

    // RFC 9865 section 4 (Service Provider Configuration), MUST, lines 385-385
    // REQUIRED.
    @SpecRef("RFC9865-4-31de16")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-31de16: cursor: REQUIRED."() {
        when:
        def pagination = requirePaginationMap()

        then:
        assert pagination.containsKey("cursor") :
            "RFC 9865 4: pagination.cursor is REQUIRED but missing"
        assert pagination.cursor instanceof Boolean :
            "RFC 9865 4: pagination.cursor must be a Boolean but was ${pagination.cursor}"
    }

    // RFC 9865 section 4 (Service Provider Configuration), MUST, lines 388-388
    // REQUIRED.
    @SpecRef("RFC9865-4-b9b16e")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-b9b16e: index: REQUIRED."() {
        when:
        def pagination = requirePaginationMap()

        then:
        assert pagination.containsKey("index") :
            "RFC 9865 4: pagination.index is REQUIRED but missing"
        assert pagination.index instanceof Boolean :
            "RFC 9865 4: pagination.index must be a Boolean but was ${pagination.index}"
    }

    // RFC 9865 section 4 (Service Provider Configuration), MAY, lines 393-393
    // OPTIONAL.
    @SpecRef("RFC9865-4-241864")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-241864: defaultPaginationMethod: OPTIONAL."() {
        when:
        Map pagination = requirePaginationMap()

        then: "the sub-attribute carries the name defined by RFC 9865, and when published, it is cursor or index and names a method that is supported"
        List<String> lookalikes = pagination.keySet().findAll { String k ->
            k.toLowerCase().startsWith("defaultpagination") && k != "defaultPaginationMethod"
        }.toList()
        assert lookalikes.isEmpty() :
            "RFC 9865 4: the sub-attribute is named defaultPaginationMethod but the server returns ${lookalikes}"
        if (pagination.containsKey("defaultPaginationMethod")) {
            String method = pagination.defaultPaginationMethod
            assert method in ["cursor", "index"] :
                "RFC 9865 4: defaultPaginationMethod must be 'cursor' or 'index' but was ${method}"
            assert pagination[method] == true :
                "RFC 9865 4: defaultPaginationMethod '${method}' must be a method the server supports (pagination.${method} is ${pagination[method]})"
        }
    }

    // RFC 9865 section 4 (Service Provider Configuration), MAY, lines 397-397
    // OPTIONAL.
    @SpecRef("RFC9865-4-3328c7")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-3328c7: defaultPageSize: OPTIONAL."() {
        when:
        Map pagination = requirePaginationMap()

        then:
        if (pagination.containsKey("defaultPageSize")) {
            def value = pagination.defaultPageSize
            assert (value instanceof Integer || value instanceof Long) && value > 0 :
                "RFC 9865 4: defaultPageSize must be a positive integer but was ${value}"
        }
    }

    // RFC 9865 section 4 (Service Provider Configuration), MAY, lines 402-402
    // OPTIONAL.
    @SpecRef("RFC9865-4-0f750c")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-0f750c: maxPageSize: OPTIONAL."() {
        when:
        Map pagination = requirePaginationMap()

        then:
        if (pagination.containsKey("maxPageSize")) {
            def value = pagination.maxPageSize
            assert (value instanceof Integer || value instanceof Long) && value > 0 :
                "RFC 9865 4: maxPageSize must be a positive integer but was ${value}"
        }
    }

    // RFC 9865 section 4 (Service Provider Configuration), MAY, lines 409-409
    // OPTIONAL.
    @SpecRef("RFC9865-4-710a4e")
    @RequiresCapability("cursorPagination")
    def "RFC9865-4-710a4e: cursorTimeout: OPTIONAL."() {
        when:
        Map pagination = requirePaginationMap()

        then:
        if (pagination.containsKey("cursorTimeout")) {
            def value = pagination.cursorTimeout
            assert (value instanceof Integer || value instanceof Long) && value > 0 :
                "RFC 9865 4: cursorTimeout must be a positive integer but was ${value}"
        }
    }

    private Object loadPagination() {
        Response response = scimRequest().get("/ServiceProviderConfig")
        assert response.statusCode() == 200 :
            "RFC 7644 4: GET /ServiceProviderConfig must return 200 but was ${response.statusCode()}"
        return response.jsonPath().get("pagination")
    }

    private Map requirePaginationMap() {
        def pagination = loadPagination()
        if (pagination == null) {
            throw new TestAbortedException("Server does not publish the pagination attribute")
        }
        assert pagination instanceof Map : "RFC 9865 4: pagination must be a complex attribute but was ${pagination}"
        return (Map) pagination
    }
}
