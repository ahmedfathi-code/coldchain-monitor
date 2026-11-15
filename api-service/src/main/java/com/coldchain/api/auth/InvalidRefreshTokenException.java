package com.coldchain.api.auth;

/** The refresh token is unknown, expired, revoked or reused (answered with HTTP 401). */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
