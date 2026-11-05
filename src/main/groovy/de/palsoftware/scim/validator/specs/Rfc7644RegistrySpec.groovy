package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.SpecRef

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "IANA registration").
 */
class Rfc7644RegistrySpec extends ScimBaseSpec {

    // RFC 7644 section 8.1 (Media Type Registration), SHOULD, lines 4622-4623
    // Applications intending to use the SCIM protocol SHOULD use the "application/scim+json" media type.
    @SpecRef("RFC7644-8.1-98ef08")
    def "RFC7644-8.1-98ef08: Applications intending to use the SCIM protocol SHOULD use the 'application/scim+json' media type."() {
        given:
        def created = createUser()
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")

        when:
        def single = scimRequestQuiet().get("/Users/${id}")
        def list = scimRequestQuiet().queryParam("count", 1).get("/Users")
        def error = scimRequestQuiet().get("/Users/does-not-exist-${UUID.randomUUID()}")

        then:
        [single, list, error].each { r ->
            String ct = r.contentType()
            assert ct != null && ct.toLowerCase().startsWith("application/scim+json") :
                "RFC 7644 8.1: SCIM responses SHOULD use media type application/scim+json but Content-Type was '${ct}' (status ${r.statusCode()})"
        }
        assert single.statusCode() == 200
        assert error.statusCode() == 404
    }
}
