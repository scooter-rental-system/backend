package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.BillingDtos.CashTotals;
import com.scooterrentalkandy.spring.dto.BillingDtos.InvoiceResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.GearLine;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.InvoiceMapper;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.repository.InvoiceRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.PricingService.FinalBill;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The final invoice is issued when the rental ends (SDS 4.3 steps 13-14); payment status lives on the Payment. */
@Service
public class BillingService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final Clock clock;
    private final String currency;

    public BillingService(InvoiceRepository invoices, PaymentRepository payments,
                          PaymentTransactionRepository transactions, Clock clock, AppProperties props) {
        this.invoices = invoices;
        this.payments = payments;
        this.transactions = transactions;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    /** One FINAL invoice per booking: rental days and extra hours, GPS distance and gear, exactly as billed. */
    @Transactional
    public Invoice issueFinalInvoice(Booking b, FinalBill bill) {
        if (invoices.findFirstByBooking_BookingIdAndKind(b.getBookingId(), Invoice.Kind.FINAL).isPresent()) {
            throw new IllegalStateException("Booking " + b.getReference() + " already has a final invoice");
        }
        Invoice inv = new Invoice();
        inv.setInvoiceNumber(References.next("INV"));
        inv.setBooking(b);
        inv.setKind(Invoice.Kind.FINAL);
        inv.setCurrency(currency);
        inv.setIssuedAt(LocalDateTime.now(clock));
        var s = b.getScooter();
        String scooter = "Scooter " + s.getModel() + " (" + s.getPlateNumber() + ")";
        if (bill.days() > 0) {
            inv.addLine(scooter + ": " + bill.days() + " day" + (bill.days() == 1 ? "" : "s"),
                    BigDecimal.valueOf(bill.days()), bill.dailyRate(), bill.dayCost());
        }
        if (bill.extraHours() > 0) {
            inv.addLine(scooter + ": " + bill.extraHours() + " extra hour" + (bill.extraHours() == 1 ? "" : "s"),
                    BigDecimal.valueOf(bill.extraHours()), bill.hourlyRate(), bill.extraHourCost());
        }
        // Only the kilometres above the allowance (120 km per charged day) are charged; the line says how many.
        inv.addLine("Distance from GPS: " + bill.distanceKm().toPlainString() + " km, "
                        + bill.includedKm().toPlainString() + " km included; excess km",
                bill.excessKm(), bill.perKmRate(), bill.distanceCost());
        for (GearLine g : bill.gear()) {
            inv.addLine(g.name() + " x" + g.quantity() + ", " + g.days() + " day" + (g.days() == 1 ? "" : "s"),
                    BigDecimal.valueOf((long) g.quantity() * g.days()), g.dailyRate(), g.lineTotal());
        }
        // An approved repair reimbursement (BE-043) is a negative line; the subtotal stays the rental charge.
        if (bill.reimbursementApplied().signum() > 0) {
            boolean capped = bill.reimbursementApplied().compareTo(bill.approvedReimbursement()) < 0;
            inv.addLine("Approved repair reimbursement" + (capped ? " (" + currency + " "
                            + bill.approvedReimbursement().toPlainString() + " approved; limited to the rental subtotal)"
                            : ""),
                    BigDecimal.ONE, bill.reimbursementApplied().negate(), bill.reimbursementApplied().negate());
        }
        inv.setSubtotal(bill.subtotal());
        inv.setTax(BigDecimal.ZERO.setScale(2));
        inv.setTotal(bill.total());
        return invoices.save(inv);
    }

    /** Bookings paid under the earlier model carry a RENTAL invoice; void it when the booking is cancelled. */
    @Transactional
    public void voidEarlierRentalInvoice(UUID bookingId) {
        invoices.findFirstByBooking_BookingIdAndKind(bookingId, Invoice.Kind.RENTAL)
                .ifPresent(inv -> inv.setStatus(Invoice.Status.VOID));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> forBooking(UUID bookingId) {
        Payment payment = payment(bookingId);
        return invoices.findByBooking_BookingIdOrderByIssuedAtAsc(bookingId).stream()
                .map(i -> InvoiceMapper.toResponse(i, true, payment, cashTotals(payment))).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID invoiceId, AuthUser user) {
        Invoice inv = invoices.findWithLinesByInvoiceId(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (!user.isAdmin() && !inv.getBooking().getUser().getUserId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        Payment payment = payment(inv.getBooking().getBookingId());
        return InvoiceMapper.toResponse(inv, true, payment, cashTotals(payment));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> recent() {
        return invoices.findTop300ByOrderByIssuedAtDesc().stream()
                .map(i -> {
                    Payment payment = payment(i.getBooking().getBookingId());
                    return InvoiceMapper.toResponse(i, false, payment, cashTotals(payment));
                }).toList();
    }

    /** For a cash payment: the cash recorded as received (initial and balance) and as handed back. */
    private CashTotals cashTotals(Payment payment) {
        if (payment == null || payment.getMethod() != PaymentMethod.CASH) {
            return null;
        }
        BigDecimal received = BigDecimal.ZERO.setScale(2);
        BigDecimal refunded = BigDecimal.ZERO.setScale(2);
        for (PaymentTransaction t : transactions.findByPayment_PaymentIdOrderByCreatedAtAsc(payment.getPaymentId())) {
            if (t.getStatus() != PaymentTransaction.Status.SUCCESS) {
                continue;
            }
            if (t.getKind() == PaymentTransaction.Kind.REFUND) {
                refunded = refunded.add(t.getAmount());
            } else {
                received = received.add(t.getAmount());
            }
        }
        return new CashTotals(received, refunded);
    }

    private Payment payment(UUID bookingId) {
        return payments.findByBooking_BookingId(bookingId).orElse(null);
    }
}
