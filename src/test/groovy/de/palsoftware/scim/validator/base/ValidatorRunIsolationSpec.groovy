package de.palsoftware.scim.validator.base

import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.specification.FilterableRequestSpecification
import spock.lang.Specification

import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class ValidatorRunIsolationSpec extends Specification {

    def "concurrent runs keep validator state isolated across inherited child threads and request builders"() {
        given:
        Map<String, Object> defaultConfiguration = snapshotConfiguration(ValidatorConfiguration.current())
        Map<String, Map<String, Object>> results = new ConcurrentHashMap<>()
        List<Throwable> failures = new CopyOnWriteArrayList<>()

        Thread runOne = new Thread({
            captureStateForRun("run-1", "https://example-1.test/ws/1/scim/v2", "token-1", results, failures)
        })
        Thread runTwo = new Thread({
            captureStateForRun("run-2", "https://example-2.test/ws/2/scim/v2", "token-2", results, failures)
        })

        when:
        runOne.start()
        runTwo.start()
        runOne.join()
        runTwo.join()

        then:
        failures.isEmpty()
        results["run-1"].configuration.baseUrl == "https://example-1.test/ws/1/scim/v2"
        results["run-1"].configuration.basePath == "/ws/1/scim/v2"
        results["run-1"].configuration.authToken == "token-1"
        results["run-1"].childConfiguration == results["run-1"].configuration
        results["run-1"].captureEnabled
        results["run-1"].childCaptureEnabled
        results["run-1"].requestBuilders.authenticated.baseUri == "https://example-1.test"
        results["run-1"].requestBuilders.authenticated.basePath == "/ws/1/scim/v2"
        results["run-1"].requestBuilders.authenticated.authorization == "Bearer token-1"
        results["run-1"].requestBuilders.authenticated.filters.contains("ScimExchangeCaptureFilter")
        !results["run-1"].requestBuilders.authenticated.filters.contains("RequestLoggingFilter")
        !results["run-1"].requestBuilders.authenticated.filters.contains("ResponseLoggingFilter")
        results["run-1"].requestBuilders.quiet.authorization == "Bearer token-1"
        results["run-1"].requestBuilders.anonymous.authorization == null
        results["run-1"].requestBuilders.anonymous.baseUri == "https://example-1.test"
        results["run-1"].requestBuilders.anonymous.basePath == "/ws/1/scim/v2"
        results["run-1"].exchanges*.url as Set == [
            "https://example-1.test/ws/1/scim/v2/Users",
            "https://example-1.test/ws/1/scim/v2/Groups"
        ] as Set

        results["run-2"].configuration.baseUrl == "https://example-2.test/ws/2/scim/v2"
        results["run-2"].configuration.basePath == "/ws/2/scim/v2"
        results["run-2"].configuration.authToken == "token-2"
        results["run-2"].childConfiguration == results["run-2"].configuration
        results["run-2"].captureEnabled
        results["run-2"].childCaptureEnabled
        results["run-2"].requestBuilders.authenticated.baseUri == "https://example-2.test"
        results["run-2"].requestBuilders.authenticated.basePath == "/ws/2/scim/v2"
        results["run-2"].requestBuilders.authenticated.authorization == "Bearer token-2"
        results["run-2"].requestBuilders.authenticated.filters.contains("ScimExchangeCaptureFilter")
        !results["run-2"].requestBuilders.authenticated.filters.contains("RequestLoggingFilter")
        !results["run-2"].requestBuilders.authenticated.filters.contains("ResponseLoggingFilter")
        results["run-2"].requestBuilders.quiet.authorization == "Bearer token-2"
        results["run-2"].requestBuilders.anonymous.authorization == null
        results["run-2"].requestBuilders.anonymous.baseUri == "https://example-2.test"
        results["run-2"].requestBuilders.anonymous.basePath == "/ws/2/scim/v2"
        results["run-2"].exchanges*.url as Set == [
            "https://example-2.test/ws/2/scim/v2/Users",
            "https://example-2.test/ws/2/scim/v2/Groups"
        ] as Set

        !ScimRunContext.isCaptureEnabled()
        snapshotConfiguration(ValidatorConfiguration.current()) == defaultConfiguration
    }

    def "ending a run clears captured exchanges and clearing overrides restores defaults"() {
        given:
        Map<String, Object> defaultConfiguration = snapshotConfiguration(ValidatorConfiguration.current())
        Map<String, String> activeConfiguration
        List<ScimHttpExchange> capturedBeforeEnd
        List<ScimHttpExchange> capturedAfterRestart
        Map<String, Object> restoredConfiguration

        when:
        ValidatorConfiguration.useRunOverrides("https://cleanup.test/ws/cleanup/scim/v2", "cleanup-token")
        TestScimBaseSpec.resetRunState()
        ScimRunContext.beginRun("cleanup")
        ScimRunContext.beginTest("test-cleanup")
        ScimRunContext.record(new ScimHttpExchange(
            method: "GET",
            url: "https://cleanup.test/ws/cleanup/scim/v2/Users",
            createdAt: OffsetDateTime.now()
        ))
        activeConfiguration = TestScimBaseSpec.snapshotConfiguration()
        capturedBeforeEnd = ScimRunContext.getForTest("test-cleanup")

        ScimRunContext.endRun()
        TestScimBaseSpec.resetRunState()
        ValidatorConfiguration.clearRunOverrides()

        ScimRunContext.beginRun("cleanup")
        capturedAfterRestart = ScimRunContext.getForTest("test-cleanup")
        ScimRunContext.endRun()
        restoredConfiguration = snapshotConfiguration(ValidatorConfiguration.current())

        then:
        activeConfiguration.baseUrl == "https://cleanup.test/ws/cleanup/scim/v2"
        activeConfiguration.basePath == "/ws/cleanup/scim/v2"
        activeConfiguration.authToken == "cleanup-token"
        capturedBeforeEnd*.url == ["https://cleanup.test/ws/cleanup/scim/v2/Users"]
        capturedAfterRestart.isEmpty()
        !ScimRunContext.isCaptureEnabled()
        restoredConfiguration == defaultConfiguration
    }

    private static void captureStateForRun(String runId,
                                           String baseUrl,
                                           String authToken,
                                           Map<String, Map<String, Object>> results,
                                           List<Throwable> failures) {
        try {
            ValidatorConfiguration.useRunOverrides(baseUrl, authToken)
            TestScimBaseSpec.resetRunState()
            ScimRunContext.beginRun(runId)
            ScimRunContext.beginTest("test-${runId}")
            boolean captureEnabled = ScimRunContext.isCaptureEnabled()
            ScimRunContext.record(new ScimHttpExchange(
                method: "GET",
                url: "${baseUrl}/Users",
                createdAt: OffsetDateTime.now()
            ))

            AtomicReference<Map<String, String>> childConfiguration = new AtomicReference<>()
            AtomicReference<Map<String, Map<String, Object>>> childRequestBuilders = new AtomicReference<>()
            AtomicReference<Boolean> childCaptureEnabled = new AtomicReference<>(false)
            Thread child = new Thread({
                childConfiguration.set(TestScimBaseSpec.snapshotConfiguration())
                childRequestBuilders.set(TestScimBaseSpec.snapshotRequestBuilders())
                childCaptureEnabled.set(ScimRunContext.isCaptureEnabled())
                ScimRunContext.record(new ScimHttpExchange(
                    method: "GET",
                    url: "${baseUrl}/Groups",
                    createdAt: OffsetDateTime.now()
                ))
            })
            child.start()
            child.join()

            results[runId] = [
                configuration      : TestScimBaseSpec.snapshotConfiguration(),
                childConfiguration : childConfiguration.get(),
                requestBuilders    : TestScimBaseSpec.snapshotRequestBuilders(),
                childRequestBuilders: childRequestBuilders.get(),
                captureEnabled     : captureEnabled,
                childCaptureEnabled: childCaptureEnabled.get(),
                exchanges          : ScimRunContext.getForTest("test-${runId}")
            ]
        } catch (Throwable throwable) {
            failures.add(throwable)
        } finally {
            ScimRunContext.endRun()
            TestScimBaseSpec.resetRunState()
            ValidatorConfiguration.clearRunOverrides()
        }
    }

    private static Map<String, Object> snapshotConfiguration(ValidatorConfiguration.Config config) {
        return [
            baseUrl   : config.baseUrl,
            apiUrl    : config.apiUrl,
            workspaceId: config.workspaceId,
            authToken : config.authToken
        ]
    }

    private static final class TestScimBaseSpec extends ScimBaseSpec {

        static Map<String, String> snapshotConfiguration() {
            refreshConfiguration()
            return [
                baseUrl  : BASE_URL,
                basePath : BASE_PATH,
                authToken: AUTH_TOKEN
            ]
        }

        static Map<String, Map<String, Object>> snapshotRequestBuilders() {
            TestScimBaseSpec spec = new TestScimBaseSpec()
            return [
                authenticated: snapshotRequest(spec.scimRequest() as FilterableRequestSpecification),
                quiet        : snapshotRequest(spec.scimRequestQuiet() as FilterableRequestSpecification),
                anonymous    : snapshotRequest(spec.scimRequestAnonymous() as FilterableRequestSpecification)
            ]
        }

        private static Map<String, Object> snapshotRequest(FilterableRequestSpecification request) {
            return [
                baseUri      : request.baseUri,
                basePath     : request.basePath,
                contentType  : request.contentType,
                authorization: request.headers.getValue("Authorization"),
                filters      : request.definedFilters.collect { it.class.simpleName }
            ]
        }
    }
}