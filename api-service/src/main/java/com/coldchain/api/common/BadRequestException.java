package com.coldchain.api.common;

/** The request itself is wrong, e.g. an unknown sort field or from after to (HTTP 400). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
