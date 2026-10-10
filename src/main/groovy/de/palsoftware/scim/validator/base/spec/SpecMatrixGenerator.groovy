package de.palsoftware.scim.validator.base.spec

import groovy.yaml.YamlSlurper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * SpecMatrixGenerator:
 * Generates and verifies traceability between SCIM RFC specifications and the validator test suite.
 *
 * Inputs:
 * - src/main/resources/spec/rfc7643-requirements.yaml
 * - src/main/resources/spec/rfc7644-requirements.yaml
 * - src/main/groovy/de/palsoftware/scim/validator/specs/*.groovy
 *
 * Outputs:
 * - docs/TEST_SPEC_MAP.md (test → spec)
 * - docs/SCIM_SPEC_COVERAGE.md (spec → test)
 */
class SpecMatrixGenerator {

    static class Requirement {
        String id
        String rfc
        String section
        String title
        String level
        String capability
        String observability
        String disposition
        String note
        List<String> tests = []
    }

    static class TestMethod {
        String id
        String name
        String fileName
        int lineNumber
        List<String> specRefs = []
        String capability
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

    SpecMatrixGenerator(Path projectRoot) {
        this.projectRoot = projectRoot
    }

    boolean run(boolean verifyOnly = false) {
        Map<String, Requirement> requirements = loadCatalog()
        List<TestMethod> tests = scanTestSpecs()

        // Link tests to requirements
        linkTestsToRequirements(requirements, tests)

        // Validation checks
        List<String> errors = validate(requirements, tests)
        if (!errors.isEmpty()) {
            System.err.println("\n[SpecMatrixGenerator] Traceability validation errors:")
            errors.each { System.err.println("  ERROR: " + it) }
            return false
        }

        Path docsDir = projectRoot.resolve("docs")
        Files.createDirectories(docsDir)

        String testSpecMapContent = generateTestSpecMap(tests, requirements)
        String specCoverageContent = generateSpecCoverage(requirements)

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
        System.out.println("[SpecMatrixGenerator] Verified test methods: ${tests.size()} methods")
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
                    .sorted { Path p1, Path p2 ->
                        String n1 = p1.fileName.toString()
                        String n2 = p2.fileName.toString()
                        if (n1.startsWith("rfc7644")) return -1
                        if (n2.startsWith("rfc7644")) return 1
                        if (n1.startsWith("rfc7643")) return -1
                        if (n2.startsWith("rfc7643")) return 1
                        return n1.compareTo(n2)
                    }
                    .toList()
            }
        }

        catalogFiles.each { path ->
            if (Files.exists(path)) {
                def docs = slurper.parse(path.toFile())
                if (docs instanceof List) {
                    docs.each { item ->
                        Requirement req = new Requirement(
                            id: item.id?.toString()?.trim(),
                            rfc: item.rfc?.toString()?.trim(),
                            section: item.section?.toString()?.trim(),
                            title: item.title?.toString()?.trim(),
                            level: item.level?.toString()?.trim(),
                            capability: item.capability?.toString()?.trim() ?: "none",
                            observability: item.observability?.toString()?.trim() ?: "response",
                            disposition: item.disposition?.toString()?.trim() ?: "tested",
                            note: item.note?.toString()?.trim() ?: ""
                        )
                        result.put(req.id, req)
                    }
                }
            } else {
                throw new IllegalStateException("Catalog file not found: " + path)
            }
        }

        return result
    }

    List<TestMethod> scanTestSpecs() {
        List<TestMethod> tests = []
        Path specsDir = projectRoot.resolve("src/main/groovy/de/palsoftware/scim/validator/specs")
        if (!Files.exists(specsDir)) {
            return tests
        }

        Pattern methodPattern = Pattern.compile('^\\s*def\\s+["\']([^"\']+)["\']\\s*\\(.*')
        Pattern specRefPattern = Pattern.compile('@SpecRef\\s*\\(\\s*["\']([^"\']+)["\'](?:\\s*,\\s*note\\s*=\\s*["\']([^"\']*)["\'])?\\s*\\)')
        Pattern capPattern = Pattern.compile('@RequiresCapability\\s*\\(\\s*["\']([^"\']+)["\']\\s*\\)')

        Files.list(specsDir).filter { it.toString().endsWith(".groovy") }.sorted().forEach { file ->
            List<String> lines = Files.readAllLines(file)
            String fileName = file.fileName.toString()
            List<String> pendingSpecRefs = []
            String pendingCap = null

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i)
                Matcher capMatcher = capPattern.matcher(line)
                while (capMatcher.find()) {
                    pendingCap = capMatcher.group(1).trim()
                }

                Matcher refMatcher = specRefPattern.matcher(line)
                while (refMatcher.find()) {
                    pendingSpecRefs.add(refMatcher.group(1).trim())
                }

                Matcher methodMatcher = methodPattern.matcher(line)
                if (methodMatcher.find()) {
                    String fullTitle = methodMatcher.group(1).trim()
                    String testId = extractTestId(fullTitle, fileName, tests.size() + 1)
                    TestMethod test = new TestMethod(
                        id: testId,
                        name: fullTitle,
                        fileName: fileName,
                        lineNumber: i + 1,
                        specRefs: new ArrayList<>(pendingSpecRefs),
                        capability: pendingCap
                    )
                    tests.add(test)
                    pendingSpecRefs.clear()
                    pendingCap = null
                }
            }
        }

        return tests
    }

    static String extractTestId(String title, String fileName, int index) {
        Matcher m = Pattern.compile('^([A-Z0-9_]+):\\s*(.*)').matcher(title)
        if (m.find()) {
            return m.group(1)
        }
        if (fileName.contains("A1_ServiceDiscovery")) {
            return String.format("DSC_%02d", index)
        }
        if (fileName.contains("A2_SchemaValidation")) {
            return String.format("SCH_%02d", index)
        }
        return String.format("TST_%02d", index)
    }

    void linkTestsToRequirements(Map<String, Requirement> requirements, List<TestMethod> tests) {
        tests.each { test ->
            test.specRefs.each { refId ->
                Requirement req = requirements.get(refId)
                if (req != null) {
                    if (!req.tests.contains(test.id)) {
                        req.tests.add(test.id)
                    }
                }
            }
        }
    }

    List<String> validate(Map<String, Requirement> requirements, List<TestMethod> tests) {
        linkTestsToRequirements(requirements, tests)
        List<String> errors = []

        // 1. Every test method must have at least one @SpecRef
        tests.each { test ->
            if (test.specRefs.isEmpty()) {
                errors.add("Test '${test.name}' (${test.fileName}:${test.lineNumber}) has no @SpecRef annotation")
            }
        }

        // 2. Every @SpecRef must point to a known requirement ID
        tests.each { test ->
            test.specRefs.each { refId ->
                if (!requirements.containsKey(refId)) {
                    errors.add("Test '${test.name}' (${test.fileName}:${test.lineNumber}) references unknown requirement '${refId}'")
                }
            }
        }

        // 3. Every MUST or SHOULD requirement must be tested, optional (gated), or have na disposition
        requirements.values().each { req ->
            if (req.level in ["MUST", "SHOULD"]) {
                if (req.tests.isEmpty() && req.disposition == "tested") {
                    errors.add("Normative requirement ${req.id} (RFC ${req.rfc} §${req.section} - '${req.title}') has no test and is not marked optional/na")
                }
            }
        }

        return errors
    }

    String generateTestSpecMap(List<TestMethod> tests, Map<String, Requirement> requirements) {
        StringBuilder sb = new StringBuilder()
        sb.append("# SCIM 2.0 Test to Specification Traceability Map\n\n")
        sb.append("*Auto-generated by `SpecMatrixGenerator`. Do not edit by hand.*\n\n")
        sb.append("| Test ID | Test Title | File:Line | RFC | Section | Requirement ID | Requirement Title | Mode |\n")
        sb.append("| :--- | :--- | :--- | :---: | :---: | :--- | :--- | :---: |\n")

        tests.each { test ->
            String mode = test.capability ? "gated (${test.capability})" : "strict"
            if (test.specRefs.isEmpty()) {
                sb.append("| ${test.id} | ${escapePipes(test.name)} | [${test.fileName}:${test.lineNumber}](../src/main/groovy/de/palsoftware/scim/validator/specs/${test.fileName}#L${test.lineNumber}) | - | - | - | - | ${mode} |\n")
            } else {
                test.specRefs.each { refId ->
                    Requirement req = requirements.get(refId)
                    String rfc = req ? req.rfc : "-"
                    String sec = req ? req.section : "-"
                    String reqTitle = req ? escapePipes(req.title) : "-"
                    sb.append("| ${test.id} | ${escapePipes(test.name)} | [${test.fileName}:${test.lineNumber}](../src/main/groovy/de/palsoftware/scim/validator/specs/${test.fileName}#L${test.lineNumber}) | ${rfc} | ${sec} | ${refId} | ${reqTitle} | ${mode} |\n")
                }
            }
        }

        return sb.toString()
    }

    String generateSpecCoverage(Map<String, Requirement> requirements) {
        StringBuilder sb = new StringBuilder()
        sb.append("# SCIM 2.0 Specification Coverage Matrix\n\n")
        sb.append("*Auto-generated by `SpecMatrixGenerator`. Do not edit by hand.*\n")
        sb.append("*Specification Basis: RFC 7643, RFC 7644 + Verified Errata*\n\n")

        int total = requirements.size()
        long covered = requirements.values().count { !it.tests.isEmpty() }
        long optional = requirements.values().count { it.disposition == "optional" }
        long na = requirements.values().count { it.disposition == "na" }
        long gap = requirements.values().count { it.disposition == "gap" }

        sb.append("### Coverage Summary\n\n")
        sb.append("- **Total Requirements Tracked**: ${total}\n")
        sb.append("- **Tested / Covered Requirements**: ${covered}\n")
        sb.append("- **Optional / Capability Gated**: ${optional}\n")
        sb.append("- **Non-Observable / NA**: ${na}\n")
        sb.append("- **Known Gaps**: ${gap}\n\n")

        sb.append("| Requirement ID | RFC | Section | Requirement Title | Level | Capability | Status | Test(s) | Notes |\n")
        sb.append("| :--- | :---: | :---: | :--- | :---: | :---: | :---: | :--- | :--- |\n")

        requirements.values().each { req ->
            String status
            if (!req.tests.isEmpty()) {
                status = req.capability != "none" ? "Gated (${req.capability})" : "Covered"
            } else if (req.disposition == "na") {
                status = "N/A"
            } else if (req.disposition == "optional") {
                status = "Optional (${req.capability})"
            } else {
                status = "Gap"
            }

            String testsStr = req.tests.isEmpty() ? "—" : req.tests.join(", ")
            sb.append("| ${req.id} | ${req.rfc} | ${req.section} | ${escapePipes(req.title)} | ${req.level} | ${req.capability} | ${status} | ${testsStr} | ${escapePipes(req.note)} |\n")
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
