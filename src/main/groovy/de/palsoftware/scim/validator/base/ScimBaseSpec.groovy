package de.palsoftware.scim.validator.base

import io.restassured.RestAssured
import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import groovy.json.JsonOutput
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Specification

/**
 * Abstract base class for all SCIM 2.0 compliance test specifications.
 * Configures REST Assured with base URI, auth headers, and provides shared helper methods.
 *
 * Builds the SCIM base URL from SCIM_API_URL and SCIM_WORKSPACE_ID.
 * SCIM_WORKSPACE_ID and SCIM_AUTH_TOKEN must be provided unless SCIM_BASE_URL is set.
 */
abstract class ScimBaseSpec extends Specification {

    private static final InheritableThreadLocal<RuntimeState> STATE = new InheritableThreadLocal<>()

    // ─── Configuration ───────────────────────────────────────────────────
    static String getBASE_URL() {
        return state().baseUrl
    }

    static String getBASE_PATH() {
        return state().basePath
    }

    static String getAUTH_TOKEN() {
        return state().authToken
    }

    static String getWorkspaceId() {
        return state().workspaceId
    }

    static {
        refreshConfiguration()
    }
    static final String SCIM_CONTENT_TYPE = "application/scim+json"
    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User"
    static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group"
    static final String ENTERPRISE_USER_SCHEMA = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"
    static final String PATCH_OP_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp"
    static final String BULK_REQUEST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:BulkRequest"
    static final String BULK_RESPONSE_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:BulkResponse"
    static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error"
    static final String LIST_RESPONSE_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
    static final String SEARCH_REQUEST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:SearchRequest"
    static final String SPC_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"

    // ─── Service Provider Config (loaded once) ───────────────────────────
    static boolean isSpcLoaded() {
        return state().spcLoaded
    }

    static CapabilityState getCapabilityState(String capability) {
        RuntimeState s = state()
        switch (capability?.toLowerCase()) {
            case "patch": return s.patchState ?: CapabilityState.UNKNOWN
            case "bulk": return s.bulkState ?: CapabilityState.UNKNOWN
            case "sort": return s.sortState ?: CapabilityState.UNKNOWN
            case "etag": return s.etagState ?: CapabilityState.UNKNOWN
            case "filter": return s.filterState ?: CapabilityState.UNKNOWN
            case "changepassword": return s.changePasswordState ?: CapabilityState.UNKNOWN
            case "search": return s.searchState ?: CapabilityState.UNKNOWN
            case "enterprise": return s.enterpriseState ?: CapabilityState.UNKNOWN
            default: return CapabilityState.UNKNOWN
        }
    }

    static boolean isPatchSupported() {
        return getCapabilityState("patch") == CapabilityState.SUPPORTED
    }

    static boolean isBulkSupported() {
        return getCapabilityState("bulk") == CapabilityState.SUPPORTED
    }

    static int getBulkMaxOperations() {
        return state().bulkMaxOperations
    }

    static int getFilterMaxResults() {
        return state().filterMaxResults
    }

    static boolean isFilterSupported() {
        return getCapabilityState("filter") == CapabilityState.SUPPORTED
    }

    static boolean isEtagSupported() {
        return getCapabilityState("etag") == CapabilityState.SUPPORTED
    }

    static boolean isSortSupported() {
        return getCapabilityState("sort") == CapabilityState.SUPPORTED
    }

    /**
     * Require that the target server advertises support for the given capability.
     * If the capability is explicitly UNSUPPORTED, the test is skipped via TestAbortedException.
     * If the capability is UNKNOWN, a note is logged and the probe proceeds.
     */
    protected void requireCapability(String capability) {
        CapabilityState state = getCapabilityState(capability)
        if (state == CapabilityState.UNSUPPORTED) {
            String msg = "Skipped: server declares '${capability}' unsupported in /ServiceProviderConfig"
            ScimOutput.note(msg)
            throw new org.opentest4j.TestAbortedException(msg)
        }
        if (state == CapabilityState.UNKNOWN) {
            ScimOutput.note("Probe run: server capability '${capability}' is UNKNOWN (/ServiceProviderConfig not loaded or flag omitted)")
        }
    }

    static boolean isStrictScimType() {
        String strictProp = System.getProperty("scim.strictScimType")
        if (strictProp != null) {
            return Boolean.parseBoolean(strictProp)
        }
        String lenientProp = System.getProperty("scim.lenient")
        if (lenientProp != null && Boolean.parseBoolean(lenientProp)) {
            return false
        }
        return true // default strict
    }


    // ─── Dynamic data generator ──────────────────────────────────────────
    @Shared Faker faker = new Faker()

    // ─── Track created resource IDs for cleanup ──────────────────────────
    @Shared List<String> createdUserIds = []
    @Shared List<String> createdGroupIds = []

    def setupSpec() {
        refreshConfiguration()
        configureRestAssured()
        loadServiceProviderConfig()
    }

    static void resetRunState() {
        STATE.remove()
    }

    protected static void refreshConfiguration() {
        RuntimeState currentState = state()
        ValidatorConfiguration.Config configuration = ValidatorConfiguration.current()
        String explicitBaseUrl = configuration.baseUrl
        String configuredWorkspace = configuration.workspaceId
        String explicitApiUrl = configuration.apiUrl
        String configuredAuthToken = configuration.authToken
        boolean testcontainersEnabled = configuration.testcontainersEnabled

        if (ValidatorTargetConfiguration.shouldBootstrap(configuration)) {
            if (testcontainersEnabled) {
                Iterator<ScimBootstrapProvider> providers =
                    ServiceLoader.load(ScimBootstrapProvider.class, ScimBaseSpec.classLoader).iterator()
                if (!providers.hasNext()) {
                    throw new IllegalStateException(
                        "No ScimBootstrapProvider found on the classpath. " +
                        "Add the scim-validator test-jar to enable the Testcontainers bootstrap, or " +
                        "configure SCIM_BASE_URL and SCIM_AUTH_TOKEN to target an existing endpoint."
                    )
                }
                ScimRuntimeConfiguration runtimeConfiguration = providers.next().bootstrap()
                currentState.workspaceId = runtimeConfiguration.workspaceId
                currentState.authToken = runtimeConfiguration.authToken
                currentState.scimApiUrl = runtimeConfiguration.apiUrl
                currentState.basePath = "/ws/${currentState.workspaceId}/scim/v2"
                currentState.baseUrl = "${currentState.scimApiUrl}${currentState.basePath}"
                return
            }

            throw new IllegalStateException(
                "No SCIM validator target configured. Provide -Dscim.baseUrl and -Dscim.authToken " +
                    "or -Dscim.apiUrl, -Dscim.workspaceId, and -Dscim.authToken, or leave Testcontainers enabled."
            )
        }

        currentState.workspaceId = configuredWorkspace
        currentState.authToken = configuredAuthToken

        if (currentState.authToken == null || currentState.authToken.isBlank()) {
            throw new IllegalStateException("SCIM_AUTH_TOKEN or -Dscim.authToken must be configured for validator runs")
        }

        if (explicitBaseUrl != null && !explicitBaseUrl.isBlank()) {
            URI uri = new URI(explicitBaseUrl.trim())
            currentState.scimApiUrl = "${uri.scheme}://${uri.authority}"
            currentState.basePath = uri.path != null && !uri.path.isBlank() ? uri.path : "/"
            currentState.baseUrl = explicitBaseUrl.trim()
        } else {
            if (currentState.workspaceId == null || currentState.workspaceId.isBlank()) {
                throw new IllegalStateException("SCIM_WORKSPACE_ID or -Dscim.workspaceId must be configured when SCIM_BASE_URL is not set")
            }
            currentState.scimApiUrl = explicitApiUrl
            currentState.basePath = "/ws/${currentState.workspaceId}/scim/v2"
            currentState.baseUrl = "${currentState.scimApiUrl}${currentState.basePath}"
        }
    }

    protected static boolean hasText(String value) {
        return value != null && !value.isBlank()
    }

    protected static void configureRestAssured() {
        state()
    }

    /**
     * Load ServiceProviderConfig once across all specs.
     */
    protected void loadServiceProviderConfig() {
        configureRestAssured()
        RuntimeState currentState = state()
        if (currentState.spcLoaded) return
        try {
            Response response = scimRequestQuiet()
                .get("/ServiceProviderConfig")

            if (response.statusCode() == 200) {
                def json = response.jsonPath()
                currentState.patchState = parseCapability(json.get("patch.supported"))
                currentState.bulkState = parseCapability(json.get("bulk.supported"))
                currentState.bulkMaxOperations = asInt(json.get("bulk.maxOperations"), 0)
                currentState.filterMaxResults = asInt(json.get("filter.maxResults"), 0)
                currentState.filterState = parseCapability(json.get("filter.supported"))
                currentState.etagState = parseCapability(json.get("etag.supported"))
                currentState.sortState = parseCapability(json.get("sort.supported"))
                currentState.changePasswordState = parseCapability(json.get("changePassword.supported"))
                currentState.spcLoaded = true
            } else {
                System.err.println("WARNING: GET /ServiceProviderConfig returned status ${response.statusCode()}; optional capabilities marked UNKNOWN")
                setAllCapabilitiesUnknown(currentState)
            }
        } catch (Exception e) {
            System.err.println("WARNING: Could not load ServiceProviderConfig: ${e.message}; optional capabilities marked UNKNOWN")
            setAllCapabilitiesUnknown(currentState)
        }
    }

    private static void setAllCapabilitiesUnknown(RuntimeState state) {
        state.patchState = CapabilityState.UNKNOWN
        state.bulkState = CapabilityState.UNKNOWN
        state.filterState = CapabilityState.UNKNOWN
        state.etagState = CapabilityState.UNKNOWN
        state.sortState = CapabilityState.UNKNOWN
        state.changePasswordState = CapabilityState.UNKNOWN
        state.searchState = CapabilityState.UNKNOWN
        state.enterpriseState = CapabilityState.UNKNOWN
    }

    private static CapabilityState parseCapability(Object val) {
        if (val == null) return CapabilityState.UNKNOWN
        if (val instanceof Boolean) return ((Boolean) val) ? CapabilityState.SUPPORTED : CapabilityState.UNSUPPORTED
        if (val instanceof String) {
            String s = ((String) val).trim().toLowerCase()
            if (s == "true") return CapabilityState.SUPPORTED
            if (s == "false") return CapabilityState.UNSUPPORTED
        }
        return CapabilityState.UNKNOWN
    }

    protected static boolean asBoolean(Object value, boolean defaultValue = false) {
        if (value == null) return defaultValue
        if (value instanceof Boolean) return (Boolean) value
        if (value instanceof String) {
            String normalized = ((String) value).trim().toLowerCase()
            if (normalized == "true") return true
            if (normalized == "false") return false
        }
        return defaultValue
    }

    protected static int asInt(Object value, int defaultValue = 0) {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).intValue()
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim())
            } catch (NumberFormatException ignored) {
                return defaultValue
            }
        }
        return defaultValue
    }

    /**
     * Build a REST Assured request with default SCIM headers.
     */
    protected RequestSpecification scimRequest() {
        configureRestAssured()
        RuntimeState currentState = state()
        def req = RestAssured.given()
            .baseUri(currentState.scimApiUrl)
            .basePath(currentState.basePath)
            .filter(new ScimExchangeCaptureFilter())
            .header("Authorization", "Bearer ${currentState.authToken}")
            .contentType(SCIM_CONTENT_TYPE)
            .accept(SCIM_CONTENT_TYPE)

        // Only attach RestAssured console logging when not capturing exchanges
        if (!ScimRunContext.isCaptureEnabled()) {
            req = req.filter(new RequestLoggingFilter()).filter(new ResponseLoggingFilter())
        }

        return req
    }

    /**
     * Build a REST Assured request without logging (for cleanup operations).
     */
    protected RequestSpecification scimRequestQuiet() {
        configureRestAssured()
        RuntimeState currentState = state()
        return RestAssured.given()
            .baseUri(currentState.scimApiUrl)
            .basePath(currentState.basePath)
            .filter(new ScimExchangeCaptureFilter())
            .header("Authorization", "Bearer ${currentState.authToken}")
            .contentType(SCIM_CONTENT_TYPE)
            .accept(SCIM_CONTENT_TYPE)
    }

    /**
     * Build a REST Assured request without an Authorization header.
     */
    protected RequestSpecification scimRequestAnonymous() {
        configureRestAssured()
        RuntimeState currentState = state()
        return RestAssured.given()
            .baseUri(currentState.scimApiUrl)
            .basePath(currentState.basePath)
            .filter(new ScimExchangeCaptureFilter())
            .contentType(SCIM_CONTENT_TYPE)
            .accept(SCIM_CONTENT_TYPE)
    }

    private static RuntimeState state() {
        RuntimeState currentState = STATE.get()
        if (currentState == null) {
            currentState = new RuntimeState()
            STATE.set(currentState)
        }
        return currentState
    }

    private static final class RuntimeState {
        String scimApiUrl
        String baseUrl
        String basePath
        String authToken
        String workspaceId
        boolean spcLoaded
        CapabilityState patchState = CapabilityState.UNKNOWN
        CapabilityState bulkState = CapabilityState.UNKNOWN
        int bulkMaxOperations
        int filterMaxResults
        CapabilityState filterState = CapabilityState.UNKNOWN
        CapabilityState etagState = CapabilityState.UNKNOWN
        CapabilityState sortState = CapabilityState.UNKNOWN
        CapabilityState changePasswordState = CapabilityState.UNKNOWN
        CapabilityState searchState = CapabilityState.UNKNOWN
        CapabilityState enterpriseState = CapabilityState.UNKNOWN
    }

    /**
     * Create a minimal SCIM User and return the response.
     */
    protected Response createUser(Map overrides = [:]) {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8)
        Map payload = [
            schemas : [USER_SCHEMA],
            userName: overrides.userName ?: "testuser_${uniqueSuffix}_${faker.name().username()}@test.com",
            emails  : overrides.emails ?: [
                [value: "user_${uniqueSuffix}@test.com", type: "work", primary: true]
            ]
        ]

        // 'enterprise' is only meaningful for createFullUser; copying it verbatim would
        // emit a bogus top-level "enterprise" member, so it is filtered here as well.
        overrides.each { k, v ->
            if (k != 'enterprise') {
                payload[k] = v
            }
        }

        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) createdUserIds << id
        }
        return response
    }

    /**
     * Create a full SCIM User with enterprise extension and return the response.
     */
    protected Response createFullUser(Map overrides = [:]) {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8)
        String fakeEmail = "work_${uniqueSuffix}@${faker.internet().domainName()}"
        String homeEmail = "home_${uniqueSuffix}@${faker.internet().domainName()}"

        Map payload = [
            schemas     : [USER_SCHEMA, ENTERPRISE_USER_SCHEMA],
            userName    : overrides.userName ?: "fulluser_${uniqueSuffix}_${faker.name().username()}@test.com",
            displayName : overrides.displayName ?: faker.name().fullName(),
            active      : overrides.containsKey('active') ? overrides.active : true,
            name        : overrides.name ?: [
                givenName : faker.name().firstName(),
                familyName: faker.name().lastName()
            ],
            emails      : overrides.emails ?: [
                [value: fakeEmail, type: "work", primary: true],
                [value: homeEmail, type: "home", primary: false]
            ],
            ("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"): overrides.enterprise ?: [
                employeeNumber: faker.number().digits(6),
                department    : faker.commerce().department()
            ]
        ]

        overrides.each { k, v ->
            if (k != 'enterprise' && k != 'schemas') {
                payload[k] = v
            }
        }

        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Users")

        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) createdUserIds << id
        }
        return response
    }

    /**
     * Create a SCIM Group and return the response.
     */
    protected Response createGroup(String displayName, List<String> memberIds = []) {
        Map payload = [
            schemas    : [GROUP_SCHEMA],
            displayName: displayName
        ]
        if (memberIds) {
            payload.members = memberIds.collect { [value: it] }
        }

        Response response = scimRequestQuiet()
            .body(JsonOutput.toJson(payload))
            .post("/Groups")

        if (response.statusCode() == 201) {
            String id = response.jsonPath().getString("id")
            if (id) createdGroupIds << id
        }
        return response
    }

    /**
     * Delete a user by ID (silently, for cleanup).
     */
    protected void deleteUser(String id) {
        if (!id) return
        try {
            scimRequestQuiet().delete("/Users/${id}")
        } catch (Exception ignored) {}
    }

    /**
     * Delete a group by ID (silently, for cleanup).
     */
    protected void deleteGroup(String id) {
        if (!id) return
        try {
            scimRequestQuiet().delete("/Groups/${id}")
        } catch (Exception ignored) {}
    }

    /**
     * Clean up all tracked resources.
     */
    def cleanupSpec() {
        createdGroupIds.each { deleteGroup(it) }
        createdUserIds.each { deleteUser(it) }
        createdGroupIds.clear()
        createdUserIds.clear()
    }

    /**
     * Build a PATCH operation payload.
     */
    protected Map buildPatchOp(List<Map> operations) {
        return [
            schemas   : [PATCH_OP_SCHEMA],
            Operations: operations
        ]
    }

    /**
     * Assert standard SCIM error response body structure.
     * RFC 7644 §3.12:
     * - HTTP status code matches expectedStatus
     * - schemas MUST contain "urn:ietf:params:scim:api:messages:2.0:Error"
     * - status attribute MUST match HTTP status code as string
     * - scimType is OPTIONAL; when the server supplies one it MUST match
     *   expectedScimType, when it omits one a deviation is logged instead
     */
    protected void assertScimError(Response response, int expectedStatus, String expectedScimType = null) {
        assert response.statusCode() == expectedStatus :
            "HTTP status ${response.statusCode()} != expected ${expectedStatus}"
        def schemas = response.jsonPath().getList("schemas")
        assert schemas != null && schemas.contains(ERROR_SCHEMA) :
            "Response schemas ${schemas} must contain ${ERROR_SCHEMA} (RFC 7644 §3.12)"
        String statusAttr = response.jsonPath().getString("status")
        assert statusAttr == String.valueOf(expectedStatus) :
            "SCIM error status attribute '${statusAttr}' must equal '${expectedStatus}' (RFC 7644 §3.12)"
        if (expectedScimType != null) {
            assertScimType(response, expectedScimType)
        }
    }

    /**
     * Assert the SCIM detail error keyword when the server supplies one.
     *
     * In strict mode (default for validator runs), an omitted scimType is a failure
     * when the test expects a specific keyword. In lenient mode (-Dscim.lenient=true),
     * an omitted keyword is recorded as a structured deviation.
     * A keyword that is present but not among the expected values is always a failure.
     */
     protected void assertScimType(Response response, String... expectedScimTypes) {
         List<String> expected = expectedScimTypes as List<String>
         String scimType = response.jsonPath().getString("scimType")
         if (scimType == null || scimType.isBlank()) {
             String msg = "HTTP ${response.statusCode()} omits optional scimType (expected one of ${expected}) (RFC 7644 §3.12)"
             ScimOutput.deviation(msg, null, "RFC7644-3.12-R1", "omitted", expected.toString(), getBASE_URL())
             if (isStrictScimType()) {
                 assert false : "Strict mode: ${msg}"
             }
             return
         }
         assert expected.contains(scimType) :
             "Expected scimType one of ${expected} but got '${scimType}' (RFC 7644 §3.12)"
     }
}
