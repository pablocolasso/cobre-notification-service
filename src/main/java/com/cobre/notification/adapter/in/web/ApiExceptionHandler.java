package com.cobre.notification.adapter.in.web;

import com.cobre.notification.application.InvalidNotificationEventQueryException;
import com.cobre.notification.application.NotReplayableException;
import com.cobre.notification.application.NotificationEventNotFoundException;
import com.cobre.notification.application.SubscriptionInactiveException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem details. 500 and 503 never copy database or exception messages into the body.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotificationEventNotFoundException.class)
    ProblemDetail handleNotFound() {
        return problem(HttpStatus.NOT_FOUND, "Notification event not found",
                "The notification event does not exist", "notification_event_not_found");
    }

    @ExceptionHandler(NotReplayableException.class)
    ProblemDetail handleNotReplayable() {
        return problem(HttpStatus.CONFLICT, "Not replayable",
                "The notification event cannot be replayed", "not_replayable");
    }

    @ExceptionHandler(SubscriptionInactiveException.class)
    ProblemDetail handleSubscriptionInactive() {
        return problem(HttpStatus.CONFLICT, "Subscription inactive",
                "There is no active subscription for this notification", "subscription_inactive");
    }

    @ExceptionHandler({InvalidRequestException.class, InvalidNotificationEventQueryException.class})
    ProblemDetail handleInvalidRequest(RuntimeException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage(), "invalid_request");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The request is invalid", "invalid_request");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ProblemDetail handleUnavailable() {
        log.warn("Database unavailable while handling an API request");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "The service is temporarily unavailable", "service_unavailable");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled error while handling an API request", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred", "internal_error");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.equals(HttpStatus.INTERNAL_SERVER_ERROR)) {
            log.error("Framework error while handling an API request", ex);
            return super.handleExceptionInternal(ex,
                    problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                            "An unexpected error occurred", "internal_error"),
                    headers, statusCode, request);
        }
        ProblemDetail problem = body instanceof ProblemDetail existing
                ? existing
                : ProblemDetail.forStatusAndDetail(statusCode, "The request is invalid");
        if (problem.getTitle() == null) {
            problem.setTitle("Invalid request");
        }
        problem.setProperty("code", "invalid_request");
        String correlationId = currentCorrelationId();
        if (correlationId != null) {
            problem.setProperty("correlation_id", correlationId);
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        String correlationId = currentCorrelationId();
        if (correlationId != null) {
            problem.setProperty("correlation_id", correlationId);
        }
        return problem;
    }

    private static String currentCorrelationId() {
        String fromMdc = MDC.get("correlation_id");
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object value = attributes.getRequest().getAttribute(ApiKeyAuthenticationFilter.CORRELATION_ID_ATTRIBUTE);
            return value instanceof String correlationId ? correlationId : null;
        }
        return null;
    }
}
