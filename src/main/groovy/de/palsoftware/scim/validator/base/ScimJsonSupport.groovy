package de.palsoftware.scim.validator.base

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.response.Response

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Safe JSON parsing and bounded response helpers that avoid REST Assured's GPath/GroovyShell
 * evaluation on untrusted server-supplied keys or payloads.
 */
final class ScimJsonSupport {

    static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    static final int MAX_JSON_NESTING_DEPTH = 64
    private static final Set<String> STANDARD_SUB_ID_KEYS = Set.of("format", "uri", "externalId", "id")
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {}
    private static final ObjectMapper OBJECT_MAPPER = createSafeObjectMapper()

    private ScimJsonSupport() {
    }

    private static ObjectMapper createSafeObjectMapper() {
        ObjectMapper mapper = new ObjectMapper()
        mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                .maxStringLength(DEFAULT_MAX_RESPONSE_BYTES)
                .build()
        )
        return mapper
    }

    static Map<String, Object> parseMap(Response response) {
        if (response == null || response.getBody() == null) {
            return Collections.emptyMap()
        }
        return parseMap(response.getBody().asString())
    }

    static Map<String, Object> parseMap(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyMap()
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(rawJson, MAP_TYPE)
            return parsed != null ? parsed : Collections.emptyMap()
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse SCIM JSON object: " + e.getMessage(), e)
        }
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
     * Reads at most {@code maxBytes} from {@code inputStream} as UTF-8 text, throwing an
     * {@link IllegalStateException} immediately if the stream exceeds {@code maxBytes}.
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
                throw new IllegalStateException("HTTP response body exceeded maximum allowed size of " + maxBytes + " bytes")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(StandardCharsets.UTF_8)
    }
}
