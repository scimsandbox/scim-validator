package de.palsoftware.scim.validator.base.spec

import groovy.yaml.YamlSlurper
import org.spockframework.runtime.model.FeatureMetadata
import spock.lang.Ignore

import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * SpecMatrixGenerator:
 * Generates and verifies traceability between the SCIM requirement catalogs and the validator specs.
 *
 * Inputs:
 * - src/main/resources/spec/rfc{7643,7644,9865,9967}-requirements.yaml
 * - the compiled Rfc*Spec classes in package de.palsoftware.scim.validator.specs
 *   (listed from src/main/groovy/de/palsoftware/scim/validator/specs/Rfc*Spec.groovy, inspected by reflection)
 *
 * Outputs:
 * - docs/TEST_SPEC_MAP.md (test to spec)
 * - docs/SCIM_SPEC_COVERAGE.md (spec to test)
 *
 * Verification rules (see validate):
 * - every feature has exactly one @SpecRef, and its id exists in the catalog
 * - no two features share an id
 * - the feature display name starts with "<id>:"
 * - every requirement whose disposition is not "na" has a feature; "na" requirements have none
 * - @RequiresCapability equals the requirement's capability (absent means "core")
 * - "unassigned" dispositions are only counted, and fail with -Dscim.spec.strict=true
 */
class SpecMatrixGenerator {

    static final String SPECS_PACKAGE = "de.palsoftware.scim.validator.specs"
    static final String SPECS_PATH = "src/main/groovy/de/palsoftware/scim/validator/specs"
    static final String CORE = "core"

    static class Requirement {
        String id
        String rfc
        String section
        String subject
        String level
        String polarity
        String quote
        String area
        String capability
        String disposition
        String reason
        List<Feature> features = []

        boolean isImplemented() {
            return features.any { !it.ignored }
        }
    }

    static class Feature {
        String id
        String name
        String className
        String methodName
        String fileName
        int lineNumber
        boolean ignored
        List<String> specRefs = []
        String capability = CORE

        /** Display name without the leading "<id>:" prefix. */
        String getLabel() {
            String prefix = (id ?: "") + ":"
            return name.startsWith(prefix) ? name.substring(prefix.length()).trim() : name
        }
    }

    static void main(String[] args) {
        boolean verifyOnly = args.contains("--verify")
        Path projectRoot = findProjectRoot()

        SpecMatrixGenerator generator = new SpecMatrixGenerator(projectRoot)
        boolean valid = generator.run(verifyOnly)
        if (!valid) {
            System.exit(1)
        }
    }

    private final Path projectRoot
    boolean strict = Boolean.getBoolean("scim.spec.strict")

    SpecMatrixGenerator(Path projectRoot) {
        this.projectRoot = projectRoot
    }

    boolean run(boolean verifyOnly = false) {
        Map<String, Requirement> requirements = loadCatalog()
        List<Feature> features = scanFeatures()

        // Validation checks (also links features to requirements)
        List<String> errors = validate(requirements, features)
        long unassigned = requirements.values().count { it.disposition == "unassigned" }
        System.out.println("[SpecMatrixGenerator] Unassigned requirements: ${unassigned}" + (strict ? " (strict)" : ""))
        if (!errors.isEmpty()) {
            System.err.println("\n[SpecMatrixGenerator] Traceability validation errors:")
            errors.each { System.err.println("  ERROR: " + it) }
            return false
        }

        Path docsDir = projectRoot.resolve("docs")
        Files.createDirectories(docsDir)

        String testSpecMapContent = generateTestSpecMap(features, requirements)
        String specCoverageContent = generateSpecCoverage(requirements, features)

        Path testSpecMapPath = docsDir.resolve("TEST_SPEC_MAP.md")
        Path specCoveragePath = docsDir.resolve("SCIM_SPEC_COVERAGE.md")

        if (verifyOnly) {
            boolean current = true
            if (!Files.exists(testSpecMapPath) || Files.readString(testSpecMapPath) != testSpecMapContent) {
                System.err.println("ERROR: docs/TEST_SPEC_MAP.md is out of date. Re-run generator.")
                current = false
            }
            if (!Files.exists(specCoveragePath) || Files.readString(specCoveragePath) != specCoverageContent) {
                System.err.println("ERROR: docs/SCIM_SPEC_COVERAGE.md is out of date. Re-run generator.")
                current = false
            }
            return current
        }

        Files.writeString(testSpecMapPath, testSpecMapContent)
        Files.writeString(specCoveragePath, specCoverageContent)
        System.out.println("[SpecMatrixGenerator] Successfully generated docs/TEST_SPEC_MAP.md and docs/SCIM_SPEC_COVERAGE.md")
        System.out.println("[SpecMatrixGenerator] Requirements catalog size: ${requirements.size()} rows")
        System.out.println("[SpecMatrixGenerator] Features: ${features.size()} (${features.count { !it.ignored }} implemented, ${features.count { it.ignored }} skeletons)")
        return true
    }

    Map<String, Requirement> loadCatalog() {
        Map<String, Requirement> result = new LinkedHashMap<>()
        YamlSlurper slurper = new YamlSlurper()

        Path specDir = projectRoot.resolve("src/main/resources/spec")
        List<Path> catalogFiles = []
        if (Files.exists(specDir)) {
            try (def stream = Files.list(specDir)) {
                catalogFiles = stream
                    .filter { it.fileName.toString().endsWith("-requirements.yaml") }
                    .sorted { Path p1, Path p2 -> p1.fileName.toString() <=> p2.fileName.toString() }
                    .toList()
            }
        }

        catalogFiles.each { path ->
            def docs = slurper.parse(path.toFile())
            if (docs instanceof List) {
                docs.each { item ->
                    Requirement req = new Requirement(
                        id: item.id?.toString()?.trim(),
                        rfc: item.rfc?.toString()?.trim(),
                        section: item.section?.toString()?.trim(),
                        subject: item.subject?.toString()?.trim() ?: "",
                        level: item.level?.toString()?.trim(),
                        polarity: item.polarity?.toString()?.trim(),
                        quote: item.quote?.toString()?.trim() ?: "",
                        area: item.area?.toString()?.trim() ?: "",
                        capability: item.capability?.toString()?.trim() ?: CORE,
                        disposition: item.disposition?.toString()?.trim() ?: "unassigned",
                        reason: item.reason?.toString()?.trim() ?: ""
                    )
                    result.put(req.id, req)
                }
            }
        }

        return result
    }

    /**
     * Inspects the compiled Rfc*Spec classes by reflection. The class names come from the
     * Rfc*Spec.groovy files in the specs directory, so the main sources must be compiled.
     */
    List<Feature> scanFeatures() {
        List<Feature> features = []
        Path specsDir = projectRoot.resolve(SPECS_PATH)
        if (!Files.exists(specsDir)) {
            return features
        }

        List<Path> files
        try (def stream = Files.list(specsDir)) {
            files = stream
                .filter { it.fileName.toString() ==~ /Rfc\d+\w*Spec\.groovy/ }
                .sorted { Path p1, Path p2 -> p1.fileName.toString() <=> p2.fileName.toString() }
                .toList()
        }

        ClassLoader loader = Thread.currentThread().contextClassLoader ?: SpecMatrixGenerator.classLoader
        files.each { file ->
            String fileName = file.fileName.toString()
            String className = SPECS_PACKAGE + "." + fileName.substring(0, fileName.length() - ".groovy".length())
            Class<?> specClass = Class.forName(className, false, loader)
            boolean classIgnored = specClass.isAnnotationPresent(Ignore)
            String classCapability = specClass.getAnnotation(RequiresCapability)?.value()?.trim()

            List<Method> methods = specClass.declaredMethods.findAll { it.isAnnotationPresent(FeatureMetadata) }
            methods.sort { it.getAnnotation(FeatureMetadata).ordinal() }
            methods.each { Method method ->
                FeatureMetadata meta = method.getAnnotation(FeatureMetadata)
                String methodCapability = method.getAnnotation(RequiresCapability)?.value()?.trim()
                features.add(new Feature(
                    name: meta.name(),
                    className: className,
                    methodName: method.name,
                    fileName: fileName,
                    lineNumber: meta.line(),
                    ignored: classIgnored || method.isAnnotationPresent(Ignore),
                    specRefs: method.getAnnotationsByType(SpecRef).collect { it.value().trim() },
                    capability: methodCapability ?: classCapability ?: CORE
                ))
            }
        }

        features.each { it.id = it.specRefs.size() == 1 ? it.specRefs[0] : null }
        return features
    }

    void linkFeaturesToRequirements(Map<String, Requirement> requirements, List<Feature> features) {
        requirements.values().each { it.features.clear() }
        features.each { feature ->
            feature.specRefs.each { refId ->
                Requirement req = requirements.get(refId)
                if (req != null && !req.features.contains(feature)) {
                    req.features.add(feature)
                }
            }
        }
    }

    List<String> validate(Map<String, Requirement> requirements, List<Feature> features) {
        linkFeaturesToRequirements(requirements, features)
        List<String> errors = []

        // 1. Every feature has exactly one @SpecRef
        features.each { f ->
            if (f.specRefs.size() != 1) {
                errors.add("Feature '${f.name}' (${f.fileName}:${f.lineNumber}) must have exactly one @SpecRef, found ${f.specRefs.size()}")
            }
        }

        // 2. Every @SpecRef points to a known requirement id
        features.each { f ->
            f.specRefs.each { refId ->
                if (!requirements.containsKey(refId)) {
                    errors.add("Feature '${f.name}' (${f.fileName}:${f.lineNumber}) references unknown requirement '${refId}'")
                }
            }
        }

        // 3. No two features share an id
        Map<String, List<Feature>> byId = features.findAll { it.id != null }.groupBy { it.id }
        byId.each { id, group ->
            if (group.size() > 1) {
                errors.add("Requirement '${id}' is referenced by ${group.size()} features: " +
                    group.collect { "${it.fileName}:${it.lineNumber}" }.join(", "))
            }
        }

        // 4. The display name starts with "<id>:"
        features.each { f ->
            if (f.id != null && !f.name.startsWith(f.id + ":")) {
                errors.add("Feature '${f.name}' (${f.fileName}:${f.lineNumber}) must be named starting with '${f.id}:'")
            }
        }

        // 5. Disposition vs. feature presence
        requirements.values().each { req ->
            if (req.disposition == "na") {
                if (!req.features.isEmpty()) {
                    errors.add("Requirement ${req.id} is na (${req.reason ?: 'no reason'}) but has feature(s): " +
                        req.features.collect { "${it.fileName}:${it.lineNumber}" }.join(", "))
                }
            } else if (req.features.isEmpty()) {
                errors.add("Requirement ${req.id} (RFC ${req.rfc} section ${req.section}, disposition=${req.disposition}) has no feature")
            }
        }

        // 6. @RequiresCapability must match the catalog capability (absent means core)
        features.each { f ->
            f.specRefs.each { refId ->
                Requirement req = requirements.get(refId)
                if (req != null && f.capability != req.capability) {
                    errors.add("Feature '${f.name}' (${f.fileName}:${f.lineNumber}) has capability '${f.capability}' but ${req.id} requires '${req.capability}'")
                }
            }
        }

        // 7. Unassigned dispositions only fail in strict mode
        if (strict) {
            List<String> unassigned = requirements.values().findAll { it.disposition == "unassigned" }.collect { it.id }
            if (!unassigned.isEmpty()) {
                errors.add("${unassigned.size()} requirement(s) are still unassigned (scim.spec.strict=true), e.g. ${unassigned.take(5).join(', ')}")
            }
        }

        return errors
    }

    private static String sectionLink(Requirement req) {
        return (req.rfc && req.section) ?
            "[${req.section}](https://www.rfc-editor.org/rfc/rfc${req.rfc}#section-${req.section})" : (req.section ?: "-")
    }

    private static String stateOf(Feature f) {
        return f.ignored ? "skeleton" : "implemented"
    }

    String generateTestSpecMap(List<Feature> features, Map<String, Requirement> requirements) {
        StringBuilder sb = new StringBuilder()
        sb.append("# SCIM 2.0 Test to Specification Traceability Map\n\n")
        sb.append("*Auto-generated by `SpecMatrixGenerator`. Do not edit by hand.*\n\n")
        sb.append("| Requirement ID | Feature | File:Line | RFC | Section | Area | Level | Capability | Disposition | State | Quote |\n")
        sb.append("| :--- | :--- | :--- | :---: | :---: | :--- | :---: | :---: | :---: | :---: | :--- |\n")

        features.each { f ->
            String link = "[${f.fileName}:${f.lineNumber}](../${SPECS_PATH}/${f.fileName}#L${f.lineNumber})"
            if (f.specRefs.isEmpty()) {
                sb.append("| - | ${escapePipes(f.name)} | ${link} | - | - | - | - | ${f.capability} | - | ${stateOf(f)} | - |\n")
            } else {
                f.specRefs.each { refId ->
                    Requirement req = requirements.get(refId)
                    sb.append("| ${refId} | ${escapePipes(f.label)} | ${link} | ${req ? req.rfc : '-'} | ${req ? sectionLink(req) : '-'} | " +
                        "${req ? req.area : '-'} | ${req ? req.level : '-'} | ${f.capability} | ${req ? req.disposition : '-'} | ${stateOf(f)} | ${req ? escapePipes(req.quote) : '-'} |\n")
                }
            }
        }

        return sb.toString()
    }

    String generateSpecCoverage(Map<String, Requirement> requirements, List<Feature> features) {
        StringBuilder sb = new StringBuilder()
        sb.append("# SCIM 2.0 Specification Coverage Matrix\n\n")
        sb.append("*Auto-generated by `SpecMatrixGenerator`. Do not edit by hand.*\n")
        sb.append("*Specification Basis: RFC 7643, RFC 7644, RFC 9865, RFC 9967 + Verified Errata*\n\n")

        List<Requirement> all = requirements.values().toList()
        long implementedFeatures = features.count { !it.ignored }
        long skeletonFeatures = features.count { it.ignored }

        sb.append("### Coverage Summary\n\n")
        sb.append("- **Total Requirements Tracked**: ${all.size()}\n")
        sb.append("- **Features**: ${features.size()} (${implementedFeatures} implemented, ${skeletonFeatures} still @Ignore'd skeletons)\n")
        ["unassigned", "tested", "optional", "na", "gap"].each { d ->
            sb.append("- **Disposition ${d}**: ${all.count { it.disposition == d }}\n")
        }
        List<String> otherDispositions = all.collect { it.disposition }.unique().findAll { !(it in ["unassigned", "tested", "optional", "na", "gap"]) }
        otherDispositions.each { d -> sb.append("- **Disposition ${d}**: ${all.count { it.disposition == d }}\n") }
        sb.append("\n")

        sb.append("### By RFC\n\n")
        sb.append("| RFC | Requirements | Features | Implemented | Skeletons | N/A | Unassigned |\n")
        sb.append("| :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n")
        all.collect { it.rfc }.unique().sort().each { rfc ->
            List<Requirement> rs = all.findAll { it.rfc == rfc }
            List<Feature> fs = rs.collectMany { it.features }
            sb.append("| ${rfc} | ${rs.size()} | ${fs.size()} | ${fs.count { !it.ignored }} | ${fs.count { it.ignored }} | " +
                "${rs.count { it.disposition == 'na' }} | ${rs.count { it.disposition == 'unassigned' }} |\n")
        }
        sb.append("\n")

        sb.append("### By Level\n\n")
        sb.append("| Level | Requirements | Implemented | Skeletons | N/A |\n")
        sb.append("| :---: | :---: | :---: | :---: | :---: |\n")
        ["MUST", "SHOULD", "MAY"].each { level ->
            List<Requirement> rs = all.findAll { it.level == level }
            List<Feature> fs = rs.collectMany { it.features }
            sb.append("| ${level} | ${rs.size()} | ${fs.count { !it.ignored }} | ${fs.count { it.ignored }} | ${rs.count { it.disposition == 'na' }} |\n")
        }
        sb.append("\n")

        sb.append("### By Capability\n\n")
        sb.append("| Capability | Requirements | Implemented | Skeletons | N/A |\n")
        sb.append("| :--- | :---: | :---: | :---: | :---: |\n")
        all.collect { it.capability }.unique().sort().each { cap ->
            List<Requirement> rs = all.findAll { it.capability == cap }
            List<Feature> fs = rs.collectMany { it.features }
            sb.append("| ${cap} | ${rs.size()} | ${fs.count { !it.ignored }} | ${fs.count { it.ignored }} | ${rs.count { it.disposition == 'na' }} |\n")
        }
        sb.append("\n")

        sb.append("### Requirements\n\n")
        sb.append("| Requirement ID | RFC | Section | Area | Level | Capability | Disposition | Status | Feature | Quote / Reason |\n")
        sb.append("| :--- | :---: | :---: | :--- | :---: | :---: | :---: | :---: | :--- | :--- |\n")

        all.each { req ->
            String status
            if (req.disposition == "na") {
                status = "N/A"
            } else if (req.features.isEmpty()) {
                status = "Missing"
            } else if (req.implemented) {
                status = "Implemented"
            } else {
                status = "Skeleton"
            }
            String featureStr = req.features.isEmpty() ? "-" :
                req.features.collect { "[${it.fileName}:${it.lineNumber}](../${SPECS_PATH}/${it.fileName}#L${it.lineNumber})" }.join(", ")
            String text = escapePipes(req.quote)
            if (req.reason) {
                text += " (reason: ${escapePipes(req.reason)})"
            }
            sb.append("| ${req.id} | ${req.rfc} | ${sectionLink(req)} | ${req.area} | ${req.level} | ${req.capability} | ${req.disposition} | ${status} | ${featureStr} | ${text} |\n")
        }

        return sb.toString()
    }

    private static String escapePipes(String text) {
        return text ? text.replace("|", "\\|").replace("\n", " ") : ""
    }

    private static Path findProjectRoot() {
        Path current = Paths.get("").toAbsolutePath()
        Path p = current
        while (p != null) {
            if (Files.exists(p.resolve("pom.xml"))) {
                return p
            }
            p = p.getParent()
        }
        return current
    }
}
