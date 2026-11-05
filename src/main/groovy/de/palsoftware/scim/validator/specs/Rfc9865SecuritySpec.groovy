package de.palsoftware.scim.validator.specs

import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.spec.RequiresCapability
import de.palsoftware.scim.validator.base.spec.SpecRef
import spock.lang.Ignore
import org.opentest4j.TestAbortedException
import io.restassured.response.Response
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 * RFC 9865: SCIM Cursor-Based Pagination
 *
 * One feature per requirement of requirements-rfc9865.yaml (area "Security considerations"). Features start as
 * skeletons: @Ignore'd until the body is written. Do not rename the features or change the
 * @SpecRef ids; spec-sources/check_spec_coverage.py enforces exactly one feature per requirement.
 */
class Rfc9865SecuritySpec extends ScimBaseSpec {

    // RFC 9865 section 5.2 (Confidentiality), MUST, lines 500-501
    // Authorization checks MUST be continuously applied as an actor navigates through the result set associated with a cursor.
    @SpecRef("RFC9865-5.2-5661f9")
    @RequiresCapability("cursorPagination")
    def "RFC9865-5.2-5661f9: Authorization checks MUST be continuously applied as an actor navigates through the result set associated..."() {
        given: "three users that share a unique userName prefix"
        String prefix = newPrefix()
        List<String> created = createPagedUsers(prefix, 3)

        when: "a valid cursor is presented without credentials and with an invalid token"
        Response first = getPage(prefix, "", 1)
        String cursor = first.jsonPath().getString("nextCursor")
        assert hasText(cursor) : "RFC 9865 2: first page with more results must carry nextCursor"
        String filter = "userName sw \"${prefix}_\""
        Response anonymous = scimRequestAnonymous().queryParam("filter", filter).queryParam("cursor", cursor).queryParam("count", 1).get("/Users")
        Response badToken = scimRequestAnonymous().header("Authorization", "Bearer not-a-valid-token")
            .queryParam("filter", filter).queryParam("cursor", cursor).queryParam("count", 1).get("/Users")

        then: "possession of the cursor grants no access"
        assert anonymous.statusCode() == 401 :
            "RFC 9865 5.2: a cursor without authentication must be refused with 401 but status was ${anonymous.statusCode()}"
        assert badToken.statusCode() == 401 :
            "RFC 9865 5.2: a cursor with an invalid token must be refused with 401 but status was ${badToken.statusCode()}"
        assert (anonymous.jsonPath().getList("Resources") ?: []).isEmpty() && (badToken.jsonPath().getList("Resources") ?: []).isEmpty() :
            "RFC 9865 5.2: refused cursor requests must not return resources"
    }

    private String newPrefix() {
        return "cur_${UUID.randomUUID().toString().substring(0, 8)}"
    }

    private List<String> createPagedUsers(String prefix, int n) {
        if (!isFilterSupported()) {
            throw new TestAbortedException("filter is not supported; cannot isolate the test data")
        }
        return (1..n).collect { int i ->
            Response response = createUser(userName: "${prefix}_${i}@test.com")
            assert response.statusCode() == 201 : "Setup: user creation must return 201 but was ${response.statusCode()}"
            response.jsonPath().getString("id")
        }
    }

    private Response getPage(String prefix, Object cursor, Object count) {
        return scimRequest()
            .queryParam("filter", "userName sw \"${prefix}_\"")
            .queryParam("cursor", cursor)
            .queryParam("count", count)
            .get("/Users")
    }
}
