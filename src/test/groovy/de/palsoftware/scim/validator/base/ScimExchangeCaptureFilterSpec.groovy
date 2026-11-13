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

    def "filter redacts sensitive request and response headers"() {
        given:
        def filter = new ScimExchangeCaptureFilter()
        def requestSpec = Mock(FilterableRequestSpecification) {
            getMethod() >> "GET"
            getURI() >> "https://example.test/ws/123/scim/v2/Users"
            getHeaders() >> new Headers(
                new Header("Authorization", "Bearer secret-token"),
                new Header("Proxy-Authorization", "Basic c2VjcmV0"),
                new Header("Cookie", "SESSION=secret-cookie"),
                new Header("X-API-Key", "secret-api-key"),
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
                new Header("Set-Cookie", "SESSION=rotated-secret; HttpOnly")
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
        exchanges[0].responseHeaders == "Content-Type=application/scim+json"
        !exchanges[0].responseHeaders.contains("Set-Cookie")
        !exchanges[0].responseHeaders.contains("rotated-secret")
    }

    def "filter truncates oversized request and response bodies at 64 KiB UTF-8 safely"() {
        given:
        def filter = new ScimExchangeCaptureFilter()
        String oversizedRequest = "A" * (ScimExchangeCaptureFilter.MAX_CAPTURED_BODY_BYTES + 2048)
        // Place multi-byte UTF-8 characters ('é' = 2 bytes, '🚀' = 4 bytes) across the 64 KiB boundary
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
}
