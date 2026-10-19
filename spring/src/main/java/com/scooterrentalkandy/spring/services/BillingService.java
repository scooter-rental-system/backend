package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.BillingDtos.InvoiceResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.GearLine;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.InvoiceMapper;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.repository.InvoiceRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.PricingService.FinalBill;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The final invoice is issued when the rental ends (SDS 4.3 steps 13-14); payment status lives on the Payment. */
@Service
public class BillingService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final Clock clock;
    private final String currency;

    public BillingService(InvoiceRepository invoices, PaymentRepository payments, Clock clock, AppProperties props) {
        this.invoices = invoices;
        this.payments = payments;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    /** One FINAL invoice per booking: rental hours, GPS distance and gear, exactly as billed. */
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
        inv.addLine("Scooter " + s.getModel() + " (" + s.getPlateNumber() + "): rental hours",
                BigDecimal.valueOf(bill.hours()), bill.hourlyRate(), bill.timeCost());
        inv.addLine("Distance from GPS (km)", bill.distanceKm(), bill.perKmRate(), bill.distanceCost());
        for (GearLine g : bill.gear()) {
            inv.addLine(g.name() + " x" + g.quantity() + ", " + g.days() + " day" + (g.days() == 1 ? "" : "s"),
                    BigDecimal.valueOf((long) g.quantity() * g.days()), g.dailyRate(), g.lineTotal());
        }
        inv.setSubtotal(bill.total());
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
                .map(i -> InvoiceMapper.toResponse(i, true, payment)).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID invoiceId, AuthUser user) {
        Invoice inv = invoices.findWithLinesByInvoiceId(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (!user.isAdmin() && !inv.getBooking().getUser().getUserId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        return InvoiceMapper.toResponse(inv, true, payment(inv.getBooking().getBookingId()));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> recent() {
        return invoices.findTop300ByOrderByIssuedAtDesc().stream()
                .map(i -> InvoiceMapper.toResponse(i, false, payment(i.getBooking().getBookingId()))).toList();
    }

    private Payment payment(UUID bookingId) {
        return payments.findByBooking_BookingId(bookingId).orElse(null);
    }
}
