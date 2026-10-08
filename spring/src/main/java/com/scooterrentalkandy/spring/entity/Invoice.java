package com.scooterrentalkandy.spring.entity;


import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "invoices")
@Getter
@Setter
public class Invoice {

    public enum Kind {
        /** Issued when the rental ends: hours, distance and gear (SDS 4.3). */
        FINAL,
        /** Earlier model, kept for existing invoices: issued when a booking was paid. */
        RENTAL,
        /** Earlier model, kept for existing invoices: issued at return against the deposit. */
        SETTLEMENT
    }

    /** Earlier invoices only; for FINAL invoices the booking's payment status is the status of record. */
    public enum Status {
        /** Balance outstanding. */
        ISSUED,
        PAID,
        /** Cancelled and refunded. */
        VOID
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, unique = true)
    private String invoiceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    /** An earlier SETTLEMENT invoice may be negative (money owed back from the deposit). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<InvoiceLine> lines = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }

    public void addLine(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        InvoiceLine line = new InvoiceLine();
        line.setInvoice(this);
        line.setLineNo(lines.size());
        line.setDescription(description);
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice);
        line.setAmount(amount);
        lines.add(line);
    }
}
