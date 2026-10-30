package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.services.PricingService.FinalBill;
import com.scooterrentalkandy.spring.services.PricingService.GearSelection;
import com.scooterrentalkandy.spring.services.PricingService.InitialCharge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Decision E2 pricing, by duration. Scooter: LKR 2,500 per complete day; a remainder of up to 8 hours at LKR 250 per
 * started hour, more than 8 hours as another day. Gear: per complete day, a remainder of up to 12 hours included,
 * more than 12 hours another day, minimum one day. Durations are exact; "+1s" is one second past the boundary.
 */
class PricingServiceTests {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 9, 0);

    private final PricingService pricing = new PricingService();

    private static Scooter scooter() {
        Scooter s = new Scooter();
        s.setCode("SRK-T");
        s.setModel("Test Dio");
        s.setPlateNumber("CP TST-0001");
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return s;
    }

    private static GearItem tent() {
        GearItem g = new GearItem();
        g.setGearItemId(UUID.randomUUID());
        g.setName("2-person tent");
        g.setDailyRate(new BigDecimal("1500.00"));
        return g;
    }

    /** "27h", "32h+1s", "45m" and similar. */
    private static Duration duration(String spec) {
        Duration d = Duration.ZERO;
        for (String part : spec.split("\\+")) {
            long amount = Long.parseLong(part.replaceAll("[a-z]", ""));
            d = d.plus(switch (part.replaceAll("[0-9]", "")) {
                case "h" -> Duration.ofHours(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "s" -> Duration.ofSeconds(amount);
                default -> throw new IllegalArgumentException(part);
            });
        }
        return d;
    }

    private FinalBill bill(String spec, List<GearSelection> gear) {
        return pricing.finalBill(scooter(), gear, START, START.plus(duration(spec)), BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "{0}: {1} day(s) + {2} extra hour(s) = LKR {3}")
    @CsvSource({
            "24h,     1, 0, 2500.00",
            "25h,     1, 1, 2750.00",
            "27h,     1, 3, 3250.00",
            "30h,     1, 6, 4000.00",
            "32h,     1, 8, 4500.00",
            "32h+1s,  2, 0, 5000.00",
            "33h,     2, 0, 5000.00",
            "48h,     2, 0, 5000.00",
            "48h+1s,  2, 1, 5250.00",
            "51h,     2, 3, 5750.00",
            "56h,     2, 8, 7000.00",
            "56h+1s,  3, 0, 7500.00",
            "72h,     3, 0, 7500.00",
            "75h,     3, 3, 8250.00",
            "80h+1s,  4, 0, 10000.00",
    })
    void chargesScooterTimeByDaysAndExtraHours(String spec, long days, long extraHours, String cost) {
        FinalBill b = bill(spec, List.of());
        assertThat(b.days()).isEqualTo(days);
        assertThat(b.extraHours()).isEqualTo(extraHours);
        assertThat(b.timeCost()).isEqualByComparingTo(cost);
        assertThat(b.dayCost()).isEqualByComparingTo(new BigDecimal("2500").multiply(BigDecimal.valueOf(days)));
        assertThat(b.extraHourCost()).isEqualByComparingTo(new BigDecimal("250").multiply(BigDecimal.valueOf(extraHours)));
    }

    @ParameterizedTest(name = "{0}: {1} day(s) + {2} extra hour(s)")
    @CsvSource({
            "1h,      0, 1",
            "45m,     0, 1",
            "3h,      0, 3",
            "2h+1s,   0, 3",
            "7h+59m,  0, 8",
            "8h,      0, 8",
            "8h+1s,   1, 0",
            "23h,     1, 0",
    })
    void chargesShortRentalsHourlyUpTo8HoursThenAsADay(String spec, long days, long extraHours) {
        FinalBill b = bill(spec, List.of());
        assertThat(b.days()).isEqualTo(days);
        assertThat(b.extraHours()).isEqualTo(extraHours);
    }

    @Test
    void neverChargesLessThanTheOneHourMinimum() {
        FinalBill zero = pricing.finalBill(scooter(), List.of(), START, START, BigDecimal.ZERO);
        FinalBill negative = pricing.finalBill(scooter(), List.of(), START, START.minusMinutes(5), BigDecimal.ZERO);
        assertThat(zero.timeCost()).isEqualByComparingTo("250.00");
        assertThat(negative.timeCost()).isEqualByComparingTo("250.00");
    }

    @Test
    void neverChargesLessForALongerRental() {
        BigDecimal previous = BigDecimal.ZERO;
        for (long minutes = 1; minutes <= 5 * 24 * 60; minutes++) {
            BigDecimal cost = bill(minutes + "m", List.of()).timeCost();
            assertThat(cost).as(minutes + " minutes").isGreaterThanOrEqualTo(previous);
            previous = cost;
        }
    }

    @ParameterizedTest(name = "gear {0}: {1} day(s)")
    @CsvSource({
            "1h,      1",
            "12h,     1",
            "24h,     1",
            "27h,     1",
            "36h,     1",
            "36h+1s,  2",
            "37h,     2",
            "48h,     2",
            "60h,     2",
            "60h+1s,  3",
            "61h,     3",
            "72h,     3",
    })
    void chargesGearByDaysWithTwelveHoursIncluded(String spec, int days) {
        GearSelection twoTents = new GearSelection(tent(), 2);
        FinalBill b = bill(spec, List.of(twoTents));
        assertThat(b.gearDays()).isEqualTo(days);
        assertThat(b.gear()).singleElement().satisfies(line -> {
            assertThat(line.days()).isEqualTo(days);
            assertThat(line.lineTotal()).isEqualByComparingTo(new BigDecimal("3000").multiply(BigDecimal.valueOf(days)));
        });
    }

    @Test
    void addsExcessDistanceAndGearToTheTimeCharge() {
        // 27 hours: 1 charged day + 3 extra hours, 120 km included; 150.004 km -> 30.00 km excess at 15.
        FinalBill b = pricing.finalBill(scooter(), List.of(new GearSelection(tent(), 1)), START,
                START.plus(duration("27h")), new BigDecimal("150.004"));
        assertThat(b.timeCost()).isEqualByComparingTo("3250.00");
        assertThat(b.distanceKm()).isEqualByComparingTo("150.00");
        assertThat(b.includedKm()).isEqualByComparingTo("120.00");
        assertThat(b.excessKm()).isEqualByComparingTo("30.00");
        assertThat(b.distanceCost()).isEqualByComparingTo("450.00");
        assertThat(b.gearCost()).isEqualByComparingTo("1500.00");
        assertThat(b.total()).isEqualByComparingTo("5200.00");
        assertThat(b.hours()).isEqualTo(27);
    }

    /** At least 120 km per rental, 120 km more per further charged day; only the excess is charged (LKR 15/km). */
    @ParameterizedTest(name = "{0} with {1} km: {2} km included, {3} km excess, LKR {4}")
    @CsvSource({
            "6h,      100,    120.00, 0.00,  0.00",
            "6h,      120,    120.00, 0.00,  0.00",
            "6h,      125,    120.00, 5.00,  75.00",
            "24h,     119,    120.00, 0.00,  0.00",
            "24h,     120,    120.00, 0.00,  0.00",
            "24h,     120.01, 120.00, 0.01,  0.15",
            "24h,     125,    120.00, 5.00,  75.00",
            "27h,     125,    120.00, 5.00,  75.00",
            "32h,     125,    120.00, 5.00,  75.00",
            "32h+1s,  125,    240.00, 0.00,  0.00",
            "48h,     240,    240.00, 0.00,  0.00",
            "48h,     250,    240.00, 10.00, 150.00",
            "51h,     250,    240.00, 10.00, 150.00",
            "56h,     250,    240.00, 10.00, 150.00",
            "56h+1s,  250,    360.00, 0.00,  0.00",
            "56h+1s,  365,    360.00, 5.00,  75.00",
    })
    void chargesOnlyTheDistanceAboveTheAllowance(String spec, String km, String included, String excess, String cost) {
        FinalBill b = pricing.finalBill(scooter(), List.of(), START, START.plus(duration(spec)), new BigDecimal(km));
        assertThat(b.includedKm()).isEqualByComparingTo(included);
        assertThat(b.excessKm()).isEqualByComparingTo(excess);
        assertThat(b.distanceCost()).isEqualByComparingTo(cost);
        assertThat(b.total()).isEqualByComparingTo(b.timeCost().add(b.distanceCost()));
    }

    @Test
    void theAllowanceIsAtLeastOneDayAndFollowsTheChargedDays() {
        assertThat(PricingService.distance(0, new BigDecimal("50")).includedKm()).isEqualByComparingTo("120.00");
        assertThat(PricingService.distance(1, new BigDecimal("50")).includedKm()).isEqualByComparingTo("120.00");
        assertThat(PricingService.distance(3, new BigDecimal("50")).includedKm()).isEqualByComparingTo("360.00");
    }

    @Test
    void theInitialChargeIsTheOneHourMinimumPlusOneDayOfGear() {
        InitialCharge c = pricing.initialCharge(scooter(), List.of(new GearSelection(tent(), 2)));
        assertThat(c.scooterCost()).isEqualByComparingTo("250.00");
        assertThat(c.gearCost()).isEqualByComparingTo("3000.00");
        assertThat(c.total()).isEqualByComparingTo("3250.00");
        assertThat(c.dailyRate()).isEqualByComparingTo("2500.00");
        assertThat(c.hourlyRate()).isEqualByComparingTo("250.00");
    }

    @Test
    void theInitialChargeIsWhatTheShortestRentalCosts() {
        InitialCharge c = pricing.initialCharge(scooter(), List.of());
        assertThat(c.total()).isEqualByComparingTo(bill("1h", List.of()).total());
    }

    @Test
    void reportsStartedHoursForDisplay() {
        assertThat(PricingService.billableHours(START, START.plus(duration("27h+1s")))).isEqualTo(28);
        assertThat(PricingService.billableHours(START, START)).isEqualTo(1);
    }
}
