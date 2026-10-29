package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.AdminReceiptResponse;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.ApproveReceiptRequest;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.ChooseTransferRequest;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.ManualRefundRequest;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.RejectReceiptRequest;
import com.scooterrentalkandy.spring.entity.PaymentReceipt;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.BookingService;
import com.scooterrentalkandy.spring.services.ManualPaymentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * QR payments and bank transfers. The customer chooses the method and uploads receipts; approving, rejecting and
 * recording refunds are under /api/admin, so only ADMIN and SUPER_ADMIN can mark a transfer as received. Each
 * approval and refund names one receipt or booking and its exact amount: there is no way to mark an arbitrary payment
 * as paid.
 */
@RestController
public class ManualPaymentController {

    private final ManualPaymentService transfers;
    private final BookingService bookings;

    public ManualPaymentController(ManualPaymentService transfers, BookingService bookings) {
        this.transfers = transfers;
        this.bookings = bookings;
    }

    /** The customer will pay the initial charge by QR payment or bank transfer. */
    @PostMapping("/api/bookings/{id}/transfer")
    public BookingResponse choose(@PathVariable UUID id, @Valid @RequestBody ChooseTransferRequest req,
                                  @CurrentUser AuthUser user) {
        transfers.choose(id, req.method(), user);
        return bookings.get(id, user);
    }

    /**
     * The receipt of a transfer the customer made: an image part "receipt", the optional transaction "reference",
     * and for the final balance the "method" used (QR_TRANSFER or BANK_TRANSFER). It waits for an admin to verify it.
     */
    @PostMapping(path = "/api/bookings/{id}/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BookingResponse submitReceipt(@PathVariable UUID id,
                                         @RequestPart(name = "receipt", required = false) MultipartFile receipt,
                                         @RequestParam(name = "method", required = false) PaymentMethod method,
                                         @RequestParam(name = "reference", required = false) String reference,
                                         @CurrentUser AuthUser user) {
        transfers.submitReceipt(id, method, reference, receipt, user);
        return bookings.get(id, user);
    }

    /** One receipt image, for the booking's customer or an admin only (signed in by the default rule). */
    @GetMapping("/api/payment-receipts/{receiptId}/image")
    public ResponseEntity<byte[]> receiptImage(@PathVariable UUID receiptId, @CurrentUser AuthUser user) {
        return PaymentSettingsController.image(transfers.receiptImage(receiptId, user));
    }

    @GetMapping("/api/admin/payment-receipts")
    public List<AdminReceiptResponse> receipts(
            @RequestParam(name = "status", required = false) PaymentReceipt.Status status) {
        return transfers.list(status);
    }

    /** The money reached the business account: the initial payment or final balance is recorded as received. */
    @PostMapping("/api/admin/payment-receipts/{receiptId}/approve")
    public AdminReceiptResponse approve(@PathVariable UUID receiptId, @Valid @RequestBody ApproveReceiptRequest req,
                                        @CurrentUser AuthUser admin) {
        return transfers.approve(receiptId, req.amount(), admin);
    }

    @PostMapping("/api/admin/payment-receipts/{receiptId}/reject")
    public AdminReceiptResponse reject(@PathVariable UUID receiptId, @Valid @RequestBody RejectReceiptRequest req,
                                       @CurrentUser AuthUser admin) {
        return transfers.reject(receiptId, req.reason(), admin);
    }

    /** The refund due on a QR or bank transfer payment was sent to the customer outside the application. */
    @PostMapping("/api/admin/bookings/{id}/manual-refund")
    public BookingResponse confirmRefund(@PathVariable UUID id, @Valid @RequestBody ManualRefundRequest req,
                                         @CurrentUser AuthUser admin) {
        transfers.confirmRefund(id, req.amount(), req.reference(), req.note(), admin);
        return bookings.get(id, admin);
    }
}
