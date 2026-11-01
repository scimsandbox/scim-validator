package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.SpecRef
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * Area 2 — Schema Validation Deep Dive
 *
 * Validates the server's advertised schema definitions match RFC 7643.
 * Checks core User schema attributes, Enterprise User extension, and attribute
 * mutability declarations.
 */
class A2_SchemaValidationSpec extends ScimBaseSpec {

    private static final Set<String> VALID_TYPES = [
        "string", "boolean", "decimal", "integer", "dateTime", "binary", "reference", "complex"
    ] as Set
    private static final Set<String> VALID_MUTABILITIES = [
        "readOnly", "readWrite", "immutable", "writeOnly"
    ] as Set
    private static final Set<String> VALID_RETURNED = [
        "always", "never", "default", "request"
    ] as Set
    private static final Set<String> VALID_UNIQUENESS = [
        "none", "server", "global"
    ] as Set

    // ─── Core User Schema Attributes ────────────────────────────────────────

    @SpecRef("RFC7643-4.1.1-R1")
    @SpecRef("RFC7643-4.1.1-R3")
    @SpecRef("RFC7643-4.1.1-R4")
    @SpecRef("RFC7643-4.1.1-R5")
    def "SCH_01: Core User schema has correct attribute definitions per RFC 7643"() {
        // RFC 7643 §4.1.1 — User schema attributes
        when: "GET the User schema definition"
        def response = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Schema id matches core User schema URI"
        response.jsonPath().getString("id") == USER_SCHEMA

        and: "userName attribute exists, is required, and is case-insensitive"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null
        def userNameAttr = attributes.find { it.name == "userName" }
        userNameAttr != null
        userNameAttr.required == true
        userNameAttr.type == "string"
        userNameAttr.mutability == "readWrite"
        (userNameAttr.caseExact ?: false) == false
        userNameAttr.uniqueness in ["server", "global"]

        and: "name attribute is a complex type with standard sub-attributes"
        def nameAttr = attributes.find { it.name == "name" }
        nameAttr != null
        nameAttr.type == "complex"
        (nameAttr.multiValued ?: false) == false

        def subAttrs = nameAttr.subAttributes
        assert subAttrs != null && !subAttrs.isEmpty() : "name.subAttributes must not be empty (RFC 7643 §4.1.1)"
        def validSubAttrNames = ["formatted", "familyName", "givenName", "middleName", "honorificPrefix", "honorificSuffix"]
        assert subAttrs.any { it.name in validSubAttrNames } : "name must contain at least one valid RFC 7643 §4.1.1 sub-attribute"
        ["formatted", "familyName", "givenName", "middleName", "honorificPrefix", "honorificSuffix"].each { subName ->
            def sub = subAttrs.find { it.name == subName }
            if (sub != null) {
                assert (sub.type ?: "string") == "string" : "name.${subName} must be string"
                assert (sub.multiValued ?: false) == false : "name.${subName} must be single-valued"
            }
        }

        and: "Singular User attributes adhere to RFC 7643 §4.1.1 definitions when declared"
        ["displayName", "nickName", "title", "userType", "preferredLanguage", "locale", "timezone"].each { attrName ->
            def attr = attributes.find { it.name == attrName }
            if (attr != null) {
                assert (attr.type ?: "string") == "string" : "${attrName} type must be string"
                assert (attr.multiValued ?: false) == false : "${attrName} must be single-valued"
            }
        }
        def profileUrlAttr = attributes.find { it.name == "profileUrl" }
        if (profileUrlAttr != null) {
            assert profileUrlAttr.type == "reference" : "profileUrl type must be reference"
            assert (profileUrlAttr.multiValued ?: false) == false : "profileUrl must be single-valued"
        }
    }

    // ─── Common and Security Attributes ─────────────────────────────────────

    @SpecRef("RFC7643-3.1-R1")
    @SpecRef("RFC7643-3.1-R2")
    @SpecRef("RFC7643-3.1-R5")
    @SpecRef("RFC7643-4.1.1-R2")
    def "SCH_02: Core User schema common and security attributes adhere to RFC 7643"() {
        // RFC 7643 §3.1 — Common attributes; §4.1.1 — Security attributes
        when: "GET the User schema definition"
        def response = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "User schema attributes list is present"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null

        and: "id attribute (if declared in schema) has readOnly mutability, caseExact true, and is returned always"
        def idAttr = attributes.find { it.name == "id" }
        if (idAttr != null) {
            assert idAttr.type == "string" : "id type must be string"
            assert idAttr.mutability == "readOnly" : "id mutability must be readOnly"
            if (idAttr.caseExact != null) {
                assert idAttr.caseExact == true : "id caseExact must be true"
            }
            if (idAttr.returned != null) {
                assert idAttr.returned == "always" : "id returned characteristic must be 'always'"
            }
        }

        and: "externalId attribute (if declared) has readWrite mutability and caseExact true"
        def extIdAttr = attributes.find { it.name == "externalId" }
        if (extIdAttr != null) {
            assert extIdAttr.type == "string" : "externalId type must be string"
            assert extIdAttr.mutability == "readWrite" : "externalId mutability must be readWrite"
            if (extIdAttr.caseExact != null) {
                assert extIdAttr.caseExact == true : "externalId caseExact must be true"
            }
        }

        and: "meta attribute (if declared) is complex and readOnly with readOnly dateTime timestamps"
        def metaAttr = attributes.find { it.name == "meta" }
        if (metaAttr != null) {
            assert metaAttr.type == "complex" : "meta type must be complex"
            assert metaAttr.mutability == "readOnly" : "meta mutability must be readOnly"
            ["created", "lastModified"].each { tsName ->
                def tsSub = metaAttr.subAttributes?.find { it.name == tsName }
                if (tsSub != null) {
                    assert tsSub.type == "dateTime" : "meta.${tsName} type must be dateTime"
                    assert tsSub.mutability == "readOnly" : "meta.${tsName} mutability must be readOnly"
                }
            }
        }

        and: "password attribute is writeOnly and returned never if declared in schema"
        def passwordAttr = attributes.find { it.name == "password" }
        if (passwordAttr != null) {
            assert passwordAttr.type == "string" : "password type must be string"
            assert passwordAttr.mutability == "writeOnly" : "password mutability must be writeOnly"
            assert passwordAttr.returned == "never" : "password returned characteristic must be never"
        }
    }

    // ─── Multi-Valued Attributes ────────────────────────────────────────────

    @SpecRef("RFC7643-4.1.2-R1")
    @SpecRef("RFC7643-4.1.1-R6")
    def "SCH_03: Core User schema contains expected multi-valued attributes"() {
        // RFC 7643 §4.1.2 — Multi-valued attributes
        when: "GET the User schema definition"
        def response = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "emails attribute is multi-valued complex type"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null
        def emailsAttr = attributes.find { it.name == "emails" }
        emailsAttr != null
        emailsAttr.type == "complex"
        emailsAttr.multiValued == true

        and: "emails sub-attributes contain value, type, and primary"
        def emailSubAttrs = emailsAttr.subAttributes
        emailSubAttrs != null
        emailSubAttrs.find { it.name == "value" } != null
        emailSubAttrs.find { it.name == "type" } != null
        emailSubAttrs.find { it.name == "primary" } != null

        and: "active attribute is a single-valued boolean type with readWrite mutability"
        def activeAttr = attributes.find { it.name == "active" }
        activeAttr != null
        activeAttr.type == "boolean"
        (activeAttr.multiValued ?: false) == false
        (activeAttr.mutability ?: "readWrite") == "readWrite"
    }

    // ─── Complex and Reference Attributes ───────────────────────────────────

    @SpecRef("RFC7643-2.3.7-R1")
    @SpecRef("RFC7643-2.3.8-R1")
    @SpecRef("RFC7643-4.1.2-R1")
    def "SCH_04: Core User schema complex and reference attributes adhere to RFC 7643"() {
        // RFC 7643 §2.3.7 — Reference; §2.3.8 — Complex attributes
        when: "GET the User schema definition"
        def response = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Complex attributes do not contain nested complex attributes (RFC 7643 §2.3.8)"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null
        def complexAttrs = attributes.findAll { it.type == "complex" }
        complexAttrs.each { complexAttr ->
            assert complexAttr.subAttributes != null : "Complex attribute '${complexAttr.name}' must have subAttributes"
            complexAttr.subAttributes.each { subAttr ->
                assert subAttr.type != "complex" :
                    "Sub-attribute '${subAttr.name}' of '${complexAttr.name}' must not be complex (RFC 7643 §2.3.8)"
            }
        }

        and: "Reference attributes and sub-attributes declare referenceTypes (RFC 7643 §2.3.7)"
        attributes.findAll { it.type == "reference" }.each { refAttr ->
            if (refAttr.referenceTypes != null) {
                assert !(refAttr.referenceTypes as List).isEmpty() : "Reference attribute '${refAttr.name}' must declare non-empty referenceTypes"
            }
        }
        complexAttrs.each { complexAttr ->
            complexAttr.subAttributes.findAll { it.type == "reference" }.each { refSub ->
                if (refSub.referenceTypes != null) {
                    assert !(refSub.referenceTypes as List).isEmpty() : "Reference sub-attribute '${complexAttr.name}.${refSub.name}' must declare non-empty referenceTypes"
                }
            }
        }
    }

    // ─── ReadOnly Groups Attribute ──────────────────────────────────────────

    @SpecRef("RFC7643-4.1.2-R6")
    def "SCH_05: groups attribute on User schema is readOnly"() {
        // RFC 7643 §4.1.2 — groups attribute is readOnly
        when: "GET the User schema definition"
        def response = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "groups attribute (if present) has mutability readOnly"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null
        def groupsAttr = attributes.find { it.name == "groups" }
        if (groupsAttr != null) {
            assert groupsAttr.mutability == "readOnly" : "groups attribute mutability must be readOnly (RFC 7643 §4.1.2)"
        }
    }

    // ─── Core Group Schema ──────────────────────────────────────────────────

    @SpecRef("RFC7643-4.2-R1")
    @SpecRef("RFC7643-4.2-R2")
    def "SCH_06: Core Group schema has correct attribute definitions per RFC 7643"() {
        // RFC 7643 §4.2 — Group schema
        when: "GET the Group schema definition"
        def response = scimRequest()
            .get("/Schemas/${GROUP_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Schema id and name match RFC 7643 §4.2"
        response.jsonPath().getString("id") == GROUP_SCHEMA
        response.jsonPath().getString("name") == "Group"

        and: "displayName is a readWrite string"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null
        def displayNameAttr = attributes.find { it.name == "displayName" }
        displayNameAttr != null
        displayNameAttr.type == "string"
        displayNameAttr.mutability == "readWrite"

        and: "members is complex, multi-valued, and readWrite"
        def membersAttr = attributes.find { it.name == "members" }
        membersAttr != null
        membersAttr.type == "complex"
        membersAttr.multiValued == true
        membersAttr.mutability in ["readWrite", "readOnly"]

        and: 'members sub-attributes contain value, $ref, type, and display'
        def subAttrs = membersAttr.subAttributes
        subAttrs != null

        def valueSub = subAttrs.find { it.name == "value" }
        assert valueSub != null : "members must have 'value' sub-attribute"
        assert valueSub.type == "string" : "members.value must be string"
        assert valueSub.mutability in ["immutable", "readWrite", "readOnly"]

        def refSub = subAttrs.find { it.name == '$ref' }
        assert refSub != null : "members must have '\$ref' sub-attribute"
        assert refSub.type == "reference" : "members.\$ref must be reference"

        def typeSub = subAttrs.find { it.name == "type" }
        if (typeSub != null) {
            assert (typeSub.type ?: "string") == "string" : "members.type must be string"
            if (typeSub.mutability != null) {
                assert typeSub.mutability in ["immutable", "readWrite", "readOnly"]
            }
        }

        def displaySub = subAttrs.find { it.name == "display" }
        if (displaySub != null) {
            assert displaySub.type == "string"
            assert displaySub.mutability in ["readOnly", "readWrite", "immutable"] :
                "members.display mutability '${displaySub.mutability}' must be in [readOnly, readWrite, immutable] (RFC 7643 §2.4)"
        }
    }

    // ─── Enterprise User Extension ──────────────────────────────────────────

    @RequiresCapability("enterprise")
    @SpecRef("RFC7643-4.3-R1")
    @SpecRef("RFC7643-4.3-R2")
    def "SCH_07: Enterprise User extension schema is present and contains required attributes"() {
        // RFC 7643 §4.3 — Enterprise User Extension
        when: "GET the Schemas endpoint"
        def response = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Enterprise User extension schema exists if supported"
        def schemas = extractSchemasList(response)
        def enterpriseSchema = schemas.find { it.id == ENTERPRISE_USER_SCHEMA }
        if (enterpriseSchema == null) {
            requireCapability("enterprise")
            assert enterpriseSchema != null : "Enterprise User extension schema (${ENTERPRISE_USER_SCHEMA}) must be present in /Schemas when enterprise capability is supported (RFC 7643 §4.3)"
        }

        and: "Enterprise schema declares at least one standard RFC 7643 §4.3 attribute (RFC 7644 §3.1 allows schema subsets)"
        def attributes = enterpriseSchema.attributes
        assert attributes != null && !attributes.isEmpty() : "Enterprise schema must declare attributes"
        def declaredEnterpriseAttrs = ["employeeNumber", "costCenter", "organization", "division", "department", "manager"].findAll { attrName ->
            attributes.any { it.name == attrName }
        }
        assert !declaredEnterpriseAttrs.isEmpty() :
            "Enterprise schema must declare at least one standard RFC 7643 §4.3 attribute (employeeNumber, costCenter, organization, division, department, or manager)"

        def empNumAttr = attributes.find { it.name == "employeeNumber" }
        if (empNumAttr != null) {
            assert empNumAttr.type == "string" : "employeeNumber type should be string"
        }

        and: "Enterprise schema contains valid department, costCenter, organization, division if present"
        ["department", "costCenter", "organization", "division"].each { attrName ->
            def attr = attributes.find { it.name == attrName }
            if (attr != null) {
                assert attr.type == "string" : "${attrName} type should be string"
            }
        }

        and: "Enterprise schema manager attribute is valid complex type if declared (RFC 7643 §4.3, RFC 7644 §3.1)"
        def mgrAttr = attributes.find { it.name == "manager" }
        if (mgrAttr != null) {
            assert mgrAttr.type == "complex" : "manager should be complex type"
            if (mgrAttr.subAttributes != null) {
                def valSub = mgrAttr.subAttributes.find { it.name == "value" }
                assert valSub != null && valSub.type == "string" : "manager.value should be string"
                def refSub = mgrAttr.subAttributes.find { it.name == '$ref' }
                if (refSub != null) {
                    assert refSub.type == "reference" : "manager.\$ref should be reference"
                }
                def dispSub = mgrAttr.subAttributes.find { it.name in ["displayName", "display"] }
                if (dispSub != null && dispSub.mutability != null) {
                    assert dispSub.mutability in ["readOnly", "immutable"] : "manager.${dispSub.name} should be readOnly"
                }
            }
        }
    }

    // ─── Attribute Constraints across Schemas ───────────────────────────────

    @SpecRef("RFC7643-2.1-R1")
    @SpecRef("RFC7643-2.2-R1")
    def "SCH_08: All advertised schemas satisfy RFC 7643 §2.2 and §2.3 attribute constraints"() {
        // RFC 7643 §2.2 — Attribute characteristics; §2.3 — Data types
        when: "GET /Schemas to inspect all published schemas"
        def response = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "All attributes across all schemas have valid types, mutability, returned, uniqueness"
        def schemas = extractSchemasList(response)
        schemas != null && !schemas.isEmpty()

        schemas.each { schema ->
            String schemaId = schema.id ?: "unknown"
            assert schema.name != null : "Schema '${schemaId}' should declare name per RFC 7643 §7"
            if (schema.attributes != null) {
                schema.attributes.each { attr ->
                    validateAttributeCharacteristics(schemaId, (Map) attr)
                }
            }
        }
    }

    // ─── Schema Identity and Metadata ───────────────────────────────────────

    @SpecRef("RFC7643-7-R1")
    def "SCH_09: Schema representations include valid schema URN and meta resourceType"() {
        // RFC 7643 §7 — Schema definition resource
        when: "GET /Schemas"
        def response = scimRequest()
            .get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Each schema defines an id URI and valid meta if present"
        def schemas = extractSchemasList(response)
        schemas.each { schema ->
            assert schema.id != null && !schema.id.toString().isBlank() : "Every schema must have an id URI"
            assert schema.id.toString().startsWith("urn:") : "Schema id '${schema.id}' should be a URN"

            def meta = schema.meta
            if (meta != null && meta.resourceType != null) {
                assert meta.resourceType == "Schema" :
                    "Schema '${schema.id}' meta.resourceType must be 'Schema' per RFC 7643 §7"
            }
        }
    }

    // ─── Attribute Mutability Values ────────────────────────────────────────

    @SpecRef("RFC7643-2.2-R1")
    def "SCH_10: Attribute mutability values are valid per RFC 7643 §2.2"() {
        // RFC 7643 §2.2 — mutability: readOnly, readWrite, immutable, writeOnly
        when: "GET the User schema definition"
        def response = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "All User schema attributes have valid mutability (or default readWrite when omitted)"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null

        attributes.each { attr ->
            String mutability = attr.mutability ?: "readWrite"
            assert VALID_MUTABILITIES.contains(mutability) :
                "Attribute '${attr.name}' has invalid mutability '${attr.mutability}' (RFC 7643 §2.2)"
        }
    }

    // ─── Gap Closures: SCH_11..SCH_14 ────────────────────────────────────────

    @SpecRef("RFC7643-4.2-R1")
    def "SCH_11: Group displayName attribute required is true per RFC 7643 §4.2 and erratum 5368"() {
        // RFC 7643 §4.2 and Erratum 5368 (Verified): Group displayName is REQUIRED
        when: "GET /Schemas/${GROUP_SCHEMA}"
        def response = scimRequest().get("/Schemas/${GROUP_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "displayName required is true per RFC 7643 §4.2 and Erratum 5368"
        def attributes = response.jsonPath().getList("attributes")
        def displayNameAttr = attributes?.find { it.name == "displayName" }
        assert displayNameAttr != null : "displayName attribute must be present in Group schema"
        assert displayNameAttr.required == true : "Group displayName declared required=${displayNameAttr.required} instead of true (RFC 7643 §4.2, Erratum 5368)"
    }

    @SpecRef("RFC7643-2.4-R2")
    def "SCH_12: Multi-valued complex display sub-attribute allowed mutability includes immutable per RFC 7643 §2.4"() {
        // RFC 7643 §2.4 — display sub-attribute has mutability immutable (or readOnly/readWrite in §8.7.1)
        when: "GET /Schemas/${GROUP_SCHEMA} and /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${GROUP_SCHEMA}")
        def userSchemaResp = scimRequestQuiet().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200
        userSchemaResp.statusCode() == 200

        and: "members.display and User multi-valued display sub-attributes have string type and mutability in [immutable, readOnly, readWrite]"
        def attributes = response.jsonPath().getList("attributes")
        def membersAttr = attributes?.find { it.name == "members" }
        def displaySub = membersAttr?.subAttributes?.find { it.name == "display" }
        if (displaySub != null) {
            assert (displaySub.type ?: "string") == "string" : "members.display type must be string (RFC 7643 §2.4)"
            if (displaySub.mutability != null) {
                assert displaySub.mutability in ["immutable", "readOnly", "readWrite"] :
                    "members.display mutability '${displaySub.mutability}' not permitted (RFC 7643 §2.4)"
            }
        }
        def userAttrs = userSchemaResp.jsonPath().getList("attributes")
        ["emails", "phoneNumbers", "ims", "photos", "entitlements", "roles", "x509Certificates", "groups"].each { fam ->
            def mvAttr = userAttrs?.find { it.name == fam }
            def famDisplay = mvAttr?.subAttributes?.find { it.name == "display" }
            if (famDisplay != null) {
                assert (famDisplay.type ?: "string") == "string" : "User.${fam}.display type must be string (RFC 7643 §2.4)"
                if (famDisplay.mutability != null) {
                    assert famDisplay.mutability in ["immutable", "readOnly", "readWrite"] :
                        "User.${fam}.display mutability '${famDisplay.mutability}' not permitted (RFC 7643 §2.4)"
                }
            }
        }
    }

    @SpecRef("RFC7643-4.1.2-R2")
    def "SCH_13a: Core User schema defines multi-valued phoneNumbers attribute family"() {
        // RFC 7643 §4.1.2 — User multi-valued phoneNumbers
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Inspect phoneNumbers attribute family"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null
        def phoneNumbers = attributes.find { it.name == "phoneNumbers" }
        if (phoneNumbers == null) {
            throw new TestAbortedException("User schema does not declare 'phoneNumbers' attribute family")
        }
        assert phoneNumbers.multiValued == true
        assert phoneNumbers.type == "complex"
        def phoneType = phoneNumbers.subAttributes?.find { it.name == "type" }
        if (phoneType?.canonicalValues != null) {
            assert (phoneType.canonicalValues as List).containsAll(["work", "home", "mobile", "fax", "pager", "other"])
        }
        def phonePrim = phoneNumbers.subAttributes?.find { it.name == "primary" }
        if (phonePrim != null) {
            assert phonePrim.type == "boolean"
        }
    }

    @SpecRef("RFC7643-4.1.2-R3")
    def "SCH_13b: Core User schema defines multi-valued ims attribute family"() {
        // RFC 7643 §4.1.2 — User multi-valued ims
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Inspect ims attribute family"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null
        def ims = attributes.find { it.name == "ims" }
        if (ims == null) {
            throw new TestAbortedException("User schema does not declare 'ims' attribute family")
        }
        assert ims.multiValued == true
        assert ims.type == "complex"
    }

    @SpecRef("RFC7643-4.1.2-R4")
    def "SCH_13c: Core User schema defines multi-valued photos attribute family"() {
        // RFC 7643 §4.1.2 — User multi-valued photos
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Inspect photos attribute family"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null
        def photos = attributes.find { it.name == "photos" }
        if (photos == null) {
            throw new TestAbortedException("User schema does not declare 'photos' attribute family")
        }
        assert photos.multiValued == true
        assert photos.type == "complex"
        def photoVal = photos.subAttributes?.find { it.name == "value" }
        if (photoVal != null) {
            assert photoVal.type == "reference"
        }
        def photoType = photos.subAttributes?.find { it.name == "type" }
        if (photoType?.canonicalValues != null) {
            def canonical = photoType.canonicalValues as List
            assert canonical.containsAll(["photo", "thumbnail"]) :
                "photos.type canonicalValues should include photo and thumbnail (RFC 7643 §4.1.2)"
        }
    }

    @SpecRef("RFC7643-4.1.2-R5")
    def "SCH_13d: Core User schema defines multi-valued addresses attribute family"() {
        // RFC 7643 §4.1.2 — User multi-valued addresses
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Inspect addresses attribute family"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null
        def addresses = attributes.find { it.name == "addresses" }
        if (addresses == null) {
            throw new TestAbortedException("User schema does not declare 'addresses' attribute family")
        }
        assert addresses.multiValued == true
        assert addresses.type == "complex"
        def countrySub = addresses.subAttributes?.find { it.name == "country" }
        if (countrySub != null) {
            assert countrySub.type == "string" : "addresses.country must be string (RFC 7643 §4.1.2)"
        }
    }

    @SpecRef("RFC7643-4.1.2-R7")
    def "SCH_13e: Core User schema defines multi-valued roles and entitlements attribute families"() {
        // RFC 7643 §4.1.2 — User multi-valued roles and entitlements
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Inspect roles and entitlements attribute families"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null
        def rolesAttr = attributes.find { it.name == "roles" }
        def entitlementsAttr = attributes.find { it.name == "entitlements" }
        if (!rolesAttr || !entitlementsAttr) {
            throw new TestAbortedException("User schema does not declare both 'roles' and 'entitlements' attribute families")
        }
        [rolesAttr, entitlementsAttr].each { attr ->
            assert attr.multiValued == true
            assert attr.type == "complex"
        }
    }

    @SpecRef("RFC7643-4.1.2-R8")
    @SpecRef("RFC7643-2.3.6-R1")
    def "SCH_13f: Core User schema defines multi-valued x509Certificates attribute family"() {
        // RFC 7643 §4.1.2, §2.3.6 — User multi-valued x509Certificates
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Inspect x509Certificates attribute family"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null
        def x509Certificates = attributes.find { it.name == "x509Certificates" }
        if (x509Certificates == null) {
            throw new TestAbortedException("User schema does not declare 'x509Certificates' attribute family")
        }
        assert x509Certificates.multiValued == true
        assert x509Certificates.type == "complex"
        def certVal = x509Certificates.subAttributes?.find { it.name == "value" }
        assert certVal != null : "x509Certificates must declare value sub-attribute (RFC 7643 §4.1.2)"
        assert certVal.type == "binary" : "x509Certificates.value type must be 'binary' (RFC 7643 §2.3.6, §4.1.2)"
        assert certVal.caseExact in [true, false, null] : "binary attribute x509Certificates.value caseExact must be true, false, or omitted (RFC 7643 §2.3.6, Erratum 6000)"
    }

    @SpecRef("RFC7643-2.2-R2")
    def "SCH_14: Schema attribute defaults apply when characteristics omitted per RFC 7643 §2.2"() {
        // RFC 7643 §2.2 — Characteristics default values when omitted
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Attributes with omitted characteristics are valid per default table"
        def attributes = response.jsonPath().getList("attributes")
        attributes.each { attr ->
            String effectiveType = attr.type ?: "string"
            String effectiveMutability = attr.mutability ?: "readWrite"
            String effectiveReturned = attr.returned ?: "default"
            String effectiveUniqueness = attr.uniqueness ?: "none"

            assert VALID_TYPES.contains(effectiveType)
            assert VALID_MUTABILITIES.contains(effectiveMutability)
            assert VALID_RETURNED.contains(effectiveReturned)
            assert VALID_UNIQUENESS.contains(effectiveUniqueness)
            assert attr.required == null || attr.required instanceof Boolean : "attr ${attr.name} required must be boolean"
            assert attr.caseExact == null || attr.caseExact instanceof Boolean : "attr ${attr.name} caseExact must be boolean"
            assert attr.multiValued == null || attr.multiValued instanceof Boolean : "attr ${attr.name} multiValued must be boolean"
        }
        ["nickName", "title", "displayName"].each { stdName ->
            def stdAttr = attributes.find { it.name == stdName }
            if (stdAttr != null) {
                assert (stdAttr.type ?: "string") == "string"
                assert VALID_MUTABILITIES.contains(stdAttr.mutability ?: "readWrite")
                assert VALID_RETURNED.contains(stdAttr.returned ?: "default")
                assert VALID_UNIQUENESS.contains(stdAttr.uniqueness ?: "none")
                assert (stdAttr.required != null ? stdAttr.required : false) instanceof Boolean
                assert (stdAttr.caseExact != null ? stdAttr.caseExact : false) instanceof Boolean
            }
        }
    }

    @SpecRef("RFC7643-2.2-R1")
    def "SCH_15: Characteristic applicability rules (subAttributes only on complex, referenceTypes only on reference)"() {
        // RFC 7643 §2.2 — Characteristic applicability
        when: "GET /Schemas"
        def response = scimRequest().get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "All attributes and sub-attributes adhere to characteristic applicability rules"
        def schemas = extractSchemasList(response)
        schemas.each { schema ->
            validateCharacteristicApplicability(schema.id?.toString(), schema.attributes as List<Map>)
        }
    }

    // ─── Canonical Values on emails.type ────────────────────────────────────

    @SpecRef("RFC7643-2.2-R7")
    def "SCH_16: canonicalValues on emails.type suggests standard values when present"() {
        // RFC 7643 §2.2, §2.3.1, §7 — canonicalValues on emails.type
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "emails.type canonicalValues is verified or aborted if omitted"
        def attributes = response.jsonPath().getList("attributes")
        def emailsAttr = attributes?.find { it.name == "emails" }
        def typeSubAttr = emailsAttr?.subAttributes?.find { it.name == "type" }
        if (typeSubAttr?.canonicalValues == null) {
            throw new TestAbortedException("Server omits optional canonicalValues on emails.type")
        }
        def canonical = typeSubAttr.canonicalValues as List
        assert !canonical.isEmpty() : "emails.type canonicalValues must not be empty when declared"
        assert canonical.every { it instanceof String && !it.isBlank() } :
            "emails.type canonicalValues must be non-blank strings (RFC 7643 §2.2, §7)"
    }

    private void validateCharacteristicApplicability(String schemaId, List<Map> attributes) {
        if (!attributes) return
        attributes.each { Map attr ->
            String type = attr.type ?: "string"
            if (type != "reference" && attr.referenceTypes != null && !(attr.referenceTypes as List).isEmpty()) {
                assert false : "Schema '${schemaId}' attr '${attr.name}' of type '${type}' must not have referenceTypes (RFC 7643 §2.2)"
            }
            if (type != "complex" && attr.subAttributes != null && !(attr.subAttributes as List).isEmpty()) {
                assert false : "Schema '${schemaId}' attr '${attr.name}' of type '${type}' must not have subAttributes (RFC 7643 §2.2)"
            }
            if (attr.subAttributes instanceof List) {
                validateCharacteristicApplicability(schemaId, attr.subAttributes as List<Map>)
            }
        }
    }

    // ─── Helper Methods ─────────────────────────────────────────────────────

    private static List extractSchemasList(Response response) {
        def body = response.jsonPath()
        if (body.get("Resources") != null) {
            return body.getList("Resources")
        }
        return body.getList("")
    }

    private static void validateAttributeCharacteristics(String schemaId, Map attr, boolean isSubAttribute = false) {
        String attrName = attr.name
        assert attrName != null && !attrName.isBlank() :
            "Schema '${schemaId}' contains attribute with null or blank name"
        assert attrName ==~ '^(?:\\$ref|[A-Za-z][A-Za-z0-9$_-]*)$' :
            "Schema '${schemaId}' attribute '${attrName}' does not conform to RFC 7643 §2.1 ABNF ATTRNAME"

        String attrType = attr.type ?: "string"
        assert VALID_TYPES.contains(attrType) :
            "Schema '${schemaId}' attribute '${attrName}' has invalid type: '${attrType}'"

        if (attr.mutability != null) {
            assert VALID_MUTABILITIES.contains(attr.mutability) :
                "Schema '${schemaId}' attribute '${attrName}' has invalid mutability: '${attr.mutability}'"
        }

        if (attr.returned != null) {
            assert VALID_RETURNED.contains(attr.returned) :
                "Schema '${schemaId}' attribute '${attrName}' has invalid returned value: '${attr.returned}'"
        }

        if (attr.uniqueness != null) {
            assert VALID_UNIQUENESS.contains(attr.uniqueness) :
                "Schema '${schemaId}' attribute '${attrName}' has invalid uniqueness value: '${attr.uniqueness}'"
        }

        if (attr.multiValued != null) {
            assert attr.multiValued instanceof Boolean :
                "Schema '${schemaId}' attribute '${attrName}' multiValued must be boolean"
        }

        if (attr.required != null) {
            assert attr.required instanceof Boolean :
                "Schema '${schemaId}' attribute '${attrName}' required must be boolean"
        }

        if (attr.caseExact != null) {
            assert attr.caseExact instanceof Boolean :
                "Schema '${schemaId}' attribute '${attrName}' caseExact must be boolean"
        }

        if (attrType == "complex") {
            if (isSubAttribute && schemaId != "urn:ietf:params:scim:schemas:core:2.0:Schema") {
                assert false :
                    "Schema '${schemaId}' attribute '${attrName}': RFC 7643 §2.3.8 prohibits complex attributes within complex attributes"
            }
            def subs = attr.subAttributes
            if (subs != null) {
                subs.each { sub ->
                    validateAttributeCharacteristics(schemaId, (Map) sub, true)
                }
            }
        }

        if (attrType == "reference") {
            def refTypes = attr.referenceTypes
            if (refTypes != null) {
                assert !refTypes.isEmpty() :
                    "Schema '${schemaId}' reference attribute '${attrName}' must declare non-empty referenceTypes"
            }
        }
    }
}
