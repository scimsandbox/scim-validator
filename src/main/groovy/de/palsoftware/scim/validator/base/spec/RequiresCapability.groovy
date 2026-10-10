package de.palsoftware.scim.validator.base.spec

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

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
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@interface RequiresCapability {
    String value()
}
