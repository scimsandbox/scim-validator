package de.palsoftware.scim.validator.base

import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification

import java.time.OffsetDateTime

class ScimExchangeCaptureFilter implements Filter {

    @Override
    Response filter(FilterableRequestSpecification requestSpec,
                    FilterableResponseSpecification responseSpec,
                    FilterContext context) {
        Response response = context.next(requestSpec, responseSpec)

        if (ScimRunContext.isCaptureEnabled()) {
            ScimHttpExchange exchange = new ScimHttpExchange(
                method: requestSpec.getMethod(),
                url: requestSpec.getURI(),
                requestHeaders: stringifyRequestHeaders(requestSpec.getHeaders()),
                requestBody: stringifyRequestBody(requestSpec.getBody()),
                responseStatus: response.statusCode(),
                responseHeaders: response.getHeaders()?.toString(),
                responseBody: response.getBody() != null ? response.getBody().asString() : null,
                createdAt: OffsetDateTime.now()
            )
            ScimRunContext.record(exchange)
        }

        return response
    }

    private static String stringifyRequestBody(Object body) {
        if (body == null) {
            return null
        }
        return String.valueOf(body)
    }

    private static String stringifyRequestHeaders(Headers headers) {
        if (headers == null) {
            return null
        }

        List<Header> sanitizedHeaders = headers.asList().findAll { Header header ->
            !"Authorization".equalsIgnoreCase(header.name)
        }
        if (sanitizedHeaders.isEmpty()) {
            return null
        }
        return new Headers(sanitizedHeaders).toString()
    }
}
