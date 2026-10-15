package com.scooterrentalkandy.spring.services;

/**
 * Delivers email/SMS to an external provider. The mock implementation only logs;
 * swap in a real provider (e.g. SMTP, an SMS gateway) by adding another bean.
 */
public interface OutboundMessageSender {

    /** @return true if the provider accepted the message. */
    boolean sendEmail(String to, String subject, String body);

    /** @return true if the provider accepted the message. */
    boolean sendSms(String to, String body);
}