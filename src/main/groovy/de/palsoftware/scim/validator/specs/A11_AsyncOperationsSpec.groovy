package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.CapabilityState
import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Shared

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Area 11 — SCIM Profile for Security Event Tokens (SETs) (RFC 9967)
 *
 * Validates asynchronous request processing via Prefer: respond-async,
 * 202 Accepted response semantics, Set-Txn header, and discovery configuration
 * per RFC 9967 when supported by the SCIM service provider.
 */
@RequiresCapability("async")
class A11_AsyncOperationsSpec extends ScimBaseSpec {

    @Shared String asyncLocation
    @Shared String createdUserName = "async_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
    @Shared List<String> asyncUserNames = new CopyOnWriteArrayList<>()

    def setupSpec() {
        ensureServiceProviderConfigLoaded()
        asyncUserNames << createdUserName
    }

    protected void ensureAsyncLocation() {
        if (asyncLocation) return
        String locUserName = "async_loc_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        asyncUserNames << locUserName
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: locUserName
        ]
        Response response = scimRequestQuiet()
            .header("Prefer", "respond-async")
            .body(JsonOutput.toJson(payload))
            .post("/Users")
        if (response.statusCode() == 202) {
            asyncLocation = response.header("Location")
        } else if (response.statusCode() in [200, 201]) {
            try {
                String uid = response.jsonPath().getString("id")
                if (uid) createdUserIds << uid
            } catch (Exception ignored) {}
        }
    }

    // ─── ASY_01: Prefer respond-async returns 202 with no body and Set-Txn ─

    @SpecRef("RFC9967-2.5.1.1-R1")
    def "ASY_01: POST /Users with Prefer: respond-async initiates asynchronous or synchronous request"() {
        // RFC 9967 §2.5.1.1 — Client initiates async request with Prefer: respond-async
        given: "User creation payload"
        asyncUserNames << createdUserName
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: createdUserName
        ]

        when: "POST with Prefer: respond-async"
        Response response = scimRequest()
            .header("Prefer", "respond-async")
            .body(JsonOutput.toJson(payload))
            .post("/Users")
        asyncLocation = response.statusCode() == 202 ? response.header("Location") : null
        if (response.statusCode() in [200, 201]) {
            try {
                String uid = response.jsonPath().getString("id")
                if (uid) createdUserIds << uid
            } catch (Exception ignored) {}
        }

        then: "Server returns HTTP 202 Accepted (or normal SCIM response if asyncRequest is 'long')"
        if (response.statusCode() == 201) {
            if (getCapabilityState("async") == CapabilityState.UNKNOWN) {
                setCapabilityState("async", CapabilityState.UNSUPPORTED)
                throw new TestAbortedException("Server does not support async operations; responded synchronously with 201 Created")
            }
        }
        String mode = getAsyncRequestMode()
        if (mode == "long") {
            assert response.statusCode() in [200, 201, 202] :
                "Expected HTTP 200, 201, or 202 per RFC 9967 §2.5.1.1 for asyncRequest='long', but got ${response.statusCode()}"
        } else {
            assert response.statusCode() == 202 :
                "Expected HTTP 202 Accepted per RFC 9967 §2.5.1.1, but got ${response.statusCode()}"
        }

        and: "Preference-Applied header contains respond-async if returned"
        String prefApplied = response.header("Preference-Applied")
        if (prefApplied) {
            assert prefApplied.toLowerCase().contains("respond-async") :
                "Preference-Applied header should indicate respond-async (RFC 7240 §3)"
        }
    }

    // ─── ASY_01b: 202 Accepted response structure ───────────────────────────

    @SpecRef("RFC9967-2.5.1.1-R2")
    @SpecRef("RFC9967-2.5.1.1-R3")
    @SpecRef("RFC9967-3-R1")
    def "ASY_01b: HTTP 202 Accepted async response contains empty body, Location, and Set-Txn headers"() {
        // RFC 9967 §2.5.1.1 & §3:
        // "The asynchronous response MUST contain no response body."
        // "The HTTP response header 'Set-Txn' (see Section 3) is returned with a value
        // that MUST match the 'txn' claim in a subsequent Security Event Token."
        given: "User creation payload"
        String testUser = "async_202_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        asyncUserNames << testUser
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: testUser
        ]

        when: "POST with Prefer: respond-async"
        Response response = scimRequest()
            .header("Prefer", "respond-async")
            .body(JsonOutput.toJson(payload))
            .post("/Users")
        if (response.statusCode() in [200, 201]) {
            try {
                String uid = response.jsonPath().getString("id")
                if (uid) createdUserIds << uid
            } catch (Exception ignored) {}
        }

        then: "If response is not 202 Accepted, abort so 202-exclusive MUST requirements are not credited"
        if (response.statusCode() != 202) {
            throw new TestAbortedException("Server responded synchronously with HTTP ${response.statusCode()}; HTTP 202 Accepted requirements are not applicable")
        }

        and: "Asynchronous 202 response adheres to RFC 9967 §2.5.1.1 and §3"
        byte[] bodyBytes = response.body().asByteArray()
        assert bodyBytes == null || bodyBytes.length == 0 || response.body().asString().trim().isEmpty() :
            "202 Accepted asynchronous response MUST contain no response body (RFC 9967 §2.5.1.1)"

        String setTxn = response.header("Set-Txn")
        assert setTxn != null && !setTxn.trim().isEmpty() :
            "202 Accepted response MUST include Set-Txn response header (RFC 9967 §3)"

        String loc = response.header("Location")
        assert loc != null && !loc.trim().isEmpty() :
            "202 Accepted response MUST include Location header (RFC 9967 §2.5.1.1)"
    }

    // ─── ASY_02: Location header points to valid URI ────────────────────────

    @SpecRef("RFC9967-2.5.1.1-R3")
    def "ASY_02: Location header in 202 Accepted response specifies a valid URI"() {
        // RFC 9967 §2.5.1.1 — Location header value MUST be either a URI where
        // the completion SCIM Event Token MAY be retrieved using HTTP GET or the
        // normal SCIM location header response specified by RFC 7644.
        given: "Verify asyncLocation was captured"
        ensureAsyncLocation()
        if (!asyncLocation) {
            throw new TestAbortedException("Skipped: no Location header received from ASY_01")
        }

        when: "Parse Location URI"
        URI locUri = URI.create(asyncLocation)

        then: "Location is a valid URI"
        assert locUri != null

        and: "Query Location only if same origin to prevent credential leakage"
        URI baseUri = URI.create(BASE_URL)
        boolean isSameOrigin = !locUri.isAbsolute() || (locUri.host == null || locUri.host.equalsIgnoreCase(baseUri.host))
        if (isSameOrigin) {
            String targetUri
            def req = scimRequestQuiet()
                .accept("application/scim+json, application/secevent+jwt, application/secevent+json, application/json")

            if (locUri.isAbsolute()) {
                targetUri = locUri.toString()
                req = req.baseUri(targetUri).basePath("")
            } else if (asyncLocation.startsWith("/")) {
                URI origin = URI.create("${baseUri.scheme}://${baseUri.rawAuthority}")
                targetUri = origin.resolve(locUri).toString()
                req = req.baseUri(targetUri).basePath("")
            } else {
                URI baseWithSlash = URI.create(BASE_URL.endsWith("/") ? BASE_URL : BASE_URL + "/")
                targetUri = baseWithSlash.resolve(locUri).toString()
                req = req.baseUri(targetUri).basePath("")
            }

            Response locResp = req.get("")
            assert locResp.statusCode() in [200, 202] :
                "GET on Location header returned unexpected status ${locResp.statusCode()} (RFC 9967 §2.5.1.1)"
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

        and: "securityEvents attribute contains valid asyncRequest configuration when present (OPTIONAL per RFC 9967 §4)"
        def secEvents = response.jsonPath().get("securityEvents")
        if (secEvents == null) {
            throw new TestAbortedException("ServiceProviderConfig omits OPTIONAL 'securityEvents' discovery attribute (RFC 9967 §4)")
        } else {
            String asyncReq = (secEvents.asyncRequest as String)?.toLowerCase()
            assert asyncReq in ["request", "long", "none"] :
                "securityEvents.asyncRequest must be 'request', 'long', or 'none' (RFC 9967 §4)"
            if (secEvents.eventUris != null) {
                assert secEvents.eventUris instanceof List : "securityEvents.eventUris must be a JSON array when present (RFC 9967 §4)"
            }
        }
    }

    def cleanupSpec() {
        // Clean up created user if found by ID
        createdUserIds.each { id ->
            if (id) {
                deleteUser(id)
            }
        }
        // Also attempt cleanup by querying each async userName in case async users were provisioned.
        // Enforce client-side exact match user.userName == targetName to eliminate destructive
        // tenant wipeout when the server ignores or does not support filtering (TASK-47).
        asyncUserNames.unique().each { String targetName ->
            if (!targetName) return
            try {
                Response searchResp = scimRequestQuiet().queryParam("filter", "userName eq \"${targetName}\"").get("/Users")
                if (searchResp.statusCode() == 200) {
                    List<Map> resources = searchResp.jsonPath().getList("Resources") ?: []
                    resources.each { Map user ->
                        if (user && user.userName == targetName && user.id) {
                            deleteUser(user.id as String)
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
