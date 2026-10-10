package de.palsoftware.scim.validator.base.spec

import de.palsoftware.scim.validator.base.CapabilityState
import de.palsoftware.scim.validator.base.ScimBaseSpec
import de.palsoftware.scim.validator.base.ScimOutput
import org.opentest4j.TestAbortedException
import org.spockframework.runtime.extension.AbstractAnnotationDrivenExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.SpecInfo

/**
 * Spock extension that intercepts features or specs annotated with @RequiresCapability.
 * If the target SCIM server explicitly reports the capability as UNSUPPORTED,
 * the test iteration is dynamically aborted (skipped) via TestAbortedException.
 */
class RequiresCapabilityExtension extends AbstractAnnotationDrivenExtension<RequiresCapability> {

    @Override
    void visitSpecAnnotation(RequiresCapability annotation, SpecInfo spec) {
        spec.allFeatures.each { feature ->
            addCapabilityInterceptor(annotation.value(), feature)
        }
    }

    @Override
    void visitFeatureAnnotation(RequiresCapability annotation, FeatureInfo feature) {
        addCapabilityInterceptor(annotation.value(), feature)
    }

    private void addCapabilityInterceptor(String capability, FeatureInfo feature) {
        feature.addIterationInterceptor(new IMethodInterceptor() {
            @Override
            void intercept(IMethodInvocation invocation) throws Throwable {
                ScimBaseSpec.ensureServiceProviderConfigLoaded()
                CapabilityState state = ScimBaseSpec.getCapabilityState(capability)
                if (state == CapabilityState.UNSUPPORTED) {
                    String msg = "Skipped: server declares capability '${capability}' unsupported"
                    ScimOutput.note(msg)
                    throw new TestAbortedException(msg)
                }
                if (state == CapabilityState.UNKNOWN) {
                    ScimOutput.note("Probe run: server capability '${capability}' is UNKNOWN (/ServiceProviderConfig not loaded or flag omitted)")
                }
                invocation.proceed()
            }
        })
    }
}
