package com.cobre.notification.adapter.out.audit;

import com.cobre.notification.application.port.out.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger("audit");

    @Override
    public void record(OperatorAction action) {
        log.atInfo()
                .setMessage("Operator action")
                .addKeyValue("audit_event", "operator_action")
                .addKeyValue("operator", action.operator())
                .addKeyValue("action", action.action().name())
                .addKeyValue("notification_event_id", action.notificationEventId())
                .addKeyValue("client_id", action.clientId())
                .addKeyValue("outcome", action.outcome())
                .addKeyValue("correlation_id", action.correlationId())
                .log();
    }
}
