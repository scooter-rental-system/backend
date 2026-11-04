package com.scooterrentalkandy.spring.services;

/** The email provider refused a message or could not be reached. The message never includes the email body. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
