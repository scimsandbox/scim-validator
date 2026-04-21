package de.palsoftware.scim.validator.base

import io.restassured.filter.FilterContext
import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.Response
import io.restassured.response.ResponseBody
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import spock.lang.Specification

class ScimExchangeCaptureFilterSpec extends Specification {

    def cleanup() {
        ScimRunContext.endRun()
    }

    def "filter does not capture authorization request headers"() {
        given:
        def filter = new ScimExchangeCaptureFilter()
        def requestSpec = Mock(FilterableRequestSpecification) {
            getMethod() >> "GET"
            getURI() >> "https://example.test/ws/123/scim/v2/Users"
            getHeaders() >> new Headers(
                new Header("Authorization", "Bearer secret-token"),
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
            getHeaders() >> new Headers(new Header("Content-Type", "application/scim+json"))
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
    }
}