package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 11 — SCIM Profile for Security Event Tokens (SETs) (RFC 9967)
 *
 * Validates asynchronous request processing via Prefer: respond-async,
 * 202 Accepted response semantics, Set-Txn header, and discovery configuration
 * per RFC 9967 when supported by the SCIM service provider.
 */
@Stepwise
@RequiresCapability("async")
class A11_AsyncOperationsSpec extends ScimBaseSpec {

    @Shared String asyncLocation
    @Shared String createdUserName = "async_${UUID.randomUUID().toString().substring(0, 8)}@test.com"

    def setupSpec() {
        ensureServiceProviderConfigLoaded()
    }

    // ─── ASY_01: Prefer respond-async returns 202 with no body and Set-Txn ─

    @SpecRef("RFC9967-2.5.1.1-R1")
    @SpecRef("RFC9967-2.5.1.1-R2")
    @SpecRef("RFC9967-2.5.1.1-R3")
    @SpecRef("RFC9967-3-R1")
    def "ASY_01: POST /Users with Prefer: respond-async returns 202 Accepted with empty body and Set-Txn header"() {
        // RFC 9967 §2.5.1.1 & §3:
        // Client initiates async request with Prefer: respond-async.
        // Server responds with HTTP 202 (Accepted).
        // "The asynchronous response MUST contain no response body."
        // "The HTTP response header 'Set-Txn' (see Section 3) is returned with a value
        // that MUST match the 'txn' claim in a subsequent Security Event Token."
        given: "User creation payload"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: createdUserName
        ]

        when: "POST with Prefer: respond-async"
        Response response = scimRequest()
            .header("Prefer", "respond-async")
            .body(JsonOutput.toJson(payload))
            .post("/Users")
        asyncLocation = response.header("Location")

        then: "Server returns HTTP 202 Accepted"
        assert response.statusCode() == 202 :
            "Expected HTTP 202 Accepted per RFC 9967 §2.5.1.1, but got ${response.statusCode()}"

        and: "Response MUST contain no response body per RFC 9967 §2.5.1.1 and §3"
        byte[] bodyBytes = response.body().asByteArray()
        assert bodyBytes == null || bodyBytes.length == 0 || response.body().asString().trim().isEmpty() :
            "202 Accepted asynchronous response MUST contain no response body (RFC 9967 §2.5.1.1)"

        and: "Set-Txn response header MUST be present and non-empty (RFC 9967 §3)"
        String setTxn = response.header("Set-Txn")
        assert setTxn != null && !setTxn.trim().isEmpty() :
            "202 Accepted response MUST include Set-Txn response header (RFC 9967 §3)"

        and: "Location response header MUST be present (RFC 9967 §2.5.1.1)"
        assert asyncLocation != null && !asyncLocation.trim().isEmpty() :
            "202 Accepted response MUST include Location header (RFC 9967 §2.5.1.1)"

        and: "Preference-Applied header contains respond-async if returned"
        String prefApplied = response.header("Preference-Applied")
        if (prefApplied) {
            assert prefApplied.toLowerCase().contains("respond-async") :
                "Preference-Applied header should indicate respond-async (RFC 7240 §3)"
        }
    }

    // ─── ASY_02: Location header points to valid URI ────────────────────────

    @SpecRef("RFC9967-2.5.1.1-R3")
    def "ASY_02: Location header in 202 Accepted response specifies a valid URI"() {
        // RFC 9967 §2.5.1.1 — Location header value MUST be either a URI where
        // the completion SCIM Event Token MAY be retrieved using HTTP GET or the
        // normal SCIM location header response specified by RFC 7644.
        given: "Verify asyncLocation was captured in ASY_01"
        if (!asyncLocation) {
            throw new TestAbortedException("Skipped: no Location header received from ASY_01")
        }

        when: "Parse Location URI"
        URI locUri = URI.create(asyncLocation)

        then: "Location is a valid URI"
        assert locUri != null

        and: "Query Location only if same origin to prevent credential leakage"
        URI baseUri = URI.create(getBaseUri())
        boolean isSameOrigin = !locUri.isAbsolute() || (locUri.host == null || locUri.host.equalsIgnoreCase(baseUri.host))
        if (isSameOrigin) {
            Response locResp = scimRequestQuiet().get(asyncLocation)
            assert locResp.statusCode() in [200, 202, 404] :
                "GET on Location header returned unexpected status ${locResp.statusCode()}"
            if (locResp.statusCode() == 200 && locResp.contentType()?.contains("scim+json")) {
                String uid = locResp.jsonPath().getString("id")
                if (uid) createdUserIds << uid
            }
        }
    }

    // ─── ASY_03: ServiceProviderConfig securityEvents discovery ─────────────

    @SpecRef("RFC9967-4-R1")
    def "ASY_03: ServiceProviderConfig advertises securityEvents discovery schema"() {
        // RFC 9967 §4 — Events Discovery Schema for Service Provider Configuration
        when: "Fetch /ServiceProviderConfig"
        Response response = scimRequest().get("/ServiceProviderConfig")

        then: "Status is 200 OK"
        assert response.statusCode() == 200

        and: "securityEvents attribute contains valid asyncRequest configuration"
        def secEvents = response.jsonPath().get("securityEvents")
        assert secEvents != null : "ServiceProviderConfig must contain 'securityEvents' per RFC 9967 §4"
        String asyncReq = (secEvents.asyncRequest as String)?.toLowerCase()
        assert asyncReq in ["request", "long", "none"] :
            "securityEvents.asyncRequest must be 'request', 'long', or 'none' (RFC 9967 §4)"
    }

    def cleanupSpec() {
        // Clean up created user if found by ID
        createdUserIds.each { id ->
            if (id) {
                try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
            }
        }
        // Also attempt cleanup by querying userName in case async user was provisioned
        try {
            Response searchResp = scimRequestQuiet().queryParam("filter", "userName eq \"${createdUserName}\"").get("/Users")
            if (searchResp.statusCode() == 200) {
                List<String> ids = searchResp.jsonPath().getList("Resources.id") ?: []
                ids.each { id ->
                    try { scimRequestQuiet().delete("/Users/${id}") } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }
}
