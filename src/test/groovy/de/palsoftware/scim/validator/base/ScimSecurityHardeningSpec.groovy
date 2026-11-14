package de.palsoftware.scim.validator.base

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.palsoftware.scim.validator.specs.Rfc7643AttributeModelSpec
import de.palsoftware.scim.validator.specs.Rfc7644MeSpec
import de.palsoftware.scim.validator.specs.Rfc9967AsyncSpec
import groovy.json.JsonOutput
import io.restassured.filter.FilterContext
import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.Response
import io.restassured.response.ResponseBody
import io.restassured.specification.FilterableRequestSpecification
import io.restassured.specification.FilterableResponseSpecification
import org.apache.http.HttpHost
import org.apache.http.HttpVersion
import org.apache.http.message.BasicHttpRequest
import org.apache.http.message.BasicHttpResponse
import org.apache.http.protocol.BasicHttpContext
import org.apache.http.protocol.HttpCoreContext
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.opentest4j.TestAbortedException
import spock.lang.Specification
import spock.lang.Unroll

import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
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
            format     : "uri",
            uri        : "https://scim.example.test/ws/1/scim/v2/Users/u-1",
            id         : "u-1",
            externalId : "ext-1",
            "userName" : "alice@example.test",
            (rceKey1)  : "literal-1",
            (rceKey2)  : "literal-2",
            (dottedKey): "dotted-literal",
            (specialKey): "special-literal"
        ]
        String userJson = JsonOutput.toJson([
            schemas     : ["urn:ietf:params:scim:schemas:core:2.0:User"],
            id          : "u-1",
            userName    : "alice@example.test",
            (rceKey1)   : "literal-1",
            (rceKey2)   : "literal-2",
            (dottedKey) : "dotted-literal",
            (specialKey): "special-literal"
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
    def "same-origin URL resolver accepts valid relative, query-only, http->https:443 upgrade, and empty-base-path URLs including JDK-8272702 (#base -> #candidate)"() {
        expect:
        ScimUrlSecurity.resolveSameOriginUrl(origin, base, candidate) == expected

        where:
        origin                           | base                                                | candidate                                                     | expected
        "https://scim.example.test"      | "https://scim.example.test/ws/tenant-1/scim/v2/Me"  | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"     | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "https://scim.example.test"      | "https://scim.example.test/ws/tenant-1/scim/v2/Me"  | "HTTPS://SCIM.EXAMPLE.TEST:443/ws/tenant-1/scim/v2/Users/u-1" | "HTTPS://SCIM.EXAMPLE.TEST:443/ws/tenant-1/scim/v2/Users/u-1"
        "http://scim.example.test"       | "http://scim.example.test/ws/tenant-1/scim/v2/Me"   | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"     | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "http://scim.example.test:8080"  | "https://scim.example.test/ws/tenant-1/scim/v2/Me"  | "Users/u-1"                                                   | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "https://scim.example.test"      | "https://scim.example.test/ws/tenant-1/scim/v2/Me"  | "/ws/tenant-1/scim/v2/Users/u-1"                              | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "https://scim.example.test"      | "https://scim.example.test/ws/tenant-1/scim/v2/Me"  | "Users/u-1"                                                   | "https://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "https://scim.example.test"      | "https://scim.example.test"                         | "Users/u-1"                                                   | "https://scim.example.test/Users/u-1"
        "http://[::1]:8080"              | "http://[::1]:8080"                                 | "Users/u-1"                                                   | "http://[::1]:8080/Users/u-1"
        "https://scim.example.test"      | "https://scim.example.test/ws/tenant-1/scim/v2/Me"  | "?attributes=userName#section"                                | "https://scim.example.test/ws/tenant-1/scim/v2/Me?attributes=userName#section"
        "https://scim.example.test"      | "https://scim.example.test"                         | "?count=5"                                                    | "https://scim.example.test/?count=5"
    }

    def "validateSameOriginUrl resolves relative paths safely when origin has an empty path (JDK-8272702)"() {
        expect:
        ScimUrlSecurity.validateSameOriginUrl("https://scim.example.test", "Users/u-1") == "https://scim.example.test/Users/u-1"
        ScimUrlSecurity.ensureNonEmptyPath(null) == null
        ScimUrlSecurity.ensureNonEmptyPath(URI.create("https://scim.example.test?a=1#frag")).toString() == "https://scim.example.test/?a=1#frag"
        ScimUrlSecurity.stripTrailingSlashes(null) == null
        ScimUrlSecurity.stripTrailingSlashes("///") == ""
    }

    def "resolveSameEndpointHttpsUpgrade accepts only same-endpoint http->https:443 upgrades and rejects alias redirects or downgrades"() {
        expect: "same-host http -> https:443 upgrades to the exact same endpoint path/query succeed"
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "https://scim.example.test/ws/t1/scim/v2/Me"
        ) == "https://scim.example.test/ws/t1/scim/v2/Me"
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test:8080/api",
            "http://scim.example.test:8080/api/ws/t1/scim/v2/Me",
            "https://scim.example.test:443/api/ws/t1/scim/v2/Me/"
        ) == "https://scim.example.test:443/api/ws/t1/scim/v2/Me/"
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/api",
            "http://scim.example.test/api/ws/t1/scim/v2/Me?attributes=userName",
            "https://scim.example.test/api/ws/t1/scim/v2/Me?attributes=userName"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/Me?attributes=userName"

        and: "alias redirects (/Me -> /Users/u-me), downgrades, cross-host, port-mismatches, query mismatches, and invalid inputs return null"
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "https://scim.example.test/ws/t1/scim/v2/Users/u-me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "/ws/t1/scim/v2/Users/u-me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "https://scim.example.test/ws/t1/scim/v2/Me?unexpected=1"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "https://scim.example.test/ws/t1/scim/v2",
            "https://scim.example.test/ws/t1/scim/v2/Me",
            "https://scim.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "https://scim.example.test/ws/t1/scim/v2",
            "https://scim.example.test/ws/t1/scim/v2/Me",
            "http://scim.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "https://evil.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test:8080/ws/t1/scim/v2",
            "http://scim.example.test:8080/ws/t1/scim/v2/Me",
            "https://scim.example.test:8443/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://other.example.test/ws/t1/scim/v2/Me",
            "https://scim.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            null,
            "https://scim.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "   ",
            "https://scim.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me\\bad",
            "https://scim.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            null
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "   "
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "//scim.example.test/ws/t1/scim/v2/Me"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "https://scim.example.test/ws/t1/scim/v2/Me\r\n"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "http://scim.example.test/ws/t1/scim/v2",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "https://[::1"
        ) == null
        ScimUrlSecurity.resolveSameEndpointHttpsUpgrade(
            "not-a-valid-origin",
            "http://scim.example.test/ws/t1/scim/v2/Me",
            "https://scim.example.test/ws/t1/scim/v2/Me"
        ) == null
    }

    @Unroll
    def "same-origin URL resolver aborts with TestAbortedException on valid HTTP(S) cross-origin, downgrade, or non-443 port follow-up URLs (#origin -> #candidate)"() {
        when:
        ScimUrlSecurity.resolveSameOriginUrl(origin, base, candidate)

        then:
        TestAbortedException ex = thrown(TestAbortedException)
        ex.message.contains("does not match configured SCIM origin")

        where:
        origin                          | base                                                    | candidate
        "https://scim.example.test"     | "https://scim.example.test/ws/tenant-1/scim/v2/Me"      | "https://evil.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "https://scim.example.test"     | "https://scim.example.test/ws/tenant-1/scim/v2/Me"      | "https://scim.example.test:8443/ws/tenant-1/scim/v2/Users/u-1"
        "https://scim.example.test"     | "https://scim.example.test/ws/tenant-1/scim/v2/Me"      | "http://scim.example.test/ws/tenant-1/scim/v2/Users/u-1"
        "http://scim.example.test:8080" | "http://scim.example.test:8080/ws/tenant-1/scim/v2/Me"  | "https://scim.example.test:8443/ws/tenant-1/scim/v2/Users/u-1"
        "https://scim.example.test"     | "https://scim.example.test/ws/tenant-1/scim/v2/Me"      | "http://events.example.test:8080/set/tx-1"
    }

    @Unroll
    def "same-origin URL resolver fails with AssertionError on malformed, non-HTTP(S), userinfo, traversal, or control-char URLs (#candidate)"() {
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
            "//evil.example.test/ws/tenant-1/scim/v2/Users/u-1",
            "https://scim.example.test@evil.example.test/Users/u-1",
            "https://scim.example.test\\@evil.example.test/Users/u-1",
            "ftp://scim.example.test/Users/u-1",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "urn:ietf:params:scim:schemas:core:2.0:User",
            "https://scim.example.test/Users/u-1\r\nX-Injected: true",
            "https://scim.example.test:0/Users/u-1",
            "https://scim.example.test:70000/Users/u-1",
            "http:///Users/u-1",
            "http://bad_host/Users/u-1",
            "https://scim.example.test/../admin",
            "../../../../../etc/passwd",
            "",
            "   "
        ]
    }

    def "isSafeRedirectTarget and ScimSafeRedirectStrategy allow same-origin and http-to-https upgrades while blocking cross-origin, downgrades, and dot-segment root traversal"() {
        given:
        URI httpDefault = URI.create("http://scim.example.test/ws/t1/scim/v2")
        URI http8080 = URI.create("http://scim.example.test:8080/ws/t1/scim/v2")
        URI httpsDefault = URI.create("https://scim.example.test/ws/t1/scim/v2")
        ScimSafeRedirectStrategy strategy = new ScimSafeRedirectStrategy()

        expect: "ScimUrlSecurity.isSafeRedirectTarget enforces exact redirect rules"
        ScimUrlSecurity.isSafeRedirectTarget(http8080, URI.create("http://scim.example.test:8080/ws/t1/scim/v2/Users"))
        ScimUrlSecurity.isSafeRedirectTarget(httpsDefault, URI.create("https://SCIM.EXAMPLE.TEST:443/ws/t1/scim/v2/Users"))
        ScimUrlSecurity.isSafeRedirectTarget(httpDefault, URI.create("https://scim.example.test/ws/t1/scim/v2/Users"))
        ScimUrlSecurity.isSafeRedirectTarget(http8080, URI.create("https://scim.example.test/ws/t1/scim/v2/Users"))
        ScimUrlSecurity.isSafeRedirectTarget(http8080, URI.create("https://scim.example.test:443/ws/t1/scim/v2/Users"))

        !ScimUrlSecurity.isSafeRedirectTarget(http8080, URI.create("https://scim.example.test:8443/ws/t1/scim/v2/Users"))
        !ScimUrlSecurity.isSafeRedirectTarget(httpsDefault, URI.create("http://scim.example.test/ws/t1/scim/v2/Users"))
        !ScimUrlSecurity.isSafeRedirectTarget(http8080, URI.create("http://scim.example.test:9090/ws/t1/scim/v2/Users"))
        !ScimUrlSecurity.isSafeRedirectTarget(httpsDefault, URI.create("https://evil.example.test/ws/t1/scim/v2/Users"))
        !ScimUrlSecurity.isSafeRedirectTarget(httpsDefault, URI.create("https://user@scim.example.test/ws/t1/scim/v2/Users"))
        !ScimUrlSecurity.isSafeRedirectTarget(httpsDefault, URI.create("ftp://scim.example.test/ws/t1/scim/v2/Users"))
        !ScimUrlSecurity.isSafeRedirectTarget(httpsDefault, URI.create("https://scim.example.test/../escape"))
        !ScimUrlSecurity.isSafeRedirectTarget(null, httpsDefault)
        !ScimUrlSecurity.isSafeRedirectTarget(httpsDefault, null)

        and: "ScimSafeRedirectStrategy handles Apache HttpClient edge cases cleanly"
        !strategy.isRedirected(null, null, null)
        !strategy.isRedirected(new BasicHttpRequest("GET", "/Users"), new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found"), new BasicHttpContext())

        when: "307/308 redirect on POST upgrades http:8080 to https:443 on the same host"
        BasicHttpRequest postReq = new BasicHttpRequest("POST", "/ws/t1/scim/v2/Users")
        BasicHttpResponse upgradeResp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 307, "Temporary Redirect")
        upgradeResp.addHeader("Location", "https://scim.example.test/ws/t1/scim/v2/Users")
        BasicHttpResponse upgrade308Resp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 308, "Permanent Redirect")
        upgrade308Resp.addHeader("Location", "https://scim.example.test/ws/t1/scim/v2/Users")
        BasicHttpContext ctx = new BasicHttpContext()
        ctx.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, new HttpHost("scim.example.test", 8080, "http"))

        then:
        strategy.isRedirected(postReq, upgradeResp, ctx)
        strategy.isRedirected(postReq, upgrade308Resp, ctx)

        when: "302 redirect is returned on POST (not method-preserving)"
        BasicHttpResponse post302 = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found")
        post302.addHeader("Location", "http://scim.example.test:8080/ws/t1/scim/v2/Users")

        then:
        !strategy.isRedirected(postReq, post302, ctx)

        when: "Location attempts dot-segment root traversal or request line uses an absolute URI"
        BasicHttpRequest getReq = new BasicHttpRequest("GET", "/ws/t1/scim/v2/Users")
        BasicHttpRequest absGetReq = new BasicHttpRequest("GET", "http://scim.example.test:8080/ws/t1/scim/v2/Users")
        BasicHttpResponse validRelResp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found")
        validRelResp.addHeader("Location", "/ws/t1/scim/v2/Final")
        BasicHttpResponse rootDotResp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found")
        rootDotResp.addHeader("Location", "/../escape")
        BasicHttpResponse deepDotResp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found")
        deepDotResp.addHeader("Location", "../../../../../../escape")

        then: "valid relative redirects pass while root-escaping dot-segments are rejected consistently"
        strategy.isRedirected(absGetReq, validRelResp, ctx)
        !strategy.isRedirected(getReq, rootDotResp, ctx)
        !strategy.isRedirected(absGetReq, rootDotResp, ctx)
        !strategy.isRedirected(getReq, deepDotResp, ctx)

        when: "Location is protocol-relative, contains unsafe chars, is malformed, or lacks HTTP_TARGET_HOST"
        BasicHttpResponse protoRelResp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found")
        protoRelResp.addHeader("Location", "//evil.example.test/Users")
        BasicHttpResponse crlfResp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found")
        crlfResp.addHeader("Location", "http://scim.example.test:8080/Users\\evil")
        BasicHttpResponse badUriResp = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found")
        badUriResp.addHeader("Location", "http://[::1")

        then:
        !strategy.isRedirected(getReq, protoRelResp, ctx)
        !strategy.isRedirected(getReq, crlfResp, ctx)
        !strategy.isRedirected(getReq, badUriResp, ctx)
        !strategy.isRedirected(getReq, upgradeResp, new BasicHttpContext())
    }

    def "REST Assured and async HttpClient follow safe same-origin 302/307/308 redirects while blocking cross-origin and root-traversal redirects"() {
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

        AtomicReference<String> postedMethod = new AtomicReference<>()
        AtomicReference<String> postedBody = new AtomicReference<>()
        AtomicReference<String> postedAuth = new AtomicReference<>()

        HttpServer originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        int originPort = originServer.address.port
        originServer.createContext("/ws/t1/scim/v2/CrossOriginRedirect") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreignPort}/steal-token")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/DotSegmentRedirect") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "/../escape")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/DeepDotSegmentRedirect") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "../../../../../../escape")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/SameOriginRedirect") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "/ws/t1/scim/v2/FinalTarget")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/FinalTarget") { HttpExchange exchange ->
            byte[] body = '{"ok":true}'.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirect307") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostTarget")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirect308") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "/ws/t1/scim/v2/PostTarget")
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirectEvil308") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreignPort}/steal-post")
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirectTraversal308") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "/../escape")
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirect302") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "/ws/t1/scim/v2/PostTarget")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirectLoop") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "/ws/t1/scim/v2/PostRedirectLoop")
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirectBadUri") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "http://[::1")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostRedirectProtoRelative") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", "//127.0.0.1:${foreignPort}/steal")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        originServer.createContext("/ws/t1/scim/v2/PostTarget") { HttpExchange exchange ->
            postedMethod.set(exchange.requestMethod)
            postedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            postedBody.set(new String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        originServer.start()

        ValidatorConfiguration.useRunOverrides("http://127.0.0.1:${originPort}/ws/t1/scim/v2", "super-secret-bearer-token")
        TestSecuritySpecHarness.resetRunState()
        TestSecuritySpecHarness.initConfig()
        TestSecuritySpecHarness harness = new TestSecuritySpecHarness()
        HttpClient jdkClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        when: "REST Assured receives a same-origin 302 redirect"
        ScimRunContext.beginRun("redirect-check")
        Response sameOriginGet = harness.issueRequest("/SameOriginRedirect")

        then: "same-origin redirect is followed transparently"
        sameOriginGet.statusCode() == 200
        sameOriginGet.jsonPath().getBoolean("ok")

        when: "caller explicitly disables redirects via .redirects().follow(false) (as /Me does)"
        Response unfollowedGet = TestSecuritySpecHarness.quietRequest().redirects().follow(false).get("/SameOriginRedirect")

        then: "302 is returned directly without following"
        unfollowedGet.statusCode() == 302

        when: "REST Assured receives 307 and 308 redirects on POST to the same origin"
        Response sameOriginPost307 = TestSecuritySpecHarness.quietRequest()
            .body('{"userName":"alice"}')
            .post("/PostRedirect307")
        String method307 = postedMethod.get()
        String body307 = postedBody.get()
        String auth307 = postedAuth.get()

        postedMethod.set(null)
        postedBody.set(null)
        postedAuth.set(null)
        Response sameOriginPost308 = TestSecuritySpecHarness.quietRequest()
            .body('{"userName":"alice-308"}')
            .post("/PostRedirect308")

        then: "both 307 and 308 POST redirects are followed with POST method, body, and Authorization preserved"
        sameOriginPost307.statusCode() == 202
        method307 == "POST"
        body307 == '{"userName":"alice"}'
        auth307 == "Bearer super-secret-bearer-token"
        sameOriginPost308.statusCode() == 202
        postedMethod.get() == "POST"
        postedBody.get() == '{"userName":"alice-308"}'
        postedAuth.get() == "Bearer super-secret-bearer-token"

        when: "target server responds with cross-origin or root-escaping dot-segment Location headers"
        Response crossOriginGet = harness.issueRequest("/CrossOriginRedirect")
        Response dotSegmentGet = harness.issueRequest("/DotSegmentRedirect")
        Response deepDotSegmentGet = harness.issueRequest("/DeepDotSegmentRedirect")
        Response crossOriginPost = TestSecuritySpecHarness.quietRequest()
            .body('{"userName":"bob"}')
            .post("/PostRedirectEvil308")

        then: "REST Assured returns 3xx directly without following unsafe redirects or leaking Bearer credentials"
        crossOriginGet.statusCode() == 302
        dotSegmentGet.statusCode() == 302
        deepDotSegmentGet.statusCode() == 302
        crossOriginPost.statusCode() == 308
        foreignHits.get() == 0
        leakedAuthHeader.get() == null

        when: "async JDK HttpClient helper sends POST across safe 307/308, unsafe, non-307/308, and capped redirects"
        postedMethod.set(null)
        postedBody.set(null)
        postedAuth.set(null)
        HttpResponse<InputStream> asyncOk307 = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirect307"),
            Duration.ofSeconds(5),
            [Authorization: "Bearer async-token-307", "Content-Type": "application/scim+json"],
            '{"userName":"async-user-307"}'
        )
        asyncOk307.body()?.close()
        String asyncMethod307 = postedMethod.get()
        String asyncBody307 = postedBody.get()
        String asyncAuth307 = postedAuth.get()

        postedMethod.set(null)
        postedBody.set(null)
        postedAuth.set(null)
        HttpResponse<InputStream> asyncOk308 = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirect308"),
            Duration.ofSeconds(5),
            [Authorization: "Bearer async-token-308", "Content-Type": "application/scim+json"],
            '{"userName":"async-user-308"}'
        )
        asyncOk308.body()?.close()

        HttpResponse<InputStream> asyncEvil = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirectEvil308"),
            Duration.ofSeconds(5),
            [Authorization: "Bearer async-token"],
            '{}'
        )
        asyncEvil.body()?.close()

        HttpResponse<InputStream> asyncTraversal = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirectTraversal308"),
            Duration.ofSeconds(5),
            [Authorization: "Bearer async-token"],
            '{}'
        )
        asyncTraversal.body()?.close()

        HttpResponse<InputStream> async302 = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirect302"),
            Duration.ofSeconds(5),
            null,
            '{}'
        )
        async302.body()?.close()

        HttpResponse<InputStream> asyncLoop = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirectLoop"),
            Duration.ofSeconds(5),
            [Authorization: "Bearer async-token"],
            '{}',
            2
        )
        asyncLoop.body()?.close()

        HttpResponse<InputStream> asyncBadUri = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirectBadUri"),
            Duration.ofSeconds(5),
            [Authorization: "Bearer async-token"],
            '{}'
        )
        asyncBadUri.body()?.close()

        HttpResponse<InputStream> asyncProtoRel = ScimSafeRedirectStrategy.sendPostWithSafeRedirects(
            jdkClient,
            URI.create("http://127.0.0.1:${originPort}/ws/t1/scim/v2/PostRedirectProtoRelative"),
            Duration.ofSeconds(5),
            [Authorization: "Bearer async-token"],
            '{}'
        )
        asyncProtoRel.body()?.close()

        then:
        asyncOk307.statusCode() == 202
        asyncMethod307 == "POST"
        asyncBody307 == '{"userName":"async-user-307"}'
        asyncAuth307 == "Bearer async-token-307"
        asyncOk308.statusCode() == 202
        postedMethod.get() == "POST"
        postedBody.get() == '{"userName":"async-user-308"}'
        postedAuth.get() == "Bearer async-token-308"
        asyncEvil.statusCode() == 308
        asyncTraversal.statusCode() == 308
        async302.statusCode() == 302
        asyncLoop.statusCode() == 308
        asyncBadUri.statusCode() == 307
        asyncProtoRel.statusCode() == 307
        foreignHits.get() == 0
        leakedAuthHeader.get() == null

        when: "Rfc9967 follow-up helper is given valid cross-origin or non-443 HTTPS port URLs"
        harness.fetchFollowUp("http://127.0.0.1:${foreignPort}/Events/tx-1")

        then: "test aborts via TestAbortedException before any network call is sent"
        thrown(TestAbortedException)
        foreignHits.get() == 0

        when: "Rfc9967 follow-up helper is given a non-443 HTTPS URL on the same loopback host"
        harness.fetchFollowUp("https://127.0.0.1:${originPort}/ws/t1/scim/v2/Events/tx-1")

        then:
        thrown(TestAbortedException)
        foreignHits.get() == 0

        when: "Rfc9967 follow-up helper is given an unsafe non-HTTP or userinfo URL"
        harness.fetchFollowUp("http://user@127.0.0.1:${foreignPort}/Events/tx-1")

        then: "test fails via AssertionError before any network call is sent"
        thrown(AssertionError)
        foreignHits.get() == 0

        cleanup:
        TestSecuritySpecHarness.resetRunState()
        originServer?.stop(0)
        foreignServer?.stop(0)
    }

    def "SCIM_API_URL with path prefix preserves prefix across configuration, colon-containing relative paths, and Rfc7644MeSpec"() {
        given:
        AtomicReference<String> meLocationHeader = new AtomicReference<>("/ws/t1/scim/v2/Users/u-me")
        AtomicInteger prefixedUserHits = new AtomicInteger(0)

        HttpServer prefixedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        int port = prefixedServer.address.port
        prefixedServer.createContext("/api/ws/t1/scim/v2/ServiceProviderConfig") { HttpExchange exchange ->
            byte[] body = '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"]}'.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        prefixedServer.createContext("/api/ws/t1/scim/v2/Users/u-me") { HttpExchange exchange ->
            prefixedUserHits.incrementAndGet()
            byte[] body = JsonOutput.toJson([
                schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"],
                id     : "u-me",
                meta   : [location: "http://127.0.0.1:${port}/api/ws/t1/scim/v2/Users/u-me"]
            ]).getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        prefixedServer.createContext("/api/ws/t1/scim/v2/Me") { HttpExchange exchange ->
            exchange.responseHeaders.add("Location", meLocationHeader.get())
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }
        prefixedServer.createContext("/api/ws/t1/scim/v2/Users") { HttpExchange exchange ->
            byte[] body = '{"Resources":[],"totalResults":0}'.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        prefixedServer.createContext("/api/ws/t1/scim/v2/Events/tx-1") { HttpExchange exchange ->
            byte[] body = '{"jti":"tx-1"}'.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/secevent+jwt")
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        prefixedServer.start()

        when: "SCIM_API_URL has a path prefix and trailing slash"
        ValidatorConfiguration.useApiRunOverrides("http://127.0.0.1:${port}/api/", "t1", "prefix-secret")
        TestSecuritySpecHarness.resetRunState()
        TestSecuritySpecHarness.initConfig()
        TestSecuritySpecHarness harness = new TestSecuritySpecHarness()
        Response usersResp = TestSecuritySpecHarness.quietRequest().get("/Users")
        Response eventResp = harness.fetchFollowUp("/ws/t1/scim/v2/Events/tx-1")

        then: "no double slash is produced and /api prefix is preserved for both requests and follow-ups"
        ScimBaseSpec.BASE_URL == "http://127.0.0.1:${port}/api/ws/t1/scim/v2"
        ScimBaseSpec.BASE_PATH == "/ws/t1/scim/v2"
        usersResp.statusCode() == 200
        eventResp.statusCode() == 200

        when: "Rfc7644MeSpec executes against /api-prefixed server with basePath-relative and resource-relative 308 Locations"
        ValidatorConfiguration.useApiRunOverrides("http://127.0.0.1:${port}/api", "t1", "prefix-secret")
        meLocationHeader.set("/ws/t1/scim/v2/Users/u-me")
        def meBasePathSummary = executeSpecClass(Rfc7644MeSpec)
        int hitsAfterBasePathLocation = prefixedUserHits.get()

        prefixedUserHits.set(0)
        meLocationHeader.set("/Users/u-me")
        def meResourceSummary = executeSpecClass(Rfc7644MeSpec)
        int hitsAfterResourceLocation = prefixedUserHits.get()

        then: "Rfc7644MeSpec preserves /api prefix on 308 follow-up requests with zero failures"
        meBasePathSummary.testsFailedCount == 0
        hitsAfterBasePathLocation >= 3
        meResourceSummary.testsFailedCount == 0
        hitsAfterResourceLocation >= 3

        and: "resolveScimReferenceUrl preserves /api across full-path, basePath-prefixed, colon-containing, root, and resource-relative forms"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/api/ws/t1/scim/v2/Events/tx-1"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/Events/tx-1"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api/",
            "https://scim.example.test/api/ws/t1/scim/v2/",
            "/ws/t1/scim/v2",
            "/api/ws/t1/scim/v2?count=1"
        ) == "https://scim.example.test/api/ws/t1/scim/v2?count=1"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/ws/t1/scim/v2/Events/tx-2"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/Events/tx-2"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api/",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "ws/t1/scim/v2/",
            "/ws/t1/scim/v2"
        ) == "https://scim.example.test/api/ws/t1/scim/v2"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/Users/u-1"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/Users/u-1"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api/",
            "https://scim.example.test/api/ws/t1/scim/v2/",
            "/ws/t1/scim/v2",
            "Users/u-2"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/Users/u-2"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/txn:0"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/txn:0"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/Events/tx-1:0"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/Events/tx-1:0"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/urn:ietf:params:scim:schemas:core:2.0:User"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/urn:ietf:params:scim:schemas:core:2.0:User"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "http://scim.example.test/api",
            "http://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "https://scim.example.test/api/ws/t1/scim/v2/Users/u-https"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/Users/u-https"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test/api",
            "https://scim.example.test/api/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/ws/t1/scim/v200/Users/u-3"
        ) == "https://scim.example.test/api/ws/t1/scim/v2/ws/t1/scim/v200/Users/u-3"
        !ScimUrlSecurity.matchesPathPrefix(null, "/ws")
        !ScimUrlSecurity.matchesPathPrefix("/Users", null)
        !ScimUrlSecurity.matchesPathPrefix("/Users", "/")
        ScimUrlSecurity.matchesPathPrefix("/ws/t1", "/ws/")
        ScimUrlSecurity.matchesPathPrefix("/ws/t1#frag", "/ws/t1")

        cleanup:
        TestSecuritySpecHarness.resetRunState()
        prefixedServer?.stop(0)
    }

    def "RestAssuredConfig sets 10s/60s timeouts with configurable overrides and redacts sensitive request and response headers in console logs"() {
        given:
        HttpServer localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        localServer.createContext("/ws/t1/scim/v2/Users") { HttpExchange exchange ->
            byte[] body = '{"Resources":[],"totalResults":0}'.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/scim+json")
            exchange.responseHeaders.add("Set-Cookie", "SID=live-cookie-secret")
            exchange.responseHeaders.add("X-API-Key", "echoed-api-key-secret")
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
        ByteArrayOutputStream consoleBuffer = new ByteArrayOutputStream()
        PrintStream consoleStream = new PrintStream(consoleBuffer, true, StandardCharsets.UTF_8)
        PrintStream originalOut = System.out
        String propKey = "scim.http.testTimeoutOverrideMs"

        Response mockNonRestAssuredResp = Mock(Response) {
            statusLine() >> "HTTP/1.1 200 OK"
            headers() >> new Headers(new Header("Set-Cookie", "SID=mock-cookie-secret"))
            asString() >> "{}"
        }
        FilterContext mockFilterCtx = Mock(FilterContext) {
            next(_, _) >> mockNonRestAssuredResp
        }

        when:
        Response resp = TestSecuritySpecHarness.quietRequest()
            .header("Cookie", "SESSION=client-cookie-secret")
            .header("Proxy-Authorization", "Basic cHJveHktc2VjcmV0")
            .header("X-API-Key", "api-key-live-secret")
            .filter(TestSecuritySpecHarness.redactingLogger(logStream))
            .filter(TestSecuritySpecHarness.redactingResponseLogger(logStream))
            .get("/Users")
        Response mockFiltered = TestSecuritySpecHarness.redactingResponseLogger(logStream)
            .filter(Mock(FilterableRequestSpecification), Mock(FilterableResponseSpecification), mockFilterCtx)
        String logged = logBuffer.toString(StandardCharsets.UTF_8)

        ScimRunContext.endRun()
        Response scimConsoleResp
        try {
            System.setOut(consoleStream)
            scimConsoleResp = new TestSecuritySpecHarness().issueRequest("/Users")
        } finally {
            System.setOut(originalOut)
        }
        String consoleLogged = consoleBuffer.toString(StandardCharsets.UTF_8)

        System.setProperty(propKey, "25000")
        int fromProp = ScimBaseSpec.resolveTimeoutMs(propKey, "SCIM_HTTP_TEST_TIMEOUT_MS", 60_000, [:])
        System.setProperty(propKey, "not-an-int")
        int fallbackEnv = ScimBaseSpec.resolveTimeoutMs(propKey, "SCIM_HTTP_TEST_TIMEOUT_MS", 60_000, [SCIM_HTTP_TEST_TIMEOUT_MS: "45000"])
        System.setProperty(propKey, "-10")
        int fallbackDefault = ScimBaseSpec.resolveTimeoutMs(propKey, "SCIM_HTTP_TEST_TIMEOUT_MS", 60_000, [SCIM_HTTP_TEST_TIMEOUT_MS: "0"])

        then:
        resp.statusCode() == 200
        resp.jsonPath().getInt("totalResults") == 0
        mockFiltered.is(mockNonRestAssuredResp)
        scimConsoleResp.statusCode() == 200
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.redirectConfig.followsRedirects()
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.redirectConfig.maxRedirects() == 5
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.httpClientConfig.params()["http.connection.timeout"] == 10_000
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.httpClientConfig.params()["http.socket.timeout"] == 60_000
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.httpClientConfig.params()["http.connection-manager.timeout"] == 10_000L
        ScimBaseSpec.SCIM_REST_ASSURED_CONFIG.logConfig.blacklistedHeaders().containsAll(ScimExchangeCaptureFilter.SENSITIVE_HEADER_NAMES)
        fromProp == 25_000
        fallbackEnv == 45_000
        fallbackDefault == 60_000
        logged.contains("BLACKLISTED")
        logged.toLowerCase(Locale.ROOT).contains("set-cookie: [ blacklisted ]")
        logged.toLowerCase(Locale.ROOT).contains("x-api-key: [ blacklisted ]")
        !logged.contains("live-cookie-secret")
        !logged.contains("mock-cookie-secret")
        !logged.contains("echoed-api-key-secret")
        !logged.contains("live-secret-bearer-xyz")
        !logged.contains("client-cookie-secret")
        !logged.contains("cHJveHktc2VjcmV0")
        !logged.contains("api-key-live-secret")
        consoleLogged.toLowerCase(Locale.ROOT).contains("set-cookie: [ blacklisted ]")
        !consoleLogged.contains("live-cookie-secret")
        !consoleLogged.contains("echoed-api-key-secret")
        !consoleLogged.contains("live-secret-bearer-xyz")

        cleanup:
        System.clearProperty(propKey)
        TestSecuritySpecHarness.resetRunState()
        localServer?.stop(0)
    }

    def "bounded UTF-8 stream reader and ScimJsonSupport enforce document length, multi-byte UTF-8 size, and nesting depth limits"() {
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

        // Build >4 MiB JSON with tiny 1-char tokens ("1") so maxStringLength alone would not reject it
        StringBuilder hugeJsonBuilder = new StringBuilder(ScimJsonSupport.DEFAULT_MAX_RESPONSE_BYTES + 4096)
        hugeJsonBuilder.append('{"a":[')
        int count = (ScimJsonSupport.DEFAULT_MAX_RESPONSE_BYTES / 2) + 512
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                hugeJsonBuilder.append(',')
            }
            hugeJsonBuilder.append('1')
        }
        hugeJsonBuilder.append(']}')
        String hugeTokenArrayJson = hugeJsonBuilder.toString()

        String asciiWithin120 = '{"v":"' + ('a' * 60) + '"}'
        String utf8Exceeding120 = '{"v":"' + ('€' * 45) + '"}'
        String deeplyNestedJson = ('{"a":' * 70) + '1' + ('}' * 70)

        expect:
        ScimJsonSupport.readBoundedUtf8(null) == ""
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(okPayload), 2048).length() == 1024
        ScimJsonSupport.parseMap((Response) null).isEmpty()
        ScimJsonSupport.parseMap(nullBodyResponse).isEmpty()
        ScimJsonSupport.parseMap(validResponse) == [id: "u-42", active: true]
        ScimJsonSupport.parseMap((String) null).isEmpty()
        ScimJsonSupport.parseMap("   ").isEmpty()
        ScimJsonSupport.parseMap("null").isEmpty()
        ScimJsonSupport.parseMap(asciiWithin120, 120).v.length() == 60
        ScimJsonSupport.getLiteralAttribute(null, "id") == null
        ScimJsonSupport.getLiteralAttribute([id: "u-42"], null) == null
        ScimJsonSupport.extractExtraSubIdAttributeNames(null).isEmpty()
        ScimJsonSupport.extractExtraSubIdAttributeNames([:]).isEmpty()
        ScimJsonSupport.extractExtraSubIdAttributeNames([(null): "ignored", format: "uri", tenantKey: "t-1"]) == ["tenantKey"] as Set

        when: "stream exceeds maxBytes"
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(oversizedPayload), 2048)

        then:
        thrown(IllegalStateException)

        when: "readBoundedUtf8 maxBytes is negative"
        ScimJsonSupport.readBoundedUtf8(new ByteArrayInputStream(okPayload), -1)

        then:
        thrown(IllegalArgumentException)

        when: "parseMap maxBytes is negative"
        ScimJsonSupport.parseMap("{}", -1)

        then:
        thrown(IllegalArgumentException)

        when: "JSON document exceeds DEFAULT_MAX_RESPONSE_BYTES via millions of tiny tokens"
        ScimJsonSupport.parseMap(hugeTokenArrayJson)

        then:
        thrown(IllegalArgumentException)

        when: "multi-byte UTF-8 JSON exceeds maxBytes even though char length is <= maxBytes"
        assert utf8Exceeding120.length() <= 120
        ScimJsonSupport.parseMap(utf8Exceeding120, 120)

        then:
        thrown(IllegalArgumentException)

        when: "JSON nesting depth exceeds MAX_JSON_NESTING_DEPTH"
        ScimJsonSupport.parseMap(deeplyNestedJson)

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
            "https://scim.example.test/scim?foo=bar",
            "https://scim.example.test/scim#frag",
            "http:///missing-host",
            "http://scim.example.test:0/scim",
            "https://scim.example.test:70000/scim"
        ]
    }

    def "effectivePort, isSameOrigin, resolveScimReferenceUrl, and caller error paths enforce strict origin semantics"() {
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
        !ScimUrlSecurity.isSameOrigin(httpsDefault, URI.create("https://scim.example.test:0/Users"))
        !ScimUrlSecurity.isSameOrigin(httpsDefault, URI.create("https://scim.example.test:70000/Users"))

        and: "resolveScimReferenceUrl handles absolute, basePath-prefixed, slash-prefixed, colon-containing, root, and relative paths"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            "https://scim.example.test",
            "/",
            "/Users/u-root"
        ) == "https://scim.example.test/Users/u-root"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/txn:0"
        ) == "https://scim.example.test/ws/t1/scim/v2/txn:0"
        ScimUrlSecurity.resolveScimReferenceUrl(
            "https://scim.example.test",
            "https://scim.example.test/ws/t1/scim/v2",
            "/ws/t1/scim/v2",
            "/"
        ) == "https://scim.example.test/ws/t1/scim/v2/"
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

        when: "resolution base URL does not match expected origin"
        ScimUrlSecurity.resolveSameOriginUrl("https://scim.example.test", "https://other.example.test", "/Users/u-1")

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

    def "Rfc7644MeSpec and Rfc7643AttributeModelSpec abort on valid cross-origin URLs and fail on unsafe URLs without contacting foreign hosts"() {
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
        AtomicReference<String> refMode = new AtomicReference<>("cross-origin")
        HttpServer scimServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        int scimPort = scimServer.address.port
        scimServer.createContext("/ws/t1/scim/v2/ServiceProviderConfig") { HttpExchange exchange ->
            byte[] body = '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"],"securityEvents":{"asyncRequest":"request"}}'.getBytes(StandardCharsets.UTF_8)
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
        scimServer.createContext("/ws/t1/scim/v2/AsyncAcceptedTarget") { HttpExchange exchange ->
            exchange.responseHeaders.add("Set-Txn", "txn-1")
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        scimServer.createContext("/ws/t1/scim/v2/Users") { HttpExchange exchange ->
            if (exchange.requestMethod == "POST" && exchange.requestHeaders.getFirst("Prefer")?.contains("respond-async")) {
                exchange.responseHeaders.add("Location", "/ws/t1/scim/v2/AsyncAcceptedTarget")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            } else if (exchange.requestMethod == "POST") {
                byte[] body = JsonOutput.toJson([
                    schemas : ["urn:ietf:params:scim:schemas:core:2.0:User"],
                    id      : "u-ref-1",
                    userName: "refuser"
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(201, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            } else {
                byte[] body = JsonOutput.toJson([
                    schemas : ["urn:ietf:params:scim:schemas:core:2.0:User"],
                    id      : "u-ref-1",
                    userName: "refuser"
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            }
        }
        scimServer.createContext("/ws/t1/scim/v2/Groups") { HttpExchange exchange ->
            if (exchange.requestMethod == "POST") {
                byte[] body = JsonOutput.toJson([
                    schemas    : ["urn:ietf:params:scim:schemas:core:2.0:Group"],
                    id         : "g-ref-1",
                    displayName: "refgrp"
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(201, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            } else {
                String memberRef
                if (refMode.get() == "same-origin") {
                    memberRef = "http://127.0.0.1:${scimPort}/ws/t1/scim/v2/Users/u-ref-1"
                } else if (refMode.get() == "cross-origin") {
                    memberRef = "http://127.0.0.1:${foreignPort}/ws/t1/scim/v2/Users/u-ref-1"
                } else {
                    memberRef = "javascript:alert(1)"
                }
                byte[] body = JsonOutput.toJson([
                    schemas    : ["urn:ietf:params:scim:schemas:core:2.0:Group"],
                    id         : "g-ref-1",
                    displayName: "refgrp",
                    members    : [[value: "u-ref-1", '$ref': memberRef]]
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            }
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
            } else if (mode == "308-unsafe") {
                exchange.responseHeaders.add("Location", "javascript:alert(1)")
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
            } else if (mode == "200-cross-origin") {
                byte[] body = JsonOutput.toJson([
                    schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"],
                    id     : "u-me",
                    meta   : [location: "http://127.0.0.1:${foreignPort}/ws/t1/scim/v2/Users/u-me"]
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            } else {
                byte[] body = JsonOutput.toJson([
                    schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"],
                    id     : "u-me",
                    meta   : [location: "http://user@127.0.0.1:${foreignPort}/ws/t1/scim/v2/Users/u-me"]
                ]).getBytes(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/scim+json")
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            }
        }
        scimServer.start()

        ValidatorConfiguration.useRunOverrides("http://127.0.0.1:${scimPort}/ws/t1/scim/v2/", "me-secret-token")
        TestSecuritySpecHarness.resetRunState()
        TestSecuritySpecHarness.initConfig()
        TestSecuritySpecHarness harness = new TestSecuritySpecHarness()

        Response patch204 = Mock(Response) { statusCode() >> 204 }
        Response patch200 = Mock(Response) { statusCode() >> 200 }

        when: "resolvePatchRepresentation follows 200 and 204 responses on configured base URL"
        Response patchDirect = harness.followPatch(patch200, "/Users/u-me")
        Response patchFollowed = harness.followPatch(patch204, "/Users/u-me")

        then:
        patchDirect.is(patch200)
        patchFollowed.statusCode() == 200
        patchFollowed.jsonPath().getString("id") == "u-me"

        when: "/Me responds 308 or 200 with same-origin URLs"
        meMode.set("308-same-origin")
        def summary308Ok = executeSpecClass(Rfc7644MeSpec)
        meMode.set("200-same-origin")
        def summary200Ok = executeSpecClass(Rfc7644MeSpec)

        then:
        summary308Ok.testsFailedCount == 0
        summary200Ok.testsFailedCount == 0
        foreignHits.get() == 0

        when: "/Me responds 308 or 200 with valid cross-origin HTTP URLs"
        meMode.set("308-cross-origin")
        def summary308Cross = executeSpecClass(Rfc7644MeSpec)
        meMode.set("200-cross-origin")
        def summary200Cross = executeSpecClass(Rfc7644MeSpec)

        then: "follow-up tests abort cleanly (0 failures) without sending requests to the foreign host"
        summary308Cross.testsFailedCount == 0
        summary308Cross.testsAbortedCount >= 3
        summary200Cross.testsFailedCount == 0
        summary200Cross.testsAbortedCount >= 1
        foreignHits.get() == 0

        when: "/Me responds 308 or 200 with unsafe non-HTTP or userinfo URLs"
        meMode.set("308-unsafe")
        def summary308Unsafe = executeSpecClass(Rfc7644MeSpec)
        meMode.set("200-unsafe")
        def summary200Unsafe = executeSpecClass(Rfc7644MeSpec)

        then: "tests fail compliance assertions without contacting foreign hosts"
        summary308Unsafe.testsFailedCount >= 1
        summary200Unsafe.testsFailedCount >= 1
        foreignHits.get() == 0

        when: 'RFC7643-2.3.7-1d19d6 and RFC9967-2.5.1.1-84d853 execute against same-origin, cross-origin, and unsafe targets'
        String refFeatureName = "RFC7643-2.3.7-1d19d6: An HTTP client performing a GET operation on a reference URI MUST receive the target resource or an..."
        String asyncAcceptFeature = "RFC9967-2.5.1.1-84d853: The HTTP 'Accept' header MUST be ignored for purposes of an asynchronous response."
        refMode.set("same-origin")
        def refSameSummary = executeSpecMethod(Rfc7643AttributeModelSpec, refFeatureName)
        refMode.set("cross-origin")
        def refCrossSummary = executeSpecMethod(Rfc7643AttributeModelSpec, refFeatureName)
        refMode.set("unsafe")
        def refUnsafeSummary = executeSpecMethod(Rfc7643AttributeModelSpec, refFeatureName)
        def asyncAcceptSummary = executeSpecMethod(Rfc9967AsyncSpec, asyncAcceptFeature)

        then:
        refSameSummary.testsSucceededCount == 1
        refSameSummary.testsFailedCount == 0
        refCrossSummary.testsFailedCount == 0
        refCrossSummary.testsAbortedCount == 1
        refUnsafeSummary.testsFailedCount == 1
        asyncAcceptSummary.testsSucceededCount == 1
        asyncAcceptSummary.testsFailedCount == 0
        foreignHits.get() == 0

        cleanup:
        TestSecuritySpecHarness.resetRunState()
        scimServer?.stop(0)
        foreignServer?.stop(0)
    }

    private static def executeSpecClass(Class<?> specClass) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(specClass))
            .build()
        SummaryGeneratingListener listener = new SummaryGeneratingListener()
        LauncherFactory.create().execute(request, listener)
        return listener.summary
    }

    private static def executeSpecMethod(Class<?> specClass, String methodName) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectMethod(specClass, methodName))
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

        static def redactingResponseLogger(PrintStream stream) {
            return createRedactingResponseLoggingFilter(stream)
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
