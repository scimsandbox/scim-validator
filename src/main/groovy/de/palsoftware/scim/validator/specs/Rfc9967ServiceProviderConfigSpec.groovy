package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.Rfc9967BaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 9967: SCIM Profile for Security Event Tokens
 *
 * One feature per requirement of requirements-rfc9967.yaml (area "ServiceProviderConfig"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc9967ServiceProviderConfigSpec extends Rfc9967BaseSpec {

    // RFC 9967 section 4 (Events Discovery Schema for Service Provider Configuration), MAY, lines 1014-1017
    // This attribute is OPTIONAL, and when absent, it indicates the SCIM service provider does not support or is not currently configured for Security Events.
    @SpecRef("RFC9967-4-85b9e0")
    @RequiresCapability("async")
    def "RFC9967-4-85b9e0: This attribute is OPTIONAL, and when absent, it indicates the SCIM service provider does not support or is..."() {
        given: "the discovery document"
        Response spc = scimRequest().get("/ServiceProviderConfig")
        assert spc.statusCode() == 200 : "RFC 7644 4: ServiceProviderConfig must return 200 but was ${spc.statusCode()}"
        def securityEvents = spc.jsonPath().get("securityEvents")
        String mode = (securityEvents instanceof Map) ? (securityEvents.asyncRequest as String)?.toLowerCase() : null

        when: "an asynchronous request is probed"
        String probeName = uniqueUserName()
        asyncUserNames << probeName
        Response probe = asyncRequest("POST", "/Users", [schemas: [USER_SCHEMA], userName: probeName])
        trackSyncResponse(probe)

        then: "the advertised configuration matches the behavior"
        if (securityEvents == null) {
            assert probe.statusCode() != 202 : "RFC 9967 4: an absent 'securityEvents' indicates no support for Security Events but the server accepted an asynchronous request with 202"
        } else {
            assert securityEvents instanceof Map : "RFC 9967 4: 'securityEvents' must be a complex attribute but was ${securityEvents}"
            if (mode == null) {
                throw new TestAbortedException("securityEvents present without asyncRequest; nothing to cross-check")
            }
            assert mode in ["none", "long", "request"] : "RFC 9967 4: securityEvents.asyncRequest must be none, long or request but was '${mode}'"
            if (mode == "none") {
                assert probe.statusCode() != 202 : "RFC 9967 4: asyncRequest 'none' means asynchronous requests are not supported but the server answered 202"
            } else if (mode == "request") {
                assert probe.statusCode() == 202 : "RFC 9967 4: asyncRequest 'request' means the server completes requests asynchronously when asked but answered ${probe.statusCode()}"
            } else {
                assert probe.statusCode() in [200, 201, 202] : "RFC 9967 4: asyncRequest 'long' allows a normal response or 202 but answered ${probe.statusCode()}"
            }
        }
    }

}
