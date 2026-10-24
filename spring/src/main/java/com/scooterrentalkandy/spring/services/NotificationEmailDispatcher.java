package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends a notification's email only after the transaction that created it has committed, so a rolled-back
 * booking or payment change never emails anyone and a slow or failing provider never holds or rolls back business
 * state. The outcome is recorded in its own transaction: SENT once the provider has accepted the message, FAILED
 * otherwise. Nothing escapes this listener: the business transaction has already committed.
 */
@Component
public class NotificationEmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailDispatcher.class);

    /** Published by NotificationService inside the business transaction; only when an EmailSender exists. */
    public record EmailRequested(UUID userId, String to, String subject, String message) {
    }

    private final ObjectProvider<EmailSender> sender;
    private final NotificationRepository repository;
    private final UserRepository users;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    public NotificationEmailDispatcher(ObjectProvider<EmailSender> sender, NotificationRepository repository,
                                       UserRepository users, PlatformTransactionManager transactionManager,
                                       Clock clock) {
        this.sender = sender;
        this.repository = repository;
        this.users = users;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(EmailRequested request) {
        EmailSender email = sender.getIfAvailable();
        if (email == null) {
            return;
        }
        Notification.Status status;
        try {
            email.send(request.to(), request.subject(), request.message());
            status = Notification.Status.SENT;
        } catch (RuntimeException ex) {
            // Never the address or the body: only who it was for and why. A provider's own text can quote the
            // address, so only EmailDeliveryException (whose message the sender writes) is logged in full.
            log.warn("Email to user {} was not delivered: {}", request.userId(),
                    ex instanceof EmailDeliveryException ? ex.getMessage() : ex.getClass().getSimpleName());
            status = Notification.Status.FAILED;
        }
        record(request, status);
    }

    private void record(EmailRequested request, Notification.Status status) {
        try {
            newTransaction.executeWithoutResult(tx -> {
                Notification n = new Notification();
                n.setUser(users.getReferenceById(request.userId()));
                n.setChannel(Notification.Channel.EMAIL);
                n.setSubject(request.subject());
                n.setMessage(request.message());
                n.setStatus(status);
                n.setCreatedAt(LocalDateTime.now(clock));
                repository.save(n);
            });
        } catch (RuntimeException ex) {
            log.error("Could not record the {} email delivery for user {}", status, request.userId(), ex);
        }
    }
}
