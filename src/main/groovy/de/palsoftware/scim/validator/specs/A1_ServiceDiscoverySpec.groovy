package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.SpecRef

/**
 * Area 1 — Service Discovery
 *
 * Validates connectivity to the SCIM 2.0 server and tests the discovery endpoints:
 * - /ServiceProviderConfig (RFC 7643 §5)
 * - /ResourceTypes (RFC 7643 §6)
 * - /Schemas (RFC 7643 §7)
 */
class A1_ServiceDiscoverySpec extends ScimBaseSpec {

    // ─── ServiceProviderConfig ──────────────────────────────────────────────

    @SpecRef("RFC7644-4-R1")
    @SpecRef("RFC7643-5-R1")
    def "DSC_01: ServiceProviderConfig response is valid and contains required fields"() {
        // RFC 7643 §5 — ServiceProviderConfig
        when: "GET /ServiceProviderConfig"
        def response = scimRequest()
            .get("/ServiceProviderConfig")

        then: "Status is 200 and response contains required schema and configuration fields"
        response.statusCode() == 200

        def schemas = response.jsonPath().getList("schemas")
        schemas.contains(SPC_SCHEMA)

        // Verify patch config exists
        response.jsonPath().get("patch") != null
        response.jsonPath().get("patch.supported") != null

        // Verify bulk config exists
        response.jsonPath().get("bulk") != null
        response.jsonPath().get("bulk.supported") != null
        response.jsonPath().get("bulk.maxOperations") != null
        response.jsonPath().get("bulk.maxPayloadSize") != null

        // Verify filter config exists
        response.jsonPath().get("filter") != null
        response.jsonPath().get("filter.maxResults") != null

        // Verify etag config exists
        response.jsonPath().get("etag") != null

        // Verify changePassword config exists
        response.jsonPath().get("changePassword") != null

        // Verify sort config exists
        response.jsonPath().get("sort") != null

        and: "Verify authenticationSchemes is present and valid per RFC 7643 §5"
        def authSchemes = response.jsonPath().getList("authenticationSchemes")
        assert authSchemes != null && !authSchemes.isEmpty() : "authenticationSchemes is REQUIRED and must not be empty (RFC 7643 §5)"
        authSchemes.each { scheme ->
            assert scheme.name != null && !scheme.name.toString().isBlank() : "authenticationScheme name is required"
            assert scheme.description != null && !scheme.description.toString().isBlank() : "authenticationScheme description is required"
            assert scheme.type != null && !scheme.type.toString().isBlank() : "authenticationScheme type is required (RFC 7643 §5)"
        }

        and: "Verify meta attributes if present"
        def meta = response.jsonPath().getMap("meta")
        if (meta != null && meta.resourceType != null) {
            assert meta.resourceType == "ServiceProviderConfig" : "meta.resourceType must be ServiceProviderConfig"
        }

        and: "Log discovered capabilities"
        ScimOutput.println "=== SCIM Server Capabilities ==="
        ScimOutput.println "patch.supported    = ${response.jsonPath().getBoolean('patch.supported')}"
        ScimOutput.println "bulk.supported     = ${response.jsonPath().getBoolean('bulk.supported')}"
        ScimOutput.println "bulk.maxOperations = ${response.jsonPath().get('bulk.maxOperations')}"
        ScimOutput.println "bulk.maxPayloadSize= ${response.jsonPath().get('bulk.maxPayloadSize')}"
        ScimOutput.println "filter.maxResults  = ${response.jsonPath().get('filter.maxResults')}"
        ScimOutput.println "etag.supported     = ${response.jsonPath().getBoolean('etag.supported')}"
        ScimOutput.println "sort.supported     = ${response.jsonPath().getBoolean('sort.supported')}"
        ScimOutput.println "authSchemes        = ${authSchemes.collect { "${it.name} (${it.type})" }.join(', ')}"
        ScimOutput.println "================================"
    }

    // ─── ResourceTypes ──────────────────────────────────────────────────────

    @SpecRef("RFC7644-4-R1")
    @SpecRef("RFC7643-6-R1")
    def "DSC_02: ResourceTypes endpoint contains User and Group resource types"() {
        // RFC 7643 §6 — ResourceType
        when: "GET /ResourceTypes"
        def response = scimRequest()
            .get("/ResourceTypes")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Response contains User and Group resource types"
        def body = response.jsonPath()

        // Response must be a ListResponse envelope per RFC 7644 §4
        assert body.get("Resources") != null : "/ResourceTypes must return ListResponse envelope with 'Resources' attribute (RFC 7644 §4)"
        def resources = body.getList("Resources")
        def schemas = body.getList("schemas")
        assert schemas != null && schemas.contains(LIST_RESPONSE_SCHEMA) : "ListResponse must contain ListResponse schema URI"
        resources != null
        resources.size() >= 2

        // Find User resource type
        def userRT = resources.find { it.name == "User" || it.id == "User" }
        assert userRT != null : "User resource type must be present"
        assert userRT.name == "User" : "User ResourceType name must be 'User' (RFC 7643 §6)"
        assert userRT.schema == USER_SCHEMA : "User schema URI must be ${USER_SCHEMA}"
        assert userRT.endpoint in ["Users", "/Users"] || userRT.endpoint?.endsWith("/Users") :
            "User ResourceType endpoint must be 'Users', '/Users', or end with '/Users' (RFC 7643 §6, Erratum 8475)"

        // Find Group resource type
        def groupRT = resources.find { it.name == "Group" || it.id == "Group" }
        assert groupRT != null : "Group resource type must be present"
        assert groupRT.name == "Group" : "Group ResourceType name must be 'Group' (RFC 7643 §6)"
        assert groupRT.schema == GROUP_SCHEMA : "Group schema URI must be ${GROUP_SCHEMA}"
        assert groupRT.endpoint in ["Groups", "/Groups"] || groupRT.endpoint?.endsWith("/Groups") :
            "Group ResourceType endpoint must be 'Groups', '/Groups', or end with '/Groups' (RFC 7643 §6, Erratum 8475)"

        // Validate schemaExtensions if present
        if (userRT.schemaExtensions != null) {
            userRT.schemaExtensions.each { ext ->
                assert ext.schema != null && !ext.schema.toString().isBlank() : "schemaExtension must define a schema URI"
                assert ext.required != null : "schemaExtension must define a required boolean flag"
            }
        }

        // Validate meta.resourceType if present
        if (userRT.meta?.resourceType != null) {
            assert userRT.meta.resourceType == "ResourceType" : "ResourceType meta.resourceType must be 'ResourceType'"
        }
    }

    // ─── Schemas ────────────────────────────────────────────────────────────

    @SpecRef("RFC7644-4-R1")
    @SpecRef("RFC7643-7-R1")
    def "DSC_03: Schemas endpoint returns core User schema with correct attribute properties"() {
        // RFC 7643 §7 — Schemas endpoint
        when: "GET /Schemas"
        def response = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Response contains the core User schema in a ListResponse envelope"
        def body = response.jsonPath()

        assert body.get("Resources") != null : "/Schemas must return ListResponse envelope with 'Resources' attribute (RFC 7644 §4)"
        def schemas = body.getList("Resources")
        def topSchemas = body.getList("schemas")
        assert topSchemas != null && topSchemas.contains(LIST_RESPONSE_SCHEMA) : "ListResponse must contain ListResponse schema URI"
        schemas != null
        schemas.size() >= 1

        // Find User schema
        def userSchema = schemas.find { it.id == USER_SCHEMA }
        userSchema != null

        and: "userName attribute is required"
        def attributes = userSchema.attributes
        attributes != null
        def userNameAttr = attributes.find { it.name == "userName" }
        userNameAttr != null
        userNameAttr.required == true

        and: "id attribute has readOnly mutability (if present in attributes) or is a top-level schema property"
        def idAttr = attributes.find { it.name == "id" }
        if (idAttr != null) {
            assert idAttr.mutability == "readOnly" : "id attribute should be readOnly per RFC 7643"
        }
        userSchema.id == USER_SCHEMA
    }

    // ─── Schemas: Core Schemas Validation ───────────────────────────────────

    @SpecRef("RFC7643-7-R1")
    def "DSC_04: Schemas endpoint contains mandatory core schemas (ServiceProviderConfig, ResourceType, Schema)"() {
        // RFC 7643 §7 / §8.7.2 — Schema representations for ServiceProviderConfig, ResourceType, and Schema
        when: "GET /Schemas"
        def response = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Response contains the three core server schemas"
        def body = response.jsonPath()
        def schemas
        if (body.get("Resources") != null) {
            schemas = body.getList("Resources")
        } else {
            schemas = body.getList("")
        }
        schemas != null

        def schemaIds = schemas.collect { it.id ?: "" }
        def schemaNames = schemas.collect { it.name ?: "" }

        def hasSPC = schemaIds.contains(SPC_SCHEMA) ||
            schemaNames.any { it.toLowerCase().contains("serviceprovider") || it.toLowerCase().contains("service provider") }
        def hasRT = schemaIds.contains("urn:ietf:params:scim:schemas:core:2.0:ResourceType") ||
            schemaNames.any { it.toLowerCase().contains("resourcetype") || it.toLowerCase().contains("resource type") }
        def hasSchema = schemaIds.contains("urn:ietf:params:scim:schemas:core:2.0:Schema") ||
            schemaNames.any { it.toLowerCase() == "schema" }

        assert hasSPC : "ServiceProviderConfig schema definition missing from /Schemas (RFC 7643 §7)"
        assert hasRT : "ResourceType schema definition missing from /Schemas (RFC 7643 §7)"
        assert hasSchema : "Schema schema definition missing from /Schemas (RFC 7643 §7)"
    }

    // ─── Schemas: Individual Schema Retrieval ───────────────────────────────

    @SpecRef("RFC7644-4-R2")
    def "DSC_05: Each schema can be individually retrieved by its ID via /Schemas/{id}"() {
        // RFC 7644 §4 — Schemas can be retrieved individually by their URI ID
        when: "GET /Schemas to list all schemas"
        def listResponse = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        listResponse.statusCode() == 200

        when: "Retrieve each schema individually"
        def schemas
        if (listResponse.jsonPath().get("Resources") != null) {
            schemas = listResponse.jsonPath().getList("Resources")
        } else {
            schemas = listResponse.jsonPath().getList("")
        }

        then: "Each schema with an id can be fetched via /Schemas/{id}"
        schemas.each { schema ->
            if (schema.id) {
                def getResponse = scimRequestQuiet()
                    .get("/Schemas/${schema.id}")
                assert getResponse.statusCode() == 200 :
                    "Failed to retrieve schema '${schema.id}': HTTP ${getResponse.statusCode()}"
                assert getResponse.jsonPath().get("totalResults") == null && getResponse.jsonPath().get("Resources") == null :
                    "Direct schema retrieval '/Schemas/${schema.id}' must return a single object, not ListResponse (RFC 7644 §4)"
                assert getResponse.jsonPath().getString("id") == schema.id
            }
        }
    }

    // ─── Schemas: Invalid Schema ID ─────────────────────────────────────────

    @SpecRef("RFC7644-3.4.1-R2")
    @SpecRef("RFC7644-3.12-R1")
    def "DSC_06: GET /Schemas with non-existent schema ID returns 404"() {
        // RFC 7644 §3.12 — Error responses for non-existent resources
        when: "GET /Schemas/{random_uuid}"
        String invalidId = UUID.randomUUID().toString()
        def response = scimRequest()
            .get("/Schemas/${invalidId}")

        then: "Status is 404"
        response.statusCode() == 404
        assertScimError(response, 404)
    }

    // ─── ResourceTypes: Individual Retrieval ────────────────────────────────

    @SpecRef("RFC7644-4-R2")
    def "DSC_07: Each ResourceType can be individually retrieved by its ID via /ResourceTypes/{id}"() {
        // RFC 7644 §4 — ResourceTypes can be retrieved individually by their ID
        when: "GET /ResourceTypes to list all resource types"
        def listResponse = scimRequest()
            .get("/ResourceTypes")

        then: "Status is 200"
        listResponse.statusCode() == 200

        when: "Retrieve each ResourceType individually"
        def resources
        if (listResponse.jsonPath().get("Resources") != null) {
            resources = listResponse.jsonPath().getList("Resources")
        } else {
            resources = listResponse.jsonPath().getList("")
        }

        then: "Each ResourceType with an id can be fetched via /ResourceTypes/{id}"
        resources.each { rt ->
            def rtId = rt.id ?: rt.name
            if (rtId) {
                def getResponse = scimRequestQuiet()
                    .get("/ResourceTypes/${rtId}")
                assert getResponse.statusCode() == 200 :
                    "Failed to retrieve ResourceType '${rtId}': HTTP ${getResponse.statusCode()}"
                assert getResponse.jsonPath().get("totalResults") == null && getResponse.jsonPath().get("Resources") == null :
                    "Direct ResourceType retrieval '/ResourceTypes/${rtId}' must return a single object, not ListResponse (RFC 7644 §4)"
                assert getResponse.jsonPath().getString("id") == rtId || getResponse.jsonPath().getString("name") == rtId
            }
        }
    }

    // ─── ResourceTypes: Invalid ID ──────────────────────────────────────────

    @SpecRef("RFC7644-3.4.1-R2")
    @SpecRef("RFC7644-3.12-R1")
    def "DSC_08: GET /ResourceTypes with non-existent ID returns 404"() {
        // RFC 7644 §3.12 — Error responses for non-existent resources
        when: "GET /ResourceTypes/{random_uuid}"
        String invalidId = UUID.randomUUID().toString()
        def response = scimRequest()
            .get("/ResourceTypes/${invalidId}")

        then: "Status is 404"
        response.statusCode() == 404
        assertScimError(response, 404)
    }

    // ─── ResourceTypes: Schema Cross-Reference ──────────────────────────────

    @SpecRef("RFC7643-6-R1")
    def "DSC_09: ResourceType schema URIs reference schemas accessible via /Schemas/{id}"() {
        // RFC 7643 §6 — Each ResourceType's schema attribute MUST be the id of a Schema resource
        when: "GET /ResourceTypes"
        def rtResponse = scimRequest()
            .get("/ResourceTypes")

        then: "Status is 200"
        rtResponse.statusCode() == 200

        when: "Check each ResourceType's schema against /Schemas"
        def resources
        if (rtResponse.jsonPath().get("Resources") != null) {
            resources = rtResponse.jsonPath().getList("Resources")
        } else {
            resources = rtResponse.jsonPath().getList("")
        }

        then: "Each ResourceType's schema URI and extension schema URIs are accessible via /Schemas/{schemaUri}"
        resources.each { rt ->
            String schemaUri = rt.schema
            assert schemaUri != null && !schemaUri.isBlank() :
                "ResourceType '${rt.name}' must declare a non-empty schema URI (RFC 7643 §6)"
            def schemaResponse = scimRequestQuiet()
                .get("/Schemas/${schemaUri}")
            assert schemaResponse.statusCode() == 200 :
                "ResourceType '${rt.name}' references schema '${schemaUri}' which is not accessible: HTTP ${schemaResponse.statusCode()}"
            if (rt.schemaExtensions) {
                rt.schemaExtensions.each { ext ->
                    String extUri = ext.schema
                    assert extUri != null && !extUri.isBlank() :
                        "ResourceType '${rt.name}' schemaExtension must declare a non-empty schema URI (RFC 7643 §6)"
                    def extResponse = scimRequestQuiet()
                        .get("/Schemas/${extUri}")
                    assert extResponse.statusCode() == 200 :
                        "ResourceType '${rt.name}' references schema extension '${extUri}' which is not accessible: HTTP ${extResponse.statusCode()}"
                }
            }
        }
    }

    // ─── Discovery Endpoints: HTTP Method Enforcement ───────────────────────

    @SpecRef("RFC7644-4-R3")
    def "DSC_10: ServiceProviderConfig endpoint rejects POST, PUT, PATCH, DELETE"() {
        // RFC 7644 §4 — Discovery endpoints only support GET
        expect: "Non-GET methods return 405 Method Not Allowed (or 501 Not Implemented)"
        ["/ServiceProviderConfig"].each { endpoint ->
            ["POST", "PUT", "PATCH", "DELETE"].each { method ->
                def response
                switch (method) {
                    case "POST":
                        response = scimRequestQuiet().body("{}").post(endpoint)
                        break
                    case "PUT":
                        response = scimRequestQuiet().body("{}").put(endpoint)
                        break
                    case "PATCH":
                        response = scimRequestQuiet().body('{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[]}').patch(endpoint)
                        break
                    case "DELETE":
                        response = scimRequestQuiet().delete(endpoint)
                        break
                }
                int status = response.statusCode()
                assert status in [405, 501] :
                    "${method} ${endpoint} must be rejected with 405 Method Not Allowed or 501 Not Implemented (RFC 7644 §4 / RFC 9110 §15.5.6), got ${status}"
                if (status == 405) {
                    def allowValues = response.headers().getValues("Allow")
                    String allowHeader = (allowValues != null && !allowValues.isEmpty()) ? allowValues.join(", ") : null
                    assert allowHeader != null && allowHeader.toUpperCase().contains("GET") :
                        "HTTP 405 on ${method} ${endpoint} must include Allow header listing GET (RFC 7644 §4 / RFC 9110 §15.5.6), got '${allowHeader}'"
                }
            }
        }
    }

    @SpecRef("RFC7644-4-R3")
    def "DSC_11: Schemas endpoint and individual schemas reject POST, PUT, PATCH, DELETE"() {
        // RFC 7644 §4 — Discovery endpoints only support GET
        // DELETE on individual schema uses a synthetic non-existent schema ID to avoid destructive mutation on non-conforming servers
        expect: "Non-GET methods return 405 Method Not Allowed (or 501 Not Implemented)"
        String syntheticSchemaEndpoint = "/Schemas/urn:ietf:params:scim:schemas:core:2.0:NonExistent"
        ["/Schemas", "/Schemas/${USER_SCHEMA}"].each { baseEndpoint ->
            ["POST", "PUT", "PATCH", "DELETE"].each { method ->
                String endpoint = (method == "DELETE" && baseEndpoint != "/Schemas") ? syntheticSchemaEndpoint : baseEndpoint
                def response
                switch (method) {
                    case "POST":
                        response = scimRequestQuiet().body("{}").post(endpoint)
                        break
                    case "PUT":
                        response = scimRequestQuiet().body("{}").put(endpoint)
                        break
                    case "PATCH":
                        response = scimRequestQuiet().body('{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[]}').patch(endpoint)
                        break
                    case "DELETE":
                        response = scimRequestQuiet().delete(endpoint)
                        break
                }
                int status = response.statusCode()
                List<Integer> allowedStatuses = (endpoint == syntheticSchemaEndpoint) ? [404, 405, 501] : [405, 501]
                assert status in allowedStatuses :
                    "${method} ${endpoint} must be rejected with ${allowedStatuses.join('/')} (RFC 7644 §4 / RFC 9110 §15.5.6), got ${status}"
                if (status == 405) {
                    def allowValues = response.headers().getValues("Allow")
                    String allowHeader = (allowValues != null && !allowValues.isEmpty()) ? allowValues.join(", ") : null
                    assert allowHeader != null && allowHeader.toUpperCase().contains("GET") :
                        "HTTP 405 on ${method} ${endpoint} must include Allow header listing GET (RFC 7644 §4 / RFC 9110 §15.5.6), got '${allowHeader}'"
                }
            }
        }
    }

    @SpecRef("RFC7644-4-R3")
    def "DSC_12: ResourceTypes endpoint and individual ResourceTypes reject POST, PUT, PATCH, DELETE"() {
        // RFC 7644 §4 — Discovery endpoints only support GET
        // DELETE on individual ResourceType uses a synthetic non-existent ID to avoid destructive mutation on non-conforming servers
        expect: "Non-GET methods return 405 Method Not Allowed (or 501 Not Implemented)"
        String syntheticRtEndpoint = "/ResourceTypes/NonExistent"
        ["/ResourceTypes", "/ResourceTypes/User"].each { baseEndpoint ->
            ["POST", "PUT", "PATCH", "DELETE"].each { method ->
                String endpoint = (method == "DELETE" && baseEndpoint != "/ResourceTypes") ? syntheticRtEndpoint : baseEndpoint
                def response
                switch (method) {
                    case "POST":
                        response = scimRequestQuiet().body("{}").post(endpoint)
                        break
                    case "PUT":
                        response = scimRequestQuiet().body("{}").put(endpoint)
                        break
                    case "PATCH":
                        response = scimRequestQuiet().body('{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[]}').patch(endpoint)
                        break
                    case "DELETE":
                        response = scimRequestQuiet().delete(endpoint)
                        break
                }
                int status = response.statusCode()
                List<Integer> allowedStatuses = (endpoint == syntheticRtEndpoint) ? [404, 405, 501] : [405, 501]
                assert status in allowedStatuses :
                    "${method} ${endpoint} must be rejected with ${allowedStatuses.join('/')} (RFC 7644 §4 / RFC 9110 §15.5.6), got ${status}"
                if (status == 405) {
                    def allowValues = response.headers().getValues("Allow")
                    String allowHeader = (allowValues != null && !allowValues.isEmpty()) ? allowValues.join(", ") : null
                    assert allowHeader != null && allowHeader.toUpperCase().contains("GET") :
                        "HTTP 405 on ${method} ${endpoint} must include Allow header listing GET (RFC 7644 §4 / RFC 9110 §15.5.6), got '${allowHeader}'"
                }
            }
        }
    }

    // ─── Gap Closures: RFC 7644 §3.1, §4, RFC 7643 §5, §6, §7 ─────────────────

    @SpecRef("RFC7644-3.1-R1")
    def "DSC_13: Message schemas SHALL NOT be discoverable using the /Schemas endpoint"() {
        // RFC 7644 §3.1 — SCIM message schemas SHALL NOT be discoverable using the '/Schemas' endpoint
        when: "GET /Schemas"
        def response = scimRequest().get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "No message schema URNs appear in the response"
        def body = response.jsonPath()
        def schemas = body.get("Resources") != null ? body.getList("Resources") : body.getList("")
        def schemaIds = schemas.collect { it.id?.toString()?.toLowerCase() }

        def forbiddenMessageSchemas = [
            "urn:ietf:params:scim:api:messages:2.0:listresponse",
            "urn:ietf:params:scim:api:messages:2.0:patchop",
            "urn:ietf:params:scim:api:messages:2.0:bulkrequest",
            "urn:ietf:params:scim:api:messages:2.0:bulkresponse",
            "urn:ietf:params:scim:api:messages:2.0:error",
            "urn:ietf:params:scim:api:messages:2.0:searchrequest"
        ]

        forbiddenMessageSchemas.each { messageUrn ->
            assert !schemaIds.contains(messageUrn) :
                "RFC 7644 §3.1 violation: Message schema '${messageUrn}' is discoverable at /Schemas"
        }
    }

    @SpecRef("RFC7644-4-R4")
    def "DSC_14: Discovery endpoints SHOULD be accessible without authentication"() {
        // RFC 7644 §4 — Discovery endpoints SHOULD be accessible without authentication
        when: "Anonymous GET to /ServiceProviderConfig, /ResourceTypes, /Schemas"
        def spcResp = scimRequestAnonymous().get("/ServiceProviderConfig")
        def rtResp = scimRequestAnonymous().get("/ResourceTypes")
        def schemasResp = scimRequestAnonymous().get("/Schemas")

        then: "Anonymous GET to /ServiceProviderConfig returns 200 OK with authenticationSchemes or 401 (RFC 7643 §5 / RFC 7644 §4)"
        assert spcResp.statusCode() in [200, 401] : "Expected HTTP 200 or 401 on anonymous discovery access for /ServiceProviderConfig, got ${spcResp.statusCode()}"
        if (spcResp.statusCode() == 200) {
            def authSchemes = spcResp.jsonPath().getList("authenticationSchemes")
            assert authSchemes != null && !authSchemes.isEmpty() : "Anonymous /ServiceProviderConfig must expose non-empty authenticationSchemes (RFC 7643 §5)"
        } else {
            assert spcResp.header("WWW-Authenticate") != null || spcResp.body()?.asString()?.contains(ERROR_SCHEMA) : "HTTP 401 must include WWW-Authenticate header or SCIM error response"
        }

        and: "/ResourceTypes and /Schemas return 200, 401, or 403"
        [
            ["/ResourceTypes", rtResp],
            ["/Schemas", schemasResp]
        ].each { path, resp ->
            assert resp.statusCode() in [200, 401, 403] : "Expected HTTP 200, 401, or 403 on anonymous discovery access for ${path}, got ${resp.statusCode()}"
        }
    }

    @SpecRef("RFC7644-4-R2")
    def "DSC_15: Direct retrieval of /Schemas/{id} and /ResourceTypes/{id} returns single JSON object"() {
        // RFC 7644 §4 — Request for specific ResourceType or Schema returns single JSON object as per §3.4.1
        when: "Discover ResourceType ID and GET specific schema and ResourceType"
        def allRtResp = scimRequest().get("/ResourceTypes")
        def userRtId = "User"
        if (allRtResp.statusCode() == 200) {
            def list = allRtResp.jsonPath().getList("Resources")
            def userRt = list?.find { it.name == "User" || it.id == "User" }
            if (userRt != null) {
                userRtId = userRt.id ?: userRt.name ?: "User"
            }
        }
        def schemaResp = scimRequest().get("/Schemas/${USER_SCHEMA}")
        def rtResp = scimRequest().get("/ResourceTypes/${userRtId}")

        then: "Responses are 200 and return single JSON objects without ListResponse wrapper"
        schemaResp.statusCode() == 200
        assert schemaResp.jsonPath().get("totalResults") == null :
            "Direct schema retrieval must not return ListResponse wrapper (RFC 7644 §4)"
        assert schemaResp.jsonPath().get("Resources") == null :
            "Direct schema retrieval must not include Resources wrapper (RFC 7644 §4)"
        assert schemaResp.jsonPath().getString("id") == USER_SCHEMA

        rtResp.statusCode() == 200
        assert rtResp.jsonPath().get("totalResults") == null :
            "Direct ResourceType retrieval must not return ListResponse wrapper (RFC 7644 §4)"
        assert rtResp.jsonPath().get("Resources") == null :
            "Direct ResourceType retrieval must not include Resources wrapper (RFC 7644 §4)"
        assert rtResp.jsonPath().getString("name") == "User" || rtResp.jsonPath().getString("id") == userRtId
    }

    @SpecRef("RFC7643-7-R1")
    @SpecRef("RFC7643-3-R1")
    def "DSC_16: Schema representations include schemas and meta per erratum 8417"() {
        // RFC 7643 erratum 8417 (Verified): Schema representations must include schemas and meta
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Response is 200 and contains schemas and meta"
        response.statusCode() == 200
        def schemas = response.jsonPath().getList("schemas")
        assert schemas != null && !schemas.isEmpty() :
            "Schema representation must include schemas attribute (RFC 7643 §7, Erratum 8417)"
        assert schemas.contains("urn:ietf:params:scim:schemas:core:2.0:Schema") :
            "Schema representation schemas array must contain 'urn:ietf:params:scim:schemas:core:2.0:Schema' (RFC 7643 §7, Erratum 8417)"
        assert schemas.size() == schemas.unique(false).size() :
            "Schema representation schemas array must not contain duplicate URIs (RFC 7643 §3)"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null && !attributes.isEmpty() :
            "Schema representation must include non-empty attributes array (RFC 7643 §7)"

        def meta = response.jsonPath().getMap("meta")
        assert meta != null : "Schema representation must include meta object per RFC 7643 Erratum 8417"
        assert meta.resourceType == "Schema" : "meta.resourceType must be 'Schema'"
    }

    @SpecRef("RFC7643-6-R1")
    def "DSC_17: ResourceType representations have server-unique name and endpoint per erratum 8475"() {
        // RFC 7643 §6, Erratum 8475 (Verified): ResourceType name and endpoint are server-unique and case-exact
        when: "GET /ResourceTypes"
        def response = scimRequest().get("/ResourceTypes")

        then: "Response is 200"
        response.statusCode() == 200

        and: "All ResourceType names and endpoints are present and unique across the server"
        def body = response.jsonPath()
        def resources = body.get("Resources") != null ? body.getList("Resources") : body.getList("")
        resources.each { rt ->
            assert rt.name != null && !rt.name.toString().isBlank() : "Every ResourceType must have a non-empty name (RFC 7643 §6)"
            assert rt.endpoint != null && !rt.endpoint.toString().isBlank() : "Every ResourceType must have a non-empty endpoint (RFC 7643 §6)"
        }
        def names = resources.collect { it.name }
        def endpoints = resources.collect { it.endpoint }

        assert names.size() == names.unique(false).size() :
            "ResourceType names must be server-unique per RFC 7643 Erratum 8475"
        assert endpoints.size() == endpoints.unique(false).size() :
            "ResourceType endpoints must be server-unique per RFC 7643 Erratum 8475"
    }

    @SpecRef("RFC7643-5-R1")
    def "DSC_18: ServiceProviderConfig attributes have mutability readOnly and declare required capability flags"() {
        // RFC 7643 §5 — all attributes have mutability readOnly and declare capability flags
        when: "GET /ServiceProviderConfig and /Schemas"
        def response = scimRequest().get("/ServiceProviderConfig")
        def spcSchemaResp = scimRequestQuiet().get("/Schemas/${SPC_SCHEMA}")
        def userSchemaResp = scimRequestQuiet().get("/Schemas/${USER_SCHEMA}")

        then: "Response is 200"
        response.statusCode() == 200

        and: "All required capability objects, supported booleans, and limit integers are present"
        def json = response.jsonPath()
        assert json.get("patch.supported") instanceof Boolean : "patch.supported boolean is required in SPC (RFC 7643 §5)"
        assert json.get("bulk.supported") instanceof Boolean : "bulk.supported boolean is required in SPC (RFC 7643 §5)"
        assert json.get("bulk.maxOperations") instanceof Integer : "bulk.maxOperations integer is required in SPC (RFC 7643 §5)"
        assert json.get("bulk.maxPayloadSize") instanceof Integer : "bulk.maxPayloadSize integer is required in SPC (RFC 7643 §5)"
        assert json.get("filter.supported") instanceof Boolean : "filter.supported boolean is required in SPC (RFC 7643 §5)"
        assert json.get("filter.maxResults") instanceof Integer : "filter.maxResults integer is required in SPC (RFC 7643 §5)"
        assert json.get("changePassword.supported") instanceof Boolean : "changePassword.supported boolean is required in SPC (RFC 7643 §5)"
        assert json.get("sort.supported") instanceof Boolean : "sort.supported boolean is required in SPC (RFC 7643 §5)"
        assert json.get("etag.supported") instanceof Boolean : "etag.supported boolean is required in SPC (RFC 7643 §5)"

        and: "ServiceProviderConfig schema attributes declare readOnly mutability and User schema attributes declare core metadata properties"
        if (spcSchemaResp.statusCode() == 200) {
            def spcAttrs = spcSchemaResp.jsonPath().getList("attributes")
            assert spcAttrs != null && !spcAttrs.isEmpty() : "ServiceProviderConfig schema must declare attributes"
            spcAttrs.each { attr ->
                assert attr.type != null : "ServiceProviderConfig attribute '${attr.name}' must declare type"
                assert attr.multiValued != null : "ServiceProviderConfig attribute '${attr.name}' must declare multiValued"
                assert (attr.mutability ?: "readOnly") == "readOnly" :
                    "ServiceProviderConfig attribute '${attr.name}' must have mutability readOnly (RFC 7643 §5), got '${attr.mutability}'"
            }
        }
        assert userSchemaResp.statusCode() == 200
        def userAttrs = userSchemaResp.jsonPath().getList("attributes")
        assert userAttrs != null && !userAttrs.isEmpty() : "User schema must declare attributes"
        userAttrs.each { attr ->
            assert attr.type != null : "User schema attribute '${attr.name}' must declare type"
            assert attr.mutability != null : "User schema attribute '${attr.name}' must declare mutability"
            assert attr.multiValued != null : "User schema attribute '${attr.name}' must declare multiValued"
        }
    }

    // ─── Discovery Endpoints: Reject (403) or Ignore (200) Filter Parameter ─

    @SpecRef("RFC7644-2-R2")
    def "DSC_19: discovery endpoints reject (403) or ignore (200) filter query parameter"() {
        // RFC 7644 §4 — If a 'filter' is provided, the service provider SHOULD respond with HTTP status code 403 (Forbidden)
        // Alternatively, service provider may ignore query parameters and return HTTP status code 200 (OK)
        when: "GET discovery endpoints with filter query parameter"
        def spcResp = scimRequest().get("/ServiceProviderConfig?filter=userName+eq+%22test%22")
        def rtResp = scimRequest().get("/ResourceTypes?filter=name+eq+%22User%22")
        def schemasResp = scimRequest().get("/Schemas?filter=id+eq+%22urn:ietf:params:scim:schemas:core:2.0:User%22")

        then: "All discovery endpoints respond with 403 Forbidden or 200 OK (RFC 7644 §4)"
        [
            ["/ServiceProviderConfig", spcResp],
            ["/ResourceTypes", rtResp],
            ["/Schemas", schemasResp]
        ].each { String path, def response ->
            assert response.statusCode() in [200, 403] : "GET ${path} with filter must return 200 OK or 403 Forbidden (RFC 7644 §4), got ${response.statusCode()}"
            if (response.statusCode() == 403) {
                assertScimError(response, 403)
            } else {
                assert response.jsonPath().getList("schemas") != null && !response.jsonPath().getList("schemas").isEmpty() :
                    "GET ${path} ignoring filter (200 OK) must return a valid SCIM discovery response with non-empty schemas (RFC 7644 §4)"
            }
        }
    }
}
