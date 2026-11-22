package de.palsoftware.scim.validator.base

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.Generated
import io.restassured.response.Response

import java.nio.charset.StandardCharsets

/**
 * Safe JSON parsing and bounded stream-reading helpers.
 *
 * <p>{@link #parseMap(String)} and {@link #parseMap(Response)} parse JSON via Jackson rather than
 * REST Assured's GPath/{@code GroovyShell} evaluator—preventing code execution on untrusted attribute
 * names—and enforce document length ({@link #DEFAULT_MAX_RESPONSE_BYTES}), string length, and nesting
 * depth ({@link #MAX_JSON_NESTING_DEPTH}) limits before allocating {@code Map}/{@code List} AST nodes.
 * Note that {@link #parseMap(Response)} inspects a response body already buffered by REST Assured, whereas
 * {@link #readBoundedUtf8(InputStream, int)} bounds raw HTTP stream reads prior to buffering.</p>
 */
final class ScimJsonSupport {

    static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    static final int MAX_JSON_NESTING_DEPTH = 64
    private static final Set<String> STANDARD_SUB_ID_KEYS = Set.of("format", "uri", "externalId", "id")
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {}
    private static final ObjectMapper OBJECT_MAPPER = createSafeObjectMapper()

    @Generated
    private ScimJsonSupport() {
    }

    private static ObjectMapper createSafeObjectMapper() {
        ObjectMapper mapper = new ObjectMapper()
        mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxDocumentLength(DEFAULT_MAX_RESPONSE_BYTES)
                .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                .maxStringLength(DEFAULT_MAX_RESPONSE_BYTES)
                .build()
        )
        return mapper
    }

    /**
     * Parses a REST Assured {@link Response} body (already buffered by REST Assured) into a {@code Map}
     * without GPath/{@code GroovyShell} evaluation, enforcing {@link #DEFAULT_MAX_RESPONSE_BYTES} and
     * {@link #MAX_JSON_NESTING_DEPTH}.
     */
    static Map<String, Object> parseMap(Response response) {
        if (response == null || response.getBody() == null) {
            return Collections.emptyMap()
        }
        return parseMap(response.getBody().asString(), DEFAULT_MAX_RESPONSE_BYTES)
    }

    /**
     * Parses {@code rawJson} into a {@code Map} without GPath/{@code GroovyShell} evaluation,
     * rejecting payloads exceeding {@link #DEFAULT_MAX_RESPONSE_BYTES} or {@link #MAX_JSON_NESTING_DEPTH}.
     */
    static Map<String, Object> parseMap(String rawJson) {
        return parseMap(rawJson, DEFAULT_MAX_RESPONSE_BYTES)
    }

    static Map<String, Object> parseMap(String rawJson, int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative")
        }
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyMap()
        }
        enforceMaxJsonBytes(rawJson, maxBytes)
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(rawJson, MAP_TYPE)
            return parsed != null ? parsed : Collections.emptyMap()
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse SCIM JSON object: " + e.getMessage(), e)
        }
    }

    private static void enforceMaxJsonBytes(String rawJson, int maxBytes) {
        if (rawJson.length() > maxBytes) {
            throw new IllegalArgumentException(maxSizeExceededMessage("SCIM JSON payload", maxBytes))
        }
        if (rawJson.length() * 3L > maxBytes && rawJson.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(maxSizeExceededMessage("SCIM JSON payload", maxBytes))
        }
    }

    private static String maxSizeExceededMessage(String label, int maxBytes) {
        return label + " exceeded maximum allowed size of " + maxBytes + " bytes"
    }

    /**
     * Performs a literal top-level key lookup on a parsed SCIM JSON map without invoking
     * GPath or GroovyShell expression evaluation.
     */
    static Object getLiteralAttribute(Map<String, Object> resourceMap, String attributeName) {
        if (resourceMap == null || attributeName == null) {
            return null
        }
        return resourceMap.get(attributeName)
    }

    static Set<String> extractExtraSubIdAttributeNames(Map<?, ?> subId) {
        if (subId == null || subId.isEmpty()) {
            return Collections.emptySet()
        }
        Set<String> extra = new LinkedHashSet<>()
        for (Object rawKey : subId.keySet()) {
            if (rawKey != null) {
                String key = String.valueOf(rawKey)
                if (!STANDARD_SUB_ID_KEYS.contains(key)) {
                    extra.add(key)
                }
            }
        }
        return extra
    }

    /**
     * Reads at most {@code maxBytes} from {@code inputStream} as UTF-8 text before buffering the
     * full stream, throwing an {@link IllegalStateException} immediately if the stream exceeds {@code maxBytes}.
     */
    static String readBoundedUtf8(InputStream inputStream, int maxBytes = DEFAULT_MAX_RESPONSE_BYTES) {
        if (inputStream == null) {
            return ""
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative")
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192))
        byte[] chunk = new byte[8192]
        int totalRead = 0
        int read
        while ((read = inputStream.read(chunk)) != -1) {
            totalRead += read
            if (totalRead > maxBytes) {
                throw new IllegalStateException(maxSizeExceededMessage("HTTP response body", maxBytes))
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(StandardCharsets.UTF_8)
    }
}
