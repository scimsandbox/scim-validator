package de.palsoftware.scim.validator

import de.palsoftware.scim.validator.specs.A1_ServiceDiscoverySpec
import de.palsoftware.scim.validator.specs.A2_SchemaValidationSpec
import de.palsoftware.scim.validator.specs.A3_UserCrudSpec
import de.palsoftware.scim.validator.specs.A4_PatchOperationsSpec
import de.palsoftware.scim.validator.specs.A5_FilteringSpec
import de.palsoftware.scim.validator.specs.A5_PaginationSpec
import de.palsoftware.scim.validator.specs.A5_SortingSpec
import de.palsoftware.scim.validator.specs.A6_GroupLifecycleSpec
import de.palsoftware.scim.validator.specs.A7_BulkOperationsSpec
import de.palsoftware.scim.validator.specs.A8_SecurityAndRobustnessSpec
import de.palsoftware.scim.validator.specs.A9_NegativeAndEdgeCasesSpec
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

@Suite
@SelectClasses([
    A1_ServiceDiscoverySpec.class,
    A2_SchemaValidationSpec.class,
    A3_UserCrudSpec.class,
    A4_PatchOperationsSpec.class,
    A5_FilteringSpec.class,
    A5_PaginationSpec.class,
    A5_SortingSpec.class,
    A6_GroupLifecycleSpec.class,
    A7_BulkOperationsSpec.class,
    A8_SecurityAndRobustnessSpec.class,
    A9_NegativeAndEdgeCasesSpec.class
])
class ScimValidatorSuite {
}
