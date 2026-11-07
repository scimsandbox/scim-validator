package de.palsoftware.scim.validator

import org.junit.platform.suite.api.IncludeClassNamePatterns
import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite

/**
 * Selects the generated requirement specs (one class per RFC and area,
 * {@code Rfc<NNNN><Area>Spec}) in the specs package.
 */
@Suite
@SelectPackages("de.palsoftware.scim.validator.specs")
@IncludeClassNamePatterns(".*Rfc\\d+\\w*Spec")
class ScimValidatorSuite {
}
