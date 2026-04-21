package de.palsoftware.scim.validator.base

/**
 * Holds the resolved SCIM API coordinates produced by the bootstrap process.
 * Returned by a {@link ScimBootstrapProvider} implementation and consumed by
 * {@link ScimBaseSpec#refreshConfiguration()}.
 */
final class ScimRuntimeConfiguration {
    final String apiUrl
    final String workspaceId
    final String authToken

    ScimRuntimeConfiguration(String apiUrl, String workspaceId, String authToken) {
        this.apiUrl = apiUrl
        this.workspaceId = workspaceId
        this.authToken = authToken
    }
}
