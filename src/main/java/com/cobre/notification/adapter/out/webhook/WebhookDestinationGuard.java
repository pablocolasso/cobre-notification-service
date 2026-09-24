package com.cobre.notification.adapter.out.webhook;

import com.cobre.notification.config.WebhookProperties;
import com.cobre.notification.config.WebhookProperties.Ssrf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SSRF checks before an outbound webhook call. Allowlisted hosts skip the strict IP and scheme rules so the
 * local WireMock and a future public HTTPS URL can both work. DNS rebinding (TOCTOU) is a documented limit.
 */
@Component
public class WebhookDestinationGuard {

    private static final Logger log = LoggerFactory.getLogger(WebhookDestinationGuard.class);

    @FunctionalInterface
    public interface NameResolver {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public sealed interface Decision permits Decision.Allow, Decision.Reject {

        record Allow() implements Decision {
        }

        record Reject(String reason) implements Decision {
        }
    }

    private final Ssrf ssrf;
    private final NameResolver resolver;
    private final Set<String> allowedHosts;

    public WebhookDestinationGuard(WebhookProperties properties, NameResolver resolver) {
        this.ssrf = properties.ssrf();
        this.resolver = resolver;
        this.allowedHosts = ssrf.allowedHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Decision evaluate(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return new Decision.Reject("malformed_url");
        }
        if (uri.getHost() == null || uri.getScheme() == null) {
            return new Decision.Reject("malformed_url");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts.contains(host)) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return new Decision.Reject("scheme_not_allowed");
            }
            return new Decision.Allow();
        }
        if (!ssrf.strict()) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return new Decision.Reject("scheme_not_allowed");
            }
            log.atWarn()
                    .setMessage("SSRF strict checks skipped (permissive mode)")
                    .addKeyValue("scheme", scheme)
                    .addKeyValue("host", host)
                    .log();
            return new Decision.Allow();
        }
        if (!scheme.equals("https")) {
            return new Decision.Reject("https_required");
        }
        if (!portAllowed(uri)) {
            return new Decision.Reject("port_not_allowed");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            return new Decision.Reject("unresolvable_host");
        }
        if (addresses.length == 0) {
            return new Decision.Reject("unresolvable_host");
        }
        if (Arrays.stream(addresses).anyMatch(WebhookDestinationGuard::isBlocked)) {
            return new Decision.Reject("blocked_address");
        }
        return new Decision.Allow();
    }

    private boolean portAllowed(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        if (port == 443 || port > 1023) {
            return true;
        }
        return ssrf.extraAllowedPorts().contains(port);
    }

    static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            return isCarrierGradeNat(ipv4);
        }
        if (address instanceof Inet6Address ipv6) {
            return isUniqueLocal(ipv6) || isCarrierGradeNat(ipv4Mapped(ipv6));
        }
        return false;
    }

    private static boolean isUniqueLocal(Inet6Address address) {
        int first = address.getAddress()[0] & 0xFF;
        return (first & 0xFE) == 0xFC;
    }

    private static boolean isCarrierGradeNat(Inet4Address address) {
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    private static Inet4Address ipv4Mapped(Inet6Address address) {
        if (!address.isIPv4CompatibleAddress()) {
            byte[] bytes = address.getAddress();
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (!mapped || (bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
                return null;
            }
            try {
                return (Inet4Address) InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
            } catch (UnknownHostException e) {
                return null;
            }
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(Arrays.copyOfRange(address.getAddress(), 12, 16));
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
