package com.cobre.notification.application.port.out;

public record BacklogSnapshot(long pending, long retrying, long processing, long oldestAgeSeconds) {
}
