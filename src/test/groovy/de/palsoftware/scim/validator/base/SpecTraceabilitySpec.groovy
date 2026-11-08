package de.palsoftware.scim.validator.base

import de.palsoftware.scim.validator.base.spec.SpecMatrixGenerator
import de.palsoftware.scim.validator.base.spec.SpecMatrixGenerator.Feature
import de.palsoftware.scim.validator.base.spec.SpecMatrixGenerator.Requirement
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

        when: "Catalog is loaded and feature specs are scanned"
        def requirements = generator.loadCatalog()
        def features = generator.scanFeatures()

        then: "Catalogs are non-empty and every requirement has a self-contained summary"
        !requirements.isEmpty()
        !features.isEmpty()
        requirements.values().every { Requirement r ->
            r.summary != null && !r.summary.trim().isEmpty() &&
                (!(r.quote in ["REQUIRED.", "RECOMMENDED.", "OPTIONAL."]) || r.summary != r.quote)
        }

        and: "Traceability validation succeeds"
        def errors = generator.validate(requirements, features)
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

    def "every non-na requirement has exactly one feature with a unique id"() {
        given: "Spec matrix generator"
        SpecMatrixGenerator generator = new SpecMatrixGenerator(Paths.get("").toAbsolutePath())

        when: "Catalog and features are loaded"
        def requirements = generator.loadCatalog()
        def features = generator.scanFeatures()
        def ids = features.collect { it.id }

        then: "Feature ids are unique and every feature name starts with its id"
        ids.size() == ids.unique().size()
        features.every { it.name.startsWith(it.id + ":") }

        and: "Features and non-na requirements correspond one to one"
        features.size() == requirements.values().count { it.disposition != "na" }
    }

    def "validation rejects broken traceability"() {
        given: "A tiny catalog with one core, one gated and one na requirement"
        SpecMatrixGenerator generator = new SpecMatrixGenerator(Paths.get("").toAbsolutePath())
        def requirements = [
            "R-core": new Requirement(id: "R-core", rfc: "1", section: "1", level: "MUST", capability: "core", disposition: "tested"),
            "R-patch": new Requirement(id: "R-patch", rfc: "1", section: "2", level: "MAY", capability: "patch", disposition: "tested"),
            "R-na": new Requirement(id: "R-na", rfc: "1", section: "3", level: "MAY", capability: "core", disposition: "na", reason: "not observable"),
            "R-open": new Requirement(id: "R-open", rfc: "1", section: "4", level: "MAY", capability: "core", disposition: "unassigned")
        ]
        List<Feature> good = [
            feature("R-core", "R-core: ok", "core"),
            feature("R-patch", "R-patch: ok", "patch"),
            feature("R-open", "R-open: ok", "core")
        ]

        expect: "The consistent set is valid and unassigned is only reported"
        generator.validate(requirements, good).isEmpty()

        and: "Each broken rule is reported"
        generator.validate(requirements, good + [feature("R-unknown", "R-unknown: x", "core")]).any { it.contains("unknown requirement") }
        generator.validate(requirements, good + [new Feature(name: "R-core: none", fileName: "X.groovy")]).any { it.contains("exactly one @SpecRef") }
        generator.validate(requirements, good + [feature("R-core", "R-core: again", "core")]).any { it.contains("referenced by 2 features") }
        generator.validate(requirements, [feature("R-core", "wrong name", "core"), good[1], good[2]]).any { it.contains("must be named starting with") }
        generator.validate(requirements, good.take(2)).any { it.contains("R-open") && it.contains("has no feature") }
        generator.validate(requirements, good + [feature("R-na", "R-na: x", "core")]).any { it.contains("R-na is na") }
        generator.validate(requirements, [good[0], feature("R-patch", "R-patch: ok", "core"), good[2]]).any { it.contains("has capability 'core' but R-patch requires 'patch'") }
    }

    def "strict mode fails on unassigned requirements"() {
        given: "A consistent catalog with one unassigned requirement"
        SpecMatrixGenerator generator = new SpecMatrixGenerator(Paths.get("").toAbsolutePath())
        def requirements = [
            "R-open": new Requirement(id: "R-open", rfc: "1", section: "4", level: "MAY", capability: "core", disposition: "unassigned")
        ]
        def features = [feature("R-open", "R-open: ok", "core")]

        when: "Strict mode is switched on"
        generator.strict = true

        then: "The unassigned requirement is an error"
        generator.validate(requirements, features).any { it.contains("unassigned") }
    }

    private static Feature feature(String id, String name, String capability) {
        return new Feature(id: id, name: name, fileName: "X.groovy", lineNumber: 1, specRefs: [id], capability: capability)
    }
}
