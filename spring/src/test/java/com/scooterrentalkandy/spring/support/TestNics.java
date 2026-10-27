package com.scooterrentalkandy.spring.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Synthetic NIC numbers for tests: always in the new 12-digit format that registration accepts (BE-035), never a
 * real person's number. The same seed gives the same NIC, so a test can register a number twice on purpose.
 */
public final class TestNics {

    private static final long TWELVE_DIGITS = 1_000_000_000_000L;

    private TestNics() {
    }

    public static String of(String seed) {
        long bits = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        return String.format("%012d", Math.floorMod(bits, TWELVE_DIGITS));
    }
}
