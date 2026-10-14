package com.scooterrentalkandy.spring.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "booking_gear")
@Getter
@Setter
public class BookingGear {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "booking_gear_id", nullable = false, updatable = false)
    private UUID bookingGearId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gear_item_id")
    private GearItem gearItem;

    @Column(nullable = false)
    private int quantity;
}
