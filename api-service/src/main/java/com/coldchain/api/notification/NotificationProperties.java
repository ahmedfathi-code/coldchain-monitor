package com.coldchain.api.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings under {@code notifications.*} in application.yml.
 *
 * @param cooldown after a notification for a device and alert type, further ones for the same
 *                 device and type are suppressed for this long (anti-spam)
 * @param telegram Telegram Bot API settings
 */
@ConfigurationProperties("notifications")
public record NotificationProperties(Duration cooldown, Telegram telegram) {

    /**
     * @param apiUrl   base URL of the Bot API
     * @param botToken secret token from @BotFather (env var, never committed)
     * @param chatId   chat that receives the messages
     */
    public record Telegram(String apiUrl, String botToken, String chatId) {

        /** Without a token and a chat id we log the notification instead of sending it. */
        public boolean isConfigured() {
            return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
        }
    }
}
