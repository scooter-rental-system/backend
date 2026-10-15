package com.scooterrentalkandy.spring.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record InvoiceLineResponse(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record InvoiceResponse(
            UUID id,
            String invoiceNumber,
            UUID bookingId,
            String bookingReference,
            String customerName,
            String customerEmail,
            Invoice.Kind kind,
            /** Earlier invoices only (PAID/VOID/ISSUED); null for FINAL invoices. */
            Invoice.Status status,
            /** Status of record for the booking's money (SDS 3.2 PaymentStatus). */
            PaymentStatus paymentStatus,
            /** Unpaid remainder of this invoice, if the remaining-balance charge failed. */
            BigDecimal balanceDue,
            String currency,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal total,
            LocalDateTime issuedAt,
            List<InvoiceLineResponse> lines) {
    }
}
