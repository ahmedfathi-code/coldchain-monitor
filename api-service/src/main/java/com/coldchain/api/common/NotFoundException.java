package com.coldchain.api.common;

/**
 * The resource does not exist FOR THIS USER. Also used for resources of another tenant, so
 * a caller cannot even find out that they exist (answered with HTTP 404).
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
