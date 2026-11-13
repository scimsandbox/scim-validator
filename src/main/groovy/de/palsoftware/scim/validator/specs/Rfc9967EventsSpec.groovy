package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.Rfc9967BaseSpec
import de.palsoftware.scim.validator.base.ScimJsonSupport
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import io.restassured.response.Response
import org.opentest4j.TestAbortedException

/**
 * RFC 9967: SCIM Profile for Security Event Tokens
 *
 * One feature per requirement of requirements-rfc9967.yaml (area "SCIM events"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc9967EventsSpec extends Rfc9967BaseSpec {

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MUST, lines 255-256
    // SCIM Events MUST use the "sub_id" claim, defined by [RFC9493], to identify the subject of events.
    @SpecRef("RFC9967-2.1-6289f0")
    @RequiresCapability("async")
    def "RFC9967-2.1-6289f0: SCIM Events MUST use the 'sub_id' claim, defined by [RFC9493], to identify the subject of events."() {
        when: "an asynchronous create completes and its event token is retrieved"
        Map s = creationSet()

        then: "the token identifies the subject (the created user) with a sub_id claim"
        assert s.claims.sub_id instanceof Map : "RFC 9967 2.1: SCIM Events must use the 'sub_id' claim but the token has claims ${s.claims.keySet()}"
        assert s.id : "Precondition: the created user must be findable"
        assert (s.claims.sub_id.uri as String)?.endsWith("/Users/${s.id}") : "RFC 9967 2.1: 'sub_id' must identify the subject /Users/${s.id} but was ${s.claims.sub_id}"
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MUST, lines 256-258
    // The "sub_id" claim MUST be contained within the main JWT claims body
    @SpecRef("RFC9967-2.1-d80019")
    @RequiresCapability("async")
    def "RFC9967-2.1-d80019: The 'sub_id' claim MUST be contained within the main JWT claims body"() {
        when: "an asynchronous create completes and its event token is retrieved"
        Map s = creationSet()

        then: "sub_id is a member of the main claims body"
        assert s.claims.containsKey("sub_id") : "RFC 9967 2.1: 'sub_id' must be contained within the main JWT claims body but top-level claims are ${s.claims.keySet()}"
        assert s.claims.sub_id instanceof Map && !s.claims.sub_id.isEmpty() : "RFC 9967 2.1: top-level 'sub_id' must be a non-empty object but was ${s.claims.sub_id}"
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MUST NOT, lines 256-258
    // MUST NOT be located within an event payload within the "events" claim.
    @SpecRef("RFC9967-2.1-91f518")
    @RequiresCapability("async")
    def "RFC9967-2.1-91f518: MUST NOT be located within an event payload within the 'events' claim."() {
        when: "an asynchronous create completes and its event token is retrieved"
        Map s = creationSet()
        Map events = eventsOf(s.claims)

        then: "no event payload contains a sub_id"
        assert !events.isEmpty() : "Precondition: token must carry at least one event"
        for (def entry : events.entrySet()) {
            assert !(entry.value instanceof Map && (entry.value as Map).containsKey("sub_id")) :
                "RFC 9967 2.1: 'sub_id' must not be located within the event payload of ${entry.key}"
        }
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MUST NOT, lines 262-264
    // The JWT "sub" claim MUST NOT be used to identify subjects to prevent confusion with JWT authorization tokens (originally recommended in Section 3 of [RFC8417]).
    @SpecRef("RFC9967-2.1-c78147")
    @RequiresCapability("async")
    def "RFC9967-2.1-c78147: The JWT 'sub' claim MUST NOT be used to identify subjects to prevent confusion with JWT authorization..."() {
        when: "the event tokens of an asynchronous create and delete are retrieved"
        Map created = creationSet()
        Map deleted = deletionSet()

        then: "neither token uses the JWT 'sub' claim"
        assert !created.claims.containsKey("sub") : "RFC 9967 2.1: the JWT 'sub' claim must not be used to identify subjects (create token: sub=${created.claims.sub})"
        assert !deleted.claims.containsKey("sub") : "RFC 9967 2.1: the JWT 'sub' claim must not be used to identify subjects (delete token: sub=${deleted.claims.sub})"
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MUST, lines 282-282
    // Instead of "sub", the top-level claim "sub_id" SHALL be used.
    @SpecRef("RFC9967-2.1-085965")
    @RequiresCapability("async")
    def "RFC9967-2.1-085965: Instead of 'sub', the top-level claim 'sub_id' SHALL be used."() {
        when: "the event token of an asynchronous delete is retrieved"
        Map d = deletionSet()

        then: "the subject is carried in 'sub_id', not in 'sub'"
        assert d.claims.sub_id instanceof Map : "RFC 9967 2.1: the top-level claim 'sub_id' shall be used but token claims are ${d.claims.keySet()}"
        assert !d.claims.containsKey("sub") : "RFC 9967 2.1: instead of 'sub' the top-level 'sub_id' shall be used, but 'sub' is present"
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MUST, lines 291-292
    // This attribute MUST be provided in a SCIM Event "sub_id" claim.
    @SpecRef("RFC9967-2.1-f33921")
    @RequiresCapability("async")
    def "RFC9967-2.1-f33921: This attribute MUST be provided in a SCIM Event 'sub_id' claim."() {
        when: "an asynchronous create completes and its event token is retrieved"
        Map s = creationSet()
        def subId = s.claims.sub_id

        then: "sub_id carries a uri that addresses the resource"
        assert subId instanceof Map : "RFC 9967 2.1: token has no 'sub_id' object"
        assert subId.uri instanceof String && !(subId.uri as String).isBlank() : "RFC 9967 2.1: 'uri' must be provided in the 'sub_id' claim but sub_id was ${subId}"
        assert s.id : "Precondition: the created user must be findable"
        assert (subId.uri as String).endsWith("/Users/${s.id}") : "RFC 9967 2.1: 'sub_id.uri' must be the resource type endpoint plus the resource id (/Users/${s.id}) but was ${subId.uri}"
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MAY, lines 301-304
    // If known, the "externalId" value (defined in Section 3.1 of [RFC7643]) of the SCIM resource that MAY be used by a receiver to identify the corresponding resource in the Event Receiver's domain.
    @SpecRef("RFC9967-2.1-c2f0f5")
    @RequiresCapability("async")
    def "RFC9967-2.1-c2f0f5: externalId: If known, the 'externalId' value (defined in Section 3.1 of [RFC7643]) of the SCIM resource..."() {
        given: "an asynchronously created user with an externalId"
        String externalId = "ext-${UUID.randomUUID().toString().substring(0, 8)}"

        when: "the event token is retrieved"
        Map s = creationSet([externalId: externalId])
        Map subId = s.claims.sub_id as Map

        then: "when sub_id carries an externalId it is the externalId of the resource"
        assert subId != null : "RFC 9967 2.1: token has no 'sub_id'"
        if (subId.containsKey("externalId")) {
            assert subId.externalId == externalId : "RFC 9967 2.1: sub_id.externalId must be the resource's externalId '${externalId}' but was '${subId.externalId}'"
        }
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MAY, lines 307-309
    // The SCIM "id" attribute (defined in Section 3.1 of [RFC7643]) MAY be used for backwards compatibility reasons in addition to the "uri" claim.
    @SpecRef("RFC9967-2.1-65b4a2")
    @RequiresCapability("async")
    def "RFC9967-2.1-65b4a2: id: The SCIM 'id' attribute (defined in Section 3.1 of [RFC7643]) MAY be used for backwards compatibility..."() {
        when: "an asynchronous create completes and its event token is retrieved"
        Map s = creationSet()
        Map subId = s.claims.sub_id as Map

        then: "when sub_id carries an id it is the id of the resource"
        assert subId != null : "RFC 9967 2.1: token has no 'sub_id'"
        assert s.id : "Precondition: the created user must be findable"
        if (subId.containsKey("id")) {
            assert subId.id == s.id : "RFC 9967 2.1: sub_id.id must be the SCIM id '${s.id}' but was '${subId.id}'"
        }
    }

    // RFC 9967 section 2.1 (Identifying the Subject of an Event), MAY, lines 311-315
    // In cases where SCIM identifiers ("id" and "externalId") are not enough to identify a common resource between an Event Publisher and Event Receiver, the "sub_id" object MAY contain attributes whose SCIM attribute types have "uniqueness" set to "server" or "global" as per Section 7 of [RFC7643].
    @SpecRef("RFC9967-2.1-097f43")
    @RequiresCapability("async")
    def "RFC9967-2.1-097f43: In cases where SCIM identifiers ('id' and 'externalId') are not enough to identify a common resource..."() {
        given: "an asynchronously created user with a unique userName"
        Map s = creationSet()
        Map subId = s.claims.sub_id as Map
        Set<String> extra = ScimJsonSupport.extractExtraSubIdAttributeNames(subId)

        when: "the retrieved resource is read"
        Response user = scimRequestQuiet().get("/Users/${s.id}")

        then: "any additional sub_id attributes are attributes of the resource with the same value"
        assert subId != null : "RFC 9967 2.1: token has no 'sub_id'"
        assert user.statusCode() == 200
        Map<String, Object> userMap = ScimJsonSupport.parseMap(user)
        for (String name : extra) {
            Object actualValue = ScimJsonSupport.getLiteralAttribute(userMap, name)
            assert actualValue == subId[name] :
                "RFC 9967 2.1: sub_id attribute '${name}' must equal the resource's value ${actualValue} but was ${subId[name]}"
        }
    }

    // RFC 9967 section 2.2 (Common Event Attributes), MUST, lines 325-327
    // Only one of the claims, "data" or "attributes", MUST be provided depending on the event definition.
    @SpecRef("RFC9967-2.2-3f67c6")
    @RequiresCapability("async")
    def "RFC9967-2.2-3f67c6: Only one of the claims, 'data' or 'attributes', MUST be provided depending on the event definition."() {
        when: "tokens for create, PUT and PATCH are retrieved"
        List<Map> payloads = provPayloads(provisioningSets())

        then: "no event carries both 'data' and 'attributes'"
        for (Map p : payloads) {
            Map payload = p.payload as Map
            assert !(payload.containsKey("data") && payload.containsKey("attributes")) :
                "RFC 9967 2.2: only one of 'data' or 'attributes' must be provided but ${p.uri} has both"
        }
    }

    // RFC 9967 section 2.2 (Common Event Attributes), MUST, lines 340-343
    // "txn" is REQUIRED to support asynchronous SCIM requests, CP, and replication to disambiguate or detect duplicate SETs regarding the same underlying transaction.
    @SpecRef("RFC9967-2.2-9cfe0b")
    @RequiresCapability("async")
    def "RFC9967-2.2-9cfe0b: 'txn' is REQUIRED to support asynchronous SCIM requests, CP, and replication to disambiguate or detect..."() {
        when: "an asynchronous create completes"
        Map s = creationSet()
        String setTxn = (s.accepted as Response).header("Set-Txn")

        then: "the token carries a txn claim equal to the value handed to the client"
        assert setTxn : "RFC 9967 3: the 202 response must carry Set-Txn"
        assert s.claims.txn instanceof String && !(s.claims.txn as String).isEmpty() : "RFC 9967 2.2: 'txn' is required to support asynchronous SCIM requests but the token has txn=${s.claims.txn}"
        assert s.claims.txn == setTxn : "RFC 9967 2.2: 'txn' claim '${s.claims.txn}' must match Set-Txn '${setTxn}'"
    }

    // RFC 9967 section 2.2 (Common Event Attributes), SHOULD, lines 361-362
    // Names of modified attributes SHOULD conform to the ABNF syntax rule for "path" (Section 3.5.2 of [RFC7644]).
    @SpecRef("RFC9967-2.2-c17805")
    @RequiresCapability("async")
    def "RFC9967-2.2-c17805: Names of modified attributes SHOULD conform to the ABNF syntax rule for 'path' (Section 3.5.2 of [RFC7644])."() {
        when: "tokens for create, PUT and PATCH are retrieved"
        List<Map> payloads = provPayloads(provisioningSets())
        List<Map> withAttributes = payloads.findAll { (it.payload as Map).containsKey("attributes") }
        // attrPath of RFC 7644 3.5.2: optional URN prefix, attribute name, optional filter, optional sub-attribute
        def attrPath = ~/^(?:[A-Za-z0-9._-]+(?::[A-Za-z0-9._-]+)*:)?[A-Za-z][A-Za-z0-9_$-]*(?:\[[^\]]*\])?(?:\.[A-Za-z][A-Za-z0-9_$-]*)?$/

        then: "'attributes' is an array of path names"
        if (withAttributes.isEmpty()) {
            throw new TestAbortedException("No retrieved event carries an 'attributes' payload")
        }
        for (Map p : withAttributes) {
            def attrs = (p.payload as Map).attributes
            assert attrs instanceof List : "RFC 9967 2.2: 'attributes' of ${p.uri} must be an array of attribute names but was ${attrs?.getClass()?.simpleName}"
            for (def name : attrs) {
                assert name instanceof String && attrPath.matcher(name as String).matches() :
                    "RFC 9967 2.2: attribute name '${name}' in ${p.uri} should conform to the 'path' ABNF of RFC 7644 3.5.2"
            }
        }
    }

    // RFC 9967 section 2.4 (SCIM Provisioning Events), MUST, lines 434-436
    // When the "data" payload attribute is included for each of the following events, the event URI MUST end with "full"; otherwise, the event URI ends with "notice".
    @SpecRef("RFC9967-2.4-71c037")
    @RequiresCapability("async")
    def "RFC9967-2.4-71c037: When the 'data' payload attribute is included for each of the following events, the event URI MUST end..."() {
        when: "tokens for create, PUT and PATCH are retrieved"
        List<Map> payloads = provPayloads(provisioningSets())

        then: "'data' is present exactly when the event URI ends with 'full' (otherwise 'notice')"
        for (Map p : payloads) {
            boolean hasData = (p.payload as Map).containsKey("data")
            boolean full = (p.uri as String).endsWith(":full")
            if (hasData) {
                assert full : "RFC 9967 2.4: with a 'data' payload the event URI must end with 'full' but was ${p.uri}"
            } else {
                assert (p.uri as String).endsWith(":notice") : "RFC 9967 2.4: without a 'data' payload the event URI ends with 'notice' but was ${p.uri} (payload keys ${(p.payload as Map).keySet()})"
            }
        }
    }

    // RFC 9967 section 2.4 (SCIM Provisioning Events), MUST, lines 442-443
    // Exactly one of the payload attributes, "data" or "attributes", MUST be present.
    @SpecRef("RFC9967-2.4-a7de3a")
    @RequiresCapability("async")
    def "RFC9967-2.4-a7de3a: Exactly one of the payload attributes, 'data' or 'attributes', MUST be present."() {
        when: "tokens for create, PUT and PATCH are retrieved"
        List<Map> payloads = provPayloads(provisioningSets())

        then: "every provisioning event has exactly one of 'data' and 'attributes'"
        for (Map p : payloads) {
            Map payload = p.payload as Map
            int present = ["data", "attributes"].count { payload.containsKey(it) }
            assert present == 1 : "RFC 9967 2.4: exactly one of 'data' or 'attributes' must be present in ${p.uri} but payload keys are ${payload.keySet()}"
        }
    }

    // RFC 9967 section 2.4 (SCIM Provisioning Events), MUST NOT, lines 443-443
    // Both MUST NOT be present simultaneously.
    @SpecRef("RFC9967-2.4-0234bf")
    @RequiresCapability("async")
    def "RFC9967-2.4-0234bf: Both MUST NOT be present simultaneously."() {
        when: "tokens for create, PUT and PATCH are retrieved"
        List<Map> payloads = provPayloads(provisioningSets())

        then: "'data' and 'attributes' are never present together"
        for (Map p : payloads) {
            Map payload = p.payload as Map
            assert !(payload.containsKey("data") && payload.containsKey("attributes")) :
                "RFC 9967 2.4: 'data' and 'attributes' must not be present simultaneously in ${p.uri}"
        }
    }

    // RFC 9967 section 2.4.1 (urn:ietf:params:scim:event:prov:create:{notice|full}), MAY, lines 449-452
    // Note that because the event may be used for replication, the "id" attribute that was assigned by the SCIM service provider is shared so that all replicas in the domain MAY use the same resource identifier.
    @SpecRef("RFC9967-2.4.1-508a2d")
    @RequiresCapability("async")
    def "RFC9967-2.4.1-508a2d: Note that because the event may be used for replication, the 'id' attribute that was assigned by the SCIM..."() {
        when: "an asynchronous create completes"
        Map s = creationSet()
        List<Map> payloads = provPayloads([s.claims as Map])

        then: "the create event shares the id assigned by the service provider"
        assert s.id : "Precondition: the created user must be findable"
        boolean shared = false
        for (Map p : payloads) {
            Map payload = p.payload as Map
            def data = payload.data
            def attrs = payload.attributes
            if (data instanceof Map && data.id == s.id) shared = true
            if (attrs instanceof Map && attrs.id == s.id) shared = true
            if (attrs instanceof List && attrs.contains("id")) shared = true
        }
        assert shared : "RFC 9967 2.4.1: the create event must share the id '${s.id}' assigned by the service provider, payloads were ${payloads.collect { it.payload }}"
    }

    // RFC 9967 section 2.4.1 (urn:ietf:params:scim:event:prov:create:{notice|full}), MAY, lines 516-517
    // The Event Receiver MAY retrieve that information by performing a SCIM GET based on the "sub_id" value provided.
    @SpecRef("RFC9967-2.4.1-cca8a1")
    @RequiresCapability("async")
    def "RFC9967-2.4.1-cca8a1: The Event Receiver MAY retrieve that information by performing a SCIM GET based on the 'sub_id' value..."() {
        when: "an asynchronous create completes and the sub_id is used for a SCIM GET"
        Map s = creationSet()
        String uri = (s.claims.sub_id as Map)?.uri
        Response get = hasText(uri) ? getUrl(resolveUrl(uri)) : null

        then: "the GET based on sub_id returns the created resource"
        assert hasText(uri) : "RFC 9967 2.1: token has no sub_id.uri"
        assert get.statusCode() == 200 : "RFC 9967 2.4.1: GET on sub_id uri '${uri}' must return 200 but was ${get.statusCode()}"
        assert get.jsonPath().getString("id") == s.id : "RFC 9967 2.4.1: GET on sub_id uri must return the resource ${s.id} but returned ${get.jsonPath().getString('id')}"
    }

    // RFC 9967 section 2.4.4 (urn:ietf:params:scim:event:prov:delete), MUST NOT, lines 649-650
    // When a DELETE is sent, a corresponding "feedRemove" SHALL NOT be issued.
    @SpecRef("RFC9967-2.4.4-f76b8b")
    @RequiresCapability("async")
    def "RFC9967-2.4.4-f76b8b: When a DELETE is sent, a corresponding 'feedRemove' SHALL NOT be issued."() {
        when: "an asynchronous DELETE completes"
        Map d = deletionSet()
        Map events = eventsOf(d.claims)

        then: "the token contains no feed:remove event"
        assert !events.isEmpty() : "Precondition: delete token must carry an event"
        for (String uri : events.keySet()) {
            assert !uri.contains(":feed:remove") && !uri.toLowerCase().contains("feedremove") :
                "RFC 9967 2.4.4: a corresponding 'feedRemove' shall not be issued when a DELETE is sent but found ${uri}"
        }
    }

    // RFC 9967 section 2.4.4 (urn:ietf:params:scim:event:prov:delete), MUST NOT, lines 651-653
    // Note that because the delete event has no attributes, the qualifiers "full" and "notice" SHALL NOT be used.
    @SpecRef("RFC9967-2.4.4-e89e1d")
    @RequiresCapability("async")
    def "RFC9967-2.4.4-e89e1d: Note that because the delete event has no attributes, the qualifiers 'full' and 'notice' SHALL NOT be used."() {
        when: "an asynchronous DELETE completes"
        Map d = deletionSet()
        List<String> deleteEvents = eventsOf(d.claims).keySet().findAll { it.toString().startsWith(PROV_PREFIX + "delete") }.collect { it.toString() }

        then: "the delete event URI carries neither the 'full' nor the 'notice' qualifier"
        if (deleteEvents.isEmpty()) {
            throw new TestAbortedException("Retrieved token has no prov:delete event: ${eventsOf(d.claims).keySet()}")
        }
        for (String uri : deleteEvents) {
            assert uri == PROV_PREFIX + "delete" : "RFC 9967 2.4.4: the qualifiers 'full' and 'notice' shall not be used for the delete event but was ${uri}"
        }
    }

}
