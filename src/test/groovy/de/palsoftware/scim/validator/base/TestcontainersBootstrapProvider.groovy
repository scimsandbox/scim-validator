package de.palsoftware.scim.validator.base

/**
 * {@link ScimBootstrapProvider} implementation that starts a disposable
 * PostgreSQL database and SCIM API container via Testcontainers.
 *
 * Discovered automatically through the standard Java ServiceLoader mechanism
 * when this module's test-jar (or test-classes directory) is on the classpath.
 */
class TestcontainersBootstrapProvider implements ScimBootstrapProvider {

    @Override
    ScimRuntimeConfiguration bootstrap() {
        return ScimValidatorEnvironment.ensureStarted()
    }
}
