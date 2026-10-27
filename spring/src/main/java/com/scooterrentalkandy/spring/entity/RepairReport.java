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
import lombok.Getter;
import lombok.Setter;

/**
 * A repair or breakdown reported during a rental (V17). The approved amount is a reimbursement record for a repair
 * the customer arranged themselves; it moves no money.
 */
@Entity
@Table(name = "repair_reports")
@Getter
@Setter
public class RepairReport {

    public enum Status { REPORTED, ASSISTANCE_ARRANGED, UNDER_REVIEW, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "repair_report_id", nullable = false, updatable = false)
    private UUID repairReportId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by", nullable = false)
    private User reportedBy;

    @Column(nullable = false, length = 2000)
    private String description;

    /** The customer arranged (and paid for) the repair themselves and may claim it back. */
    @Column(name = "self_arranged", nullable = false)
    private boolean selfArranged;

    @Column(name = "claimed_amount", precision = 10, scale = 2)
    private BigDecimal claimedAmount;

    /** Set by an admin; a record of the approved reimbursement only. */
    @Column(name = "approved_amount", precision = 10, scale = 2)
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.REPORTED;

    @Column(name = "admin_note", length = 2000)
    private String adminNote;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
