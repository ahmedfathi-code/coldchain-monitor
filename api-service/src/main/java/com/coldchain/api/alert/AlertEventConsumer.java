package com.coldchain.api.alert;

import com.coldchain.api.notification.NotificationService;
import com.coldchain.common.event.AlertEvent;
import com.coldchain.common.event.AlertTransition;
import com.coldchain.common.topic.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the alerts produced by the stream processor:
 * <ul>
 *     <li>OPENED: store the alert, then notify on Telegram (cooldown permitting);</li>
 *     <li>RECOVERED: resolve the stored alert automatically if it is still open.</li>
 * </ul>
 */
@Component
public class AlertEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlertEventConsumer.class);

    private final AlertService alertService;
    private final NotificationService notifications;

    public AlertEventConsumer(AlertService alertService, NotificationService notifications) {
        this.alertService = alertService;
        this.notifications = notifications;
    }

    @KafkaListener(topics = KafkaTopics.ALERTS, groupId = "api-service")
    public void onAlert(AlertEvent alert) {
        log.info("Alert {} {} for {} ({}) at {}: {}", alert.type(), alert.transition(), alert.deviceId(),
                alert.severity(), alert.detectedAt(), alert.details());
        if (alert.transition() == AlertTransition.OPENED) {
            alertService.recordOpened(alert);
            // Also for a duplicate event: if the first attempt failed to notify, this retry
            // should; if it succeeded, the cooldown suppresses a second message.
            notifications.notifyOpened(alert);
        } else {
            alertService.recordRecovered(alert);
        }
    }
}
