package de.palsoftware.scim.validator.base.spec

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Repeatable
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Declares that a test method exercises a specific normative requirement
 * from the SCIM specifications (RFC 7643, RFC 7644, etc.).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@Repeatable(SpecRefs)
@interface SpecRef {
    /**
     * Requirement ID matching a row in the requirement catalog
     * (e.g. "RFC7644-3.4.2.2-R1").
     */
    String value()

    /**
     * Optional note explaining how the test addresses the requirement.
     */
    String note() default ""
}
