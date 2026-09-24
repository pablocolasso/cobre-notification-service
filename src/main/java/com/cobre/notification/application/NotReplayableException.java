package com.cobre.notification.application;

public class NotReplayableException extends RuntimeException {

    public NotReplayableException() {
        super("The notification event cannot be replayed");
    }
}
