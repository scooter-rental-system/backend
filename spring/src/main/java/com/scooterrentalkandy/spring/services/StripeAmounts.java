package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Converts between amounts in a currency's major unit and Stripe's integer amounts in the smallest unit. Most
 * currencies, LKR included, have two decimals (LKR 250.00 is 25000). Stripe lists some currencies as
 * zero-decimal and some as three-decimal; those are listed here. An amount with more decimals than the currency
 * has is refused, never rounded.
 */
public final class StripeAmounts {

    private static final Set<String> ZERO_DECIMAL = Set.of("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");
    private static final Set<String> THREE_DECIMAL = Set.of("BHD", "JOD", "KWD", "OMR", "TND");

    private StripeAmounts() {
    }

    static int decimals(String currency) {
        String code = currency.toUpperCase(Locale.ROOT);
        return ZERO_DECIMAL.contains(code) ? 0 : THREE_DECIMAL.contains(code) ? 3 : 2;
    }

    /** Throws {@link ArithmeticException} if {@code amount} has more decimals than the currency allows. */
    public static long toMinor(BigDecimal amount, String currency) {
        return amount.movePointRight(decimals(currency)).longValueExact();
    }

    public static BigDecimal fromMinor(long amount, String currency) {
        return BigDecimal.valueOf(amount, decimals(currency));
    }
}
