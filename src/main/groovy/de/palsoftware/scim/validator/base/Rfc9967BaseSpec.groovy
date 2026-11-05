package de.palsoftware.scim.validator.base

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Shared

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared base class for RFC 9967 (SCIM Profile for Security Event Tokens) specifications.
 * Provides helper logic, event token parsers, async polling, and test lifecycle cleanup.
 */
abstract class Rfc9967BaseSpec extends ScimBaseSpec {

    // ---------------------------------------------------------------------------------------------
    // Helpers for asynchronous requests and Security Event Tokens (RFC 9967)
    // ---------------------------------------------------------------------------------------------

    static final String ASYNC_RESP_EVENT = "urn:ietf:params:scim:event:misc:asyncresp"
    static final String PROV_PREFIX = "urn:ietf:params:scim:event:prov:"

    @Shared List<String> asyncUserNames = new CopyOnWriteArrayList<>()

    def cleanupSpec() {
        asyncUserNames.unique().each { String name ->
            try {
                Response r = scimRequestQuiet().queryParam("filter", "userName eq \"${name}\"").get("/Users")
                if (r.statusCode() == 200) {
                    (r.jsonPath().getList("Resources") ?: []).each { Map u ->
                        if (u.userName == name && u.id) deleteUser(u.id as String)
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    protected String uniqueUserName() {
        return "w9967_${UUID.randomUUID().toString().replace('-', '').substring(0, 12)}"
    }

    protected Response asyncRequest(String method, String path, Map body = null, Map<String, String> headers = [:]) {
        def req = scimRequest().header("Prefer", "respond-async")
        headers.each { k, v -> req = req.header(k, v) }
        if (body != null) req = req.body(JsonOutput.toJson(body))
        switch (method) {
            case "POST": return req.post(path)
            case "PUT": return req.put(path)
            case "PATCH": return req.patch(path)
            case "DELETE": return req.delete(path)
        }
        throw new IllegalArgumentException("unsupported method ${method}")
    }

    protected void trackSyncResponse(Response r) {
        try {
            if (r.statusCode() == 201) {
                String id = r.jsonPath().getString("id")
                if (id) createdUserIds << id
            }
        } catch (Exception ignored) {}
    }

    /** Aborts when the server answered normally (allowed by RFC 9967 2.5.1.1); fails on anything but 202. */
    protected void requireAccepted(Response r, String what) {
        int sc = r.statusCode()
        if (sc in [200, 201, 204]) {
            trackSyncResponse(r)
            throw new TestAbortedException("Server answered the asynchronous ${what} request normally (HTTP ${sc}); RFC 9967 2.5.1.1 allows a normal SCIM response, so no 202 requirement applies")
        }
        assert sc == 202 : "RFC 9967 2.5.1.1: asynchronous ${what} request must return 202 or a normal SCIM response but was ${sc}"
    }

    protected Map createAsyncUser(Map extra = [:]) {
        String userName = uniqueUserName()
        asyncUserNames << userName
        Map payload = [schemas: [USER_SCHEMA], userName: userName]
        payload.putAll(extra)
        Response r = asyncRequest("POST", "/Users", payload)
        requireAccepted(r, "create")
        return [userName: userName, response: r]
    }

    protected Map syncUser(Map extra = [:]) {
        String userName = uniqueUserName()
        Map payload = [schemas: [USER_SCHEMA], userName: userName]
        payload.putAll(extra)
        Response r = scimRequestQuiet().body(JsonOutput.toJson(payload)).post("/Users")
        assert r.statusCode() == 201 : "Precondition: synchronous user creation must return 201 but was ${r.statusCode()}"
        String id = r.jsonPath().getString("id")
        createdUserIds << id
        return [id: id, userName: userName]
    }

    protected String findUserId(String userName) {
        for (int i = 0; i < 20; i++) {
            Response r = scimRequestQuiet().queryParam("filter", "userName eq \"${userName}\"").get("/Users")
            if (r.statusCode() == 200) {
                Map match = (r.jsonPath().getList("Resources") ?: []).find { it.userName == userName } as Map
                if (match?.id) {
                    createdUserIds << (match.id as String)
                    return match.id as String
                }
            }
            sleep(250)
        }
        return null
    }

    protected String resolveUrl(String ref) {
        if (ref ==~ /(?i)^https?:.*/) return ref
        String origin = state().scimApiUrl
        if (BASE_PATH && ref.startsWith(BASE_PATH)) return origin + ref
        return BASE_URL + (ref.startsWith("/") ? ref : "/" + ref)
    }

    protected Response getUrl(String url) {
        return scimRequestQuiet().accept("*/*").baseUri(url).basePath("").get()
    }

    /** GET the Location header of an accepted response (same origin only, polling while not ready). */
    protected Response getLocation(Response accepted) {
        String loc = accepted.header("Location")
        assert loc : "RFC 9967 2.5.1.1: the 202 response must carry a Location header"
        String url = resolveUrl(loc)
        if (URI.create(url).authority != URI.create(state().scimApiUrl).authority) {
            throw new TestAbortedException("Location points to another origin (${URI.create(url).authority}); not queried to avoid leaking credentials")
        }
        Response r = null
        for (int i = 0; i < 10; i++) {
            r = getUrl(url)
            if (!(r.statusCode() in [202, 404])) break
            sleep(500)
        }
        return r
    }

    protected Map parseClaims(Response r) {
        if (r.statusCode() != 200) return null
        String body = r.body().asString()?.trim()
        if (!body) return null
        try {
            if (body.startsWith("{")) return new JsonSlurper().parseText(body) as Map
            String[] parts = body.split("\\.")
            if (parts.length >= 2) {
                return new JsonSlurper().parseText(new String(Base64.urlDecoder.decode(parts[1]), "UTF-8")) as Map
            }
        } catch (Exception ignored) {}
        return null
    }

    /** The SCIM Event Token (claims) found at the Location of an accepted response, or null when Location is not a token. */
    protected Map fetchSet(Response accepted) {
        Map claims = parseClaims(getLocation(accepted))
        return (claims != null && claims.events instanceof Map) ? claims : null
    }

    protected Map eventsOf(Map claims) {
        return (claims.events instanceof Map) ? (claims.events as Map) : [:]
    }

    protected Map creationSet(Map extra = [:]) {
        Map created = createAsyncUser(extra)
        Map claims = fetchSet(created.response as Response)
        if (claims == null) throw new TestAbortedException("Location of the 202 response did not return a Security Event Token")
        return [claims: claims, userName: created.userName, id: findUserId(created.userName as String), accepted: created.response]
    }

    protected Map updateSet(String method) {
        Map u = syncUser()
        Response r = (method == "PUT")
            ? asyncRequest("PUT", "/Users/${u.id}", [schemas: [USER_SCHEMA], userName: u.userName, displayName: "w9967 put"])
            : asyncRequest("PATCH", "/Users/${u.id}", [schemas: [PATCH_OP_SCHEMA], Operations: [[op: "replace", path: "displayName", value: "w9967 patch"]]])
        requireAccepted(r, method.toLowerCase())
        Map claims = fetchSet(r)
        if (claims == null) throw new TestAbortedException("Location of the 202 response did not return a Security Event Token")
        return [claims: claims, id: u.id, userName: u.userName, accepted: r]
    }

    protected Map deletionSet() {
        Map u = syncUser()
        Response r = asyncRequest("DELETE", "/Users/${u.id}")
        requireAccepted(r, "delete")
        Map claims = fetchSet(r)
        if (claims == null) throw new TestAbortedException("Location of the 202 response did not return a Security Event Token")
        return [claims: claims, id: u.id, accepted: r]
    }

    /** Event tokens for a create, a PUT and a PATCH. */
    protected List<Map> provisioningSets() {
        return [creationSet().claims, updateSet("PUT").claims, updateSet("PATCH").claims] as List<Map>
    }

    /** [uri, payload] of every provisioning event that carries a payload (not delete). Aborts when there is none. */
    protected List<Map> provPayloads(List<Map> sets) {
        List<Map> out = []
        for (Map claims : sets) {
            eventsOf(claims).each { k, v ->
                if (k.toString().startsWith(PROV_PREFIX) && !k.toString().contains(":delete") && v instanceof Map) {
                    out << [uri: k.toString(), payload: v]
                }
            }
        }
        if (out.isEmpty()) throw new TestAbortedException("No provisioning event with a payload in the retrieved tokens")
        return out
    }

    /** Async bulk request; returns [synchronous: true/false, response, ops, bulkIds, txn]. */
    protected Map asyncBulk(boolean forwardReference) {
        if (!isBulkSupported()) throw new TestAbortedException("Bulk is not supported")
        String tag = UUID.randomUUID().toString().replace('-', '').substring(0, 8)
        String u1 = uniqueUserName(), u2 = uniqueUserName()
        asyncUserNames << u1 << u2
        List<Map> ops = forwardReference ? [
            [method: "POST", path: "/Groups", bulkId: "bk-g-${tag}", data: [schemas: [GROUP_SCHEMA], displayName: "w9967_grp_${tag}", members: [[value: "bulkId:bk-u1-${tag}"]]]],
            [method: "POST", path: "/Users", bulkId: "bk-u1-${tag}", data: [schemas: [USER_SCHEMA], userName: u1]],
            [method: "POST", path: "/Users", bulkId: "bk-u2-${tag}", data: [schemas: [USER_SCHEMA], userName: u2]]
        ] : [
            [method: "POST", path: "/Users", bulkId: "bk-u1-${tag}", data: [schemas: [USER_SCHEMA], userName: u1]],
            [method: "POST", path: "/Users", bulkId: "bk-u2-${tag}", data: [schemas: [USER_SCHEMA], userName: u2]]
        ]
        Response r = asyncRequest("POST", "/Bulk", [schemas: [BULK_REQUEST_SCHEMA], Operations: ops])
        if (r.statusCode() == 200) {
            try {
                (r.jsonPath().getList("Operations") ?: []).each { Map op ->
                    String loc = op.location as String
                    if (loc) {
                        String id = loc.substring(loc.lastIndexOf('/') + 1)
                        if (loc.contains("/Groups/")) createdGroupIds << id else createdUserIds << id
                    }
                }
            } catch (Exception ignored) {}
            return [synchronous: true, response: r, ops: ops, bulkIds: ops.collect { it.bulkId }, txn: null]
        }
        requireAccepted(r, "bulk")
        return [synchronous: false, response: r, ops: ops, bulkIds: ops.collect { it.bulkId }, txn: r.header("Set-Txn")]
    }

    /** Completion event token of bulk operation {index}, located next to the Location of the bulk 202 response. */
    protected Map bulkOperationSet(Map bulk, int index) {
        String txn = bulk.txn as String
        String loc = (bulk.response as Response).header("Location")
        assert txn && loc : "RFC 9967 2.5.1.1: the 202 response must carry Set-Txn and Location"
        String url = resolveUrl(loc)
        if (!url.endsWith(txn)) throw new TestAbortedException("Location does not end with the txn value; cannot locate the per-operation events")
        String opUrl = url + ":" + index
        Response r = null
        for (int i = 0; i < 10; i++) {
            r = getUrl(opUrl)
            if (r.statusCode() != 404 && r.statusCode() != 202) break
            sleep(500)
        }
        return parseClaims(r)
    }
}
