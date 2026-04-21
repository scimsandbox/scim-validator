package de.palsoftware.scim.validator.base

import de.palsoftware.scim.validator.base.ScimBaseSpec
import io.restassured.RestAssured
import io.restassured.response.Response
import spock.lang.Shared

/**
 * Area 5 shared setup for filtering, sorting, and pagination specs.
 */
abstract class A5_BaseSpec extends ScimBaseSpec {

    // Unique prefix to isolate this spec's users from other test runs
    static final String PREFIX = "flt_" + UUID.randomUUID().toString().substring(0, 6) + "_"

    @Shared List<Map> userData = []  // Stores [userName, externalId, active, id] per user

    def setupSpec() {
        loadServiceProviderConfig()

        // Create 5 users with distinct, predictable attributes
        def users = [
            [userName: "${PREFIX}alice@test.com",   externalId: "${PREFIX}EXT-A", active: true,
             name: [givenName: "Alice", familyName: "Anderson"]],
            [userName: "${PREFIX}bob@test.com",     externalId: "${PREFIX}EXT-B", active: true,
             name: [givenName: "Bob", familyName: "Baker"]],
            [userName: "${PREFIX}charlie@test.com", externalId: "${PREFIX}EXT-C", active: false,
             name: [givenName: "Charlie", familyName: "Clark"]],
            [userName: "${PREFIX}diana@test.com",   externalId: "${PREFIX}EXT-D", active: true,
             name: [givenName: "Diana", familyName: "Davis"]],
            [userName: "${PREFIX}eve@test.com",     externalId: "${PREFIX}EXT-E", active: false,
             name: [givenName: "Eve", familyName: "Evans"]],
        ]

        users.each { u ->
            Response response = createUser(u)
            assert response.statusCode() == 201 : "Setup: could not create user ${u.userName}: ${response.body().asString()}"
            String id = response.jsonPath().getString("id")
            userData << [userName: u.userName, externalId: u.externalId, active: u.active, id: id]
        }
    }
}
