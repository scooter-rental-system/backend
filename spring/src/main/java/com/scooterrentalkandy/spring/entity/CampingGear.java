package com.scooterrentalkandy.spring.entity;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "camping_gears")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampingGear {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "gear_id", nullable = false, updatable = false)
  private UUID gearId;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  /** Daily rental charge per item in LKR. */
  @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
  private BigDecimal dailyRate;

  /** Number of units currently available in inventory. */
  @Column(name = "stock", nullable = false)
  private Integer stock;

  // ── Relationships ──────────────────────────────────────────────────────────

  @OneToMany(mappedBy = "campingGear", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  private List<BookingGear> bookingGears;
}
