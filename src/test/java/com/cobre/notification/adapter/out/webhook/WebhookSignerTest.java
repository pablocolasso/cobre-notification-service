package com.cobre.notification.adapter.out.webhook;

import com.cobre.notification.adapter.out.webhook.WebhookSigner.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class WebhookSignerTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final String SECRET = "super-secret-value";

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void signsTimestampAndBodyWithHmacSha256() throws Exception {
        byte[] body = "{\"event_id\":\"EVT-1\"}".getBytes(StandardCharsets.UTF_8);

        Optional<Signature> signed = signer.sign(SECRET, body, NOW);

        String timestamp = Long.toString(NOW.getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        mac.update(body);
        String expected = "v1=" + HexFormat.of().formatHex(mac.doFinal());

        assertThat(signed).hasValue(new Signature(timestamp, expected));
    }

    @Test
    void omitsHeadersWhenSecretIsMissing() {
        assertThat(signer.sign(null, "body".getBytes(StandardCharsets.UTF_8), NOW)).isEmpty();
        assertThat(signer.sign("  ", "body".getBytes(StandardCharsets.UTF_8), NOW)).isEmpty();
    }

    @Test
    void secretDoesNotAppearInSignatureOrLogs(CapturedOutput output) {
        Signature signature = signer.sign(SECRET, "payload".getBytes(StandardCharsets.UTF_8), NOW).orElseThrow();

        assertThat(signature.timestamp()).isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(signature.headerValue()).startsWith("v1=");
        assertThat(signature.toString()).doesNotContain(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }
}
