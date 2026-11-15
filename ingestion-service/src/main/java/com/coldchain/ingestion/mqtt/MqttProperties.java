package com.coldchain.ingestion.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code mqtt.*} in application.yml (can be overridden with env vars).
 *
 * @param url         broker address, e.g. tcp://mosquitto:1883
 * @param username    service account
 * @param password    service account password
 * @param clientId    fixed client id: the broker keeps our session (and queued messages) under it
 * @param topicFilter which topics to subscribe to
 */
@ConfigurationProperties("mqtt")
public record MqttProperties(String url, String username, String password, String clientId, String topicFilter) {
}
