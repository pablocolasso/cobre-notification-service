package com.cobre.notification.domain.model;

public enum AttemptStatus {
    IN_PROGRESS,
    SUCCESS,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    ABANDONED
}
