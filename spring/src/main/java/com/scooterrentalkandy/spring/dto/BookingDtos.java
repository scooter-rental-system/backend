package com.scooterrentalkandy.spring.dto;

import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.Payment;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record GearSelectionRequest(@NotNull UUID gearItemId, @Min(1) int quantity) {
    }

    /** A gear line priced for a number of started days. */
    public record GearLine(UUID gearItemId, String name, int quantity, BigDecimal dailyRate, int days,
                           BigDecimal lineTotal) {
    }

    public record QuoteRequest(@NotNull UUID scooterId, List<@Valid GearSelectionRequest> gear) {
    }

    /**
     * Rates and the initial charge (the one-hour minimum + 1 gear day); the final total is known only when the rental
     * ends. {@code hourlyRate} is the extra-hour rate (see PricingService).
     */
    public record Quote(
            UUID scooterId,
            String currency,
            BigDecimal dailyRate,
            BigDecimal hourlyRate,
            BigDecimal perKmRate,
            List<GearLine> gear,
            BigDecimal gearPerDay,
            BigDecimal initialCharge) {
    }

    /** SDS 4.2: {scooterId, startTime, gear}. Rentals are open-ended; there is no end time at booking. */
    public record CreateBookingRequest(
            @NotNull UUID scooterId,
            @NotNull LocalDateTime startTime,
            List<@Valid GearSelectionRequest> gear,
            @NotBlank @Size(max = 300) String pickupLocation,
            @Size(max = 2000) String notes) {
    }

    public record SignContractRequest(@NotBlank String signedName, boolean agree) {
    }

    /** Current gear rate, read from the gear item (rates are not copied onto bookings). */
    public record GearLineResponse(UUID gearItemId, String name, int quantity, BigDecimal dailyRate) {
    }

    public record CustomerSummary(UUID id, String fullName, String email, String phone) {
    }

    public record ScooterSummary(UUID id, String code, String model, String plateNumber, String imageUrl,
                                 BigDecimal dailyRate, BigDecimal hourlyRate, BigDecimal perKmRate) {
    }

    /**
     * An ended rental under the pricing rule: charged days and extra hours, and the distance allowance (120 km per
     * charged day) with the kilometres above it.
     */
    public record RentalBreakdown(long chargedDays, long extraHours, BigDecimal includedKm, BigDecimal excessKm) {
    }

    /**
     * {@code method}: CARD (payment provider), CASH (received at the rental counter and recorded by an admin), or
     * QR_TRANSFER / BANK_TRANSFER (transferred by the customer and approved by an admin from the receipt).
     * {@code refundDue}: cash, QR or bank transfer only, money still owed back to the customer; null when none.
     * {@code receipts}: the transfer receipts the customer uploaded, oldest first (empty for card and cash).
     */
    public record PaymentSummary(
            PaymentStatus status,
            BigDecimal amount,
            BigDecimal balanceDue,
            String cardLast4,
            String failureReason,
            LocalDateTime paidAt,
            PaymentMethod method,
            BigDecimal refundDue,
            List<ManualPaymentDtos.ReceiptSummary> receipts) {
    }

    /**
     * Where the booking is in its life, for display. Derived from the SDS status plus payment and times:
     * AWAITING_PAYMENT, PAYMENT_FAILED, UPCOMING (paid, start time ahead), IN_PROGRESS, BALANCE_DUE (ended,
     * remaining balance not yet collected), COMPLETED, CANCELLED, REFUNDED.
     */
    public enum Stage { AWAITING_PAYMENT, PAYMENT_FAILED, UPCOMING, IN_PROGRESS, BALANCE_DUE, COMPLETED, CANCELLED,
        REFUNDED }

    public record BookingResponse(
            UUID id,
            String reference,
            BookingStatus status,
            Stage stage,
            CustomerSummary customer,
            ScooterSummary scooter,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Long billableHours,
            /** How the ended rental is charged (null until it ends); see PricingService. */
            RentalBreakdown breakdown,
            BigDecimal distanceKm,
            BigDecimal totalCost,
            /** Balance due only: the frozen settlement (endTime and totalCost stay null until COMPLETED). */
            LocalDateTime endedAt,
            BigDecimal pendingDistanceKm,
            BigDecimal pendingTotalCost,
            BigDecimal initialCharge,
            List<GearLineResponse> gear,
            PaymentSummary payment,
            String pickupLocation,
            String dropLocation,
            String notes,
            boolean contractSigned,
            LocalDateTime cancelledAt,
            LocalDateTime createdAt) {
    }

    public record ContractResponse(
            UUID id,
            String contractNumber,
            String termsVersion,
            String termsText,
            boolean signed,
            String signedName,
            LocalDateTime signedAt) {
    }
}
