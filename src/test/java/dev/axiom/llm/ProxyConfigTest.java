package dev.axiom.llm;

import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProxyConfig}: env parsing, scheme selection,
 * {@code NO_PROXY} bypass, and proxy authentication — no network I/O.
 */
class ProxyConfigTest {

    @Test
    void noProxyVarsMeansEmpty() {
        assertTrue(ProxyConfig.fromEnv(Map.of()).isEmpty());
        assertTrue(ProxyConfig.fromEnv(Map.of("SOME_OTHER", "x")).isEmpty());
    }

    @Test
    void parsesHttpsProxyWithAuth() {
        var s = ProxyConfig.fromEnv(Map.of(
            "https_proxy", "http://user:p%40ss@proxy.example:3128")).orElseThrow();
        assertEquals("proxy.example", s.https().host());
        assertEquals(3128, s.https().port());
        assertEquals("user", s.https().username());
        assertEquals("p@ss", s.https().password());
    }

    @Test
    void defaultsSchemeAndPort() {
        var ep = ProxyConfig.parse("proxy.internal:8080", 443);
        assertEquals("proxy.internal", ep.host());
        assertEquals(8080, ep.port());
        var ep2 = ProxyConfig.parse("proxy.internal", 443);
        assertEquals(443, ep2.port());
    }

    @Test
    void malformedProxyUrlIsIgnored() {
        assertNull(ProxyConfig.parse("http://", 443));
        assertNull(ProxyConfig.parse("not a url with spaces", 443));
        assertTrue(ProxyConfig.fromEnv(Map.of("https_proxy", "http://")).isEmpty());
    }

    @Test
    void allProxyFallsBackForBothSchemes() {
        var s = ProxyConfig.fromEnv(Map.of("ALL_PROXY", "http://proxy:3128")).orElseThrow();
        assertEquals("proxy", s.https().host());
        assertEquals("proxy", s.http().host());
    }

    @Test
    void schemeSpecificVarsWinOverAllProxy() {
        var s = ProxyConfig.fromEnv(Map.of(
            "ALL_PROXY", "http://generic:3128",
            "HTTPS_PROXY", "http://secure:4443")).orElseThrow();
        assertEquals("secure", s.https().host());
        assertEquals(4443, s.https().port());
        assertEquals("generic", s.http().host());
    }

    @Test
    void selectorRoutesByScheme() {
        var s = ProxyConfig.fromEnv(Map.of(
            "HTTPS_PROXY", "http://secure-proxy:4443",
            "HTTP_PROXY", "http://plain-proxy:8080")).orElseThrow();
        ProxySelector sel = ProxyConfig.selectorFor(s);

        Proxy httpsProxy = sel.select(URI.create("https://api.example.com/v1")).get(0);
        assertEquals(Proxy.Type.HTTP, httpsProxy.type());
        assertEquals(new InetSocketAddress("secure-proxy", 4443), httpsProxy.address());

        Proxy httpProxy = sel.select(URI.create("http://api.example.com/v1")).get(0);
        assertEquals(new InetSocketAddress("plain-proxy", 8080), httpProxy.address());
    }

    @Test
    void noProxyBypassesListedHosts() {
        var s = ProxyConfig.fromEnv(Map.of(
            "HTTPS_PROXY", "http://proxy:3128",
            "NO_PROXY", "localhost, 127.0.0.1, .internal.example, db:5432")).orElseThrow();
        ProxySelector sel = ProxyConfig.selectorFor(s);

        assertEquals(List.of(Proxy.NO_PROXY),
            sel.select(URI.create("https://localhost:11434/v1")));
        assertEquals(List.of(Proxy.NO_PROXY),
            sel.select(URI.create("http://127.0.0.1:8080/")));
        // Leading-dot entry matches the domain and its subdomains.
        assertEquals(List.of(Proxy.NO_PROXY),
            sel.select(URI.create("https://svc.internal.example/")));
        assertEquals(List.of(Proxy.NO_PROXY),
            sel.select(URI.create("https://internal.example/")));
        // host:port entry matches the host regardless of target port.
        assertEquals(List.of(Proxy.NO_PROXY),
            sel.select(URI.create("https://db:9999/")));

        // Unlisted hosts still go through the proxy.
        Proxy routed = sel.select(URI.create("https://api.example.com/")).get(0);
        assertEquals(new InetSocketAddress("proxy", 3128), routed.address());
        // Suffix tricks must not bypass: notexample.com is not example.com.
        Proxy notBypassed = sel.select(URI.create("https://notinternal.example/")).get(0);
        assertEquals(new InetSocketAddress("proxy", 3128), notBypassed.address());
    }

    @Test
    void wildcardNoProxyBypassesEverything() {
        var s = ProxyConfig.fromEnv(Map.of(
            "HTTPS_PROXY", "http://proxy:3128",
            "no_proxy", "*")).orElseThrow();
        ProxySelector sel = ProxyConfig.selectorFor(s);
        assertEquals(List.of(Proxy.NO_PROXY),
            sel.select(URI.create("https://anything.example/")));
    }

    @Test
    void authenticatorSuppliesProxyCredentialsOnlyForProxyChallenges() {
        var s = ProxyConfig.fromEnv(Map.of(
            "HTTPS_PROXY", "http://user:s3cret@proxy:3128")).orElseThrow();
        Authenticator auth = ProxyConfig.authenticatorFor(s).orElseThrow();
        var proxyAuth = (ProxyConfig.ProxyAuthenticator) auth;
        PasswordAuthentication pa =
            proxyAuth.credentialsFor(Authenticator.RequestorType.PROXY);
        assertEquals("user", pa.getUserName());
        assertArrayEquals("s3cret".toCharArray(), pa.getPassword());
        // Server (401) challenges get nothing: the API key rides the header.
        assertNull(proxyAuth.credentialsFor(Authenticator.RequestorType.SERVER));
    }

    @Test
    void authenticatorAbsentWithoutCredentials() {
        var s = ProxyConfig.fromEnv(Map.of("HTTPS_PROXY", "http://proxy:3128")).orElseThrow();
        assertTrue(ProxyConfig.authenticatorFor(s).isEmpty());
    }
}
