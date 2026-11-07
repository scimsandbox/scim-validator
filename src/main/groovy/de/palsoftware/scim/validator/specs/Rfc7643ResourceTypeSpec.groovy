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
 * One feature per requirement of requirements-rfc7643.yaml (area "ResourceType"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7643ResourceTypeSpec extends ScimBaseSpec {

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1607-1609
    // Unlike other core resources, all attributes are REQUIRED unless otherwise specified.
    @SpecRef("RFC7643-6-db9c67")
    def "RFC7643-6-db9c67: Unlike other core resources, all attributes are REQUIRED unless otherwise specified."() {
        expect:
        List types = resourceTypes()
        assert !types.isEmpty() : "RFC 7643 6: /ResourceTypes returned no resource types"
        types.each { rt ->
            ["name", "endpoint", "schema"].each { attr ->
                def v = rt[attr]
                assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 6: ResourceType attribute ${attr} is REQUIRED (non-empty string) but was ${v} in ${rt}"
            }
            def ext = rt["schemaExtensions"]
            (ext ?: []).each { e ->
                assert e instanceof Map && e["schema"] instanceof String && e["required"] instanceof Boolean : "RFC 7643 6: schemaExtensions entries must carry schema and required, got ${e}"
            }
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MAY, lines 1616-1616
    // OPTIONAL.
    @SpecRef("RFC7643-6-26a55b")
    def "RFC7643-6-26a55b: id: OPTIONAL."() {
        expect:
        List types = resourceTypes()
        def withId = types.findAll { it["id"] != null }
        if (withId.isEmpty()) {
            throw new TestAbortedException("No resource type carries the OPTIONAL id attribute")
        }
        withId.each { rt ->
            assert rt["id"] instanceof String && !rt["id"].trim().isEmpty() : "RFC 7643 6: ResourceType id, when present, must be a non-empty string but was ${rt["id"]}"
        }
        assert withId.collect { it["id"] }.toSet().size() == withId.size() : "RFC 7643 6: ResourceType ids must be server-unique"
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1619-1620
    // When applicable, service providers MUST specify the name, e.g., "User" or "Group".
    @SpecRef("RFC7643-6-ed7e2b")
    def "RFC7643-6-ed7e2b: name: When applicable, service providers MUST specify the name, e.g., 'User' or 'Group'."() {
        expect:
        List types = resourceTypes()
        def byEndpoint = { String ep -> types.find { it["endpoint"]?.toString()?.replaceFirst("^/", "") == ep } }
        def user = byEndpoint("Users")
        def group = byEndpoint("Groups")
        assert user != null : "RFC 7643 6: no resource type with endpoint /Users"
        assert group != null : "RFC 7643 6: no resource type with endpoint /Groups"
        assert user["name"] == "User" : "RFC 7643 6: name of the Users resource type should be \"User\" but was ${user["name"]}"
        assert group["name"] == "Group" : "RFC 7643 6: name of the Groups resource type should be \"Group\" but was ${group["name"]}"
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1623-1623
    // REQUIRED
    @SpecRef("RFC7643-6-d563ea")
    def "RFC7643-6-d563ea: name: REQUIRED"() {
        expect:
        List types = resourceTypes()
        assert !types.isEmpty() : "RFC 7643 6: /ResourceTypes returned no resource types"
        types.each { rt ->
            def v = rt["name"]
            assert v != null : "RFC 7643 6: ResourceType attribute name is REQUIRED but is missing in ${rt}"
            assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 6: ResourceType name must be a non-empty string but was ${v} in ${rt}"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1633-1634
    // When applicable, service providers MUST specify the description.
    @SpecRef("RFC7643-6-e1fe2a")
    def "RFC7643-6-e1fe2a: description: When applicable, service providers MUST specify the description."() {
        expect:
        List types = resourceTypes()
        assert !types.isEmpty() : "RFC 7643 6: /ResourceTypes returned no resource types"
        types.each { rt ->
            def v = rt["description"]
            assert v != null : "RFC 7643 6: ResourceType attribute description is REQUIRED but is missing in ${rt}"
            assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 6: ResourceType description must be specified as a non-empty string but was ${v} in ${rt}"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MAY, lines 1634-1634
    // OPTIONAL.
    @SpecRef("RFC7643-6-ce71a2")
    def "RFC7643-6-ce71a2: description: OPTIONAL."() {
        expect:
        List types = resourceTypes()
        def withDesc = types.findAll { it["description"] != null }
        if (withDesc.isEmpty()) {
            throw new TestAbortedException("No resource type carries the OPTIONAL description attribute")
        }
        withDesc.each { rt ->
            assert rt["description"] instanceof String : "RFC 7643 6: ResourceType description, when present, must be a string but was ${rt["description"]}"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1639-1639
    // REQUIRED
    @SpecRef("RFC7643-6-0f19ea")
    def "RFC7643-6-0f19ea: endpoint: REQUIRED"() {
        expect:
        List types = resourceTypes()
        assert !types.isEmpty() : "RFC 7643 6: /ResourceTypes returned no resource types"
        types.each { rt ->
            def v = rt["endpoint"]
            assert v != null : "RFC 7643 6: ResourceType attribute endpoint is REQUIRED but is missing in ${rt}"
            assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 6: ResourceType endpoint must be a non-empty string but was ${v} in ${rt}"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1643-1644
    // This MUST be equal to the "id" attribute of the associated "Schema" resource.
    @SpecRef("RFC7643-6-addfa5")
    def "RFC7643-6-addfa5: schema: This MUST be equal to the 'id' attribute of the associated 'Schema' resource."() {
        expect:
        List types = resourceTypes()
        Set ids = schemaIds()
        assert !types.isEmpty() : "RFC 7643 6: /ResourceTypes returned no resource types"
        types.each { rt ->
            assert ids.contains(rt["schema"]) : "RFC 7643 6: ResourceType ${rt["name"]} schema ${rt["schema"]} must equal the id of a Schema resource (known: ${ids})"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1645-1645
    // REQUIRED.
    @SpecRef("RFC7643-6-971685")
    def "RFC7643-6-971685: schema: REQUIRED."() {
        expect:
        List types = resourceTypes()
        assert !types.isEmpty() : "RFC 7643 6: /ResourceTypes returned no resource types"
        types.each { rt ->
            def v = rt["schema"]
            assert v != null : "RFC 7643 6: ResourceType attribute schema is REQUIRED but is missing in ${rt}"
            assert v instanceof String && !v.trim().isEmpty() : "RFC 7643 6: ResourceType schema must be a non-empty URI string but was ${v} in ${rt}"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MAY, lines 1649-1649
    // OPTIONAL.
    @SpecRef("RFC7643-6-0af5e7")
    def "RFC7643-6-0af5e7: schemaExtensions: OPTIONAL."() {
        expect:
        List types = resourceTypes()
        def withExt = types.findAll { it["schemaExtensions"] != null }
        if (withExt.isEmpty()) {
            throw new TestAbortedException("No resource type carries the OPTIONAL schemaExtensions attribute")
        }
        withExt.each { rt ->
            assert rt["schemaExtensions"] instanceof List : "RFC 7643 6: schemaExtensions must be a list but was ${rt["schemaExtensions"]}"
        }
        assert withExt.any { !it["schemaExtensions"].isEmpty() } : "RFC 7643 6: schemaExtensions is present on every type but never lists an extension"
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1652-1653
    // This MUST be equal to the "id" attribute of a "Schema" resource.
    @SpecRef("RFC7643-6-80607c")
    def "RFC7643-6-80607c: schemaExtensions.schema: This MUST be equal to the 'id' attribute of a 'Schema' resource."() {
        expect:
        Set ids = schemaIds()
        List exts = allExtensions()
        if (exts.isEmpty()) {
            throw new TestAbortedException("No resource type declares schema extensions")
        }
        exts.each { e ->
            assert ids.contains(e["schema"]) : "RFC 7643 6: schemaExtensions.schema ${e["schema"]} must equal the id of a Schema resource (known: ${ids})"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1653-1653
    // REQUIRED.
    @SpecRef("RFC7643-6-f3f19d")
    def "RFC7643-6-f3f19d: schemaExtensions.schema: REQUIRED."() {
        expect:
        List exts = allExtensions()
        if (exts.isEmpty()) {
            throw new TestAbortedException("No resource type declares schema extensions")
        }
        exts.each { e ->
            assert e["schema"] instanceof String && !e["schema"].trim().isEmpty() : "RFC 7643 6: schemaExtensions.schema is REQUIRED but was ${e["schema"]}"
        }
    }

    // RFC 7643 section 6 (ResourceType Schema), MAY, lines 1659-1660
    // If false, a resource of this type MAY omit this schema extension.
    @SpecRef("RFC7643-6-7282ea")
    def "RFC7643-6-7282ea: schemaExtensions.required: If false, a resource of this type MAY omit this schema extension."() {
        expect:
        List types = resourceTypes()
        def user = types.find { it["endpoint"]?.toString()?.replaceFirst("^/", "") == "Users" }
        def optional = (user?.get("schemaExtensions") ?: []).findAll { it["required"] == false }
        if (optional.isEmpty()) {
            throw new TestAbortedException("The Users resource type declares no schema extension with required=false")
        }
        Response response = createUser()
        assert response.statusCode() == 201 : "RFC 7643 6: a User may omit extensions with required=false (${optional*.schema}) but POST returned ${response.statusCode()}"
        def schemas = response.jsonPath().getList("schemas")
        assert schemas.contains(USER_SCHEMA) : "RFC 7643 6: created User must carry the core schema, got ${schemas}"
    }

    // RFC 7643 section 6 (ResourceType Schema), MUST, lines 1660-1660
    // REQUIRED.
    @SpecRef("RFC7643-6-1025e0")
    def "RFC7643-6-1025e0: schemaExtensions.required: REQUIRED."() {
        expect:
        List exts = allExtensions()
        if (exts.isEmpty()) {
            throw new TestAbortedException("No resource type declares schema extensions")
        }
        exts.each { e ->
            assert e["required"] instanceof Boolean : "RFC 7643 6: schemaExtensions.required is REQUIRED and must be boolean but was ${e["required"]} for ${e["schema"]}"
        }
    }

    private List<Map> resourceTypes() {
        Response response = scimRequestQuiet().get("/ResourceTypes")
        assert response.statusCode() == 200 : "RFC 7643 6: GET /ResourceTypes must return 200 but was ${response.statusCode()}"
        List<Map> list = response.jsonPath().getList("Resources")
        assert list != null : "RFC 7643 6: /ResourceTypes must return a ListResponse with Resources"
        return list
    }

    private Set schemaIds() {
        Response response = scimRequestQuiet().get("/Schemas")
        assert response.statusCode() == 200 : "RFC 7643 7: GET /Schemas must return 200 but was ${response.statusCode()}"
        return (response.jsonPath().getList("Resources.id") ?: []).toSet()
    }

    private List<Map> allExtensions() {
        List<Map> result = []
        resourceTypes().each { rt ->
            (rt["schemaExtensions"] ?: []).each { result << (it as Map) }
        }
        return result
    }
}
