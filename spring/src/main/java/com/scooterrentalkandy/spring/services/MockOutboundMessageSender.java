package com.scooterrentalkandy.spring.services;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.notifications.provider", havingValue = "mock", matchIfMissing = true)
public class MockOutboundMessageSender implements OutboundMessageSender {

    private static final Logger log = LoggerFactory.getLogger(MockOutboundMessageSender.class);

    @Override
    public boolean sendEmail(String to, String subject, String body) {
        log.info("[MOCK EMAIL] to={} subject=\"{}\" body=\"{}\"", to, subject, body);
        return true;
    }

    @Override
    public boolean sendSms(String to, String body) {
        log.info("[MOCK SMS] to={} body=\"{}\"", to, body);
        return true;
    }
}
