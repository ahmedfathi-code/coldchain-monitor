package com.coldchain.api.common;

/** The request is valid but clashes with the current state, e.g. a duplicate id (HTTP 409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
