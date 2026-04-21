package de.palsoftware.scim.validator.base

import spock.lang.Specification

class ValidatorTargetConfigurationSpec extends Specification {

    def "uses managed runtime when no usable target is configured"() {
        expect:
        ValidatorTargetConfiguration.shouldBootstrap(config(
            "",
            "http://localhost:8080",
            "",
            ""
        ))
    }

    def "does not use managed runtime when a full base url target is configured"() {
        expect:
        !ValidatorTargetConfiguration.shouldBootstrap(config(
            "http://localhost:8080/ws/demo/scim/v2",
            "http://localhost:8080",
            "",
            "token"
        ))
    }

    def "does not use managed runtime when api url workspace and token are configured"() {
        expect:
        !ValidatorTargetConfiguration.shouldBootstrap(config(
            "",
            "http://localhost:8080",
            "demo",
            "token"
        ))
    }

    private static ValidatorConfiguration.Config config(String baseUrl,
                                                        String apiUrl,
                                                        String workspaceId,
                                                        String authToken) {
        return new ValidatorConfiguration.Config(
            baseUrl,
            apiUrl,
            workspaceId,
            authToken,
            true,
            "postgres:17-alpine3.23",
            "scim-server-api-flyway:compose",
            "scim-server-api:compose",
            new ValidatorConfiguration.PostgresConfig(
                "validator-postgres",
                "scimplayground",
                "scim_playground",
                "scim_playground"
            ),
            new ValidatorConfiguration.ApiConfig(8080)
        )
    }
}