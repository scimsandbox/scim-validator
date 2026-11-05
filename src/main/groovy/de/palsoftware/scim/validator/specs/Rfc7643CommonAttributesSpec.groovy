package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.SpecRef
import de.palsoftware.scim.validator.base.CapabilityState
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7643: SCIM Core Schema
 *
 * One feature per requirement of requirements-rfc7643.yaml (area "Common attributes and extensions"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7643CommonAttributesSpec extends ScimBaseSpec {

    private static final String DATETIME_RE =
        /^-?\d{4,}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?$/
    private static final String RT_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ResourceType"

    private static String uniq() { UUID.randomUUID().toString().substring(0, 12) }

    private static Map json(Response r) {
        new JsonSlurper().parseText(r.asString()) as Map
    }

    private Map mkUser(Map overrides = [:]) {
        Response r = createUser(overrides)
        assert r.statusCode() == 201 : "RFC 7644 3.3: POST /Users must return 201 but was ${r.statusCode()}: ${r.asString()}"
        return json(r)
    }

    private Map mkGroup(String name = "w2grp_${uniq()}") {
        Response r = createGroup(name)
        assert r.statusCode() == 201 : "RFC 7644 3.3: POST /Groups must return 201 but was ${r.statusCode()}: ${r.asString()}"
        return json(r)
    }

    private Map mkEnterpriseUser() {
        if (getCapabilityState("enterprise") != CapabilityState.SUPPORTED) {
            throw new TestAbortedException("enterprise user extension not supported by this server")
        }
        Response r = createFullUser(userName: "w2ent_${uniq()}@test.com")
        assert r.statusCode() == 201 : "RFC 7643 3.3: creating a User with the enterprise extension must return 201 but was ${r.statusCode()}: ${r.asString()}"
        return json(r)
    }

    private Response getResource(String path) {
        scimRequestQuiet().get(path)
    }

    private Response filterGet(String path, String filter) {
        scimRequestQuiet().queryParam("filter", filter).get(path)
    }

    private Map getJsonOk(String path) {
        Response r = getResource(path)
        assert r.statusCode() == 200 : "RFC 7644 3.4.1: GET ${path} must return 200 but was ${r.statusCode()}"
        return json(r)
    }

    private Map resourceTypeFor(String name) {
        Response r = getResource("/ResourceTypes/${name}")
        if (r.statusCode() == 404) {
            throw new TestAbortedException("server does not expose /ResourceTypes/${name}")
        }
        assert r.statusCode() == 200 : "RFC 7644 4: GET /ResourceTypes/${name} must return 200 but was ${r.statusCode()}"
        return json(r)
    }

    private static List<String> schemasOf(Map resource) {
        assert resource.schemas instanceof List : "RFC 7643 3: 'schemas' must be an array but was ${resource.schemas?.getClass()?.simpleName}"
        return (resource.schemas as List).collect { it as String }
    }

    private static void assertDateTime(String label, Object v) {
        assert v instanceof String && (v as String) ==~ DATETIME_RE : "RFC 7643 3.1: ${label} must be a DateTime but was '${v}'"
    }


    // RFC 7643 section 3 (SCIM Resources), MAY, lines 698-700
    // More information about a resource MAY be found in its resource type definition (see Section 6).
    @SpecRef("RFC7643-3-a88624")
    def "RFC7643-3-a88624: More information about a resource MAY be found in its resource type definition (see Section 6)."() {
        when:
        Map user = mkUser()
        String rtName = user.meta?.resourceType as String
        Map rt = resourceTypeFor(rtName)

        then:
        assert rtName : "RFC 7643 3.1: meta.resourceType missing from the User"
        assert rt.name == rtName || rt.id == rtName : "RFC 7643 6: ResourceType '${rtName}' must identify itself but was id=${rt.id} name=${rt.name}"
        assert rt.schema == USER_SCHEMA : "RFC 7643 6: ResourceType of a User must define schema ${USER_SCHEMA} but was ${rt.schema}"
        assert rt.endpoint == "/Users" : "RFC 7643 6: ResourceType of a User must name endpoint /Users but was ${rt.endpoint}"
    }

    // RFC 7643 section 3 (SCIM Resources), MUST, lines 702-706
    // "Schemas" Attribute The "schemas" attribute is a REQUIRED attribute and is an array of Strings containing URIs that are used to indicate the namespaces of the SCIM schemas that define the attributes present in the current JSON structure.
    @SpecRef("RFC7643-3-20d87c")
    def "RFC7643-3-20d87c: 'Schemas' Attribute The 'schemas' attribute is a REQUIRED attribute and is an array of Strings containing..."() {
        expect:
        [mkUser(), mkGroup()].each { Map res ->
            assert res.containsKey("schemas") : "RFC 7643 3: 'schemas' is REQUIRED but missing in ${res.meta?.resourceType} representation"
            assert res.schemas instanceof List && !res.schemas.isEmpty() : "RFC 7643 3: 'schemas' must be a non-empty array"
            res.schemas.each { Object s ->
                assert s instanceof String : "RFC 7643 3: every 'schemas' value must be a String but was ${s}"
                assert (s as String) ==~ /^[A-Za-z][A-Za-z0-9+.\-]*:\S+$/ : "RFC 7643 3: 'schemas' value must be a URI but was '${s}'"
            }
        }
    }

    // RFC 7643 section 3 (SCIM Resources), MUST, lines 709-711
    // All representations of SCIM schemas MUST include a non-empty array with value(s) of the URIs supported by that representation.
    @SpecRef("RFC7643-3-a9b9d0")
    def "RFC7643-3-a9b9d0: All representations of SCIM schemas MUST include a non-empty array with value(s) of the URIs supported by..."() {
        when:
        Map user = mkUser()
        Map group = mkGroup()
        Map listing = json(filterGet("/Users", "id eq \"${user.id}\""))
        Map missing = json(getResource("/Users/${UUID.randomUUID()}"))
        Map rtList = getJsonOk("/ResourceTypes")
        Map schemaList = getJsonOk("/Schemas")
        Map spc = getJsonOk("/ServiceProviderConfig")
        Map rt = getJsonOk("/ResourceTypes/User")

        then:
        Map<String, Map> reps = [
            "User"                 : user,
            "Group"                : group,
            "GET /Users/{id}"      : getJsonOk("/Users/${user.id}"),
            "ListResponse"         : listing,
            "Error"                : missing,
            "ServiceProviderConfig": spc,
            "ResourceType"         : rt,
            "ResourceType list"    : rtList
        ]
        (rtList.Resources as List).eachWithIndex { Map r, int i -> reps["ResourceTypes[${i}]"] = r }
        (schemaList.Resources as List).eachWithIndex { Map r, int i -> reps["Schemas[${i}]"] = r }
        reps.each { String label, Map rep ->
            assert rep.schemas instanceof List && !rep.schemas.isEmpty() : "RFC 7643 3: ${label} must include a non-empty 'schemas' array but had ${rep.schemas}"
            assert rep.schemas.every { it instanceof String && it } : "RFC 7643 3: ${label} 'schemas' must hold URI strings"
        }
        assert schemasOf(listing).contains(LIST_RESPONSE_SCHEMA) : "RFC 7644 3.4.2: list response must carry ${LIST_RESPONSE_SCHEMA}"
        assert schemasOf(missing).contains(ERROR_SCHEMA) : "RFC 7644 3.12: error response must carry ${ERROR_SCHEMA}"
    }

    // RFC 7643 section 3 (SCIM Resources), MUST, lines 711-713
    // The "schemas" attribute for a resource MUST only contain values defined as "schema" and "schemaExtensions" for the resource's defined "resourceType".
    @SpecRef("RFC7643-3-4df73b")
    def "RFC7643-3-4df73b: The 'schemas' attribute for a resource MUST only contain values defined as 'schema' and 'schemaExtensions'..."() {
        when:
        List<Map> samples = [mkUser(), mkGroup()]
        if (getCapabilityState("enterprise") == CapabilityState.SUPPORTED) samples << mkEnterpriseUser()

        then:
        samples.each { Map res ->
            String rtName = res.meta.resourceType as String
            Map rt = resourceTypeFor(rtName)
            Set<String> allowed = [rt.schema as String] as Set
            (rt.schemaExtensions ?: []).each { allowed << (it.schema as String) }
            schemasOf(res).each { String s ->
                assert allowed.contains(s) : "RFC 7643 3: 'schemas' of a ${rtName} may only hold ${allowed} but contained '${s}'"
            }
        }
    }

    // RFC 7643 section 3 (SCIM Resources), MUST NOT, lines 713-714
    // Duplicate values MUST NOT be included.
    @SpecRef("RFC7643-3-7066dc")
    def "RFC7643-3-7066dc: Duplicate values MUST NOT be included."() {
        when:
        Map plain = mkUser()
        String dupName = "w2dup_${uniq()}@test.com"
        List dupSchemas = [USER_SCHEMA, USER_SCHEMA]
        Response dup = scimRequestQuiet()
            .body(JsonOutput.toJson([schemas: dupSchemas, userName: dupName]))
            .post("/Users")
        if (dup.statusCode() == 201) createdUserIds << dup.jsonPath().getString("id")

        then:
        List<String> s1 = schemasOf(plain)
        assert s1.size() == (s1 as Set).size() : "RFC 7643 3: duplicate 'schemas' values in ${s1}"
        assert dup.statusCode() == 201 || dup.statusCode() == 400 : "RFC 7643 3: a request with duplicate schemas must be accepted (201) or rejected (400) but was ${dup.statusCode()}"
        if (dup.statusCode() == 201) {
            List<String> s2 = schemasOf(json(dup))
            assert s2.size() == (s2 as Set).size() : "RFC 7643 3: representation echoed duplicate 'schemas' values ${s2}"
            List<String> s3 = schemasOf(getJsonOk("/Users/${json(dup).id}"))
            assert s3.size() == (s3 as Set).size() : "RFC 7643 3: GET returned duplicate 'schemas' values ${s3}"
        }
    }

    // RFC 7643 section 3 (SCIM Resources), MUST NOT, lines 714-715
    // Value order is not specified and MUST NOT impact behavior.
    @SpecRef("RFC7643-3-87e7cb")
    def "RFC7643-3-87e7cb: Value order is not specified and MUST NOT impact behavior."() {
        when:
        if (getCapabilityState("enterprise") != CapabilityState.SUPPORTED) {
            throw new TestAbortedException("order of schemas values can only be varied with an extension schema; enterprise not supported")
        }
        String suffix = uniq()
        Map ext = [employeeNumber: "E${suffix}", department: "Dept"]
        Response a = scimRequestQuiet().body(JsonOutput.toJson([
            schemas: [USER_SCHEMA, ENTERPRISE_USER_SCHEMA], userName: "w2ord_a_${suffix}@test.com", (ENTERPRISE_USER_SCHEMA): ext])).post("/Users")
        Response b = scimRequestQuiet().body(JsonOutput.toJson([
            schemas: [ENTERPRISE_USER_SCHEMA, USER_SCHEMA], userName: "w2ord_b_${suffix}@test.com", (ENTERPRISE_USER_SCHEMA): ext])).post("/Users")
        [a, b].each { if (it.statusCode() == 201) createdUserIds << it.jsonPath().getString("id") }

        then:
        assert a.statusCode() == 201 : "RFC 7643 3: schemas order [core, extension] must be accepted but was ${a.statusCode()}"
        assert b.statusCode() == 201 : "RFC 7643 3: schemas order [extension, core] must behave the same but was ${b.statusCode()}: ${b.asString()}"
        Map ja = json(a)
        Map jb = json(b)
        assert (schemasOf(ja) as Set) == (schemasOf(jb) as Set) : "RFC 7643 3: order of schemas changed the schemas of the result: ${schemasOf(ja)} vs ${schemasOf(jb)}"
        assert ja[ENTERPRISE_USER_SCHEMA]?.employeeNumber == ext.employeeNumber : "RFC 7643 3: extension attributes lost with order [core, extension]"
        assert jb[ENTERPRISE_USER_SCHEMA]?.employeeNumber == ext.employeeNumber : "RFC 7643 3: extension attributes lost when the extension schema is listed first"
    }

    // RFC 7643 section 3 (SCIM Resources), MUST, lines 720-723
    // These attributes are not defined in any particular schema but SHALL be assumed to be present in every resource, regardless of the value of the "schemas" attribute.
    @SpecRef("RFC7643-3-814607")
    def "RFC7643-3-814607: These attributes are not defined in any particular schema but SHALL be assumed to be present in every..."() {
        expect:
        Map plain = mkUser()
        Map group = mkGroup()
        List<Map> all = [plain, group]
        if (getCapabilityState("enterprise") == CapabilityState.SUPPORTED) all << mkEnterpriseUser()
        all.each { Map res ->
            String label = "${res.meta?.resourceType} with schemas ${res.schemas}"
            assert res.id instanceof String && res.id : "RFC 7643 3: common attribute 'id' missing on ${label}"
            assert res.meta instanceof Map : "RFC 7643 3: common attribute 'meta' missing on ${label}"
            assert res.meta.resourceType : "RFC 7643 3.1: meta.resourceType missing on ${label}"
        }
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 850-853
    // With the exception of the "/ServiceProviderConfig" and "/ResourceTypes" server discovery endpoints and their associated resources, these attributes MUST be defined for all resources, including any extended resource types.
    @SpecRef("RFC7643-3.1-8a2eda")
    def "RFC7643-3.1-8a2eda: With the exception of the '/ServiceProviderConfig' and '/ResourceTypes' server discovery endpoints and..."() {
        expect:
        List<Map> resources = [mkUser(), mkGroup()]
        if (getCapabilityState("enterprise") == CapabilityState.SUPPORTED) resources << mkEnterpriseUser()
        resources.each { Map res ->
            assert res.id : "RFC 7643 3.1: 'id' must be defined on ${res.meta?.resourceType}"
            assert res.meta instanceof Map && res.meta.created && res.meta.lastModified : "RFC 7643 3.1: 'meta' must be defined on ${res.meta?.resourceType}"
        }
        getJsonOk("/Schemas").Resources.each { Map schema ->
            assert schema.id : "RFC 7643 3.1: 'id' must be defined on Schema resources"
            assert schema.meta instanceof Map && schema.meta.resourceType : "RFC 7643 3.1: 'meta' must be defined on Schema ${schema.id} (only /ServiceProviderConfig and /ResourceTypes are exempt)"
        }
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 853-856
    // When accepted by a service provider (e.g., after a SCIM create), the attributes "id" and "meta" (and its associated sub-attributes) MUST be assigned values by the service provider.
    @SpecRef("RFC7643-3.1-f361bd")
    def "RFC7643-3.1-f361bd: When accepted by a service provider (e.g., after a SCIM create), the attributes 'id' and 'meta' (and its..."() {
        when:
        Map created = mkUser()
        Map group = mkGroup()

        then:
        [created, group].each { Map res ->
            String rt = res.meta?.resourceType
            assert res.id instanceof String && res.id : "RFC 7643 3.1: service provider must assign 'id' on create of ${rt}"
            assert res.meta instanceof Map : "RFC 7643 3.1: service provider must assign 'meta' on create of ${rt}"
            ["resourceType", "created", "lastModified", "location"].each { String sub ->
                assert res.meta[sub] instanceof String && res.meta[sub] : "RFC 7643 3.1: service provider must assign meta.${sub} on create of ${rt}"
            }
        }
        Map fetched = getJsonOk("/Users/${created.id}")
        assert fetched.id == created.id && fetched.meta.created == created.meta.created : "RFC 7643 3.1: assigned id/meta must be persisted"
    }

    // RFC 7643 section 3.1 (Common Attributes), MAY, lines 859-860
    // For backward compatibility, some existing schema definitions MAY list common attributes as part of the schema.
    @SpecRef("RFC7643-3.1-98d03f")
    def "RFC7643-3.1-98d03f: For backward compatibility, some existing schema definitions MAY list common attributes as part of the schema."() {
        when:
        Map schema = getJsonOk("/Schemas/${USER_SCHEMA}")
        List listed = (schema.attributes as List).findAll { it.name in ["id", "externalId", "meta"] }
        if (listed.isEmpty()) {
            throw new TestAbortedException("User schema does not list common attributes (allowed)")
        }

        then:
        listed.each { Map attr ->
            assert attr.type : "RFC 7643 3.1: common attribute '${attr.name}' listed in the User schema must carry a type"
            assert attr.containsKey("mutability") : "RFC 7643 3.1: common attribute '${attr.name}' listed in the User schema must carry its characteristics"
        }
        Map user = mkUser()
        listed.each { Map attr ->
            if (attr.name == "id" || attr.name == "meta") {
                assert user.containsKey(attr.name) : "RFC 7643 3.1: attribute '${attr.name}' listed in the schema must appear in representations"
            }
        }
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 860-862
    // The attribute characteristics (see Section 2.2) listed here SHALL take precedence over older definitions that may be included in existing schemas.
    @SpecRef("RFC7643-3.1-b8ab46")
    def "RFC7643-3.1-b8ab46: The attribute characteristics (see Section 2.2) listed here SHALL take precedence over older definitions..."() {
        when:
        List<Map> schemas = (getJsonOk("/Schemas").Resources as List).findAll { it.id in [USER_SCHEMA, GROUP_SCHEMA] }
        List<Map> listed = []
        schemas.each { Map s ->
            (s.attributes as List).findAll { it.name in ["id", "externalId", "meta"] }.each { listed << (it + [schemaId: s.id]) }
        }
        if (listed.isEmpty()) {
            throw new TestAbortedException("core schemas do not list common attributes, nothing to compare")
        }

        then:
        listed.each { Map a ->
            String where = "${a.schemaId} attribute '${a.name}'"
            if (a.name == "id") {
                assert a.caseExact == true : "RFC 7643 3.1: ${where} must have caseExact true"
                assert a.mutability == "readOnly" : "RFC 7643 3.1: ${where} must have mutability readOnly but was ${a.mutability}"
                assert a.returned == "always" : "RFC 7643 3.1: ${where} must have returned 'always' but was ${a.returned}"
            } else if (a.name == "externalId") {
                assert a.caseExact == true : "RFC 7643 3.1: ${where} must have caseExact true"
                assert a.mutability == "readWrite" : "RFC 7643 3.1: ${where} must have mutability readWrite but was ${a.mutability}"
            } else {
                assert a.mutability == "readOnly" : "RFC 7643 3.1: ${where} must have mutability readOnly but was ${a.mutability}"
                assert a.returned == "default" : "RFC 7643 3.1: ${where} must have returned 'default' but was ${a.returned}"
            }
        }
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 866-867
    // Each representation of the resource MUST include a non-empty "id" value.
    @SpecRef("RFC7643-3.1-969b6d")
    def "RFC7643-3.1-969b6d: id: Each representation of the resource MUST include a non-empty 'id' value."() {
        when:
        Map user = mkUser()
        Map group = mkGroup()
        String uid = user.id
        Response filtered = filterGet("/Users", "id eq \"${uid}\"")
        Response projected = getResource("/Users/${uid}?attributes=userName")
        Response put = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: user.userName, displayName: "w2 put"])).put("/Users/${uid}")

        then:
        Map<String, Map> reps = [
            "POST /Users": user, "POST /Groups": group,
            "GET /Users/{id}": getJsonOk("/Users/${uid}"),
            "GET /Groups/{id}": getJsonOk("/Groups/${group.id}"),
            "GET ?attributes=userName": json(projected),
            "PUT /Users/{id}": json(put)
        ]
        assert filtered.statusCode() == 200 && projected.statusCode() == 200 && put.statusCode() == 200 : "RFC 7644: GET filtered/projected and PUT must return 200 but were ${filtered.statusCode()}/${projected.statusCode()}/${put.statusCode()}"
        json(filtered).Resources.each { reps["list entry"] = it }
        reps.each { String label, Map rep ->
            assert rep.id instanceof String && !(rep.id as String).isEmpty() : "RFC 7643 3.1: representation from ${label} must include a non-empty 'id'"
        }
        assert (json(filtered).Resources as List).size() == 1 : "RFC 7643 3.1: filter on id must find the resource"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 867-868
    // This identifier MUST be unique across the SCIM service provider's entire set of resources.
    @SpecRef("RFC7643-3.1-051f62")
    def "RFC7643-3.1-051f62: id: This identifier MUST be unique across the SCIM service provider's entire set of resources."() {
        expect:
        List<String> ids = []
        5.times { ids << (mkUser().id as String) }
        3.times { ids << (mkGroup().id as String) }
        assert ids.every { it } : "RFC 7643 3.1: every resource must have an id"
        assert ids.size() == (ids as Set).size() : "RFC 7643 3.1: ids must be unique across all resources (Users and Groups) but got ${ids}"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 868-870
    // It MUST be a stable, non-reassignable identifier that does not change when the same resource is returned in subsequent requests.
    @SpecRef("RFC7643-3.1-73e95d")
    def "RFC7643-3.1-73e95d: id: It MUST be a stable, non-reassignable identifier that does not change when the same resource is..."() {
        when:
        Map user = mkUser()
        String id = user.id
        Map first = getJsonOk("/Users/${id}")
        Map second = getJsonOk("/Users/${id}")
        Map listed = json(filterGet("/Users", "userName eq \"${user.userName}\""))
        Response put = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: user.userName, displayName: "w2 changed"])).put("/Users/${id}")
        Map afterUpdate = getJsonOk("/Users/${id}")
        scimRequestQuiet().delete("/Users/${id}")
        createdUserIds.remove(id)
        Map next = mkUser()

        then:
        assert first.id == id && second.id == id : "RFC 7643 3.1: id must not change between GETs but was ${first.id}/${second.id}"
        assert (listed.Resources as List)[0]?.id == id : "RFC 7643 3.1: id in a list response must equal the id on create"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}"
        assert json(put).id == id && afterUpdate.id == id : "RFC 7643 3.1: id must not change when the resource is modified"
        assert next.id != id : "RFC 7643 3.1: id must be non-reassignable but a new resource reused deleted id ${id}"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST NOT, lines 870-872
    // The value of the "id" attribute is always issued by the service provider and MUST NOT be specified by the client.
    @SpecRef("RFC7643-3.1-485813")
    def "RFC7643-3.1-485813: id: The value of the 'id' attribute is always issued by the service provider and MUST NOT be specified by..."() {
        when:
        String clientId = UUID.randomUUID().toString()
        String name = "w2cid_${uniq()}@test.com"
        Response r = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: name, id: clientId])).post("/Users")
        if (r.statusCode() == 201) createdUserIds << r.jsonPath().getString("id")
        Map victim = mkUser()
        Response put = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: victim.userName, id: clientId])).put("/Users/${victim.id}")

        then:
        assert r.statusCode() in [201, 400] : "RFC 7643 3.1: client-specified id on create must be ignored (201) or rejected (400) but was ${r.statusCode()}"
        if (r.statusCode() == 201) {
            assert json(r).id != clientId : "RFC 7643 3.1: service provider used the client-specified id ${clientId}"
            assert getResource("/Users/${clientId}").statusCode() == 404 : "RFC 7643 3.1: resource must not be addressable under the client-specified id"
        }
        assert put.statusCode() in [200, 400] : "RFC 7643 3.1: client-specified id in PUT must be ignored (200) or rejected (400) but was ${put.statusCode()}"
        assert getJsonOk("/Users/${victim.id}").id == victim.id : "RFC 7643 3.1: id must not be changeable by the client through PUT"
        assert getResource("/Users/${clientId}").statusCode() == 404 : "RFC 7643 3.1: PUT must not assign the client-specified id ${clientId}"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST NOT, lines 872-874
    // The string "bulkId" is a reserved keyword and MUST NOT be used within any unique identifier value.
    @SpecRef("RFC7643-3.1-1edca8")
    def "RFC7643-3.1-1edca8: id: The string 'bulkId' is a reserved keyword and MUST NOT be used within any unique identifier value."() {
        when:
        List<String> ids = [mkUser().id as String, mkGroup().id as String]
        Response r = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: "w2bid_${uniq()}@test.com", id: "bulkId:${uniq()}"])).post("/Users")
        if (r.statusCode() == 201) {
            createdUserIds << r.jsonPath().getString("id")
            ids << r.jsonPath().getString("id")
        }

        then:
        assert r.statusCode() in [201, 400] : "RFC 7643 3.1: a client-supplied 'bulkId:' id must be ignored or rejected but was ${r.statusCode()}"
        ids.each { String id ->
            assert id && !id.contains("bulkId") : "RFC 7643 3.1: id '${id}' must not contain the reserved keyword bulkId"
        }
    }

    // RFC 7643 section 3.1 (Common Attributes), MAY, lines 887-888
    // Each resource MAY include a non-empty "externalId" value.
    @SpecRef("RFC7643-3.1-11a960")
    def "RFC7643-3.1-11a960: externalId: Each resource MAY include a non-empty 'externalId' value."() {
        when:
        String ext = "w2ext_${uniq()}"
        Map user = mkUser(externalId: ext)
        if (!user.containsKey("externalId")) {
            throw new TestAbortedException("server does not store externalId on Users (optional)")
        }
        Map group = json(scimRequestQuiet().body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: "w2g_${uniq()}", externalId: ext])).post("/Groups"))
        if (group.id) createdGroupIds << (group.id as String)

        then:
        assert user.externalId == ext : "RFC 7643 3.1: externalId must be returned as sent but was '${user.externalId}'"
        assert getJsonOk("/Users/${user.id}").externalId == ext : "RFC 7643 3.1: externalId must persist on GET"
        Map found = json(filterGet("/Users", "externalId eq \"${ext}\""))
        assert (found.Resources as List)*.id == [user.id] : "RFC 7643 3.1: externalId must be usable to locate the resource"
        assert group.externalId == ext : "RFC 7643 3.1: Group externalId must be returned as sent but was '${group.externalId}'"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST NOT, lines 888-890
    // The value of the "externalId" attribute is always issued by the provisioning client and MUST NOT be specified by the service provider.
    @SpecRef("RFC7643-3.1-17a248")
    def "RFC7643-3.1-17a248: externalId: The value of the 'externalId' attribute is always issued by the provisioning client and MUST..."() {
        when:
        Map without = mkUser()
        Map groupWithout = mkGroup()
        Map fetched = getJsonOk("/Users/${without.id}")
        Response put = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: without.userName, displayName: "w2 touched"])).put("/Users/${without.id}")
        Map afterPut = getJsonOk("/Users/${without.id}")

        then:
        [without, groupWithout, fetched, afterPut].each { Map rep ->
            assert !rep.containsKey("externalId") || rep.externalId == null : "RFC 7643 3.1: service provider must not issue an externalId but ${rep.meta.resourceType} had '${rep.externalId}'"
        }
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}"
        Map hit = json(filterGet("/Users", "userName eq \"${without.userName}\" and externalId pr"))
        assert (hit.Resources as List ?: []).isEmpty() : "RFC 7643 3.1: no externalId may be present on a User created without one"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 890-892
    // The service provider MUST always interpret the externalId as scoped to the provisioning domain.
    @SpecRef("RFC7643-3.1-67a7ba")
    def "RFC7643-3.1-67a7ba: externalId: The service provider MUST always interpret the externalId as scoped to the provisioning domain."() {
        when:
        Map a = mkUser()
        String ext = "w2dom_${uniq()}"
        Map b = mkUser(externalId: ext)
        Map c = mkUser(externalId: a.id)
        Map sameAgain = mkUser(externalId: ext)
        Response foundExt = filterGet("/Users", "externalId eq \"${ext}\"")
        Response foundAsId = filterGet("/Users", "externalId eq \"${a.id}\"")

        then:
        assert c.id != a.id && c.externalId == a.id : "RFC 7643 3.1: externalId equal to another resource's id must not be interpreted as that resource (id ${c.id}, externalId ${c.externalId})"
        assert getJsonOk("/Users/${a.id}").userName == a.userName : "RFC 7643 3.1: resource addressed by id must be unaffected by another resource's externalId"
        assert sameAgain.id != b.id : "RFC 7643 3.1: the same externalId on a second resource must not collapse into the first"
        assert foundExt.statusCode() == 200 && (json(foundExt).Resources as List)*.id.sort() == [b.id, sameAgain.id].sort() : "RFC 7643 3.1: externalId filter must match exactly the resources carrying that externalId"
        assert foundAsId.statusCode() == 200 && (json(foundAsId).Resources as List)*.id == [c.id] : "RFC 7643 3.1: externalId filter must not match by id"
    }

    // RFC 7643 section 3.1 (Common Attributes), MAY, lines 904-905
    // This attribute is OPTIONAL.
    @SpecRef("RFC7643-3.1-4f6ee9")
    def "RFC7643-3.1-4f6ee9: externalId: This attribute is OPTIONAL."() {
        when:
        Map user = mkUser()
        Map group = mkGroup()
        Response put = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: user.userName, externalId: "w2opt_${uniq()}"])).put("/Users/${user.id}")
        Response clear = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: user.userName])).put("/Users/${user.id}")

        then:
        assert user.id && group.id : "RFC 7643 3.1: resources must be creatable without externalId"
        assert put.statusCode() == 200 : "RFC 7643 3.1: setting externalId via PUT must return 200 but was ${put.statusCode()}"
        assert clear.statusCode() == 200 : "RFC 7643 3.1: PUT without externalId must be accepted (attribute is OPTIONAL) but was ${clear.statusCode()}"
        assert json(clear).externalId == null : "RFC 7643 3.1: PUT without externalId must leave it unset but was '${json(clear).externalId}'"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 911-912
    // This attribute SHALL be ignored when provided by clients.
    @SpecRef("RFC7643-3.1-96b4db")
    def "RFC7643-3.1-96b4db: meta: This attribute SHALL be ignored when provided by clients."() {
        when:
        String name = "w2meta_${uniq()}@test.com"
        Map fake = [created: "2001-01-01T00:00:00Z", lastModified: "2001-01-01T00:00:00Z", location: "https://evil.example/Users/1", resourceType: "Group", version: "W/\"fake\""]
        Response r = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: name, meta: fake])).post("/Users")
        if (r.statusCode() == 201) createdUserIds << r.jsonPath().getString("id")

        then:
        assert r.statusCode() == 201 : "RFC 7643 3.1: meta provided by a client must be ignored, not rejected: status ${r.statusCode()}: ${r.asString()}"
        Map meta = json(r).meta
        assert meta.created != fake.created && meta.lastModified != fake.lastModified : "RFC 7643 3.1: client-supplied meta timestamps were used (${meta})"
        assert meta.resourceType == "User" : "RFC 7643 3.1: client-supplied meta.resourceType was used (${meta.resourceType})"
        assert !(meta.location as String).contains("evil.example") && (meta.location as String).endsWith("/Users/${json(r).id}") : "RFC 7643 3.1: client-supplied meta.location was used (${meta.location})"
        assert meta.version != fake.version : "RFC 7643 3.1: client-supplied meta.version was used"
        String id = json(r).id
        Response put = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: name, meta: fake])).put("/Users/${id}")
        assert put.statusCode() == 200 : "RFC 7643 3.1: PUT with client meta must be accepted but was ${put.statusCode()}"
        assert getJsonOk("/Users/${id}").meta.created == meta.created : "RFC 7643 3.1: PUT overwrote meta.created with the client value"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 920-920
    // This attribute MUST be a DateTime.
    @SpecRef("RFC7643-3.1-5565f3")
    def "RFC7643-3.1-5565f3: meta.created: This attribute MUST be a DateTime."() {
        expect:
        Map user = mkUser()
        Map group = mkGroup()
        assertDateTime("User meta.created", user.meta?.created)
        assertDateTime("Group meta.created", group.meta?.created)
        assertDateTime("GET meta.created", getJsonOk("/Users/${user.id}").meta?.created)
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 923-925
    // If this resource has never been modified since its initial creation, the value MUST be the same as the value of "created".
    @SpecRef("RFC7643-3.1-0da14a")
    def "RFC7643-3.1-0da14a: meta.lastModified: If this resource has never been modified since its initial creation, the value MUST be..."() {
        expect:
        Map user = mkUser()
        Map group = mkGroup()
        assert user.meta.lastModified == user.meta.created : "RFC 7643 3.1: unmodified User lastModified (${user.meta.lastModified}) must equal created (${user.meta.created})"
        assert group.meta.lastModified == group.meta.created : "RFC 7643 3.1: unmodified Group lastModified (${group.meta.lastModified}) must equal created (${group.meta.created})"
        Map fetched = getJsonOk("/Users/${user.id}")
        assert fetched.meta.lastModified == fetched.meta.created : "RFC 7643 3.1: unmodified User on GET must have lastModified == created"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 927-929
    // This value MUST be the same as the "Content-Location" HTTP response header (see Section 3.1.4.2 of [RFC7231]).
    @SpecRef("RFC7643-3.1-13aa17")
    def "RFC7643-3.1-13aa17: meta.location: This value MUST be the same as the 'Content-Location' HTTP response header (see Section..."() {
        expect:
        Response postUser = createUser()
        assert postUser.statusCode() == 201
        Map user = json(postUser)
        Response postGroup = createGroup("w2loc_${uniq()}")
        assert postGroup.statusCode() == 201
        Map group = json(postGroup)
        assert postUser.header("Content-Location") : "RFC 7643 3.1: POST /Users response must carry a Content-Location header"
        assert postUser.header("Content-Location") == user.meta.location : "RFC 7643 3.1: Content-Location '${postUser.header('Content-Location')}' must equal meta.location '${user.meta.location}'"
        assert postGroup.header("Content-Location") == group.meta.location : "RFC 7643 3.1: Group Content-Location '${postGroup.header('Content-Location')}' must equal meta.location '${group.meta.location}'"
        Response get = getResource("/Users/${user.id}")
        assert get.header("Content-Location") == json(get).meta.location : "RFC 7643 3.1: GET Content-Location '${get.header('Content-Location')}' must equal meta.location '${json(get).meta.location}'"
    }

    // RFC 7643 section 3.1 (Common Attributes), MUST, lines 936-942
    // If a service provider provides "version" (entity-tag) for a representation and the generation of that entity-tag does not satisfy all of the characteristics of a strong validator (see Section 2.1 of [RFC7232]), then the origin server MUST mark the "version" (entity-tag) as weak by prefixing its opaque value with "W/" (case sensitive).
    @SpecRef("RFC7643-3.1-8ed05b")
    def "RFC7643-3.1-8ed05b: meta.version: If a service provider provides 'version' (entity-tag) for a representation and the..."() {
        when:
        Map user = mkUser()
        String version = user.meta?.version as String
        if (!version) {
            throw new TestAbortedException("server does not provide meta.version (optional)")
        }
        String etag = getResource("/Users/${user.id}").header("ETag")
        Response put = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: user.userName, displayName: "w2 v2"])).put("/Users/${user.id}")
        Response put2 = scimRequestQuiet().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: user.userName, displayName: "w2 v3"])).put("/Users/${user.id}")

        then:
        assert version ==~ /^(W\/)?"[^"]*"$/ : "RFC 7643 3.1: version must be an entity-tag, weak ones prefixed with exactly 'W/' (case sensitive), but was ${version}"
        assert etag == null || etag ==~ /^(W\/)?"[^"]*"$/ : "RFC 7232 2.3: ETag header must be a valid entity-tag but was ${etag}"
        assert put.statusCode() == 200 && put2.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}/${put2.statusCode()}"
        if (!version.startsWith("W/")) {
            // strong validator: must differ whenever the representation differs
            String v2 = json(put).meta.version
            String v3 = json(put2).meta.version
            assert version != v2 && v2 != v3 : "RFC 7643 3.1: a version not marked weak must behave as a strong validator, but stayed the same across changed representations (${version}, ${v2}, ${v3})"
        }
    }

    // RFC 7643 section 3.3 (Attribute Extensions to Resources), MAY, lines 974-975
    // Each value in the "schemas" attribute indicates additive schema that MAY exist in a SCIM resource representation.
    @SpecRef("RFC7643-3.3-c5f01e")
    def "RFC7643-3.3-c5f01e: Each value in the 'schemas' attribute indicates additive schema that MAY exist in a SCIM resource..."() {
        when:
        Map user = mkEnterpriseUser()

        then:
        List<String> declared = schemasOf(user)
        assert declared.contains(ENTERPRISE_USER_SCHEMA) : "RFC 7643 3.3: extension schema must be listed in 'schemas' when its attributes are present: ${declared}"
        user.keySet().findAll { it.toString().startsWith("urn:") }.each { Object key ->
            assert declared.contains(key as String) : "RFC 7643 3.3: extension container '${key}' present although not listed in 'schemas' ${declared}"
        }
        Map fetched = getJsonOk("/Users/${user.id}")
        assert schemasOf(fetched).contains(ENTERPRISE_USER_SCHEMA) : "RFC 7643 3.3: GET must keep the extension in 'schemas'"
    }

    // RFC 7643 section 3.3 (Attribute Extensions to Resources), MUST, lines 975-977
    // The "schemas" attribute MUST contain at least one value, which SHALL be the base schema for the resource.
    @SpecRef("RFC7643-3.3-13d25c")
    def "RFC7643-3.3-13d25c: The 'schemas' attribute MUST contain at least one value, which SHALL be the base schema for the resource."() {
        expect:
        List<Map> resources = [mkUser(), mkGroup()]
        if (getCapabilityState("enterprise") == CapabilityState.SUPPORTED) resources << mkEnterpriseUser()
        resources.each { Map res ->
            Map rt = resourceTypeFor(res.meta.resourceType as String)
            List<String> s = schemasOf(res)
            assert s.size() >= 1 : "RFC 7643 3.3: 'schemas' must contain at least one value"
            assert s.contains(rt.schema as String) : "RFC 7643 3.3: 'schemas' of a ${res.meta.resourceType} must contain its base schema ${rt.schema} but was ${s}"
        }
    }

    // RFC 7643 section 3.3 (Attribute Extensions to Resources), MAY, lines 977-978
    // The "schemas" attribute MAY contain additional values indicating extended schemas that are in use.
    @SpecRef("RFC7643-3.3-4fbd1c")
    def "RFC7643-3.3-4fbd1c: The 'schemas' attribute MAY contain additional values indicating extended schemas that are in use."() {
        when:
        Map user = mkEnterpriseUser()

        then:
        List<String> s = schemasOf(user)
        assert s.size() >= 2 : "RFC 7643 3.3: User with extension attributes must list the extension in 'schemas' besides the base: ${s}"
        assert s.contains(USER_SCHEMA) && s.contains(ENTERPRISE_USER_SCHEMA) : "RFC 7643 3.3: 'schemas' must contain base and extension but was ${s}"
        assert getJsonOk("/Users/${user.id}").schemas.contains(ENTERPRISE_USER_SCHEMA) : "RFC 7643 3.3: extension must remain listed on GET"
    }

    // RFC 7643 section 3.3 (Attribute Extensions to Resources), SHOULD, lines 978-980
    // Schema extensions SHOULD avoid redefining any attributes defined in this specification and SHOULD follow conventions defined in this specification.
    @SpecRef("RFC7643-3.3-97c769")
    def "RFC7643-3.3-97c769: Schema extensions SHOULD avoid redefining any attributes defined in this specification and SHOULD follow..."() {
        when:
        List<Map> rts = getJsonOk("/ResourceTypes").Resources as List
        Map schemasByUrn = [:]
        (getJsonOk("/Schemas").Resources as List).each { schemasByUrn[it.id] = it }
        Set<String> common = ["id", "externalId", "meta", "schemas"] as Set
        List<String> problems = []
        int checked = 0

        then:
        rts.each { Map rt ->
            Set<String> coreNames = common + ((schemasByUrn[rt.schema]?.attributes ?: []) as List).collect { it.name as String }
            (rt.schemaExtensions ?: []).each { Map ext ->
                Map schema = schemasByUrn[ext.schema as String]
                assert schema != null : "RFC 7643 3.3: extension ${ext.schema} of ${rt.name} must be described in /Schemas"
                checked++
                (schema.attributes as List).each { Map attr ->
                    if (coreNames.contains(attr.name as String)) problems << "${ext.schema} redefines '${attr.name}'"
                    ["name", "type", "multiValued"].each { String f ->
                        if (attr[f] == null) problems << "${ext.schema} attribute '${attr.name}' lacks '${f}'"
                    }
                }
            }
        }
        if (checked == 0) throw new TestAbortedException("no schema extensions defined")
        assert problems.isEmpty() : "RFC 7643 3.3: schema extensions SHOULD avoid redefining specification attributes and follow its conventions: ${problems}"
    }

    // RFC 7643 section 3.3 (Attribute Extensions to Resources), MUST, lines 980-983
    // Except for the base object schema, the schema extension URI SHALL be used as a JSON container to distinguish attributes belonging to the extension namespace from base schema attributes.
    @SpecRef("RFC7643-3.3-7042da")
    def "RFC7643-3.3-7042da: Except for the base object schema, the schema extension URI SHALL be used as a JSON container to..."() {
        when:
        Map user = mkEnterpriseUser()
        Map schema = getJsonOk("/Schemas/${ENTERPRISE_USER_SCHEMA}")
        List<String> extNames = (schema.attributes as List).collect { it.name as String }

        then:
        assert user[ENTERPRISE_USER_SCHEMA] instanceof Map : "RFC 7643 3.3: extension attributes must be inside a JSON object keyed by the extension URI but ${ENTERPRISE_USER_SCHEMA} was ${user[ENTERPRISE_USER_SCHEMA]?.getClass()?.simpleName}"
        assert user[ENTERPRISE_USER_SCHEMA].employeeNumber != null : "RFC 7643 3.3: extension attribute employeeNumber missing inside the container"
        ["employeeNumber", "department"].each { String n ->
            assert !user.containsKey(n) : "RFC 7643 3.3: extension attribute '${n}' must not appear at the top level next to base attributes"
        }
        Map fetched = getJsonOk("/Users/${user.id}")
        assert fetched[ENTERPRISE_USER_SCHEMA]?.employeeNumber == user[ENTERPRISE_USER_SCHEMA].employeeNumber : "RFC 7643 3.3: container must persist on GET"
        assert extNames.contains("employeeNumber") : "RFC 7643 3.3: extension schema must define employeeNumber"
    }

    // RFC 7643 section 3.3 (Attribute Extensions to Resources), MAY, lines 987-990
    // In order to determine which URI value in the "schemas" attribute is the base schema and which is an extended schema for any given resource, the resource's "resourceType" attribute value MAY be used to retrieve the resource's "ResourceType" schema (see Section 6).
    @SpecRef("RFC7643-3.3-1d9960")
    def "RFC7643-3.3-1d9960: In order to determine which URI value in the 'schemas' attribute is the base schema and which is an..."() {
        when:
        Map user = mkEnterpriseUser()
        Map rt = resourceTypeFor(user.meta.resourceType as String)
        List<String> s = schemasOf(user)
        String base = rt.schema
        Set<String> extensions = ((rt.schemaExtensions ?: []) as List).collect { it.schema as String } as Set

        then:
        assert s.contains(base) : "RFC 7643 3.3: ResourceType.schema ${base} must be identifiable as the base schema within ${s}"
        List<String> others = s - [base]
        assert !others.isEmpty() : "RFC 7643 3.3: an extended User must have at least one extension URI besides the base"
        others.each { String u ->
            assert extensions.contains(u) : "RFC 7643 3.3: remaining schema '${u}' must be an extension listed in the ResourceType (${extensions})"
        }
    }
}
