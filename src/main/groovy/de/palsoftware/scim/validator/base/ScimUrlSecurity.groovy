package de.palsoftware.scim.validator.base

import groovy.transform.Generated
import org.opentest4j.TestAbortedException

/**
 * Enforces strict origin validation on target SCIM URLs and server-supplied follow-up URLs
 * (such as HTTP {@code Location} headers, {@code meta.location}, {@code $ref}, and RFC 9967
 * {@code sub_id.uri}) so authenticated requests never leak Bearer credentials cross-origin or
 * across scheme/port downgrades.
 *
 * <p>Malformed, non-HTTP(S), protocol-relative, credential-bearing, path-traversing, or control-character
 * follow-up URLs fail compliance checks with {@link AssertionError}. Syntactically valid {@code http} or
 * {@code https} follow-up URLs that point to an untrusted origin (neither exact same-origin nor a
 * same-host {@code http -> https} upgrade on port 443) abort the test with {@link TestAbortedException}
 * before any network request is sent.</p>
 */
final class ScimUrlSecurity {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https")

    @Generated
    private ScimUrlSecurity() {
    }

    static URI validateOriginUri(String rawUrl, String label = "SCIM URL") {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("${label} must not be blank")
        }
        if (containsUnsafeCharacters(rawUrl)) {
            throw new IllegalArgumentException("${label} contains illegal control or backslash characters: '${rawUrl}'")
        }
        String trimmed = rawUrl.trim()
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
        if (uri.rawQuery != null || uri.rawFragment != null) {
            throw new IllegalArgumentException("${label} must not contain query or fragment components: '${trimmed}'")
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
        if (!isValidHttpEndpointUri(expectedOrigin) || !isValidHttpEndpointUri(candidate)) {
            return false
        }
        if (!expectedOrigin.scheme.equalsIgnoreCase(candidate.scheme)) {
            return false
        }
        if (!expectedOrigin.host.equalsIgnoreCase(candidate.host)) {
            return false
        }
        return effectivePort(expectedOrigin) == effectivePort(candidate)
    }

    /**
     * Returns {@code true} only when {@code targetUri} is a valid {@code http}/{@code https} URI
     * without userinfo or unsafe characters, targets the exact same host as {@code currentOrigin},
     * and either stays on the exact same origin or upgrades {@code http -> https} on default port 443.
     */
    static boolean isSafeRedirectTarget(URI currentOrigin, URI targetUri) {
        if (!isValidHttpEndpointUri(currentOrigin) || !isValidHttpEndpointUri(targetUri)) {
            return false
        }
        if (!currentOrigin.host.equalsIgnoreCase(targetUri.host)) {
            return false
        }
        if (isSameOrigin(currentOrigin, targetUri)) {
            return true
        }
        return "http".equalsIgnoreCase(currentOrigin.scheme)
            && "https".equalsIgnoreCase(targetUri.scheme)
            && effectivePort(targetUri) == 443
    }

    static String validateSameOriginUrl(String scimApiUrl, String candidateUrl) {
        return resolveSameOriginUrl(scimApiUrl, scimApiUrl, candidateUrl)
    }

    /**
     * Resolves {@code candidateUrl} (relative or absolute) against {@code resolutionBaseUrl} while
     * enforcing that the resulting URL is a valid HTTP(S) URL on the exact origin of {@code scimApiUrl}
     * (or a same-host {@code http -> https} upgrade on port 443). Fails with {@link AssertionError} on
     * malformed or unsafe URLs, and aborts with {@link TestAbortedException} when a valid HTTP(S) URL
     * targets an untrusted origin.
     */
    static String resolveSameOriginUrl(String scimApiUrl, String resolutionBaseUrl, String candidateUrl) {
        return resolveSameOriginUrlInternal(scimApiUrl, resolutionBaseUrl, candidateUrl, candidateUrl)
    }

    /**
     * Resolves a SCIM reference or {@code Location} header where relative paths may match the full
     * server path of {@code baseUrl} (e.g. {@code /api/ws/123/scim/v2/Events/abc}), start with the
     * workspace {@code basePath} (e.g. {@code /ws/123/scim/v2/Events/abc}), or be relative to
     * {@code baseUrl} (e.g. {@code /Users/123}, {@code Users/123}, or {@code /txn:0}), preserving
     * any path prefix on {@code scimApiUrl} and enforcing origin rules.
     */
    static String resolveScimReferenceUrl(String scimApiUrl, String baseUrl, String basePath, String candidateUrl) {
        URI expectedOrigin = parseExpectedOrigin(scimApiUrl)
        URI candidateUri = parseCandidateReference(candidateUrl, candidateUrl)

        if (candidateUri.isAbsolute()) {
            return resolveSameOriginUrlInternal(scimApiUrl, scimApiUrl, candidateUrl, candidateUrl)
        }

        String trimmed = candidateUrl.trim()
        String normalizedApiUrl = stripTrailingSlashes(scimApiUrl.trim())
        String originBase = normalizedApiUrl + "/"
        String effectiveBaseRaw = (baseUrl != null && !baseUrl.isBlank())
            ? stripTrailingSlashes(baseUrl.trim())
            : normalizedApiUrl
        String effectiveBase = effectiveBaseRaw + "/"
        URI effectiveBaseUri = parseResolutionBase(expectedOrigin, effectiveBase)
        String fullBasePath = stripTrailingSlashes(effectiveBaseUri.rawPath)
        String normalizedBasePath = normalizePrefixPath(basePath)
        String relativeSubpath = toRelativeSubpath(trimmed)

        if (matchesPathPrefix(trimmed, fullBasePath)) {
            String bareOrigin = expectedOrigin.scheme + "://" + expectedOrigin.rawAuthority + "/"
            return resolveSameOriginUrlInternal(scimApiUrl, bareOrigin, relativeSubpath, candidateUrl)
        }
        if (matchesPathPrefix(trimmed, normalizedBasePath)) {
            return resolveSameOriginUrlInternal(scimApiUrl, originBase, relativeSubpath, candidateUrl)
        }
        return resolveSameOriginUrlInternal(scimApiUrl, effectiveBase, relativeSubpath, candidateUrl)
    }

    /**
     * If {@code scimApiUrl} uses {@code http} and {@code rawLocation} redirects to the exact same
     * endpoint path and query over {@code https} on default port 443 of the same host, returns the
     * normalized {@code https} URL; otherwise returns {@code null}.
     */
    static String resolveSameEndpointHttpsUpgrade(String scimApiUrl, String endpointUrl, String rawLocation) {
        if (endpointUrl == null || endpointUrl.isBlank() || containsUnsafeCharacters(endpointUrl)
            || rawLocation == null || rawLocation.isBlank() || containsUnsafeCharacters(rawLocation)) {
            return null
        }
        String trimmedLocation = rawLocation.trim()
        if (trimmedLocation.startsWith("//")) {
            return null
        }
        try {
            URI expectedOrigin = validateOriginUri(scimApiUrl, "scimApiUrl")
            if (!"http".equalsIgnoreCase(expectedOrigin.scheme)) {
                return null
            }
            URI rawEndpointUri = new URI(endpointUrl.trim())
            if (!isSafeRedirectTarget(expectedOrigin, rawEndpointUri)) {
                return null
            }
            URI endpointUri = ensureNonEmptyPath(rawEndpointUri)
            URI candidate = new URI(trimmedLocation)
            URI resolved = candidate.isAbsolute() ? candidate.normalize() : resolveAgainstBase(endpointUri, candidate)
            if (!"https".equalsIgnoreCase(resolved.scheme) || !isSafeRedirectTarget(expectedOrigin, resolved)) {
                return null
            }
            if (normalizePrefixPath(resolved.rawPath) != normalizePrefixPath(endpointUri.rawPath)) {
                return null
            }
            if (!Objects.equals(resolved.rawQuery, endpointUri.rawQuery)) {
                return null
            }
            return resolved.toString()
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            return null
        }
    }

    static boolean matchesPathPrefix(String candidate, String prefix) {
        if (candidate == null || prefix == null || prefix.isEmpty() || prefix == "/") {
            return false
        }
        if (candidate == prefix) {
            return true
        }
        if (prefix.endsWith("/")) {
            return candidate.startsWith(prefix)
        }
        return candidate.startsWith(prefix + "/")
            || candidate.startsWith(prefix + "?")
            || candidate.startsWith(prefix + "#")
    }

    static String stripTrailingSlashes(String value) {
        if (value == null) {
            return null
        }
        int end = value.length()
        while (end > 0 && value.charAt(end - 1) == ('/' as char)) {
            end--
        }
        return value.substring(0, end)
    }

    static URI ensureNonEmptyPath(URI uri) {
        if (uri == null || (uri.rawPath != null && !uri.rawPath.isEmpty())) {
            return uri
        }
        StringBuilder normalizedBase = new StringBuilder()
            .append(uri.scheme)
            .append("://")
            .append(uri.rawAuthority)
            .append('/')
        if (uri.rawQuery != null) {
            normalizedBase.append('?').append(uri.rawQuery)
        }
        if (uri.rawFragment != null) {
            normalizedBase.append('#').append(uri.rawFragment)
        }
        return URI.create(normalizedBase.toString())
    }

    static boolean containsUnsafeCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i)
            if (ch <= 0x1F || ch == (char) 0x7F || ch == ('\\' as char)) {
                return true
            }
        }
        return false
    }

    private static String resolveSameOriginUrlInternal(String scimApiUrl,
                                                       String resolutionBaseUrl,
                                                       String candidateUrl,
                                                       String displayCandidate) {
        URI expectedOrigin = parseExpectedOrigin(scimApiUrl)
        URI baseUri = parseResolutionBase(expectedOrigin, resolutionBaseUrl)
        URI candidateUri = parseCandidateReference(candidateUrl, displayCandidate)

        if (candidateUri.isAbsolute()) {
            assertValidTargetHttpUri(candidateUri, displayCandidate)
            URI normalized = candidateUri.normalize()
            assertValidTargetHttpUri(normalized, displayCandidate)
            assertSameOriginOrAbort(expectedOrigin, normalized, displayCandidate)
            return normalized.toString()
        }

        URI resolved = resolveAgainstBase(baseUri, candidateUri)
        assertValidTargetHttpUri(resolved, displayCandidate)
        assertSameOriginOrAbort(expectedOrigin, resolved, displayCandidate)
        return resolved.toString()
    }

    private static URI resolveAgainstBase(URI baseUri, URI candidateUri) {
        if ((candidateUri.rawPath == null || candidateUri.rawPath.isEmpty()) && candidateUri.rawQuery != null) {
            StringBuilder queryTarget = new StringBuilder()
                .append(baseUri.scheme)
                .append("://")
                .append(baseUri.rawAuthority)
                .append(baseUri.rawPath)
                .append('?')
                .append(candidateUri.rawQuery)
            if (candidateUri.rawFragment != null) {
                queryTarget.append('#').append(candidateUri.rawFragment)
            }
            return URI.create(queryTarget.toString()).normalize()
        }
        return baseUri.resolve(candidateUri).normalize()
    }

    private static String normalizePrefixPath(String path) {
        if (path == null || path.isBlank()) {
            return ""
        }
        String stripped = stripTrailingSlashes(path.trim())
        if (stripped.isEmpty()) {
            return ""
        }
        return stripped.startsWith("/") ? stripped : "/" + stripped
    }

    private static String toRelativeSubpath(String value) {
        return value.startsWith("/") ? "." + value : value
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
        if (!isSafeRedirectTarget(expectedOrigin, baseUri)) {
            throw new AssertionError("resolutionBaseUrl '${effective}' does not match configured scimApiUrl origin")
        }
        return ensureNonEmptyPath(baseUri)
    }

    private static URI parseCandidateReference(String candidateUrl, String displayCandidate) {
        if (candidateUrl == null || candidateUrl.isBlank()) {
            throw new AssertionError("Rejected empty or blank follow-up URL")
        }
        if (containsUnsafeCharacters(candidateUrl)) {
            throw new AssertionError("Rejected follow-up URL containing control or backslash characters: '${displayCandidate}'")
        }
        String trimmed = candidateUrl.trim()
        if (trimmed.startsWith("//")) {
            throw new AssertionError("Rejected protocol-relative follow-up URL '${displayCandidate}'")
        }
        URI candidateUri
        try {
            candidateUri = new URI(trimmed)
        } catch (URISyntaxException e) {
            throw new AssertionError("Rejected malformed follow-up URL '${displayCandidate}': ${e.message}", e)
        }
        return candidateUri
    }

    private static void assertValidTargetHttpUri(URI uri, String rawCandidate) {
        String scheme = uri.scheme?.toLowerCase(Locale.ROOT)
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new AssertionError("Rejected non-HTTP(S) follow-up URL '${rawCandidate}'")
        }
        if (uri.rawUserInfo != null) {
            throw new AssertionError("Rejected follow-up URL containing userinfo credentials: '${rawCandidate}'")
        }
        if (uri.host == null || uri.host.isBlank()) {
            throw new AssertionError("Rejected follow-up URL with missing or invalid host: '${rawCandidate}'")
        }
        int port = effectivePort(uri)
        if (port < 1 || port > 65535) {
            throw new AssertionError("Rejected follow-up URL with invalid port: '${rawCandidate}'")
        }
        if (hasLeadingParentTraversal(uri.normalize().rawPath)) {
            throw new AssertionError("Rejected follow-up URL escaping root path via dot-segments: '${rawCandidate}'")
        }
    }

    private static void assertSameOriginOrAbort(URI expectedOrigin, URI actual, String rawCandidate) {
        if (!isSafeRedirectTarget(expectedOrigin, actual)) {
            String expectedDesc = "${expectedOrigin.scheme.toLowerCase(Locale.ROOT)}://${expectedOrigin.host.toLowerCase(Locale.ROOT)}:${effectivePort(expectedOrigin)}"
            throw new TestAbortedException(
                "Skipped follow-up request to '${rawCandidate}': target origin does not match configured SCIM origin ${expectedDesc} " +
                    "(refusing to forward credentials cross-origin)"
            )
        }
    }

    private static boolean isValidHttpEndpointUri(URI uri) {
        if (uri == null || uri.scheme == null || uri.host == null || uri.host.isBlank() || uri.rawUserInfo != null) {
            return false
        }
        String scheme = uri.scheme.toLowerCase(Locale.ROOT)
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return false
        }
        int port = effectivePort(uri)
        if (port < 1 || port > 65535) {
            return false
        }
        return !hasLeadingParentTraversal(uri.normalize().rawPath)
    }

    private static boolean hasLeadingParentTraversal(String rawPath) {
        return rawPath == "/.." || (rawPath != null && rawPath.startsWith("/../"))
    }
}
