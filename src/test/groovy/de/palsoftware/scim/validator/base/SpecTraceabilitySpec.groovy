package de.palsoftware.scim.validator.base

import de.palsoftware.scim.validator.base.spec.SpecMatrixGenerator
import spock.lang.Specification

import java.nio.file.Path
import java.nio.file.Paths

/**
 * CI Verification Spec for SCIM Specification Traceability.
 * Runs fast without needing Docker or a live SCIM server.
 */
class SpecTraceabilitySpec extends Specification {

    def "specification catalog loads and requirement references are valid"() {
        given: "Project root directory"
        Path projectRoot = Paths.get("").toAbsolutePath()
        SpecMatrixGenerator generator = new SpecMatrixGenerator(projectRoot)

        when: "Catalog is loaded and test specs are scanned"
        def requirements = generator.loadCatalog()
        def tests = generator.scanTestSpecs()

        then: "Catalogs are non-empty"
        !requirements.isEmpty()
        !tests.isEmpty()

        and: "Traceability validation succeeds"
        def errors = generator.validate(requirements, tests)
        if (!errors.isEmpty()) {
            System.err.println("Spec Traceability Errors:\n" + errors.join("\n"))
        }
        errors.isEmpty()
    }

    def "generated documentation files are up to date"() {
        given: "Spec matrix generator"
        Path projectRoot = Paths.get("").toAbsolutePath()
        SpecMatrixGenerator generator = new SpecMatrixGenerator(projectRoot)

        expect: "Generated docs match current specs"
        generator.run(false)
    }
}
