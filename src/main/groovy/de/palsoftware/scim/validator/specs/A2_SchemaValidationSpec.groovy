package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import de.palsoftware.scim.validator.base.spec.SpecRef
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import io.restassured.response.Response

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
    def "SCH_01: Core User schema has correct attribute definitions per RFC 7643"() {
        // RFC 7643 §4.1.1 — User schema attributes
        when: "GET the User schema definition"
        def response = scimRequest()
            .get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Schema id matches core User schema URI"
        response.jsonPath().getString("id") == USER_SCHEMA

        and: "userName attribute exists and is required"
        def attributes = response.jsonPath().getList("attributes")
        attributes != null
        def userNameAttr = attributes.find { it.name == "userName" }
        userNameAttr != null
        userNameAttr.required == true
        userNameAttr.type == "string"
        userNameAttr.mutability == "readWrite"
        userNameAttr.uniqueness in ["server", "global"]

        and: "name attribute is a complex type with familyName and givenName sub-attributes"
        def nameAttr = attributes.find { it.name == "name" }
        nameAttr != null
        nameAttr.type == "complex"

        def subAttrs = nameAttr.subAttributes
        subAttrs != null
        subAttrs.find { it.name == "familyName" } != null
        subAttrs.find { it.name == "givenName" } != null
    }

    // ─── Common and Security Attributes ─────────────────────────────────────

    @SpecRef("RFC7643-3.1-R1")
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

        and: "id attribute (if declared in schema) has readOnly mutability and is returned always"
        def idAttr = attributes.find { it.name == "id" }
        if (idAttr != null) {
            assert idAttr.type == "string" : "id type must be string"
            assert idAttr.mutability == "readOnly" : "id mutability must be readOnly"
            if (idAttr.returned != null) {
                assert idAttr.returned == "always" : "id returned characteristic must be 'always'"
            }
        } else {
            ScimOutput.println "NOTE: User schema omits 'id' attribute from attributes list " +
                "(common attributes are defined in RFC 7643 §3.1)"
        }

        and: "externalId attribute (if declared) has readWrite mutability"
        def extIdAttr = attributes.find { it.name == "externalId" }
        if (extIdAttr != null) {
            assert extIdAttr.type == "string" : "externalId type must be string"
            assert extIdAttr.mutability == "readWrite" : "externalId mutability must be readWrite"
        }

        and: "password attribute is writeOnly and returned never if declared in schema"
        def passwordAttr = attributes.find { it.name == "password" }
        if (passwordAttr != null) {
            assert passwordAttr.type == "string" : "password type must be string"
            assert passwordAttr.mutability == "writeOnly" : "password mutability must be writeOnly"
            assert passwordAttr.returned == "never" : "password returned characteristic must be never"
        } else {
            ScimOutput.println "NOTE: User schema omits optional 'password' attribute definition"
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

        and: "active attribute is a boolean type with readWrite mutability"
        def activeAttr = attributes.find { it.name == "active" }
        activeAttr != null
        activeAttr.type == "boolean"
        activeAttr.mutability == "readWrite"
    }

    // ─── Complex and Reference Attributes ───────────────────────────────────

    @SpecRef("RFC7643-2.3.7-R1")
    @SpecRef("RFC7643-2.3.8-R1")
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

        and: "emails.type defines canonical values work, home, other if canonicalValues is present"
        def emailsAttr = attributes.find { it.name == "emails" }
        def typeSubAttr = emailsAttr?.subAttributes?.find { it.name == "type" }
        if (typeSubAttr?.canonicalValues != null) {
            def canonical = typeSubAttr.canonicalValues as List
            assert canonical.containsAll(["work", "home", "other"]) :
                "emails.type canonicalValues should contain work, home, other, got: ${canonical}"
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
            assert groupsAttr.mutability in ["readOnly", "readWrite"]
            if (groupsAttr.mutability == "readWrite") {
                ScimOutput.deviation("groups attribute mutability is readWrite instead of readOnly (RFC 7643 §4.1.2)", "SCH_05", "RFC7643-4.1.2-R6")
            }
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
    @SpecRef("RFC7643-2.1-R1")
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
            return
        }

        and: "Enterprise schema contains employeeNumber attribute"
        def attributes = enterpriseSchema.attributes
        attributes != null
        def empNumAttr = attributes.find { it.name == "employeeNumber" }
        empNumAttr != null
        empNumAttr.type == "string"

        and: "Enterprise schema contains valid department, costCenter, organization, division if present"
        ["department", "costCenter", "organization", "division"].each { attrName ->
            def attr = attributes.find { it.name == attrName }
            if (attr != null) {
                assert attr.type == "string" : "${attrName} type should be string"
            }
        }

        and: "Enterprise schema manager attribute is valid complex type if present"
        def mgrAttr = attributes.find { it.name == "manager" }
        if (mgrAttr != null) {
            assert mgrAttr.type == "complex" : "manager should be complex type"
            if (mgrAttr.subAttributes != null) {
                def valSub = mgrAttr.subAttributes.find { it.name == "value" }
                assert valSub != null && valSub.type == "string" : "manager.value should be string"
            }
        } else {
            ScimOutput.deviation("Enterprise schema missing 'manager' attribute (RFC 7643 §4.3)", "SCH_07", "RFC7643-4.3-R2")
        }
    }

    // ─── Attribute Constraints across Schemas ───────────────────────────────

    @SpecRef("RFC7643-2.2-R1")
    @SpecRef("RFC7643-2.3-R1")
    @SpecRef("RFC7643-2.2-R3")
    @SpecRef("RFC7643-2.2-R4")
    @SpecRef("RFC7643-2.2-R6")
    @SpecRef("RFC7643-3.1-R2")
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
    @SpecRef("RFC7643-2.3.5-R1")
    @SpecRef("RFC7643-4.1.1-R4")
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

        and: "displayName required is true or recorded as deviation"
        def attributes = response.jsonPath().getList("attributes")
        def displayNameAttr = attributes?.find { it.name == "displayName" }
        assert displayNameAttr != null : "displayName attribute must be present in Group schema"
        if (displayNameAttr.required != true) {
            ScimOutput.deviation("Group displayName declared required=${displayNameAttr.required} instead of true (RFC 7643 §4.2, Erratum 5368)", "SCH_11", "RFC7643-4.2-R1")
        }
    }

    @SpecRef("RFC7643-2.4-R2")
    @SpecRef("RFC7643-2.2-R3")
    def "SCH_12: Multi-valued complex display sub-attribute allowed mutability includes immutable per RFC 7643 §2.4"() {
        // RFC 7643 §2.4 — display sub-attribute has mutability immutable
        when: "GET /Schemas/${GROUP_SCHEMA}"
        def response = scimRequest().get("/Schemas/${GROUP_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "members.display if present has mutability in [immutable, readOnly, readWrite]"
        def attributes = response.jsonPath().getList("attributes")
        def membersAttr = attributes?.find { it.name == "members" }
        def displaySub = membersAttr?.subAttributes?.find { it.name == "display" }
        if (displaySub != null && displaySub.mutability != null) {
            assert displaySub.mutability in ["immutable", "readOnly", "readWrite"] :
                "members.display mutability '${displaySub.mutability}' not permitted (RFC 7643 §2.4)"
        }
    }

    @SpecRef("RFC7643-4.1.2-R2")
    @SpecRef("RFC7643-4.1.2-R5")
    @SpecRef("RFC7643-4.1.2-R8")
    @SpecRef("RFC7643-2.3.6-R1")
    @SpecRef("RFC7643-2.4-R1")
    @SpecRef("RFC7643-2.4-R3")
    @SpecRef("RFC7643-4.1.2-R3")
    @SpecRef("RFC7643-4.1.2-R4")
    def "SCH_13: Core User schema defines multi-valued families (phoneNumbers, addresses with ISO 3166-1 country, x509Certificates)"() {
        // RFC 7643 §4.1.2 — User multi-valued attribute families
        when: "GET /Schemas/${USER_SCHEMA}"
        def response = scimRequest().get("/Schemas/${USER_SCHEMA}")

        then: "Status is 200"
        response.statusCode() == 200

        and: "Inspect multi-valued attribute families"
        def attributes = response.jsonPath().getList("attributes")
        assert attributes != null

        def phoneNumbers = attributes.find { it.name == "phoneNumbers" }
        if (phoneNumbers != null) {
            assert phoneNumbers.multiValued == true
            assert phoneNumbers.type == "complex"
        }

        def addresses = attributes.find { it.name == "addresses" }
        if (addresses != null) {
            assert addresses.multiValued == true
            assert addresses.type == "complex"
            def countrySub = addresses.subAttributes?.find { it.name == "country" }
            if (countrySub != null) {
                assert countrySub.type == "string" : "addresses.country must be string (RFC 7643 §4.1.2)"
            }
        }

        def x509Certificates = attributes.find { it.name == "x509Certificates" }
        if (x509Certificates != null) {
            assert x509Certificates.multiValued == true
        }
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
        }
    }

    @SpecRef("RFC7643-2.2-R1")
    def "SCH_15: Characteristic applicability rules (canonicalValues only on string, referenceTypes only on reference)"() {
        // RFC 7643 §2.2 — Characteristic applicability
        when: "GET /Schemas"
        def response = scimRequest().get("/Schemas")

        then: "Status is 200"
        response.statusCode() == 200

        and: "All attributes adhere to characteristic applicability rules"
        def schemas = extractSchemasList(response)
        schemas.each { schema ->
            def attrs = schema.attributes
            attrs?.each { Map attr ->
                String type = attr.type ?: "string"
                if (type != "string" && attr.canonicalValues != null) {
                    assert false : "Schema '${schema.id}' attr '${attr.name}' of type '${type}' must not have canonicalValues (RFC 7643 §2.2)"
                }
                if (type != "reference" && attr.referenceTypes != null) {
                    assert false : "Schema '${schema.id}' attr '${attr.name}' of type '${type}' must not have referenceTypes (RFC 7643 §2.2)"
                }
                if (type != "complex" && attr.subAttributes != null) {
                    assert false : "Schema '${schema.id}' attr '${attr.name}' of type '${type}' must not have subAttributes (RFC 7643 §2.2)"
                }
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
