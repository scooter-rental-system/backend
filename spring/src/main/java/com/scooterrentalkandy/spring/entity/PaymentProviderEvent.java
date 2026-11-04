package com.scooterrentalkandy.spring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A provider webhook event that has been processed; (provider, eventId) is unique, so it is never processed twice. */
@Entity
@Table(name = "payment_provider_events")
@Getter
@Setter
@NoArgsConstructor
public class PaymentProviderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "payment_provider_event_id", nullable = false, updatable = false)
    private UUID paymentProviderEventId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public PaymentProviderEvent(String provider, String eventId, String eventType, LocalDateTime processedAt) {
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }
}
