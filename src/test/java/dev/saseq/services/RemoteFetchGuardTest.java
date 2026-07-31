package dev.saseq.services;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteFetchGuardTest {

    @Test
    void blocksPrivateAndSpecialUseAddresses() {
        assertThat(blocked("0.0.0.0")).isTrue();
        assertThat(blocked("10.0.0.1")).isTrue();
        assertThat(blocked("127.0.0.1")).isTrue();
        assertThat(blocked("172.16.0.1")).isTrue();
        assertThat(blocked("192.168.1.1")).isTrue();
        assertThat(blocked("224.0.0.1")).isTrue();
        assertThat(blocked("255.255.255.255")).isTrue();
        // Cloud instance metadata, the payload that makes SSRF worth exploiting.
        assertThat(blocked("169.254.169.254")).isTrue();
    }

    @Test
    void blocksRangesTheInetAddressPredicatesMiss() {
        assertThat(blocked("100.64.0.1")).isTrue();   // carrier-grade NAT
        assertThat(blocked("192.0.0.1")).isTrue();    // IETF protocol assignments
        assertThat(blocked("198.18.0.1")).isTrue();   // benchmarking
        assertThat(blocked("240.0.0.1")).isTrue();    // reserved
    }

    @Test
    void allowsPublicAddressesJustOutsideTheBlockedPrefixes() {
        assertThat(blocked("8.8.8.8")).isFalse();
        assertThat(blocked("172.32.0.1")).isFalse();   // one past 172.16/12
        assertThat(blocked("100.128.0.1")).isFalse();  // one past 100.64/10
        assertThat(blocked("198.20.0.1")).isFalse();   // one past 198.18/15
    }

    @Test
    void blocksIpv6LoopbackAndUniqueLocal() {
        assertThat(blocked("::1")).isTrue();
        assertThat(blocked("fd00::1")).isTrue();  // fc00::/7, not covered by isSiteLocalAddress
        assertThat(blocked("fe80::1")).isTrue();
        assertThat(blocked("2606:4700::1")).isFalse();
    }

    @Test
    void blocksNat64WrappersAroundPrivateIpv4() {
        // 64:ff9b::/96 tunnels an IPv4 destination, so the embedded address is
        // what connect() actually reaches.
        assertThat(blocked("64:ff9b::7f00:1")).isTrue();      // 127.0.0.1
        assertThat(blocked("64:ff9b::a9fe:a9fe")).isTrue();   // 169.254.169.254
        assertThat(blocked("64:ff9b::808:808")).isFalse();    // 8.8.8.8
    }

    @Test
    void rejectsNonHttpsSchemes() {
        assertThatThrownBy(() -> RemoteFetchGuard.fetch("http://example.com/x.png", 1024, "image"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> RemoteFetchGuard.fetch("file:///etc/shadow", 1024, "file"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    /** Parses a literal address without touching DNS. */
    private boolean blocked(String literal) {
        try {
            return RemoteFetchGuard.isBlocked(InetAddress.getByName(literal));
        } catch (UnknownHostException e) {
            throw new AssertionError("not a literal address: " + literal, e);
        }
    }

    @Test
    void readingStopsOneByteAfterTheAllowanceRatherThanOneChunk() throws Exception {
        // A chunked reader that always asks for a full 8 KiB can pull maxBytes + 8191 before it
        // notices, while the caller charges only the allowance — so a byte budget spent on
        // oversized responses overshoots by a chunk per attachment.
        byte[] body = new byte[64 * 1024];
        CountingStream stream = new CountingStream(body);

        java.lang.reflect.Method readBounded = RemoteFetchGuard.class.getDeclaredMethod(
                "readBounded", java.io.InputStream.class, int.class, String.class);
        readBounded.setAccessible(true);

        assertThatThrownBy(() -> readBounded.invoke(null, stream, 1000, "attachment"))
                .hasRootCauseInstanceOf(RemoteFetchGuard.TooLargeException.class);

        // 1001, not 1000 + 8192: exactly the one byte needed to prove it is over.
        assertThat(stream.delivered).isEqualTo(1001);
    }

    /** Counts what was actually handed out, which is the thing under test. */
    private static final class CountingStream extends java.io.InputStream {
        private final byte[] data;
        private int position;
        private int delivered;

        CountingStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            if (position >= data.length) return -1;
            delivered++;
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int off, int len) {
            if (position >= data.length) return -1;
            int n = Math.min(len, data.length - position);
            System.arraycopy(data, position, target, off, n);
            position += n;
            delivered += n;
            return n;
        }
    }
}
