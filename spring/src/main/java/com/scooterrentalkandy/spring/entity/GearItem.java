package com.scooterrentalkandy.spring.entity;


import com.scooterrentalkandy.spring.entity.enums.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "gear_items")
@Getter
@Setter
public class GearItem {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "gear_item_id", nullable = false, updatable = false)
  private UUID gearItemId;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Category category;

  @Column(length = 1000)
  private String description;

  @Column(name = "daily_rate", nullable = false, precision = 12, scale = 2)
  private BigDecimal dailyRate;

  @Column(name = "total_quantity", nullable = false)
  private int totalQuantity;

  @Column(nullable = false)
  private boolean active = true;
}

