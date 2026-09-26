package com.cobre.notification.adapter.out.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cobre.notification.adapter.out.webhook.WebhookDestinationGuard.Decision;
import com.cobre.notification.config.WebhookProperties;
import com.cobre.notification.config.WebhookProperties.Ssrf;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDestinationGuardTest {

    @Test
    void rejectsLoopback() throws Exception {
        assertRejected(strict(address("127.0.0.1", 127, 0, 0, 1)), "https://127.0.0.1/hook", "blocked_address");
        assertRejected(strict(address("localhost", 127, 0, 0, 1)), "https://localhost/hook", "blocked_address");
    }

    @Test
    void rejectsPrivateRfc1918() throws Exception {
        assertRejected(strict(address("10.1.2.3", 10, 1, 2, 3)), "https://10.1.2.3/hook", "blocked_address");
        assertRejected(strict(address("192.168.1.10", 192, 168, 1, 10)), "https://192.168.1.10/hook", "blocked_address");
        assertRejected(strict(address("172.16.0.8", 172, 16, 0, 8)), "https://172.16.0.8/hook", "blocked_address");
    }

    @Test
    void rejectsLinkLocalAndMetadata() throws Exception {
        assertRejected(strict(address("169.254.1.1", 169, 254, 1, 1)), "https://169.254.1.1/hook", "blocked_address");
        assertRejected(strict(address("metadata", 169, 254, 169, 254)),
                "https://169.254.169.254/latest/meta-data", "blocked_address");
    }

    @Test
    void rejectsIpv4EmbeddedInIpv6() throws Exception {
        assertRejected(strict(ipv6(mapped(10, 0, 0, 1))), "https://[::ffff:10.0.0.1]/hook", "blocked_address");
        assertRejected(strict(ipv6(mapped(127, 0, 0, 1))), "https://[::ffff:127.0.0.1]/hook", "blocked_address");
        assertRejected(strict(ipv6(compatible(127, 0, 0, 1))), "https://[::127.0.0.1]/hook", "blocked_address");
        assertRejected(strict(ipv6(mapped(169, 254, 169, 254))), "https://[::ffff:169.254.169.254]/hook",
                "blocked_address");

        WebhookDestinationGuard guard = strict(ipv6(mapped(8, 8, 8, 8)));
        assertThat(guard.evaluate("https://[::ffff:8.8.8.8]/hook")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void rejectsIpv6UniqueLocal() throws Exception {
        byte[] ula = new byte[16];
        ula[0] = (byte) 0xFD;
        ula[15] = 1;
        assertRejected(strict(InetAddress.getByAddress("internal", ula)), "https://[fd00::1]/hook", "blocked_address");
    }

    @Test
    void rejectsCarrierGradeNatAndMulticast() throws Exception {
        assertRejected(strict(address("cgnat", 100, 64, 0, 1)), "https://100.64.0.1/hook", "blocked_address");
        assertRejected(strict(address("mcast", 224, 0, 0, 1)), "https://224.0.0.1/hook", "blocked_address");
    }

    @Test
    void rejectsHttpInStrictMode() {
        assertRejected(strict(unusedResolver()), "http://hooks.example.com/hook", "https_required");
    }

    @Test
    void allowsPublicHttps() throws Exception {
        WebhookDestinationGuard guard = strict(address("hooks.example.com", 8, 8, 8, 8));

        assertThat(guard.evaluate("https://hooks.example.com/hook")).isInstanceOf(Decision.Allow.class);
        assertThat(guard.evaluate("https://hooks.example.com:8443/hook")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void allowlistedHostSkipsDnsAndIpChecks() {
        WebhookDestinationGuard guard = guard(
                new Ssrf(true, List.of("webhook-mock", "Webhook-Mock"), List.of(), List.of()),
                host -> {
                    throw new UnknownHostException(host);
                });

        assertThat(guard.evaluate("http://webhook-mock:8080/webhook/ok")).isInstanceOf(Decision.Allow.class);
        assertThat(guard.evaluate("https://WEBHOOK-MOCK/hook")).isInstanceOf(Decision.Allow.class);
        assertRejected(guard, "ftp://webhook-mock/hook", "scheme_not_allowed");
    }

    @Test
    void permissiveModeAllowsHttpAndLocalhostWithWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(WebhookDestinationGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            WebhookDestinationGuard guard = guard(new Ssrf(false, List.of(), List.of(), List.of()), unusedResolver());

            assertThat(guard.evaluate("http://localhost:8089/webhook/ok")).isInstanceOf(Decision.Allow.class);
            assertThat(guard.evaluate("https://127.0.0.1/hook")).isInstanceOf(Decision.Allow.class);

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("permissive mode");
                assertThat(event.toString()).doesNotContain("/webhook/ok");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void rejectsPrivilegedPortsUnlessConfigured() throws Exception {
        WebhookDestinationGuard guard = strict(address("hooks.example.com", 8, 8, 8, 8));
        assertRejected(guard, "https://hooks.example.com:80/hook", "port_not_allowed");

        WebhookDestinationGuard extra = guard(new Ssrf(true, List.of(), List.of(80), List.of()),
                host -> new InetAddress[] {address("hooks.example.com", 8, 8, 8, 8)});
        assertThat(extra.evaluate("https://hooks.example.com:80/hook")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsBlocked() throws Exception {
        InetAddress publicIp = address("hooks.example.com", 8, 8, 8, 8);
        InetAddress loopback = address("hooks.example.com", 127, 0, 0, 1);
        WebhookDestinationGuard guard = guard(new Ssrf(true, List.of(), List.of(), List.of()),
                host -> new InetAddress[] {publicIp, loopback});

        assertRejected(guard, "https://hooks.example.com/hook", "blocked_address");
    }

    @Test
    void extraAllowedHostsAreMergedIntoTheAllowlist() {
        WebhookDestinationGuard guard = guard(
                new Ssrf(true, List.of("webhook-mock"), List.of(), List.of("host-del-dia")),
                unusedResolver());

        assertThat(guard.evaluate("http://host-del-dia/hook")).isInstanceOf(Decision.Allow.class);
    }

    private static WebhookDestinationGuard strict(InetAddress address) {
        return strict(host -> new InetAddress[] {address});
    }

    private static WebhookDestinationGuard strict(WebhookDestinationGuard.NameResolver resolver) {
        return guard(new Ssrf(true, List.of(), List.of(), List.of()), resolver);
    }

    private static WebhookDestinationGuard guard(Ssrf ssrf, WebhookDestinationGuard.NameResolver resolver) {
        return new WebhookDestinationGuard(new WebhookProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), ssrf),
                resolver);
    }

    private static WebhookDestinationGuard.NameResolver unusedResolver() {
        return host -> {
            throw new AssertionError("DNS should not run: " + host);
        };
    }

    private static InetAddress address(String host, int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(host, new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static InetAddress ipv6(byte[] bytes) throws UnknownHostException {
        return InetAddress.getByAddress("embedded", bytes);
    }

    private static byte[] mapped(int a, int b, int c, int d) {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return bytes;
    }

    private static byte[] compatible(int a, int b, int c, int d) {
        byte[] bytes = new byte[16];
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return bytes;
    }

    private static void assertRejected(WebhookDestinationGuard guard, String url, String reason) {
        assertThat(guard.evaluate(url)).isInstanceOfSatisfying(Decision.Reject.class,
                reject -> assertThat(reject.reason()).isEqualTo(reason));
    }
}
