package de.palsoftware.scim.validator.base

import de.palsoftware.scim.validator.ScimValidatorSuite
import de.palsoftware.scim.validator.base.spec.SpecMatrixGenerator
import org.junit.platform.suite.api.SelectClasses
import org.spockframework.runtime.model.FeatureMetadata
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
        boolean verifyOnly = !Boolean.getBoolean("scim.spec.regenerate")

        expect: "Generated docs match current specs"
        generator.run(verifyOnly)
    }

    def "all feature methods across suite specs are scanned including those with nested quotes and class-level capabilities"() {
        given: "Spec matrix generator"
        Path projectRoot = Paths.get("").toAbsolutePath()
        SpecMatrixGenerator generator = new SpecMatrixGenerator(projectRoot)
        int expectedFeatureCount = ScimValidatorSuite.getAnnotation(SelectClasses).value().sum { Class<?> specClass ->
            specClass.declaredMethods.count { it.isAnnotationPresent(FeatureMetadata) }
        } as int

        when: "Test specs are scanned and requirements linked"
        def requirements = generator.loadCatalog()
        def tests = generator.scanTestSpecs()
        generator.linkTestsToRequirements(requirements, tests)
        def testIds = tests.collect { it.id }

        then: "All compiled feature methods across suite specs are found with unique IDs"
        tests.size() == expectedFeatureCount
        testIds.size() == testIds.unique().size()

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

        and: "Class-level @RequiresCapability is inherited and composite capabilities are tracked"
        tests.find { it.id == "BLK_01" }?.capabilities == ["bulk"]
        tests.find { it.id == "PAT_01" }?.capabilities == ["patch"]
        tests.find { it.id == "FLT_01" }?.capabilities == ["filter"]
        tests.find { it.id == "SRT_01" }?.capabilities == ["sort"]
        tests.find { it.id == "CUR_01" }?.capabilities == ["cursorPagination"]
        tests.find { it.id == "CUR_05" }?.capabilities == ["cursorPagination", "search"]
        tests.find { it.id == "ASY_01" }?.capabilities == ["async"]

        and: "Generated markdown contains clickable IETF RFC section links"
        String mapMd = generator.generateTestSpecMap(tests, requirements)
        String covMd = generator.generateSpecCoverage(requirements)
        mapMd.contains("[§4](https://www.rfc-editor.org/rfc/rfc7644#section-4)")
        covMd.contains("[§2.1](https://www.rfc-editor.org/rfc/rfc7643#section-2.1)")
    }
}
