package com.scooterrentalkandy.spring.mappers;


import java.util.List;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.TransactionResponse;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;

public final class PaymentMapper {

    private PaymentMapper() {
    }

    public static PaymentResponse toResponse(Payment p, boolean includeBooking) {
        return toResponse(p, includeBooking, null);
    }

    /** {@code transactions}: included for a single booking, null in lists. */
    public static PaymentResponse toResponse(Payment p, boolean includeBooking, List<TransactionResponse> transactions) {
        var b = p.getBooking();
        return new PaymentResponse(p.getPaymentId(), b.getBookingId(), includeBooking ? b.getReference() : null,
                includeBooking ? b.getUser().getFullName() : null, p.getAmount(), p.getMethod(), p.getStatus(),
                p.getProvider(), p.getStripePaymentId(), p.getCardLast4(), p.getBalanceDue(), p.getFailureReason(),
                p.getPaidAt(), p.getCreatedAt(), transactions);
    }

    public static TransactionResponse toResponse(PaymentTransaction t) {
        return new TransactionResponse(t.getId(), t.getKind(), t.getProviderRef(), t.getAmount(), t.getStatus(),
                t.getFailureReason(), t.getCreatedAt());
    }
}
