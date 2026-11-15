package com.coldchain.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Settings under {@code security.jwt.*}.
 *
 * @param secret key used to sign and verify tokens (HMAC-SHA256), at least 32 characters
 * @param issuer written into every token and checked when a token comes back
 * @param expiry        how long an access token (JWT) stays valid; short, because it cannot be revoked
 * @param refreshExpiry how long a refresh token stays valid
 */
@ConfigurationProperties("security.jwt")
public record JwtProperties(String secret, String issuer, Duration expiry, Duration refreshExpiry) {

    public JwtProperties {
        // HS256 needs a key of at least 256 bits; a short key would make tokens guessable.
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("security.jwt.secret (JWT_SECRET) must be at least 32 characters");
        }
    }

    public SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
