package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.CapabilityState
import de.palsoftware.scim.validator.base.Rfc9967BaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * RFC 9967: SCIM Profile for Security Event Tokens
 *
 * One feature per requirement of requirements-rfc9967.yaml (area "Asynchronous requests"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc9967AsyncSpec extends Rfc9967BaseSpec {

    // RFC 9967 section 2.5.1.1 (Making an Asynchronous SCIM Request), MAY, lines 719-721
    // A SCIM client making SCIM HTTP requests defined in Section 3 of [RFC7644] MAY request asynchronous processing using the "Prefer" HTTP header as defined in Section 4.1 of [RFC7240].
    @SpecRef("RFC9967-2.5.1.1-505e1c")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.1-505e1c: A SCIM client making SCIM HTTP requests defined in Section 3 of [RFC7644] MAY request asynchronous..."() {
        given: "a user that can be updated and deleted"
        Map target = syncUser()
        Map patchTarget = syncUser()
        Map deleteTarget = syncUser()
        String newName = uniqueUserName()
        asyncUserNames << newName

        when: "POST, PUT, PATCH and DELETE are sent with Prefer: respond-async"
        Map<String, Response> responses = [
            POST  : asyncRequest("POST", "/Users", [schemas: [USER_SCHEMA], userName: newName]),
            PUT   : asyncRequest("PUT", "/Users/${target.id}", [schemas: [USER_SCHEMA], userName: target.userName, displayName: "w9967 async put"]),
            PATCH : asyncRequest("PATCH", "/Users/${patchTarget.id}", [schemas: [PATCH_OP_SCHEMA], Operations: [[op: "replace", path: "displayName", value: "w9967 async"]]]),
            DELETE: asyncRequest("DELETE", "/Users/${deleteTarget.id}")
        ]
        responses.values().each { trackSyncResponse(it) }
        String mode = getAsyncRequestMode()

        then: "each is either answered normally or accepted with 202 and Preference-Applied"
        for (def entry : responses.entrySet()) {
            int sc = entry.value.statusCode()
            assert sc in [200, 201, 202, 204] : "RFC 9967 2.5.1.1: async ${entry.key} must return a normal SCIM response or 202 but was ${sc}"
            if (mode == "request") {
                assert sc == 202 : "RFC 9967 4/2.5.1.1: asyncRequest 'request' is advertised, so async ${entry.key} must return 202 but was ${sc}"
            }
            if (sc == 202) {
                assert entry.value.header("Preference-Applied")?.toLowerCase()?.contains("respond-async") :
                    "RFC 9967 2.5.1.1: the 202 response to ${entry.key} must include 'Preference-Applied: respond-async' (RFC 7240 3) but was ${entry.value.header('Preference-Applied')}"
            }
        }
    }

    // RFC 9967 section 2.5.1.1 (Making an Asynchronous SCIM Request), MUST, lines 730-731
    // The HTTP "Accept" header MUST be ignored for purposes of an asynchronous response.
    @SpecRef("RFC9967-2.5.1.1-84d853")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.1-84d853: The HTTP 'Accept' header MUST be ignored for purposes of an asynchronous response."() {
        given: "a request accepting only an unrelated media type"
        List<Map> results = []

        when: "async creates are sent with Accept values the server cannot produce"
        for (String accept : ["application/xml", "text/html"]) {
            String userName = uniqueUserName()
            asyncUserNames << userName
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/Users"))
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .header("Content-Type", SCIM_CONTENT_TYPE)
                .header("Accept", accept)
                .header("Prefer", "respond-async")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName])))
                .build()
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            results << [accept: accept, status: resp.statusCode(), body: resp.body()]
        }

        then: "the Accept header does not turn the asynchronous response into 406 or a body"
        if (results.every { it.status in [200, 201] }) {
            throw new TestAbortedException("Server answered the async requests normally; no asynchronous response to check")
        }
        for (Map r : results) {
            assert r.status == 202 : "RFC 9967 2.5.1.1: the Accept header must be ignored for an asynchronous response, but Accept '${r.accept}' produced ${r.status}"
            assert !r.body : "RFC 9967 2.5.1.1: the asynchronous response must have no body for Accept '${r.accept}'"
        }
    }

    // RFC 9967 section 2.5.1.1 (Making an Asynchronous SCIM Request), SHOULD, lines 731-733
    // Additionally, per Section 4.3 of [RFC7240], the "wait" preference SHOULD be supported to establish a maximum time before a SCIM service provider MAY choose to respond asynchronously.
    @SpecRef("RFC9967-2.5.1.1-27ac2b")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.1-27ac2b: Additionally, per Section 4.3 of [RFC7240], the 'wait' preference SHOULD be supported to establish a..."() {
        when: "a fast create is sent with Prefer: respond-async, wait=10"
        String userName = uniqueUserName()
        asyncUserNames << userName
        Response r = asyncRequest("POST", "/Users", [schemas: [USER_SCHEMA], userName: userName], [Prefer: "respond-async, wait=10"])
        trackSyncResponse(r)

        then: "the wait preference is honored: the quick request completes normally, or the server reports applying wait"
        String applied = r.header("Preference-Applied")?.toLowerCase() ?: ""
        assert r.statusCode() in [200, 201, 202] : "RFC 9967 2.5.1.1: request with wait preference must be accepted but was ${r.statusCode()}"
        assert r.statusCode() in [200, 201] || applied.contains("wait") :
            "RFC 9967 2.5.1.1: the 'wait' preference should be supported (RFC 7240 4.3): a request that completes within 10 s should get a normal response, but the server answered 202 with Preference-Applied '${applied}'"
    }

    // RFC 9967 section 2.5.1.1 (Making an Asynchronous SCIM Request), MUST, lines 736-737
    // The asynchronous response MUST contain no response body.
    @SpecRef("RFC9967-2.5.1.1-d6dbf7")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.1-d6dbf7: The asynchronous response MUST contain no response body."() {
        given: "targets for async PUT, PATCH and DELETE"
        Map put = syncUser()
        Map patch = syncUser()
        Map del = syncUser()
        String newName = uniqueUserName()
        asyncUserNames << newName

        when: "async requests are sent"
        Map<String, Response> responses = [
            POST  : asyncRequest("POST", "/Users", [schemas: [USER_SCHEMA], userName: newName]),
            PUT   : asyncRequest("PUT", "/Users/${put.id}", [schemas: [USER_SCHEMA], userName: put.userName, displayName: "w9967 b"]),
            PATCH : asyncRequest("PATCH", "/Users/${patch.id}", [schemas: [PATCH_OP_SCHEMA], Operations: [[op: "replace", path: "displayName", value: "w9967 b"]]]),
            DELETE: asyncRequest("DELETE", "/Users/${del.id}")
        ]
        responses.values().each { trackSyncResponse(it) }
        Map<String, Response> accepted = responses.findAll { it.value.statusCode() == 202 }

        then: "every 202 response has an empty body"
        if (accepted.isEmpty()) {
            throw new TestAbortedException("Server answered every async request normally")
        }
        for (def entry : accepted.entrySet()) {
            assert entry.value.body().asString().isEmpty() : "RFC 9967 2.5.1.1: the asynchronous response to ${entry.key} must contain no response body but had '${entry.value.body().asString()}'"
        }
    }

    // RFC 9967 section 2.5.1.1 (Making an Asynchronous SCIM Request), MUST, lines 737-740
    // To enable correlation of the future event, the HTTP response header "Set-Txn" (see Section 3) is returned with a value that MUST match the "txn" claim in a subsequent Security Event Token.
    @SpecRef("RFC9967-2.5.1.1-eca9d9")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.1-eca9d9: To enable correlation of the future event, the HTTP response header 'Set-Txn' (see Section 3) is returned..."() {
        when: "an async create is accepted and its completion token fetched"
        Map created = createAsyncUser()
        Response accepted = created.response as Response
        String setTxn = accepted.header("Set-Txn")
        Map claims = fetchSet(accepted)

        then: "Set-Txn is returned and equals the txn claim of the token"
        assert setTxn : "RFC 9967 2.5.1.1: the HTTP response header 'Set-Txn' must be returned with the 202 response"
        if (claims == null) {
            throw new TestAbortedException("Location does not return a Security Event Token")
        }
        assert claims.txn == setTxn : "RFC 9967 2.5.1.1: Set-Txn '${setTxn}' must match the 'txn' claim '${claims.txn}' of the Security Event Token"
    }

    // RFC 9967 section 2.5.1.1 (Making an Asynchronous SCIM Request), MUST, lines 741-744
    // The "Location" header value MUST be one of the following: (a) a URI
    @SpecRef("RFC9967-2.5.1.1-7f39f7")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.1-7f39f7: The 'Location' header value MUST be one of the following: (a) a URI"() {
        when: "an async create is accepted and Location is requested"
        Map created = createAsyncUser()
        Response accepted = created.response as Response
        String setTxn = accepted.header("Set-Txn")
        String loc = accepted.header("Location")
        Response located = loc ? getLocation(accepted) : null
        Map claims = located != null ? parseClaims(located) : null

        then: "Location is a retrievable completion token or the normal resource location"
        assert loc : "RFC 9967 2.5.1.1: the 202 response must carry a Location header"
        assert located.statusCode() == 200 : "RFC 9967 2.5.1.1: GET on Location '${loc}' must return 200 but was ${located.statusCode()}"
        boolean isToken = claims != null && claims.events instanceof Map && claims.txn == setTxn
        boolean isResource = false
        try {
            isResource = located.jsonPath().getString("userName") == created.userName
        } catch (Exception ignored) {}
        assert isToken || isResource : "RFC 9967 2.5.1.1: Location must be (a) a URI where the completion token with txn '${setTxn}' can be retrieved or (b) the normal SCIM location of the resource, but GET returned ${located.body().asString().take(200)}"
    }

    // RFC 9967 section 2.5.1.1 (Making an Asynchronous SCIM Request), MAY, lines 741-744
    // where the completion SCIM Event Token MAY be retrieved using HTTP GET or (b) the normal SCIM location header response specified by [RFC7644].
    @SpecRef("RFC9967-2.5.1.1-4a2b7e")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.1-4a2b7e: where the completion SCIM Event Token MAY be retrieved using HTTP GET or (b) the normal SCIM location..."() {
        when: "an async create is accepted and Location is requested"
        Map created = createAsyncUser()
        Response accepted = created.response as Response
        Response located = getLocation(accepted)
        Map claims = parseClaims(located)

        then: "Location returns either (a) a valid completion SCIM Event Token for the transaction or (b) the normal SCIM resource"
        assert located.statusCode() == 200
        if (claims != null && claims.events instanceof Map) {
            for (String claim : ["jti", "iat", "iss", "events", "txn"]) {
                assert claims.containsKey(claim) : "RFC 9967 2.5.1.1: the completion Security Event Token must contain '${claim}' but has ${claims.keySet()}"
            }
            assert claims.txn == accepted.header("Set-Txn") : "RFC 9967 2.5.1.1: token retrieved from Location must belong to the transaction ${accepted.header('Set-Txn')} but txn was ${claims.txn}"
        } else {
            assert located.jsonPath().getString("userName") == created.userName :
                "RFC 9967 2.5.1.1: Location option (b) must return the created resource"
        }
    }

    // RFC 9967 section 2.5.1.2 (Asynchronous Bulk Endpoint Requests), MUST, lines 787-789
    // When an asynchronous response is requested, a single Asynchronous Request Completion Event MUST be generated for each requested operation.
    @SpecRef("RFC9967-2.5.1.2-d52ade")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.2-d52ade: When an asynchronous response is requested, a single Asynchronous Request Completion Event MUST be..."() {
        when: "an async bulk request with two operations is sent"
        Map bulk = asyncBulk(false)

        then: "if answered asynchronously (202), each operation has a completion event; if answered synchronously (200), each operation has a bulk result"
        if (bulk.synchronous) {
            Response r = bulk.response as Response
            assert r.statusCode() == 200
            List<Map> resOps = r.jsonPath().getList("Operations") ?: []
            assert resOps.size() == bulk.ops.size()
            assert resOps.every { (it.status as String) == "201" }
        } else {
            List<Map> sets = (0..<bulk.ops.size()).collect { bulkOperationSet(bulk, it) }
            for (int i = 0; i < sets.size(); i++) {
                assert sets[i] != null : "RFC 9967 2.5.1.2: a completion event must be generated for bulk operation ${i} (txn ${bulk.txn}:${i}) but none could be retrieved"
                assert eventsOf(sets[i]).containsKey(ASYNC_RESP_EVENT) : "RFC 9967 2.5.1.2: operation ${i} must have an Asynchronous Request Completion Event but events were ${eventsOf(sets[i]).keySet()}"
            }
        }
    }

    // RFC 9967 section 2.5.1.2 (Asynchronous Bulk Endpoint Requests), MUST, lines 793-796
    // The "txn" claim MUST be set to the value originally returned to the requesting SCIM client (see Section 2.5.1.1) appended with a colon ":" and the zero-based array index of the operation expressed in the "Operations" attribute of the original bulk request.
    @SpecRef("RFC9967-2.5.1.2-91c988")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.2-91c988: The 'txn' claim MUST be set to the value originally returned to the requesting SCIM client (see Section..."() {
        when: "an async bulk request with two operations is sent"
        Map bulk = asyncBulk(false)

        then: "if answered asynchronously (202), the txn of operation n is the returned txn, a colon and n; if synchronous (200), the BulkResponse is valid"
        if (bulk.synchronous) {
            Response r = bulk.response as Response
            assert r.statusCode() == 200
            assert (r.jsonPath().getList("Operations") ?: []).size() == bulk.ops.size()
        } else {
            String txn = bulk.txn as String
            List<Map> sets = (0..<bulk.ops.size()).collect { bulkOperationSet(bulk, it) }
            assert txn : "RFC 9967 2.5.1.1: Set-Txn missing"
            for (int i = 0; i < sets.size(); i++) {
                assert sets[i] != null : "RFC 9967 2.5.1.2: no completion event for operation ${i}"
                assert sets[i].txn == "${txn}:${i}".toString() : "RFC 9967 2.5.1.2: txn of operation ${i} must be '${txn}:${i}' but was '${sets[i].txn}'"
            }
        }
    }

    // RFC 9967 section 2.5.1.2 (Asynchronous Bulk Endpoint Requests), MUST NOT, lines 796-798
    // The "bulkId" parameter MUST NOT be used for this purpose as it is a temporary identifier and is not required for every operation.
    @SpecRef("RFC9967-2.5.1.2-74841e")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.2-74841e: The 'bulkId' parameter MUST NOT be used for this purpose as it is a temporary identifier and is not..."() {
        when: "an async bulk request with bulkIds is sent"
        Map bulk = asyncBulk(false)

        then: "neither Set-Txn nor the event txn values are derived from a bulkId"
        if (bulk.synchronous) {
            Response r = bulk.response as Response
            assert r.statusCode() == 200
            assert r.header("Set-Txn") == null
        } else {
            String txn = bulk.txn as String
            List<Map> sets = (0..<bulk.ops.size()).collect { bulkOperationSet(bulk, it) }
            for (String bulkId : bulk.bulkIds) {
                assert !txn.contains(bulkId) : "RFC 9967 2.5.1.2: bulkId '${bulkId}' must not be used for the txn but Set-Txn was '${txn}'"
                for (Map set : sets) {
                    assert set != null && !(set.txn as String).contains(bulkId) : "RFC 9967 2.5.1.2: bulkId '${bulkId}' must not be used for the txn but the event txn was '${set?.txn}'"
                }
            }
        }
    }

    // RFC 9967 section 2.5.1.2 (Asynchronous Bulk Endpoint Requests), MAY, lines 808-810
    // If a SCIM service provider optimizes the sequence of operations (per Section 3.7 of [RFC7644]), the Asynchronous Request Completion Events MAY be generated out of sequence from the original request.
    @SpecRef("RFC9967-2.5.1.2-d68c76")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.2-d68c76: If a SCIM service provider optimizes the sequence of operations (per Section 3.7 of [RFC7644]), the..."() {
        when: "an async bulk request whose first operation depends on a later one is sent"
        Map bulk = asyncBulk(true)

        then: "events may arrive in any order (or synchronously in BulkResponse) with one result per operation"
        if (bulk.synchronous) {
            Response r = bulk.response as Response
            assert r.statusCode() == 200
            List<Map> resOps = r.jsonPath().getList("Operations") ?: []
            assert resOps.size() == bulk.ops.size()
            assert resOps.every { (it.status as String) == "201" }
        } else {
            List<Map> sets = (0..<bulk.ops.size()).collect { bulkOperationSet(bulk, it) }
            for (int i = 0; i < sets.size(); i++) {
                assert sets[i] != null : "RFC 9967 2.5.1.2: no completion event for operation ${i} of the original request"
            }
            assert (sets.collect { it.txn } as Set).size() == bulk.ops.size() : "RFC 9967 2.5.1.2: each operation needs its own txn but got ${sets.collect { it.txn }}"
        }
    }

    // RFC 9967 section 2.5.1.2 (Asynchronous Bulk Endpoint Requests), MUST, lines 810-812
    // In this case, the "txn" claims in those events MUST use operation numbers that correspond to the order in the original request.
    @SpecRef("RFC9967-2.5.1.2-03f6b4")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.2-03f6b4: In this case, the 'txn' claims in those events MUST use operation numbers that correspond to the order in..."() {
        when: "an async bulk request whose group refers to a user created by a later operation is sent"
        Map bulk = asyncBulk(true)

        then: "if answered asynchronously, operation numbers in txn follow the original request order; if synchronous, all operations succeed"
        if (bulk.synchronous) {
            Response r = bulk.response as Response
            assert r.statusCode() == 200
            List<Map> resOps = r.jsonPath().getList("Operations") ?: []
            assert resOps.size() == bulk.ops.size()
            assert resOps.every { (it.status as String) == "201" }
        } else {
            List<Map> sets = (0..<bulk.ops.size()).collect { bulkOperationSet(bulk, it) }
            for (int i = 0; i < sets.size(); i++) {
                assert sets[i] != null : "RFC 9967 2.5.1.2: no completion event for operation ${i}"
                String uri = (sets[i].sub_id as Map)?.uri as String
                assert uri : "RFC 9967 2.1: event for operation ${i} has no sub_id.uri"
                String expected = (i == 0) ? "/Groups/" : "/Users/"
                assert uri.contains(expected) : "RFC 9967 2.5.1.2: txn ${bulk.txn}:${i} must belong to operation ${i} of the original request (${expected}) but sub_id.uri was ${uri}"
            }
        }
    }

    // RFC 9967 section 2.5.1.3 (urn:ietf:params:scim:event:misc:asyncresp), MUST, lines 819-821
    // In the event, the "txn" claim MUST be set to the value originally returned to the requesting SCIM client (see Section 2.5.1.1).
    @SpecRef("RFC9967-2.5.1.3-8817f0")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.3-8817f0: In the event, the 'txn' claim MUST be set to the value originally returned to the requesting SCIM client..."() {
        when: "an async create completes"
        Map s = creationSet()
        String setTxn = (s.accepted as Response).header("Set-Txn")
        Map events = eventsOf(s.claims)

        then: "the token has the asyncresp event and its txn is the value returned to the client"
        assert events.containsKey(ASYNC_RESP_EVENT) : "RFC 9967 2.5.1.3: the completion of an asynchronous request is signaled by ${ASYNC_RESP_EVENT} but the token carries ${events.keySet()}"
        assert s.claims.txn == setTxn : "RFC 9967 2.5.1.3: txn '${s.claims.txn}' must equal the value originally returned (Set-Txn '${setTxn}')"
    }

    // RFC 9967 section 2.5.1.3 (urn:ietf:params:scim:event:misc:asyncresp), MUST, lines 846-850
    // If an error occurs during asynchronous processing, the event operation MUST include a "response" attribute indicating a non- 200-series HTTP status as defined in Section 3.7 of [RFC7644], and that "response" attribute MUST contain the sub-attributes defined in Section 3.12 of [RFC7644].
    @SpecRef("RFC9967-2.5.1.3-3f18cc")
    @RequiresCapability("async")
    def "RFC9967-2.5.1.3-3f18cc: If an error occurs during asynchronous processing, the event operation MUST include a 'response' attribute..."() {
        given: "an existing user"
        Map existing = syncUser()

        when: "an async create with a duplicate userName (uniqueness conflict) is sent"
        Response r = asyncRequest("POST", "/Users", [schemas: [USER_SCHEMA], userName: existing.userName])
        trackSyncResponse(r)
        if (r.statusCode() != 202) {
            throw new TestAbortedException("Server rejected the request synchronously with ${r.statusCode()}; no asynchronous error to check")
        }
        Response located = getLocation(r)
        Map claims = parseClaims(located)
        Map asyncResp = (claims != null) ? (eventsOf(claims)[ASYNC_RESP_EVENT] as Map) : null

        then: "the completion event reports the error with a response attribute"
        assert claims != null : "RFC 9967 2.5.1.3: the completion Security Event Token for the failed request must be retrievable at Location, but GET returned ${located.statusCode()} with '${located.body().asString().take(200)}'"
        assert asyncResp != null : "RFC 9967 2.5.1.3: expected event ${ASYNC_RESP_EVENT} in ${eventsOf(claims).keySet()}"
        assert (asyncResp.status as String) ==~ /[45]\d\d/ : "RFC 9967 2.5.1.3: event status must be a non-200-series HTTP status but was ${asyncResp.status}"
        assert asyncResp.response instanceof Map : "RFC 9967 2.5.1.3: the event must include a 'response' attribute for the error"
        assert (asyncResp.response.schemas as List)?.contains(ERROR_SCHEMA) : "RFC 9967 2.5.1.3: 'response' must carry the sub-attributes of RFC 7644 3.12 (schemas ${ERROR_SCHEMA}) but was ${asyncResp.response}"
        assert (asyncResp.response.status as String) ==~ /[45]\d\d/ : "RFC 9967 2.5.1.3: response.status must be an error status but was ${asyncResp.response.status}"
    }

    // RFC 9967 section 3 (Set-Txn HTTP Response Header for Asynchronous Requests), MUST, lines 988-990
    // The header field MUST be used in SCIM responses when HTTP status code 202 (Accepted) is being returned with no message body.
    @SpecRef("RFC9967-3-236722")
    @RequiresCapability("async")
    def "RFC9967-3-236722: The header field MUST be used in SCIM responses when HTTP status code 202 (Accepted) is being returned..."() {
        given: "targets for async PUT, PATCH and DELETE"
        Map put = syncUser()
        Map patch = syncUser()
        Map del = syncUser()
        String newName = uniqueUserName()
        asyncUserNames << newName

        when: "async requests are sent"
        Map<String, Response> responses = [
            POST  : asyncRequest("POST", "/Users", [schemas: [USER_SCHEMA], userName: newName]),
            PUT   : asyncRequest("PUT", "/Users/${put.id}", [schemas: [USER_SCHEMA], userName: put.userName, displayName: "w9967 c"]),
            PATCH : asyncRequest("PATCH", "/Users/${patch.id}", [schemas: [PATCH_OP_SCHEMA], Operations: [[op: "replace", path: "displayName", value: "w9967 c"]]]),
            DELETE: asyncRequest("DELETE", "/Users/${del.id}")
        ]
        responses.values().each { trackSyncResponse(it) }
        Map<String, Response> accepted = responses.findAll { it.value.statusCode() == 202 }

        then: "every 202 response carries a non-empty Set-Txn header"
        if (accepted.isEmpty()) {
            throw new TestAbortedException("Server answered every async request normally")
        }
        for (def entry : accepted.entrySet()) {
            assert entry.value.header("Set-Txn")?.trim() : "RFC 9967 3: the Set-Txn header must be used when 202 is returned, but the ${entry.key} response had none"
        }
    }

}
