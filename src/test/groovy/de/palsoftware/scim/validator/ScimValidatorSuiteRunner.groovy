package de.palsoftware.scim.validator

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

/**
 * Thin delegation runner so that {@code mvn test} still invokes the full compliance
 * suite when the main sources are consumed as a library.
 *
 * Surefire discovers this class in {@code target/test-classes} and hands it to the
 * JUnit Platform Suite Engine, which recursively processes {@link ScimValidatorSuite}
 * and runs all A1-A9 specs.
 */
@Suite
@SelectClasses(ScimValidatorSuite)
class ScimValidatorSuiteRunner {
}
