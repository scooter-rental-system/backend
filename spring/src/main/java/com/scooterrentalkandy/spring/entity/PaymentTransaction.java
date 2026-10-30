package com.scooterrentalkandy.spring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import lombok.Getter;
import lombok.Setter;

/**
 * One call to the payment provider for a booking's payment, with the provider's reference (audit trail); or one amount
 * an admin recorded outside the provider (cash at the counter, an approved QR payment or bank transfer, or money
 * handed or transferred back), with no provider reference.
 */
@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
public class PaymentTransaction {

    public enum Kind { INITIAL, BALANCE, REFUND }

    public enum Status { PENDING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "payment_transaction_id", nullable = false, updatable = false)
    private UUID paymentTransactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    /** How this amount moved; the payment's method unless set (a final balance may be paid another manual way). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    /** Payment intent, charge or refund id from the provider. */
    @Column(name = "provider_ref")
    private String providerRef;

    /** For a refund: the payment intent it was made against. */
    @Column(name = "intent_ref")
    private String intentRef;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "failure_reason")
    private String failureReason;

    /** A manual transfer: the customer's transfer reference, or the reference of a refund an admin made. */
    @Column(length = 100)
    private String reference;

    /** An admin's note on a manual refund. */
    @Column(length = 500)
    private String note;

    /** The admin who recorded or approved a manual amount or refund; null for payment-provider transactions. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void defaultMethod() {
        if (method == null && payment != null) {
            method = payment.getMethod();
        }
    }
}
