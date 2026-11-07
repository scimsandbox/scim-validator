package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import de.palsoftware.scim.validator.base.CapabilityState
import org.opentest4j.TestAbortedException

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "Bulk"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644BulkSpec extends ScimBaseSpec {

    // RFC 7644 section 3.7 (Bulk Operations), MAY, lines 2720-2720
    // OPTIONAL in a request.
    @SpecRef("RFC7644-3.7-37e095")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-37e095: OPTIONAL in a request."() {
        expect:
        String un = "w12fo_${uniq()}"
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [postOp("fo1", un)]])
        trackBulk(r)
        assert r.statusCode() == 200 : "RFC 7644 3.7: a bulk request without failOnErrors must be processed (200) but was ${r.statusCode()}"
        List<Map> ops = r.jsonPath().getList("Operations")
        assert ops?.size() == 1 : "RFC 7644 3.7: expected one operation result but got ${ops}"
        assert (ops[0].status as String) == "201" : "RFC 7644 3.7: POST without failOnErrors must return 201 but was ${ops[0].status}"
        assert userExists(un) : "RFC 7644 3.7: the user from the bulk POST was not created"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2728-2728
    // REQUIRED.
    @SpecRef("RFC7644-3.7-a1972c")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-a1972c: REQUIRED."() {
        expect:
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], failOnErrors: 1])
        assert r.statusCode() == 400 : "RFC 7644 3.7: a BulkRequest without the REQUIRED Operations attribute must be rejected with 400 but was ${r.statusCode()}"
        assertScimError(r, 400)
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2732-2732
    // REQUIRED.
    @SpecRef("RFC7644-3.7-1795ed")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-1795ed: method: REQUIRED."() {
        expect:
        String un = "w12nometh_${uniq()}"
        Map op = postOp("nm1", un)
        op.remove("method")
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [op]])
        trackBulk(r)
        assertOpRejected(r, 0, "RFC 7644 3.7: operation without the REQUIRED method")
        assert !userExists(un) : "RFC 7644 3.7: an operation without method must not be executed (user was created)"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2739-2739
    // REQUIRED when "method" is "POST".
    @SpecRef("RFC7644-3.7-5382b9")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-5382b9: bulkId: REQUIRED when 'method' is 'POST'."() {
        expect:
        String un = "w12nobid_${uniq()}"
        Map op = postOp("nb1", un)
        op.remove("bulkId")
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [op]])
        trackBulk(r)
        boolean created = userExists(un)
        assert !created : "RFC 7644 3.7: a POST operation without the REQUIRED bulkId was executed (user created, response ${r.statusCode()} ${r.body().asString().take(300)})"
        assertOpRejected(r, 0, "RFC 7644 3.7: POST operation without the REQUIRED bulkId")
    }

    // RFC 7644 section 3.7 (Bulk Operations), MAY, lines 2741-2743
    // Version MAY be used if the service provider supports entity-tags (ETags) (Section 2.3 of [RFC7232]) and "method" is "PUT", "PATCH", or "DELETE".
    @SpecRef("RFC7644-3.7-75a335")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-75a335: version: Version MAY be used if the service provider supports entity-tags (ETags) (Section 2.3 of..."() {
        expect:
        if (!isEtagSupported()) {
            throw new TestAbortedException("Server does not advertise ETag support, the version attribute is not offered")
        }
        String un = "w12ver_${uniq()}"
        Response cr = createUser(userName: un, title: "orig")
        assert cr.statusCode() == 201
        String uid = cr.jsonPath().getString("id")
        String stale = 'W/"w12-stale-version"'
        Response cur = scimRequestQuiet().get("/Users/${uid}")
        String current = cur.jsonPath().getString("meta.version") ?: cur.header("ETag")
        assert current : "server supports ETags but the user has neither meta.version nor an ETag header"
        List ops = [
            [method: "PUT", path: "/Users/${uid}", version: stale, data: [schemas: [USER_SCHEMA], userName: un, title: "stale-put"]],
            [method: "DELETE", path: "/Users/${uid}", version: stale]
        ]
        if (isPatchSupported()) {
            ops << [method: "PATCH", path: "/Users/${uid}", version: stale, data: buildPatchOp([[op: "replace", path: "title", value: "stale-patch"]])]
        }
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        assert r.statusCode() == 200 : "RFC 7644 3.7: bulk with versions must be processed (200) but was ${r.statusCode()}"
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size()
        res.eachWithIndex { Map o, int i ->
            assert (o.status as String) == "412" : "RFC 7644 3.7/3.14: ${ops[i].method} with a stale version must fail with 412 (ETag precondition) but was ${o.status}"
        }
        Response after = scimRequestQuiet().get("/Users/${uid}")
        assert after.statusCode() == 200 : "RFC 7644 3.7: a DELETE with a stale version must not delete the resource (GET ${after.statusCode()})"
        assert after.jsonPath().getString("title") == "orig" : "RFC 7644 3.7: operations with a stale version must not modify the resource, title is '${after.jsonPath().getString('title')}'"
        Response ok = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [
            [method: "PUT", path: "/Users/${uid}", version: current, data: [schemas: [USER_SCHEMA], userName: un, title: "fresh-put"]]]])
        assert ok.statusCode() == 200
        assert (ok.jsonPath().getList("Operations")[0].status as String) == "200" : "RFC 7644 3.7: PUT with the current version must succeed but was ${ok.body().asString().take(300)}"
        assert scimRequestQuiet().get("/Users/${uid}").jsonPath().getString("title") == "fresh-put"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2756-2757
    // REQUIRED in a request.
    @SpecRef("RFC7644-3.7-f8b48e")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-f8b48e: path: REQUIRED in a request."() {
        expect:
        String uA = "w12nopath_${uniq()}"
        String uB = "w12nopathb_${uniq()}"
        Response cr = createUser(userName: uB)
        assert cr.statusCode() == 201
        String idB = cr.jsonPath().getString("id")
        Map post = postOp("np1", uA)
        post.remove("path")
        Map put = [method: "PUT", data: [schemas: [USER_SCHEMA], userName: uB, title: "nopath"]]
        Map del = [method: "DELETE"]
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [post, put, del]])
        trackBulk(r)
        assertOpRejected(r, 0, "RFC 7644 3.7: POST without the REQUIRED path")
        assertOpRejected(r, 1, "RFC 7644 3.7: PUT without the REQUIRED path")
        assertOpRejected(r, 2, "RFC 7644 3.7: DELETE without the REQUIRED path")
        assert !userExists(uA) : "RFC 7644 3.7: a POST without path must not be executed"
        Response after = scimRequestQuiet().get("/Users/${idB}")
        assert after.statusCode() == 200 : "RFC 7644 3.7: a DELETE without path must not delete anything"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2760-2761
    // REQUIRED in a request when "method" is "POST", "PUT", or "PATCH".
    @SpecRef("RFC7644-3.7-724600")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-724600: data: REQUIRED in a request when 'method' is 'POST', 'PUT', or 'PATCH'."() {
        expect:
        String uP = "w12nodata_${uniq()}"
        String uX = "w12nodatax_${uniq()}"
        Response cr = createUser(userName: uX, title: "keep")
        assert cr.statusCode() == 201
        String idX = cr.jsonPath().getString("id")
        List ops = [
            [method: "POST", path: "/Users", bulkId: "nd1"],
            [method: "PUT", path: "/Users/${idX}"]
        ]
        if (isPatchSupported()) {
            ops << [method: "PATCH", path: "/Users/${idX}"]
        }
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assertOpRejected(r, 0, "RFC 7644 3.7: POST without the REQUIRED data")
        assertOpRejected(r, 1, "RFC 7644 3.7: PUT without the REQUIRED data")
        if (isPatchSupported()) {
            assertOpRejected(r, 2, "RFC 7644 3.7: PATCH without the REQUIRED data")
        }
        Response after = scimRequestQuiet().get("/Users/${idX}")
        assert after.statusCode() == 200
        assert after.jsonPath().getString("title") == "keep" : "RFC 7644 3.7: a PUT without data must not modify (blank) the resource, title is '${after.jsonPath().getString('title')}'"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2763-2764
    // REQUIRED in a response, except in the event of a POST failure.
    @SpecRef("RFC7644-3.7-43292f")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-43292f: location: REQUIRED in a response, except in the event of a POST failure."() {
        expect:
        String sfx = uniq()
        Response putUser = createUser(userName: "w12loc_put_${sfx}")
        Response delUser = createUser(userName: "w12loc_del_${sfx}")
        Response patUser = createUser(userName: "w12loc_pat_${sfx}")
        assert putUser.statusCode() == 201 && delUser.statusCode() == 201 && patUser.statusCode() == 201
        String putId = putUser.jsonPath().getString("id")
        String delId = delUser.jsonPath().getString("id")
        String patId = patUser.jsonPath().getString("id")
        String ghost = UUID.randomUUID().toString()
        List ops = [
            postOp("loc1", "w12loc_new_${sfx}"),
            [method: "PUT", path: "/Users/${putId}", data: [schemas: [USER_SCHEMA], userName: "w12loc_put_${sfx}", title: "t"]],
            [method: "DELETE", path: "/Users/${delId}"],
            [method: "PUT", path: "/Users/${ghost}", data: [schemas: [USER_SCHEMA], userName: "w12loc_ghost_${sfx}"]],
            postOp("loc2", "w12loc_new_${sfx}")
        ]
        List<String> expectedSuffix = [null, "/Users/${putId}", "/Users/${delId}", "/Users/${ghost}", null]
        if (isPatchSupported()) {
            ops << [method: "PATCH", path: "/Users/${patId}", data: buildPatchOp([[op: "replace", path: "title", value: "p"]])]
            expectedSuffix << "/Users/${patId}"
        }
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size() : "expected ${ops.size()} operation results but got ${res?.size()}"
        res.eachWithIndex { Map o, int i ->
            boolean failedPost = ops[i].method == "POST" && !((o.status as String) ==~ /2\d\d/)
            if (failedPost) return
            String loc = o.location as String
            assert loc : "RFC 7644 3.7: location is REQUIRED in the response of ${ops[i].method} operation #${i} (status ${o.status}) but was missing: ${o}"
            if (expectedSuffix[i] != null) {
                assert loc.endsWith(expectedSuffix[i]) : "RFC 7644 3.7: location '${loc}' must be the resource endpoint ending in ${expectedSuffix[i]}"
            } else {
                assert loc =~ /\/Users\/[^\/]+$/ : "RFC 7644 3.7: location '${loc}' of the created resource must be a /Users/{id} endpoint"
            }
        }
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2767-2769
    // When indicating a response with an HTTP status other than a 200-series response, the response body MUST be included.
    @SpecRef("RFC7644-3.7-96ff6c")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-96ff6c: response: When indicating a response with an HTTP status other than a 200-series response, the response..."() {
        expect:
        String sfx = uniq()
        Response ex = createUser(userName: "w12body_dup_${sfx}")
        assert ex.statusCode() == 201
        List ops = [
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("rb1", "w12body_dup_${sfx}"),
            [method: "PUT", path: "/Users/${UUID.randomUUID()}", data: [schemas: [USER_SCHEMA], userName: "w12body_g_${sfx}"]],
            [method: "FOO", path: "/Users/${UUID.randomUUID()}"]
        ]
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size()
        res.eachWithIndex { Map o, int i ->
            String st = o.status as String
            assert !(st ==~ /2\d\d/) : "test setup: operation #${i} was expected to fail but returned ${st}"
            assert o.response instanceof Map && !((Map) o.response).isEmpty() : "RFC 7644 3.7: operation #${i} with status ${st} MUST include the response body but got ${o}"
        }
    }

    // RFC 7644 section 3.7 (Bulk Operations), MAY, lines 2769-2770
    // For normal completion, the server MAY elect to omit the response body.
    @SpecRef("RFC7644-3.7-93252a")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-93252a: response: For normal completion, the server MAY elect to omit the response body."() {
        expect:
        String sfx = uniq()
        Response delUser = createUser(userName: "w12omit_del_${sfx}")
        assert delUser.statusCode() == 201
        String delId = delUser.jsonPath().getString("id")
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [
            postOp("om1", "w12omit_new_${sfx}"),
            [method: "DELETE", path: "/Users/${delId}"]]])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == 2
        assert (res[0].status as String) == "201" && (res[1].status as String) == "204"
        if (res[0].response != null) {
            assert res[0].response instanceof Map : "RFC 7644 3.7: response of a successful POST must be a JSON object but was ${res[0].response}"
            Map body = (Map) res[0].response
            String locId = (res[0].location as String).tokenize("/").last()
            assert body.id == locId : "RFC 7644 3.7: response body id '${body.id}' must match the created resource ${locId}"
            assert body.userName == "w12omit_new_${sfx}" : "RFC 7644 3.7: response body must be the created resource, userName was ${body.userName}"
        }
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2773-2774
    // When indicating an error, the "response" attribute MUST contain the detail error response as per Section 3.12.
    @SpecRef("RFC7644-3.7-3d9e9e")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-3d9e9e: status: When indicating an error, the 'response' attribute MUST contain the detail error response as per..."() {
        expect:
        String sfx = uniq()
        Response ex = createUser(userName: "w12err_dup_${sfx}")
        assert ex.statusCode() == 201
        List ops = [
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("er1", "w12err_dup_${sfx}"),
            [method: "FOO", path: "/Users/${UUID.randomUUID()}"]
        ]
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size()
        res.eachWithIndex { Map o, int i ->
            String st = o.status as String
            assert st ==~ /[45]\d\d/ : "test setup: operation #${i} was expected to fail but returned ${st}"
            assert o.response instanceof Map : "RFC 7644 3.7: failed operation #${i} must carry the error in 'response' but got ${o}"
            Map resp = (Map) o.response
            assert (resp.schemas as List)?.contains(ERROR_SCHEMA) : "RFC 7644 3.12: response of failed operation #${i} must use the Error schema, got ${resp.schemas}"
            assert resp.status instanceof String && resp.status == st : "RFC 7644 3.12: error status '${resp.status}' must be a string equal to the operation status '${st}'"
        }
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2776-2778
    // If a bulk job is processed successfully, HTTP response code 200 OK MUST be returned; otherwise, an appropriate HTTP error code MUST be returned.
    @SpecRef("RFC7644-3.7-51afc1")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-51afc1: If a bulk job is processed successfully, HTTP response code 200 OK MUST be returned; otherwise, an..."() {
        expect:
        Response ok = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [postOp("ok1", "w12st_${uniq()}")]])
        trackBulk(ok)
        assert ok.statusCode() == 200 : "RFC 7644 3.7: a successfully processed bulk job must return 200 but was ${ok.statusCode()}"
        assert (ok.jsonPath().getList("Operations")[0].status as String) == "201"
        Response bad = postBulk([schemas: [BULK_REQUEST_SCHEMA]])
        assert bad.statusCode() != 200 : "RFC 7644 3.7: a bulk job that cannot be processed must not return 200"
        assert bad.statusCode() in 400..499 : "RFC 7644 3.7: an invalid bulk job must return an appropriate HTTP error code (4xx) but was ${bad.statusCode()}"
        Response wrongSchema = postBulk([schemas: [USER_SCHEMA], Operations: [postOp("ws1", "w12ws_${uniq()}")]])
        trackBulk(wrongSchema)
        assert wrongSchema.statusCode() in 400..499 : "RFC 7644 3.7: a request with the wrong schema URI must return an HTTP error (4xx) but was ${wrongSchema.statusCode()}"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2780-2781
    // The service provider MUST continue performing as many changes as possible and disregard partial failures.
    @SpecRef("RFC7644-3.7-76a97d")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-76a97d: The service provider MUST continue performing as many changes as possible and disregard partial failures."() {
        expect:
        String sfx = uniq()
        String u1 = "w12cont1_${sfx}"
        String u3 = "w12cont3_${sfx}"
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [
            postOp("c1", u1),
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("c3", u3)]])
        trackBulk(r)
        assert r.statusCode() == 200 : "RFC 7644 3.7: expected 200 but was ${r.statusCode()}"
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == 3 : "RFC 7644 3.7: expected results for all 3 operations but got ${res?.size()}"
        assert (res[0].status as String) == "201"
        assert (res[1].status as String) == "404"
        assert (res[2].status as String) == "201" : "RFC 7644 3.7: the service provider MUST continue after a partial failure, but the operation after the failure returned ${res[2].status}"
        assert userExists(u3) : "RFC 7644 3.7: the user from the operation after the failed one was not created"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MAY, lines 2781-2782
    // The client MAY override this behavior by specifying a value for the "failOnErrors" attribute.
    @SpecRef("RFC7644-3.7-0dbbdc")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-0dbbdc: The client MAY override this behavior by specifying a value for the 'failOnErrors' attribute."() {
        expect:
        String sfx = uniq()
        String uA = "w12foe_a_${sfx}"
        Response a = postBulk([schemas: [BULK_REQUEST_SCHEMA], failOnErrors: 1, Operations: [
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("fe1", uA)]])
        trackBulk(a)
        assert a.statusCode() == 200 : "RFC 7644 3.7.3: expected 200 but was ${a.statusCode()}"
        List<Map> ra = a.jsonPath().getList("Operations")
        assert ra && (ra[0].status as String) == "404"
        assert ra.size() == 1 || !((ra[1].status as String) ==~ /2\d\d/) : "RFC 7644 3.7: with failOnErrors=1 the operation after the first error must not be executed, result ${ra[1]}"
        assert !userExists(uA) : "RFC 7644 3.7: with failOnErrors=1 the operation after the first error was executed (user created)"
        String uB = "w12foe_b_${sfx}"
        String uC = "w12foe_c_${sfx}"
        Response b = postBulk([schemas: [BULK_REQUEST_SCHEMA], failOnErrors: 2, Operations: [
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("fe2", uB),
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("fe3", uC)]])
        trackBulk(b)
        assert b.statusCode() == 200
        List<Map> rb = b.jsonPath().getList("Operations")
        assert rb && rb.size() >= 3 : "RFC 7644 3.7: with failOnErrors=2 operations up to and including the second error are processed, got ${rb}"
        assert (rb[1].status as String) == "201" : "RFC 7644 3.7: with failOnErrors=2 the first error must not stop processing, POST returned ${rb[1].status}"
        assert userExists(uB)
        assert !userExists(uC) : "RFC 7644 3.7: with failOnErrors=2 the operation after the second error was executed (user created)"
    }

    // RFC 7644 section 3.7 (Bulk Operations), MAY, lines 2787-2788
    // To be able to reference a newly created resource, the bulkId attribute MAY be specified when creating new resources.
    @SpecRef("RFC7644-3.7-cda80e")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-cda80e: To be able to reference a newly created resource, the bulkId attribute MAY be specified when creating new..."() {
        expect:
        String un = "w12bid_${uniq()}"
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [postOp("myTransientId", un)]])
        trackBulk(r)
        assert r.statusCode() == 200 : "RFC 7644 3.7: bulk POST with a bulkId must be processed (200) but was ${r.statusCode()}"
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == 1 && (res[0].status as String) == "201" : "RFC 7644 3.7: POST with bulkId must create the resource, got ${res}"
        String id = (res[0].location as String)?.tokenize("/")?.last()
        assert id : "created resource has no location"
        Response get = scimRequestQuiet().get("/Users/${id}")
        assert get.statusCode() == 200 && get.jsonPath().getString("userName") == un
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2790-2791
    // The service provider MUST return the same "bulkId" together with the newly created resource.
    @SpecRef("RFC7644-3.7-47e193")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-47e193: The service provider MUST return the same 'bulkId' together with the newly created resource."() {
        expect:
        String sfx = uniq()
        String uA = "w12ret_a_${sfx}"
        String uB = "w12ret_b_${sfx}"
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [postOp("idAlpha", uA), postOp("idBeta", uB)]])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == 2
        Map byBid = res.collectEntries { Map o -> [(o.bulkId as String): o] }
        assert byBid.keySet() == ["idAlpha", "idBeta"] as Set : "RFC 7644 3.7: the service provider MUST return the same bulkId with each created resource, got ${res.collect { it.bulkId }}"
        [idAlpha: uA, idBeta: uB].each { String bid, String name ->
            Map o = (Map) byBid[bid]
            assert (o.status as String) == "201"
            String id = (o.location as String)?.tokenize("/")?.last()
            assert id
            assert scimRequestQuiet().get("/Users/${id}").jsonPath().getString("userName") == name : "RFC 7644 3.7: bulkId ${bid} must map to the resource created by the operation carrying that bulkId"
        }
    }

    // RFC 7644 section 3.7 (Bulk Operations), MUST, lines 2796-2808
    // When doing so, the service provider MUST ensure that the client's intent is preserved and the same stateful result is achieved as for non-optimized processing.
    @SpecRef("RFC7644-3.7-b0e958")
    @RequiresCapability("bulk")
    def "RFC7644-3.7-b0e958: When doing so, the service provider MUST ensure that the client's intent is preserved and the same..."() {
        expect:
        String sfx = uniq()
        Response xr = createUser(userName: "w12ord_x_${sfx}", title: "start")
        Response yr = createUser(userName: "w12ord_y_${sfx}")
        assert xr.statusCode() == 201 && yr.statusCode() == 201
        String xId = xr.jsonPath().getString("id")
        String yId = yr.jsonPath().getString("id")
        String groupName = "w12ord_g_${sfx}"
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [
            [method: "PUT", path: "/Users/${xId}", data: [schemas: [USER_SCHEMA], userName: "w12ord_x_${sfx}", title: "first"]],
            [method: "PUT", path: "/Users/${xId}", data: [schemas: [USER_SCHEMA], userName: "w12ord_x_${sfx}", title: "second"]],
            [method: "PUT", path: "/Users/${yId}", data: [schemas: [USER_SCHEMA], userName: "w12ord_y_${sfx}", title: "before-delete"]],
            [method: "DELETE", path: "/Users/${yId}"],
            postOp("ordU", "w12ord_n_${sfx}"),
            [method: "POST", path: "/Groups", bulkId: "ordG", data: [schemas: [GROUP_SCHEMA], displayName: groupName, members: [[value: "bulkId:ordU", type: "User"]]]]]])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == 6
        assert res.collect { it.status as String } == ["200", "200", "200", "204", "201", "201"] : "RFC 7644 3.7: results of dependent operations were ${res.collect { it.status }}"
        assert scimRequestQuiet().get("/Users/${xId}").jsonPath().getString("title") == "second" : "RFC 7644 3.7: same-resource operations must keep the client's order (last PUT wins)"
        assert scimRequestQuiet().get("/Users/${yId}").statusCode() == 404 : "RFC 7644 3.7: a DELETE after a PUT must leave the resource deleted"
        String newUserId = (res[4].location as String).tokenize("/").last()
        String groupId = (res[5].location as String).tokenize("/").last()
        List members = scimRequestQuiet().get("/Groups/${groupId}").jsonPath().getList("members.value")
        assert members == [newUserId] : "RFC 7644 3.7: the group created after its member must contain the new user ${newUserId}, members were ${members}"
    }

    // RFC 7644 section 3.7.1 (Circular Reference Processing), MUST, lines 2815-2817
    // The service provider MUST try to resolve circular cross-references between resources in a single bulk job
    @SpecRef("RFC7644-3.7.1-8bd1ce")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.1-8bd1ce: The service provider MUST try to resolve circular cross-references between resources in a single bulk job"() {
        expect:
        Map g = runCircularGroups()
        assert g.outcome != "other" : "RFC 7644 3.7.1: the service provider MUST try to resolve circular cross-references (or stop with 409) but groups (RFC 7644 3.7.1 example): ${g.detail}"
    }

    // RFC 7644 section 3.7.1 (Circular Reference Processing), MAY, lines 2815-2817
    // but MAY stop after a failed attempt and instead return HTTP status code 409 (Conflict).
    @SpecRef("RFC7644-3.7.1-320758")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.1-320758: but MAY stop after a failed attempt and instead return HTTP status code 409 (Conflict)."() {
        expect:
        Map g = runCircularGroups()
        assert g.outcome in ["resolved", "conflict"] : "RFC 7644 3.7.1: a server must either resolve circular cross-references or stop and return 409 (Conflict), but ${g.name}: ${g.detail}"
    }

    // RFC 7644 section 3.7.2 ("bulkId" Temporary Identifiers), MUST, lines 2961-2962
    // The service provider MUST replace the string "bulkId:qwerty" with the permanent resource id once created.
    @SpecRef("RFC7644-3.7.2-a2687c")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.2-a2687c: The service provider MUST replace the string 'bulkId:qwerty' with the permanent resource id once created."() {
        expect:
        String sfx = uniq()
        String un = "w12ref_u_${sfx}"
        String gn = "w12ref_g_${sfx}"
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [
            postOp("qwerty", un),
            [method: "POST", path: "/Groups", bulkId: "grp", data: [schemas: [GROUP_SCHEMA], displayName: gn, members: [[value: "bulkId:qwerty", type: "User"]]]]]])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == 2 && res.every { (it.status as String) == "201" } : "RFC 7644 3.7.2: user+group bulk with bulkId reference failed: ${res}"
        String userId = (res[0].location as String).tokenize("/").last()
        String groupId = (res[1].location as String).tokenize("/").last()
        List members = scimRequestQuiet().get("/Groups/${groupId}").jsonPath().getList("members.value")
        assert members == [userId] : "RFC 7644 3.7.2: 'bulkId:qwerty' must be replaced with the permanent id ${userId}, group members were ${members}"
    }

    // RFC 7644 section 3.7.2 ("bulkId" Temporary Identifiers), MUST, lines 3144-3145
    // Extensions that include references to other resources MUST be handled in the same way by the service provider.
    @SpecRef("RFC7644-3.7.2-14c13f")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.2-14c13f: Extensions that include references to other resources MUST be handled in the same way by the service provider."() {
        expect:
        if (getCapabilityState("enterprise") == CapabilityState.UNSUPPORTED) {
            throw new TestAbortedException("Server does not support the enterprise user extension")
        }
        String sfx = uniq()
        String ENT = ENTERPRISE_USER_SCHEMA
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [
            postOp("mgr", "w12ext_m_${sfx}"),
            [method: "POST", path: "/Users", bulkId: "emp", data: [
                schemas: [USER_SCHEMA, ENT], userName: "w12ext_e_${sfx}",
                (ENT): [manager: [value: "bulkId:mgr"]]]]]])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == 2 && res.every { (it.status as String) == "201" } : "RFC 7644 3.7.2: bulk with bulkId in an extension attribute failed: ${res}"
        String mgrId = (res[0].location as String).tokenize("/").last()
        String empId = (res[1].location as String).tokenize("/").last()
        def mv = scimRequestQuiet().get("/Users/${empId}").jsonPath().get("'${ENT}'.manager.value")
        assert mv == mgrId : "RFC 7644 3.7.2: bulkId reference in the enterprise extension manager.value must be replaced with ${mgrId}, but was '${mv}'"
    }

    // RFC 7644 section 3.7.3 (Response and Error Handling), MUST, lines 3202-3203
    // The service provider response MUST include the result of all processed operations.
    @SpecRef("RFC7644-3.7.3-4eef65")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.3-4eef65: The service provider response MUST include the result of all processed operations."() {
        expect:
        String sfx = uniq()
        Response ex = createUser(userName: "w12all_dup_${sfx}")
        Response putU = createUser(userName: "w12all_put_${sfx}")
        Response delU = createUser(userName: "w12all_del_${sfx}")
        assert ex.statusCode() == 201 && putU.statusCode() == 201 && delU.statusCode() == 201
        String putId = putU.jsonPath().getString("id")
        String delId = delU.jsonPath().getString("id")
        List ops = [
            postOp("all1", "w12all_new_${sfx}"),
            [method: "PUT", path: "/Users/${putId}", data: [schemas: [USER_SCHEMA], userName: "w12all_put_${sfx}", title: "t"]],
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("all2", "w12all_dup_${sfx}"),
            [method: "DELETE", path: "/Users/${delId}"]
        ]
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size() : "RFC 7644 3.7.3: response MUST include the result of all ${ops.size()} processed operations but has ${res?.size()}"
        res.eachWithIndex { Map o, int i ->
            assert o.method == ops[i].method : "RFC 7644 3.7.3: result #${i} has method '${o.method}' but operation was ${ops[i].method}"
            assert o.status : "RFC 7644 3.7.3: result #${i} has no status"
            if (ops[i].bulkId) assert o.bulkId == ops[i].bulkId : "RFC 7644 3.7.3: result #${i} must carry bulkId ${ops[i].bulkId}"
        }
        assert res.collect { it.status as String } == ["201", "200", "404", "409", "204"] : "RFC 7644 3.7.3: per-operation statuses were ${res.collect { it.status }}"
    }

    // RFC 7644 section 3.7.3 (Response and Error Handling), MUST, lines 3203-3205
    // A "location" attribute that includes the resource's endpoint MUST be returned for all operations except for failed POST operations (which have no location).
    @SpecRef("RFC7644-3.7.3-dd7b62")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.3-dd7b62: A 'location' attribute that includes the resource's endpoint MUST be returned for all operations except..."() {
        expect:
        String sfx = uniq()
        Response ex = createUser(userName: "w12floc_dup_${sfx}")
        assert ex.statusCode() == 201
        String g1 = UUID.randomUUID().toString()
        String g2 = UUID.randomUUID().toString()
        String g3 = UUID.randomUUID().toString()
        List ops = [
            [method: "DELETE", path: "/Users/${g1}"],
            [method: "PUT", path: "/Users/${g2}", data: [schemas: [USER_SCHEMA], userName: "w12floc_ghost_${sfx}"]],
            postOp("fl1", "w12floc_dup_${sfx}")
        ]
        List<String> expectedSuffix = ["/Users/${g1}", "/Users/${g2}", null]
        if (isPatchSupported()) {
            ops << [method: "PATCH", path: "/Users/${g3}", data: buildPatchOp([[op: "replace", path: "title", value: "p"]])]
            expectedSuffix << "/Users/${g3}"
        }
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size()
        res.eachWithIndex { Map o, int i ->
            assert !((o.status as String) ==~ /2\d\d/) : "test setup: operation #${i} was expected to fail but returned ${o.status}"
            if (expectedSuffix[i] == null) {
                assert !o.location : "RFC 7644 3.7.3: a failed POST has no location but result #${i} has '${o.location}'"
            } else {
                assert o.location : "RFC 7644 3.7.3: location MUST be returned for the failed ${ops[i].method} operation #${i} (status ${o.status}), got ${o}"
                assert (o.location as String).endsWith(expectedSuffix[i]) : "RFC 7644 3.7.3: location '${o.location}' must be the resource endpoint ${expectedSuffix[i]}"
            }
        }
    }

    // RFC 7644 section 3.7.3 (Response and Error Handling), MUST, lines 3207-3209
    // The status attribute MUST include the code attribute that holds the HTTP response code that would have been returned if a single HTTP request would have been used.
    @SpecRef("RFC7644-3.7.3-21bff2")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.3-21bff2: The status attribute MUST include the code attribute that holds the HTTP response code that would have..."() {
        expect:
        String sfx = uniq()
        // twins: the same request is sent once as a single HTTP request and once inside the bulk
        Response exS = createUser(userName: "w12code_dupS_${sfx}")
        Response putS = createUser(userName: "w12code_putS_${sfx}")
        Response delS = createUser(userName: "w12code_delS_${sfx}")
        Response patS = createUser(userName: "w12code_patS_${sfx}")
        Response putB = createUser(userName: "w12code_putB_${sfx}")
        Response delB = createUser(userName: "w12code_delB_${sfx}")
        Response patB = createUser(userName: "w12code_patB_${sfx}")
        [exS, putS, delS, patS, putB, delB, patB].each { assert it.statusCode() == 201 }
        String putSid = putS.jsonPath().getString("id")
        String delSid = delS.jsonPath().getString("id")
        String patSid = patS.jsonPath().getString("id")
        String putBid = putB.jsonPath().getString("id")
        String delBid = delB.jsonPath().getString("id")
        String patBid = patB.jsonPath().getString("id")
        Map patchBody = buildPatchOp([[op: "replace", path: "title", value: "p"]])
        List<String> single = []
        List ops = []
        Response s1 = scimRequestQuiet().body(JsonOutput.toJson(userData("w12code_newS_${sfx}"))).post("/Users")
        if (s1.statusCode() == 201) createdUserIds << s1.jsonPath().getString("id")
        single << (s1.statusCode() as String)
        ops << postOp("cd1", "w12code_newB_${sfx}")
        single << (scimRequestQuiet().body(JsonOutput.toJson(userData("w12code_putS_${sfx}", [title: "t"]))).put("/Users/${putSid}").statusCode() as String)
        ops << [method: "PUT", path: "/Users/${putBid}", data: userData("w12code_putB_${sfx}", [title: "t"])]
        single << (scimRequestQuiet().delete("/Users/${delSid}").statusCode() as String)
        ops << [method: "DELETE", path: "/Users/${delBid}"]
        single << (scimRequestQuiet().delete("/Users/${UUID.randomUUID()}").statusCode() as String)
        ops << [method: "DELETE", path: "/Users/${UUID.randomUUID()}"]
        single << (scimRequestQuiet().body(JsonOutput.toJson(userData("w12code_dupS_${sfx}"))).post("/Users").statusCode() as String)
        ops << postOp("cd2", "w12code_dupS_${sfx}")
        if (isPatchSupported()) {
            single << (scimRequestQuiet().body(JsonOutput.toJson(patchBody)).patch("/Users/${patSid}").statusCode() as String)
            ops << [method: "PATCH", path: "/Users/${patBid}", data: patchBody]
        }
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size()
        res.eachWithIndex { Map o, int i ->
            assert o.status instanceof String && (o.status as String) ==~ /\d{3}/ : "RFC 7644 3.7.3: status of ${ops[i].method} #${i} must be a three digit HTTP code string but was '${o.status}'"
            assert (o.status as String) == single[i] : "RFC 7644 3.7.3: bulk ${ops[i].method} #${i} reported ${o.status} but the single HTTP request returned ${single[i]}"
        }
    }

    // RFC 7644 section 3.7.3 (Response and Error Handling), MUST, lines 3209-3211
    // If an error occurred, the status MUST also include the description attribute containing a human-readable explanation of the error.
    @SpecRef("RFC7644-3.7.3-5e0949")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.3-5e0949: If an error occurred, the status MUST also include the description attribute containing a human-readable..."() {
        expect:
        String sfx = uniq()
        Response ex = createUser(userName: "w12desc_dup_${sfx}")
        assert ex.statusCode() == 201
        List ops = [
            [method: "DELETE", path: "/Users/${UUID.randomUUID()}"],
            postOp("ds1", "w12desc_dup_${sfx}"),
            [method: "FOO", path: "/Users/${UUID.randomUUID()}"]
        ]
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        trackBulk(r)
        assert r.statusCode() == 200
        List<Map> res = r.jsonPath().getList("Operations")
        assert res?.size() == ops.size()
        res.eachWithIndex { Map o, int i ->
            assert (o.status as String) ==~ /[45]\d\d/ : "test setup: operation #${i} was expected to fail but returned ${o.status}"
            String detail = (o.response instanceof Map) ? ((Map) o.response).detail as String : null
            assert detail != null && !detail.isBlank() : "RFC 7644 3.7.3: failed operation #${i} (${o.status}) MUST include a human-readable description of the error (response.detail) but got ${o}"
        }
    }

    // RFC 7644 section 3.7.4 (Maximum Operations), MUST, lines 3492-3493
    // The service provider MUST define the maximum number of operations and maximum payload size a client may send in a single request.
    @SpecRef("RFC7644-3.7.4-215282")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.4-215282: The service provider MUST define the maximum number of operations and maximum payload size a client may..."() {
        expect:
        Response r = scimRequestQuiet().get("/ServiceProviderConfig")
        assert r.statusCode() == 200
        def bulk = r.jsonPath().get("bulk")
        assert bulk instanceof Map : "RFC 7644 3.7.4 / RFC 7643 5: ServiceProviderConfig must contain the bulk complex attribute"
        assert bulk.maxOperations instanceof Integer && bulk.maxOperations > 0 : "RFC 7644 3.7.4: the maximum number of operations must be defined (bulk.maxOperations), got ${bulk.maxOperations}"
        assert bulk.maxPayloadSize instanceof Integer && bulk.maxPayloadSize > 0 : "RFC 7644 3.7.4: the maximum payload size must be defined (bulk.maxPayloadSize), got ${bulk.maxPayloadSize}"
    }

    // RFC 7644 section 3.7.4 (Maximum Operations), MAY, lines 3493-3495
    // These limits MAY be retrieved from the service provider configuration (see 'bulk' in Sections 5 and 8.5 of [RFC7643]).
    @SpecRef("RFC7644-3.7.4-77d179")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.4-77d179: These limits MAY be retrieved from the service provider configuration (see 'bulk' in Sections 5 and 8.5 of..."() {
        expect:
        Response spc = scimRequestQuiet().get("/ServiceProviderConfig")
        assert spc.statusCode() == 200
        Integer maxOps = spc.jsonPath().get("bulk.maxOperations") as Integer
        Integer maxSize = spc.jsonPath().get("bulk.maxPayloadSize") as Integer
        if (maxOps == null || maxSize == null) {
            throw new TestAbortedException("Server does not advertise the bulk limits in ServiceProviderConfig")
        }
        if (maxOps > 2000) {
            throw new TestAbortedException("maxOperations ${maxOps} too large to probe")
        }
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: (1..maxOps).collect { [method: "DELETE", path: "/Users/${UUID.randomUUID()}"] }])
        assert r.statusCode() == 200 : "RFC 7644 3.7.4: a request with exactly the advertised maxOperations (${maxOps}) operations must be accepted but got ${r.statusCode()}"
        assert r.jsonPath().getList("Operations").size() == maxOps
    }

    // RFC 7644 section 3.7.4 (Maximum Operations), MUST, lines 3495-3497
    // If either limit is exceeded, the service provider MUST return HTTP response code 413 (Payload Too Large).
    @SpecRef("RFC7644-3.7.4-155753")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.4-155753: If either limit is exceeded, the service provider MUST return HTTP response code 413 (Payload Too Large)."() {
        expect:
        Map lim = limits()
        String sfx = uniq()
        Response ops = oversizedOps(lim)
        assert ops.statusCode() == 413 : "RFC 7644 3.7.4: more than maxOperations (${lim.maxOps}) operations must return 413 but was ${ops.statusCode()}"
        String un = "w12big_${sfx}"
        Response size = oversizedPayload(lim, un)
        trackBulk(size)
        assert size.statusCode() == 413 : "RFC 7644 3.7.4: a payload larger than maxPayloadSize (${lim.maxSize}) must return 413 but was ${size.statusCode()}"
        assert !userExists(un) : "RFC 7644 3.7.4: an oversized bulk request must not be executed"
    }

    // RFC 7644 section 3.7.4 (Maximum Operations), MUST, lines 3497-3498
    // The returned response MUST specify the limit exceeded in the body of the error response.
    @SpecRef("RFC7644-3.7.4-1bc850")
    @RequiresCapability("bulk")
    def "RFC7644-3.7.4-1bc850: The returned response MUST specify the limit exceeded in the body of the error response."() {
        expect:
        Map lim = limits()
        Response ops = oversizedOps(lim)
        assert ops.statusCode() == 413 : "RFC 7644 3.7.4: more than maxOperations operations must return 413 but was ${ops.statusCode()}"
        String d1 = (ops.jsonPath().getString("detail") ?: "").toLowerCase()
        assert d1.contains("maxoperations") || d1.contains("operations") : "RFC 7644 3.7.4: the 413 body must specify the limit exceeded (operations), detail was '${d1}'"
        assert d1.contains(lim.maxOps as String) : "RFC 7644 3.7.4: the 413 body should state the maxOperations value ${lim.maxOps}, detail was '${d1}'"
        String un = "w12big2_${uniq()}"
        Response size = oversizedPayload(lim, un)
        trackBulk(size)
        assert size.statusCode() == 413
        String d2 = (size.jsonPath().getString("detail") ?: "").toLowerCase()
        assert d2.contains("maxpayloadsize") || d2.contains("payload") || d2.contains("size") : "RFC 7644 3.7.4: the 413 body must specify the limit exceeded (payload size), detail was '${d2}'"
        assert d2.contains(lim.maxSize as String) : "RFC 7644 3.7.4: the 413 body should state the maxPayloadSize value ${lim.maxSize}, detail was '${d2}'"
    }

    // ---------------------------------------------------------------- helpers

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8)
    }

    private Response postBulk(Map payload) {
        return scimRequestQuiet().body(JsonOutput.toJson(payload)).post("/Bulk")
    }

    private Map userData(String userName, Map more = [:]) {
        Map m = [schemas: [USER_SCHEMA], userName: userName]
        m.putAll(more)
        return m
    }

    private Map postOp(String bulkId, String userName, Map more = [:]) {
        return [method: "POST", path: "/Users", bulkId: bulkId, data: userData(userName, more)]
    }

    /** Registers every resource a bulk response says it created, so cleanupSpec deletes it. */
    private void trackBulk(Response r) {
        if (r == null || r.statusCode() != 200) return
        try {
            List<Map> ops = r.jsonPath().getList("Operations") ?: []
            ops.each { Map o ->
                String st = o.status as String
                String loc = o.location as String
                if (o.method == "POST" && st ==~ /2\d\d/ && loc) {
                    String id = loc.tokenize("/").last()
                    if (loc.contains("/Groups/")) createdGroupIds << id else createdUserIds << id
                }
            }
        } catch (Exception ignored) {
            // unparsable bulk response: nothing to track
        }
    }

    /** True when a user with this userName exists (and registers it for cleanup). */
    private boolean userExists(String userName) {
        Response r = scimRequestQuiet().queryParam("filter", "userName eq \"${userName}\"").get("/Users")
        assert r.statusCode() == 200 : "lookup of user ${userName} failed with ${r.statusCode()}"
        List<Map> found = r.jsonPath().getList("Resources") ?: []
        found.each { Map u -> createdUserIds << (u.id as String) }
        return !found.isEmpty()
    }

    /** The operation at idx must have been rejected: HTTP 4xx for the whole job or a 4xx result for that operation. */
    private void assertOpRejected(Response r, int idx, String what) {
        int sc = r.statusCode()
        if (sc != 200) {
            assert sc in 400..499 : "${what}: expected a 4xx rejection but got HTTP ${sc}"
            return
        }
        List<Map> ops = r.jsonPath().getList("Operations")
        assert ops != null && ops.size() > idx : "${what}: no result for the operation (bulk response ${r.body().asString().take(300)})"
        String st = ops[idx].status as String
        assert st ==~ /4\d\d/ : "${what}: operation must be rejected with a 4xx status but got '${st}' (${ops[idx]})"
    }

    private Map limits() {
        Response spc = scimRequestQuiet().get("/ServiceProviderConfig")
        Integer maxOps = spc.jsonPath().get("bulk.maxOperations") as Integer
        Integer maxSize = spc.jsonPath().get("bulk.maxPayloadSize") as Integer
        if (maxOps == null || maxSize == null) {
            throw new TestAbortedException("Server does not advertise the bulk limits in ServiceProviderConfig")
        }
        if (maxOps > 5000 || maxSize > 8 * 1024 * 1024) {
            throw new TestAbortedException("Advertised bulk limits (${maxOps} operations, ${maxSize} bytes) too large to probe")
        }
        return [maxOps: maxOps, maxSize: maxSize]
    }

    private Response oversizedOps(Map lim) {
        int n = (lim.maxOps as int) + 1
        return postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: (1..n).collect { [method: "DELETE", path: "/Users/${UUID.randomUUID()}"] }])
    }

    private Response oversizedPayload(Map lim, String userName) {
        String filler = "x" * ((lim.maxSize as int) + 1024)
        return postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [postOp("big1", userName, [title: filler])]])
    }

    /** RFC 7644 3.7.1 example: two groups that reference each other. */
    private Map runCircularGroups() {
        String sfx = uniq()
        Response r = postBulk([schemas: [BULK_REQUEST_SCHEMA], Operations: [
            [method: "POST", path: "/Groups", bulkId: "qwerty", data: [schemas: [GROUP_SCHEMA], displayName: "w12circ_a_${sfx}", members: [[type: "Group", value: "bulkId:ytrewq"]]]],
            [method: "POST", path: "/Groups", bulkId: "ytrewq", data: [schemas: [GROUP_SCHEMA], displayName: "w12circ_b_${sfx}", members: [[type: "Group", value: "bulkId:qwerty"]]]]]])
        trackBulk(r)
        Map out = [name: "groups"]
        if (r.statusCode() == 409) {
            return out + [outcome: "conflict", detail: "whole job rejected with 409"]
        }
        if (r.statusCode() != 200) {
            return out + [outcome: "other", detail: "HTTP ${r.statusCode()} for the circular group job: ${r.body().asString().take(300)}"]
        }
        List<Map> ops = r.jsonPath().getList("Operations") ?: []
        if (ops.size() == 2 && ops.every { (it.status as String) == "409" }) {
            return out + [outcome: "conflict", detail: "operations rejected with 409"]
        }
        if (ops.size() != 2 || !ops.every { (it.status as String) == "201" }) {
            return out + [outcome: "other", detail: "circular group operations returned ${ops.collect { it.status }} instead of 201/201 (resolved) or 409 (conflict): ${ops.collect { it.response?.detail }}"]
        }
        String idA = (ops[0].location as String).tokenize("/").last()
        String idB = (ops[1].location as String).tokenize("/").last()
        List ma = scimRequestQuiet().get("/Groups/${idA}").jsonPath().getList("members.value")
        List mb = scimRequestQuiet().get("/Groups/${idB}").jsonPath().getList("members.value")
        if (ma != [idB] || mb != [idA]) {
            return out + [outcome: "other", detail: "operations returned 201 but the references were not resolved: members of A ${ma}, of B ${mb}"]
        }
        return out + [outcome: "resolved", detail: "resolved"]
    }
}
