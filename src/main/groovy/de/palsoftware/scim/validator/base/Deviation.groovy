package de.palsoftware.scim.validator.base

import groovy.transform.Canonical

import java.time.Instant

/**
 * Structured deviation record representing an observed server behavior
 * that deviates from strict SCIM specification requirements or recommendations.
 */
@Canonical
class Deviation {
    String testId
    String testName
    String requirementId
    String severity          // "deviation", "warning", "info"
    String observed
    String expected
    String message
    String targetUrl
    String timestamp = Instant.now().toString()
}
