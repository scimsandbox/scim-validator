package de.palsoftware.scim.validator.base

import io.restassured.response.Response
import spock.lang.Shared

/**
 * Area 5 shared setup for filtering, sorting, and pagination specs.
 */
abstract class A5_BaseSpec extends ScimBaseSpec {

    // Per-spec-class prefix to isolate each spec's test data and prevent cross-spec interference
    @Shared String specPrefix = "a5_" + UUID.randomUUID().toString().substring(0, 6) + "_"

    String getPREFIX() {
        return specPrefix
    }

    @Shared List<Map> userData = []  // Stores [userName, externalId, active, id] per user

    def setupSpec() {
        loadServiceProviderConfig()
        String p = getPREFIX()

        // Create 5 users with distinct, predictable attributes (familyName order is distinct from userName/insertion order)
        def users = [
            [userName: "${p}alice@test.com",   externalId: "${p}EXT-A", active: true,
             name: [givenName: "Alice", familyName: "Zimmerman"]],
            [userName: "${p}bob@test.com",     externalId: "${p}EXT-B", active: true,
             name: [givenName: "Bob", familyName: "Baker"]],
            [userName: "${p}charlie@test.com", externalId: "${p}EXT-C", active: false,
             name: [givenName: "Charlie", familyName: "Xander"]],
            [userName: "${p}diana@test.com",   externalId: "${p}EXT-D", active: true,
             name: [givenName: "Diana", familyName: "Davis"]],
            [userName: "${p}eve@test.com",     externalId: "${p}EXT-E", active: false,
             name: [givenName: "Eve", familyName: "Anderson"]],
        ]

        users.each { u ->
            Response response = createUser(u)
            assert response.statusCode() == 201 : "Setup: could not create user ${u.userName}: ${response.body().asString()}"
            String id = response.jsonPath().getString("id")
            userData << [userName: u.userName, externalId: u.externalId, active: u.active, id: id]
        }
    }

    def cleanupSpec() {
        userData.each { u ->
            if (u.id) deleteUser(u.id)
        }
        userData.clear()
    }
}
