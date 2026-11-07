package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.restassured.response.Response
import org.opentest4j.TestAbortedException
import spock.lang.Ignore

/**
 * RFC 7643: SCIM Core Schema
 *
 * One feature per requirement of requirements-rfc7643.yaml (area "Attribute model"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7643AttributeModelSpec extends ScimBaseSpec {

    // ---------------------------------------------------------------------------------------------
    // helpers (private to this class)
    // ---------------------------------------------------------------------------------------------

    private static final java.util.regex.Pattern XSD_DATETIME = java.util.regex.Pattern.compile(
        '^-?\\d{4,}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d+)?(Z|[+-]((0\\d|1[0-3]):[0-5]\\d|14:00))?$')
    private static final java.util.regex.Pattern ATTR_NAME = java.util.regex.Pattern.compile('^[A-Za-z][A-Za-z0-9$_-]*$')
    private static final List<String> SCIM_DATA_TYPES = ["string", "boolean", "decimal", "integer", "dateTime", "binary", "reference", "complex"]

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8)
    }

    private static Object parseJson(Response response) {
        return new JsonSlurper().parseText(response.body().asString())
    }

    private Map fetchJson(String path) {
        Response response = scimRequest().get(path)
        assert response.statusCode() == 200 : "GET ${path} must return 200 but was ${response.statusCode()}"
        return (Map) parseJson(response)
    }

    /** POST a resource and register the created id for cleanup. */
    private Response postResource(String path, Map payload) {
        Response response = scimRequest().body(JsonOutput.toJson(payload)).post(path)
        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) {
                if (path == "/Users") {
                    createdUserIds.add(id)
                } else {
                    createdGroupIds.add(id)
                }
            }
        }
        return response
    }

    private List<Map> serverSchemas() {
        Map doc = fetchJson("/Schemas")
        List<Map> resources = (List<Map>) doc.Resources
        assert resources != null && !resources.isEmpty() : "GET /Schemas must return schema resources"
        return resources
    }

    private Map userAttribute(String name) {
        Map userSchema = serverSchemas().find { it.id == USER_SCHEMA }
        assert userSchema != null : "GET /Schemas must contain ${USER_SCHEMA}"
        return (Map) ((List<Map>) userSchema.attributes).find { it.name == name }
    }

    /** All attributes and sub-attributes of a schema as [path, attr] entries. */
    private static List<Map> flattenAttrs(List<Map> attrs, String prefix = "") {
        List<Map> out = []
        (attrs ?: []).each { Map a ->
            out.add([path: prefix + a.name, attr: a])
            if (a.subAttributes) {
                out.addAll(flattenAttrs((List<Map>) a.subAttributes, prefix + a.name + "."))
            }
        }
        return out
    }

    private static boolean isUnassigned(Object value) {
        return value == null || (value instanceof List && ((List) value).isEmpty())
    }

    private static boolean validBase64(String value) {
        try {
            java.util.Base64.getDecoder().decode(value)
            return true
        } catch (IllegalArgumentException ignored) {
            return false
        }
    }

    private static Object reverseKeys(Object node) {
        if (node instanceof Map) {
            Map out = new LinkedHashMap()
            ((Map) node).keySet().toList().reverse().each { out.put(it, reverseKeys(((Map) node).get(it))) }
            return out
        }
        if (node instanceof List) {
            return ((List) node).collect { reverseKeys(it) }
        }
        return node
    }

    private static void collectKeyViolations(Object node, boolean topLevel, String path, List<String> bad) {
        if (node instanceof Map) {
            ((Map) node).each { k, v ->
                String key = k as String
                boolean extensionUrn = topLevel && key.contains(":")
                if (!extensionUrn && key != '$ref' && !ATTR_NAME.matcher(key).matches()) {
                    bad.add(path + key)
                }
                collectKeyViolations(v, false, path + key + ".", bad)
            }
        } else if (node instanceof List) {
            ((List) node).each { collectKeyViolations(it, false, path, bad) }
        }
    }

    private static void collectNumbers(Object node, String path, Map<String, Object> out) {
        if (node instanceof Map) {
            ((Map) node).each { k, v -> collectNumbers(v, path + "." + k, out) }
        } else if (node instanceof List) {
            ((List) node).eachWithIndex { v, i -> collectNumbers(v, path + "[" + i + "]", out) }
        } else if (node instanceof Number) {
            out.put(path, node)
        }
    }

    private String scimBaseUrl() {
        return (state().scimApiUrl + state().basePath).replaceAll('/+$', '')
    }

    /** Creates a user that is a member of a new group; returns the group and user representations. */
    private Map userInGroup() {
        Response userResponse = createUser([userName: "w1ref_${uniq()}"])
        assert userResponse.statusCode() == 201 : "POST /Users must return 201 but was ${userResponse.statusCode()}"
        String userId = userResponse.jsonPath().getString("id")
        Response groupResponse = createGroup("w1refgrp_${uniq()}", [userId])
        assert groupResponse.statusCode() == 201 : "RFC 7643 2.3.7: POST /Groups with a member must return 201 but was ${groupResponse.statusCode()}"
        String groupId = groupResponse.jsonPath().getString("id")
        return [userId: userId, groupId: groupId, group: fetchJson("/Groups/${groupId}"), user: fetchJson("/Users/${userId}")]
    }

    /** The reference URIs of the user/group relation: [label, ref, tail] where tail is the relative form. */
    private List<Map> sampleReferences() {
        Map ctx = userInGroup()
        List<Map> refs = []
        ((List<Map>) ctx.group.members ?: []).each { Map m ->
            if (m.value == ctx.userId && m['$ref'] != null) {
                refs.add([label: "Group.members.\$ref", ref: m['$ref'] as String, tail: "Users/${ctx.userId}".toString()])
            }
        }
        ((List<Map>) ctx.user.groups ?: []).each { Map g ->
            if (g.value == ctx.groupId && g['$ref'] != null) {
                refs.add([label: "User.groups.\$ref", ref: g['$ref'] as String, tail: "Groups/${ctx.groupId}".toString()])
            }
        }
        if (refs.isEmpty()) {
            throw new TestAbortedException("Server returns no \$ref sub-attribute for group members (the \$ref convention is OPTIONAL)")
        }
        return refs
    }

    private Map fetchFiltered(String filter) {
        Response response = scimRequest().queryParam("filter", filter).get("/Users")
        assert response.statusCode() == 200 : "GET /Users?filter=${filter} must return 200 but was ${response.statusCode()}"
        return (Map) parseJson(response)
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n]
        new Random().nextBytes(bytes)
        return bytes
    }

    // RFC 7643 section 2.1 (Attributes), MUST, lines 365-367
    // SCIM resources are represented in JSON [RFC7159] format and MUST specify schema via the "schemas" attribute per Section 3.
    @SpecRef("RFC7643-2.1-a12b3e")
    def "RFC7643-2.1-a12b3e: SCIM resources are represented in JSON [RFC7159] format and MUST specify schema via the 'schemas'..."() {
        expect:
        String name = "w1_${uniq()}"
        Response userResponse = postResource("/Users", [schemas: [USER_SCHEMA], userName: name])
        assert userResponse.statusCode() == 201 : "RFC 7643 2.1: POST /Users must return 201 but was ${userResponse.statusCode()}"
        String userId = userResponse.jsonPath().getString("id")
        Response groupResponse = postResource("/Groups", [schemas: [GROUP_SCHEMA], displayName: "w1grp_${uniq()}"])
        assert groupResponse.statusCode() == 201 : "RFC 7643 2.1: POST /Groups must return 201 but was ${groupResponse.statusCode()}"
        String groupId = groupResponse.jsonPath().getString("id")

        List<List> docs = [
            ["POST /Users response", parseJson(userResponse), USER_SCHEMA],
            ["GET /Users/{id}", fetchJson("/Users/${userId}"), USER_SCHEMA],
            ["POST /Groups response", parseJson(groupResponse), GROUP_SCHEMA],
            ["GET /Groups/{id}", fetchJson("/Groups/${groupId}"), GROUP_SCHEMA],
            ["GET /ServiceProviderConfig", fetchJson("/ServiceProviderConfig"), SPC_SCHEMA]
        ]
        def pass1 = docs.each { List d ->
            def schemas = d[1].schemas
            assert schemas instanceof List && !((List) schemas).isEmpty() :
                "RFC 7643 2.1: ${d[0]} must specify its schema via the 'schemas' attribute but schemas was ${schemas}"
            assert ((List) schemas).contains(d[2]) :
                "RFC 7643 2.1: ${d[0]} 'schemas' ${schemas} must contain ${d[2]}"
        }
    }

    // RFC 7643 section 2.1 (Attributes), MUST, lines 369-369
    // Attribute names MUST conform to the following ABNF rules:
    @SpecRef("RFC7643-2.1-b234ff")
    def "RFC7643-2.1-b234ff: Attribute names MUST conform to the following ABNF rules:"() {
        expect:
        // "$ref" is defined by RFC 7643 itself (sections 2.4 and 7) although it starts with "$"; it is exempt.
        List<String> badSchemaNames = []
        def pass2 = serverSchemas().each { Map schema ->
            flattenAttrs((List<Map>) schema.attributes).each { Map e ->
                String n = e.attr.name
                if (n != '$ref' && !ATTR_NAME.matcher(n).matches()) {
                    badSchemaNames.add("${schema.id}:${e.path}".toString())
                }
            }
        }
        assert badSchemaNames.isEmpty() :
            "RFC 7643 2.1: attribute names in /Schemas must match ALPHA *(\"\$\" / \"-\" / \"_\" / DIGIT / ALPHA) but these do not: ${badSchemaNames}"

        Response response = createFullUser()
        assert response.statusCode() == 201 : "POST /Users must return 201 but was ${response.statusCode()}"
        Map user = (Map) parseJson(response)
        List<String> badKeys = []
        collectKeyViolations(user, true, "", badKeys)
        assert badKeys.isEmpty() :
            "RFC 7643 2.1: attribute names in a User representation must conform to the ABNF but these do not: ${badKeys}"
    }

    // RFC 7643 section 2.3 (Attribute Data Types), SHOULD NOT, lines 443-444
    // SCIM extensions SHOULD NOT introduce new data types.
    @SpecRef("RFC7643-2.3-aa6113")
    def "RFC7643-2.3-aa6113: SCIM extensions SHOULD NOT introduce new data types."() {
        expect:
        List<String> offenders = []
        def pass3 = serverSchemas().each { Map schema ->
            flattenAttrs((List<Map>) schema.attributes).each { Map e ->
                if (!SCIM_DATA_TYPES.contains(e.attr.type)) {
                    offenders.add("${schema.id}:${e.path} has type '${e.attr.type}'".toString())
                }
            }
        }
        assert offenders.isEmpty() :
            "RFC 7643 2.3: schemas should only use the SCIM data types ${SCIM_DATA_TYPES} but found: ${offenders}"
    }

    // RFC 7643 section 2.3.1 (String), MAY, lines 490-492
    // Additionally, when "canonicalValues" is specified, service providers MAY restrict accepted values to the specified values.
    @SpecRef("RFC7643-2.3.1-bf81c1")
    def "RFC7643-2.3.1-bf81c1: Additionally, when 'canonicalValues' is specified, service providers MAY restrict accepted values to the..."() {
        expect:
        Map emails = userAttribute("emails")
        Map typeSub = (Map) ((List<Map>) emails.subAttributes).find { it.name == "type" }
        if (!typeSub?.canonicalValues) {
            throw new TestAbortedException("Server declares no canonicalValues for emails.type")
        }
        String bogus = "w1-not-canonical-${uniq()}"
        assert !((List) typeSub.canonicalValues).contains(bogus)

        when:
        Response response = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}",
            emails: [[value: "w1_${uniq()}@example.com", type: bogus]]])

        then:
        if (response.statusCode() == 201) {
            // The server does not restrict values to canonicalValues (allowed); the value must then be kept as sent.
            Map stored = fetchJson("/Users/${response.jsonPath().getString('id')}")
            assert ((List<Map>) stored.emails).any { it.type == bogus } :
                "RFC 7643 2.3.1: server accepted non-canonical emails.type '${bogus}' but returned ${stored.emails}"
        } else {
            // The server restricts to canonicalValues (allowed); it must say so with a SCIM error.
            assertScimErrorOptionalType(response, 400, "invalidValue")
        }
    }

    // RFC 7643 section 2.3.4 (Integer), MUST NOT, lines 519-521
    // The JSON format is defined in Section 6 of [RFC7159], with the additional constraint that the value MUST NOT contain fractional or exponent parts.
    @SpecRef("RFC7643-2.3.4-69cfa2")
    def "RFC7643-2.3.4-69cfa2: The JSON format is defined in Section 6 of [RFC7159], with the additional constraint that the value MUST..."() {
        expect:
        Response created = createUser([userName: "w1_${uniq()}"])
        assert created.statusCode() == 201
        Map<String, Object> numbers = [:]
        collectNumbers(fetchJson("/ServiceProviderConfig"), "ServiceProviderConfig", numbers)
        collectNumbers(fetchJson("/Users?count=1&startIndex=1"), "ListResponse", numbers)
        collectNumbers(parseJson(created), "User", numbers)
        assert numbers.size() >= 3 : "expected numeric values in ServiceProviderConfig and ListResponse but found ${numbers.keySet()}"
        List<String> offenders = numbers.findAll { k, v -> !(v instanceof Integer || v instanceof Long || v instanceof BigInteger) }
            .collect { k, v -> "${k}=${v}".toString() }
        assert offenders.isEmpty() :
            "RFC 7643 2.3.4: integer values must not contain fractional or exponent parts but found: ${offenders}"
    }

    // RFC 7643 section 2.3.5 (DateTime), MUST, lines 526-528
    // The attribute value MUST be encoded as a valid xsd:dateTime as specified in Section 3.3.7 of [XML-Schema] and MUST include both a date and a time.
    @SpecRef("RFC7643-2.3.5-0a752a")
    def "RFC7643-2.3.5-0a752a: The attribute value MUST be encoded as a valid xsd:dateTime as specified in Section 3.3.7 of [XML-Schema]..."() {
        expect:
        Response created = createUser([userName: "w1_${uniq()}"])
        assert created.statusCode() == 201
        String id = created.jsonPath().getString("id")
        Response groupResponse = createGroup("w1grp_${uniq()}")
        assert groupResponse.statusCode() == 201
        Map<String, Map> docs = [
            "POST /Users": (Map) parseJson(created),
            "GET /Users/{id}": fetchJson("/Users/${id}"),
            "POST /Groups": (Map) parseJson(groupResponse)
        ]
        def pass4 = docs.each { String label, Map doc ->
            ["created", "lastModified"].each { String attr ->
                def value = doc.meta?.get(attr)
                assert value instanceof String :
                    "RFC 7643 2.3.5: ${label} meta.${attr} must be present as a dateTime string but was ${value}"
                assert XSD_DATETIME.matcher((String) value).matches() :
                    "RFC 7643 2.3.5: ${label} meta.${attr} '${value}' is not a valid xsd:dateTime with both date and time"
            }
        }
    }

    // RFC 7643 section 2.3.5 (DateTime), MUST, lines 531-533
    // Values represented in JSON format MUST conform to the XML constraints above and are represented as a JSON string per Section 7 of [RFC7159].
    @SpecRef("RFC7643-2.3.5-185c9d")
    def "RFC7643-2.3.5-185c9d: Values represented in JSON format MUST conform to the XML constraints above and are represented as a JSON..."() {
        expect:
        Response created = createUser([userName: "w1_${uniq()}"])
        assert created.statusCode() == 201
        String raw = created.body().asString()
        def pass5 = ["created", "lastModified"].each { String attr ->
            assert raw =~ /"${attr}"\s*:\s*"/ :
                "RFC 7643 2.3.5: meta.${attr} must be represented as a JSON string in the raw response: ${raw.take(300)}"
        }
        // a dateTime attribute inside a list response is represented the same way
        Map list = fetchJson("/Users?count=1&startIndex=1")
        def pass6 = ((List<Map>) list.Resources).each { Map resource ->
            ["created", "lastModified"].each { String attr ->
                def value = resource.meta?.get(attr)
                assert value instanceof String && XSD_DATETIME.matcher((String) value).matches() :
                    "RFC 7643 2.3.5: listed resource meta.${attr} must be a JSON string conforming to xsd:dateTime but was ${value}"
            }
        }
    }

    // RFC 7643 section 2.3.6 (Binary), MUST, lines 537-538
    // The attribute value MUST be base64 encoded as specified in Section 4 of [RFC4648].
    @SpecRef("RFC7643-2.3.6-0cfc0f")
    def "RFC7643-2.3.6-0cfc0f: The attribute value MUST be base64 encoded as specified in Section 4 of [RFC4648]."() {
        expect:
        byte[] bytes = randomBytes(33)
        String encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        Response created = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}",
            x509Certificates: [[value: encoded]]])
        assert created.statusCode() == 201 : "RFC 7643 2.3.6: POST with a base64 binary value must return 201 but was ${created.statusCode()}"
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        String returned = stored.x509Certificates?.getAt(0)?.value
        assert returned != null && validBase64(returned) :
            "RFC 7643 2.3.6: binary value must be returned base64 encoded but was '${returned}'"
        assert java.util.Arrays.equals(java.util.Base64.getDecoder().decode(returned), bytes) :
            "RFC 7643 2.3.6: returned binary value '${returned}' does not decode to the bytes sent"

        // a value that is not base64 must not end up as a binary attribute value
        Response invalid = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}",
            x509Certificates: [[value: "not base64!!"]]])
        if (invalid.statusCode() == 201) {
            String kept = fetchJson("/Users/${invalid.jsonPath().getString('id')}").x509Certificates?.getAt(0)?.value
            assert kept != null && validBase64(kept) :
                "RFC 7643 2.3.6: server accepted the non-base64 binary value 'not base64!!' (201) and returned '${kept}'; a binary value MUST be base64 encoded"
        } else {
            assert invalid.statusCode() == 400 : "RFC 7643 2.3.6: invalid binary value must be rejected with 400 but was ${invalid.statusCode()}"
        }
    }

    // RFC 7643 section 2.3.6 (Binary), MAY, lines 538-540
    // In cases where a URL-safe encoding is required, the attribute definition MAY specify that base64 URL encoding be used as per Section 5 of [RFC4648].
    @SpecRef("RFC7643-2.3.6-aa15ea")
    def "RFC7643-2.3.6-aa15ea: In cases where a URL-safe encoding is required, the attribute definition MAY specify that base64 URL..."() {
        expect:
        List<Map> schemas = serverSchemas()
        assert !schemas.isEmpty()
        List<String> declared = []
        def pass7 = schemas.each { Map schema ->
            flattenAttrs((List<Map>) schema.attributes).each { Map e ->
                String description = ((String) e.attr.description ?: "").toLowerCase()
                if (e.attr.type == "binary" && description.contains("base64") && description.contains("url")) {
                    declared.add("${schema.id}|${e.path}".toString())
                }
            }
        }
        if (declared.contains("${USER_SCHEMA}|x509Certificates.value".toString())) {
            byte[] bytes = [(byte) 0xfb, (byte) 0xff, (byte) 0xfe, (byte) 0xfa, (byte) 0x01] as byte[]
            String urlSafe = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assert urlSafe.contains("-") || urlSafe.contains("_")
            Response created = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}",
                x509Certificates: [[value: urlSafe]]])
            assert created.statusCode() == 201 : "RFC 7643 2.3.6: base64url value for an attribute declared base64url must be accepted but was ${created.statusCode()}"
            String returned = fetchJson("/Users/${created.jsonPath().getString('id')}").x509Certificates?.getAt(0)?.value
            assert returned != null && java.util.Arrays.equals(java.util.Base64.getUrlDecoder().decode(returned), bytes) :
                "RFC 7643 2.3.6: returned value '${returned}' does not decode as base64url to the bytes sent"
        }
    }

    // RFC 7643 section 2.3.6 (Binary), MAY, lines 540-542
    // Unless otherwise specified in the attribute definition, trailing padding characters MAY be omitted ("=").
    @SpecRef("RFC7643-2.3.6-11e806")
    def "RFC7643-2.3.6-11e806: Unless otherwise specified in the attribute definition, trailing padding characters MAY be omitted ('=')."() {
        expect:
        // "AB" encodes to "QUI=" ; the padding "=" is omitted here
        String unpadded = "QUI"
        Response created = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}",
            x509Certificates: [[value: unpadded]]])
        assert created.statusCode() in [201, 400] :
            "RFC 7643 2.3.6: unpadded base64 value must either be accepted (201) or rejected per attribute definition (400), got ${created.statusCode()}"
        if (created.statusCode() == 201) {
            String returned = fetchJson("/Users/${created.jsonPath().getString('id')}").x509Certificates?.getAt(0)?.value
            assert returned != null && validBase64(returned) :
                "RFC 7643 2.3.6: value stored from unpadded base64 must be returned as valid base64 but was '${returned}'"
            assert new String(java.util.Base64.getDecoder().decode(returned), "UTF-8") == "AB" :
                "RFC 7643 2.3.6: unpadded value '${unpadded}' must decode to 'AB' but the returned '${returned}' does not"
        } else {
            assertScimError(created, 400)
        }
    }

    // RFC 7643 section 2.3.7 (Reference), MAY, lines 550-551
    // A resource MAY be a SCIM resource, an external link to a resource (e.g., a photo), or an identifier such as a URN.
    @SpecRef("RFC7643-2.3.7-ee3668")
    def "RFC7643-2.3.7-ee3668: A resource MAY be a SCIM resource, an external link to a resource (e.g., a photo), or an identifier such..."() {
        expect:
        String photo = "https://example.com/w1_${uniq()}.png"
        String profile = "https://example.com/profile/w1_${uniq()}"
        Response created = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}",
            profileUrl: profile, photos: [[value: photo, type: "photo"]]])
        if (created.statusCode() != 201) {
            throw new TestAbortedException("Server does not accept external links in reference attributes (HTTP ${created.statusCode()})")
        }
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        assert stored.profileUrl == profile :
            "RFC 7643 2.3.7: external link in profileUrl must be returned as sent but was ${stored.profileUrl}"
        assert ((List<Map>) stored.photos).any { it.value == photo } :
            "RFC 7643 2.3.7: external link in photos.value must be returned as sent but was ${stored.photos}"
    }

    // RFC 7643 section 2.3.7 (Reference), MUST, lines 552-553
    // The value MUST be the absolute or relative URI of the target resource.
    @SpecRef("RFC7643-2.3.7-bda313")
    def "RFC7643-2.3.7-bda313: The value MUST be the absolute or relative URI of the target resource."() {
        expect:
        URI base = new URI(scimBaseUrl() + "/")
        def pass8 = sampleReferences().each { Map r ->
            URI parsed
            try {
                parsed = new URI((String) r.ref)
            } catch (URISyntaxException e) {
                parsed = null
            }
            assert parsed != null : "RFC 7643 2.3.7: ${r.label} '${r.ref}' is not a valid URI"
            URI target = base.resolve(parsed)
            assert target.path == base.path + r.tail :
                "RFC 7643 2.3.7: ${r.label} '${r.ref}' must be the absolute or relative URI of the target resource (${base.path}${r.tail}) but resolves to ${target.path}"
        }
    }

    // RFC 7643 section 2.3.7 (Reference), MUST, lines 554-570
    // However, the base URI for relative URI resolution MUST include all URI components and path segments up to, but not including, the Endpoint URI (the SCIM service provider root endpoint); e.g., the base URI for a request to "https://example.com/v2/Users/2819c223-7f76-453a-919d-413861904646" would be "https://example.com/v2/", and the relative URI for this resource would be "Users/2819c223-7f76-453a-919d-413861904646".
    @SpecRef("RFC7643-2.3.7-8e82c9")
    def "RFC7643-2.3.7-8e82c9: However, the base URI for relative URI resolution MUST include all URI components and path segments up to,..."() {
        expect:
        URI base = new URI(scimBaseUrl() + "/")
        def pass9 = sampleReferences().each { Map r ->
            URI parsed = new URI((String) r.ref)
            if (parsed.isAbsolute()) {
                assert parsed.path == base.path + r.tail :
                    "RFC 7643 2.3.7: ${r.label} '${r.ref}' must have the path ${base.path}${r.tail} (base URI up to the endpoint URI plus ${r.tail})"
            } else {
                assert parsed.toString() == r.tail :
                    "RFC 7643 2.3.7: relative ${r.label} '${r.ref}' must be relative to the base URI ${base} and therefore equal '${r.tail}'"
            }
        }
    }

    // RFC 7643 section 2.3.7 (Reference), MUST, lines 577-577
    // A reference URI MUST be to an HTTP-addressable resource.
    @SpecRef("RFC7643-2.3.7-a66bd8")
    def "RFC7643-2.3.7-a66bd8: A reference URI MUST be to an HTTP-addressable resource."() {
        expect:
        URI base = new URI(scimBaseUrl() + "/")
        def pass10 = sampleReferences().each { Map r ->
            URI target = base.resolve(new URI((String) r.ref))
            assert target.scheme in ["http", "https"] && target.host :
                "RFC 7643 2.3.7: ${r.label} '${r.ref}' must be an HTTP-addressable URI but resolves to ${target}"
        }
    }

    // RFC 7643 section 2.3.7 (Reference), MUST, lines 577-579
    // An HTTP client performing a GET operation on a reference URI MUST receive the target resource or an appropriate HTTP response code.
    @SpecRef("RFC7643-2.3.7-1d19d6")
    def "RFC7643-2.3.7-1d19d6: An HTTP client performing a GET operation on a reference URI MUST receive the target resource or an..."() {
        expect:
        URI base = new URI(scimBaseUrl() + "/")
        def pass11 = sampleReferences().each { Map r ->
            URI target = base.resolve(new URI((String) r.ref))
            Response response = scimRequest().get(target.toString())
            assert response.statusCode() == 200 :
                "RFC 7643 2.3.7: GET on ${r.label} '${target}' must return the target resource (200) but was ${response.statusCode()}"
            String expectedId = ((String) r.tail).split("/")[1]
            assert response.jsonPath().getString("id") == expectedId :
                "RFC 7643 2.3.7: GET on ${r.label} '${target}' returned id ${response.jsonPath().getString('id')} instead of ${expectedId}"
        }
    }

    // RFC 7643 section 2.3.7 (Reference), MAY, lines 579-581
    // A SCIM service provider MAY choose to enforce referential integrity for reference types referring to SCIM resources.
    @SpecRef("RFC7643-2.3.7-98b178")
    def "RFC7643-2.3.7-98b178: A SCIM service provider MAY choose to enforce referential integrity for reference types referring to SCIM..."() {
        expect:
        String missingId = UUID.randomUUID().toString()
        Response response = createGroup("w1refint_${uniq()}", [missingId])
        if (response.statusCode() == 201) {
            // referential integrity not enforced (allowed): the group must be consistent with what was accepted
            Map stored = fetchJson("/Groups/${response.jsonPath().getString('id')}")
            assert ((List<Map>) stored.members ?: []).any { it.value == missingId } :
                "RFC 7643 2.3.7: server accepted a dangling member reference but does not return it: ${stored.members}"
        } else {
            // referential integrity enforced (allowed): the rejection must be a SCIM error
            assert response.statusCode() in [400, 404, 409] :
                "RFC 7643 2.3.7: rejection of a dangling member must be 400/404/409 but was ${response.statusCode()}"
            assertScimError(response, response.statusCode())
        }
    }

    // RFC 7643 section 2.3.7 (Reference), MAY, lines 583-585
    // By convention, a reference is commonly represented as a "$ref" sub-attribute in complex or multi-valued attributes; however, this is OPTIONAL.
    @SpecRef("RFC7643-2.3.7-d8ca61")
    def "RFC7643-2.3.7-d8ca61: By convention, a reference is commonly represented as a ''ref' sub-attribute in complex or multi-valued..."() {
        expect:
        Map ctx = userInGroup()
        Map member = (Map) ((List<Map>) ctx.group.members).find { it.value == ctx.userId }
        assert member != null : "group must contain the member that was added"
        if (!member.containsKey('$ref')) {
            throw new TestAbortedException("Server does not use the optional \$ref sub-attribute for members")
        }
        assert member['$ref'] instanceof String && !((String) member['$ref']).isBlank() :
            "RFC 7643 2.3.7: members.\$ref must be a non-empty string URI but was ${member['$ref']}"
        Map membersAttr = (Map) serverSchemas().find { it.id == GROUP_SCHEMA }.attributes.find { it.name == "members" }
        Map refSub = (Map) ((List<Map>) membersAttr.subAttributes).find { it.name == '$ref' }
        assert refSub != null && refSub.type == "reference" :
            "RFC 7643 2.3.7: the \$ref sub-attribute of members must be declared with type reference in /Schemas but was ${refSub}"
    }

    // RFC 7643 section 2.3.8 (Complex), MUST NOT, lines 592-594
    // Servers and clients MUST NOT require or expect attributes to be in any specific order when an object is either generated or analyzed.
    @SpecRef("RFC7643-2.3.8-a84092")
    def "RFC7643-2.3.8-a84092: Servers and clients MUST NOT require or expect attributes to be in any specific order when an object is..."() {
        expect:
        String suffix = uniq()
        Map payload = [
            schemas    : [USER_SCHEMA],
            userName   : "w1order_${suffix}",
            displayName: "W1 Order ${suffix}",
            name       : [formatted: "A B", familyName: "B", givenName: "A"],
            emails     : [[value: "w1order_${suffix}@example.com", type: "work", primary: true]],
            active     : true
        ]
        Response created = postResource("/Users", (Map) reverseKeys(payload))
        assert created.statusCode() == 201 : "RFC 7643 2.3.8: POST with attributes in reverse order must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        Map stored = fetchJson("/Users/${id}")
        assert stored.userName == payload.userName
        assert stored.displayName == payload.displayName
        assert stored.name.givenName == "A" && stored.name.familyName == "B" && stored.name.formatted == "A B" :
            "RFC 7643 2.3.8: name sub-attributes sent in reverse order must be stored as sent but were ${stored.name}"
        assert stored.emails.size() == 1 && stored.emails[0].value == payload.emails[0].value && stored.emails[0].primary == true

        Map replacement = new LinkedHashMap(payload)
        replacement.put("displayName", "W1 Reordered ${suffix}")
        Response replaced = scimRequest().body(JsonOutput.toJson(reverseKeys(replacement))).put("/Users/${id}")
        assert replaced.statusCode() == 200 : "RFC 7643 2.3.8: PUT with attributes in reverse order must return 200 but was ${replaced.statusCode()}"
        assert fetchJson("/Users/${id}").displayName == replacement.displayName
    }

    // RFC 7643 section 2.3.8 (Complex), MUST NOT, lines 595-596
    // A complex attribute MUST NOT contain sub-attributes that have sub-attributes (i.e., that are complex).
    @SpecRef("RFC7643-2.3.8-20749f")
    def "RFC7643-2.3.8-20749f: A complex attribute MUST NOT contain sub-attributes that have sub-attributes (i.e., that are complex)."() {
        expect:
        List<String> offenders = []
        def pass12 = serverSchemas().each { Map schema ->
            ((List<Map>) schema.attributes).each { Map attr ->
                // the Schema meta-schema of RFC 7643 section 7 itself nests "subAttributes" inside "attributes"
                if (attr.type == "complex" && schema.id != "urn:ietf:params:scim:schemas:core:2.0:Schema") {
                    ((List<Map>) attr.subAttributes ?: []).each { Map sub ->
                        if (sub.type == "complex" || sub.subAttributes) {
                            offenders.add("${schema.id}:${attr.name}.${sub.name}".toString())
                        }
                    }
                }
            }
        }
        assert offenders.isEmpty() :
            "RFC 7643 2.3.8: complex attributes must not contain complex sub-attributes but found: ${offenders}"

        // a client cannot introduce such nesting either
        Response response = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}",
            name: [givenName: [nested: "value"]]])
        if (response.statusCode() == 201) {
            Map stored = fetchJson("/Users/${response.jsonPath().getString('id')}")
            assert !(stored.name?.givenName instanceof Map) :
                "RFC 7643 2.3.8: server stored a complex value inside the sub-attribute name.givenName: ${stored.name}"
        } else {
            assertScimErrorOptionalType(response, 400, "invalidValue", "invalidSyntax")
        }
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), MUST, lines 606-608
    // objects with a set of sub-attributes and values, using the JSON object format defined in Section 4 of [RFC7159], in which case they SHALL be considered to be complex attributes.
    @SpecRef("RFC7643-2.4-40a8c0")
    def "RFC7643-2.4-40a8c0: objects with a set of sub-attributes and values, using the JSON object format defined in Section 4 of..."() {
        expect:
        List<String> offenders = []
        def pass13 = serverSchemas().each { Map schema ->
            flattenAttrs((List<Map>) schema.attributes).each { Map e ->
                Map attr = e.attr
                if (attr.subAttributes && attr.type != "complex") {
                    offenders.add("${schema.id}:${e.path} has sub-attributes but type '${attr.type}'".toString())
                }
                if (attr.multiValued && attr.type == "complex" && !attr.subAttributes) {
                    offenders.add("${schema.id}:${e.path} is complex but defines no sub-attributes".toString())
                }
            }
        }
        assert offenders.isEmpty() :
            "RFC 7643 2.4: multi-valued attributes of objects must be defined as complex attributes: ${offenders}"

        Response created = createFullUser()
        assert created.statusCode() == 201
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        assert userAttribute("emails").type == "complex" && userAttribute("emails").multiValued == true
        assert stored.emails instanceof List && !stored.emails.isEmpty() && stored.emails.every { it instanceof Map } :
            "RFC 7643 2.4: emails must be returned as an array of objects but was ${stored.emails}"
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), MUST, lines 610-612
    // The predefined sub-attributes listed in this section can be used with multi-valued attribute objects, but these sub-attributes MUST be used with the meanings defined here.
    @SpecRef("RFC7643-2.4-cbf2eb")
    def "RFC7643-2.4-cbf2eb: The predefined sub-attributes listed in this section can be used with multi-valued attribute objects, but..."() {
        expect:
        Map<String, String> expectedTypes = [type: "string", primary: "boolean", display: "string", '$ref': "reference"]
        List<String> offenders = []
        def pass14 = serverSchemas().each { Map schema ->
            ((List<Map>) schema.attributes).each { Map attr ->
                if (attr.multiValued && attr.type == "complex") {
                    ((List<Map>) attr.subAttributes).each { Map sub ->
                        if (expectedTypes.containsKey(sub.name) && sub.type != expectedTypes[sub.name]) {
                            offenders.add("${schema.id}:${attr.name}.${sub.name} has type '${sub.type}', expected '${expectedTypes[sub.name]}'".toString())
                        }
                        if (sub.name == "value" && (sub.type == "complex" || sub.multiValued)) {
                            offenders.add("${schema.id}:${attr.name}.value must be a single simple value".toString())
                        }
                        if (sub.name == "primary" && sub.multiValued) {
                            offenders.add("${schema.id}:${attr.name}.primary must be single-valued".toString())
                        }
                    }
                }
            }
        }
        assert offenders.isEmpty() :
            "RFC 7643 2.4: predefined sub-attributes must be used with their defined meanings/types: ${offenders}"

        String work = "w1_${uniq()}@example.com"
        String home = "w1_${uniq()}@example.com"
        Response created = createUser([userName: "w1_${uniq()}",
            emails: [[value: work, type: "work", primary: true], [value: home, type: "home", primary: false]]])
        assert created.statusCode() == 201
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        Map w = (Map) stored.emails.find { it.value == work }
        Map h = (Map) stored.emails.find { it.value == home }
        assert w != null && h != null : "both emails must be returned: ${stored.emails}"
        assert w.type == "work" && h.type == "home" : "RFC 7643 2.4: 'type' must keep its label meaning but was ${w.type}/${h.type}"
        assert w.primary == true && h.primary != true : "RFC 7643 2.4: 'primary' must mark the preferred value but was ${w.primary}/${h.primary}"
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), MUST, lines 633-634
    // The primary attribute value "true" MUST appear no more than once.
    @SpecRef("RFC7643-2.4-33af50")
    def "RFC7643-2.4-33af50: primary: The primary attribute value 'true' MUST appear no more than once."() {
        expect:
        Map<String, List<Map>> payloads = [
            emails      : [[value: "w1a_${uniq()}@example.com", type: "work", primary: true],
                           [value: "w1b_${uniq()}@example.com", type: "home", primary: true]],
            phoneNumbers: [[value: "+15550100001", type: "work", primary: true],
                           [value: "+15550100002", type: "home", primary: true]]
        ]
        for (String attr : payloads.keySet()) {
            Response response = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}", (attr): payloads[attr]])
            if (response.statusCode() == 201) {
                Map stored = fetchJson("/Users/${response.jsonPath().getString('id')}")
                long primaries = ((List<Map>) stored[attr] ?: []).count { it.primary == true }
                assert primaries <= 1 :
                    "RFC 7643 2.4: primary 'true' MUST appear no more than once in ${attr} but appears ${primaries} times: ${stored[attr]}"
            } else {
                assertScimErrorOptionalType(response, 400, "invalidValue")
            }
        }
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), MUST, lines 634-635
    // If not specified, the value of "primary" SHALL be assumed to be "false".
    @SpecRef("RFC7643-2.4-3ad615")
    def "RFC7643-2.4-3ad615: primary: If not specified, the value of 'primary' SHALL be assumed to be 'false'."() {
        expect:
        String first = "w1a_${uniq()}@example.com"
        String second = "w1b_${uniq()}@example.com"
        Response created = createUser([userName: "w1_${uniq()}",
            emails: [[value: first, type: "work", primary: true], [value: second, type: "home"]]])
        assert created.statusCode() == 201 : "POST must return 201 but was ${created.statusCode()}; an unspecified primary must not conflict with the primary one"
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        Map e1 = (Map) stored.emails.find { it.value == first }
        Map e2 = (Map) stored.emails.find { it.value == second }
        assert e1?.primary == true : "the email sent with primary=true must stay primary: ${stored.emails}"
        assert e2 != null && e2.primary != true :
            "RFC 7643 2.4: an unspecified 'primary' is assumed to be false but the second email came back as ${e2}"

        Response noPrimary = createUser([userName: "w1_${uniq()}",
            emails: [[value: "w1c_${uniq()}@example.com", type: "work"], [value: "w1d_${uniq()}@example.com", type: "home"]]])
        assert noPrimary.statusCode() == 201
        Map stored2 = fetchJson("/Users/${noPrimary.jsonPath().getString('id')}")
        assert stored2.emails.every { it.primary != true } :
            "RFC 7643 2.4: 'primary' unspecified on every email is assumed false, but got ${stored2.emails}"
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), SHOULD, lines 655-658
    // When returning multi-valued attributes, service providers SHOULD canonicalize the value returned (e.g., by returning a value for the sub-attribute "type", such as "home" or "work") when appropriate (e.g., for email addresses and URLs).
    @SpecRef("RFC7643-2.4-994c5d")
    def "RFC7643-2.4-994c5d: When returning multi-valued attributes, service providers SHOULD canonicalize the value returned (e.g., by..."() {
        expect:
        String value = "w1_${uniq()}@example.com"
        Response created = createUser([userName: "w1_${uniq()}", emails: [[value: value]]])
        assert created.statusCode() == 201 : "POST must return 201 but was ${created.statusCode()}"
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        Map email = (Map) stored.emails.find { it.value == value }
        assert email != null : "email must be returned: ${stored.emails}"
        assert email.type instanceof String && !((String) email.type).isBlank() :
            "RFC 7643 2.4: service providers SHOULD canonicalize multi-valued attributes, e.g. by returning a 'type' (such as work or home) for an email address, but the returned element was ${email}"
        List canonical = (List) ((List<Map>) userAttribute("emails").subAttributes).find { it.name == "type" }?.canonicalValues
        if (canonical) {
            assert canonical.contains(email.type) : "RFC 7643 2.4: returned type '${email.type}' should be one of ${canonical}"
        }
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), MAY, lines 660-664
    // Service providers MAY return element objects with the same "value" sub-attribute more than once with a different "type" sub-attribute (e.g., the same email address may be used for work and home)
    @SpecRef("RFC7643-2.4-6b51c5")
    def "RFC7643-2.4-6b51c5: Service providers MAY return element objects with the same 'value' sub-attribute more than once with a..."() {
        expect:
        String value = "w1_${uniq()}@example.com"
        Response created = createUser([userName: "w1_${uniq()}",
            emails: [[value: value, type: "work"], [value: value, type: "home"]]])
        if (created.statusCode() != 201) {
            throw new TestAbortedException("Server rejects the same email value with different types (HTTP ${created.statusCode()}); returning it more than once is optional")
        }
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        List<Map> matches = ((List<Map>) stored.emails).findAll { it.value == value }
        assert !matches.isEmpty() : "RFC 7643 2.4: the accepted email value must be returned: ${stored.emails}"
        assert matches.collect { it.type }.unique().size() == matches.size() :
            "RFC 7643 2.4: same value returned more than once must carry different types but was ${matches}"
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), SHOULD NOT, lines 660-664
    // but SHOULD NOT return the same (type, value) combination more than once per attribute, as this complicates processing by the client.
    @SpecRef("RFC7643-2.4-3f9b86")
    def "RFC7643-2.4-3f9b86: but SHOULD NOT return the same (type, value) combination more than once per attribute, as this complicates..."() {
        expect:
        String value = "w1_${uniq()}@example.com"
        Response created = createUser([userName: "w1_${uniq()}",
            emails: [[value: value, type: "work"], [value: value, type: "work"]]])
        if (created.statusCode() == 201) {
            Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
            List<List> pairs = ((List<Map>) stored.emails).collect { [it.type, it.value] }
            assert pairs.unique(false).size() == pairs.size() :
                "RFC 7643 2.4: the same (type, value) combination SHOULD NOT be returned more than once per attribute but emails was ${stored.emails}"
        } else {
            assertScimErrorOptionalType(created, 400, "invalidValue")
        }
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), MAY, lines 666-668
    // When defining schema for multi-valued attributes, it is considered a good practice to provide a type attribute that MAY be used for the purpose of canonicalization of values.
    @SpecRef("RFC7643-2.4-4fe9e4")
    def "RFC7643-2.4-4fe9e4: When defining schema for multi-valued attributes, it is considered a good practice to provide a type..."() {
        expect:
        List<String> withType = []
        List<String> offenders = []
        def pass15 = serverSchemas().each { Map schema ->
            ((List<Map>) schema.attributes).each { Map attr ->
                if (attr.multiValued && attr.type == "complex") {
                    Map typeSub = (Map) ((List<Map>) attr.subAttributes).find { it.name == "type" }
                    if (typeSub != null) {
                        withType.add("${schema.id}:${attr.name}".toString())
                        if (typeSub.type != "string" || typeSub.multiValued) {
                            offenders.add("${schema.id}:${attr.name}.type".toString())
                        }
                    }
                }
            }
        }
        if (withType.isEmpty()) {
            throw new TestAbortedException("Server defines no 'type' sub-attribute for any multi-valued attribute (optional)")
        }
        assert offenders.isEmpty() :
            "RFC 7643 2.4: a 'type' sub-attribute for canonicalization must be a single string but was not for ${offenders}"
        String value = "w1_${uniq()}@example.com"
        Response created = createUser([userName: "w1_${uniq()}", emails: [[value: value, type: "work"]]])
        assert created.statusCode() == 201
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        assert ((List<Map>) stored.emails).any { it.value == value && it.type == "work" } :
            "RFC 7643 2.4: emails.type declared in the schema must be usable for canonicalization (round trip) but was ${stored.emails}"
    }

    // RFC 7643 section 2.4 (Multi-Valued Attributes), MAY, lines 668-670
    // In the schema definition for an attribute, the service provider MAY define the recommended canonical values (see Section 7).
    @SpecRef("RFC7643-2.4-cf4745")
    def "RFC7643-2.4-cf4745: In the schema definition for an attribute, the service provider MAY define the recommended canonical..."() {
        expect:
        List<String> declared = []
        List<String> offenders = []
        def pass16 = serverSchemas().each { Map schema ->
            flattenAttrs((List<Map>) schema.attributes).each { Map e ->
                Map attr = e.attr
                if (attr.canonicalValues != null) {
                    declared.add("${schema.id}:${e.path}".toString())
                    List values = attr.canonicalValues instanceof List ? (List) attr.canonicalValues : []
                    if (values.isEmpty() || !values.every { it instanceof String } || values.unique(false).size() != values.size() || attr.type != "string") {
                        offenders.add("${schema.id}:${e.path} canonicalValues=${attr.canonicalValues} type=${attr.type}".toString())
                    }
                }
            }
        }
        if (declared.isEmpty()) {
            throw new TestAbortedException("Server declares no canonicalValues in /Schemas (optional)")
        }
        assert offenders.isEmpty() :
            "RFC 7643 2.4/7: canonicalValues must be a non-empty list of distinct strings on a string attribute: ${offenders}"
        Response created = createFullUser()
        assert created.statusCode() == 201
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        List canonical = (List) ((List<Map>) userAttribute("emails").subAttributes).find { it.name == "type" }?.canonicalValues
        if (canonical) {
            stored.emails.each { Map email ->
                assert email.type == null || canonical.contains(email.type) :
                    "RFC 7643 2.4: emails.type '${email.type}' sent from the canonical values must be one of ${canonical}"
            }
        }
    }

    // RFC 7643 section 2.5 (Unassigned and Null Values), MUST, lines 681-683
    // Unassigned attributes, the null value, or an empty array (in the case of a multi-valued attribute) SHALL be considered to be equivalent in "state".
    @SpecRef("RFC7643-2.5-80adb5")
    def "RFC7643-2.5-80adb5: Unassigned attributes, the null value, or an empty array (in the case of a multi-valued attribute) SHALL..."() {
        expect:
        String prefix = "w1eq_${uniq()}"
        Map base = [schemas: [USER_SCHEMA]]
        Response absent = postResource("/Users", base + [userName: "${prefix}_absent"])
        Response nulls = postResource("/Users", base + [userName: "${prefix}_null", nickName: null, title: null, phoneNumbers: []])
        Response seeded = postResource("/Users", base + [userName: "${prefix}_put", nickName: "nick", title: "t",
            phoneNumbers: [[value: "+15550001111", type: "work"]]])
        def pass17 = [absent, nulls, seeded].each { Response r ->
            assert r.statusCode() == 201 : "RFC 7643 2.5: POST must return 201 but was ${r.statusCode()}"
        }
        String seededId = seeded.jsonPath().getString("id")
        Response replaced = scimRequest().body(JsonOutput.toJson(base + [userName: "${prefix}_put", nickName: null, title: null, phoneNumbers: []]))
            .put("/Users/${seededId}")
        assert replaced.statusCode() == 200 : "RFC 7643 2.5: PUT assigning null/empty must return 200 but was ${replaced.statusCode()}: ${replaced.body().asString()}"

        List<String> ids = [absent, nulls, seeded].collect { it.jsonPath().getString("id") }
        def pass18 = ids.each { String id ->
            Map stored = fetchJson("/Users/${id}")
            ["nickName", "title", "phoneNumbers"].each { String attr ->
                assert isUnassigned(stored[attr]) :
                    "RFC 7643 2.5: ${attr} unassigned/null/empty must be equivalent but resource ${id} returned ${stored[attr]}"
            }
        }
        if (isFilterSupported()) {
            ["nickName pr", "title pr", "phoneNumbers pr"].each { String presence ->
                Map found = fetchFiltered("userName sw \"${prefix}\" and ${presence}")
                assert found.totalResults == 0 :
                    "RFC 7643 2.5: attributes assigned null/empty or never assigned must not match '${presence}' but totalResults was ${found.totalResults}"
            }
        }
    }

    // RFC 7643 section 2.5 (Unassigned and Null Values), MAY, lines 685-687
    // When a resource is expressed in JSON format, unassigned attributes, although they are defined in schema, MAY be omitted for compactness.
    @SpecRef("RFC7643-2.5-6663d0")
    def "RFC7643-2.5-6663d0: When a resource is expressed in JSON format, unassigned attributes, although they are defined in schema,..."() {
        expect:
        Response created = postResource("/Users", [schemas: [USER_SCHEMA], userName: "w1_${uniq()}"])
        assert created.statusCode() == 201
        Map stored = fetchJson("/Users/${created.jsonPath().getString('id')}")
        List<Map> userAttrs = (List<Map>) serverSchemas().find { it.id == USER_SCHEMA }.attributes
        List<String> offenders = []
        def pass19 = userAttrs.each { Map attr ->
            if (!(attr.name in ["id", "userName", "meta", "schemas", "active"]) && stored.containsKey(attr.name)) {
                if (!isUnassigned(stored[attr.name])) {
                    offenders.add("${attr.name}=${stored[attr.name]}".toString())
                }
            }
        }
        assert offenders.isEmpty() :
            "RFC 7643 2.5: attributes never assigned may be omitted but if returned must be null or empty, yet got: ${offenders}"
    }
}
