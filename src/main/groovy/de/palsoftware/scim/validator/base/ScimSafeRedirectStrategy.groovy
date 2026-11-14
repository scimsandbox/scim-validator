package de.palsoftware.scim.validator.base

import org.apache.http.Header
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.ProtocolException
import org.apache.http.client.utils.URIUtils
import org.apache.http.impl.client.DefaultRedirectStrategy
import org.apache.http.protocol.HttpContext
import org.apache.http.protocol.HttpCoreContext

import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse
import java.time.Duration

/**
 * Redirect strategy and JDK {@link HttpClient} helper that permit automatic redirects only when
 * the target stays on the exact same origin or upgrades {@code http -> https} on port 443 of the
 * same host, preventing cross-origin or downgrade exfiltration of {@code Authorization} headers.
 */
class ScimSafeRedirectStrategy extends DefaultRedirectStrategy {

    static final int DEFAULT_MAX_REDIRECTS = 5
    private static final int HTTP_TEMPORARY_REDIRECT = 307
    private static final int HTTP_PERMANENT_REDIRECT = 308
    private static final String LOCATION_HEADER = "location"
    private static final Set<String> PRESERVED_BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE")

    @Override
    boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
        if (request?.requestLine == null || response?.statusLine == null) {
            return false
        }
        Header locationHeader = response.getFirstHeader(LOCATION_HEADER)
        String rawLocation = locationHeader?.value
        if (isUnsafeOrBlankLocation(rawLocation)) {
            return false
        }

        int statusCode = response.statusLine.statusCode
        String method = request.requestLine.method?.toUpperCase(Locale.ROOT) ?: ""
        boolean methodPreservingRedirect = (statusCode == HTTP_TEMPORARY_REDIRECT || statusCode == HTTP_PERMANENT_REDIRECT)
            && PRESERVED_BODY_METHODS.contains(method)
        if (!super.isRedirected(request, response, context) && !methodPreservingRedirect) {
            return false
        }

        HttpHost targetHost = (HttpHost) context?.getAttribute(HttpCoreContext.HTTP_TARGET_HOST)
        if (targetHost == null || targetHost.schemeName == null || targetHost.hostName == null) {
            return false
        }

        URI currentOrigin
        URI targetUri
        try {
            currentOrigin = new URI(targetHost.schemeName, null, targetHost.hostName, targetHost.port, "/", null, null)
            targetUri = resolveTargetUriWithoutMutatingContext(request, targetHost, rawLocation.trim())
        } catch (ProtocolException | URISyntaxException ignored) {
            return false
        }
        return ScimUrlSecurity.isSafeRedirectTarget(currentOrigin, targetUri)
    }

    private static URI resolveTargetUriWithoutMutatingContext(HttpRequest request,
                                                              HttpHost targetHost,
                                                              String trimmedLocation) throws URISyntaxException {
        URI candidate = new URI(trimmedLocation)
        if (candidate.isAbsolute()) {
            return candidate.normalize()
        }
        URI requestUri = new URI(request.requestLine.uri)
        URI absoluteRequestUri = requestUri.isAbsolute()
            ? requestUri
            : URIUtils.rewriteURI(requestUri, targetHost, URIUtils.DROP_FRAGMENT)
        URI baseForResolve = ScimUrlSecurity.ensureNonEmptyPath(absoluteRequestUri)
        return baseForResolve.resolve(candidate).normalize()
    }

    /**
     * Executes a {@code POST} request with {@link HttpClient.Redirect#NEVER} and manually follows
     * up to {@code maxRedirects} method-preserving ({@code 307}/{@code 308}) redirects only when
     * {@link ScimUrlSecurity#isSafeRedirectTarget(URI, URI)} approves the target URI.
     */
    static JdkHttpResponse<InputStream> sendPostWithSafeRedirects(HttpClient client,
                                                                  URI initialUri,
                                                                  Duration requestTimeout,
                                                                  Map<String, String> headers,
                                                                  String body,
                                                                  int maxRedirects = DEFAULT_MAX_REDIRECTS) {
        URI currentUri = initialUri
        for (int hop = 0; ; hop++) {
            JdkHttpRequest.Builder builder = JdkHttpRequest.newBuilder(currentUri)
                .timeout(requestTimeout)
                .POST(JdkHttpRequest.BodyPublishers.ofString(body))
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.key, entry.value)
                }
            }
            JdkHttpResponse<InputStream> response = client.send(builder.build(), JdkHttpResponse.BodyHandlers.ofInputStream())
            if (hop >= maxRedirects) {
                return response
            }
            URI nextUri = resolveSafePostRedirectUri(currentUri, response)
            if (nextUri == null) {
                return response
            }
            InputStream intermediateBody = response.body()
            if (intermediateBody != null) {
                intermediateBody.close()
            }
            currentUri = nextUri
        }
    }

    private static URI resolveSafePostRedirectUri(URI currentUri, JdkHttpResponse<?> response) {
        int status = response.statusCode()
        if (status != HTTP_TEMPORARY_REDIRECT && status != HTTP_PERMANENT_REDIRECT) {
            return null
        }
        String rawLocation = response.headers().firstValue(LOCATION_HEADER).orElse(null)
        if (isUnsafeOrBlankLocation(rawLocation)) {
            return null
        }
        try {
            URI candidate = new URI(rawLocation.trim())
            URI baseForResolve = ScimUrlSecurity.ensureNonEmptyPath(currentUri)
            URI resolved = candidate.isAbsolute() ? candidate.normalize() : baseForResolve.resolve(candidate).normalize()
            return ScimUrlSecurity.isSafeRedirectTarget(currentUri, resolved) ? resolved : null
        } catch (URISyntaxException ignored) {
            return null
        }
    }

    private static boolean isUnsafeOrBlankLocation(String rawLocation) {
        return rawLocation == null
            || rawLocation.isBlank()
            || ScimUrlSecurity.containsUnsafeCharacters(rawLocation)
            || rawLocation.trim().startsWith("//")
    }
}
