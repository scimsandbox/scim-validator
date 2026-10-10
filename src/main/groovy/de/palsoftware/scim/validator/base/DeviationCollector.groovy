package de.palsoftware.scim.validator.base

import groovy.json.JsonOutput

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe central collector for SCIM compliance deviations.
 * Dumps structured records to target/scim-run/deviations.json at suite conclusion.
 */
class DeviationCollector {

    private static final List<Deviation> DEVIATIONS = new CopyOnWriteArrayList<>()
    private static volatile boolean shutdownHookRegistered = false

    static {
        registerShutdownHook()
    }

    static void record(Deviation deviation) {
        if (deviation != null) {
            DEVIATIONS.add(deviation)
        }
    }

    static List<Deviation> getDeviations() {
        return Collections.unmodifiableList(new ArrayList<>(DEVIATIONS))
    }

    static int getCount() {
        return DEVIATIONS.size()
    }

    static void clear() {
        DEVIATIONS.clear()
    }

    static synchronized void dumpToFile(String outputPath = "target/scim-run/deviations.json") {
        try {
            Path path = Paths.get(outputPath)
            Path parent = path.getParent()
            if (parent != null) {
                Files.createDirectories(parent)
            }
            String json = JsonOutput.prettyPrint(JsonOutput.toJson(DEVIATIONS))
            Files.writeString(path, json)
        } catch (Exception e) {
            System.err.println("WARNING: Failed to write deviations to ${outputPath}: ${e.message}")
        }
    }

    static void printSummary() {
        if (DEVIATIONS.isEmpty()) {
            System.out.println("\n[SCIM Validator] 0 deviations recorded. Run is fully compliant with tested specifications.")
        } else {
            System.out.println("\n[SCIM Validator] ==================================================")
            System.out.println("[SCIM Validator] DEVIATIONS SUMMARY: ${DEVIATIONS.size()} deviation(s) recorded")
            DEVIATIONS.eachWithIndex { dev, idx ->
                System.out.println("  ${idx + 1}. [${dev.testId ?: 'N/A'}] ${dev.requirementId ?: ''} - ${dev.message}")
                if (dev.observed && dev.expected) {
                    System.out.println("     Observed: ${dev.observed} | Expected: ${dev.expected}")
                }
            }
            System.out.println("[SCIM Validator] Written to target/scim-run/deviations.json")
            System.out.println("[SCIM Validator] ==================================================\n")
        }
    }

    private static synchronized void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true
            Runtime.getRuntime().addShutdownHook(new Thread({
                dumpToFile()
                if (DEVIATIONS.size() > 0) {
                    printSummary()
                }
            }, "ScimDeviationCollectorShutdown"))
        }
    }
}
