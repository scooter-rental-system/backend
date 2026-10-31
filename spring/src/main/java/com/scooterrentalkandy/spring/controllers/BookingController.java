package com.scooterrentalkandy.spring.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BillingDtos.InvoiceResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.CompleteRentalRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.ContractResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.CreateBookingRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.HandoverRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.Quote;
import com.scooterrentalkandy.spring.dto.BookingDtos.QuoteRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.SignContractRequest;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentResponse;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.BillingService;
import com.scooterrentalkandy.spring.services.BookingService;
import com.scooterrentalkandy.spring.services.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

    private final BookingService bookings;
    private final PaymentService payments;
    private final BillingService billing;

    public BookingController(BookingService bookings, PaymentService payments, BillingService billing) {
        this.bookings = bookings;
        this.payments = payments;
        this.billing = billing;
    }

    @PostMapping("/api/bookings/quote")
    public Quote quote(@Valid @RequestBody QuoteRequest req) {
        return bookings.quote(req);
    }

    @PostMapping("/api/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@CurrentUser AuthUser user, @Valid @RequestBody CreateBookingRequest req) {
        return bookings.create(user.id(), req);
    }

    @GetMapping("/api/bookings")
    public List<BookingResponse> mine(@CurrentUser AuthUser user) {
        return bookings.listForCustomer(user.id());
    }

    @GetMapping("/api/bookings/{id}")
    public BookingResponse get(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.get(id, user);
    }

    @GetMapping("/api/bookings/{id}/contract")
    public ContractResponse contract(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.contract(id, user);
    }

    @PostMapping("/api/bookings/{id}/contract/sign")
    public ContractResponse sign(@PathVariable UUID id, @CurrentUser AuthUser user,
                                 @Valid @RequestBody SignContractRequest req, HttpServletRequest http) {
        return bookings.signContract(id, user, req, http.getRemoteAddr());
    }

    /**
     * Admin only (URL rule /api/admin/**): the scooter was physically handed over, with the starting odometer. The
     * rental starts only when the customer then confirms it.
     */
    @PostMapping("/api/admin/bookings/{id}/handover")
    public BookingResponse handover(@PathVariable UUID id, @CurrentUser AuthUser admin,
                                    @RequestBody(required = false) HandoverRequest req) {
        return bookings.confirmHandover(id, admin, req == null ? null : req.startOdometerKm());
    }

    /** The customer confirms the start of their own rental after the handover; the rental starts now. */
    @PostMapping("/api/bookings/{id}/start")
    public BookingResponse start(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.confirmStart(id, user);
    }

    /**
     * Ends the rental when the scooter is returned: an admin enters the ending odometer. Retrying the settlement of a
     * rental that has already ended (for example after a declined card) is open to the customer too.
     */
    @PutMapping("/api/bookings/{id}/complete")
    public BookingResponse complete(@PathVariable UUID id, @CurrentUser AuthUser user,
                                    @RequestBody(required = false) CompleteRentalRequest req) {
        return bookings.complete(id, user, req == null ? null : req.endOdometerKm());
    }

    @PostMapping("/api/bookings/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return bookings.cancel(id, user);
    }

    /** The booking's single payment (one payment per booking). */
    @GetMapping("/api/bookings/{id}/payment")
    public PaymentResponse payment(@PathVariable UUID id, @CurrentUser AuthUser user) {
        bookings.get(id, user); // ownership check
        return payments.forBooking(id).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    @GetMapping("/api/bookings/{id}/invoices")
    public List<InvoiceResponse> invoices(@PathVariable UUID id, @CurrentUser AuthUser user) {
        bookings.get(id, user); // ownership check
        return billing.forBooking(id);
    }

    @GetMapping("/api/admin/bookings")
    public List<BookingResponse> all(@RequestParam(required = false) BookingStatus status) {
        return bookings.listAll(status);
    }
}
