package com.cobre.notification.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Minimal webhook receiver for tests, built on the JDK HTTP server. Each path has a fixed behavior.
 */
public final class RecordingWebhookServer implements AutoCloseable {

    public record ReceivedRequest(String path, Headers headers, String body) {
    }

    private final HttpServer server;
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();

    private RecordingWebhookServer(HttpServer server) {
        this.server = server;
    }

    public static RecordingWebhookServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            RecordingWebhookServer webhookServer = new RecordingWebhookServer(server);
            webhookServer.respond("/ok", 200);
            webhookServer.respond("/error", 500);
            webhookServer.respond("/gone", 404);
            webhookServer.redirect("/redirect", "/ok");
            webhookServer.delay("/slow", Duration.ofSeconds(3));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return webhookServer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    public List<ReceivedRequest> received() {
        return received;
    }

    public List<ReceivedRequest> receivedOn(String path) {
        return received.stream().filter(request -> request.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void respond(String path, int status) {
        server.createContext(path, exchange -> {
            record(exchange);
            reply(exchange, status);
        });
    }

    private void redirect(String path, String location) {
        server.createContext(path, exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Location", url(location));
            reply(exchange, 302);
        });
    }

    private void delay(String path, Duration delay) {
        server.createContext(path, exchange -> {
            record(exchange);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reply(exchange, 200);
        });
    }

    private void record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new ReceivedRequest(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
    }

    private static void reply(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
