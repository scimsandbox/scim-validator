package de.palsoftware.scim.validator.base.spec

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Container annotation for repeatable {@link SpecRef} annotations.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@interface SpecRefs {
    SpecRef[] value()
}
