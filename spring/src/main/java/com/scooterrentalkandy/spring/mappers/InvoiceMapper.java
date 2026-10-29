package com.scooterrentalkandy.spring.mappers;

import java.util.List;
import com.scooterrentalkandy.spring.dto.BillingDtos.CashTotals;
import com.scooterrentalkandy.spring.dto.BillingDtos.InvoiceLineResponse;
import com.scooterrentalkandy.spring.dto.BillingDtos.InvoiceResponse;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;

public final class InvoiceMapper {

    private InvoiceMapper() {
    }

    /**
     * {@code payment}: the booking's payment, for its status and the unpaid balance of a FINAL invoice.
     * {@code cash}: for a payment in cash, by QR payment or by bank transfer, the amounts recorded as received and
     * refunded; otherwise null. The cash fields stay for cash payments only.
     */
    public static InvoiceResponse toResponse(Invoice i, boolean withLines, Payment payment, CashTotals cash) {
        boolean finalManual = cash != null && i.getKind() == Invoice.Kind.FINAL;
        boolean finalCash = finalManual && payment.getMethod() == PaymentMethod.CASH;
        var b = i.getBooking();
        List<InvoiceLineResponse> lines = withLines
                ? i.getLines().stream()
                .map(l -> new InvoiceLineResponse(l.getDescription(), l.getQuantity(), l.getUnitPrice(),
                        l.getAmount()))
                .toList()
                : List.of();
        return new InvoiceResponse(i.getInvoiceId(), i.getInvoiceNumber(), b.getBookingId(), b.getReference(),
                b.getUser().getFullName(), b.getUser().getEmail(), i.getKind(), i.getStatus(),
                payment == null ? null : payment.getStatus(),
                payment != null && i.getKind() == Invoice.Kind.FINAL ? payment.getBalanceDue() : null,
                i.getCurrency(), i.getSubtotal(), i.getTax(), i.getTotal(), i.getIssuedAt(), lines,
                payment == null ? null : payment.getMethod(), finalCash ? cash.received() : null,
                finalCash ? cash.refunded() : null, finalManual ? payment.getRefundDue() : null,
                finalManual ? cash.received() : null, finalManual ? cash.refunded() : null);
    }
}
