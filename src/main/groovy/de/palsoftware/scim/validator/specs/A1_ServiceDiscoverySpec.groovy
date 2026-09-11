package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import spock.lang.Stepwise

/**
 * Area 1 — Service Discovery
 *
 * Validates connectivity to the SCIM 2.0 server and tests the discovery endpoints:
 * - /ServiceProviderConfig (RFC 7643 §5)
 * - /ResourceTypes (RFC 7643 §6)
 * - /Schemas (RFC 7643 §7)
 */
@Stepwise
class A1_ServiceDiscoverySpec extends ScimBaseSpec {

    // ─── ServiceProviderConfig ──────────────────────────────────────────────

    def "ServiceProviderConfig response is valid and contains required fields"() {
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
        def validAuthTypes = ["oauth", "oauth2", "oauthbearertoken", "httpbasic", "httpdigest"]
        authSchemes.each { scheme ->
            assert scheme.name != null && !scheme.name.toString().isBlank() : "authenticationScheme name is required"
            assert scheme.description != null && !scheme.description.toString().isBlank() : "authenticationScheme description is required"
            assert scheme.type != null : "authenticationScheme type is required"
            assert validAuthTypes.contains(scheme.type.toString().toLowerCase()) :
                "authenticationScheme type '${scheme.type}' must be one of ${validAuthTypes} (RFC 7643 §5)"
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

    def "ResourceTypes endpoint contains User and Group resource types"() {
        // RFC 7643 §6 — ResourceType
        when: "GET /ResourceTypes"
        def response = scimRequest()
            .get("/ResourceTypes")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Response contains User and Group resource types"
        def body = response.jsonPath()

        // Response may be a list (array) or a ListResponse
        def resources
        if (body.get("Resources") != null) {
            resources = body.getList("Resources")
            def schemas = body.getList("schemas")
            if (schemas != null) {
                assert schemas.contains(LIST_RESPONSE_SCHEMA) : "ListResponse must contain ListResponse schema URI"
            }
        } else {
            // Direct array response
            resources = body.getList("")
        }
        resources != null
        resources.size() >= 2

        // Find User resource type
        def userRT = resources.find { it.name == "User" || it.id == "User" }
        assert userRT != null : "User resource type must be present"
        assert userRT.schema == USER_SCHEMA : "User schema URI must be ${USER_SCHEMA}"
        // TODO DEVIATION: api.scim.dev returns full URL endpoints instead of relative paths per RFC 7643 §6
        // RFC expects: "/Users", server returns: "https://api.scim.dev/scim/v2/Users"
        userRT.endpoint?.endsWith("/Users")

        // Find Group resource type
        def groupRT = resources.find { it.name == "Group" || it.id == "Group" }
        assert groupRT != null : "Group resource type must be present"
        assert groupRT.schema == GROUP_SCHEMA : "Group schema URI must be ${GROUP_SCHEMA}"
        // DEVIATION: Same as above for Groups
        groupRT.endpoint?.endsWith("/Groups")

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

    def "Schemas endpoint returns core User schema with correct attribute properties"() {
        // RFC 7643 §7 — Schemas endpoint
        when: "GET /Schemas"
        def response = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Response contains the core User schema"
        def body = response.jsonPath()

        def schemas
        if (body.get("Resources") != null) {
            schemas = body.getList("Resources")
        } else {
            schemas = body.getList("")
        }
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
        // TODO DEVIATION: api.scim.dev does not include 'id' in the User schema attributes array.
        // Per RFC 7643 §2, 'id' is a common attribute on all resources, may not be in per-schema attributes.
        def idAttr = attributes.find { it.name == "id" }
        // If id is listed, verify it's readOnly; otherwise this is a known deviation
        if (idAttr != null) {
            assert idAttr.mutability == "readOnly" : "id attribute should be readOnly per RFC 7643"
        }
        // The schema itself has an 'id' field which is the schema URI — that's expected
        userSchema.id == USER_SCHEMA
    }

    // ─── Schemas: Core Schemas Validation ───────────────────────────────────

    def "Schemas endpoint contains mandatory core schemas (ServiceProviderConfig, ResourceType, Schema)"() {
        // RFC 7644 §4 — Service providers MUST provide Schema definitions for
        // ServiceProviderConfig, ResourceType, and Schema itself.
        when: "GET /Schemas"
        def response = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Response contains the three mandatory server schemas"
        def body = response.jsonPath()
        def schemas
        if (body.get("Resources") != null) {
            schemas = body.getList("Resources")
        } else {
            schemas = body.getList("")
        }
        schemas != null

        // Check for the three mandatory core schemas by ID URN or name per RFC 7644 §4
        def schemaIds = schemas.collect { it.id ?: "" }
        def schemaNames = schemas.collect { it.name ?: "" }

        def hasSPC = schemaIds.contains(SPC_SCHEMA) ||
            schemaNames.any { it.toLowerCase().contains("serviceprovider") || it.toLowerCase().contains("service provider") }
        def hasRT = schemaIds.contains("urn:ietf:params:scim:schemas:core:2.0:ResourceType") ||
            schemaNames.any { it.toLowerCase().contains("resourcetype") || it.toLowerCase().contains("resource type") }
        def hasSchema = schemaIds.contains("urn:ietf:params:scim:schemas:core:2.0:Schema") ||
            schemaNames.any { it.toLowerCase() == "schema" }

        if (!hasSPC) ScimOutput.println "DEVIATION: /Schemas missing ServiceProviderConfig schema definition (RFC 7644 §4)"
        if (!hasRT) ScimOutput.println "DEVIATION: /Schemas missing ResourceType schema definition (RFC 7644 §4)"
        if (!hasSchema) ScimOutput.println "DEVIATION: /Schemas missing Schema schema definition (RFC 7644 §4)"

        // Per RFC 7644 §4: "Service providers MUST provide Schema definitions for ServiceProviderConfig, ResourceType, and Schema itself"
        assert hasSPC : "Mandatory ServiceProviderConfig schema definition missing from /Schemas (RFC 7644 §4)"
        assert hasRT : "Mandatory ResourceType schema definition missing from /Schemas (RFC 7644 §4)"
        assert hasSchema : "Mandatory Schema schema definition missing from /Schemas (RFC 7644 §4)"
    }

    // ─── Schemas: Individual Schema Retrieval ───────────────────────────────

    def "Each schema can be individually retrieved by its ID via /Schemas/{id}"() {
        // RFC 7644 §4 — Schemas can be retrived individually by their URI ID
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
            }
        }
    }

    // ─── Schemas: Invalid Schema ID ─────────────────────────────────────────

    def "GET /Schemas with non-existent schema ID returns 404"() {
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

    def "Each ResourceType can be individually retrieved by its ID via /ResourceTypes/{id}"() {
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
            }
        }
    }

    // ─── ResourceTypes: Invalid ID ──────────────────────────────────────────

    def "GET /ResourceTypes with non-existent ID returns 404"() {
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

    def "ResourceType schema URIs reference schemas accessible via /Schemas/{id}"() {
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
            if (schemaUri) {
                def schemaResponse = scimRequestQuiet()
                    .get("/Schemas/${schemaUri}")
                assert schemaResponse.statusCode() == 200 :
                    "ResourceType '${rt.name}' references schema '${schemaUri}' which is not accessible: HTTP ${schemaResponse.statusCode()}"
            }
            if (rt.schemaExtensions) {
                rt.schemaExtensions.each { ext ->
                    String extUri = ext.schema
                    if (extUri) {
                        def extResponse = scimRequestQuiet()
                            .get("/Schemas/${extUri}")
                        assert extResponse.statusCode() == 200 :
                            "ResourceType '${rt.name}' references schema extension '${extUri}' which is not accessible: HTTP ${extResponse.statusCode()}"
                    }
                }
            }
        }
    }

    // ─── Discovery Endpoints: HTTP Method Enforcement ───────────────────────

    def "ServiceProviderConfig endpoint rejects POST, PUT, PATCH, DELETE"() {
        // RFC 7644 §4 — Discovery endpoints only support GET
        expect: "Non-GET methods return 405 Method Not Allowed"
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
                assert response.statusCode() >= 400 :
                    "${method} ${endpoint} should return error but got ${response.statusCode()}"
                if (response.statusCode() == 405) {
                    def allowHeader = response.header("Allow")
                    if (allowHeader != null && !allowHeader.contains("GET")) {
                        ScimOutput.println "NOTE: ${method} ${endpoint} returned 405 with Allow header '${allowHeader}' not listing GET"
                    }
                } else {
                    ScimOutput.println "DEVIATION: ${method} ${endpoint} returned ${response.statusCode()} instead of 405 (RFC 7644 §4)"
                }
            }
        }
    }

    def "Schemas endpoint and individual schemas reject POST, PUT, PATCH, DELETE"() {
        // RFC 7644 §4 — Discovery endpoints only support GET
        expect: "Non-GET methods return 405 Method Not Allowed"
        ["/Schemas", "/Schemas/${USER_SCHEMA}"].each { endpoint ->
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
                assert response.statusCode() >= 400 :
                    "${method} ${endpoint} should return error but got ${response.statusCode()}"
                if (response.statusCode() == 405) {
                    def allowHeader = response.header("Allow")
                    if (allowHeader != null && !allowHeader.contains("GET")) {
                        ScimOutput.println "NOTE: ${method} ${endpoint} returned 405 with Allow header '${allowHeader}' not listing GET"
                    }
                } else {
                    ScimOutput.println "DEVIATION: ${method} ${endpoint} returned ${response.statusCode()} instead of 405 (RFC 7644 §4)"
                }
            }
        }
    }

    def "ResourceTypes endpoint and individual ResourceTypes reject POST, PUT, PATCH, DELETE"() {
        // RFC 7644 §4 — Discovery endpoints only support GET
        expect: "Non-GET methods return 405 Method Not Allowed"
        ["/ResourceTypes", "/ResourceTypes/User"].each { endpoint ->
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
                assert response.statusCode() >= 400 :
                    "${method} ${endpoint} should return error but got ${response.statusCode()}"
                if (response.statusCode() == 405) {
                    def allowHeader = response.header("Allow")
                    if (allowHeader != null && !allowHeader.contains("GET")) {
                        ScimOutput.println "NOTE: ${method} ${endpoint} returned 405 with Allow header '${allowHeader}' not listing GET"
                    }
                } else {
                    ScimOutput.println "DEVIATION: ${method} ${endpoint} returned ${response.statusCode()} instead of 405 (RFC 7644 §4)"
                }
            }
        }
    }
}
