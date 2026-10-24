package com.scooterrentalkandy.spring.entity;

import com.scooterrentalkandy.spring.entity.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /**
     * BCrypt hashed password (cost factor 12). Plain text is never stored.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /**
     * Sri Lanka NIC number (SRS FR-001, Table 6; SDS Table 4): required and unique, matching
     * {@code nic_number VARCHAR(20) NOT NULL UNIQUE} (V11). Its format and encryption at rest are separate tasks.
     */
    @NotBlank
    @Size(max = 20)
    @Column(name = "nic_number", nullable = false, unique = true, length = 20)
    private String nicNumber;


    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "id_document_number")
    private String idDocumentNumber;

    @Column(name = "driving_license_no")
    private String drivingLicenseNo;

    private String country;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** Stripe Customer used to save the payment method for the off-session balance charge. Never in a DTO. */
    @Column(name = "stripe_customer_id", unique = true)
    private String stripeCustomerId;


    // ── Relationships ──────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Booking> bookings;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

}