package com.coldchain.api.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Sends messages with the Telegram Bot API ({@code POST /bot<token>/sendMessage}).
 * If no bot token / chat id is configured, the message is logged instead, so the system
 * works (and can be demonstrated) without a Telegram account.
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final NotificationProperties.Telegram settings;
    private final RestClient restClient;

    public TelegramNotifier(RestClient.Builder restClientBuilder, NotificationProperties properties) {
        this.settings = properties.telegram();
        this.restClient = restClientBuilder.baseUrl(settings.apiUrl()).build();
    }

    public void send(String text) {
        if (!settings.isConfigured()) {
            log.info("Telegram is not configured (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID), notification:\n{}", text);
            return;
        }
        try {
            restClient.post()
                    // Not a {token} URI variable: variables get percent-encoded, which would
                    // turn the ":" of every bot token into "%3A".
                    .uri("/bot" + settings.botToken() + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", settings.chatId(), "text", text))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram notification sent to chat {}", settings.chatId());
        } catch (RestClientException e) {
            // The token is part of the URL, and some exception messages contain the URL:
            // never let the secret reach the logs.
            String safeMessage = String.valueOf(e.getMessage()).replace(settings.botToken(), "***");
            throw new NotificationException("Could not send Telegram notification: " + safeMessage);
        }
    }
}
