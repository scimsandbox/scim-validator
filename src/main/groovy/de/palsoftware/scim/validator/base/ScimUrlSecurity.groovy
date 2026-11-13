package de.palsoftware.scim.validator.base

/**
 * Enforces strict same-origin validation on target SCIM URLs and server-supplied follow-up URLs
 * (such as HTTP Location headers, meta.location, $ref, and RFC 9967 sub_id.uri) so authenticated
 * requests never leak Bearer credentials cross-origin or across scheme/port downgrades.
 */
final class ScimUrlSecurity {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https")

    private ScimUrlSecurity() {
    }

    static URI validateOriginUri(String rawUrl, String label = "SCIM URL") {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("${label} must not be blank")
        }
        String trimmed = rawUrl.trim()
        if (containsUnsafeCharacters(trimmed)) {
            throw new IllegalArgumentException("${label} contains illegal control or backslash characters: '${trimmed}'")
        }
        URI uri
        try {
            uri = new URI(trimmed)
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid ${label} '${trimmed}': ${e.message}", e)
        }
        String scheme = uri.scheme?.toLowerCase(Locale.ROOT)
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("${label} must use http or https scheme, but was '${trimmed}'")
        }
        if (uri.rawUserInfo != null) {
            throw new IllegalArgumentException("${label} must not contain userinfo credentials: '${trimmed}'")
        }
        if (uri.host == null || uri.host.isBlank()) {
            throw new IllegalArgumentException("${label} must specify a valid host: '${trimmed}'")
        }
        int port = effectivePort(uri)
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("${label} specifies an invalid port: '${trimmed}'")
        }
        return uri
    }

    static int effectivePort(URI uri) {
        if (uri == null || uri.scheme == null) {
            return -1
        }
        if (uri.port != -1) {
            return uri.port
        }
        String scheme = uri.scheme.toLowerCase(Locale.ROOT)
        if ("http" == scheme) {
            return 80
        }
        if ("https" == scheme) {
            return 443
        }
        return -1
    }

    static boolean isSameOrigin(URI expectedOrigin, URI candidate) {
        if (expectedOrigin == null || candidate == null) {
            return false
        }
        if (candidate.scheme == null || candidate.host == null || candidate.rawUserInfo != null) {
            return false
        }
        String expectedScheme = expectedOrigin.scheme?.toLowerCase(Locale.ROOT)
        String candidateScheme = candidate.scheme.toLowerCase(Locale.ROOT)
        if (expectedScheme == null || !ALLOWED_SCHEMES.contains(candidateScheme) || expectedScheme != candidateScheme) {
            return false
        }
        if (!expectedOrigin.host.equalsIgnoreCase(candidate.host)) {
            return false
        }
        return effectivePort(expectedOrigin) == effectivePort(candidate)
    }

    static String validateSameOriginUrl(String scimApiUrl, String candidateUrl) {
        return resolveSameOriginUrl(scimApiUrl, scimApiUrl, candidateUrl)
    }

    /**
     * Resolves {@code candidateUrl} (relative or absolute) against {@code resolutionBaseUrl} while
     * enforcing that the resulting URL preserves the exact {@code (scheme, host, effectivePort)}
     * of {@code scimApiUrl}. Fails with {@link AssertionError} if rejected.
     */
    static String resolveSameOriginUrl(String scimApiUrl, String resolutionBaseUrl, String candidateUrl) {
        URI expectedOrigin = parseExpectedOrigin(scimApiUrl)
        URI baseUri = parseResolutionBase(expectedOrigin, resolutionBaseUrl)
        URI candidateUri = parseCandidateReference(candidateUrl)

        if (candidateUri.isAbsolute()) {
            assertSameOrigin(expectedOrigin, candidateUri, candidateUrl)
            URI normalized = candidateUri.normalize()
            assertSameOrigin(expectedOrigin, normalized, candidateUrl)
            return normalized.toString()
        }

        URI resolved = baseUri.resolve(candidateUri).normalize()
        assertSameOrigin(expectedOrigin, resolved, candidateUrl)
        return resolved.toString()
    }

    /**
     * Resolves a SCIM reference or Location header where relative paths may either start with
     * {@code basePath} (e.g. {@code /ws/123/scim/v2/Events/abc}) or be relative to {@code baseUrl}
     * (e.g. {@code /Users/123} or {@code Users/123}), enforcing exact same-origin match.
     */
    static String resolveScimReferenceUrl(String scimApiUrl, String baseUrl, String basePath, String candidateUrl) {
        URI expectedOrigin = parseExpectedOrigin(scimApiUrl)
        URI candidateUri = parseCandidateReference(candidateUrl)

        if (candidateUri.isAbsolute()) {
            assertSameOrigin(expectedOrigin, candidateUri, candidateUrl)
            URI normalized = candidateUri.normalize()
            assertSameOrigin(expectedOrigin, normalized, candidateUrl)
            return normalized.toString()
        }

        String trimmed = candidateUrl.trim()
        if (basePath != null && !basePath.isBlank() && trimmed.startsWith(basePath)) {
            String originBase = scimApiUrl.trim().endsWith("/") ? scimApiUrl.trim() : scimApiUrl.trim() + "/"
            return resolveSameOriginUrl(scimApiUrl, originBase, trimmed)
        }

        String effectiveBase = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : scimApiUrl.trim()
        if (!effectiveBase.endsWith("/")) {
            effectiveBase = effectiveBase + "/"
        }
        String relativeRef = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed
        return resolveSameOriginUrl(scimApiUrl, effectiveBase, relativeRef)
    }

    private static URI parseExpectedOrigin(String scimApiUrl) {
        try {
            return validateOriginUri(scimApiUrl, "scimApiUrl")
        } catch (IllegalArgumentException e) {
            throw new AssertionError(e.message, e)
        }
    }

    private static URI parseResolutionBase(URI expectedOrigin, String resolutionBaseUrl) {
        String effective = (resolutionBaseUrl != null && !resolutionBaseUrl.isBlank()) ? resolutionBaseUrl.trim() : expectedOrigin.toString()
        URI baseUri
        try {
            baseUri = validateOriginUri(effective, "resolutionBaseUrl")
        } catch (IllegalArgumentException e) {
            throw new AssertionError(e.message, e)
        }
        assertSameOrigin(expectedOrigin, baseUri, effective)
        return baseUri
    }

    private static URI parseCandidateReference(String candidateUrl) {
        if (candidateUrl == null || candidateUrl.isBlank()) {
            throw new AssertionError("Rejected empty or blank follow-up URL")
        }
        String trimmed = candidateUrl.trim()
        if (containsUnsafeCharacters(trimmed)) {
            throw new AssertionError("Rejected follow-up URL containing control or backslash characters: '${trimmed}'")
        }
        if (trimmed.startsWith("//")) {
            throw new AssertionError("Rejected protocol-relative follow-up URL '${trimmed}'")
        }
        URI candidateUri
        try {
            candidateUri = new URI(trimmed)
        } catch (URISyntaxException e) {
            throw new AssertionError("Rejected malformed follow-up URL '${trimmed}': ${e.message}", e)
        }
        if (!candidateUri.isAbsolute() && (candidateUri.rawAuthority != null || candidateUri.host != null || candidateUri.rawUserInfo != null)) {
            throw new AssertionError("Rejected network-path or authority-bearing relative URL '${trimmed}'")
        }
        return candidateUri
    }

    private static void assertSameOrigin(URI expectedOrigin, URI actual, String rawCandidate) {
        if (!isSameOrigin(expectedOrigin, actual)) {
            String expectedDesc = "${expectedOrigin.scheme.toLowerCase(Locale.ROOT)}://${expectedOrigin.host.toLowerCase(Locale.ROOT)}:${effectivePort(expectedOrigin)}"
            throw new AssertionError(
                "Rejected cross-origin or scheme/port-mismatched follow-up URL '${rawCandidate}' " +
                    "(expected origin ${expectedDesc})"
            )
        }
    }

    private static boolean containsUnsafeCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i)
            if (ch <= 0x1F || ch == (char) 0x7F || ch == ('\\' as char)) {
                return true
            }
        }
        return false
    }
}
