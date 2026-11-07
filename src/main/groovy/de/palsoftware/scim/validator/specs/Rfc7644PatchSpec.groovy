package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import de.palsoftware.scim.validator.base.CapabilityState
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "PATCH"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644PatchSpec extends ScimBaseSpec {

    // ─── helpers ─────────────────────────────────────────────────────────

    private static String uid8() {
        return UUID.randomUUID().toString().substring(0, 8)
    }

    /** PATCH /Users/{id} with a well-formed PatchOp body. */
    private Response patchUser(String id, List<Map> operations, Map<String, String> query = [:]) {
        def req = scimRequestQuiet()
        query.each { k, v -> req = req.queryParam(k, v) }
        return req.body(JsonOutput.toJson(buildPatchOp(operations))).patch("/Users/${id}")
    }

    /** PATCH /Users/{id} with an arbitrary (possibly malformed) body. */
    private Response patchUserRaw(String id, Object body) {
        String text = (body instanceof String) ? body : JsonOutput.toJson(body)
        return scimRequestQuiet().body(text).patch("/Users/${id}")
    }

    private Map getUser(String id) {
        def r = scimRequestQuiet().get("/Users/${id}")
        assert r.statusCode() == 200 : "GET /Users/${id} must return 200 but was ${r.statusCode()}"
        return r.jsonPath().getMap("")
    }

    /** Creates a user (no enterprise extension) and returns its id. */
    private String newUser(Map overrides = [:]) {
        def r = createFullUser(new LinkedHashMap(overrides + [includeEnterprise: false]))
        assert r.statusCode() == 201 : "Setup: creating user failed with ${r.statusCode()}: ${r.body().asString()}"
        return r.jsonPath().getString("id")
    }

    private static boolean unassigned(Object v) {
        return v == null || (v instanceof Collection && v.isEmpty()) || (v instanceof Map && v.isEmpty())
    }

    private void assertPatchOk(Response r, String what) {
        assert r.statusCode() in [200, 204] : "RFC 7644 3.5.2: ${what} must succeed with 200 or 204 but was ${r.statusCode()}: ${r.body().asString()}"
    }

    private void requireEnterprise() {
        if (getCapabilityState("enterprise") != CapabilityState.SUPPORTED) {
            throw new TestAbortedException("Server does not advertise the enterprise User extension")
        }
    }


    // RFC 7644 section 3.5.2 (Modifying with PATCH), MAY, lines 1782-1784
    // HTTP PATCH is an OPTIONAL server function that enables clients to update one or more attributes of a SCIM resource using a sequence of operations to "add", "remove", or "replace" values.
    @SpecRef("RFC7644-3.5.2-708fa7")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-708fa7: HTTP PATCH is an OPTIONAL server function that enables clients to update one or more attributes of a SCIM..."() {
        given:
        String id = newUser(title: "Before")
        String nick = "nick_${uid8()}"

        when:
        def r = patchUser(id, [
            [op: "add", path: "nickName", value: nick],
            [op: "replace", path: "title", value: "After"],
            [op: "add", path: "externalId", value: "ext_${uid8()}"],
            [op: "remove", path: "externalId"]
        ])
        def user = null
        if (r.statusCode() in [200, 204]) user = getUser(id)

        then:
        assertPatchOk(r, "a PATCH with add, replace and remove operations")
        assert user.nickName == nick : "RFC 7644 3.5.2: 'add' must set nickName"
        assert user.title == "After" : "RFC 7644 3.5.2: 'replace' must update title"
        assert user.externalId == null : "RFC 7644 3.5.2: 'remove' must clear externalId"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1805-1806
    // The body of each request MUST contain the "schemas" attribute with the URI value of "urn:ietf:params:scim:api:messages:2.0:PatchOp".
    @SpecRef("RFC7644-3.5.2-92e9dc")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-92e9dc: The body of each request MUST contain the 'schemas' attribute with the URI value of..."() {
        given:
        String id = newUser(title: "Original")

        when:
        def missing = patchUserRaw(id, [Operations: [[op: "replace", path: "title", value: "Changed"]]])
        def wrong = patchUserRaw(id, [schemas: [USER_SCHEMA], Operations: [[op: "replace", path: "title", value: "Changed"]]])
        def after = getUser(id)

        then:
        assertScimError(missing, 400)
        assertScimError(wrong, 400)
        assert after.title == "Original" : "RFC 7644 3.5.2: a PATCH without the PatchOp schema must not be applied"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1808-1810
    // The body of an HTTP PATCH request MUST contain the attribute "Operations", whose value is an array of one or more PATCH operations.
    @SpecRef("RFC7644-3.5.2-2aa964")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-2aa964: The body of an HTTP PATCH request MUST contain the attribute 'Operations', whose value is an array of one..."() {
        given:
        String id = newUser(title: "Original")

        when:
        def missing = patchUserRaw(id, [schemas: [PATCH_OP_SCHEMA]])
        def empty = patchUserRaw(id, [schemas: [PATCH_OP_SCHEMA], Operations: []])
        def notArray = patchUserRaw(id, [schemas: [PATCH_OP_SCHEMA], Operations: [op: "replace", path: "title", value: "Changed"]])
        def after = getUser(id)

        then:
        assertScimError(missing, 400)
        assertScimError(empty, 400)
        assertScimError(notArray, 400)
        assert after.title == "Original" : "RFC 7644 3.5.2: a PATCH without a valid Operations array must not change the resource"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1810-1812
    // Each PATCH operation object MUST have exactly one "op" member, whose value indicates the operation to perform
    @SpecRef("RFC7644-3.5.2-437ab3")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-437ab3: Each PATCH operation object MUST have exactly one 'op' member, whose value indicates the operation to perform"() {
        given:
        String id = newUser(title: "Original")

        when:
        def noOp = patchUserRaw(id, [schemas: [PATCH_OP_SCHEMA], Operations: [[path: "title", value: "Changed"]]])
        def twoOps = patchUserRaw(id, [schemas: [PATCH_OP_SCHEMA], Operations: [[op: ["replace", "remove"], path: "title", value: "Changed"]]])
        def after = getUser(id)

        then:
        assertScimError(noOp, 400)
        assert twoOps.statusCode() == 400 : "RFC 7644 3.5.2: an operation whose op is not a single operation name must be rejected with 400 but was ${twoOps.statusCode()}"
        assert after.title == "Original" : "RFC 7644 3.5.2: an operation without exactly one 'op' must not be applied"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MAY, lines 1810-1812
    // MAY be one of "add", "remove", or "replace".
    @SpecRef("RFC7644-3.5.2-fe207c")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-fe207c: MAY be one of 'add', 'remove', or 'replace'."() {
        given:
        String id = newUser(title: "Original")

        when:
        def add = patchUser(id, [[op: "add", path: "nickName", value: "n_${uid8()}"]])
        def replace = patchUser(id, [[op: "replace", path: "title", value: "Replaced"]])
        def remove = patchUser(id, [[op: "remove", path: "nickName"]])
        def bogus = patchUser(id, [[op: "move", path: "title", value: "Moved"]])
        def after = getUser(id)

        then:
        assertPatchOk(add, "op 'add'")
        assertPatchOk(replace, "op 'replace'")
        assertPatchOk(remove, "op 'remove'")
        assertScimError(bogus, 400)
        assert after.title == "Replaced" : "RFC 7644 3.5.2: an op value outside add/remove/replace must not be applied"
        assert after.nickName == null
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MAY, lines 1856-1858
    // The "path" attribute is OPTIONAL for "add" and "replace"
    @SpecRef("RFC7644-3.5.2-ebfd50")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-ebfd50: The 'path' attribute is OPTIONAL for 'add' and 'replace'"() {
        given:
        String id = newUser()
        String nick = "nick_${uid8()}"
        String title = "title_${uid8()}"

        when:
        def add = patchUser(id, [[op: "add", value: [nickName: nick]]])
        def replace = patchUser(id, [[op: "replace", value: [title: title]]])
        def after = getUser(id)

        then:
        assertPatchOk(add, "an 'add' without path")
        assertPatchOk(replace, "a 'replace' without path")
        assert after.nickName == nick : "RFC 7644 3.5.2: 'add' without path must apply the value to the resource"
        assert after.title == title : "RFC 7644 3.5.2: 'replace' without path must apply the value to the resource"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1856-1858
    // and is REQUIRED for "remove" operations.
    @SpecRef("RFC7644-3.5.2-d42806")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-d42806: and is REQUIRED for 'remove' operations."() {
        given:
        String id = newUser(title: "Keep")

        when:
        def r = patchUser(id, [[op: "remove"]])
        def after = getUser(id)

        then:
        assertScimError(r, 400, "noTarget")
        assert after.title == "Keep"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1886-1888
    // Each operation against an attribute MUST be compatible with the attribute's mutability and schema as defined in Sections 2.2 and 2.3 of [RFC7643].
    @SpecRef("RFC7644-3.5.2-d91a0f")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-d91a0f: Each operation against an attribute MUST be compatible with the attribute's mutability and schema as..."() {
        given:
        String id = newUser()

        when:
        def r = patchUser(id, [[op: "replace", path: "active", value: "not-a-boolean"]])
        def r2 = patchUser(id, [[op: "replace", path: "id", value: "other-${uid8()}"]])
        def after = getUser(id)

        then:
        assert r.statusCode() == 400 : "RFC 7644 3.5.2: replacing the boolean 'active' with a string must be rejected with 400 but was ${r.statusCode()}"
        assert r2.statusCode() == 400 : "RFC 7644 3.5.2: replacing the readOnly 'id' must be rejected with 400 but was ${r2.statusCode()}"
        assert after.active == true : "RFC 7644 3.5.2: the schema-incompatible operation must not be applied"
        assert after.id == id
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST NOT, lines 1888-1889
    // For example, a client MUST NOT modify an attribute that has mutability "readOnly" or "immutable".
    @SpecRef("RFC7644-3.5.2-0c04e7")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-0c04e7: For example, a client MUST NOT modify an attribute that has mutability 'readOnly' or 'immutable'."() {
        given:
        String id = newUser()
        Map before = getUser(id)

        when:
        def r1 = patchUser(id, [[op: "replace", path: "id", value: "forged-${uid8()}"]])
        def r2 = patchUser(id, [[op: "replace", path: "meta.created", value: "2001-01-01T00:00:00Z"]])
        def r3 = patchUser(id, [[op: "replace", value: [id: "forged-${uid8()}"]]])
        def after = getUser(id)

        then:
        assert after.id == before.id : "RFC 7644 3.5.2: readOnly attribute 'id' must not be modified by PATCH (${r1.statusCode()}/${r3.statusCode()})"
        assert after.meta?.created == before.meta?.created : "RFC 7644 3.5.2: readOnly attribute meta.created must not be modified by PATCH (${r2.statusCode()})"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1891-1894
    // An operation that is not compatible with an attribute's mutability or schema SHALL return the appropriate HTTP response status code and a JSON detail error response as defined in Section 3.12.
    @SpecRef("RFC7644-3.5.2-5a70a0")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-5a70a0: An operation that is not compatible with an attribute's mutability or schema SHALL return the appropriate..."() {
        given:
        String id = newUser()

        when:
        def readOnly = patchUser(id, [[op: "replace", path: "id", value: "other-${uid8()}"]])
        def wrongType = patchUser(id, [[op: "replace", path: "active", value: "not-a-boolean"]])

        then:
        assertScimErrorOptionalType(readOnly, 400, "mutability")
        assertScimErrorOptionalType(wrongType, 400, "invalidValue")
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1897-1899
    // For all operations, the value of the "schemas" attribute on the SCIM service provider's representation of the resource SHALL be assumed by default.
    @SpecRef("RFC7644-3.5.2-583272")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-583272: For all operations, the value of the 'schemas' attribute on the SCIM service provider's representation of..."() {
        given:
        String id = newUser()
        String plain = "plain_${uid8()}"
        String qualified = "qualified_${uid8()}"

        when:
        def r1 = patchUser(id, [[op: "replace", path: "nickName", value: plain]])
        def mid = r1.statusCode() in [200, 204] ? getUser(id) : null
        def r2 = patchUser(id, [[op: "replace", path: "${USER_SCHEMA}:nickName".toString(), value: qualified]])
        def after = r2.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r1, "a PATCH with an unqualified attribute path")
        assert mid.nickName == plain : "RFC 7644 3.5.2: an unqualified path must address the core schema attribute"
        assertPatchOk(r2, "a PATCH with the core-schema-qualified attribute path")
        assert after.nickName == qualified : "RFC 7644 3.5.2: the core-schema-qualified path must address the same attribute as the unqualified path"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1899-1901
    // If one of the PATCH operations modifies the "schemas" attribute, subsequent operations SHALL assume the modified state of the "schemas" attribute.
    @SpecRef("RFC7644-3.5.2-de207f")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-de207f: If one of the PATCH operations modifies the 'schemas' attribute, subsequent operations SHALL assume the..."() {
        given:
        requireEnterprise()
        String id = newUser()
        String number = "N${uid8()}"
        String dept = "D${uid8()}"

        when:
        def r = patchUser(id, [
            [op: "add", path: "${ENTERPRISE_USER_SCHEMA}:employeeNumber".toString(), value: number],
            [op: "add", path: "${ENTERPRISE_USER_SCHEMA}:department".toString(), value: dept]
        ])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "two operations that address the extension schema added by the first operation")
        assert after.schemas.contains(ENTERPRISE_USER_SCHEMA) : "RFC 7644 3.5.2: the schemas attribute must reflect the modification made by the first operation"
        assert after[ENTERPRISE_USER_SCHEMA]?.employeeNumber == number
        assert after[ENTERPRISE_USER_SCHEMA]?.department == dept : "RFC 7644 3.5.2: the second operation must see the modified schemas state"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MAY, lines 1901-1912
    // Clients MAY implicitly modify the "schemas" attribute by adding (or replacing) an attribute with its fully qualified name, including schema URN.
    @SpecRef("RFC7644-3.5.2-f35aca")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-f35aca: Clients MAY implicitly modify the 'schemas' attribute by adding (or replacing) an attribute with its fully..."() {
        given:
        requireEnterprise()
        String id = newUser()
        Map before = getUser(id)
        String number = "N${uid8()}"

        when:
        def r = patchUser(id, [[op: "add", path: "${ENTERPRISE_USER_SCHEMA}:employeeNumber".toString(), value: number]])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assert !before.schemas.contains(ENTERPRISE_USER_SCHEMA) : "Setup: fresh user must not carry the extension schema"
        assertPatchOk(r, "adding an extension attribute by its fully qualified name")
        assert after.schemas.contains(ENTERPRISE_USER_SCHEMA) : "RFC 7644 3.5.2: adding an attribute by its fully qualified name must add the schema URN to 'schemas'"
        assert after[ENTERPRISE_USER_SCHEMA]?.employeeNumber == number
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1926-1929
    // For multi-valued attributes, a PATCH operation that sets a value's "primary" sub-attribute to "true" SHALL cause the server to automatically set "primary" to "false" for any other values in the array.
    @SpecRef("RFC7644-3.5.2-5c4fa1")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-5c4fa1: For multi-valued attributes, a PATCH operation that sets a value's 'primary' sub-attribute to 'true' SHALL..."() {
        given:
        String e1 = "p1_${uid8()}@test.com"
        String e2 = "p2_${uid8()}@test.com"
        String e3 = "p3_${uid8()}@test.com"
        String id = newUser(emails: [[value: e1, type: "work", primary: true], [value: e2, type: "home", primary: false]])

        when:
        def r1 = patchUser(id, [[op: "replace", path: 'emails[type eq "home"].primary', value: true]])
        def mid = r1.statusCode() in [200, 204] ? getUser(id) : null
        def r2 = patchUser(id, [[op: "add", path: "emails", value: [[value: e3, type: "other", primary: true]]]])
        def after = r2.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r1, "setting primary to true on the home email")
        assert mid.emails.findAll { it.primary == true }*.value == [e2] : "RFC 7644 3.5.2: after setting primary on one value, all others must be primary=false, got ${mid.emails}"
        assertPatchOk(r2, "adding an email with primary true")
        assert after.emails.findAll { it.primary == true }*.value == [e3] : "RFC 7644 3.5.2: after adding a primary value, all others must be primary=false, got ${after.emails}"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1931-1932
    // A PATCH request, regardless of the number of operations, SHALL be treated as atomic.
    @SpecRef("RFC7644-3.5.2-bd4c14")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-bd4c14: A PATCH request, regardless of the number of operations, SHALL be treated as atomic."() {
        given:
        String id = newUser(displayName: "Stable_${uid8()}")
        Map before = getUser(id)
        String otherId = newUser()
        String taken = getUser(otherId).userName

        when:
        def r = patchUser(id, [
            [op: "replace", path: "displayName", value: "ShouldRollBack"],
            [op: "replace", path: "title", value: "ShouldRollBackToo"],
            [op: "replace", path: "userName", value: taken]
        ])
        def after = getUser(id)

        then:
        assert r.statusCode() in [400, 409] : "RFC 7644 3.5.2: a PATCH whose last operation collides on userName must fail but was ${r.statusCode()}"
        assert after.displayName == before.displayName : "RFC 7644 3.5.2: the PATCH must be atomic, displayName of the first operation was persisted"
        assert after.title == before.title : "RFC 7644 3.5.2: the PATCH must be atomic, title of the second operation was persisted"
        assert after.userName == before.userName
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1932-1934
    // If a single operation encounters an error condition, the original SCIM resource MUST be restored, and a failure status SHALL be returned.
    @SpecRef("RFC7644-3.5.2-710b2a")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-710b2a: If a single operation encounters an error condition, the original SCIM resource MUST be restored, and a..."() {
        given:
        String id = newUser(displayName: "Original_${uid8()}")
        Map before = getUser(id)

        when:
        def r = patchUser(id, [
            [op: "replace", path: "displayName", value: "Changed_${uid8()}"],
            [op: "replace", path: 'emails[type eq "no-such-type"].value', value: "x@test.com"]
        ])
        def after = getUser(id)

        then:
        assert r.statusCode() in 400..499 : "RFC 7644 3.5.2: a failing operation must produce a failure status but was ${r.statusCode()}"
        assert after.displayName == before.displayName : "RFC 7644 3.5.2: the original resource must be restored after a failing operation"
        assert after.emails == before.emails
        assert after.meta?.lastModified == before.meta?.lastModified : "RFC 7644 3.5.2: a failed PATCH must leave the resource untouched"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1936-1937
    // If a request fails, the server SHALL return an HTTP response status code and a JSON detail error response as defined in Section 3.12.
    @SpecRef("RFC7644-3.5.2-8c00a5")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-8c00a5: If a request fails, the server SHALL return an HTTP response status code and a JSON detail error response..."() {
        given:
        String id = newUser()

        when:
        def badOp = patchUser(id, [[op: "replace", path: 'emails[type eq "no-such-type"].value', value: "x@test.com"]])
        def missing = scimRequestQuiet().body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "title", value: "x"]])))
            .patch("/Users/${UUID.randomUUID()}")

        then:
        assert badOp.statusCode() in 400..499
        assertScimError(badOp, badOp.statusCode())
        assertScimError(missing, 404)
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1939-1943
    // On successful completion, the server either MUST return a 200 OK response code and the entire resource within the response body, subject to the "attributes" query parameter (see Section 3.9),
    @SpecRef("RFC7644-3.5.2-ba1a04")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-ba1a04: On successful completion, the server either MUST return a 200 OK response code and the entire resource..."() {
        given:
        String id = newUser()
        String title = "title_${uid8()}"

        when:
        def r = patchUser(id, [[op: "replace", path: "title", value: title]])
        def after = getUser(id)

        then:
        assertPatchOk(r, "PATCH")
        if (r.statusCode() == 200) {
            def body = r.jsonPath()
            assert body.getString("id") == id : "RFC 7644 3.5.2: 200 response must contain the entire resource (id)"
            assert body.getString("userName") == after.userName : "RFC 7644 3.5.2: 200 response must contain the entire resource (userName)"
            assert body.getList("schemas")?.contains(USER_SCHEMA) : "RFC 7644 3.5.2: 200 response must contain the resource schemas"
            assert body.getString("meta.resourceType") == "User" : "RFC 7644 3.5.2: 200 response must contain meta"
            assert body.getString("title") == title : "RFC 7644 3.5.2: 200 response must reflect the change"
            assert body.getList("emails")?.size() == after.emails.size() : "RFC 7644 3.5.2: 200 response must contain the entire resource (emails)"
        } else {
            assert !r.body().asString()?.trim() : "RFC 7644 3.5.2: 204 response must have no body"
        }
        assert after.title == title
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MAY, lines 1939-1943
    // or MAY return HTTP status code 204 (No Content) and the appropriate response headers for a successful PATCH request.
    @SpecRef("RFC7644-3.5.2-8b4d7a")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-8b4d7a: or MAY return HTTP status code 204 (No Content) and the appropriate response headers for a successful..."() {
        given:
        String id = newUser()
        String title = "title_${uid8()}"

        when:
        def r = patchUser(id, [[op: "replace", path: "title", value: title]])

        then:
        assertPatchOk(r, "PATCH")
        if (r.statusCode() == 204) {
            assert !r.body().asString()?.trim() : "RFC 7644 3.5.2: a 204 response must not carry a body"
        } else {
            assert r.jsonPath().getString("id") == id : "RFC 7644 3.5.2: a 200 response must carry the updated resource"
            assert r.jsonPath().getString("title") == title
        }
        assert getUser(id).title == title : "RFC 7644 3.5.2: the change must be applied on a successful PATCH"
    }

    // RFC 7644 section 3.5.2 (Modifying with PATCH), MUST, lines 1943-1944
    // The server MUST return a 200 OK if the "attributes" parameter is specified in the request.
    @SpecRef("RFC7644-3.5.2-3a0860")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2-3a0860: The server MUST return a 200 OK if the 'attributes' parameter is specified in the request."() {
        given:
        String id = newUser()
        String title = "title_${uid8()}"

        when:
        def r = patchUser(id, [[op: "replace", path: "title", value: title]], [attributes: "userName,title"])

        then:
        assert r.statusCode() == 200 : "RFC 7644 3.5.2: PATCH with the attributes parameter must return 200 but was ${r.statusCode()}"
        assert r.jsonPath().getString("title") == title
        assert r.jsonPath().getString("userName") != null
        assert r.jsonPath().get("emails") == null : "RFC 7644 3.5.2/3.9: attributes=userName,title must exclude emails from the PATCH response"
    }

    // RFC 7644 section 3.5.2.1 (Add Operation), MUST, lines 1972-1973
    // The operation MUST contain a "value" member whose content specifies the value to be added.
    @SpecRef("RFC7644-3.5.2.1-952117")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.1-952117: The operation MUST contain a 'value' member whose content specifies the value to be added."() {
        given:
        String id = newUser(title: "Original")

        when:
        def r = patchUser(id, [[op: "add", path: "title"]])
        def after = getUser(id)

        then:
        assertScimError(r, 400)
        assert after.title == "Original"
    }

    // RFC 7644 section 3.5.2.1 (Add Operation), MAY, lines 1973-1975
    // The value MAY be a quoted value, or it may be a JSON object containing the sub-attributes of the complex attribute specified in the operation's "path".
    @SpecRef("RFC7644-3.5.2.1-1d8023")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.1-1d8023: The value MAY be a quoted value, or it may be a JSON object containing the sub-attributes of the complex..."() {
        given:
        String id = newUser()
        String nick = "nick_${uid8()}"
        String given = "G${uid8()}"

        when:
        def simple = patchUser(id, [[op: "add", path: "nickName", value: nick]])
        def complex = patchUser(id, [[op: "add", path: "name", value: [givenName: given, familyName: "Fam"]]])
        def after = getUser(id)

        then:
        assertPatchOk(simple, "adding a quoted (string) value")
        assertPatchOk(complex, "adding a JSON object to the complex attribute 'name'")
        assert after.nickName == nick
        assert after.name?.givenName == given : "RFC 7644 3.5.2.1: the sub-attributes of the object value must be added to the complex attribute"
        assert after.name?.familyName == "Fam"
    }

    // RFC 7644 section 3.5.2.1 (Add Operation), MUST, lines 1987-1988
    // If the target location specifies a complex attribute, a set of sub-attributes SHALL be specified in the "value" parameter.
    @SpecRef("RFC7644-3.5.2.1-892fce")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.1-892fce: If the target location specifies a complex attribute, a set of sub-attributes SHALL be specified in the..."() {
        given:
        String id = newUser(name: [givenName: "First", familyName: "Last"])

        when:
        def scalar = patchUser(id, [[op: "add", path: "name", value: "just a string"]])
        def after = getUser(id)
        def ok = patchUser(id, [[op: "add", path: "name", value: [middleName: "Mid"]]])
        def afterOk = ok.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertScimError(scalar, 400)
        assert after.name?.givenName == "First" : "RFC 7644 3.5.2.1: a scalar value for a complex attribute must not be applied"
        assertPatchOk(ok, "adding a set of sub-attributes to the complex attribute 'name'")
        assert afterOk.name?.middleName == "Mid"
    }

    // RFC 7644 section 3.5.2.1 (Add Operation), SHOULD, lines 2001-2003
    // If the target location already contains the value specified, no changes SHOULD be made to the resource, and a success response SHOULD be returned.
    @SpecRef("RFC7644-3.5.2.1-b61229")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.1-b61229: If the target location already contains the value specified, no changes SHOULD be made to the resource,..."() {
        given:
        String email = "dup_${uid8()}@test.com"
        String id = newUser(title: "Same", emails: [[value: email, type: "work", primary: true]])
        Map before = getUser(id)

        when:
        def sameSimple = patchUser(id, [[op: "add", path: "title", value: "Same"]])
        def sameMulti = patchUser(id, [[op: "add", path: "emails", value: [[value: email, type: "work", primary: true]]]])
        def after = getUser(id)

        then:
        assertPatchOk(sameSimple, "adding the value the attribute already contains")
        assertPatchOk(sameMulti, "adding the multi-valued value the attribute already contains")
        assert after.title == "Same"
        assert after.emails.size() == before.emails.size() : "RFC 7644 3.5.2.1: adding an existing value must not change the resource, got ${after.emails}"
        assert after.emails.count { it.value == email } == 1
    }

    // RFC 7644 section 3.5.2.1 (Add Operation), MUST NOT, lines 2003-2005
    // Unless other operations change the resource, this operation SHALL NOT change the modify timestamp of the resource.
    @SpecRef("RFC7644-3.5.2.1-070c8d")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.1-070c8d: Unless other operations change the resource, this operation SHALL NOT change the modify timestamp of the..."() {
        given:
        String id = newUser(title: "Same")
        String before = getUser(id).meta.lastModified
        Thread.sleep(1500)

        when:
        def r = patchUser(id, [[op: "add", path: "title", value: "Same"]])
        def after = getUser(id).meta.lastModified

        then:
        assertPatchOk(r, "a no-op add")
        assert after == before : "RFC 7644 3.5.2.1: a no-op add must not change meta.lastModified (was ${before}, now ${after})"
    }

    // RFC 7644 section 3.5.2.2 (Remove Operation), MUST, lines 2146-2148
    // If the target location is a single-value attribute, the attribute and its associated value is removed, and the attribute SHALL be considered unassigned.
    @SpecRef("RFC7644-3.5.2.2-abf3c3")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.2-abf3c3: If the target location is a single-value attribute, the attribute and its associated value is removed, and..."() {
        given:
        String id = newUser(title: "ToRemove", nickName: "n_${uid8()}")

        when:
        def r = patchUser(id, [[op: "remove", path: "title"], [op: "remove", path: "nickName"]])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "removing single-valued attributes")
        assert unassigned(after.title) : "RFC 7644 3.5.2.2: removed single-valued attribute title must be unassigned"
        assert unassigned(after.nickName) : "RFC 7644 3.5.2.2: removed single-valued attribute nickName must be unassigned"
    }

    // RFC 7644 section 3.5.2.2 (Remove Operation), MUST, lines 2150-2152
    // If the target location is a multi-valued attribute and no filter is specified, the attribute and all values are removed, and the attribute SHALL be considered unassigned.
    @SpecRef("RFC7644-3.5.2.2-3e0053")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.2-3e0053: If the target location is a multi-valued attribute and no filter is specified, the attribute and all..."() {
        given:
        String id = newUser(phoneNumbers: [[value: "+1555${uid8().replaceAll('[^0-9]', '1')}", type: "work"], [value: "+1556${uid8().replaceAll('[^0-9]', '2')}", type: "home"]])
        assert getUser(id).phoneNumbers?.size() == 2 : "Setup: user must have two phone numbers"

        when:
        def r = patchUser(id, [[op: "remove", path: "phoneNumbers"]])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "removing a multi-valued attribute without filter")
        assert unassigned(after.phoneNumbers) : "RFC 7644 3.5.2.2: all values must be removed and the attribute unassigned, got ${after.phoneNumbers}"
    }

    // RFC 7644 section 3.5.2.2 (Remove Operation), MUST, lines 2156-2158
    // If no other values remain after removal of the selected values, the multi-valued attribute SHALL be considered unassigned.
    @SpecRef("RFC7644-3.5.2.2-bc4586")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.2-bc4586: If no other values remain after removal of the selected values, the multi-valued attribute SHALL be..."() {
        given:
        String only = "only_${uid8()}@test.com"
        String id = newUser(emails: [[value: only, type: "work", primary: true]])

        when:
        def r = patchUser(id, [[op: "remove", path: "emails[value eq \"${only}\"]".toString()]])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "removing the last value with a value filter")
        assert unassigned(after.emails) : "RFC 7644 3.5.2.2: with no values remaining the attribute must be unassigned, got ${after.emails}"
    }

    // RFC 7644 section 3.5.2.2 (Remove Operation), MUST, lines 2162-2163
    // Sub-attributes whose values have been removed SHALL be considered unassigned.
    @SpecRef("RFC7644-3.5.2.2-9a5a54")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.2-9a5a54: Sub-attributes whose values have been removed SHALL be considered unassigned."() {
        given:
        String id = newUser(name: [givenName: "GivenGone", familyName: "FamilyStays"])

        when:
        def r1 = patchUser(id, [[op: "remove", path: "name.givenName"]])
        def mid = r1.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r1, "removing the sub-attribute name.givenName")
        assert unassigned(mid.name?.givenName) : "RFC 7644 3.5.2.2: removed sub-attribute name.givenName must be unassigned"
        assert mid.name?.familyName == "FamilyStays"
    }

    // RFC 7644 section 3.5.2.2 (Remove Operation), MUST, lines 2163-2165
    // If the complex multi-valued attribute has no remaining records, the attribute SHALL be considered unassigned.
    @SpecRef("RFC7644-3.5.2.2-4e484f")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.2-4e484f: If the complex multi-valued attribute has no remaining records, the attribute SHALL be considered unassigned."() {
        given:
        String id = newUser(emails: [[value: "w1_${uid8()}@test.com", type: "work", primary: true], [value: "w2_${uid8()}@test.com", type: "work"]])

        when:
        def r = patchUser(id, [[op: "remove", path: 'emails[type eq "work"]']])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "removing all matching records")
        assert unassigned(after.emails) : "RFC 7644 3.5.2.2: a multi-valued attribute with no remaining records must be unassigned, got ${after.emails}"
    }

    // RFC 7644 section 3.5.2.2 (Remove Operation), MUST, lines 2167-2171
    // If an attribute is removed or becomes unassigned and is defined as a required attribute or a read-only attribute, the server SHALL return an HTTP response status code and a JSON detail error response as defined in Section 3.12, with a "scimType" error code of "mutability".
    @SpecRef("RFC7644-3.5.2.2-817cea")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.2-817cea: If an attribute is removed or becomes unassigned and is defined as a required attribute or a read-only..."() {
        given:
        String id = newUser()
        Map before = getUser(id)

        when:
        def required = patchUser(id, [[op: "remove", path: "userName"]])
        def readOnly = patchUser(id, [[op: "remove", path: "id"]])
        def after = getUser(id)

        then:
        assertScimError(required, 400, "mutability")
        assertScimError(readOnly, 400, "mutability")
        assert after.userName == before.userName : "RFC 7644 3.5.2.2: the required attribute userName must not be removed"
        assert after.id == id
    }

    // RFC 7644 section 3.5.2.3 (Replace Operation), MUST, lines 2367-2368
    // In this case, the "value" attribute SHALL contain a list of one or more attributes that are to be replaced.
    @SpecRef("RFC7644-3.5.2.3-5ae8fc")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.3-5ae8fc: In this case, the 'value' attribute SHALL contain a list of one or more attributes that are to be replaced."() {
        given:
        String id = newUser(title: "Old")
        String nick = "nick_${uid8()}"

        when:
        def r = patchUser(id, [[op: "replace", value: [title: "New", nickName: nick]]])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null
        def scalar = patchUser(id, [[op: "replace", value: "not an attribute list"]])
        def afterScalar = getUser(id)

        then:
        assertPatchOk(r, "a pathless replace with a list of attributes")
        assert after.title == "New" : "RFC 7644 3.5.2.3: every attribute of the value must be replaced (title)"
        assert after.nickName == nick : "RFC 7644 3.5.2.3: every attribute of the value must be replaced (nickName)"
        assertScimError(scalar, 400)
        assert afterScalar.title == "New"
    }

    // RFC 7644 section 3.5.2.3 (Replace Operation), MUST, lines 2376-2377
    // If the target location path specifies an attribute that does not exist, the service provider SHALL treat the operation as an "add".
    @SpecRef("RFC7644-3.5.2.3-8226cc")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.3-8226cc: If the target location path specifies an attribute that does not exist, the service provider SHALL treat..."() {
        given:
        String id = newUser()
        String nick = "nick_${uid8()}"
        String phone = "+1557${uid8().replaceAll('[^0-9]', '3')}"
        assert getUser(id).nickName == null

        when:
        def r = patchUser(id, [
            [op: "replace", path: "nickName", value: nick],
            [op: "replace", path: "phoneNumbers", value: [[value: phone, type: "work"]]]
        ])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "replace on attributes that do not exist")
        assert after.nickName == nick : "RFC 7644 3.5.2.3: replace on a missing attribute must act as add"
        assert after.phoneNumbers?.find { it.value == phone } != null : "RFC 7644 3.5.2.3: replace on a missing multi-valued attribute must act as add, got ${after.phoneNumbers}"
    }

    // RFC 7644 section 3.5.2.3 (Replace Operation), MUST, lines 2379-2382
    // If the target location specifies a complex attribute, a set of sub-attributes SHALL be specified in the "value" parameter, which replaces any existing values or adds where an attribute did not previously exist.
    @SpecRef("RFC7644-3.5.2.3-6a49e4")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.3-6a49e4: If the target location specifies a complex attribute, a set of sub-attributes SHALL be specified in the..."() {
        given:
        String id = newUser(name: [givenName: "OldGiven", familyName: "Fam"])

        when:
        def r = patchUser(id, [[op: "replace", path: "name", value: [givenName: "NewGiven", middleName: "AddedMiddle"]]])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "replacing a complex attribute with a set of sub-attributes")
        assert after.name?.givenName == "NewGiven" : "RFC 7644 3.5.2.3: existing sub-attribute must be replaced"
        assert after.name?.middleName == "AddedMiddle" : "RFC 7644 3.5.2.3: missing sub-attribute must be added"
    }

    // RFC 7644 section 3.5.2.3 (Replace Operation), MUST, lines 2385-2388
    // If the target location is a multi-valued attribute and a value selection ("valuePath") filter is specified that matches one or more values of the multi-valued attribute, then all matching record values SHALL be replaced.
    @SpecRef("RFC7644-3.5.2.3-994959")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.3-994959: If the target location is a multi-valued attribute and a value selection ('valuePath') filter is specified..."() {
        given:
        String w1 = "w1_${uid8()}@test.com"
        String w2 = "w2_${uid8()}@test.com"
        String h = "h_${uid8()}@test.com"
        String neu = "new_${uid8()}@test.com"
        String id = newUser(emails: [[value: w1, type: "work", primary: true], [value: w2, type: "work"], [value: h, type: "home"]])

        when:
        def r = patchUser(id, [[op: "replace", path: 'emails[type eq "work"]', value: [value: neu, type: "work"]]])
        def after = r.statusCode() in [200, 204] ? getUser(id) : null

        then:
        assertPatchOk(r, "replacing the records selected by a valuePath filter")
        assert after.emails.find { it.value == w1 } == null : "RFC 7644 3.5.2.3: every matching record must be replaced (${w1} remains)"
        assert after.emails.find { it.value == w2 } == null : "RFC 7644 3.5.2.3: every matching record must be replaced (${w2} remains)"
        assert after.emails.find { it.value == neu } != null : "RFC 7644 3.5.2.3: the replacement value must be present, got ${after.emails}"
        assert after.emails.find { it.value == h } != null : "RFC 7644 3.5.2.3: non-matching records must be kept"
    }

    // RFC 7644 section 3.5.2.3 (Replace Operation), MUST, lines 2395-2399
    // If the target location is a multi-valued attribute for which a value selection filter ("valuePath") has been supplied and no record match was made, the service provider SHALL indicate failure by returning HTTP status code 400 and a "scimType" error code of "noTarget".
    @SpecRef("RFC7644-3.5.2.3-47ad6c")
    @RequiresCapability("patch")
    def "RFC7644-3.5.2.3-47ad6c: If the target location is a multi-valued attribute for which a value selection filter ('valuePath') has..."() {
        given:
        String id = newUser()
        Map before = getUser(id)

        when:
        def r = patchUser(id, [[op: "replace", path: 'emails[type eq "no-such-type"].value', value: "x_${uid8()}@test.com"]])
        def after = getUser(id)

        then:
        assertScimError(r, 400, "noTarget")
        assert after.emails == before.emails
    }
}
