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
 * One feature per requirement of requirements-rfc7644.yaml (area "Security considerations"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644SecuritySpec extends ScimBaseSpec {

    // RFC 7644 section 7.8 (Case-Insensitive Comparison and International Languages), MUST, lines 4553-4555
    // When comparing Unicode strings such as those in query filters or testing for uniqueness of usernames and passwords, strings MUST be appropriately prepared before comparison.
    @SpecRef("RFC7644-7.8-432b0b")
    def "RFC7644-7.8-432b0b: When comparing Unicode strings such as those in query filters or testing for uniqueness of usernames and..."() {
        given: "a user name in precomposed (NFC) form and the same name in decomposed (NFD) form"
        String prefix = "w7u${UUID.randomUUID().toString().substring(0, 8)}"
        String nfc = "${prefix}-caf\u00e9"
        String nfd = "${prefix}-cafe\u0301"
        assert nfc != nfd

        when: "the NFC user is created"
        Response first = createUser(userName: nfc)
        assert first.statusCode() == 201 : "setup: create failed with ${first.statusCode()}"
        String firstId = first.jsonPath().getString("id")

        and: "the same name is filtered for in decomposed form"
        Response filtered = scimRequest().queryParam("filter", "userName eq \"${nfd}\"").get("/Users")

        and: "a second user with the decomposed name is created"
        Response second = createUser(userName: nfd)

        then: "the strings were prepared before comparison"
        assert filtered.statusCode() == 200 : "RFC 7644 7.8: filter must succeed, status ${filtered.statusCode()}"
        assert filtered.jsonPath().getList("Resources.id")?.contains(firstId) :
            "RFC 7644 7.8: filter with the NFD form of a stored NFC userName must match it"
        assert second.statusCode() == 409 : "RFC 7644 7.8: userName that differs only in Unicode normalization must conflict (409) but was ${second.statusCode()}"
    }

}
