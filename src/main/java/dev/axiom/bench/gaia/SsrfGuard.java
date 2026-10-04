package dev.axiom.bench.gaia;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for outbound HTTP fetches. Validates that a URI's destination
 * is a public internet address, not loopback, private, link-local, or
 * cloud metadata endpoints.
 *
 * <p>DNS is resolved and every returned address is checked. This does not
 * prevent DNS rebinding between validation and connect (a TOCTOU race), but
 * it blocks the common cases: direct private IPs, DNS names resolving to
 * private ranges, and well-known metadata endpoints.
 */
public final class SsrfGuard {

    private SsrfGuard() {}

    /** Well-known cloud metadata endpoints that must never be fetched. */
    private static final String[] METADATA_HOSTS = {
        "169.254.169.254",  // AWS, GCP, Azure IMDS
        "metadata.google.internal",
        "metadata.goog",
    };

    /**
     * Returns null if the URI is safe to fetch, or an error message if not.
     */
    public static String validate(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "no host in URI";
        }
        String lowerHost = host.toLowerCase();
        for (String meta : METADATA_HOSTS) {
            if (lowerHost.equals(meta)) {
                return "blocked metadata endpoint: " + host;
            }
        }
        // Resolve and check all addresses.
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "cannot resolve host: " + host;
        }
        for (InetAddress addr : addrs) {
            String reason = checkAddress(addr);
            if (reason != null) {
                return "blocked address " + addr.getHostAddress()
                    + " for host " + host + ": " + reason;
            }
        }
        return null; // safe
    }

    /** Returns null if safe, or a reason string if blocked. */
    static String checkAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()) return "loopback";
        if (addr.isLinkLocalAddress()) return "link-local";
        if (addr.isSiteLocalAddress()) return "private (RFC 1918)";
        if (addr.isMulticastAddress()) return "multicast";
        // 169.254.0.0/16 is link-local, but check explicitly for clarity
        // since it hosts cloud metadata services.
        byte[] b = addr.getAddress();
        if (b.length == 4 && (b[0] & 0xFF) == 169 && (b[1] & 0xFF) == 254) {
            return "link-local (cloud metadata range)";
        }
        // 0.0.0.0/8
        if (b.length == 4 && (b[0] & 0xFF) == 0) {
            return "unspecified/current network";
        }
        return null;
    }
}
