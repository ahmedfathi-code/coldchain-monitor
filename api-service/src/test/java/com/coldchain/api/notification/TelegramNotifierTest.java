package com.coldchain.api.notification;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramNotifierTest {

    private static final String TOKEN = "123456:secret-token";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer telegramApi = MockRestServiceServer.bindTo(builder).build();

    @Test
    void postsTheMessageToTheBotApi() {
        TelegramNotifier notifier = notifier(TOKEN, "42");
        telegramApi.expect(requestTo("https://api.telegram.org/bot" + TOKEN + "/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"chat_id\":\"42\",\"text\":\"hello\"}"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        notifier.send("hello");

        telegramApi.verify();
    }

    @Test
    void onlyLogsWhenTelegramIsNotConfigured() {
        TelegramNotifier notifier = notifier("", "");

        notifier.send("hello");

        telegramApi.verify(); // no request was expected, and none was made
    }

    @Test
    void failureMessageNeverContainsTheBotToken() {
        TelegramNotifier notifier = notifier(TOKEN, "42");
        telegramApi.expect(requestTo("https://api.telegram.org/bot" + TOKEN + "/sendMessage"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> notifier.send("hello"))
                .isInstanceOf(NotificationException.class)
                .hasMessageNotContaining(TOKEN);
    }

    private TelegramNotifier notifier(String token, String chatId) {
        var properties = new NotificationProperties(Duration.ofMinutes(30),
                new NotificationProperties.Telegram("https://api.telegram.org", token, chatId));
        return new TelegramNotifier(builder, properties);
    }
}
