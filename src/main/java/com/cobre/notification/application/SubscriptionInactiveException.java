package com.cobre.notification.application;

public class SubscriptionInactiveException extends RuntimeException {

    public SubscriptionInactiveException() {
        super("There is no active subscription for this notification");
    }
}
