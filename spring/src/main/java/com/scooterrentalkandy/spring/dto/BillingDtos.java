package com.scooterrentalkandy.spring.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
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
            List<InvoiceLineResponse> lines,
            /**
             * How the booking was first paid: CARD, CASH, QR_TRANSFER or BANK_TRANSFER; null when there is no payment
             * (a viva demo rental).
             */
            PaymentMethod paymentMethod,
            /** FINAL invoice paid in cash: the cash recorded as received at the counter, and as handed back. */
            BigDecimal cashReceived,
            BigDecimal cashRefunded,
            /** FINAL invoice paid in cash or by QR or bank transfer: money still owed back; null when none. */
            BigDecimal refundDue,
            /**
             * FINAL invoice paid in cash or by QR or bank transfer: everything recorded or approved as received, and
             * everything refunded so far (a refund still due is not included).
             */
            BigDecimal amountReceived,
            BigDecimal amountRefunded,
            /**
             * Every way money was received, in order and once each, e.g. [QR_TRANSFER, CASH] when the final balance
             * was paid in cash after a QR payment; {@code paymentMethod} stays the first. Empty when there is no
             * payment.
             */
            List<PaymentMethod> paymentMethods) {
    }

    /** What an admin recorded or approved as received, and as refunded, for a booking paid outside the provider. */
    public record CashTotals(BigDecimal received, BigDecimal refunded) {
    }
}
