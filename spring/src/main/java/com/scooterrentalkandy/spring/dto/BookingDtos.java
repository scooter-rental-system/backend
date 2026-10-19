package com.scooterrentalkandy.spring.dto;

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

    /** Rates and the initial charge (1 hour + 1 gear day); the final total is known only when the rental ends. */
    public record Quote(
            UUID scooterId,
            String currency,
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
                                 BigDecimal hourlyRate, BigDecimal perKmRate) {
    }

    public record PaymentSummary(
            PaymentStatus status,
            BigDecimal amount,
            BigDecimal balanceDue,
            String cardLast4,
            String failureReason,
            LocalDateTime paidAt) {
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
