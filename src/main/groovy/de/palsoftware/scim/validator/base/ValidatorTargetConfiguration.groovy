package de.palsoftware.scim.validator.base

final class ValidatorTargetConfiguration {

    private ValidatorTargetConfiguration() {
    }

    static boolean shouldBootstrap(ValidatorConfiguration.Config configuration) {
        return !hasText(configuration.baseUrl)
            && !hasText(configuration.workspaceId)
            && !hasText(configuration.authToken)
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank()
    }
}