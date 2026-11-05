package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7643: SCIM Core Schema
 *
 * One feature per requirement of requirements-rfc7643.yaml (area "Schema definition"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7643SchemaDefinitionSpec extends ScimBaseSpec {

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1678-1680
    // Unlike other core resources, the "Schema" resource MAY contain a complex object within a sub-attribute
    @SpecRef("RFC7643-7-ccf43b")
    def "RFC7643-7-ccf43b: Unlike other core resources, the 'Schema' resource MAY contain a complex object within a sub-attribute"() {
        expect:
        List<Map> nested = flattenAttributes(fetchSchemas()).findAll { it.depth >= 2 && it.attr.type == "complex" }
        if (nested.isEmpty()) {
            throw new TestAbortedException("server defines no complex attribute inside a sub-attribute; RFC 7643 7 makes this optional")
        }
        nested.each { Map n ->
            assert n.attr.subAttributes instanceof List && !n.attr.subAttributes.isEmpty() :
                "RFC 7643 7: complex object ${n.path} inside a sub-attribute must define its own subAttributes"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1678-1680
    // and all attributes are REQUIRED unless otherwise specified.
    @SpecRef("RFC7643-7-c3c537")
    def "RFC7643-7-c3c537: and all attributes are REQUIRED unless otherwise specified."() {
        expect:
        List<Map> schemas = fetchSchemas()
        schemas.each { Map s ->
            assert s.id instanceof String && s.id : "RFC 7643 7: schema is missing REQUIRED 'id': ${s}"
            assert s.attributes instanceof List && !s.attributes.isEmpty() :
                "RFC 7643 7: schema ${s.id} is missing REQUIRED 'attributes'"
        }
        flattenAttributes(schemas).each { Map f ->
            Map a = f.attr
            assert a.name instanceof String && a.name : "RFC 7643 7: ${f.path} is missing REQUIRED 'name'"
            assert a.type instanceof String && a.type : "RFC 7643 7: ${f.path} is missing REQUIRED 'type'"
            assert a.multiValued instanceof Boolean : "RFC 7643 7: ${f.path} is missing REQUIRED boolean 'multiValued'"
            assert a.required instanceof Boolean : "RFC 7643 7: ${f.path} is missing REQUIRED boolean 'required'"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1692-1694
    // When applicable, service providers MUST specify the URI, e.g., "urn:ietf:params:scim:schemas:core:2.0:User".
    @SpecRef("RFC7643-7-037042")
    def "RFC7643-7-037042: id: When applicable, service providers MUST specify the URI, e.g.,..."() {
        expect:
        List<Map> schemas = fetchSchemas()
        schemas.each { Map s ->
            assert s.id instanceof String && s.id : "RFC 7643 7: schema without id: ${s}"
            boolean absolute = false
            try { absolute = new URI(s.id as String).isAbsolute() } catch (URISyntaxException ignored) { }
            assert absolute : "RFC 7643 7: schema id '${s.id}' must be a URI"
        }
        assert schemaById(schemas, USER_SCHEMA) != null : "RFC 7643 7: /Schemas must specify ${USER_SCHEMA}"
        assert schemaById(schemas, GROUP_SCHEMA) != null : "RFC 7643 7: /Schemas must specify ${GROUP_SCHEMA}"
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1698-1698
    // REQUIRED.
    @SpecRef("RFC7643-7-46a02a")
    def "RFC7643-7-46a02a: id: REQUIRED."() {
        expect:
        List<Map> schemas = fetchSchemas()
        List<String> ids = schemas.collect { it.id as String }
        schemas.each { Map s ->
            assert s.id instanceof String && !(s.id as String).isBlank() : "RFC 7643 7: REQUIRED schema 'id' is missing: ${s}"
        }
        assert ids.toSet().size() == ids.size() : "RFC 7643 7: schema ids must be unique but were ${ids}"
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1701-1702
    // When applicable, service providers MUST specify the name, e.g., "User" or "Group".
    @SpecRef("RFC7643-7-899488")
    def "RFC7643-7-899488: name: When applicable, service providers MUST specify the name, e.g., 'User' or 'Group'."() {
        expect:
        List<Map> schemas = fetchSchemas()
        assert schemaById(schemas, USER_SCHEMA)?.name == "User" : "RFC 7643 7: name of the User schema must be 'User' but was '${schemaById(schemas, USER_SCHEMA)?.name}'"
        assert schemaById(schemas, GROUP_SCHEMA)?.name == "Group" : "RFC 7643 7: name of the Group schema must be 'Group' but was '${schemaById(schemas, GROUP_SCHEMA)?.name}'"
        schemas.each { Map s ->
            assert s.name instanceof String && !(s.name as String).isBlank() : "RFC 7643 7: schema ${s.id} must specify a name"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1703-1703
    // OPTIONAL.
    @SpecRef("RFC7643-7-c15f13")
    def "RFC7643-7-c15f13: name: OPTIONAL."() {
        expect:
        // name is OPTIONAL: it may be absent, but when present it must be a usable string
        fetchSchemas().each { Map s ->
            if (s.containsKey("name")) {
                assert s.name instanceof String && !(s.name as String).isBlank() :
                    "RFC 7643 7: optional schema 'name' of ${s.id} must be a non-empty string when present but was ${s.name}"
            }
        }
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1706-1707
    // When applicable, service providers MUST specify the description.
    @SpecRef("RFC7643-7-89825f")
    def "RFC7643-7-89825f: description: When applicable, service providers MUST specify the description."() {
        expect:
        List<Map> schemas = fetchSchemas()
        schemas.each { Map s ->
            assert s.description instanceof String && !(s.description as String).isBlank() :
                "RFC 7643 7: schema ${s.id} must specify a description"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1707-1707
    // OPTIONAL.
    @SpecRef("RFC7643-7-b27017")
    def "RFC7643-7-b27017: description: OPTIONAL."() {
        expect:
        // description is OPTIONAL: it may be absent, but when present it must be a string
        fetchSchemas().each { Map s ->
            if (s.containsKey("description")) {
                assert s.description instanceof String :
                    "RFC 7643 7: optional schema 'description' of ${s.id} must be a string when present but was ${s.description}"
            }
        }
    }

    // RFC 7643 section 7 (Schema Definition), SHOULD, lines 1719-1722
    // When an attribute is of type "complex", there SHOULD be a corresponding schema attribute "subAttributes" defined, listing the sub-attributes of the attribute.
    @SpecRef("RFC7643-7-dc6f53")
    def "RFC7643-7-dc6f53: attributes.type: When an attribute is of type 'complex', there SHOULD be a corresponding schema attribute..."() {
        expect:
        flattenAttributes(fetchSchemas()).findAll { it.attr.type == "complex" }.each { Map f ->
            assert f.attr.subAttributes instanceof List && !f.attr.subAttributes.isEmpty() :
                "RFC 7643 7: complex attribute ${f.path} SHOULD define 'subAttributes'"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1731-1732
    // When applicable, service providers MUST specify the description.
    @SpecRef("RFC7643-7-52f7ea")
    def "RFC7643-7-52f7ea: attributes.description: When applicable, service providers MUST specify the description."() {
        expect:
        List<Map> core = fetchSchemas().findAll { (it.id as String).startsWith("urn:ietf:params:scim:schemas:core:2.0:") }
        assert !core.isEmpty() : "RFC 7643 7: no core schema returned"
        flattenAttributes(core).each { Map f ->
            assert f.attr.description instanceof String && !(f.attr.description as String).isBlank() :
                "RFC 7643 7: attribute ${f.path} must specify a description"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1746-1747
    // A collection of suggested canonical values that MAY be used (e.g., "work" and "home").
    @SpecRef("RFC7643-7-6760a6")
    def "RFC7643-7-6760a6: attributes.canonicalValues: A collection of suggested canonical values that MAY be used (e.g., 'work' and..."() {
        expect:
        List<Map> withValues = flattenAttributes(fetchSchemas()).findAll { it.attr.canonicalValues != null }
        if (withValues.isEmpty()) {
            throw new TestAbortedException("server offers no canonicalValues; they are optional in RFC 7643 7")
        }
        withValues.each { Map f ->
            List cv = f.attr.canonicalValues instanceof List ? f.attr.canonicalValues : null
            assert cv != null && !cv.isEmpty() : "RFC 7643 7: canonicalValues of ${f.path} must be a non-empty collection"
            cv.each { assert it instanceof String && !(it as String).isBlank() : "RFC 7643 7: canonical value of ${f.path} must be a non-empty string but was ${it}" }
            assert cv.toSet().size() == cv.size() : "RFC 7643 7: canonicalValues of ${f.path} contain duplicates: ${cv}"
        }
        // a suggested canonical value may be used
        Map emailType = withValues.find { it.path == "${USER_SCHEMA}:emails:type" }
        if (emailType != null) {
            String canonical = emailType.attr.canonicalValues[0]
            Response created = createUser([emails: [[value: "canon_${UUID.randomUUID().toString().substring(0, 8)}@test.com", type: canonical, primary: true]]])
            assert created.statusCode() == 201 : "RFC 7643 7: a canonical value ('${canonical}') may be used, POST must return 201 but was ${created.statusCode()}"
            assert created.jsonPath().getString("emails[0].type") == canonical :
                "RFC 7643 7: canonical value '${canonical}' must be accepted but server returned '${created.jsonPath().getString('emails[0].type')}'"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1747-1748
    // In some cases, service providers MAY choose to ignore unsupported values.
    @SpecRef("RFC7643-7-de01a0")
    def "RFC7643-7-de01a0: attributes.canonicalValues: In some cases, service providers MAY choose to ignore unsupported values."() {
        expect:
        Map emailType = flattenAttributes(fetchSchemas()).find { it.path == "${USER_SCHEMA}:emails:type" && it.attr.canonicalValues }
        if (emailType == null) {
            throw new TestAbortedException("server defines no canonicalValues for emails.type")
        }
        List canonical = emailType.attr.canonicalValues
        String custom = "x-custom-${UUID.randomUUID().toString().substring(0, 8)}"
        assert !canonical.contains(custom)
        Response created = createUser([emails: [[value: "canon_${UUID.randomUUID().toString().substring(0, 8)}@test.com", type: custom, primary: true]]])
        assert created.statusCode() == 201 :
            "RFC 7643 7: canonical values are only suggestions, a non-canonical type must not fail the request (201 expected) but was ${created.statusCode()}"
        String returnedType = created.jsonPath().getString("emails[0].type")
        // the server may store the value or ignore it, but it must not invent another value
        assert returnedType == null || returnedType == custom || canonical.contains(returnedType) :
            "RFC 7643 7: unsupported value '${custom}' was neither kept nor ignored; server returned '${returnedType}'"
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1748-1748
    // OPTIONAL.
    @SpecRef("RFC7643-7-0ca6b6")
    def "RFC7643-7-0ca6b6: attributes.canonicalValues: OPTIONAL."() {
        expect:
        // canonicalValues is OPTIONAL: it may be absent, but when present it is an array of strings
        flattenAttributes(fetchSchemas()).each { Map f ->
            if (f.attr.containsKey("canonicalValues")) {
                assert f.attr.canonicalValues instanceof List :
                    "RFC 7643 7: canonicalValues of ${f.path} must be a collection when present but was ${f.attr.canonicalValues}"
                assert f.attr.type == "string" || f.attr.type == "reference" :
                    "RFC 7643 7: canonicalValues of ${f.path} (type ${f.attr.type}) only makes sense for string values"
            }
        }
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1751-1752
    // The server SHALL use case sensitivity when evaluating filters.
    @SpecRef("RFC7643-7-4bbb58")
    def "RFC7643-7-4bbb58: attributes.caseExact: The server SHALL use case sensitivity when evaluating filters."() {
        expect:
        def done = assertFilterCaseSensitivity(["eq"])
        assert done
    }

    // RFC 7643 section 7 (Schema Definition), MUST, lines 1752-1754
    // For attributes that are case exact, the server SHALL preserve case for any value submitted.
    @SpecRef("RFC7643-7-327b33")
    def "RFC7643-7-327b33: attributes.caseExact: For attributes that are case exact, the server SHALL preserve case for any value..."() {
        expect:
        Map ext = userAttr("externalId")
        if (ext?.caseExact != true) {
            throw new TestAbortedException("User.externalId is not declared caseExact=true")
        }
        String value = "MiXeD-${UUID.randomUUID().toString().substring(0, 12)}"
        Response created = createUser([externalId: value])
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${created.statusCode()}"
        assert created.jsonPath().getString("externalId") == value :
            "RFC 7643 7: caseExact attribute value must be preserved, sent '${value}' but POST returned '${created.jsonPath().getString('externalId')}'"
        Response got = scimRequest().get("/Users/${created.jsonPath().getString('id')}")
        assert got.statusCode() == 200
        assert got.jsonPath().getString("externalId") == value :
            "RFC 7643 7: caseExact attribute value must be preserved, sent '${value}' but GET returned '${got.jsonPath().getString('externalId')}'"
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1754-1755
    // If the attribute is case insensitive, the server MAY alter case for a submitted value.
    @SpecRef("RFC7643-7-9ef9b7")
    def "RFC7643-7-9ef9b7: attributes.caseExact: If the attribute is case insensitive, the server MAY alter case for a submitted value."() {
        expect:
        Map un = userAttr("userName")
        if (un?.caseExact != false) {
            throw new TestAbortedException("User.userName is not declared caseExact=false")
        }
        String value = "MixedCase-${UUID.randomUUID().toString().substring(0, 12)}@Test.com"
        Response created = createUser([userName: value])
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        Response got = scimRequest().get("/Users/${id}")
        assert got.statusCode() == 200
        // the server may alter the case but nothing else
        assert (got.jsonPath().getString("userName") as String).equalsIgnoreCase(value) :
            "RFC 7643 7: server may alter only the case of a case-insensitive value, sent '${value}' got '${got.jsonPath().getString('userName')}'"
        assert (created.jsonPath().getString("userName") as String).equalsIgnoreCase(value) :
            "RFC 7643 7: server may alter only the case of a case-insensitive value, sent '${value}' got '${created.jsonPath().getString('userName')}'"
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1755-1757
    // Case sensitivity also impacts how attribute values MAY be compared against filter values (see Section 3.4.2.2 of [RFC7644]).
    @SpecRef("RFC7643-7-427d6a")
    def "RFC7643-7-427d6a: attributes.caseExact: Case sensitivity also impacts how attribute values MAY be compared against filter..."() {
        expect:
        def done = assertFilterCaseSensitivity(["sw", "co"])
        assert done
    }

    // RFC 7643 section 7 (Schema Definition), MUST NOT, lines 1762-1762
    // The attribute SHALL NOT be modified.
    @SpecRef("RFC7643-7-2a9f5d")
    def "RFC7643-7-2a9f5d: attributes.mutability: The attribute SHALL NOT be modified."() {
        expect:
        Map idAttr = userAttr("id")
        Map groupsAttr = userAttr("groups")
        if (idAttr?.mutability != "readOnly" || groupsAttr?.mutability != "readOnly") {
            throw new TestAbortedException("User.id/User.groups are not declared readOnly")
        }
        Response created = createUser()
        assert created.statusCode() == 201
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        Response put = scimRequest().body(JsonOutput.toJson([
            schemas : [USER_SCHEMA],
            id      : "other-${UUID.randomUUID()}",
            userName: userName,
            groups  : [[value: "fake-${UUID.randomUUID()}", display: "Fake"]]
        ])).put("/Users/${id}")
        // readOnly attributes are ignored (200) or rejected (400), but never modified
        assert put.statusCode() in [200, 400] :
            "RFC 7643 7: readOnly attributes in a PUT must be ignored or rejected with 400 but status was ${put.statusCode()}"
        if (put.statusCode() == 200) {
            assert put.jsonPath().getString("id") == id : "RFC 7643 7: readOnly 'id' was modified by PUT"
        }
        Response got = scimRequest().get("/Users/${id}")
        assert got.statusCode() == 200 : "RFC 7644 3.4.1: GET must return 200 but was ${got.statusCode()}"
        assert got.jsonPath().getString("id") == id : "RFC 7643 7: readOnly 'id' was modified"
        List groups = got.jsonPath().getList("groups")
        assert groups == null || groups.isEmpty() : "RFC 7643 7: readOnly 'groups' was modified by the client: ${groups}"
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1764-1764
    // The attribute MAY be updated and read at any time.
    @SpecRef("RFC7643-7-d6a434")
    def "RFC7643-7-d6a434: attributes.mutability: The attribute MAY be updated and read at any time."() {
        expect:
        Map dn = userAttr("displayName")
        if (dn?.mutability != "readWrite") {
            throw new TestAbortedException("User.displayName is not declared readWrite")
        }
        Response created = createUser([displayName: "First ${UUID.randomUUID().toString().substring(0, 8)}"])
        assert created.statusCode() == 201
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        ["Second", "Third"].each { String prefix ->
            String value = "${prefix} ${UUID.randomUUID().toString().substring(0, 8)}"
            Response put = scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, displayName: value])).put("/Users/${id}")
            assert put.statusCode() == 200 : "RFC 7643 7: readWrite attribute must be updatable but PUT returned ${put.statusCode()}"
            Response got = scimRequest().get("/Users/${id}")
            assert got.statusCode() == 200
            assert got.jsonPath().getString("displayName") == value :
                "RFC 7643 7: readWrite attribute must be readable after update, expected '${value}' got '${got.jsonPath().getString('displayName')}'"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1767-1769
    // The attribute MAY be defined at resource creation (e.g., POST) or at record replacement via a request (e.g., a PUT).
    @SpecRef("RFC7643-7-7a4c83")
    def "RFC7643-7-7a4c83: attributes.mutability: The attribute MAY be defined at resource creation (e.g., POST) or at record..."() {
        expect:
        Map memberValue = immutableGroupMemberValue()
        if (memberValue == null) {
            throw new TestAbortedException("Group.members.value is not declared immutable")
        }
        String u1 = createUser().jsonPath().getString("id")
        String u2 = createUser().jsonPath().getString("id")
        assert u1 && u2
        // defined at creation
        Response created = createGroup("ImmCreate-${UUID.randomUUID().toString().substring(0, 8)}", [u1])
        assert created.statusCode() == 201 : "RFC 7643 7: immutable attribute may be defined at creation, POST returned ${created.statusCode()}"
        assert created.jsonPath().getList("members.value").contains(u1) :
            "RFC 7643 7: immutable members.value defined at creation must be stored, got ${created.jsonPath().getList('members.value')}"
        // defined at replacement (value was not defined before)
        String name = "ImmPut-${UUID.randomUUID().toString().substring(0, 8)}"
        Response empty = createGroup(name)
        assert empty.statusCode() == 201
        String gid = empty.jsonPath().getString("id")
        Response put = scimRequest().body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: name, members: [[value: u1]]])).put("/Groups/${gid}")
        assert put.statusCode() == 200 : "RFC 7643 7: immutable attribute may be defined by PUT, status was ${put.statusCode()}"
        assert put.jsonPath().getList("members.value").contains(u1) :
            "RFC 7643 7: immutable members.value defined by PUT must be stored, got ${put.jsonPath().getList('members.value')}"
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1771-1771
    // The attribute MAY be updated at any time.
    @SpecRef("RFC7643-7-789c1d")
    def "RFC7643-7-789c1d: attributes.mutability: The attribute MAY be updated at any time."() {
        expect:
        Map pw = userAttr("password")
        if (pw?.mutability != "writeOnly") {
            throw new TestAbortedException("User.password is not declared writeOnly")
        }
        Response created = createUser([password: "Pw1!${UUID.randomUUID()}"])
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST with writeOnly password must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        2.times {
            Response put = scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, password: "Pw2!${UUID.randomUUID()}"])).put("/Users/${id}")
            assert put.statusCode() == 200 : "RFC 7643 7: writeOnly attribute may be updated at any time, PUT returned ${put.statusCode()}"
        }
        if (isPatchSupported()) {
            Response patch = scimRequest().body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "password", value: "Pw3!${UUID.randomUUID()}"]]))).patch("/Users/${id}")
            assert patch.statusCode() in [200, 204] : "RFC 7643 7: writeOnly attribute may be updated at any time, PATCH returned ${patch.statusCode()}"
        }
    }

    // RFC 7643 section 7 (Schema Definition), MUST NOT, lines 1771-1773
    // Attribute values SHALL NOT be returned (e.g., because the value is a stored hash).
    @SpecRef("RFC7643-7-60f07b")
    def "RFC7643-7-60f07b: attributes.mutability: Attribute values SHALL NOT be returned (e.g., because the value is a stored hash)."() {
        expect:
        Map pw = userAttr("password")
        if (pw?.mutability != "writeOnly") {
            throw new TestAbortedException("User.password is not declared writeOnly")
        }
        String secret = "Wo1!${UUID.randomUUID()}"
        String secret2 = "Wo2!${UUID.randomUUID()}"
        Response created = createUser([password: secret])
        assert created.statusCode() == 201
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        assertNoPassword(created, secret, "POST")
        Response got = scimRequest().get("/Users/${id}")
        assert got.statusCode() == 200
        assertNoPassword(got, secret, "GET")
        Response put = scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, password: secret2])).put("/Users/${id}")
        assert put.statusCode() == 200
        assertNoPassword(put, secret2, "PUT")
        if (isPatchSupported()) {
            String secret3 = "Wo3!${UUID.randomUUID()}"
            Response patch = scimRequest().body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "password", value: secret3]]))).patch("/Users/${id}")
            assert patch.statusCode() in [200, 204]
            if (patch.statusCode() == 200) {
                assertNoPassword(patch, secret3, "PATCH")
            }
        }
        Response list = scimRequest().queryParam("filter", "userName eq \"${userName}\"").get("/Users")
        assert list.statusCode() == 200
        assertNoPassword(list, secret2, "list")
        Response explicit = scimRequest().queryParam("attributes", "password,userName").get("/Users/${id}")
        assert explicit.statusCode() == 200
        assertNoPassword(explicit, secret2, "GET with attributes=password")
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1787-1788
    // A service provider MAY allow attributes to be used in a search filter.
    @SpecRef("RFC7643-7-3a0cc1")
    def "RFC7643-7-3a0cc1: attributes.returned: A service provider MAY allow attributes to be used in a search filter."() {
        expect:
        Map pw = userAttr("password")
        if (pw?.returned != "never") {
            throw new TestAbortedException("User.password is not declared returned=never")
        }
        String secret = "Fl1!${UUID.randomUUID()}"
        Response created = createUser([password: secret])
        assert created.statusCode() == 201
        String userName = created.jsonPath().getString("userName")
        Response r = scimRequest().queryParam("filter", "userName eq \"${userName}\" and password eq \"${secret}\"").get("/Users")
        // the server may allow the attribute in a filter (200) or refuse it (400 invalidFilter)
        assert r.statusCode() in [200, 400] :
            "RFC 7643 7: filtering on a returned=never attribute must be answered with 200 or 400 but was ${r.statusCode()}"
        if (r.statusCode() == 400) {
            assertScimError(r, 400, "invalidFilter")
        } else {
            assertNoPassword(r, secret, "filtered list")
        }
    }

    // RFC 7643 section 7 (Schema Definition), MAY, lines 1815-1817
    // A server MAY reject an invalid value based on uniqueness by returning HTTP response code 400 (Bad Request).
    @SpecRef("RFC7643-7-4f9237")
    def "RFC7643-7-4f9237: attributes.uniqueness: A server MAY reject an invalid value based on uniqueness by returning HTTP response..."() {
        expect:
        Map un = userAttr("userName")
        if (un?.uniqueness != "server" && un?.uniqueness != "global") {
            throw new TestAbortedException("User.userName is not declared unique")
        }
        Response first = createUser()
        assert first.statusCode() == 201
        Response dup = createUser([userName: first.jsonPath().getString("userName")])
        assert dup.statusCode() in [400, 409] :
            "RFC 7643 7: duplicate unique attribute must be rejected with 400 or 409 but was ${dup.statusCode()}"
        assertScimError(dup, dup.statusCode())
    }

    // RFC 7643 section 7 (Schema Definition), SHOULD, lines 1826-1829
    // The value SHOULD be unique within the context of the current SCIM endpoint (or tenancy)
    @SpecRef("RFC7643-7-a1395e")
    def "RFC7643-7-a1395e: attributes.uniqueness: The value SHOULD be unique within the context of the current SCIM endpoint (or tenancy)"() {
        expect:
        List<Map> targets = uniqueAttributes("server")
        def done = assertDuplicateCreateRejected("server", targets)
        assert done
    }

    // RFC 7643 section 7 (Schema Definition), SHOULD, lines 1829-1830
    // No two resources on the same server SHOULD possess the same value.
    @SpecRef("RFC7643-7-139750")
    def "RFC7643-7-139750: attributes.uniqueness: No two resources on the same server SHOULD possess the same value."() {
        expect:
        List<Map> targets = uniqueAttributes("server")
        def done = assertDuplicateUpdateRejected("server", targets)
        assert done
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    protected List<Map> fetchSchemas() {
        Response r = scimRequest().get("/Schemas")
        assert r.statusCode() == 200 : "RFC 7644 4: GET /Schemas must return 200 but was ${r.statusCode()}"
        List<Map> resources = r.jsonPath().getList("Resources")
        assert resources != null && !resources.isEmpty() : "RFC 7644 4: GET /Schemas must list the supported schemas"
        return resources
    }

    protected static Map schemaById(List<Map> schemas, String id) {
        return schemas.find { it.id == id }
    }

    /** All attributes and sub-attributes: [schema, path, depth, attr]; depth 1 = top level attribute. */
    protected static List<Map> flattenAttributes(List<Map> schemas) {
        List<Map> out = []
        schemas.each { Map s -> walkAttributes(s.id as String, s.attributes as List<Map>, s.id as String, 1, out) }
        return out
    }

    private static void walkAttributes(String schemaId, List<Map> attrs, String prefix, int depth, List<Map> out) {
        attrs?.each { Map a ->
            String path = "${prefix}:${a.name}"
            out << [schema: schemaId, path: path, depth: depth, attr: a]
            if (a.subAttributes instanceof List) {
                walkAttributes(schemaId, a.subAttributes as List<Map>, path, depth + 1, out)
            }
        }
    }

    protected Map userAttr(String name) {
        Map user = schemaById(fetchSchemas(), USER_SCHEMA)
        return user?.attributes?.find { it.name == name } as Map
    }

    protected Map immutableGroupMemberValue() {
        Map group = schemaById(fetchSchemas(), GROUP_SCHEMA)
        Map members = group?.attributes?.find { it.name == "members" } as Map
        Map value = members?.subAttributes?.find { it.name == "value" } as Map
        return value?.mutability == "immutable" ? value : null
    }

    protected void assertNoPassword(Response response, String secret, String where) {
        String body = response.body().asString()
        Object parsed = response.jsonPath().get("")
        List<Map> docs = parsed instanceof Map && parsed.Resources instanceof List ? (parsed.Resources as List<Map>) : [parsed as Map]
        docs.each { Map doc ->
            assert !doc.containsKey("password") : "RFC 7643 7: writeOnly attribute 'password' returned in ${where} response"
        }
        assert !body.contains(secret) : "RFC 7643 7: writeOnly password value returned in ${where} response"
    }

    protected Response filterUsers(String filter) {
        Response r = scimRequest().queryParam("filter", filter).get("/Users")
        assert r.statusCode() == 200 : "RFC 7644 3.4.2.2: filter '${filter}' must return 200 but was ${r.statusCode()}"
        return r
    }

    /**
     * For a caseExact=true attribute (externalId) and a caseExact=false attribute (userName), the given
     * filter operators must be evaluated case sensitively resp. insensitively, as the schema declares.
     */
    protected boolean assertFilterCaseSensitivity(List<String> operators) {
        if (!isFilterSupported()) {
            throw new TestAbortedException("filter is not supported")
        }
        Map ext = userAttr("externalId")
        Map un = userAttr("userName")
        if (ext?.caseExact == null || un?.caseExact == null) {
            throw new TestAbortedException("schema does not declare caseExact for userName/externalId")
        }
        String sfx = UUID.randomUUID().toString().substring(0, 12)
        Response created = createUser([userName: "CaseUser-${sfx}@Test.com", externalId: "ExtCase-${sfx}"])
        assert created.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        Map attrs = [userName: [def: un, value: created.jsonPath().getString("userName"), part: "User-${sfx}"],
                     externalId: [def: ext, value: created.jsonPath().getString("externalId"), part: "Case-${sfx}"]]
        attrs.each { String name, Map info ->
            boolean caseExact = info.def.caseExact
            operators.each { String op ->
                String exact = op == "eq" ? info.value : (op == "sw" ? (info.value as String).substring(0, (info.value as String).indexOf(sfx) + sfx.length()) : info.part)
                String altered = (exact as String).toUpperCase()
                assert altered != exact
                List ids1 = filterUsers("${name} ${op} \"${exact}\"").jsonPath().getList("Resources.id")
                assert ids1.contains(id) : "RFC 7643 7: '${name} ${op} \"${exact}\"' must match the user (value with identical case)"
                List ids2 = filterUsers("${name} ${op} \"${altered}\"").jsonPath().getList("Resources.id")
                if (caseExact) {
                    assert !ids2?.contains(id) : "RFC 7643 7: ${name} is caseExact, '${name} ${op} \"${altered}\"' must not match the value '${info.value}'"
                } else {
                    assert ids2?.contains(id) : "RFC 7643 7/RFC 7644 3.4.2.2: ${name} is not caseExact, '${name} ${op} \"${altered}\"' must match the value '${info.value}'"
                }
            }
        }
        return true
    }

    /** Attributes of User/Group that the schema declares with the given uniqueness and that we can set in a request. */
    protected List<Map> uniqueAttributes(String level) {
        List<Map> schemas = fetchSchemas()
        List<Map> out = []
        schemaById(schemas, USER_SCHEMA)?.attributes?.each { Map a ->
            if (a.uniqueness == level && a.type == "string" && a.multiValued == false && a.mutability != "readOnly") {
                out << [resource: "User", name: a.name]
            }
        }
        schemaById(schemas, GROUP_SCHEMA)?.attributes?.each { Map a ->
            if (a.uniqueness == level && a.name == "displayName") {
                out << [resource: "Group", name: a.name]
            }
        }
        if (out.isEmpty()) {
            throw new TestAbortedException("server declares no User/Group attribute with uniqueness '${level}'")
        }
        return out
    }

    protected Response createWithValue(Map target, String value) {
        if (target.resource == "Group") {
            return createGroup(value)
        }
        Map overrides = [:]
        overrides[target.name] = value
        return createUser(overrides)
    }

    protected int countWithValue(Map target, String value) {
        String path = target.resource == "Group" ? "/Groups" : "/Users"
        Response r = scimRequest().queryParam("filter", "${target.name} eq \"${value}\"").get(path)
        assert r.statusCode() == 200 : "RFC 7644 3.4.2.2: filter must return 200 but was ${r.statusCode()}"
        return r.jsonPath().getList("Resources")?.size() ?: 0
    }

    protected String uniqueValue(Map target) {
        String sfx = UUID.randomUUID().toString()
        return target.name == "userName" ? "uniq_${sfx}@test.com" : "uniq-${sfx}"
    }

    /** a second resource with an already used value must be rejected on creation */
    protected boolean assertDuplicateCreateRejected(String level, List<Map> targets) {
        targets.each { Map target ->
            String value = uniqueValue(target)
            Response first = createWithValue(target, value)
            assert first.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${first.statusCode()} for ${target.resource}.${target.name}"
            Response dup = createWithValue(target, value)
            assert dup.statusCode() in [400, 409] :
                "RFC 7643 7: ${target.resource}.${target.name} has uniqueness '${level}', a second resource with the same value should be rejected (409) but POST returned ${dup.statusCode()}"
            assert countWithValue(target, value) == 1 :
                "RFC 7643 7: ${target.resource}.${target.name} has uniqueness '${level}', two resources possess the value '${value}'"
        }
        return true
    }

    /** an existing resource must not be changed to a value another resource already has */
    protected boolean assertDuplicateUpdateRejected(String level, List<Map> targets) {
        targets.each { Map target ->
            String value = uniqueValue(target)
            Response first = createWithValue(target, value)
            assert first.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${first.statusCode()} for ${target.resource}.${target.name}"
            Response second = createWithValue(target, uniqueValue(target))
            assert second.statusCode() == 201
            String id = second.jsonPath().getString("id")
            Response put
            if (target.resource == "Group") {
                put = scimRequest().body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: value])).put("/Groups/${id}")
            } else {
                Map payload = [schemas: [USER_SCHEMA], userName: second.jsonPath().getString("userName")]
                payload[target.name] = value
                put = scimRequest().body(JsonOutput.toJson(payload)).put("/Users/${id}")
            }
            assert put.statusCode() in [400, 409] :
                "RFC 7643 7: ${target.resource}.${target.name} has uniqueness '${level}', updating a resource to a value held by another should be rejected (409) but PUT returned ${put.statusCode()}"
            assert countWithValue(target, value) == 1 :
                "RFC 7643 7: ${target.resource}.${target.name} has uniqueness '${level}', two resources possess the value '${value}'"
        }
        return true
    }
}
