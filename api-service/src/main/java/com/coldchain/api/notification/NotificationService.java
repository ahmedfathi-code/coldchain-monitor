package com.coldchain.api.notification;

import com.coldchain.common.event.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Decides whether an OPENED alert is notified (cooldown) and sends it. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationCooldown cooldown;
    private final AlertMessageFormatter formatter;
    private final TelegramNotifier telegram;

    public NotificationService(NotificationCooldown cooldown, AlertMessageFormatter formatter, TelegramNotifier telegram) {
        this.cooldown = cooldown;
        this.formatter = formatter;
        this.telegram = telegram;
    }

    public void notifyOpened(AlertEvent alert) {
        if (!cooldown.tryAcquire(alert)) {
            log.info("Notification for {} {} suppressed: cooldown active", alert.type(), alert.deviceId());
            return;
        }
        try {
            telegram.send(formatter.format(alert));
        } catch (RuntimeException e) {
            // Sending failed: end the cooldown so the retry (or the next alert) can notify.
            cooldown.release(alert);
            throw e;
        }
    }
}
