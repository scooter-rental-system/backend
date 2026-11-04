package com.scooterrentalkandy.spring.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.PaymentReceipt;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * QR payments and bank transfers (BE-045). Only public payment-receiving details are ever accepted or returned: no
 * request here has a field for a password, PIN, OTP, card number or banking login.
 */
public final class ManualPaymentDtos {

    private ManualPaymentDtos() {
    }

    // ---------------------------------------------------------------- admin payment settings

    /** Everything an admin configured, enabled or not. The QR image itself is fetched separately. */
    public record PaymentSettingsResponse(
            boolean qrEnabled,
            String qrDisplayName,
            String qrInstructions,
            boolean qrImageUploaded,
            boolean bankEnabled,
            String bankName,
            String bankAccountHolder,
            String bankAccountNumber,
            String bankBranch,
            String bankBranchCode,
            String bankInstructions,
            LocalDateTime updatedAt,
            String updatedBy) {
    }

    /** Blank texts are stored as empty (null). A method can be enabled only when its required details are set. */
    public record UpdatePaymentSettingsRequest(
            boolean qrEnabled,
            @Size(max = 100) String qrDisplayName,
            @Size(max = 300) String qrInstructions,
            boolean bankEnabled,
            @Size(max = 100) String bankName,
            @Size(max = 100) String bankAccountHolder,
            @Size(max = 34) String bankAccountNumber,
            @Size(max = 100) String bankBranch,
            @Size(max = 10) String bankBranchCode,
            @Size(max = 300) String bankInstructions) {
    }

    // ---------------------------------------------------------------- what a signed-in customer sees at checkout

    public record QrOption(String displayName, String instructions) {
    }

    public record BankOption(String bankName, String accountHolder, String accountNumber, String branch,
                             String branchCode, String instructions) {
    }

    /**
     * The transfer methods currently offered: {@code qr} and {@code bank} are null when that method is disabled.
     * {@code paymentHoldMinutes}: how long an unpaid booking is held; {@code verificationHoldMinutes}: how long it
     * stays held once a receipt is waiting for verification.
     */
    public record PaymentOptionsResponse(QrOption qr, BankOption bank, int paymentHoldMinutes,
                                         int verificationHoldMinutes) {
    }

    // ---------------------------------------------------------------- receipts

    /** The customer will pay the initial charge by QR payment or bank transfer. */
    public record ChooseTransferRequest(@NotNull PaymentMethod method) {
    }

    /** A receipt as the booking's customer sees it (no reviewer details). */
    public record ReceiptSummary(
            UUID id,
            PaymentReceipt.Kind kind,
            PaymentMethod method,
            BigDecimal amount,
            String transferReference,
            PaymentReceipt.Status status,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt,
            String rejectionReason) {
    }

    /**
     * A receipt for the admin payments screen. {@code amountDue}: what the booking expects now for this step (null
     * when nothing is due any more), so an admin can see a receipt for an amount that no longer applies.
     */
    public record AdminReceiptResponse(
            UUID id,
            UUID bookingId,
            String bookingReference,
            BookingStatus bookingStatus,
            String customerName,
            String customerEmail,
            PaymentReceipt.Kind kind,
            PaymentMethod method,
            BigDecimal amount,
            BigDecimal amountDue,
            String transferReference,
            PaymentReceipt.Status status,
            LocalDateTime submittedAt,
            String reviewedBy,
            LocalDateTime reviewedAt,
            String rejectionReason) {
    }

    /** {@code amount}: the amount the admin saw on the receipt; it must be the amount due, or nothing is approved. */
    public record ApproveReceiptRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount) {
    }

    public record RejectReceiptRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /**
     * An admin records a refund they made outside the application (a bank or QR transfer back to the customer).
     * {@code amount} must be the refund due; {@code reference} and {@code note} are optional.
     */
    public record ManualRefundRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @Size(max = 100) String reference,
            @Size(max = 500) String note) {
    }
}
