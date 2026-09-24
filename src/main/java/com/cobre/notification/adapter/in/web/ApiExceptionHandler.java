package com.cobre.notification.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem details for every error. Framework exceptions (validation, type mismatch) are handled by the
 * base class; nothing here exposes stack traces or internal messages.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotificationEventNotFoundException.class)
    ProblemDetail handleNotFound(NotificationEventNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Notification event not found");
        problem.setProperty("code", "notification_event_not_found");
        return problem;
    }
}
