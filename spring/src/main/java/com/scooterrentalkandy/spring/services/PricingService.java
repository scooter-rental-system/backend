package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import com.scooterrentalkandy.spring.dto.BookingDtos.GearLine;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Scooter;
import org.springframework.stereotype.Service;

/**
 * SDS 4.3 billing (decision E1): totalCost = ceil(hours) x hourlyRate + km x perKmRate + gear, where gear is
 * quantity x dailyRate x ceil(hours / 24). Minimum one hour. Rates are always read from the Scooter and gear
 * items at the moment of calculation, never copied onto the booking (SDS 2.4). No other fees or taxes.
 */
@Service
public class PricingService {

    private static final long SECONDS_PER_HOUR = 3600;

    public record GearSelection(GearItem item, int quantity) {
    }

    /** Charged when the booking is paid: one hour of scooter time plus one day of each gear item. */
    public record InitialCharge(BigDecimal hourlyRate, BigDecimal perKmRate, BigDecimal scooterCost,
                                List<GearLine> gear, BigDecimal gearCost, BigDecimal total) {
    }

    public record FinalBill(long hours, BigDecimal hourlyRate, BigDecimal timeCost, BigDecimal distanceKm,
                            BigDecimal perKmRate, BigDecimal distanceCost, int gearDays, List<GearLine> gear, BigDecimal gearCost,
                            BigDecimal total) {
    }

    /** The gear lines of a booking as selections (quantities only; rates come from the gear items). */
    public static List<GearSelection> selectionsOf(Booking b) {
        return b.getGear().stream().map(g -> new GearSelection(g.getGearItem(), g.getQuantity())).toList();
    }

    /** Whole hours, rounded up, minimum 1. */
    public static long billableHours(LocalDateTime start, LocalDateTime end) {
        long seconds = Duration.between(start, end).getSeconds();
        return seconds <= 0 ? 1 : Math.max(1, (seconds + SECONDS_PER_HOUR - 1) / SECONDS_PER_HOUR);
    }

    /** ceil(hours / 24), minimum 1. */
    public static int gearDays(long hours) {
        return (int) Math.max(1, (hours + 23) / 24);
    }

    public InitialCharge initialCharge(Scooter scooter, List<GearSelection> gear) {
        BigDecimal scooterCost = money(scooter.getHourlyRate());
        List<GearLine> lines = gearLines(gear, 1);
        BigDecimal gearCost = sum(lines);
        return new InitialCharge(scooter.getHourlyRate(), scooter.getPerKmRate(), scooterCost, lines, gearCost,
                scooterCost.add(gearCost));
    }

    public FinalBill finalBill(Scooter scooter, List<GearSelection> gear, LocalDateTime start, LocalDateTime end,
                               BigDecimal distanceKm) {
        long hours = billableHours(start, end);
        BigDecimal km = distanceKm.setScale(2, RoundingMode.HALF_UP);
        BigDecimal timeCost = money(scooter.getHourlyRate().multiply(BigDecimal.valueOf(hours)));
        BigDecimal distanceCost = money(scooter.getPerKmRate().multiply(km));
        int days = gearDays(hours);
        List<GearLine> lines = gearLines(gear, days);
        BigDecimal gearCost = sum(lines);
        return new FinalBill(hours, scooter.getHourlyRate(), timeCost, km, scooter.getPerKmRate(), distanceCost,
                days, lines, gearCost, timeCost.add(distanceCost).add(gearCost));
    }

    private static List<GearLine> gearLines(List<GearSelection> gear, int days) {
        return gear.stream()
                .map(g -> new GearLine(g.item().getGearItemId(), g.item().getName(), g.quantity(), g.item().getDailyRate(),
                        days, money(g.item().getDailyRate()
                        .multiply(BigDecimal.valueOf((long) g.quantity() * days)))))
                .toList();
    }

    private static BigDecimal sum(List<GearLine> lines) {
        return money(lines.stream().map(GearLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
