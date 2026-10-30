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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import lombok.Getter;
import lombok.Setter;

/**
 * A receipt the customer uploaded for a QR payment or bank transfer (V21), for the initial payment or the final
 * balance. It proves nothing by itself: the money counts as received only when an admin approves it.
 *
 * <p>PENDING_VERIFICATION → APPROVED (an admin checked the business account), REJECTED (with a reason; the customer
 * may upload a replacement, which is a new receipt) or EXPIRED (the booking was released or cancelled before anyone
 * verified it). Only a receipt waiting for verification can change, so a rejected or expired one is never approved
 * later, and receipts are never deleted. The image is in the payment image store under {@code storageKey}, a
 * server-generated name; the uploaded file name is never stored.
 */
@Entity
@Table(name = "payment_receipts")
@Getter
@Setter
public class PaymentReceipt {

    public enum Kind { INITIAL, BALANCE }

    public enum Status { PENDING_VERIFICATION, APPROVED, REJECTED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "payment_receipt_id", nullable = false, updatable = false)
    private UUID paymentReceiptId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    /** The amount the customer was asked to transfer when the receipt was submitted. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** The bank or app transaction reference the customer gave, if any. */
    @Column(name = "transfer_reference", length = 100)
    private String transferReference;

    @Column(name = "storage_key", nullable = false, length = 64, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 300)
    private String rejectionReason;
}
