package de.palsoftware.scim.validator.base

class ScimOutput {

    static void println(Object msg) {
        String s = String.valueOf(msg)
        if (s != null && s.startsWith("DEVIATION:")) {
            String text = s.substring("DEVIATION:".length()).trim()
            deviation(text)
            return
        }
        if (!ScimRunContext.isCaptureEnabled()) {
            System.out.println(s)
        }
    }

    static void println() {
        if (!ScimRunContext.isCaptureEnabled()) {
            System.out.println()
        }
    }

    static void print(Object msg) {
        if (!ScimRunContext.isCaptureEnabled()) {
            System.out.print(String.valueOf(msg))
        }
    }

    /**
     * Record a structured deviation.
     * Note: Structured deviations are NEVER suppressed by capture mode,
     * ensuring compliance visibility for library consumers.
     */
    static void deviation(String message,
                          String testId = null,
                          String requirementId = null,
                          String observed = null,
                          String expected = null,
                          String targetUrl = null) {
        Deviation deviation = new Deviation(
            testId: testId,
            requirementId: requirementId,
            severity: "deviation",
            observed: observed,
            expected: expected,
            message: message,
            targetUrl: targetUrl
        )
        DeviationCollector.record(deviation)
        System.out.println("DEVIATION: ${testId ? "[$testId] " : ""}${message}")
    }

    static void note(String message) {
        if (!ScimRunContext.isCaptureEnabled()) {
            System.out.println("NOTE: ${message}")
        }
    }
}
