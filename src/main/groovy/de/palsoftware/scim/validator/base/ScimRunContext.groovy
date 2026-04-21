package de.palsoftware.scim.validator.base

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ScimRunContext {

    private static final InheritableThreadLocal<String> CURRENT_RUN = new InheritableThreadLocal<>()
    private static final InheritableThreadLocal<String> CURRENT_TEST = new InheritableThreadLocal<>()
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, CopyOnWriteArrayList<ScimHttpExchange>>> EXCHANGES = new ConcurrentHashMap<>()

    static void beginRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required")
        }
        CURRENT_RUN.set(runId)
        CURRENT_TEST.remove()
        EXCHANGES.putIfAbsent(runId, new ConcurrentHashMap<>())
    }

    static void endRun() {
        String runId = CURRENT_RUN.get()
        CURRENT_TEST.remove()
        CURRENT_RUN.remove()
        if (runId != null) {
            EXCHANGES.remove(runId)
        }
    }

    static boolean isCaptureEnabled() {
        return CURRENT_RUN.get() != null
    }

    static void beginTest(String testId) {
        String runId = CURRENT_RUN.get()
        if (runId == null) {
            CURRENT_TEST.remove()
            return
        }
        if (testId == null) {
            CURRENT_TEST.remove()
            return
        }
        CURRENT_TEST.set(testId)
        EXCHANGES.computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>())
            .putIfAbsent(testId, new CopyOnWriteArrayList<>())
    }

    static void endTest() {
        CURRENT_TEST.remove()
    }

    static void record(ScimHttpExchange exchange) {
        if (!isCaptureEnabled() || exchange == null) {
            return
        }
        String runId = CURRENT_RUN.get()
        if (runId == null) {
            return
        }
        String testId = CURRENT_TEST.get()
        if (testId == null) {
            testId = "_unassigned"
        }
        EXCHANGES.computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(testId, ignored -> new CopyOnWriteArrayList<>())
            .add(exchange)
    }

    static List<ScimHttpExchange> getForTest(String testId) {
        String runId = CURRENT_RUN.get()
        if (runId == null || testId == null) {
            return List.of()
        }
        Map<String, CopyOnWriteArrayList<ScimHttpExchange>> runExchanges = EXCHANGES.get(runId)
        if (runExchanges == null) {
            return List.of()
        }
        return new ArrayList<>(runExchanges.getOrDefault(testId, new CopyOnWriteArrayList<>()))
    }
}
