package dev.axiom.llm;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Proxy support for {@link OpenAiCompatibleClient}.
 *
 * <p>Honors the standard {@code HTTPS_PROXY}/{@code HTTP_PROXY}/{@code ALL_PROXY}
 * and {@code NO_PROXY} environment variables the way curl, Python, and Node do.
 * Java's {@code HttpClient} ignores these on its own, so without this class any
 * sandbox or corporate egress proxy turns every HTTPS call into a TLS failure
 * ({@code "Unrecognized SSL message, plaintext connection?"}).
 */
public final class ProxyConfig {
    private ProxyConfig() {}

    /** One proxy endpoint: host, port, optional credentials. */
    public record ProxyEndpoint(String host, int port, String username, String password) {
        boolean hasCredentials() { return username != null && !username.isEmpty(); }
    }

    /** Parsed proxy configuration: per-scheme endpoints plus bypass list. */
    public record Settings(ProxyEndpoint https, ProxyEndpoint http, List<String> noProxy) {}

    /** Read proxy settings from the process environment. */
    public static Optional<Settings> fromEnv() {
        return fromEnv(System.getenv());
    }

    /**
     * Apply the environment proxy configuration to an {@link java.net.http.HttpClient.Builder}.
     * Safe to call unconditionally: no-op when no proxy is configured. Web tools
     * (search/fetch) must call this — sandbox DNS interception blackholes direct
     * connections to non-allowlisted hosts, so only the proxy path reaches them.
     */
    public static void configureClient(java.net.http.HttpClient.Builder builder) {
        fromEnv().ifPresent(s -> {
            builder.proxy(selectorFor(s));
            applyPreemptiveProxyAuth(s);
        });
    }

    /** Parse proxy settings from a supplied environment map (test seam). */
    public static Optional<Settings> fromEnv(Map<String, String> env) {
        ProxyEndpoint https = parse(
            firstNonBlank(env, "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"), 443);
        ProxyEndpoint http = parse(
            firstNonBlank(env, "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"), 80);
        List<String> noProxy = parseNoProxy(firstNonBlank(env, "NO_PROXY", "no_proxy"));
        if (https == null && http == null) return Optional.empty();
        return Optional.of(new Settings(https, http, noProxy));
    }

    /** Build a {@link ProxySelector} routing through the configured proxy. */
    public static ProxySelector selectorFor(Settings settings) {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                ProxyEndpoint ep = "https".equalsIgnoreCase(uri.getScheme())
                    ? settings.https() : settings.http();
                if (ep == null) ep = settings.https() != null ? settings.https() : settings.http();
                if (ep == null || isBypassed(uri.getHost(), settings.noProxy())) {
                    return List.of(Proxy.NO_PROXY);
                }
                InetAddress resolved = resolveProxyHost(ep.host());
                InetSocketAddress addr = resolved != null
                    ? new InetSocketAddress(resolved, ep.port())
                    : new InetSocketAddress(ep.host(), ep.port());
                return List.of(new Proxy(Proxy.Type.HTTP, addr));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // Nothing to do here: the client's retry policy surfaces the failure.
            }
        };
    }

    /**
     * Resolve the proxy hostname, preferring IPv6 addresses.
     *
     * <p>Empirically (2026-10-02, Hatch sandbox): the egress proxy's IPv4
     * endpoint (198.19.0.1:3128) accepts TCP but resets every connection,
     * while its IPv6 endpoint works. {@link InetSocketAddress} with a
     * hostname lets the JVM pick — usually the dead IPv4. Resolving here
     * and preferring IPv6 makes the proxy path actually work.
     */
    static InetAddress resolveProxyHost(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (a instanceof java.net.Inet6Address) return a;
            }
            if (addrs.length > 0) return addrs[0];
        } catch (UnknownHostException e) {
            // fall through to unresolved
        }
        return null;
    }

    /**
     * Build an {@link Authenticator} supplying proxy credentials on 407
     * challenges, or empty when the configured proxy needs no auth.
     *
     * <p><b>Do not install this on an {@link java.net.http.HttpClient}.</b>
     * Empirically (2026-09-27, JDK 21): the mere presence of <i>any</i>
     * authenticator on the client causes the user's own {@code Authorization}
     * header to never reach the server — no 407/401 challenge is even issued —
     * so every API call fails with "Missing or invalid Authorization header".
     * Use {@link #applyPreemptiveProxyAuth(Settings)} instead, which sends
     * proxy credentials preemptively via the standard
     * {@code https.proxyUser}/{@code https.proxyPassword} system properties
     * and leaves the {@code Authorization} header untouched.
     */
    public static Optional<Authenticator> authenticatorFor(Settings settings) {
        ProxyEndpoint ep = settings.https() != null ? settings.https() : settings.http();
        if (ep == null || !ep.hasCredentials()) return Optional.empty();
        String user = ep.username();
        char[] pass = ep.password() == null ? new char[0] : ep.password().toCharArray();
        return Optional.of(new ProxyAuthenticator(user, pass));
    }

    /**
     * Authenticator that answers proxy (407) challenges with the configured
     * credentials and stays silent for server (401) challenges — API keys
     * travel in the {@code Authorization} header, never here.
     */
    static final class ProxyAuthenticator extends Authenticator {
        private final String user;
        private final char[] pass;

        ProxyAuthenticator(String user, char[] pass) {
            this.user = user;
            this.pass = pass;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return credentialsFor(getRequestorType());
        }

        /** Test seam: resolve credentials for a challenge type without a live 407. */
        PasswordAuthentication credentialsFor(RequestorType type) {
            if (type == RequestorType.PROXY) {
                return new PasswordAuthentication(user, pass);
            }
            return null;
        }
    }

    /**
     * Send proxy credentials preemptively via the standard
     * {@code https.proxyUser}/{@code https.proxyPassword} (and {@code http.*})
     * system properties, which {@link java.net.http.HttpClient} transmits as
     * {@code Proxy-Authorization} on the CONNECT without any 407 round-trip.
     *
     * <p>Properties already set (e.g. explicit {@code -D} flags) are left
     * alone. Prefer this over {@link #authenticatorFor(Settings)}: installing
     * an authenticator on the client suppresses the user's own
     * {@code Authorization} header even when no challenge occurs.
     */
    public static void applyPreemptiveProxyAuth(Settings settings) {
        ProxyEndpoint ep = settings.https() != null ? settings.https() : settings.http();
        if (ep == null || !ep.hasCredentials()) return;
        setIfAbsent("https.proxyUser", ep.username());
        if (ep.password() != null) setIfAbsent("https.proxyPassword", ep.password());
        setIfAbsent("http.proxyUser", ep.username());
        if (ep.password() != null) setIfAbsent("http.proxyPassword", ep.password());
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    // ------------------------------------------------------------------
    // Parsing (package-visible for tests)
    // ------------------------------------------------------------------

    static ProxyEndpoint parse(String proxyUrl, int defaultPort) {
        if (proxyUrl == null || proxyUrl.isBlank()) return null;
        String u = proxyUrl.trim();
        if (!u.contains("://")) u = "http://" + u;
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return null;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int i = userInfo.indexOf(':');
            if (i >= 0) {
                username = decode(userInfo.substring(0, i));
                password = decode(userInfo.substring(i + 1));
            } else {
                username = decode(userInfo);
            }
        }
        return new ProxyEndpoint(host, port, username, password);
    }

    static List<String> parseNoProxy(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .map(s -> s.toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .map(s -> s.startsWith(".") ? s.substring(1) : s)
            .toList();
    }

    static boolean isBypassed(String host, List<String> noProxy) {
        if (host == null || noProxy.isEmpty()) return false;
        String h = stripPort(host.toLowerCase(Locale.ROOT));
        for (String entry : noProxy) {
            if (entry.equals("*")) return true;
            String e = stripPort(entry);
            if (h.equals(e) || h.endsWith("." + e)) return true;
        }
        return false;
    }

    private static String stripPort(String host) {
        int last = host.lastIndexOf(':');
        // Single colon => host:port; multiple colons => IPv6 literal, leave alone.
        if (last >= 0 && host.indexOf(':') == last) return host.substring(0, last);
        return host;
    }

    private static String firstNonBlank(Map<String, String> env, String... keys) {
        for (String k : keys) {
            String v = env.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
