package com.scooterrentalkandy.spring.dto;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReportDtos {

    private ReportDtos() {
    }

    public record DailyRevenue(LocalDate date, BigDecimal amount) {
    }

    public record ScooterPerformance(UUID scooterId, String code, String model, long bookings, long rentedHours,
                                     BigDecimal revenue) {
    }

    public record GearUsage(String name, long units) {
    }

    /**
     * {@code revenue}: payments collected (by the date they were last paid). {@code refunds}: payments refunded
     * on cancellation in the period. {@code outstandingBalance}: remaining balances not yet collected (now).
     */
    public record Summary(
            LocalDate from,
            LocalDate to,
            String currency,
            BigDecimal revenue,
            BigDecimal refunds,
            BigDecimal outstandingBalance,
            BigDecimal maintenanceCost,
            long bookingsCreated,
            Map<String, Long> bookingsByStatus,
            long newCustomers,
            double fleetUtilizationPct,
            List<DailyRevenue> revenueByDay,
            List<ScooterPerformance> scooters,
            List<GearUsage> gear) {
    }

    /**
     * {@code activeRentals}: rentals under way. {@code upcoming}: paid, start time still ahead.
     * {@code balanceDue}: ended but the remaining balance is not yet collected.
     */
    public record Dashboard(
            Map<String, Long> fleetByStatus,
            long activeRentals,
            long upcoming,
            long balanceDue,
            long pendingPayment,
            long openMaintenance,
            long customers,
            BigDecimal revenueLast30Days,
            String currency) {
    }
}