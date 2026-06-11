package com.scooterrentalkandy.spring.entity;


import  com.scooterrentalkandy.spring.common.enums.PaymentMethod;
import  com.scooterrentalkandy.spring.common.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
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

  /** Stripe PaymentIntent reference ID. */
  @Column(name = "stripe_payment_id", length = 255)
  private String stripePaymentId;

  /** Total amount charged in LKR. */
  @Column(name = "amount", nullable = false, precision = 10, scale = 2)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(name = "method", nullable = false, length = 20)
  private PaymentMethod method;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PaymentStatus status;

  /** NULL until payment is successfully processed. */
  @Column(name = "paid_at")
  private Instant paidAt;
}
