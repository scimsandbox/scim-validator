package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Area 11 — Asynchronous SCIM Requests (RFC 9967)
 *
 * Validates asynchronous request processing via Prefer: respond-async
 * per RFC 9967 when supported by the SCIM service provider.
 */
@Stepwise
@RequiresCapability("async")
class A11_AsyncOperationsSpec extends ScimBaseSpec {

    @Shared String taskLocation
    @Shared String createdUserId

    def setupSpec() {
        loadServiceProviderConfig()
    }

    // ─── ASY_01: Prefer respond-async on POST ───────────────────────────────

    @SpecRef("RFC9967-2-R1")
    @SpecRef("RFC9967-2-R2")
    def "ASY_01: POST /Users with Prefer: respond-async header returns 202 Accepted or 201"() {
        // RFC 9967 §2 — Client signals preference for asynchronous processing
        given: "User creation payload"
        def userName = "async_${UUID.randomUUID().toString().substring(0, 8)}@test.com"
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: userName
        ]

        when: "POST with Prefer: respond-async"
        Response response = scimRequest()
            .header("Prefer", "respond-async")
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        then: "Status is either 202 Accepted (async processing) or 201 Created (sync processing)"
        response.statusCode() in [201, 202]

        and: "If 202, Location header is present to monitor status"
        if (response.statusCode() == 202) {
            taskLocation = response.header("Location")
            assert taskLocation != null : "202 Accepted response must include Location header (RFC 9967 §2)"
        } else {
            createdUserId = response.jsonPath().getString("id")
        }
    }

    // ─── ASY_02: Check async status URL ─────────────────────────────────────

    @SpecRef("RFC9967-2-R2")
    def "ASY_02: GET to async task Location returns task status or completed resource"() {
        // RFC 9967 §2 — Polling task status
        given: "Check taskLocation"
        if (!taskLocation) {
            ScimOutput.println "NOTE: Skipping ASY_02 because server responded synchronously with 201 in ASY_01"
            return
        }

        when: "Poll task status location"
        Response response = scimRequestQuiet().get(taskLocation)

        then: "Status is 200 OK or 202 Accepted"
        assert response.statusCode() in [200, 202]
        if (response.statusCode() == 200) {
            String id = response.jsonPath().getString("id")
            if (id) createdUserId = id
        }
    }

    def cleanupSpec() {
        if (createdUserId) {
            try { scimRequestQuiet().delete("/Users/${createdUserId}") } catch (Exception ignored) {}
        }
    }
}
