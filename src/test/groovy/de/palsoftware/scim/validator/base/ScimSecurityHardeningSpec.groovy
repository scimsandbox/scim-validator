package de.palsoftware.scim.validator.base

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.palsoftware.scim.validator.specs.Rfc7644MeSpec
import groovy.json.JsonOutput
import io.restassured.response.Response
import io.restassured.response.ResponseBody
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import spock.lang.Specification
import spock.lang.Unroll

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScimSecurityHardeningSpec extends Specification {

    static final AtomicBoolean RCE_CANARY = new AtomicBoolean(false)

    def cleanup() {
        RCE_CANARY.set(false)
        ScimRunContext.endRun()
        ValidatorConfiguration.clearRunOverrides()
    }

    def "untrusted sub_id keys with Groovy expressions and dotted or special syntax are handled as literal map keys without code execution"() {
        given:
        String rceKey1 = "foo+de.palsoftware.scim.validator.base.ScimSecurityHardeningSpec.RCE_CANARY.set(true)"
        String rceKey2 = "foo+Runtime.getRuntime().availableProcessors()"
        String dottedKey = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User.manager.value"
        String specialKey = 'attr["quoted"]?.$eval()'

        Map<String, Object> subId = [
            format          : "uri",
            uri             : "https://scim.example.test/ws/1/scim/v2/Users/u-1",
            id              : "u-1",
            externalId      : "ext-1",
            "userName"      : "alice@example.test",
            (rceKey1)       : "literal-1",
            (rceKey2)       : "literal-2",
            (dottedKey)     : "dotted-literal",
            (specialKey)    : "special-literal"
        ]
        String userJson = JsonOutput.toJson([
            schemas         : ["urn:ietf:params:scim:schemas:core:2.0:User"],
            id              : "u-1",
            userName        : "alice@example.test",
            (rceKey1)       : "literal-1",
            (rceKey2)       : "literal-2",
            (dottedKey)     : "dotted-literal",
            (specialKey)    : "special-literal"
        ])

        when:
        Set<String> extraNames = ScimJsonSupport.extractExtraSubIdAttributeNames(subId)
        Map<String, Object> parsedUser = ScimJsonSupport.parseMap(userJson)
        Map<String, Object> resolvedValues = [:]
        for (String name : extraNames) {
            resolvedValues[name] = ScimJsonSupport.getLiteralAttribute(parsedUser, name)
        }

        then:
        !RCE_CANARY.get()
        extraNames == ["userName", rceKey1, rceKey2, dottedKey, specialKey] as Set
        resolvedValues["userName"] == "alice@example.test"
        resolvedValues[rceKey1] == "literal-1"
        resolvedValues[rceKey2] == "literal-2"
        resolvedValues[dottedKey] == "dotted-literal"
        resolvedValues[specialKey] == "special-literal"
        ScimJsonSupport.getLiteralAttribute(parsedUser, "nonexistent+Runtime.getRuntime()") == null
        !RCE_CANARY.get()
    }

    @Unroll
    def "same-origin URL resolver accepts valid relative and same-origin URLs (#candidate)"() {
        expect:
        ScimUrlSecurity.resolveSameOriginUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/tenant-1/scim/v2/Me",
            candidate
        ) == expected

        where:
        candidate                                                          | expected
        "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"          | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "HTTPS://SCIM.EXAMPLE.TEST:443/ws/tenant-1/scim/v2/Users/u-1"      | "HTTPS://SCIM.EXAMPLE.TEST:443/ws/tenant-1/scim/v2/Users/u-1"
        "/ws/tenant-1/scim/v2/Users/u-1"                                   | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "Users/u-1"                                                        | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
    }

    @Unroll
    def "same-origin URL resolver rejects cross-origin, scheme-downgraded, and malformed follow-up URLs (#candidate)"() {
        when:
        ScimUrlSecurity.resolveSameOriginUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/tenant-1/scim/v2/Me",
            candidate
        )

        then:
        thrown(AssertionError)

        where:
        candidate << [
            "http://scim.example.test/ws/tenant-1/scim/v2/Users/u-1",
            "https://evil.example.test/ws/tenant-1/scim/v2/Users/u-1",
            "https://scim.example.test:8443/ws/tenant-1/scim/v2/Users/u-1",
            "//evil.example.test/ws/tenant-1/scim/v2/Users/u-1",
            "https://scim.example.test@evil.example.test/Users/u-1",
            "https://scim.example.test\\@evil.example.test/Users/u-1",
            "ftp://scim.example.test/Users/u-1",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://scim.example.test/Users/u-1\r\nX-Injected: true",
            "",
            "   "
        ]
    }

    def "HTTP 302 redirects are not followed and cross-origin follow-up URLs are blocked before sending requests"() {
        given:
        AtomicInteger foreignHits = new AtomicInteger(0)
        AtomicReference<String> leakedAuthHeader = new AtomicReference<>()

        HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        foreignServer.createContext("/") { HttpExchange exchange ->
            foreignHits.incrementAndGet()
            leakedAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            byte[] resp = '{"stolen":true}'.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, resp.length)
            exchange.responseBody.withCloseable { it.write(resp) }
        }
        foreignServer.start()
        int foreignPort = foreignServer.address.port

        HttpServer originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        originServer.createContext("/ws/t1/scim/v2/RedirectProbe") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreignPort}/steal-token")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        originServer.start()
        int originPort = originServer.address.port

        ValidatorConfiguration.useRunOverrides("http://127.0.0.1:${originPort}/ws/t1/scim/v2", "super-secret-bearer-token")
        TestSecuritySpecHarness.resetRunState()
        TestSecuritySpecHarness.initConfig()
        TestSecuritySpecHarness harness = new TestSecuritySpecHarness()

        when: "target server responds with HTTP 302 pointing to a foreign origin"
        ScimRunContext.beginRun("redirect-check")
        Response redirectResponse = harness.issueRequest("/RedirectProbe")

        then: "REST Assured returns 302 directly without following the redirect or leaking Bearer credentials"
        redirectResponse.statusCode() == 302
        foreignHits.get() == 0
        leakedAuthHeader.get() == null

        when: "Rfc9967 follow-up helper is given a cross-origin URL"
        harness.fetchFollowUp("http://127.0.0.1:${foreignPort}/Events/tx-1")

        then: "request is rejected before any network call is sent"
        thrown(AssertionError)
        foreignHits.get() == 0

        when: "Rfc9967 follow-up helper is given a scheme-mismatched URL"
        harness.fetchFollowUp("https://127.0.0.1:${originPort}/ws/t1/scim/v2/Events/tx-1")

        then: "scheme switch is rejected before any network call is sent"
        thrown(AssertionError)
        foreignHits.get() == 0

        cleanup:
        TestSecuritySpecHarness.resetRunState()
        originServer?.stop(0)
        foreignServer?.stop(0)
    }

    def "RestAssuredConfig sets explicit timeouts and RequestLoggingFilter redacts Bearer tokens and cookies"() {
        given:
        HttpServer localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        localServer.createContext("/ws/t1/scim/v2/Users") { HttpExchange exchange ->
            byte[] body = '{"Resources":[],"totalResults":0}'.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.responseHeaders.add("Set-Cookie", "SID=live-cookie-secret")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        localServer.start()
        int port = localServer.address.port

        ValidatorConfiguration.useRunOverrides("http://127.0.0.1:${port}/ws/t1/scim/v2", "live-secret-bearer-xyz")
        TestSecuritySpecHarness.resetRunState()
        TestSecuritySpecHarness.initConfig()

        ByteArrayOutputStream logBuffer = new ByteArrayOutputStream()
        PrintStream logStream = new PrintStream(logBuffer, true, StandardCharsets.UTF_8)

        when:
        Response resp = TestSecuritySpecHarness.quietRequest()
            .header("Cookie", "SESSION=client-cookie-secret")
            .header("Proxy-Authorization", "Basic cHJveHktc2VjcmV0")
            .filter(TestSecuritySpecHarness.redactingLogger(logStream))
            .get("/Users")
        String logged = logBuffer.toString(StandardCharsets.UTF_8)

        then:
        resp.statusCode() == 200
        !ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.redirectConfig.followsRedirects()
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.httpClientConfig.params()["http.connection.timeout"] == 5_000
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.httpClientConfig.params()["http.socket.timeout"] == 15_000
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.httpClientConfig.params()["http.connection-manager.timeout"] == 5_000L
        logged.contains("BLACKLISTED")
        !logged.contains("live-secret-bearer-xyz")
        !logged.contains("client-cookie-secret")
        !logged.contains("cHJveHktc2VjcmV0")

        cleanup:
        TestSecuritySpecHarness.resetRunState()
        localServer?.stop(0)
    }

    def "bounded UTF-8 stream reader and ScimJsonSupport handle null, blank, malformed, and oversized inputs safely"() {
        given:
        byte[] okPayload = ("x" * 1024).getBytes(StandardCharsets.UTF_8)
        byte[] oversizedPayload = ("y" * 2049).getBytes(StandardCharsets.UTF_8)
        Response nullBodyResponse = Mock(Response) {
            getBody() >> null
        }
        ResponseBody validBody = Stub(ResponseBody) {
            asString() >> '{"id":"u-42","active":true}'
        }
        Response validResponse = Mock(Response) {
            getBody() >> validBody
        }

        expect:
        ScimJsonSupport.readBoundedUtf8(null) == ""
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(okPayload), 2048).length() == 1024
        ScimJsonSupport.parseMap((Response) null).isEmpty()
        ScimJsonSupport.parseMap(nullBodyResponse).isEmpty()
        ScimJsonSupport.parseMap(validResponse) == [id: "u-42", active: true]
        ScimJsonSupport.parseMap((String) null).isEmpty()
        ScimJsonSupport.parseMap("   ").isEmpty()
        ScimJsonSupport.parseMap("null").isEmpty()
        ScimJsonSupport.getLiteralAttribute(null, "id") == null
        ScimJsonSupport.getLiteralAttribute([id: "u-42"], null) == null
        ScimJsonSupport.extractExtraSubIdAttributeNames(null).isEmpty()
        ScimJsonSupport.extractExtraSubIdAttributeNames([:]).isEmpty()
        ScimJsonSupport.extractExtraSubIdAttributeNames([(null): "ignored", format: "uri", tenantKey: "t-1"]) == ["tenantKey"] as Set

        when: "stream exceeds maxBytes"
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(oversizedPayload), 2048)

        then:
        thrown(IllegalStateException)

        when: "maxBytes is negative"
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(okPayload), -1)

        then:
        thrown(IllegalArgumentException)

        when: "JSON payload is malformed"
        ScimJsonSupport.parseMap("{unclosed-json")

        then:
        thrown(IllegalArgumentException)
    }

    @Unroll
    def "validateOriginUri rejects invalid origin URL (#invalidUrl)"() {
        when:
        ScimUrlSecurity.validateOriginUri(invalidUrl, "TEST_URL")

        then:
        thrown(IllegalArgumentException)

        where:
        invalidUrl << [
            null,
            "",
            "   ",
            "https://scim.example.test/scim\r\n",
            "https://scim.example.test/scim\u007f",
            "https://scim.example.test\\evil",
            "https://[::1",
            "/relative/only",
            "ftp://scim.example.test/scim",
            "https://user:secret@scim.example.test/scim",
            "http:///missing-host",
            "http://scim.example.test:0/scim",
            "https://scim.example.test:70000/scim"
        ]
    }

    def "effectivePort, isSameOrigin, resolveScimReferenceUrl, and error paths enforce strict origin semantics"() {
        given:
        URI httpsDefault = ScimUrlSecurity.validateOriginUri("https://scim.example.test/ws/1/scim/v2")
        URI httpDefault = ScimUrlSecurity.validateOriginUri("http://scim.example.test/ws/1/scim/v2")
        URI httpsExplicit = ScimUrlSecurity.validateOriginUri("https://SCIM.EXAMPLE.TEST:443/Users")

        expect:
        ScimUrlSecurity.effectivePort(null) == -1
        ScimUrlSecurity.effectivePort(new URI("/relative")) == -1
        ScimUrlSecurity.effectivePort(new URI("ftp://scim.example.test")) == -1
        ScimUrlSecurity.effectivePort(httpDefault) == 80
        ScimUrlSecurity.effectivePort(httpsDefault) == 443
        ScimUrlSecurity.isSameOrigin(httpsDefault, httpsExplicit)
        !ScimUrlSecurity.isSameOrigin(null, httpsDefault)
        !ScimUrlSecurity.isSameOrigin(httpsDefault, null)
        !ScimUrlSecurity.isSameOrigin(new URI("/no-scheme"), httpsDefault)
        !ScimUrlSecurity.isSameOrigin(httpsDefault, new URI("/relative"))
        !ScimUrlSecurity.isSameOrigin(httpsDefault, new URI("https:///nohost"))
        !ScimUrlSecurity.isSameOrigin(httpsDefault, new URI("https://user@scim.example.test"))
        !ScimUrlSecurity.isSameOrigin(httpsDefault, new URI("ftp://scim.example.test"))

        and: "resolveScimReferenceUrl handles absolute, basePath-prefixed, slash-prefixed, and relative paths"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "https://scim.example.test/ws/t1/scim/v2/Users/u-1"
        ) == "https://scim.example.test/ws/t1/scim/v2/Users/u-1"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/ws/t1/scim/v2/Events/tx-1"
        ) == "https://scim.example.test/ws/t1/scim/v2/Events/tx-1"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/",
            "https://scim.example.test/ws/t1/scim/v2/",
            "/ws/t1/scim/v2",
            "/ws/t1/scim/v2/Events/tx-2"
        ) == "https://scim.example.test/ws/t1/scim/v2/Events/tx-2"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/Users/u-2"
        ) == "https://scim.example.test/ws/t1/scim/v2/Users/u-2"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/t1/scim/v2/",
            "",
            "Users/u-3"
        ) == "https://scim.example.test/ws/t1/scim/v2/Users/u-3"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            null,
            null,
            "/Users/u-4"
        ) == "https://scim.example.test/Users/u-4"
        ScimUrlSecurity.resolveSameOriginUrl("https://scim.example.test", null, "/Users/u-5") == "https://scim.example.test/Users/u-5"
        ScimUrlSecurity.resolveSameOriginUrl("https://scim.example.test", "   ", "/Users/u-5") == "https://scim.example.test/Users/u-5"

        when: "expected origin itself is invalid"
        ScimUrlSecurity.resolveSameOriginUrl("not-an-http-origin", "https://scim.example.test", "/Users/u-1")

        then:
        thrown(AssertionError)

        when: "resolution base URL is malformed"
        ScimUrlSecurity.resolveSameOriginUrl("https://scim.example.test", "not a valid url", "/Users/u-1")

        then:
        thrown(AssertionError)

        when: "candidate URL is null"
        ScimUrlSecurity.resolveSameOriginUrl("https://scim.example.test", "https://scim.example.test", null)

        then:
        thrown(AssertionError)

        when: "candidate URL has invalid URI syntax"
        ScimUrlSecurity.resolveSameOriginUrl("https://scim.example.test", "https://scim.example.test", "http://[::1")

        then:
        thrown(AssertionError)
    }

    def "ScimBaseSpec patch follow-up and Rfc7644MeSpec 308 and 200 follow-up URLs enforce same-origin validation"() {
        given:
        AtomicInteger foreignHits = new AtomicInteger(0)
        HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        foreignServer.createContext("/") { HttpExchange exchange ->
            foreignHits.incrementAndGet()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.withCloseable { it.write("{}".getBytes(StandardCharsets.UTF_8)) }
        }
        foreignServer.start()
        int foreignPort = foreignServer.address.port

        AtomicReference<String> meMode = new AtomicReference<>("308-same-origin")
        HttpServer scimServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        int scimPort = scimServer.address.port
        scimServer.createContext("/ws/t1/scim/v2/ServiceProviderConfig") { HttpExchange exchange ->
            byte[] body = '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"]}'.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        scimServer.createContext("/ws/t1/scim/v2/Users/u-me") { HttpExchange exchange ->
            byte[] body = JsonOutput.toJson([
                schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"],
                id     : "u-me",
                meta   : [location: "http://127.0.0.1:${scimPort}/ws/t1/scim/v2/Users/u-me"]
            ]).getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        scimServer.createContext("/ws/t1/scim/v2/Me") { HttpExchange exchange ->
            String mode = meMode.get()
            if (mode == "308-same-origin") {
                exchange.responseHeaders.add("Location", "/ws/t1/scim/v2/Users/u-me")
                exchange.sendResponseHeaders(308, -1)
                exchange.close()
            } else if (mode == "308-cross-origin") {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreignPort}/ws/t1/scim/v2/Users/u-me")
                exchange.sendResponseHeaders(308, -1)
                exchange.close()
            } else if (mode == "200-same-origin") {
                byte[] body = JsonOutput.toJson([
                    schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"],
                    id     : "u-me",
                    meta   : [location: "http://127.0.0.1:${scimPort}/ws/t1/scim/v2/Users/u-me"]
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            } else {
                byte[] body = JsonOutput.toJson([
                    schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"],
                    id     : "u-me",
                    meta   : [location: "http://127.0.0.1:${foreignPort}/ws/t1/scim/v2/Users/u-me"]
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            }
        }
        scimServer.start()

        ValidatorConfiguration.useRunOverrides("http://127.0.0.1:${scimPort}/ws/t1/scim/v2", "me-secret-token")
        TestSecuritySpecHarness.resetRunState()
        TestSecuritySpecHarness.initConfig()
        TestSecuritySpecHarness harness = new TestSecuritySpecHarness()

        Response patch204 = Mock(Response) {
            statusCode() >> 204
        }
        Response patch200 = Mock(Response) {
            statusCode() >> 200
        }

        when: "resolveSameOriginUrl and resolvePatchRepresentation follow a valid 200 or 204 resource path"
        String resolvedHelper = TestSecuritySpecHarness.resolveBaseRelative("Users/u-me")
        Response patchDirect = harness.followPatch(patch200, "/Users/u-me")
        Response patchFollowed = harness.followPatch(patch204, "/Users/u-me")

        then:
        resolvedHelper == "http://127.0.0.1:${scimPort}/ws/t1/scim/v2/Users/u-me"
        patchDirect.is(patch200)
        patchFollowed.statusCode() == 200
        patchFollowed.jsonPath().getString("id") == "u-me"

        when: "resolvePatchRepresentation receives a cross-origin resourcePath"
        harness.followPatch(patch204, "http://127.0.0.1:${foreignPort}/Users/u-me")

        then:
        thrown(AssertionError)
        foreignHits.get() == 0

        when: "/Me responds 308 with same-origin Location"
        meMode.set("308-same-origin")
        def summary308Ok = executeMeSpec()

        then:
        summary308Ok.testsFailedCount == 0
        foreignHits.get() == 0

        when: "/Me responds 200 with same-origin meta.location"
        meMode.set("200-same-origin")
        def summary200Ok = executeMeSpec()

        then:
        summary200Ok.testsFailedCount == 0
        foreignHits.get() == 0

        when: "/Me responds 308 with cross-origin Location"
        meMode.set("308-cross-origin")
        def summary308Evil = executeMeSpec()

        then:
        summary308Evil.testsFailedCount >= 1
        foreignHits.get() == 0

        when: "/Me responds 200 with cross-origin meta.location"
        meMode.set("200-cross-origin")
        def summary200Evil = executeMeSpec()

        then:
        summary200Evil.testsFailedCount >= 1
        foreignHits.get() == 0

        cleanup:
        TestSecuritySpecHarness.resetRunState()
        scimServer?.stop(0)
        foreignServer?.stop(0)
    }

    private static def executeMeSpec() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(Rfc7644MeSpec))
            .build()
        SummaryGeneratingListener listener = new SummaryGeneratingListener()
        LauncherFactory.create().execute(request, listener)
        return listener.summary
    }

    private static final class TestSecuritySpecHarness extends Rfc9967BaseSpec {

        static void initConfig() {
            refreshConfiguration()
            configureRestAssured()
        }

        static def quietRequest() {
            return scimRequestQuiet()
        }

        static def redactingLogger(PrintStream stream) {
            return createRedactingRequestLoggingFilter(stream)
        }

        static String resolveBaseRelative(String candidate) {
            return resolveSameOriginUrl(candidate)
        }

        Response issueRequest(String path) {
            return scimRequest().get(path)
        }

        Response fetchFollowUp(String candidate) {
            return getUrl(resolveUrl(candidate))
        }

        Response followPatch(Response patchResponse, String resourcePath) {
            return resolvePatchRepresentation(patchResponse, resourcePath)
        }
    }
}
