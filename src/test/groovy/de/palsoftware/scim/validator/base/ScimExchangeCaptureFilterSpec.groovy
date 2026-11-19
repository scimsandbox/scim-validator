package de.palsoftware.scim.validator.base

import io.restassured.filter.FilterContext
import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.Response
import io.restassured.response.ResponseBody
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class ScimExchangeCaptureFilterSpec extends Specification {

    def cleanup() {
        ScimRunContext.endRun()
    }

    def "SENSITIVE_HEADER_NAMES exposes canonical immutable header set including X-API-Key"() {
        expect:
        ScimExchangeCaptureFilter.SENSITIVE_HEADER_NAMES == [
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "Set-Cookie",
            "X-API-Key"
        ] as Set

        when:
        ScimExchangeCaptureFilter.SENSITIVE_HEADER_NAMES.add("X-Custom")

        then:
        thrown(UnsupportedOperationException)
    }

    def "filter redacts sensitive request and response headers case-insensitively"() {
        given:
        def filter = new ScimExchangeCaptureFilter()
        def requestSpec = Mock(FilterableRequestSpecification) {
            getMethod() >> "GET"
            getURI() >> "https://example.test/ws/123/scim/v2/Users"
            getHeaders() >> new Headers(
                new Header("Authorization", "Bearer secret-token"),
                new Header("proxy-authorization", "Basic c2VjcmV0"),
                new Header("COOKIE", "SESSION=secret-cookie"),
                new Header("X-API-Key", "secret-api-key"),
                new Header("x-api-key", "lowercase-api-key"),
                new Header("Accept", "application/scim+json"),
                new Header("Content-Type", "application/scim+json")
            )
            getBody() >> null
        }
        def responseSpec = Mock(FilterableResponseSpecification)
        def responseBody = Stub(ResponseBody) {
            asString() >> '{"ok":true}'
        }
        def response = Mock(Response) {
            statusCode() >> 200
            getHeaders() >> new Headers(
                new Header("Content-Type", "application/scim+json"),
                new Header("Set-Cookie", "SESSION=rotated-secret; HttpOnly"),
                new Header("X-API-Key", "echoed-secret-key")
            )
            getBody() >> responseBody
        }
        def context = Mock(FilterContext) {
            next(requestSpec, responseSpec) >> response
        }
        ScimRunContext.beginRun("run-1")
        ScimRunContext.beginTest("test-1")

        when:
        filter.filter(requestSpec, responseSpec, context)

        then:
        def exchanges = ScimRunContext.getForTest("test-1")
        exchanges.size() == 1
        exchanges[0].requestHeaders == "Accept=application/scim+json\nContent-Type=application/scim+json"
        !exchanges[0].requestHeaders.contains("Authorization")
        !exchanges[0].requestHeaders.contains("secret-token")
        !exchanges[0].requestHeaders.contains("Cookie")
        !exchanges[0].requestHeaders.contains("X-API-Key")
        !exchanges[0].requestHeaders.contains("secret-api-key")
        !exchanges[0].requestHeaders.contains("lowercase-api-key")
        exchanges[0].responseHeaders == "Content-Type=application/scim+json"
        !exchanges[0].responseHeaders.contains("Set-Cookie")
        !exchanges[0].responseHeaders.contains("rotated-secret")
        !exchanges[0].responseHeaders.contains("echoed-secret-key")
    }

    def "filter truncates oversized request and response bodies at 64 KiB UTF-8 safely"() {
        given:
        def filter = new ScimExchangeCaptureFilter()
        String oversizedRequest = "A" * (ScimExchangeCaptureFilter.MAX_CAPTURED_BODY_BYTES + 2048)
        // Place multi-byte UTF-8 characters ('🚀' = 4 bytes) across the 64 KiB boundary
        String prefix = "x" * (ScimExchangeCaptureFilter.MAX_CAPTURED_BODY_BYTES - 3)
        String oversizedResponse = prefix + "🚀" + ("Z" * 512)

        def requestSpec = Mock(FilterableRequestSpecification) {
            getMethod() >> "POST"
            getURI() >> "https://example.test/ws/123/scim/v2/Users"
            getHeaders() >> new Headers(new Header("Content-Type", "application/scim+json"))
            getBody() >> oversizedRequest
        }
        def responseSpec = Mock(FilterableResponseSpecification)
        def responseBody = Stub(ResponseBody) {
            asString() >> oversizedResponse
        }
        def response = Mock(Response) {
            statusCode() >> 201
            getHeaders() >> new Headers(new Header("Content-Type", "application/scim+json"))
            getBody() >> responseBody
        }
        def context = Mock(FilterContext) {
            next(requestSpec, responseSpec) >> response
        }
        ScimRunContext.beginRun("run-trunc")
        ScimRunContext.beginTest("test-trunc")

        when:
        filter.filter(requestSpec, responseSpec, context)

        then:
        def exchanges = ScimRunContext.getForTest("test-trunc")
        exchanges.size() == 1
        exchanges[0].requestBody.endsWith(ScimExchangeCaptureFilter.TRUNCATED_SUFFIX)
        exchanges[0].requestBody.length() == ScimExchangeCaptureFilter.MAX_CAPTURED_BODY_BYTES + ScimExchangeCaptureFilter.TRUNCATED_SUFFIX.length()

        exchanges[0].responseBody.endsWith(ScimExchangeCaptureFilter.TRUNCATED_SUFFIX)
        !exchanges[0].responseBody.contains("\uFFFD")
        String withoutSuffix = exchanges[0].responseBody.substring(
            0,
            exchanges[0].responseBody.length() - ScimExchangeCaptureFilter.TRUNCATED_SUFFIX.length()
        )
        withoutSuffix.getBytes(StandardCharsets.UTF_8).length <= ScimExchangeCaptureFilter.MAX_CAPTURED_BODY_BYTES
        withoutSuffix == prefix
    }

    def "filter handles disabled capture, null headers/bodies, all-sensitive headers, and edge truncation limits"() {
        given:
        def filter = new ScimExchangeCaptureFilter()
        def requestSpec = Mock(FilterableRequestSpecification) {
            getMethod() >> "DELETE"
            getURI() >> "https://example.test/ws/123/scim/v2/Users/u-1"
            getHeaders() >> new Headers(new Header("Authorization", "Bearer only-secret"))
            getBody() >> null
        }
        def responseSpec = Mock(FilterableResponseSpecification)
        def response = Mock(Response) {
            statusCode() >> 204
            getHeaders() >> null
            getBody() >> null
        }
        def context = Mock(FilterContext) {
            next(requestSpec, responseSpec) >> response
        }

        when: "capture mode is disabled"
        Response passThrough = filter.filter(requestSpec, responseSpec, context)

        then: "no exchange is recorded"
        passThrough.is(response)
        !ScimRunContext.isCaptureEnabled()

        when: "capture mode is enabled with null response headers/body and sensitive-only request headers"
        ScimRunContext.beginRun("run-edge")
        ScimRunContext.beginTest("test-edge")
        filter.filter(requestSpec, responseSpec, context)

        then:
        def exchanges = ScimRunContext.getForTest("test-edge")
        exchanges.size() == 1
        exchanges[0].requestHeaders == null
        exchanges[0].requestBody == null
        exchanges[0].responseHeaders == null
        exchanges[0].responseBody == null
        ScimExchangeCaptureFilter.truncateUtf8(null) == null
        ScimExchangeCaptureFilter.truncateUtf8("short", 10) == "short"
        ScimExchangeCaptureFilter.truncateUtf8("non-empty", 0) == ScimExchangeCaptureFilter.TRUNCATED_SUFFIX
        ScimExchangeCaptureFilter.truncateUtf8("non-empty", -5) == ScimExchangeCaptureFilter.TRUNCATED_SUFFIX
    }
}
