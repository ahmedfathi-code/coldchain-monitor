package com.coldchain.api.notification;

/** A notification could not be delivered. */
public class NotificationException extends RuntimeException {

    public NotificationException(String message) {
        super(message);
    }
}
