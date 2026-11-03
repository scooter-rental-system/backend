package com.scooterrentalkandy.spring.dto;


import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;

public final class GpsDtos {

    private GpsDtos() {
    }

    /** Payload a tracker device posts. Devices identify the scooter by its fleet code. */
    public record PingRequest(
            @NotBlank String scooterCode,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            /** SDS 2.2: required, 0 to 999.99 km/h. */
            @NotNull @DecimalMin("0.00") @DecimalMax("999.99") BigDecimal speedKmh,
            LocalDateTime recordedAt,
            /** Optional (SDS 4.3 payload). If sent, it must be the scooter's rental in progress. */
            UUID bookingId) {
    }

    /**
     * A location the rider's own browser/device reports during their rental. The time is the server's; the
     * scooter is the booking's. Speed is optional (browsers often have none); accuracy is checked, not stored.
     */
    public record LocationRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @DecimalMin("0.00") @DecimalMax("999.99") BigDecimal speedKmh,
            @DecimalMin("0") Double accuracyMeters) {
    }

    public record Point(double latitude, double longitude, Double speedKmh, LocalDateTime recordedAt) {
    }

    public record LivePosition(
            UUID scooterId,
            String code,
            String model,
            String plateNumber,
            ScooterStatus status,
            Double latitude,
            Double longitude,
            LocalDateTime lastSeen,
            /** The rental under way on this scooter, if any (opens its GPS history for admins). */
            UUID activeBookingId,
            String activeBookingReference,
            String riderName,
            double distanceFromBaseKm,
            /** No report in the last 2 minutes (or never): the position shown is the last real one, if any. */
            boolean stale) {
    }

    /** {@code distanceKm}: along the trail (for a booking, the rental so far). */
    /** SDS 5.2: a speed-violation alert for the admin dashboard. */
    public record SpeedViolationResponse(UUID id, String scooterCode, String scooterModel, String plateNumber,
                                         String bookingReference, String riderName, BigDecimal speedKmh, BigDecimal latitude, BigDecimal longitude,
                                         LocalDateTime recordedAt) {
    }

    /**
     * {@code distanceKm}: the tracked GPS distance along the trail, approximate and for monitoring only. The rental is
     * billed by the odometer difference, never by this.
     */
    public record Tracking(LivePosition position, List<Point> trail, BigDecimal distanceKm) {
    }

    /**
     * Admin-only: the GPS route of ONE rental, from its own stored reports in time order (kept for the normal
     * retention period). {@code start} is the actual start of the rental, {@code end} its end (null while it is still
     * under way); {@code distanceKm} is the approximate tracked GPS distance, not the billed odometer distance.
     */
    public record BookingGpsHistory(UUID bookingId, String reference, String customerName, String scooterCode,
                                    String scooterModel, String plateNumber, String status, LocalDateTime start,
                                    LocalDateTime end, List<Point> points, BigDecimal distanceKm) {
    }
}

