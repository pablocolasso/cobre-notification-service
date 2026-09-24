package com.cobre.notification.adapter.out.webhook;

import com.cobre.notification.adapter.out.webhook.WebhookDestinationGuard.Decision;
import com.cobre.notification.adapter.out.webhook.WebhookSigner.Signature;
import com.cobre.notification.application.port.out.WebhookClient;
import com.cobre.notification.config.WebhookProperties;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.InvalidDestination;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryTask;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;

/**
 * Redirects are never followed: a redirect could point the request at an internal address. HTTP/1.1 is forced so
 * cleartext requests do not carry an {@code Upgrade: h2c} attempt. The destination is checked before any I/O.
 * Error messages never include the URL, response body, event content or signing secret.
 */
@Component
public class JdkWebhookClient implements WebhookClient, AutoCloseable {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String EVENT_ID_HEADER = "X-Cobre-Event-Id";
    static final String EVENT_TYPE_HEADER = "X-Cobre-Event-Type";
    static final String DELIVERY_ATTEMPT_HEADER = "X-Cobre-Delivery-Attempt";

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final WebhookDestinationGuard destinationGuard;
    private final WebhookSigner signer;
    private final Clock clock;

    public JdkWebhookClient(WebhookProperties properties, JsonMapper jsonMapper, WebhookDestinationGuard destinationGuard,
                            WebhookSigner signer, Clock clock) {
        this.connectTimeout = properties.connectTimeout();
        this.requestTimeout = properties.requestTimeout();
        this.jsonMapper = jsonMapper;
        this.destinationGuard = destinationGuard;
        this.signer = signer;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public DeliveryResult deliver(DeliveryTask task) {
        Decision decision = destinationGuard.evaluate(task.webhookUrl());
        if (decision instanceof Decision.Reject reject) {
            return new InvalidDestination(new DeliveryError(DeliveryError.INVALID_DESTINATION, reject.reason()));
        }

        byte[] body;
        try {
            body = jsonMapper.writeValueAsBytes(WebhookPayload.from(task));
        } catch (RuntimeException e) {
            return failure(DeliveryError.INVALID_DESTINATION, "Could not serialize webhook payload");
        }

        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(task.webhookUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header(IDEMPOTENCY_KEY_HEADER, task.notificationEventId().toString())
                    .header(EVENT_ID_HEADER, task.eventId())
                    .header(EVENT_TYPE_HEADER, task.eventType())
                    .header(DELIVERY_ATTEMPT_HEADER, Integer.toString(task.attemptNumber()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } catch (IllegalArgumentException e) {
            return new InvalidDestination(new DeliveryError(DeliveryError.INVALID_DESTINATION, "malformed_url"));
        }
        signer.sign(task.signingSecret(), body, clock.instant()).ifPresent(signature -> applySignature(request, signature));

        try {
            HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return new HttpResponseReceived(response.statusCode(),
                    RetryAfter.parse(response.headers().firstValue("Retry-After").orElse(null)));
        } catch (HttpConnectTimeoutException e) {
            return failure("connect_timeout", "No connection within " + connectTimeout.toMillis() + " ms");
        } catch (HttpTimeoutException e) {
            return failure("timeout", "No response within " + requestTimeout.toMillis() + " ms");
        } catch (ConnectException e) {
            return failure("connection_failed", "Connection refused or host unreachable");
        } catch (IOException e) {
            return failure("network_error", "I/O error: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("interrupted", "Delivery interrupted");
        }
    }

    private static void applySignature(HttpRequest.Builder request, Signature signature) {
        request.header(WebhookSigner.TIMESTAMP_HEADER, signature.timestamp());
        request.header(WebhookSigner.SIGNATURE_HEADER, signature.headerValue());
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private static TransportFailure failure(String code, String message) {
        return new TransportFailure(new DeliveryError(code, message));
    }
}
