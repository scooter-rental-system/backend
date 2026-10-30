package com.scooterrentalkandy.spring.mappers;


import java.util.List;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.TransactionResponse;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;

public final class PaymentMapper {

    private PaymentMapper() {
    }

    /**
     * {@code transactions}: included for a single booking, null in lists. {@code methods}: every way money was
     * received, in order (see {@link #receivedMethods}).
     */
    public static PaymentResponse toResponse(Payment p, boolean includeBooking, List<TransactionResponse> transactions,
                                             List<PaymentMethod> methods) {
        var b = p.getBooking();
        return new PaymentResponse(p.getPaymentId(), b.getBookingId(), includeBooking ? b.getReference() : null,
                includeBooking ? b.getUser().getFullName() : null, p.getAmount(), p.getMethod(), p.getStatus(),
                p.getProvider(), p.getStripePaymentId(), p.getCardLast4(), p.getBalanceDue(), p.getFailureReason(),
                p.getPaidAt(), p.getCreatedAt(), transactions, p.getRefundDue(), methods);
    }

    /**
     * Every way money was received for a payment, in order and once each, from its transactions (oldest first): a
     * final balance paid another way than the initial charge (a QR payment, then cash at the counter) gives both, e.g.
     * [QR_TRANSFER, CASH]. Refunds are not included. With nothing received yet, the payment's chosen method.
     */
    public static List<PaymentMethod> receivedMethods(Payment p, List<PaymentTransaction> transactions) {
        List<PaymentMethod> methods = transactions.stream()
                .filter(t -> t.getStatus() == PaymentTransaction.Status.SUCCESS
                        && t.getKind() != PaymentTransaction.Kind.REFUND)
                .map(t -> t.getMethod() != null ? t.getMethod() : p.getMethod())
                .distinct().toList();
        return methods.isEmpty() ? List.of(p.getMethod()) : methods;
    }

    public static TransactionResponse toResponse(PaymentTransaction t) {
        return new TransactionResponse(t.getPaymentTransactionId(), t.getKind(), t.getProviderRef(), t.getAmount(), t.getStatus(),
                t.getFailureReason(), t.getCreatedAt(),
                t.getRecordedBy() == null ? null : t.getRecordedBy().getFullName(), t.getMethod(), t.getReference(),
                t.getNote());
    }
}
