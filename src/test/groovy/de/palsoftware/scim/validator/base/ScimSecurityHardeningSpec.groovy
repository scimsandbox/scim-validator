package de.palsoftware.scim.validator.base

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import io.restassured.response.Response
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

    def "bounded UTF-8 stream reader enforces size cap"() {
        given:
        byte[] okPayload = ("x" * 1024).getBytes(StandardCharsets.UTF_8)
        byte[] oversizedPayload = ("y" * 2049).getBytes(StandardCharsets.UTF_8)

        expect:
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(okPayload), 2048).length() == 1024

        when:
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(oversizedPayload), 2048)

        then:
        thrown(IllegalStateException)
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

        Response issueRequest(String path) {
            return scimRequest().get(path)
        }

        Response fetchFollowUp(String candidate) {
            return getUrl(resolveUrl(candidate))
        }
    }
}
