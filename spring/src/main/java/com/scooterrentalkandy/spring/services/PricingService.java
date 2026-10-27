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
 * The one pricing policy (decision E2), used for the booking quote, the initial charge at checkout and the final bill.
 * Rates are always read from the Scooter and gear items at the moment of calculation, never copied onto the booking
 * (SDS 2.4). No other fees or taxes.
 *
 * <p>Scooter time: each complete 24 hours costs the daily rate. The rest of the rental is charged at the extra-hour
 * rate for each started hour when it is 8 hours or less; when it is more than 8 hours it costs one more day instead.
 * The 8-hour limit is compared with the exact duration, so 8 hours and one second is already a day.
 *
 * <p>Gear: each complete 24 hours costs one day; a remainder of up to 12 hours is included, more than 12 hours is one
 * more day. At least one day. Gear has no hourly rate.
 *
 * <p>Distance: every rental includes the first 120 km, and each further charged day (by the scooter rule above,
 * including a remainder of more than 8 hours charged as a day) adds another 120 km; extra hours charged hourly add no
 * further allowance. Only the kilometres from the GPS log above the allowance are charged, at the scooter's per-km
 * (excess-km) rate.
 *
 * <p>Minimum rental: one hour. The initial charge taken at checkout is that minimum (one extra hour) plus one day of
 * each gear item; the balance is charged when the rental ends.
 */
@Service
public class PricingService {

    private static final long SECONDS_PER_HOUR = 3600;
    private static final long SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR;
    /** A scooter remainder of more than this is charged as a whole day. */
    static final long SCOOTER_HOURLY_LIMIT_SECONDS = 8 * SECONDS_PER_HOUR;
    /** A gear remainder of up to this is included in the days already charged. */
    static final long GEAR_GRACE_SECONDS = 12 * SECONDS_PER_HOUR;
    /** Kilometres included with each charged scooter day; only distance above the allowance is charged. */
    public static final BigDecimal INCLUDED_KM_PER_DAY = new BigDecimal("120");

    public record GearSelection(GearItem item, int quantity) {
    }

    /** How a rental's time is charged: whole days at the daily rate plus extra hours at the hourly rate. */
    public record ScooterTime(long days, long extraHours) {
    }

    /** Charged when the booking is paid: the one-hour minimum of scooter time plus one day of each gear item. */
    public record InitialCharge(BigDecimal dailyRate, BigDecimal hourlyRate, BigDecimal perKmRate,
                                BigDecimal scooterCost, List<GearLine> gear, BigDecimal gearCost, BigDecimal total) {
    }

    /**
     * The final bill. {@code distanceKm} is the whole GPS distance; {@code includedKm} = max(1, days) x 120 and
     * {@code excessKm} = max(0, distance - included) is what {@code perKmRate} applies to.
     */
    public record FinalBill(long hours, long days, BigDecimal dailyRate, BigDecimal dayCost, long extraHours,
                            BigDecimal hourlyRate, BigDecimal extraHourCost, BigDecimal timeCost,
                            BigDecimal distanceKm, BigDecimal includedKm, BigDecimal excessKm, BigDecimal perKmRate,
                            BigDecimal distanceCost, int gearDays, List<GearLine> gear, BigDecimal gearCost,
                            BigDecimal total) {
    }

    /** The distance allowance and the chargeable excess for a rental charged {@code days} scooter days. */
    public record Distance(BigDecimal totalKm, BigDecimal includedKm, BigDecimal excessKm) {
    }

    /**
     * Every rental gets at least the first 120 km, even when its time is charged hourly only; each further charged
     * day adds another 120 km: included = max(1, charged days) x 120.
     */
    public static Distance distance(long days, BigDecimal distanceKm) {
        BigDecimal km = distanceKm.setScale(2, RoundingMode.HALF_UP);
        long allowanceDays = Math.max(1, days);
        BigDecimal included = INCLUDED_KM_PER_DAY.multiply(BigDecimal.valueOf(allowanceDays))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal excess = km.subtract(included).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new Distance(km, included, excess);
    }

    /** The gear lines of a booking as selections (quantities only; rates come from the gear items). */
    public static List<GearSelection> selectionsOf(Booking b) {
        return b.getGear().stream().map(g -> new GearSelection(g.getGearItem(), g.getQuantity())).toList();
    }

    /** The rental length in seconds, at least one hour (a zero or negative duration is the one-hour minimum). */
    private static long seconds(LocalDateTime start, LocalDateTime end) {
        long seconds = Duration.between(start, end).getSeconds();
        return seconds <= 0 ? SECONDS_PER_HOUR : seconds;
    }

    /** The rental length in started hours, minimum 1 (shown on bookings, reports and notifications). */
    public static long billableHours(LocalDateTime start, LocalDateTime end) {
        return Math.max(1, ceilDiv(seconds(start, end), SECONDS_PER_HOUR));
    }

    /** Whole days and extra started hours for a rental of this length (scooter rule, 8-hour limit). */
    public static ScooterTime scooterTime(long seconds) {
        long s = Math.max(seconds, 1);
        long days = s / SECONDS_PER_DAY;
        long rest = s % SECONDS_PER_DAY;
        if (rest > SCOOTER_HOURLY_LIMIT_SECONDS) {
            return new ScooterTime(days + 1, 0);
        }
        return new ScooterTime(days, ceilDiv(rest, SECONDS_PER_HOUR));
    }

    /** Whole days and extra started hours for a rental from {@code start} to {@code end}. */
    public static ScooterTime scooterTime(LocalDateTime start, LocalDateTime end) {
        return scooterTime(seconds(start, end));
    }

    /** Charged gear days for a rental of this length (gear rule, 12 hours included, minimum one day). */
    public static int gearDays(long seconds) {
        long s = Math.max(seconds, 1);
        long days = s / SECONDS_PER_DAY;
        if (s % SECONDS_PER_DAY > GEAR_GRACE_SECONDS) {
            days++;
        }
        return (int) Math.max(1, days);
    }

    public InitialCharge initialCharge(Scooter scooter, List<GearSelection> gear) {
        ScooterTime minimum = scooterTime(SECONDS_PER_HOUR);
        BigDecimal scooterCost = timeCost(scooter, minimum);
        List<GearLine> lines = gearLines(gear, 1);
        BigDecimal gearCost = sum(lines);
        return new InitialCharge(scooter.getDailyRate(), scooter.getHourlyRate(), scooter.getPerKmRate(), scooterCost,
                lines, gearCost, scooterCost.add(gearCost));
    }

    public FinalBill finalBill(Scooter scooter, List<GearSelection> gear, LocalDateTime start, LocalDateTime end,
                               BigDecimal distanceKm) {
        long seconds = seconds(start, end);
        ScooterTime time = scooterTime(seconds);
        BigDecimal dayCost = money(scooter.getDailyRate().multiply(BigDecimal.valueOf(time.days())));
        BigDecimal extraHourCost = money(scooter.getHourlyRate().multiply(BigDecimal.valueOf(time.extraHours())));
        BigDecimal timeCost = money(dayCost.add(extraHourCost));
        Distance d = distance(time.days(), distanceKm);
        BigDecimal distanceCost = money(scooter.getPerKmRate().multiply(d.excessKm()));
        int days = gearDays(seconds);
        List<GearLine> lines = gearLines(gear, days);
        BigDecimal gearCost = sum(lines);
        return new FinalBill(billableHours(start, end), time.days(), scooter.getDailyRate(), dayCost,
                time.extraHours(), scooter.getHourlyRate(), extraHourCost, timeCost, d.totalKm(), d.includedKm(),
                d.excessKm(), scooter.getPerKmRate(), distanceCost, days, lines, gearCost,
                timeCost.add(distanceCost).add(gearCost));
    }

    private static BigDecimal timeCost(Scooter scooter, ScooterTime time) {
        return money(scooter.getDailyRate().multiply(BigDecimal.valueOf(time.days()))
                .add(scooter.getHourlyRate().multiply(BigDecimal.valueOf(time.extraHours()))));
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

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
