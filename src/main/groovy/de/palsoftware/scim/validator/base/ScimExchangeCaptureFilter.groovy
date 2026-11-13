package de.palsoftware.scim.validator.base

import io.restassured.filter.Filter
import io.restassured.filter.FilterContext
import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.Response
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

class ScimExchangeCaptureFilter implements Filter {

    static final int MAX_CAPTURED_BODY_BYTES = 64 * 1024
    static final String TRUNCATED_SUFFIX = "...[truncated]"
    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key"
    )

    @Override
    Response filter(FilterableRequestSpecification requestSpec,
                    FilterableResponseSpecification responseSpec,
                    FilterContext context) {
        Response response = context.next(requestSpec, responseSpec)

        if (ScimRunContext.isCaptureEnabled()) {
            ScimHttpExchange exchange = new ScimHttpExchange(
                method: requestSpec.getMethod(),
                url: requestSpec.getURI(),
                requestHeaders: stringifySanitizedHeaders(requestSpec.getHeaders()),
                requestBody: stringifyBody(requestSpec.getBody()),
                responseStatus: response.statusCode(),
                responseHeaders: stringifySanitizedHeaders(response.getHeaders()),
                responseBody: response.getBody() != null ? truncateUtf8(response.getBody().asString(), MAX_CAPTURED_BODY_BYTES) : null,
                createdAt: OffsetDateTime.now()
            )
            ScimRunContext.record(exchange)
        }

        return response
    }

    static String truncateUtf8(String value, int maxBytes = MAX_CAPTURED_BODY_BYTES) {
        if (value == null) {
            return null
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8)
        if (utf8.length <= maxBytes) {
            return value
        }
        if (maxBytes <= 0) {
            return TRUNCATED_SUFFIX
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
        ByteBuffer byteBuffer = ByteBuffer.wrap(utf8, 0, maxBytes)
        CharBuffer charBuffer = CharBuffer.allocate(maxBytes)
        decoder.decode(byteBuffer, charBuffer, true)
        decoder.flush(charBuffer)
        charBuffer.flip()
        return charBuffer.toString() + TRUNCATED_SUFFIX
    }

    private static String stringifyBody(Object body) {
        if (body == null) {
            return null
        }
        return truncateUtf8(String.valueOf(body), MAX_CAPTURED_BODY_BYTES)
    }

    private static String stringifySanitizedHeaders(Headers headers) {
        if (headers == null) {
            return null
        }

        List<Header> sanitizedHeaders = headers.asList().findAll { Header header ->
            header?.name != null && !SENSITIVE_HEADER_NAMES.contains(header.name.toLowerCase(Locale.ROOT))
        }
        if (sanitizedHeaders.isEmpty()) {
            return null
        }
        return new Headers(sanitizedHeaders).toString()
    }
}
