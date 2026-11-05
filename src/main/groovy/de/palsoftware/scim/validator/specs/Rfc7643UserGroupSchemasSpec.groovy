package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 7643: SCIM Core Schema
 *
 * One feature per requirement of requirements-rfc7643.yaml (area "User, Group and Enterprise User schemas"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc7643UserGroupSchemasSpec extends ScimBaseSpec {

    // RFC 7643 section 4.1.1 (Singular Attributes), MUST, lines 1035-1036
    // Each User MUST include a non-empty userName value.
    @SpecRef("RFC7643-4.1.1-1bb83b")
    def "RFC7643-4.1.1-1bb83b: userName: Each User MUST include a non-empty userName value."() {
        when: "creating users without userName, with an empty userName and with a valid userName"
        def missing = postResource("/Users", [schemas: [USER_SCHEMA], displayName: "w3 no userName ${uniq()}"])
        def empty = postResource("/Users", [schemas: [USER_SCHEMA], userName: ""])
        def valid = postUser()

        then: "a User without a non-empty userName is rejected, a valid one is stored with its userName"
        assert missing.statusCode() == 400 : "RFC 7643 4.1.1: a User without userName must be rejected with 400 but was ${missing.statusCode()}"
        assert empty.statusCode() == 400 : "RFC 7643 4.1.1: a User with an empty userName must be rejected with 400 but was ${empty.statusCode()}"
        assert valid.statusCode() == 201 : "RFC 7643 4.1.1: POST of a valid User must return 201 but was ${valid.statusCode()}"
        assert hasText(valid.jsonPath().getString("userName")) : "RFC 7643 4.1.1: the returned User must carry a non-empty userName"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MUST, lines 1036-1037
    // This identifier MUST be unique across the service provider's entire set of Users.
    @SpecRef("RFC7643-4.1.1-da9770")
    def "RFC7643-4.1.1-da9770: userName: This identifier MUST be unique across the service provider's entire set of Users."() {
        given:
        String userName = "rfc7643ug_uniq_${uniq()}"

        when: "two Users with the same userName are created"
        def first = postUser([userName: userName])
        def second = postUser([userName: userName])

        then: "the second one is rejected with 409 uniqueness"
        assert first.statusCode() == 201 : "RFC 7643 4.1.1: first POST must return 201 but was ${first.statusCode()}"
        assert second.statusCode() == 409 : "RFC 7643 4.1.1: userName MUST be unique, duplicate POST must return 409 but was ${second.statusCode()}"
        assertScimType(second, "uniqueness")
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MUST, lines 1037-1038
    // This attribute is REQUIRED and is case insensitive.
    @SpecRef("RFC7643-4.1.1-e31286")
    def "RFC7643-4.1.1-e31286: userName: This attribute is REQUIRED and is case insensitive."() {
        given:
        String suffix = uniq()
        String userName = "Rfc7643UG_Case_${suffix}"

        when: "a User without userName, a User and a case-variant duplicate are submitted, then the User is looked up with a different case"
        def missing = postResource("/Users", [schemas: [USER_SCHEMA]])
        def first = postUser([userName: userName])
        def variant = postUser([userName: userName.toLowerCase()])
        def lookup = scimRequest().queryParam("filter", "userName eq \"${userName.toUpperCase()}\"").get("/Users")

        then: "userName is required and compared case-insensitively"
        assert missing.statusCode() == 400 : "RFC 7643 4.1.1: userName is REQUIRED, POST without it must return 400 but was ${missing.statusCode()}"
        assert first.statusCode() == 201 : "RFC 7643 4.1.1: POST must return 201 but was ${first.statusCode()}"
        assert variant.statusCode() == 409 : "RFC 7643 4.1.1: userName is case insensitive, a case-variant duplicate must return 409 but was ${variant.statusCode()}"
        assert lookup.statusCode() == 200 : "RFC 7643 4.1.1: filter must return 200 but was ${lookup.statusCode()}"
        assert lookup.jsonPath().getList("Resources.id").contains(first.jsonPath().getString("id")) : "RFC 7643 4.1.1: userName is case insensitive, an eq filter with different case must find the User"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MAY, lines 1041-1045
    // Service providers MAY return just the full name as a single string in the formatted sub-attribute, or they MAY return just the individual component attributes using the other sub-attributes, or they MAY return both.
    @SpecRef("RFC7643-4.1.1-704857")
    def "RFC7643-4.1.1-704857: name: Service providers MAY return just the full name as a single string in the formatted sub-attribute,..."() {
        given:
        String given1 = "Barbara"
        String family = "Jensen${uniq()}"

        when: "a User is created with component name attributes only, another with the formatted name only"
        def comp = mustCreateUser([name: [givenName: given1, familyName: family]])
        def fmt = mustCreateUser([name: [formatted: "Babs ${family}"]])
        Map compName = fetchUser(comp.id).name
        Map fmtName = fetchUser(fmt.id).name

        then: "the name is returned as formatted string, as components or as both, and both variants agree if both are present"
        assert compName : "RFC 7643 4.1.1: name sent with components must be returned (formatted, components or both)"
        assert compName.formatted || compName.givenName || compName.familyName : "RFC 7643 4.1.1: returned name must contain formatted or component sub-attributes: ${compName}"
        assert fmtName : "RFC 7643 4.1.1: name sent with formatted must be returned (formatted, components or both)"
        assert fmtName.formatted || fmtName.givenName || fmtName.familyName : "RFC 7643 4.1.1: returned name must contain formatted or component sub-attributes: ${fmtName}"
        if (compName.formatted && compName.givenName) {
            assert compName.formatted.contains(compName.givenName) : "RFC 7643 4.1.1: formatted '${compName.formatted}' must describe the same name as givenName '${compName.givenName}'"
        }
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), SHOULD, lines 1045-1047
    // If both variants are returned, they SHOULD be describing the same name, with the formatted name indicating how the component attributes should be combined.
    @SpecRef("RFC7643-4.1.1-98ee19")
    def "RFC7643-4.1.1-98ee19: name: If both variants are returned, they SHOULD be describing the same name, with the formatted name..."() {
        given:
        String family = "Jensen${uniq()}"

        when: "a User is created with the formatted name and all components"
        def created = mustCreateUser([name: [formatted: "Ms. Barbara J ${family}, III", givenName: "Barbara", familyName: family,
                                             middleName: "J", honorificPrefix: "Ms.", honorificSuffix: "III"]])
        Map name = fetchUser(created.id).name

        then: "if both variants are returned, the formatted name combines the components"
        if (!(name?.formatted && name?.givenName && name?.familyName)) {
            throw new TestAbortedException("Server does not return both the formatted name and the components: ${name}")
        }
        assert name.formatted.contains(name.givenName) : "RFC 7643 4.1.1: formatted '${name.formatted}' SHOULD contain givenName '${name.givenName}'"
        assert name.formatted.contains(name.familyName) : "RFC 7643 4.1.1: formatted '${name.formatted}' SHOULD contain familyName '${name.familyName}'"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MAY, lines 1080-1081
    // Each user returned MAY include a non-empty displayName value.
    @SpecRef("RFC7643-4.1.1-fa60be")
    def "RFC7643-4.1.1-fa60be: displayName: Each user returned MAY include a non-empty displayName value."() {
        given:
        String displayName = "Babs Jensen ${uniq()}"

        when: "a User is created with a displayName"
        def created = mustCreateUser([displayName: displayName])
        Map fetched = fetchUser(created.id)

        then: "if the server returns displayName it is non-empty"
        if (!fetched.containsKey("displayName")) {
            throw new TestAbortedException("Server does not return displayName (optional)")
        }
        assert fetched.displayName instanceof String && !fetched.displayName.isBlank() : "RFC 7643 4.1.1: a returned displayName must be non-empty but was '${fetched.displayName}'"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), SHOULD, lines 1081-1085
    // The name SHOULD be the full name of the User being described, if known (e.g., "Babs Jensen" or "Ms. Barbara J Jensen, III")
    @SpecRef("RFC7643-4.1.1-baafdd")
    def "RFC7643-4.1.1-baafdd: displayName: The name SHOULD be the full name of the User being described, if known (e.g., 'Babs Jensen'..."() {
        given:
        String displayName = "Ms. Barbara J Jensen${uniq()}, III"

        when: "a User is created with a full name as displayName"
        def created = mustCreateUser([displayName: displayName, name: [givenName: "Barbara", familyName: "Jensen"]])
        Map fetched = fetchUser(created.id)

        then: "the displayName keeps the full name"
        assert fetched.displayName == displayName : "RFC 7643 4.1.1: displayName SHOULD be the full name '${displayName}' but was '${fetched.displayName}'"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MAY, lines 1081-1085
    // but MAY be a username or handle, if that is all that is available (e.g., "bjensen").
    @SpecRef("RFC7643-4.1.1-e4e304")
    def "RFC7643-4.1.1-e4e304: displayName: but MAY be a username or handle, if that is all that is available (e.g., 'bjensen')."() {
        given:
        String handle = "bjensen${uniq()}"

        when: "a User is created whose displayName is a username/handle"
        def created = mustCreateUser([displayName: handle])
        Map fetched = fetchUser(created.id)

        then: "the handle is accepted and returned as displayName"
        assert fetched.displayName == handle : "RFC 7643 4.1.1: displayName MAY be a username or handle, expected '${handle}' but was '${fetched.displayName}'"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), SHOULD, lines 1085-1087
    // The value provided SHOULD be the primary textual label by which this User is normally displayed by the service provider when presenting it to end-users.
    @SpecRef("RFC7643-4.1.1-ba674c")
    def "RFC7643-4.1.1-ba674c: displayName: The value provided SHOULD be the primary textual label by which this User is normally..."() {
        given:
        String displayName = "Babs Jensen ${uniq()}"

        when: "a User with a displayName is made member of a Group"
        def user = mustCreateUser([displayName: displayName, name: [givenName: "Babs", familyName: "Jensen"]])
        def group = createGroup("rfc7643ug_grp_${uniq()}", [user.id])
        assert group.statusCode() == 201 : "RFC 7643 4.2: POST /Groups must return 201 but was ${group.statusCode()}"
        Map groupJson = group.jsonPath().getMap("")
        Map member = groupJson.members?.find { it.value == user.id }
        Map fetchedUser = fetchUser(user.id)

        then: "the service provider presents the User by its displayName"
        assert fetchedUser.displayName == displayName : "RFC 7643 4.1.1: displayName must be retained as the User's label, expected '${displayName}' but was '${fetchedUser.displayName}'"
        assert member != null : "RFC 7643 4.2: the created Group must list the User as member"
        if (!member.display) {
            throw new TestAbortedException("Server does not return a display label for Group members")
        }
        assert member.display == displayName : "RFC 7643 4.1.1: displayName SHOULD be the primary label by which the User is presented; Group member display was '${member.display}' instead of '${displayName}'"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), SHOULD NOT, lines 1091-1092
    // This attribute SHOULD NOT be used to represent a User's username (e.g., bjensen or mpepperidge).
    @SpecRef("RFC7643-4.1.1-cb46bb")
    def "RFC7643-4.1.1-cb46bb: nickName: This attribute SHOULD NOT be used to represent a User's username (e.g., bjensen or mpepperidge)."() {
        given:
        String userName = "rfc7643ug_nick_${uniq()}"

        when: "a User is created without nickName and another with an unrelated nickName"
        def plain = mustCreateUser([userName: userName])
        String nick = "Babsy${uniq()}"
        def withNick = mustCreateUser([nickName: nick])
        Map plainFetched = fetchUser(plain.id)
        Map nickFetched = fetchUser(withNick.id)

        then: "the server does not use nickName to represent the username"
        assert plainFetched.nickName == null || plainFetched.nickName != userName : "RFC 7643 4.1.1: nickName SHOULD NOT represent the userName but was '${plainFetched.nickName}'"
        assert nickFetched.nickName == nick : "RFC 7643 4.1.1: nickName must keep the client supplied value '${nick}' but was '${nickFetched.nickName}'"
        assert nickFetched.nickName != nickFetched.userName : "RFC 7643 4.1.1: nickName SHOULD NOT be replaced by the userName"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MAY, lines 1138-1140
    // For backward compatibility, servers MAY accept tags separated by an underscore character ("_", %x5F).
    @SpecRef("RFC7643-4.1.1-1c422c")
    def "RFC7643-4.1.1-1c422c: locale: For backward compatibility, servers MAY accept tags separated by an underscore character ('_', %x5F)."() {
        when: "a User is created with a locale tag using an underscore separator"
        def resp = postUser([locale: "en_US"])

        then: "if the server accepts it, the value is stored"
        if (resp.statusCode() == 400) {
            throw new TestAbortedException("Server does not accept underscore separated locale tags (optional)")
        }
        assert resp.statusCode() == 201 : "RFC 7643 4.1.1: POST must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))
        assert fetched.locale in ["en_US", "en-US"] : "RFC 7643 4.1.1: an accepted locale 'en_US' must be stored (as given or as en-US) but was '${fetched.locale}'"
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MUST NOT, lines 1185-1187
    // The cleartext value or the hashed value of a password SHALL NOT be returnable by a service provider.
    @SpecRef("RFC7643-4.1.1-6ee398")
    @RequiresCapability("changePassword")
    def "RFC7643-4.1.1-6ee398: password: The cleartext value or the hashed value of a password SHALL NOT be returnable by a service provider."() {
        given:
        String password = "Pw!${uniq()}Zq9"
        String userName = "rfc7643ug_pw_${uniq()}"

        when: "a User with a password is created and read back in several ways"
        def created = postUser([userName: userName, password: password])
        assert created.statusCode() == 201 : "RFC 7643 4.1.1: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        def byId = scimRequest().get("/Users/${id}")
        def byAttr = scimRequest().queryParam("attributes", "password,userName").get("/Users/${id}")
        def byFilter = scimRequest().queryParam("filter", "userName eq \"${userName}\"").get("/Users")
        def byExcluded = scimRequest().queryParam("excludedAttributes", "name").get("/Users/${id}")

        then: "neither the cleartext nor a hashed value is returned in any response"
        assertNoPassword(created, password, "POST response")
        assertNoPassword(byId, password, "GET by id")
        assertNoPassword(byAttr, password, "GET with attributes=password")
        assertNoPassword(byFilter, password, "GET with filter")
        assertNoPassword(byExcluded, password, "GET with excludedAttributes")
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MAY, lines 1211-1212
    // Testing for an equality match MAY be supported if there is an existing stored hashed value.
    @SpecRef("RFC7643-4.1.1-e0aeef")
    @RequiresCapability("changePassword")
    def "RFC7643-4.1.1-e0aeef: password: Testing for an equality match MAY be supported if there is an existing stored hashed value."() {
        given:
        String password = "Pw!${uniq()}Zq9"
        String userName = "rfc7643ug_pweq_${uniq()}"

        when: "a User with a password is created and an equality filter on password is attempted"
        def created = postUser([userName: userName, password: password])
        assert created.statusCode() == 201 : "RFC 7643 4.1.1: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        def right = scimRequest().queryParam("filter", "userName eq \"${userName}\" and password eq \"${password}\"").get("/Users")

        then: "the server either supports password equality matching (200 with match) or declines/omits password matching (200 empty or 400)"
        assert right.statusCode() in [200, 400] : "RFC 7643 4.1.1: password eq filter must return 200 or 400 but was ${right.statusCode()}"
        if (right.statusCode() == 200) {
            assertNoPassword(right, password, "password eq filter response")
            List resources = right.jsonPath().getList("Resources") ?: []
            if (resources.size() == 1) {
                assert right.jsonPath().getString("Resources[0].id") == id : "RFC 7643 4.1.1: password eq filter must match the User it belongs to"
                def wrong = scimRequest().queryParam("filter", "userName eq \"${userName}\" and password eq \"wrong-${uniq()}\"").get("/Users")
                assert wrong.statusCode() == 200 : "RFC 7643 4.1.1: password eq filter must return 200 but was ${wrong.statusCode()}"
                assert (wrong.jsonPath().getList("Resources") ?: []).isEmpty() : "RFC 7643 4.1.1: a wrong password must not match"
            }
        } else {
            assertScimError(right, 400)
        }
    }

    // RFC 7643 section 4.1.1 (Singular Attributes), MUST NOT, lines 1221-1224
    // The mutability of the password attribute is "writeOnly", indicating that the value MUST NOT be returned by a service provider in any form (the attribute characteristic "returned" is "never").
    @SpecRef("RFC7643-4.1.1-09c4fa")
    @RequiresCapability("changePassword")
    def "RFC7643-4.1.1-09c4fa: password: The mutability of the password attribute is 'writeOnly', indicating that the value MUST NOT be..."() {
        given:
        String password = "Pw!${uniq()}Zq9"

        when: "the User schema is read and a User is created, replaced and patched with a password"
        Map schema = schemaById(USER_SCHEMA)
        Map pwAttr = attrOf(schema, "password")
        def created = postUser([password: password])
        assert created.statusCode() == 201 : "RFC 7643 4.1.1: POST must return 201 but was ${created.statusCode()}"
        String id = created.jsonPath().getString("id")
        String userName = created.jsonPath().getString("userName")
        def put = scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: userName, password: password + "x"])).put("/Users/${id}")

        then: "password is writeOnly/never in the schema and never appears in responses"
        assert pwAttr != null : "RFC 7643 4.1.1: the User schema must define password"
        assert pwAttr.mutability == "writeOnly" : "RFC 7643 4.1.1: password mutability must be writeOnly but was ${pwAttr.mutability}"
        assert pwAttr.returned == "never" : "RFC 7643 4.1.1: password returned must be never but was ${pwAttr.returned}"
        assertNoPassword(created, password, "POST response")
        assert put.statusCode() == 200 : "RFC 7643 4.1.1: PUT must return 200 but was ${put.statusCode()}"
        assertNoPassword(put, password + "x", "PUT response")
        assertNoPassword(scimRequest().get("/Users/${id}"), password + "x", "GET response")
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), SHOULD, lines 1245-1247
    // Service providers SHOULD canonicalize the value according to [RFC5321], e.g., "bjensen@example.com" instead of "bjensen@EXAMPLE.COM".
    @SpecRef("RFC7643-4.1.2-4c3cb4")
    def "RFC7643-4.1.2-4c3cb4: emails: Service providers SHOULD canonicalize the value according to [RFC5321], e.g.,..."() {
        given:
        String local = "bjensen${uniq()}"

        when: "a User is created with an email whose domain is upper case"
        def created = mustCreateUser([emails: [[value: "${local}@EXAMPLE.com".toString(), type: "work"]]])
        Map fetched = fetchUser(created.id)

        then: "the email value is canonicalized"
        assert fetched.emails : "RFC 7643 4.1.2: emails must be returned"
        assert fetched.emails[0].value == "${local}@example.com".toString() : "RFC 7643 4.1.2: email SHOULD be canonicalized to '${local}@example.com' but was '${fetched.emails[0].value}'"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1247-1248
    // The "display" sub-attribute MAY be used to return the canonicalized representation of the email value.
    @SpecRef("RFC7643-4.1.2-3d956a")
    def "RFC7643-4.1.2-3d956a: emails: The 'display' sub-attribute MAY be used to return the canonicalized representation of the email value."() {
        given:
        String value = "bjensen${uniq()}@EXAMPLE.com"

        when: "a User is created with an email without display"
        def created = mustCreateUser([emails: [[value: value, type: "work"]]])
        Map fetched = fetchUser(created.id)
        Map email = fetched.emails?.getAt(0)

        then: "if the server returns a display for it, it is the canonicalized representation of the value"
        assert email != null : "RFC 7643 4.1.2: emails must be returned"
        if (email.display != null) {
            assert email.display.equalsIgnoreCase(value) : "RFC 7643 4.1.2: email display '${email.display}' must be a canonicalized representation of '${value}'"
        }
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1250-1253
    // The user interface should encourage the use of basic values of "work", "home", and "other" and MAY allow additional type values to be used at the discretion of SCIM clients.
    @SpecRef("RFC7643-4.1.2-56e4f4")
    def "RFC7643-4.1.2-56e4f4: emails: The user interface should encourage the use of basic values of 'work', 'home', and 'other' and MAY..."() {
        given:
        String value = "bjensen${uniq()}@example.com"

        when: "a User is created with an email of a custom type"
        def resp = postUser([emails: [[value: value, type: "w3custom"]]])

        then: "if additional type values are allowed, the type is stored"
        assertCustomTypeStored(resp, "emails", "w3custom")
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), SHOULD, lines 1258-1259
    // Service providers SHOULD canonicalize the value according to [RFC3966] format, when appropriate.
    @SpecRef("RFC7643-4.1.2-b36a3c")
    def "RFC7643-4.1.2-b36a3c: phoneNumbers: Service providers SHOULD canonicalize the value according to [RFC3966] format, when appropriate."() {
        when: "a User is created with a phone number in a free format"
        def created = mustCreateUser([phoneNumbers: [[value: "+1 (201) 555-0123", type: "work"]]])
        Map fetched = fetchUser(created.id)

        then: "the phone number is canonicalized to the RFC 3966 format"
        assert fetched.phoneNumbers : "RFC 7643 4.1.2: phoneNumbers must be returned"
        String value = fetched.phoneNumbers[0].value
        assert value.startsWith("tel:") && !(value =~ /\s/) : "RFC 7643 4.1.2: phone number SHOULD be canonicalized to RFC 3966 (e.g. tel:+1-201-555-0123) but was '${value}'"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1259-1261
    // The "display" sub-attribute MAY be used to return the canonicalized representation of the phone number value.
    @SpecRef("RFC7643-4.1.2-18a282")
    def "RFC7643-4.1.2-18a282: phoneNumbers: The 'display' sub-attribute MAY be used to return the canonicalized representation of the..."() {
        when: "a User is created with a phone number without display"
        def created = mustCreateUser([phoneNumbers: [[value: "tel:+1-201-555-0123", type: "work"]]])
        Map phone = fetchUser(created.id).phoneNumbers?.getAt(0)

        then: "if the server returns a display, it represents the same phone number"
        assert phone != null : "RFC 7643 4.1.2: phoneNumbers must be returned"
        if (phone.display != null) {
            assert phone.display.replaceAll("[^0-9]", "") == "12015550123" : "RFC 7643 4.1.2: phoneNumbers display '${phone.display}' must be a representation of the phone number"
        }
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1261-1264
    // The sub-attribute "type" often has typical values of "work", "home", "mobile", "fax", "pager", and "other" and MAY allow more types to be defined by the SCIM clients.
    @SpecRef("RFC7643-4.1.2-7d7aff")
    def "RFC7643-4.1.2-7d7aff: phoneNumbers: The sub-attribute 'type' often has typical values of 'work', 'home', 'mobile', 'fax',..."() {
        when: "a User is created with a phone number of a custom type"
        def resp = postUser([phoneNumbers: [[value: "tel:+1-201-555-0123", type: "w3custom"]]])

        then: "if additional types are allowed, the type is stored"
        assertCustomTypeStored(resp, "phoneNumbers", "w3custom")
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), SHOULD, lines 1267-1270
    // No official canonicalization rules exist for all instant messaging addresses, but service providers SHOULD, when appropriate, remove all whitespace and convert the address to lowercase.
    @SpecRef("RFC7643-4.1.2-cc4d0a")
    def "RFC7643-4.1.2-cc4d0a: ims: No official canonicalization rules exist for all instant messaging addresses, but service providers..."() {
        given:
        String id1 = uniq()

        when: "a User is created with an IM address containing whitespace and upper case"
        def created = mustCreateUser([ims: [[value: " Foo ${id1}@Example.COM ".toString(), type: "xmpp"]]])
        Map fetched = fetchUser(created.id)

        then: "whitespace is removed and the address is lower-cased"
        assert fetched.ims : "RFC 7643 4.1.2: ims must be returned"
        assert fetched.ims[0].value == "foo${id1}@example.com".toString() : "RFC 7643 4.1.2: IM address SHOULD be canonicalized to 'foo${id1}@example.com' but was '${fetched.ims[0].value}'"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), SHOULD, lines 1270-1274
    // The "type" sub-attribute SHOULD take one of the following values: "aim", "gtalk", "icq", "xmpp", "msn", "skype", "qq", "yahoo", or "other" (representing currently popular IM services at the time of this writing).
    @SpecRef("RFC7643-4.1.2-7df055")
    def "RFC7643-4.1.2-7df055: ims: The 'type' sub-attribute SHOULD take one of the following values: 'aim', 'gtalk', 'icq', 'xmpp',..."() {
        given:
        List<String> types = ["aim", "gtalk", "icq", "xmpp", "msn", "skype", "qq", "yahoo", "other"]
        String id1 = uniq()

        when: "a User is created with one IM address per recommended type"
        def resp = postUser([ims: types.collect { [value: "im${id1}${it}".toString(), type: it] }])

        then: "all recommended type values are accepted and returned"
        assert resp.statusCode() == 201 : "RFC 7643 4.1.2: ims with the recommended types must be accepted with 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))
        assert (fetched.ims*.type as Set) == (types as Set) : "RFC 7643 4.1.2: ims types ${types} must be returned but got ${fetched.ims*.type}"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1274-1276
    // Service providers MAY add further values if new IM services are introduced and MAY specify more detailed canonicalization rules for each possible value.
    @SpecRef("RFC7643-4.1.2-45f783")
    def "RFC7643-4.1.2-45f783: ims: Service providers MAY add further values if new IM services are introduced and MAY specify more..."() {
        when: "a User is created with an IM address of a type outside the recommended list"
        def resp = postUser([ims: [[value: "im${uniq()}", type: "w3newservice"]]])

        then: "if further type values are allowed, the type is stored"
        assertCustomTypeStored(resp, "ims", "w3newservice")
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1283-1285
    // Service providers MAY return the same image in different sizes, although it is recognized that no standard for describing images of various sizes currently exists.
    @SpecRef("RFC7643-4.1.2-2add74")
    def "RFC7643-4.1.2-2add74: photos: Service providers MAY return the same image in different sizes, although it is recognized that no..."() {
        given:
        String id1 = uniq()

        when: "a User is created with the same image in two sizes"
        def resp = postUser([photos: [[value: "https://example.com/${id1}/photo.png", type: "photo"],
                                      [value: "https://example.com/${id1}/thumb.png", type: "thumbnail"]]])
        assert resp.statusCode() == 201 : "RFC 7643 4.1.2: POST with several photos must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))

        then: "if the server keeps several images, both are returned"
        if ((fetched.photos?.size() ?: 0) < 2) {
            throw new TestAbortedException("Server does not keep several sizes of the same image (optional)")
        }
        assert (fetched.photos*.type as Set) == (["photo", "thumbnail"] as Set) : "RFC 7643 4.1.2: both photo sizes must be returned: ${fetched.photos}"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1304-1305
    // All sub-attributes are OPTIONAL.
    @SpecRef("RFC7643-4.1.2-97ccf3")
    def "RFC7643-4.1.2-97ccf3: addresses: All sub-attributes are OPTIONAL."() {
        given:
        Map schema = schemaById(USER_SCHEMA)
        Map addresses = attrOf(schema, "addresses")
        List<Map> variants = [[formatted: "1 Main St"], [streetAddress: "1 Main St"], [locality: "Springfield"], [region: "IL"],
                              [postalCode: "62701"], [country: "US"], [type: "work"]]

        when: "Users are created with an address containing only one sub-attribute each"
        List<Response> responses = variants.collect { postUser([addresses: [it]]) }

        then: "no address sub-attribute is required"
        addresses.subAttributes.each { sub ->
            assert sub.required != true : "RFC 7643 4.1.2: address sub-attribute ${sub.name} must not be required in the schema"
        }
        responses.eachWithIndex { r, i ->
            assert r.statusCode() == 201 : "RFC 7643 4.1.2: an address with only ${variants[i].keySet()} must be accepted with 201 but was ${r.statusCode()}"
        }
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1308-1308
    // This attribute MAY contain newlines.
    @SpecRef("RFC7643-4.1.2-4975a0")
    def "RFC7643-4.1.2-4975a0: addresses.formatted: This attribute MAY contain newlines."() {
        given:
        String formatted = "100 Universal City Plaza\nHollywood, CA 91608 USA"

        when: "a User is created with a multi-line formatted address"
        def resp = postUser([addresses: [[formatted: formatted, type: "work"]]])

        then: "the newline is accepted and preserved"
        assert resp.statusCode() == 201 : "RFC 7643 4.1.2: addresses.formatted may contain newlines, POST must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))
        assert fetched.addresses?.getAt(0)?.formatted == formatted : "RFC 7643 4.1.2: formatted address with newline must be preserved but was '${fetched.addresses?.getAt(0)?.formatted}'"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1312-1313
    // This attribute MAY contain newlines.
    @SpecRef("RFC7643-4.1.2-e10eec")
    def "RFC7643-4.1.2-e10eec: addresses.streetAddress: This attribute MAY contain newlines."() {
        given:
        String street = "100 Universal City Plaza\nBuilding 1"

        when: "a User is created with a multi-line streetAddress"
        def resp = postUser([addresses: [[streetAddress: street, type: "work"]]])

        then: "the newline is accepted and preserved"
        assert resp.statusCode() == 201 : "RFC 7643 4.1.2: addresses.streetAddress may contain newlines, POST must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))
        assert fetched.addresses?.getAt(0)?.streetAddress == street : "RFC 7643 4.1.2: streetAddress with newline must be preserved but was '${fetched.addresses?.getAt(0)?.streetAddress}'"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MUST, lines 1321-1323
    // When specified, the value MUST be in ISO 3166-1 "alpha-2" code format [ISO3166]; e.g., the United States and Sweden are "US" and "SE", respectively.
    @SpecRef("RFC7643-4.1.2-cfee7b")
    def "RFC7643-4.1.2-cfee7b: addresses.country: When specified, the value MUST be in ISO 3166-1 'alpha-2' code format [ISO3166]; e.g.,..."() {
        when: "Users are created with an alpha-2 country, and with country values that are not alpha-2"
        def valid = postUser([addresses: [[country: "SE", type: "work"]]])
        def long3 = postUser([addresses: [[country: "USA", type: "work"]]])
        def name = postUser([addresses: [[country: "Sweden", type: "work"]]])

        then: "alpha-2 is stored and a non alpha-2 value is never stored as country"
        assert valid.statusCode() == 201 : "RFC 7643 4.1.2: POST with country SE must return 201 but was ${valid.statusCode()}"
        assert fetchUser(valid.jsonPath().getString("id")).addresses[0].country == "SE" : "RFC 7643 4.1.2: alpha-2 country SE must be stored"
        [long3, name].each { r ->
            if (r.statusCode() == 201) {
                String stored = fetchUser(r.jsonPath().getString("id")).addresses?.getAt(0)?.country
                assert stored == null || stored ==~ /[A-Z]{2}/ : "RFC 7643 4.1.2: a stored country MUST be ISO 3166-1 alpha-2 but was '${stored}'"
            } else {
                assert r.statusCode() == 400 : "RFC 7643 4.1.2: a non alpha-2 country must be rejected with 400 but was ${r.statusCode()}"
            }
        }
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), SHOULD, lines 1334-1337
    // Direct group membership indicates that the user is directly associated with the group and SHOULD indicate that clients may modify membership through the "Group" resource.
    @SpecRef("RFC7643-4.1.2-92c41e")
    def "RFC7643-4.1.2-92c41e: groups: Direct group membership indicates that the user is directly associated with the group and SHOULD..."() {
        when: "a User is made a member of a Group through the Group resource"
        def user = mustCreateUser()
        def group = createGroup("rfc7643ug_grp_${uniq()}", [user.id])
        assert group.statusCode() == 201 : "RFC 7643 4.2: POST /Groups must return 201 but was ${group.statusCode()}"
        String groupId = group.jsonPath().getString("id")
        Map entry = fetchUser(user.id).groups?.find { it.value == groupId }

        then: "the membership is marked direct"
        assert entry != null : "RFC 7643 4.1.2: the User's groups must list the Group ${groupId}"
        assert entry.type == "direct" : "RFC 7643 4.1.2: membership added through the Group resource SHOULD have type 'direct' but was '${entry.type}'"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MAY, lines 1337-1341
    // Indirect membership indicates that user membership is transitive or dynamic and implies that clients cannot modify indirect group membership through the "Group" resource but MAY modify direct group membership through the "Group" resource, which may influence indirect memberships.
    @SpecRef("RFC7643-4.1.2-8bc830")
    def "RFC7643-4.1.2-8bc830: groups: Indirect membership indicates that user membership is transitive or dynamic and implies that..."() {
        when: "a User is a direct member of an inner Group that is itself a member of an outer Group"
        def user = mustCreateUser()
        def inner = createGroup("rfc7643ug_in_${uniq()}", [user.id])
        assert inner.statusCode() == 201 : "RFC 7643 4.2: POST /Groups must return 201 but was ${inner.statusCode()}"
        String innerId = inner.jsonPath().getString("id")
        def outer = postResource("/Groups", [schemas: [GROUP_SCHEMA], displayName: "rfc7643ug_out_${uniq()}".toString(),
                members: [[value: innerId, type: "Group"]]])

        then: "direct group membership can be modified via Group, and if indirect membership is reported it is typed indirect"
        assert outer.statusCode() in [201, 400] : "RFC 7643 4.1.2: nested Group creation must return 201 or 400 but was ${outer.statusCode()}"
        if (outer.statusCode() == 201) {
            String outerId = outer.jsonPath().getString("id")
            Map indirect = fetchUser(user.id).groups?.find { it.value == outerId }
            if (indirect != null) {
                assert indirect.type == "indirect" : "RFC 7643 4.1.2: transitive membership must have type 'indirect' but was '${indirect.type}'"
                def outerFetched = scimRequest().get("/Groups/${outerId}")
                assert !(outerFetched.jsonPath().getList("members.value") ?: []).contains(user.id) : "RFC 7643 4.1.2: indirect membership must not appear as direct member of the outer Group"
            }
        }
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MUST, lines 1341-1353
    // If the SCIM service provider exposes a "Group" resource, the "value" sub-attribute MUST be the "id", and the "$ref" sub-attribute must be the URI of the corresponding "Group" resources to which the user belongs.
    @SpecRef("RFC7643-4.1.2-8a537d")
    def "RFC7643-4.1.2-8a537d: groups: If the SCIM service provider exposes a 'Group' resource, the 'value' sub-attribute MUST be the..."() {
        when: "a User becomes member of a Group"
        def user = mustCreateUser()
        def group = createGroup("rfc7643ug_grp_${uniq()}", [user.id])
        assert group.statusCode() == 201 : "RFC 7643 4.2: POST /Groups must return 201 but was ${group.statusCode()}"
        String groupId = group.jsonPath().getString("id")
        String location = group.jsonPath().getString("meta.location")
        Map fetchedUser = fetchUser(user.id)
        Map entry = fetchedUser.groups?.find { it.value == groupId }

        then: "groups.value is the Group id and groups.ref is the Group URI"
        assert entry != null : "RFC 7643 4.1.2: groups.value must be the id of the Group ${groupId}, groups were ${fetchedUser.groups}"
        assert entry['$ref'] : "RFC 7643 4.1.2: groups.\$ref must be the URI of the Group"
        assert entry['$ref'].endsWith("/Groups/${groupId}") : "RFC 7643 4.1.2: groups.\$ref '${entry['$ref']}' must be the URI of Group ${groupId}"
        assert entry['$ref'] == location : "RFC 7643 4.1.2: groups.\$ref '${entry['$ref']}' must equal the Group's meta.location '${location}'"
        assert scimRequest().get("/Groups/${groupId}").statusCode() == 200 : "RFC 7643 4.1.2: the referenced Group must be retrievable"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MUST, lines 1353-1355
    // Since this attribute has a mutability of "readOnly", group membership changes MUST be applied via the "Group" Resource (Section 4.2).
    @SpecRef("RFC7643-4.1.2-1c156a")
    def "RFC7643-4.1.2-1c156a: groups: Since this attribute has a mutability of 'readOnly', group membership changes MUST be applied via..."() {
        given:
        def user = mustCreateUser()
        def other = mustCreateUser()
        def group = createGroup("rfc7643ug_grp_${uniq()}", [other.id])
        assert group.statusCode() == 201 : "RFC 7643 4.2: POST /Groups must return 201 but was ${group.statusCode()}"
        String groupId = group.jsonPath().getString("id")

        when: "membership is attempted through the User resource, then through the Group resource"
        def put = scimRequest().body(JsonOutput.toJson([schemas: [USER_SCHEMA], userName: user.userName,
                groups: [[value: groupId]]])).put("/Users/${user.id}")
        List afterPutUser = fetchUser(user.id).groups?.collect { it.value } ?: []
        List afterPutGroup = scimRequest().get("/Groups/${groupId}").jsonPath().getList("members.value") ?: []
        def viaGroup = scimRequest().body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: group.jsonPath().getString("displayName"),
                members: [[value: other.id], [value: user.id]]])).put("/Groups/${groupId}")
        List afterGroupUser = fetchUser(user.id).groups?.collect { it.value } ?: []

        then: "the User resource cannot change membership, the Group resource can"
        assert !afterPutUser.contains(groupId) : "RFC 7643 4.1.2: groups is readOnly, PUT /Users (status ${put.statusCode()}) must not add the User to the Group"
        assert !afterPutGroup.contains(user.id) : "RFC 7643 4.1.2: groups is readOnly, PUT /Users must not add the User to the Group's members"
        assert viaGroup.statusCode() == 200 : "RFC 7643 4.1.2: PUT /Groups must return 200 but was ${viaGroup.statusCode()}"
        assert afterGroupUser.contains(groupId) : "RFC 7643 4.1.2: membership changes MUST be applied via the Group resource; User groups were ${afterGroupUser}"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MUST, lines 1377-1379
    // Each value contains exactly one DER-encoded X.509 certificate (see Section 4 of [RFC5280]), which MUST be base64 encoded per Section 4 of [RFC4648].
    @SpecRef("RFC7643-4.1.2-c7702f")
    def "RFC7643-4.1.2-c7702f: x509Certificates: Each value contains exactly one DER-encoded X.509 certificate (see Section 4 of..."() {
        given:
        String cert = "MIICCDCCAa4CCQDeKZvzAPOL9TAKBggqhkjOPQQDAjASMRAwDgYDVQQDDAd3M3Rlc3QxMB4XDTI2MTEwMjEyNDcwMVoXDTM2MTAzMDEyNDcwMVowEjEQMA4GA1UEAwwHdzN0ZXN0MTCCAUswggEDBgcqhkjOPQIBMIH3AgEBMCwGByqGSM49AQECIQD/////AAAAAQAAAAAAAAAAAAAAAP///////////////zBbBCD/////AAAAAQAAAAAAAAAAAAAAAP///////////////AQgWsY12Ko6k+ez671VdpiGvGUdBrDMU7D2O848PifSYEsDFQDEnTYIhucEk2pmeOETnSa3gZ9+kARBBGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfUCIQD/////AAAAAP//////////vOb6racXnoTzucrC/GMlUQIBAQNCAAS1vRecb7ocHmjrU0jcy4S/eawUVpdQTVvbjoNJ14tn0gxd8kHS/PMqNEF47d6khJbXQjHJPCaOdl8ZD8HUn1c0MAoGCCqGSM49BAMCA0gAMEUCIQCIPAb4Vzo4kQ30cgTitjMfggwmFEEHyV6kfnuexZTYRAIgV/enB8JYIxv98Pshr03BiqTdlP/djuLJx+J7NArAylY="

        when: "a User is created with a base64 DER certificate and another with a value that is not base64"
        def valid = postUser([x509Certificates: [[value: cert]]])
        def invalid = postUser([x509Certificates: [[value: "!!not base64 ~~"]]])

        then: "the base64 certificate is stored unchanged and the invalid value is rejected"
        assert valid.statusCode() == 201 : "RFC 7643 4.1.2: a base64 encoded DER certificate must be accepted with 201 but was ${valid.statusCode()}"
        assert fetchUser(valid.jsonPath().getString("id")).x509Certificates?.getAt(0)?.value == cert : "RFC 7643 4.1.2: the base64 certificate must be returned unchanged"
        assert invalid.statusCode() == 400 : "RFC 7643 4.1.2: an x509Certificates value MUST be base64 encoded, a non-base64 value must be rejected with 400 but was ${invalid.statusCode()}"
    }

    // RFC 7643 section 4.1.2 (Multi-Valued Attributes), MUST NOT, lines 1379-1381
    // A single value MUST NOT contain multiple certificates and so does not contain the encoding "SEQUENCE OF Certificate" in any guise.
    @SpecRef("RFC7643-4.1.2-80b979")
    def "RFC7643-4.1.2-80b979: x509Certificates: A single value MUST NOT contain multiple certificates and so does not contain the..."() {
        given:
        String single = "MIICCDCCAa4CCQDeKZvzAPOL9TAKBggqhkjOPQQDAjASMRAwDgYDVQQDDAd3M3Rlc3QxMB4XDTI2MTEwMjEyNDcwMVoXDTM2MTAzMDEyNDcwMVowEjEQMA4GA1UEAwwHdzN0ZXN0MTCCAUswggEDBgcqhkjOPQIBMIH3AgEBMCwGByqGSM49AQECIQD/////AAAAAQAAAAAAAAAAAAAAAP///////////////zBbBCD/////AAAAAQAAAAAAAAAAAAAAAP///////////////AQgWsY12Ko6k+ez671VdpiGvGUdBrDMU7D2O848PifSYEsDFQDEnTYIhucEk2pmeOETnSa3gZ9+kARBBGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfUCIQD/////AAAAAP//////////vOb6racXnoTzucrC/GMlUQIBAQNCAAS1vRecb7ocHmjrU0jcy4S/eawUVpdQTVvbjoNJ14tn0gxd8kHS/PMqNEF47d6khJbXQjHJPCaOdl8ZD8HUn1c0MAoGCCqGSM49BAMCA0gAMEUCIQCIPAb4Vzo4kQ30cgTitjMfggwmFEEHyV6kfnuexZTYRAIgV/enB8JYIxv98Pshr03BiqTdlP/djuLJx+J7NArAylY="
        String two = "MIICCDCCAa4CCQDeKZvzAPOL9TAKBggqhkjOPQQDAjASMRAwDgYDVQQDDAd3M3Rlc3QxMB4XDTI2MTEwMjEyNDcwMVoXDTM2MTAzMDEyNDcwMVowEjEQMA4GA1UEAwwHdzN0ZXN0MTCCAUswggEDBgcqhkjOPQIBMIH3AgEBMCwGByqGSM49AQECIQD/////AAAAAQAAAAAAAAAAAAAAAP///////////////zBbBCD/////AAAAAQAAAAAAAAAAAAAAAP///////////////AQgWsY12Ko6k+ez671VdpiGvGUdBrDMU7D2O848PifSYEsDFQDEnTYIhucEk2pmeOETnSa3gZ9+kARBBGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfUCIQD/////AAAAAP//////////vOb6racXnoTzucrC/GMlUQIBAQNCAAS1vRecb7ocHmjrU0jcy4S/eawUVpdQTVvbjoNJ14tn0gxd8kHS/PMqNEF47d6khJbXQjHJPCaOdl8ZD8HUn1c0MAoGCCqGSM49BAMCA0gAMEUCIQCIPAb4Vzo4kQ30cgTitjMfggwmFEEHyV6kfnuexZTYRAIgV/enB8JYIxv98Pshr03BiqTdlP/djuLJx+J7NArAylYwggIIMIIBrgIJAM5KmDX55RZqMAoGCCqGSM49BAMCMBIxEDAOBgNVBAMMB3czdGVzdDIwHhcNMjYxMTAyMTI0NzAyWhcNMzYxMDMwMTI0NzAyWjASMRAwDgYDVQQDDAd3M3Rlc3QyMIIBSzCCAQMGByqGSM49AgEwgfcCAQEwLAYHKoZIzj0BAQIhAP////8AAAABAAAAAAAAAAAAAAAA////////////////MFsEIP////8AAAABAAAAAAAAAAAAAAAA///////////////8BCBaxjXYqjqT57PrvVV2mIa8ZR0GsMxTsPY7zjw+J9JgSwMVAMSdNgiG5wSTamZ44ROdJreBn36QBEEEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li/hp/m47n60p8D54WK84zV2sxXs7LtkBoN79R9QIhAP////8AAAAA//////////+85vqtpxeehPO5ysL8YyVRAgEBA0IABIVa6lEUs20qL8AkGXWXUKstgtpcVKczNryye8Xw9JjdlQfx5cIKDPTwg7VX4Wz8vkG7NHFEtV9VmsjIAvO3l4AwCgYIKoZIzj0EAwIDSAAwRQIhAJwpe30OM1wTei3faa/1rxOBu2unp4h5xcavYJauxLqQAiBXlF1WWG7war/vEu+O9sSG1aro+E24Mm1mAqHqnGhU5Q=="

        when: "a User is created with one certificate and another with two concatenated certificates in a single value"
        def one = postUser([x509Certificates: [[value: single]]])
        def multi = postUser([x509Certificates: [[value: two]]])

        then: "a single certificate is accepted, a value with multiple certificates is not stored"
        assert one.statusCode() == 201 : "RFC 7643 4.1.2: a single certificate must be accepted with 201 but was ${one.statusCode()}"
        assert multi.statusCode() == 400 : "RFC 7643 4.1.2: a single value MUST NOT contain multiple certificates, the request must be rejected with 400 but was ${multi.statusCode()}"
    }

    // RFC 7643 section 4.2 ("Group" Resource Schema), MUST, lines 1411-1411
    // REQUIRED.
    @SpecRef("RFC7643-4.2-36465a")
    def "RFC7643-4.2-36465a: displayName: REQUIRED."() {
        given:
        Map schema = schemaById(GROUP_SCHEMA)

        when: "Groups are created without and with an empty displayName and with a displayName"
        def missing = postResource("/Groups", [schemas: [GROUP_SCHEMA]])
        def empty = postResource("/Groups", [schemas: [GROUP_SCHEMA], displayName: ""])
        def valid = createGroup("rfc7643ug_grp_${uniq()}")

        then: "displayName is required"
        assert attrOf(schema, "displayName")?.required == true : "RFC 7643 4.2: Group displayName must be required in the schema"
        assert missing.statusCode() == 400 : "RFC 7643 4.2: a Group without displayName must be rejected with 400 but was ${missing.statusCode()}"
        assert empty.statusCode() == 400 : "RFC 7643 4.2: a Group with an empty displayName must be rejected with 400 but was ${empty.statusCode()}"
        assert valid.statusCode() == 201 : "RFC 7643 4.2: a Group with displayName must be accepted with 201 but was ${valid.statusCode()}"
    }

    // RFC 7643 section 4.2 ("Group" Resource Schema), MAY, lines 1417-1418
    // While values MAY be added or removed, sub-attributes of members are "immutable".
    @SpecRef("RFC7643-4.2-7e6adc")
    def "RFC7643-4.2-7e6adc: members: While values MAY be added or removed, sub-attributes of members are 'immutable'."() {
        given:
        def u1 = mustCreateUser()
        def u2 = mustCreateUser()
        Map members = attrOf(schemaById(GROUP_SCHEMA), "members")
        def group = createGroup("rfc7643ug_grp_${uniq()}", [u1.id])
        assert group.statusCode() == 201 : "RFC 7643 4.2: POST /Groups must return 201 but was ${group.statusCode()}"
        String groupId = group.jsonPath().getString("id")
        String displayName = group.jsonPath().getString("displayName")

        when: "a member is added and then another one removed through the Group resource"
        def added = scimRequest().body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: displayName,
                members: [[value: u1.id], [value: u2.id]]])).put("/Groups/${groupId}")
        List afterAdd = scimRequest().get("/Groups/${groupId}").jsonPath().getList("members.value") ?: []
        def removed = scimRequest().body(JsonOutput.toJson([schemas: [GROUP_SCHEMA], displayName: displayName,
                members: [[value: u2.id]]])).put("/Groups/${groupId}")
        List afterRemove = scimRequest().get("/Groups/${groupId}").jsonPath().getList("members.value") ?: []

        then: "values can be added and removed while member sub-attributes are declared immutable"
        assert members.subAttributes.find { it.name == "value" }.mutability == "immutable" : "RFC 7643 4.2: members.value must be immutable"
        assert added.statusCode() == 200 : "RFC 7643 4.2: adding a member must return 200 but was ${added.statusCode()}"
        assert (afterAdd as Set) == ([u1.id, u2.id] as Set) : "RFC 7643 4.2: members may be added, got ${afterAdd}"
        assert removed.statusCode() == 200 : "RFC 7643 4.2: removing a member must return 200 but was ${removed.statusCode()}"
        assert afterRemove == [u2.id] : "RFC 7643 4.2: members may be removed, got ${afterRemove}"
    }

    // RFC 7643 section 4.2 ("Group" Resource Schema), MAY, lines 1423-1426
    // Service providers MAY require clients to provide a non-empty value by setting the "required" attribute characteristic of a sub-attribute of the "members" attribute in the "Group" resource schema.
    @SpecRef("RFC7643-4.2-221a0c")
    def "RFC7643-4.2-221a0c: members: Service providers MAY require clients to provide a non-empty value by setting the 'required'..."() {
        given:
        Map members = attrOf(schemaById(GROUP_SCHEMA), "members")
        assert members != null && members.subAttributes instanceof List : "RFC 7643 4.2: Group schema must define members.subAttributes"
        List<String> required = members.subAttributes.findAll { it.required == true }.collect { it.name }

        expect: "if the schema declares a required sub-attribute on members, omitting it is rejected with 400; otherwise all sub-attributes have valid boolean required flags"
        if (!required.isEmpty()) {
            String omitted = required[0]
            Map member = [value: mustCreateUser().id, type: "User", display: "w3"]
            member.remove(omitted)
            def resp = postResource("/Groups", [schemas: [GROUP_SCHEMA], displayName: "rfc7643ug_grp_${uniq()}".toString(), members: [member]])
            assert resp.statusCode() == 400 : "RFC 7643 4.2: members.${omitted} is required by the schema, a member without it must be rejected with 400 but was ${resp.statusCode()}"
        } else {
            members.subAttributes.each { Map sub ->
                assert sub.required == null || sub.required == false
            }
        }
    }

    // RFC 7643 section 4.3 (Enterprise User Schema Extension), SHOULD, lines 1469-1469
    // RECOMMENDED.
    @SpecRef("RFC7643-4.3-fcc0dc")
    @RequiresCapability("enterprise")
    def "RFC7643-4.3-fcc0dc: manager.value: RECOMMENDED."() {
        when: "a User is created with an enterprise manager referenced by value only"
        def manager = mustCreateUser()
        def resp = postUser([schemas: [USER_SCHEMA, ENTERPRISE_USER_SCHEMA], (ENTERPRISE_USER_SCHEMA): [manager: [value: manager.id]]])
        assert resp.statusCode() == 201 : "RFC 7643 4.3: POST with enterprise manager.value must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))

        then: "manager.value is accepted and returned as the manager's id"
        assert fetched[ENTERPRISE_USER_SCHEMA]?.manager?.value == manager.id : "RFC 7643 4.3: manager.value SHOULD be supported and be the manager's id ${manager.id} but was ${fetched[ENTERPRISE_USER_SCHEMA]?.manager}"
    }

    // RFC 7643 section 4.3 (Enterprise User Schema Extension), SHOULD, lines 1472-1472
    // RECOMMENDED.
    @SpecRef("RFC7643-4.3-aa60b9")
    @RequiresCapability("enterprise")
    def "RFC7643-4.3-aa60b9: manager.'ref: RECOMMENDED."() {
        when: "a User is created with an enterprise manager given by value and ref"
        def manager = mustCreateUser()
        String managerUri = manager.meta.location
        def resp = postUser([schemas: [USER_SCHEMA, ENTERPRISE_USER_SCHEMA],
                (ENTERPRISE_USER_SCHEMA): [manager: [value: manager.id, '$ref': managerUri]]])
        assert resp.statusCode() == 201 : "RFC 7643 4.3: POST with enterprise manager.\$ref must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))

        then: "manager.ref is returned as the URI of the manager"
        assert fetched[ENTERPRISE_USER_SCHEMA]?.manager?.get('$ref') == managerUri : "RFC 7643 4.3: manager.\$ref SHOULD be supported and be '${managerUri}' but was ${fetched[ENTERPRISE_USER_SCHEMA]?.manager}"
    }

    // RFC 7643 section 4.3 (Enterprise User Schema Extension), MAY, lines 1474-1475
    // This attribute is OPTIONAL, and mutability is "readOnly".
    @SpecRef("RFC7643-4.3-5afea2")
    @RequiresCapability("enterprise")
    def "RFC7643-4.3-5afea2: manager.displayName: This attribute is OPTIONAL, and mutability is 'readOnly'."() {
        given:
        Map extension = schemaById(ENTERPRISE_USER_SCHEMA)
        Map displayNameAttr = attrOf(extension, "manager")?.subAttributes?.find { it.name == "displayName" }
        String bogus = "Bogus ${uniq()}"

        when: "a User is created whose manager.displayName is set by the client"
        def manager = mustCreateUser()
        def resp = postUser([schemas: [USER_SCHEMA, ENTERPRISE_USER_SCHEMA],
                (ENTERPRISE_USER_SCHEMA): [manager: [value: manager.id, displayName: bogus]]])
        assert resp.statusCode() == 201 : "RFC 7643 4.3: POST must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))

        then: "manager.displayName is readOnly and a client supplied value is not stored"
        assert displayNameAttr != null : "RFC 7643 4.3: the enterprise schema must define manager.displayName"
        assert displayNameAttr.mutability == "readOnly" : "RFC 7643 4.3: manager.displayName mutability must be readOnly but was ${displayNameAttr.mutability}"
        assert displayNameAttr.required != true : "RFC 7643 4.3: manager.displayName is OPTIONAL"
        assert fetched[ENTERPRISE_USER_SCHEMA]?.manager?.displayName != bogus : "RFC 7643 4.3: readOnly manager.displayName must not take the client supplied value '${bogus}'"
    }


    // ---- helpers (private to this spec) ----

    private static String uniq() {
        UUID.randomUUID().toString().replace("-", "").substring(0, 12)
    }

    private Response postResource(String path, Map payload) {
        Response r = scimRequest().body(JsonOutput.toJson(payload)).post(path)
        if (r.statusCode() == 201) {
            String id = r.jsonPath().getString("id")
            if (id) {
                (path == "/Groups" ? createdGroupIds : createdUserIds) << id
            }
        }
        return r
    }

    private Response postUser(Map extra = [:]) {
        Map payload = [schemas: [USER_SCHEMA], userName: "rfc7643ug_${uniq()}".toString()] + extra
        return postResource("/Users", payload)
    }

    /** Creates a User and returns the response body as a map; fails the test when it is not 201. */
    private Map mustCreateUser(Map extra = [:]) {
        Response r = postUser(extra)
        assert r.statusCode() == 201 : "RFC 7643 4.1: POST /Users must return 201 but was ${r.statusCode()}: ${r.asString()}"
        return r.jsonPath().getMap("")
    }

    private Map fetchUser(String id) {
        Response r = scimRequest().get("/Users/${id}")
        assert r.statusCode() == 200 : "RFC 7644 3.4.1: GET /Users/${id} must return 200 but was ${r.statusCode()}"
        return r.jsonPath().getMap("")
    }

    private Map schemaById(String schemaId) {
        Response r = scimRequest().get("/Schemas")
        assert r.statusCode() == 200 : "RFC 7644 4: GET /Schemas must return 200 but was ${r.statusCode()}"
        Map schema = r.jsonPath().getList("Resources").find { it.id == schemaId }
        assert schema != null : "RFC 7643 7: /Schemas must contain ${schemaId}"
        return schema
    }

    private static Map attrOf(Map schema, String name) {
        return schema.attributes.find { it.name == name }
    }

    private static boolean hasKeyDeep(Object node, String key) {
        if (node instanceof Map) {
            return node.containsKey(key) || node.values().any { hasKeyDeep(it, key) }
        }
        if (node instanceof Collection) {
            return node.any { hasKeyDeep(it, key) }
        }
        return false
    }

    private void assertNoPassword(Response r, String cleartext, String where) {
        String body = r.asString()
        assert !body.contains(cleartext) : "RFC 7643 4.1.1: ${where} must not return the cleartext password"
        assert !hasKeyDeep(new JsonSlurper().parseText(body), "password") : "RFC 7643 4.1.1: ${where} must not return the password attribute in any form"
    }

    /** MAY allow additional type values: abort when the server rejects the custom type, otherwise it must be stored. */
    private void assertCustomTypeStored(Response resp, String attribute, String type) {
        if (resp.statusCode() == 400) {
            throw new TestAbortedException("Server restricts ${attribute}.type to a fixed set of values (optional)")
        }
        assert resp.statusCode() == 201 : "RFC 7643 4.1.2: POST must return 201 but was ${resp.statusCode()}"
        Map fetched = fetchUser(resp.jsonPath().getString("id"))
        assert fetched[attribute]*.type.contains(type) : "RFC 7643 4.1.2: an accepted custom ${attribute}.type '${type}' must be stored but got ${fetched[attribute]*.type}"
    }
}
