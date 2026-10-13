package com.scooterrentalkandy.spring.entity;


import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "payment_id", nullable = false, updatable = false)
  private UUID paymentId;

  /** One payment per booking — enforced by UNIQUE constraint. */
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "booking_id", nullable = false, unique = true)
  private Booking booking;


  /** Total amount charged in LKR. */
  @Column(name = "amount", nullable = false, precision = 10, scale = 2)
  private BigDecimal amount;


  @Enumerated(EnumType.STRING)
  @Column(name = "method", nullable = false, length = 20)
  private PaymentMethod method;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PaymentStatus status;

  @Column(nullable = false)
  private String provider;

  /** Stripe PaymentIntent reference ID. */
  @Column(name = "stripe_payment_id", length = 255)
  private String stripePaymentId;

  /** Saved payment method, used to charge the remaining balance when the rental ends. */
  @Column(name = "payment_method_ref")
  private String paymentMethodRef;

  /** Remaining balance still to collect after a failed completion charge; null when nothing is owed. */
  @Column(name = "balance_due", precision = 10, scale = 2)
  private BigDecimal balanceDue;

  /** Payment intent the customer is confirming to pay {@code balanceDue}, if any. */
  @Column(name = "balance_intent_id")
  private String balanceIntentId;

  @Column(name = "card_last4")
  private String cardLast4;

  @Column(name = "failure_reason")
  private String failureReason;

  /** NULL until payment is successfully processed. */
  @Column(name = "paid_at")
  private Instant paidAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
