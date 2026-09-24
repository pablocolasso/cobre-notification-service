package com.cobre.notification.adapter.out.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Conditional HMAC-SHA256 over {@code timestamp + "." + body}. A missing secret means the webhook is unsigned.
 */
@Component
public class WebhookSigner {

    static final String TIMESTAMP_HEADER = "X-Cobre-Timestamp";
    static final String SIGNATURE_HEADER = "X-Cobre-Signature";

    public record Signature(String timestamp, String headerValue) {
    }

    public Optional<Signature> sign(String secret, byte[] body, Instant now) {
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        String timestamp = Long.toString(now.getEpochSecond());
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(prefix);
            mac.update(body);
            return Optional.of(new Signature(timestamp, "v1=" + HexFormat.of().formatHex(mac.doFinal())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
