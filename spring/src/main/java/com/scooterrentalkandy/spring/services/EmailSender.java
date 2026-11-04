package com.scooterrentalkandy.spring.services;

/**
 * Delivers a plain-text email through a real provider. There is no stand-in implementation: with no provider
 * configured there is no EmailSender bean and email is simply not sent (or recorded).
 */
public interface EmailSender {

    /**
     * Returns only once the provider has accepted the message.
     *
     * @throws EmailDeliveryException if the provider refused the message or could not be reached
     */
    void send(String to, String subject, String body);
}
