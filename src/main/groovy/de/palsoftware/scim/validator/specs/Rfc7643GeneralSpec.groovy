package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.SpecRef
import org.opentest4j.TestAbortedException

/**
 * RFC 7643: SCIM Core Schema
 *
 * One feature per requirement of requirements-rfc7643.yaml (area "General").
 */
class Rfc7643GeneralSpec extends ScimBaseSpec {

    // RFC 7643 section 1.2 (Definitions), MAY, lines 276-277
    // Service provider schemas MAY be returned from the "/Schemas" endpoint.
    @SpecRef("RFC7643-1.2-b6ad9c")
    def "RFC7643-1.2-b6ad9c: Service provider schemas MAY be returned from the '/Schemas' endpoint."() {
        when:
        def response = scimRequestQuiet().get("/Schemas")

        then:
        if (response.statusCode() == 404 || response.statusCode() == 501) {
            throw new TestAbortedException("Server does not offer the /Schemas endpoint (HTTP ${response.statusCode()})")
        }
        assert response.statusCode() == 200 : "RFC 7643 1.2/8.7: GET /Schemas offered but status was ${response.statusCode()}"
        List resources = response.jsonPath().getList("Resources")
        assert resources != null && !resources.isEmpty() : "RFC 7643 1.2: /Schemas must return schema definitions in Resources"
        assert resources.every { it.id } : "RFC 7643 8.7: every returned schema must carry an id"
        assert resources.any { it.id == USER_SCHEMA } : "RFC 7643 1.2: /Schemas must contain the core User schema ${USER_SCHEMA}"
    }

    // RFC 7643 section 1.2 (Definitions), MUST NOT, lines 304-305
    // A simple attribute MUST NOT contain sub-attributes.
    @SpecRef("RFC7643-1.2-8bf98b")
    def "RFC7643-1.2-8bf98b: A simple attribute MUST NOT contain sub-attributes."() {
        when:
        def response = scimRequestQuiet().get("/Schemas")
        if (response.statusCode() != 200) {
            throw new TestAbortedException("Server does not offer /Schemas (HTTP ${response.statusCode()})")
        }
        List resources = response.jsonPath().getList("Resources")
        List<String> violations = []
        int checked = 0
        Closure visit
        visit = { String path, Map attr ->
            if (attr.type != "complex") {
                checked++
                if (attr.subAttributes) violations << path
            }
            (attr.subAttributes ?: []).each { Map sub -> visit("${path}.${sub.name}", sub) }
        }
        resources.each { Map schema ->
            (schema.attributes ?: []).each { Map attr -> visit("${schema.id}:${attr.name}", attr) }
        }

        then:
        assert checked > 0 : "No simple attributes found in /Schemas to verify"
        assert violations.isEmpty() : "RFC 7643 1.2: simple attributes must not contain sub-attributes, but found ${violations}"
    }
}
