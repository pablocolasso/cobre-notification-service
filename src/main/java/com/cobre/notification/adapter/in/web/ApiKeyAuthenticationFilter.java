package com.cobre.notification.adapter.in.web;

import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.config.SecurityProperties;
import com.cobre.notification.config.SecurityProperties.ApiKey;
import com.cobre.notification.config.SecurityProperties.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Protects {@code /notification_events/**}. Actuator and springdoc are outside this filter's URL patterns.
 * The raw key is never logged; the configured {@code name} is the only identifier kept on the request.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-API-Key";
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String REQUESTER_ATTRIBUTE = Requester.class.getName();
    static final String CORRELATION_ID_ATTRIBUTE = "cobre.correlation_id";

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = bindCorrelationId(request);
        MDC.put("correlation_id", correlationId);
        try {
            String presented = request.getHeader(API_KEY_HEADER);
            ApiKey matched = match(presented);
            if (matched == null) {
                writeUnauthorized(response, correlationId);
                return;
            }
            Requester requester = toRequester(matched);
            request.setAttribute(REQUESTER_ATTRIBUTE, requester);
            MDC.put("caller", requester.keyName());
            if (requester instanceof Requester.Client client) {
                MDC.put("client_id", client.clientId());
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("caller");
            MDC.remove("client_id");
        }
    }

    private ApiKey match(String presented) {
        if (presented == null || presented.isBlank()) {
            return null;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        ApiKey found = null;
        for (ApiKey candidate : properties.apiKeys()) {
            byte[] expected = candidate.key().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, presentedBytes)) {
                found = candidate;
            }
        }
        return found;
    }

    private static Requester toRequester(ApiKey key) {
        return key.role() == Role.OPERATOR
                ? new Requester.Operator(key.name())
                : new Requester.Client(key.name(), key.clientId());
    }

    private static String bindCorrelationId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String correlationId = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        return correlationId;
    }

    private void writeUnauthorized(HttpServletResponse response, String correlationId) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "The API key is missing or invalid");
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "unauthorized");
        problem.setProperty("correlation_id", correlationId);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
