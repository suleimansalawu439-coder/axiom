package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class SsrfGuardTest {

    @Test
    void blocksLoopback() throws Exception {
        assertNotNull(SsrfGuard.checkAddress(InetAddress.getByName("127.0.0.1")));
        assertNotNull(SsrfGuard.checkAddress(InetAddress.getByName("::1")));
    }

    @Test
    void blocksPrivateRanges() throws Exception {
        assertNotNull(SsrfGuard.checkAddress(InetAddress.getByName("10.0.0.1")));
        assertNotNull(SsrfGuard.checkAddress(InetAddress.getByName("172.16.0.1")));
        assertNotNull(SsrfGuard.checkAddress(InetAddress.getByName("192.168.1.1")));
    }

    @Test
    void blocksLinkLocalAndMetadata() throws Exception {
        assertNotNull(SsrfGuard.checkAddress(InetAddress.getByName("169.254.169.254")));
    }

    @Test
    void blocksMetadataHostnames() {
        assertNotNull(SsrfGuard.validate(URI.create("http://169.254.169.254/")));
        assertNotNull(SsrfGuard.validate(URI.create("http://metadata.google.internal/")));
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        // 8.8.8.8 is public (Google DNS).
        assertNull(SsrfGuard.checkAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void rejectsUriWithoutHost() {
        assertNotNull(SsrfGuard.validate(URI.create("http:///no-host")));
    }
}
