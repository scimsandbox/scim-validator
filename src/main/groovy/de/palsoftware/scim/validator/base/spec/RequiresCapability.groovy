package de.palsoftware.scim.validator.base.spec

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

import org.spockframework.runtime.extension.ExtensionAnnotation

/**
 * Declares that a test method (or spec class) requires an optional SCIM server
 * capability advertised in /ServiceProviderConfig (or schema discovery).
 *
 * Capability vocabulary (matches the "capability" field of the requirement catalogs):
 * - "core": no gating, always run (the default; same as omitting the annotation)
 * - "patch"
 * - "filter"
 * - "sort"
 * - "etag"
 * - "bulk"
 * - "search"
 * - "changePassword"
 * - "enterprise"
 * - "cursorPagination"
 * - "async"
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@ExtensionAnnotation(RequiresCapabilityExtension.class)
@interface RequiresCapability {
    String value()
}
