package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.services.PricingService.FinalBill;
import org.junit.jupiter.api.Test;

/**
 * The approved repair reimbursement in the final bill: total = max(0, subtotal - reimbursement), the subtotal
 * (time + excess distance + gear) unchanged, and without a reimbursement the bill exactly as before.
 */
class PricingReimbursementTests {

    private static final LocalDateTime START = LocalDateTime.of(2026, 5, 1, 9, 0);
    private final PricingService pricing = new PricingService();

    private static Scooter scooter() {
        Scooter s = new Scooter();
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return s;
    }

    /** 1 day + 3 extra hours, 140 km (20 km above the allowance): 2,500 + 750 + 300 = 3,550. */
    private FinalBill bill(String reimbursement) {
        return pricing.finalBill(scooter(), List.of(), START, START.plusHours(26).plusMinutes(30), new BigDecimal("140"),
                reimbursement == null ? null : new BigDecimal(reimbursement));
    }

    @Test
    void withoutAReimbursementTheTotalIsTheSubtotal() {
        FinalBill plain = pricing.finalBill(scooter(), List.of(), START, START.plusHours(26).plusMinutes(30),
                new BigDecimal("140"));
        assertThat(plain.subtotal()).isEqualByComparingTo("3550.00");
        assertThat(plain.total()).isEqualByComparingTo("3550.00");
        assertThat(plain.approvedReimbursement()).isEqualByComparingTo("0");
        assertThat(plain.reimbursementApplied()).isEqualByComparingTo("0");
        assertThat(bill(null).total()).isEqualByComparingTo("3550.00");
        assertThat(bill("0").total()).isEqualByComparingTo("3550.00");
    }

    @Test
    void anApprovedReimbursementIsDeductedFromTheSubtotal() {
        FinalBill b = bill("1500");
        assertThat(b.timeCost()).isEqualByComparingTo("3250.00");
        assertThat(b.distanceCost()).isEqualByComparingTo("300.00");
        assertThat(b.subtotal()).isEqualByComparingTo("3550.00");
        assertThat(b.reimbursementApplied()).isEqualByComparingTo("1500.00");
        assertThat(b.total()).isEqualByComparingTo("2050.00");
    }

    @Test
    void theTotalNeverGoesBelowZero() {
        FinalBill b = bill("5000");
        assertThat(b.approvedReimbursement()).isEqualByComparingTo("5000.00");
        assertThat(b.reimbursementApplied()).isEqualByComparingTo("3550.00");
        assertThat(b.total()).isEqualByComparingTo("0.00");
        assertThat(bill("3550").total()).isEqualByComparingTo("0.00");
    }

    @Test
    void aNegativeReimbursementIsIgnoredAndAmountsKeepTwoDecimals() {
        assertThat(bill("-100").total()).isEqualByComparingTo("3550.00");
        FinalBill b = bill("0.005");
        assertThat(b.reimbursementApplied().scale()).isEqualTo(2);
        assertThat(b.total().scale()).isEqualTo(2);
    }
}
