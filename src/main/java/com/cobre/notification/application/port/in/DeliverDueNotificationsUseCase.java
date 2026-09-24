package com.cobre.notification.application.port.in;

public interface DeliverDueNotificationsUseCase {

    /**
     * Claims the notifications that are due and delivers them.
     *
     * @return number of notifications claimed in this run.
     */
    int deliverDueNotifications();
}
