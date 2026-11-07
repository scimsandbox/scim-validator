package de.palsoftware.scim.validator.base

class ScimOutput {

    static void println(Object msg) {
        String s = String.valueOf(msg)
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

    static void note(String message) {
        if (!ScimRunContext.isCaptureEnabled()) {
            System.out.println("NOTE: ${message}")
        }
    }
}
