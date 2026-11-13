package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.CapabilityState
import de.palsoftware.scim.validator.base.Rfc9967BaseSpec
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 9967: SCIM Profile for Security Event Tokens
 *
 * One feature per requirement of requirements-rfc9967.yaml (area "Security considerations"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc9967SecuritySpec extends Rfc9967BaseSpec {

    // RFC 9967 section 5 (Security Considerations), MUST, lines 1068-1070
    // To mitigate this, access to event recovery and forwarding MUST be limited to the parties needed to support recovery or SET forwarding.
    @SpecRef("RFC9967-5-e3dca4")
    def "RFC9967-5-e3dca4: To mitigate this, access to event recovery and forwarding MUST be limited to the parties needed to support..."() {
        given: "an accepted asynchronous request whose completion event is available at Location"
        ensureServiceProviderConfigLoaded()
        if (getCapabilityState("async") == CapabilityState.UNSUPPORTED) {
            throw new TestAbortedException("Server does not support asynchronous requests")
        }
        Map created = createAsyncUser()
        String url = resolveUrl((created.response as Response).header("Location"))

        when: "the event is requested without credentials and with a wrong token"
        Response authorized = getUrl(url)
        Response anonymous = scimRequestAnonymous().accept("*/*").baseUri(url).basePath("").get()
        Response wrongToken = scimRequestAnonymous().accept("*/*").header("Authorization", "Bearer invalid.token.w9967").baseUri(url).basePath("").get()

        then: "only the authorized client can retrieve it"
        assert authorized.statusCode() == 200 : "Precondition: the authorized client must be able to retrieve the event but got ${authorized.statusCode()}"
        assert anonymous.statusCode() in [401, 403] : "RFC 9967 5: access to event recovery must be limited, anonymous GET of ${url} returned ${anonymous.statusCode()}"
        assert wrongToken.statusCode() in [401, 403] : "RFC 9967 5: access to event recovery must be limited, GET with an invalid token returned ${wrongToken.statusCode()}"
    }

    // RFC 9967 section 5 (Security Considerations), MUST, lines 1114-1116
    // To mitigate, the returned URI endpoint MUST be protected and requires an HTTP Authorization header or some other form of client authentication.
    @SpecRef("RFC9967-5-8f317e")
    def "RFC9967-5-8f317e: To mitigate, the returned URI endpoint MUST be protected and requires an HTTP Authorization header or some..."() {
        given: "an accepted asynchronous request"
        ensureServiceProviderConfigLoaded()
        if (getCapabilityState("async") == CapabilityState.UNSUPPORTED) {
            throw new TestAbortedException("Server does not support asynchronous requests")
        }
        Map created = createAsyncUser()
        String loc = (created.response as Response).header("Location")
        String url = resolveUrl(loc)

        when: "the returned URI is requested without an Authorization header"
        Response anonymous = scimRequestAnonymous().accept("*/*").baseUri(url).basePath("").get()

        then: "the endpoint requires client authentication"
        assert anonymous.statusCode() == 401 : "RFC 9967 5: the returned URI endpoint must require an HTTP Authorization header (expected 401) but GET without credentials returned ${anonymous.statusCode()}"
        assert anonymous.header("WWW-Authenticate") : "RFC 7235 3.1: a 401 response must carry WWW-Authenticate"
    }

}
