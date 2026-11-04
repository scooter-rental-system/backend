package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Stripe amounts are integers in the currency's smallest unit; LKR has two decimals. */
class StripeAmountsTests {

    @Test
    void lkrHasTwoDecimals() {
        assertThat(StripeAmounts.toMinor(new BigDecimal("250.00"), "LKR")).isEqualTo(25_000L);
        assertThat(StripeAmounts.toMinor(new BigDecimal("1234.5"), "LKR")).isEqualTo(123_450L);
        assertThat(StripeAmounts.toMinor(new BigDecimal("0.01"), "lkr")).isEqualTo(1L);
        assertThat(StripeAmounts.toMinor(new BigDecimal("750"), "LKR")).isEqualTo(75_000L);
        assertThat(StripeAmounts.fromMinor(25_000L, "LKR")).isEqualTo(new BigDecimal("250.00"));
        assertThat(StripeAmounts.fromMinor(1L, "lkr")).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void anAmountWithMoreDecimalsThanTheCurrencyIsRefusedNotRounded() {
        assertThatThrownBy(() -> StripeAmounts.toMinor(new BigDecimal("250.005"), "LKR"))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> StripeAmounts.toMinor(new BigDecimal("500.5"), "JPY"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void zeroAndThreeDecimalCurrenciesFollowStripe() {
        assertThat(StripeAmounts.toMinor(new BigDecimal("500"), "JPY")).isEqualTo(500L);
        assertThat(StripeAmounts.fromMinor(500L, "JPY")).isEqualTo(new BigDecimal("500"));
        assertThat(StripeAmounts.toMinor(new BigDecimal("1.234"), "KWD")).isEqualTo(1_234L);
        assertThat(StripeAmounts.fromMinor(1_234L, "KWD")).isEqualTo(new BigDecimal("1.234"));
    }
}
