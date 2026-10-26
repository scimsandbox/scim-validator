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
        generator.run(true)
    }

    def "all 254 test methods are scanned including those with nested quotes"() {
        given: "Spec matrix generator"
        Path projectRoot = Paths.get("").toAbsolutePath()
        SpecMatrixGenerator generator = new SpecMatrixGenerator(projectRoot)

        when: "Test specs are scanned and requirements linked"
        def requirements = generator.loadCatalog()
        def tests = generator.scanTestSpecs()
        generator.linkTestsToRequirements(requirements, tests)
        def testIds = tests.collect { it.id }

        then: "All 254 test methods are found"
        tests.size() == 254

        and: "Methods with nested quotes are detected"
        testIds.contains("PAT_19")
        testIds.contains("GRP_16")
        testIds.contains("BLK_18")
        testIds.contains("BLK_19")

        and: "GRP_16 is mapped under RFC7643-4.2-R3"
        def grp16 = tests.find { it.id == "GRP_16" }
        grp16 != null
        grp16.specRefs.contains("RFC7643-4.2-R3")
        requirements.get("RFC7643-4.2-R3")?.tests?.contains("GRP_16")
    }
}
