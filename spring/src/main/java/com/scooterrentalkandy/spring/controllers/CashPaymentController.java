package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.CashConfirmRequest;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.BookingService;
import com.scooterrentalkandy.spring.services.CashPaymentService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cash at the rental counter. The customer only chooses cash; the confirmations are under /api/admin, so only
 * ADMIN and SUPER_ADMIN can record cash as received or handed back. Each answers with the updated booking.
 */
@RestController
public class CashPaymentController {

    private final CashPaymentService cash;
    private final BookingService bookings;

    public CashPaymentController(CashPaymentService cash, BookingService bookings) {
        this.cash = cash;
        this.bookings = bookings;
    }

    /** The customer will pay the initial charge in cash; the booking waits for an admin to record it. */
    @PostMapping("/api/bookings/{id}/cash")
    public BookingResponse choose(@PathVariable UUID id, @CurrentUser AuthUser user) {
        cash.choose(id, user);
        return bookings.get(id, user);
    }

    /** The initial cash was received; the booking becomes active. */
    @PostMapping("/api/admin/bookings/{id}/cash/initial")
    public BookingResponse confirmInitial(@PathVariable UUID id, @Valid @RequestBody CashConfirmRequest req,
                                          @CurrentUser AuthUser admin) {
        cash.confirmInitial(id, req.amount(), admin);
        return bookings.get(id, admin);
    }

    /** The remaining cash balance was received at the end of the rental; the rental completes. */
    @PostMapping("/api/admin/bookings/{id}/cash/balance")
    public BookingResponse confirmBalance(@PathVariable UUID id, @Valid @RequestBody CashConfirmRequest req,
                                          @CurrentUser AuthUser admin) {
        cash.confirmBalance(id, req.amount(), admin);
        return bookings.get(id, admin);
    }

    /** The cash owed back was handed to the customer. */
    @PostMapping("/api/admin/bookings/{id}/cash/refund")
    public BookingResponse confirmRefund(@PathVariable UUID id, @Valid @RequestBody CashConfirmRequest req,
                                         @CurrentUser AuthUser admin) {
        cash.confirmRefund(id, req.amount(), admin);
        return bookings.get(id, admin);
    }
}
