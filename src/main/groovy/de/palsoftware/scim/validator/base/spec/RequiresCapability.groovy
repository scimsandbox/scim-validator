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
 * Supported capability values:
 * - "patch"
 * - "bulk"
 * - "filter"
 * - "sort"
 * - "etag"
 * - "search"
 * - "changePassword"
 * - "enterprise"
 * - "cursorPagination"
 * - "securityEvents" (or "async")
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@ExtensionAnnotation(RequiresCapabilityExtension.class)
@interface RequiresCapability {
    String value()
}
