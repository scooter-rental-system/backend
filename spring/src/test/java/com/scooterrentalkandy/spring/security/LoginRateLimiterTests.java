package com.scooterrentalkandy.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** BE-035 (SDS 6.1): at most 5 failed logins per minute per address, as a sliding window. */
class LoginRateLimiterTests {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-10-26T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ManualClock clock = new ManualClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    private void fail(String address, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(address);
        }
    }

    @Test
    void allowsUpToFiveFailuresAndBlocksAfterwards() {
        fail("203.0.113.5", 4);
        assertThat(limiter.blockedFor("203.0.113.5")).isEmpty();
        fail("203.0.113.5", 1);
        assertThat(limiter.blockedFor("203.0.113.5")).contains(Duration.ofMinutes(1));
    }

    @Test
    void countsEachAddressSeparately() {
        fail("203.0.113.6", LoginRateLimiter.MAX_FAILURES);
        assertThat(limiter.blockedFor("203.0.113.6")).isPresent();
        assertThat(limiter.blockedFor("203.0.113.7")).isEmpty();
    }

    @Test
    void unblocksOnceTheOldestFailureIsAMinuteOld() {
        fail("203.0.113.8", 1);
        clock.advance(Duration.ofSeconds(20));
        fail("203.0.113.8", 4);
        assertThat(limiter.blockedFor("203.0.113.8")).contains(Duration.ofSeconds(40));

        clock.advance(Duration.ofSeconds(39));
        assertThat(limiter.blockedFor("203.0.113.8")).contains(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        // The first failure has left the window: four remain, so one more attempt is allowed.
        assertThat(limiter.blockedFor("203.0.113.8")).isEmpty();
        fail("203.0.113.8", 1);
        assertThat(limiter.blockedFor("203.0.113.8")).isPresent();
    }

    @Test
    void failuresSpreadOverMoreThanAMinuteNeverBlock() {
        for (int i = 0; i < 20; i++) {
            fail("203.0.113.9", 1);
            assertThat(limiter.blockedFor("203.0.113.9")).isEmpty();
            clock.advance(Duration.ofSeconds(15));
        }
    }

    @Test
    void clearForgetsEverything() {
        fail("203.0.113.10", LoginRateLimiter.MAX_FAILURES);
        limiter.clear();
        assertThat(limiter.blockedFor("203.0.113.10")).isEmpty();
    }
}
