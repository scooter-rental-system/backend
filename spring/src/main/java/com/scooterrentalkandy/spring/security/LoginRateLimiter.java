package com.scooterrentalkandy.spring.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * SDS 6.1: failed login attempts are limited to 5 per minute per IP address. Only failures count (a wrong password or
 * an unknown email); successful sign-ins are never counted. Once an address has 5 failures within the last minute,
 * further attempts from it are refused, even with the right password, until the oldest failure is a minute old
 * (a sliding window).
 *
 * <p>State is in memory, per application instance: behind a load balancer with several instances each enforces the
 * limit on its own. Entries expire with their window and the map is swept when it grows, so memory stays bounded.
 */
@Component
public class LoginRateLimiter {

    public static final int MAX_FAILURES = 5;
    public static final Duration WINDOW = Duration.ofMinutes(1);

    /** Above this many tracked addresses, expired entries are swept on the next failure. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Empty when the address may try to sign in; otherwise how long until it may try again. */
    public Optional<Duration> blockedFor(String address) {
        Instant now = clock.instant();
        AtomicReference<Duration> wait = new AtomicReference<>();
        failures.computeIfPresent(address, (key, times) -> {
            prune(times, now);
            if (times.size() >= MAX_FAILURES) {
                wait.set(Duration.between(now, times.peekFirst().plus(WINDOW)));
            }
            return times.isEmpty() ? null : times;
        });
        return Optional.ofNullable(wait.get());
    }

    /** Records one failed sign-in from the address. */
    public void recordFailure(String address) {
        Instant now = clock.instant();
        failures.compute(address, (key, times) -> {
            Deque<Instant> list = times == null ? new ArrayDeque<>() : times;
            prune(list, now);
            list.addLast(now);
            return list;
        });
        if (failures.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
    }

    /** Forgets every recorded failure (used between automated tests). */
    public void clear() {
        failures.clear();
    }

    private void sweep(Instant now) {
        for (String address : failures.keySet()) {
            failures.computeIfPresent(address, (key, times) -> {
                prune(times, now);
                return times.isEmpty() ? null : times;
            });
        }
    }

    /** Drops failures that are a full window old or older. */
    private static void prune(Deque<Instant> times, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
            times.pollFirst();
        }
    }
}
