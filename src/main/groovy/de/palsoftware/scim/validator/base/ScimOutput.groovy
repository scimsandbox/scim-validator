package de.palsoftware.scim.validator.base

class ScimOutput {

    static void println(Object msg) {
        if (!ScimRunContext.isCaptureEnabled()) {
            System.out.println(String.valueOf(msg))
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
}
