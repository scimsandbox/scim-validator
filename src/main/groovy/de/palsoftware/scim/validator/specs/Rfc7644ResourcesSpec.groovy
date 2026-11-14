package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimJsonSupport
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import de.palsoftware.scim.validator.base.CapabilityState
import groovy.json.JsonOutput
import io.restassured.response.Response

/**
 * RFC 7644: SCIM Protocol
 *
 * One feature per requirement of requirements-rfc7644.yaml (area "Resource lifecycle"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7644ResourcesSpec extends ScimBaseSpec {
    private static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12)
    }

    // RFC 7644 section 3.2 (SCIM Endpoints and HTTP Methods), SHOULD, lines 472-475
    // Service providers that support extended resources SHOULD define resource endpoints using the convention of pluralizing the resource name defined in the extended schema, by appending an 's'.
    @SpecRef("RFC7644-3.2-c8114e")
    def "RFC7644-3.2-c8114e: Service providers that support extended resources SHOULD define resource endpoints using the convention of..."() {
        expect: "every resource type endpoint is the pluralized resource name"
        Response response = scimRequest().get("/ResourceTypes")
        assert response.statusCode() == 200 : "RFC 7644 3.2: GET /ResourceTypes must return 200 but was ${response.statusCode()}"
        List<Map> types = response.jsonPath().getList("Resources")
        assert types : "RFC 7644 3.2: /ResourceTypes must list at least the User and Group resource types"
        types.each { Map rt ->
            assert rt.endpoint == "/" + rt.name + "s" :
                "RFC 7644 3.2: endpoint of resource type '${rt.name}' SHOULD be '/${rt.name}s' but was '${rt.endpoint}'"
        }
    }

    // RFC 7644 section 3.2 (SCIM Endpoints and HTTP Methods), MAY, lines 485-486
    // Depending on the endpoint, creates new resources, creates a search request, or MAY be used to bulk-modify resources.
    @SpecRef("RFC7644-3.2-785a94")
    def "RFC7644-3.2-785a94: POST: Depending on the endpoint, creates new resources, creates a search request, or MAY be used to..."() {
        given: "unique users for the create and search checks"
        String name = "res785_${uniq()}@test.com"

        when: "POST to the resource endpoint"
        Response created = createUser(userName: name)

        then: "it creates a new resource"
        assert created.statusCode() == 201 : "RFC 7644 3.2: POST /Users must create a resource (201) but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        assert id : "RFC 7644 3.2: created resource must have an id"
        assert scimRequest().get("/Users/${id}").statusCode() == 200 : "RFC 7644 3.2: POST-created resource must be retrievable"

        and: "POST to the .search endpoint creates a search request (if the server supports search)"
        if (getCapabilityState("search") == CapabilityState.SUPPORTED) {
            Response search = scimRequest()
                .body(JsonOutput.toJson([schemas: [SEARCH_REQUEST_SCHEMA], filter: "userName eq \"${name}\""]))
                .post("/Users/.search")
            assert search.statusCode() == 200 : "RFC 7644 3.2/3.4.3: POST /Users/.search must return 200 but was ${search.statusCode()}"
            assert search.jsonPath().getList("schemas").contains(LIST_RESPONSE_SCHEMA) : "RFC 7644 3.4.3: .search must return a ListResponse"
            assert search.jsonPath().getList("Resources.id") == [id] : "RFC 7644 3.4.3: .search must return the filtered resource"
            assert search.jsonPath().getList("Resources.id") != null
        }

        and: "POST to /Bulk bulk-modifies resources (if the server supports bulk)"
        if (isBulkSupported()) {
            String bulkName = "res785b_${uniq()}@test.com"
            Map bulk = [
                schemas   : [BULK_REQUEST_SCHEMA],
                Operations: [[method: "POST", path: "/Users", bulkId: "b1",
                              data  : [schemas: [USER_SCHEMA], userName: bulkName]]]
            ]
            Response bulkResp = scimRequest().body(JsonOutput.toJson(bulk)).post("/Bulk")
            String bulkLocation = bulkResp.jsonPath().getString("Operations[0].location")
            if (bulkLocation) createdUserIds << bulkLocation.substring(bulkLocation.lastIndexOf('/') + 1)
            assert bulkResp.statusCode() == 200 : "RFC 7644 3.2/3.7: POST /Bulk must return 200 but was ${bulkResp.statusCode()}"
            assert bulkResp.jsonPath().getString("Operations[0].status") == "201" :
                "RFC 7644 3.7: bulk POST operation must report status 201 but was ${bulkResp.jsonPath().getString('Operations[0].status')}"
        }
    }

    // RFC 7644 section 3.2 (SCIM Endpoints and HTTP Methods), MUST NOT, lines 489-490
    // PUT MUST NOT be used to create new resources.
    @SpecRef("RFC7644-3.2-bc7273")
    def "RFC7644-3.2-bc7273: PUT: PUT MUST NOT be used to create new resources."() {
        given: "an id that does not exist on the server"
        String fakeId = UUID.randomUUID().toString()
        String name = "res_bc7273_${uniq()}@test.com"

        when: "PUT is used on a non-existent user"
        Response put = scimRequest()
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], id: fakeId, userName: name]))
            .put("/Users/${fakeId}")
        if (put.statusCode() in [200, 201]) {
            String newId = put.jsonPath().getString("id")
            if (newId) createdUserIds << newId
        }

        then: "no resource is created"
        assert put.statusCode() == 404 : "RFC 7644 3.2: PUT to a non-existent resource must not create it (expected 404) but was ${put.statusCode()}"
        assert scimRequest().get("/Users/${fakeId}").statusCode() == 404 : "RFC 7644 3.2: resource must not exist after PUT"
        Response search = scimRequest().queryParam("filter", "userName eq \"${name}\"").get("/Users")
        assert search.jsonPath().getInt("totalResults") == 0 : "RFC 7644 3.2: no user with the PUT userName may exist"
    }

    // RFC 7644 section 3.2 (SCIM Endpoints and HTTP Methods), SHOULD, lines 570-572
    // Error status codes SHOULD be transmitted via the HTTP status code of the response (if possible) and SHOULD also be specified in the body of the response (see Section 3.12).
    @SpecRef("RFC7644-3.2-206bde")
    def "RFC7644-3.2-206bde: Error status codes SHOULD be transmitted via the HTTP status code of the response (if possible) and SHOULD..."() {
        when: "a request that must fail (GET of a non-existent user)"
        Response response = scimRequest().get("/Users/${UUID.randomUUID()}")

        then: "the error is in the HTTP status and in the body"
        assert response.statusCode() == 404 : "RFC 7644 3.2: error must be transmitted via the HTTP status code (404) but was ${response.statusCode()}"
        assertScimError(response, 404)
    }

    // RFC 7644 section 3.3 (Creating Resources), MUST, lines 580-581
    // The server SHALL process attributes according to the following mutability rules:
    @SpecRef("RFC7644-3.3-7490d1")
    def "RFC7644-3.3-7490d1: The server SHALL process attributes according to the following mutability rules:"() {
        given: "a POST body asserting readOnly attributes and omitting optional readWrite attributes"
        String name = "res7490_${uniq()}@test.com"
        Map payload = [schemas: [USER_SCHEMA], userName: name, id: "client-id-${uniq()}",
                       meta   : [created: "2001-01-01T00:00:00Z", resourceType: "Group"]]

        when:
        Response response = scimRequestQuiet().body(JsonOutput.toJson(payload)).post("/Users")
        if (response.statusCode() == 201) createdUserIds << response.jsonPath().getString("id")

        then: "readOnly values are ignored and the omitted readWrite attributes do not make the request fail"
        assert response.statusCode() == 201 : "RFC 7644 3.3: POST must be processed per the mutability rules (201) but was ${response.statusCode()}"
        assert response.jsonPath().getString("id") != payload.id : "RFC 7644 3.3: readOnly id supplied by the client SHALL be ignored"
        assert response.jsonPath().getString("meta.resourceType") == "User" : "RFC 7644 3.3: readOnly meta.resourceType SHALL be ignored"
        assert response.jsonPath().getString("meta.created") != "2001-01-01T00:00:00Z" : "RFC 7644 3.3: readOnly meta.created SHALL be ignored"
    }

    // RFC 7644 section 3.3 (Creating Resources), MUST, lines 583-584
    // In the request body, attributes whose mutability is "readOnly" (see Sections 2.2 and 7 of [RFC7643]) SHALL be ignored.
    @SpecRef("RFC7644-3.3-ae7143")
    def "RFC7644-3.3-ae7143: In the request body, attributes whose mutability is 'readOnly' (see Sections 2.2 and 7 of [RFC7643]) SHALL..."() {
        given: "a POST body with client-chosen values for readOnly attributes"
        String name = "resae71_${uniq()}@test.com"
        Map payload = [schemas : [USER_SCHEMA], userName: name, id: "client-id-${uniq()}",
                       meta    : [created: "2001-01-01T00:00:00Z", lastModified: "2001-01-01T00:00:00Z",
                                  resourceType: "Group", location: "http://example.invalid/Users/x"],
                       groups  : [[value: "client-group-id", display: "Fake"]]]

        when:
        Response response = scimRequestQuiet().body(JsonOutput.toJson(payload)).post("/Users")
        if (response.statusCode() == 201) createdUserIds << response.jsonPath().getString("id")

        then: "the readOnly values are ignored (not rejected, not stored)"
        assert response.statusCode() == 201 : "RFC 7644 3.3: readOnly attributes SHALL be ignored, so POST must succeed (201) but was ${response.statusCode()}"
        assert response.jsonPath().getString("id") != payload.id : "RFC 7644 3.3: client-supplied id SHALL be ignored"
        assert response.jsonPath().getString("meta.created") != "2001-01-01T00:00:00Z" : "RFC 7644 3.3: client-supplied meta.created SHALL be ignored"
        assert response.jsonPath().getString("meta.lastModified") != "2001-01-01T00:00:00Z" : "RFC 7644 3.3: client-supplied meta.lastModified SHALL be ignored"
        assert response.jsonPath().getString("meta.resourceType") == "User" : "RFC 7644 3.3: client-supplied meta.resourceType SHALL be ignored"
        assert !(response.jsonPath().getString("meta.location") ?: "").contains("example.invalid") : "RFC 7644 3.3: client-supplied meta.location SHALL be ignored"
        assert !(response.jsonPath().getList("groups.value") ?: []).contains("client-group-id") : "RFC 7644 3.3: client-supplied readOnly groups SHALL be ignored"
    }

    // RFC 7644 section 3.3 (Creating Resources), MAY, lines 586-588
    // Attributes whose mutability is "readWrite" (see Section 2.2 of [RFC7643]) and that are omitted from the request body MAY be assumed to be not asserted by the client.
    @SpecRef("RFC7644-3.3-972658")
    def "RFC7644-3.3-972658: Attributes whose mutability is 'readWrite' (see Section 2.2 of [RFC7643]) and that are omitted from the..."() {
        given: "a user created with only the required attributes"
        String name = "res9726_${uniq()}@test.com"

        when:
        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: name])).post("/Users")
        if (response.statusCode() == 201) createdUserIds << response.jsonPath().getString("id")

        then: "omitted readWrite attributes are accepted as not asserted and the stored state matches the response"
        assert response.statusCode() == 201 : "RFC 7644 3.3: omitting readWrite attributes must be accepted (201) but was ${response.statusCode()}"
        Response get = scimRequest().get("/Users/${response.jsonPath().getString('id')}")
        assert get.statusCode() == 200
        Map<String, Object> getMap = ScimJsonSupport.parseMap(get)
        Map<String, Object> postMap = ScimJsonSupport.parseMap(response)
        ["displayName", "title", "nickName", "emails", "phoneNumbers", "name"].each { String attr ->
            assert ScimJsonSupport.getLiteralAttribute(getMap, attr) == ScimJsonSupport.getLiteralAttribute(postMap, attr) :
                "RFC 7644 3.3: non-asserted attribute '${attr}' must be the same in the POST response (${ScimJsonSupport.getLiteralAttribute(postMap, attr)}) and a later GET (${ScimJsonSupport.getLiteralAttribute(getMap, attr)})"
        }
        assert response.jsonPath().getString("userName") == name
    }

    // RFC 7644 section 3.3 (Creating Resources), MAY, lines 588-590
    // The service provider MAY assign a default value to non-asserted attributes in the final resource representation.
    @SpecRef("RFC7644-3.3-552f22")
    def "RFC7644-3.3-552f22: The service provider MAY assign a default value to non-asserted attributes in the final resource..."() {
        given: "a user created with only the required attributes"
        String name = "res552f_${uniq()}@test.com"
        Map payload = [schemas: [USER_SCHEMA], userName: name]

        when:
        Response response = scimRequestQuiet().body(JsonOutput.toJson(payload)).post("/Users")
        if (response.statusCode() == 201) createdUserIds << response.jsonPath().getString("id")
        assert response.statusCode() == 201 : "RFC 7644 3.3: POST must return 201 but was ${response.statusCode()}"
        Map body = response.jsonPath().getMap("")
        Map stored = scimRequest().get("/Users/${body.id}").jsonPath().getMap("")

        then: "any value the server defaulted for a non-asserted attribute is consistent between response and stored resource"
        Set serverManaged = ["id", "meta", "schemas", "groups"] as Set
        List<String> defaulted = body.keySet().findAll { !payload.containsKey(it) && !(it in serverManaged) } as List
        defaulted.each { String attr ->
            assert stored[attr] == body[attr] : "RFC 7644 3.3: defaulted attribute '${attr}' must be the same in the response and the stored resource"
        }
    }

    // RFC 7644 section 3.3 (Creating Resources), MAY, lines 596-599
    // Clients that intend to override existing or server-defaulted values for attributes MAY specify "null" for a single-valued attribute or an empty array "[]" for a multi-valued attribute to clear all values.
    @SpecRef("RFC7644-3.3-5fd1bd")
    def "RFC7644-3.3-5fd1bd: Clients that intend to override existing or server-defaulted values for attributes MAY specify 'null' for..."() {
        given: "a POST that clears attributes with null and an empty array"
        String name = "res5fd1_${uniq()}@test.com"
        Map payload = [schemas: [USER_SCHEMA], userName: name, title: null, nickName: null, phoneNumbers: [], emails: []]

        when:
        Response response = scimRequestQuiet().body(JsonOutput.toJson(payload)).post("/Users")
        if (response.statusCode() == 201) createdUserIds << response.jsonPath().getString("id")

        then: "the request succeeds and the attributes carry no values"
        assert response.statusCode() == 201 : "RFC 7644 3.3: null / [] in POST must be accepted (201) but was ${response.statusCode()}"
        Map stored = scimRequest().get("/Users/${response.jsonPath().getString('id')}").jsonPath().getMap("")
        assert stored.title == null : "RFC 7644 3.3: title specified as null must have no value but was ${stored.title}"
        assert stored.nickName == null : "RFC 7644 3.3: nickName specified as null must have no value but was ${stored.nickName}"
        assert !stored.phoneNumbers : "RFC 7644 3.3: phoneNumbers specified as [] must have no values but was ${stored.phoneNumbers}"
        assert !stored.emails : "RFC 7644 3.3: emails specified as [] must have no values but was ${stored.emails}"
    }

    // RFC 7644 section 3.3 (Creating Resources), MUST, lines 601-602
    // When the service provider successfully creates the new resource, an HTTP response SHALL be returned with HTTP status code 201 (Created).
    @SpecRef("RFC7644-3.3-49498f")
    def "RFC7644-3.3-49498f: When the service provider successfully creates the new resource, an HTTP response SHALL be returned with..."() {
        when:
        Response response = createUser(userName: "res4949_${uniq()}@test.com")

        then:
        assert response.statusCode() == 201 : "RFC 7644 3.3: successful POST must return 201 (Created) but was ${response.statusCode()}"
    }

    // RFC 7644 section 3.3 (Creating Resources), SHOULD, lines 603-604
    // The response body SHOULD contain the service provider's representation of the newly created resource.
    @SpecRef("RFC7644-3.3-5bb6a7")
    def "RFC7644-3.3-5bb6a7: The response body SHOULD contain the service provider's representation of the newly created resource."() {
        given:
        String name = "res5bb6_${uniq()}@test.com"

        when:
        Response response = createUser(userName: name)

        then: "the body is the representation of the new resource"
        assert response.statusCode() == 201
        String body = response.body().asString()
        assert body?.trim() : "RFC 7644 3.3: 201 response body SHOULD contain the new resource"
        assert response.jsonPath().getString("id") : "RFC 7644 3.3: response body must contain the new resource id"
        assert response.jsonPath().getString("userName") == name : "RFC 7644 3.3: response body must contain the created userName"
        assert response.jsonPath().getList("schemas").contains(USER_SCHEMA) : "RFC 7644 3.3: response body must carry the resource schemas"
        Map stored = scimRequest().get("/Users/${response.jsonPath().getString('id')}").jsonPath().getMap("")
        assert stored.userName == name
    }

    // RFC 7644 section 3.3 (Creating Resources), MUST, lines 604-607
    // The URI of the created resource SHALL include, in the HTTP "Location" header and the HTTP body, a JSON representation [RFC7159] with the attribute "meta.location".
    @SpecRef("RFC7644-3.3-5035f4")
    def "RFC7644-3.3-5035f4: The URI of the created resource SHALL include, in the HTTP 'Location' header and the HTTP body, a JSON..."() {
        when:
        Response response = createUser(userName: "res5035_${uniq()}@test.com")

        then: "Location header and meta.location both carry the resource URI"
        assert response.statusCode() == 201
        String header = response.header("Location")
        String metaLocation = response.jsonPath().getString("meta.location")
        String id = response.jsonPath().getString("id")
        assert header : "RFC 7644 3.3: Location header must be present"
        assert metaLocation : "RFC 7644 3.3: meta.location must be present in the body"
        assert header == metaLocation : "RFC 7644 3.3: Location header '${header}' must equal meta.location '${metaLocation}'"
        assert header.endsWith("/Users/${id}") : "RFC 7644 3.3: Location '${header}' must be the URI of the created resource /Users/${id}"
        assert scimRequest().get("/Users/${id}").jsonPath().getString("meta.location") == metaLocation : "RFC 7644 3.3: meta.location must be stable"
    }

    // RFC 7644 section 3.3 (Creating Resources), MUST, lines 623-627
    // If the service provider determines that the creation of the requested resource conflicts with existing resources (e.g., a "User" resource with a duplicate "userName"), the service provider MUST return HTTP status code 409 (Conflict) with a "scimType" error code of "uniqueness", as per Section 3.12.
    @SpecRef("RFC7644-3.3-91c414")
    def "RFC7644-3.3-91c414: If the service provider determines that the creation of the requested resource conflicts with existing..."() {
        given:
        String name = "res91c4_${uniq()}@test.com"
        Response first = createUser(userName: name)
        assert first.statusCode() == 201

        when: "a second user with the same userName is created"
        Response second = createUser(userName: name)

        then:
        assert second.statusCode() == 409 : "RFC 7644 3.3: duplicate userName must return 409 but was ${second.statusCode()}"
        assertScimError(second, 409, "uniqueness")
    }

    // RFC 7644 section 3.3.1 (Resource Types), MUST, lines 711-713
    // When adding a resource to a specific endpoint, the meta attribute "resourceType" SHALL be set by the HTTP service provider to the corresponding resource type for the endpoint.
    @SpecRef("RFC7644-3.3.1-26f943")
    def "RFC7644-3.3.1-26f943: When adding a resource to a specific endpoint, the meta attribute 'resourceType' SHALL be set by the HTTP..."() {
        when:
        Response user = createUser(userName: "res26f9_${uniq()}@test.com")
        Response group = createGroup("res26f9_grp_${uniq()}")

        then:
        assert user.statusCode() == 201
        assert group.statusCode() == 201
        assert user.jsonPath().getString("meta.resourceType") == "User" : "RFC 7644 3.3.1: POST to /Users must set meta.resourceType to User but was ${user.jsonPath().getString('meta.resourceType')}"
        assert group.jsonPath().getString("meta.resourceType") == "Group" : "RFC 7644 3.3.1: POST to /Groups must set meta.resourceType to Group but was ${group.jsonPath().getString('meta.resourceType')}"
    }

    // RFC 7644 section 3.5 (Modifying Resources), MUST, lines 1607-1608
    // Implementers MUST support HTTP PUT as specified in Section 4.3 of [RFC7231].
    @SpecRef("RFC7644-3.5-8de339")
    def "RFC7644-3.5-8de339: Implementers MUST support HTTP PUT as specified in Section 4.3 of [RFC7231]."() {
        given:
        Response created = createUser(userName: "res8de3_${uniq()}@test.com")
        String id = created.jsonPath().getString("id")
        Map body = created.jsonPath().getMap("")
        body.displayName = "Put Supported"

        when:
        Response put = scimRequest().body(JsonOutput.toJson(body)).put("/Users/${id}")

        then:
        assert put.statusCode() == 200 : "RFC 7644 3.5: HTTP PUT must be supported (200) but was ${put.statusCode()}"
        assert put.jsonPath().getString("displayName") == "Put Supported" : "RFC 7644 3.5: PUT must modify the resource"
    }

    // RFC 7644 section 3.5 (Modifying Resources), SHOULD, lines 1608-1610
    // Resources such as Groups may be very large; hence, implementers SHOULD support HTTP PATCH [RFC5789] to enable partial resource modifications.
    @SpecRef("RFC7644-3.5-dff148")
    def "RFC7644-3.5-dff148: Resources such as Groups may be very large; hence, implementers SHOULD support HTTP PATCH [RFC5789] to..."() {
        given: "a group to modify partially"
        Response created = createGroup("res_dff1_${uniq()}")
        String id = created.jsonPath().getString("id")

        expect: "the server advertises PATCH and honors it"
        assert isPatchSupported() : "RFC 7644 3.5: implementers SHOULD support HTTP PATCH (ServiceProviderConfig patch.supported is not true)"
        String newName = "res_dff1_new_${uniq()}"
        Response patch = scimRequest()
            .body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "displayName", value: newName]])))
            .patch("/Groups/${id}")
        assertPatchSuccess(patch)
        assert scimRequest().get("/Groups/${id}").jsonPath().getString("displayName") == newName : "RFC 7644 3.5: PATCH must modify the resource"
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MAY, lines 1633-1635
    // For example, clients that have previously retrieved the entire resource in advance and revised it MAY replace the resource using an HTTP PUT.
    @SpecRef("RFC7644-3.5.1-d0aff3")
    def "RFC7644-3.5.1-d0aff3: For example, clients that have previously retrieved the entire resource in advance and revised it MAY..."() {
        given: "a user retrieved in full and revised"
        String id = createFullUser().jsonPath().getString("id")
        Map retrieved = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        retrieved.displayName = "Revised ${uniq()}"
        retrieved.nickName = "revised"

        when:
        Response put = scimRequest().body(JsonOutput.toJson(retrieved)).put("/Users/${id}")

        then:
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT of the retrieved and revised resource must return 200 but was ${put.statusCode()}"
        Map stored = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert stored.displayName == retrieved.displayName : "RFC 7644 3.5.1: revised displayName must be stored"
        assert stored.nickName == "revised" : "RFC 7644 3.5.1: revised nickName must be stored"
        assert stored.userName == retrieved.userName
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MUST NOT, lines 1635-1637
    // Because SCIM resource identifiers are assigned by the service provider, HTTP PUT MUST NOT be used to create new resources.
    @SpecRef("RFC7644-3.5.1-30e4f0")
    def "RFC7644-3.5.1-30e4f0: Because SCIM resource identifiers are assigned by the service provider, HTTP PUT MUST NOT be used to..."() {
        given: "an id that no group has"
        String fakeId = UUID.randomUUID().toString()
        String name = "res_30e4_${uniq()}"

        when: "PUT is used to create a group at a client-chosen URI"
        Response put = scimRequest()
            .body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], id: fakeId, displayName: name]))
            .put("/Groups/${fakeId}")
        if (put.statusCode() in [200, 201]) {
            String newId = put.jsonPath().getString("id")
            if (newId) createdGroupIds << newId
        }

        then: "nothing is created"
        assert put.statusCode() == 404 : "RFC 7644 3.5.1: PUT must not create a resource (expected 404) but was ${put.statusCode()}"
        assert scimRequest().get("/Groups/${fakeId}").statusCode() == 404 : "RFC 7644 3.5.1: group must not exist after PUT"
        Response search = scimRequest().queryParam("filter", "displayName eq \"${name}\"").get("/Groups")
        assert search.jsonPath().getInt("totalResults") == 0 : "RFC 7644 3.5.1: no group with the PUT displayName may exist"
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MAY, lines 1639-1640
    // As the operation's intent is to replace all attributes, SCIM clients MAY send all attributes, regardless of each attribute's mutability.
    @SpecRef("RFC7644-3.5.1-1cde8a")
    def "RFC7644-3.5.1-1cde8a: As the operation's intent is to replace all attributes, SCIM clients MAY send all attributes, regardless..."() {
        given: "a retrieved user with all attributes, including readOnly ones (id, meta, groups)"
        String id = createFullUser().jsonPath().getString("id")
        Map all = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert all.id && all.meta : "retrieved resource must include readOnly attributes"
        all.displayName = "All attrs ${uniq()}"

        when: "all attributes are sent back unchanged apart from displayName"
        Response put = scimRequest().body(JsonOutput.toJson(all)).put("/Users/${id}")

        then: "the server accepts them"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: sending all attributes regardless of mutability must be accepted (200) but was ${put.statusCode()}"
        assert put.jsonPath().getString("displayName") == all.displayName
        assert put.jsonPath().getString("id") == id
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MUST, lines 1644-1645
    // readWrite, writeOnly Any values provided SHALL replace the existing attribute values.
    @SpecRef("RFC7644-3.5.1-d5c54c")
    def "RFC7644-3.5.1-d5c54c: readWrite, writeOnly Any values provided SHALL replace the existing attribute values."() {
        given: "a user with values in readWrite attributes"
        Response created = createFullUser()
        String id = created.jsonPath().getString("id")
        Map body = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        String newDisplay = "Replaced ${uniq()}"
        body.displayName = newDisplay
        body.name = [givenName: "Replaced", familyName: "Values"]
        body.emails = [[value: "replaced_${uniq()}@test.com", type: "work", primary: true]]
        body.password = "Repl4ced!${uniq()}Pw"

        when:
        Response put = scimRequest().body(JsonOutput.toJson(body)).put("/Users/${id}")

        then: "all provided readWrite values replace the existing ones and the writeOnly value is accepted"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}"
        Map stored = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert stored.displayName == newDisplay : "RFC 7644 3.5.1: provided displayName must replace the existing value"
        assert stored.name.givenName == "Replaced" && stored.name.familyName == "Values" : "RFC 7644 3.5.1: provided name must replace the existing value"
        assert stored.emails*.value == body.emails*.value : "RFC 7644 3.5.1: provided emails must replace the existing values"
        assert !stored.containsKey("password") : "RFC 7643 7: writeOnly password must never be returned"
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MAY, lines 1647-1648
    // Attributes whose mutability is "readWrite" that are omitted from the request body MAY be assumed to be not asserted by the client.
    @SpecRef("RFC7644-3.5.1-302824")
    def "RFC7644-3.5.1-302824: Attributes whose mutability is 'readWrite' that are omitted from the request body MAY be assumed to be not..."() {
        given: "a user with a value in a readWrite attribute (title)"
        String id = createFullUser(title: "Original Title").jsonPath().getString("id")
        Map body = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert body.title == "Original Title"
        body.remove("title")

        when: "title is omitted from the PUT"
        Response put = scimRequest().body(JsonOutput.toJson(body)).put("/Users/${id}")

        then: "the omission is accepted and the result is consistent"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: omitted readWrite attribute must be accepted (200) but was ${put.statusCode()}"
        Map stored = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert stored.title == put.jsonPath().get("title") : "RFC 7644 3.5.1: PUT response and stored resource must agree on the non-asserted title"
        assert stored.userName == body.userName
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MAY, lines 1649-1651
    // The service provider MAY assume that any existing values are to be cleared, or the service provider MAY assign a default value to the final resource representation.
    @SpecRef("RFC7644-3.5.1-669b96")
    def "RFC7644-3.5.1-669b96: The service provider MAY assume that any existing values are to be cleared, or the service provider MAY..."() {
        given: "a user with a value in a readWrite attribute (title)"
        String id = createFullUser(title: "Original Title").jsonPath().getString("id")
        Map body = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        body.remove("title")

        when: "title is omitted from the PUT"
        Response put = scimRequest().body(JsonOutput.toJson(body)).put("/Users/${id}")

        then: "the existing value was cleared or replaced by a default, not kept"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}"
        Map stored = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert stored.title != "Original Title" :
            "RFC 7644 3.5.1: non-asserted title must be cleared or defaulted, but the existing value was kept"
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MAY, lines 1654-1657
    // Clients that want to override a server's defaults MAY specify "null" for a single-valued attribute, or an empty array "[]" for a multi-valued attribute, to clear all values.
    @SpecRef("RFC7644-3.5.1-ddbaf9")
    def "RFC7644-3.5.1-ddbaf9: Clients that want to override a server's defaults MAY specify 'null' for a single-valued attribute, or an..."() {
        given: "a user with values in a single-valued and a multi-valued attribute"
        String id = createFullUser(title: "To Clear").jsonPath().getString("id")
        Map body = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert body.title && body.emails
        body.title = null
        body.emails = []

        when:
        Response put = scimRequest().body(JsonOutput.toJson(body)).put("/Users/${id}")

        then:
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT with null / [] must return 200 but was ${put.statusCode()}"
        Map stored = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert stored.title == null : "RFC 7644 3.5.1: title set to null must be cleared but was ${stored.title}"
        assert !stored.emails : "RFC 7644 3.5.1: emails set to [] must be cleared but was ${stored.emails}"
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MUST, lines 1661-1663
    // If the service provider has no existing values, the new value(s) SHALL be applied.
    @SpecRef("RFC7644-3.5.1-56d7a1")
    def "RFC7644-3.5.1-56d7a1: immutable: If the service provider has no existing values, the new value(s) SHALL be applied."() {
        given: "a group without members (members.value is immutable in the server schema)"
        Response user = createUser(userName: "res56d7_${uniq()}@test.com")
        String userId = user.jsonPath().getString("id")
        Response group = createGroup("res_56d7_${uniq()}")
        String groupId = group.jsonPath().getString("id")
        assert !(scimRequest().get("/Groups/${groupId}").jsonPath().getList("members"))

        when: "PUT supplies members for the first time"
        Response put = scimRequest()
            .body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: group.jsonPath().getString("displayName"),
                                     members: [[value: userId]]]))
            .put("/Groups/${groupId}")

        then: "the new values are applied"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: PUT must return 200 but was ${put.statusCode()}"
        assert scimRequest().get("/Groups/${groupId}").jsonPath().getList("members.value") == [userId] :
            "RFC 7644 3.5.1: values supplied where none existed SHALL be applied"
    }

    // RFC 7644 section 3.5.1 (Replacing with PUT), MUST, lines 1665-1665
    // Any values provided SHALL be ignored.
    @SpecRef("RFC7644-3.5.1-b2f775")
    def "RFC7644-3.5.1-b2f775: readOnly: Any values provided SHALL be ignored."() {
        given:
        Response created = createUser(userName: "resb2f7_${uniq()}@test.com")
        String id = created.jsonPath().getString("id")
        String createdAt = created.jsonPath().getString("meta.created")
        Map body = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        body.id = UUID.randomUUID().toString()
        body.meta = [created: "2001-01-01T00:00:00Z", lastModified: "2001-01-01T00:00:00Z", resourceType: "Group"]
        body.groups = [[value: "client-group-id", display: "Fake"]]

        when:
        Response put = scimRequest().body(JsonOutput.toJson(body)).put("/Users/${id}")

        then: "readOnly values are ignored"
        assert put.statusCode() == 200 : "RFC 7644 3.5.1: readOnly values SHALL be ignored, so PUT must succeed (200) but was ${put.statusCode()}"
        Map stored = scimRequest().get("/Users/${id}").jsonPath().getMap("")
        assert stored.id == id : "RFC 7644 3.5.1: readOnly id must not change"
        assert stored.meta.created == createdAt : "RFC 7644 3.5.1: readOnly meta.created must not change"
        assert stored.meta.resourceType == "User" : "RFC 7644 3.5.1: readOnly meta.resourceType must not change"
        assert !(stored.groups*.value ?: []).contains("client-group-id") : "RFC 7644 3.5.1: readOnly groups must be ignored"
    }

    // RFC 7644 section 3.6 (Deleting Resources), MUST, lines 2642-2645
    // but MUST return a 404 (Not Found) error code for all operations associated with the previously deleted resource.
    @SpecRef("RFC7644-3.6-272626")
    def "RFC7644-3.6-272626: but MUST return a 404 (Not Found) error code for all operations associated with the previously deleted..."() {
        given:
        Response created = createUser(userName: "res2726_${uniq()}@test.com")
        String id = created.jsonPath().getString("id")
        Map body = created.jsonPath().getMap("")
        assert scimRequest().delete("/Users/${id}").statusCode() == 204

        expect: "every operation on the deleted resource returns 404"
        assert scimRequest().get("/Users/${id}").statusCode() == 404 : "RFC 7644 3.6: GET of a deleted resource must return 404"
        assert scimRequest().body(JsonOutput.toJson(body)).put("/Users/${id}").statusCode() == 404 : "RFC 7644 3.6: PUT of a deleted resource must return 404"
        assert scimRequest().delete("/Users/${id}").statusCode() == 404 : "RFC 7644 3.6: second DELETE must return 404"
        if (isPatchSupported()) {
            assert scimRequest()
                .body(JsonOutput.toJson(buildPatchOp([[op: "replace", path: "displayName", value: "x"]])))
                .patch("/Users/${id}").statusCode() == 404 : "RFC 7644 3.6: PATCH of a deleted resource must return 404"
        }
    }

    // RFC 7644 section 3.6 (Deleting Resources), MUST, lines 2645-2646
    // Service providers MUST omit the resource from future query results.
    @SpecRef("RFC7644-3.6-df3854")
    def "RFC7644-3.6-df3854: Service providers MUST omit the resource from future query results."() {
        given:
        String name = "resdf38_${uniq()}@test.com"
        Response created = createUser(userName: name)
        String id = created.jsonPath().getString("id")
        Response before = scimRequest().queryParam("filter", "userName eq \"${name}\"").get("/Users")
        assert before.jsonPath().getInt("totalResults") == 1 : "precondition: user is listed before delete"

        when:
        assert scimRequest().delete("/Users/${id}").statusCode() == 204

        then: "the deleted resource is omitted from query results"
        Response byName = scimRequest().queryParam("filter", "userName eq \"${name}\"").get("/Users")
        assert byName.statusCode() == 200
        assert byName.jsonPath().getInt("totalResults") == 0 : "RFC 7644 3.6: deleted user must not match a filter"
        assert !(byName.jsonPath().getList("Resources.id") ?: []).contains(id)
        Response byId = scimRequest().queryParam("filter", "id eq \"${id}\"").get("/Users")
        assert !(byId.jsonPath().getList("Resources.id") ?: []).contains(id) : "RFC 7644 3.6: deleted user must not match an id filter"
        Response prefix = scimRequest().queryParam("filter", "userName sw \"resdf38_\"").queryParam("count", 200).get("/Users")
        assert !(prefix.jsonPath().getList("Resources.id") ?: []).contains(id) : "RFC 7644 3.6: deleted user must not appear in list results"
    }

    // RFC 7644 section 3.6 (Deleting Resources), SHOULD NOT, lines 2646-2648
    // In addition, the service provider SHOULD NOT consider the deleted resource in conflict calculation.
    @SpecRef("RFC7644-3.6-1edaa3")
    def "RFC7644-3.6-1edaa3: In addition, the service provider SHOULD NOT consider the deleted resource in conflict calculation."() {
        given: "a deleted user with userName and externalId"
        String name = "res1eda_${uniq()}@test.com"
        String ext = "ext-${uniq()}"
        Response first = createUser(userName: name, externalId: ext)
        assert first.statusCode() == 201
        assert scimRequest().delete("/Users/${first.jsonPath().getString('id')}").statusCode() == 204

        when: "a user with the same identifying values is created"
        Response second = createUser(userName: name, externalId: ext)

        then: "the deleted resource is not considered in conflict calculation"
        assert second.statusCode() == 201 : "RFC 7644 3.6: deleted resource SHOULD NOT cause a conflict, but POST returned ${second.statusCode()}"
    }

    // RFC 7644 section 3.6 (Deleting Resources), SHOULD NOT, lines 2648-2651
    // For example, if a User resource is deleted, a CREATE request for a User resource with the same userName as the previously deleted resource SHOULD NOT fail with a 409 error due to userName conflict.
    @SpecRef("RFC7644-3.6-eef22e")
    def "RFC7644-3.6-eef22e: For example, if a User resource is deleted, a CREATE request for a User resource with the same userName as..."() {
        given: "a deleted user"
        String name = "reseef2_${uniq()}@test.com"
        Response first = createUser(userName: name)
        assert first.statusCode() == 201
        assert scimRequest().delete("/Users/${first.jsonPath().getString('id')}").statusCode() == 204

        when: "a user with the same userName is created"
        Response second = createUser(userName: name)

        then:
        assert second.statusCode() != 409 : "RFC 7644 3.6: CREATE after DELETE of the same userName SHOULD NOT fail with 409"
        assert second.statusCode() == 201 : "RFC 7644 3.6: CREATE with the previously deleted userName must succeed (201) but was ${second.statusCode()}"
    }

    // RFC 7644 section 3.6 (Deleting Resources), MUST, lines 2658-2659
    // In response to a successful DELETE, the server SHALL return a successful HTTP status code 204 (No Content).
    @SpecRef("RFC7644-3.6-63d00b")
    def "RFC7644-3.6-63d00b: In response to a successful DELETE, the server SHALL return a successful HTTP status code 204 (No Content)."() {
        given:
        String id = createUser(userName: "res63d0_${uniq()}@test.com").jsonPath().getString("id")

        when:
        Response delete = scimRequest().delete("/Users/${id}")

        then:
        assert delete.statusCode() == 204 : "RFC 7644 3.6: successful DELETE must return 204 (No Content) but was ${delete.statusCode()}"
        assert !delete.body().asString()?.trim() : "RFC 7644 3.6: 204 response must have no body"
    }
}
