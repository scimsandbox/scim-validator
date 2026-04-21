package de.palsoftware.scim.validator.base

/**
 * SPI for providing a SCIM runtime environment when no explicit target is
 * configured (i.e. when automatic bootstrap is requested).
 *
 * Implementations are discovered via {@link java.util.ServiceLoader}.  The
 * testcontainers-based implementation lives in the test sources of this module
 * and is registered under
 * {@code META-INF/services/de.palsoftware.scim.validator.base.ScimBootstrapProvider}.
 *
 * Downstream projects that want to bring their own bootstrap mechanism can
 * provide an alternative implementation on the test classpath.
 */
interface ScimBootstrapProvider {

    /**
     * Start (or locate) a SCIM environment and return its connection details.
     *
     * @return a non-null {@link ScimRuntimeConfiguration} describing the
     *         running environment
     */
    ScimRuntimeConfiguration bootstrap()
}
