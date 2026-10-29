package com.scooterrentalkandy.spring.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * At most {@code max} events per key within a sliding {@code window}, in memory and per application instance. Entries
 * expire with their window and the map is swept when it grows, so memory stays bounded.
 */
public class SlidingWindowCounter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public SlidingWindowCounter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** Records an event for the key if it is still within the limit; false when the limit is already reached. */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        AtomicBoolean allowed = new AtomicBoolean(false);
        events.compute(key, (k, times) -> {
            Deque<Instant> list = times == null ? new ArrayDeque<>() : times;
            prune(list, now);
            if (list.size() < max) {
                list.addLast(now);
                allowed.set(true);
            }
            return list;
        });
        if (events.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
        return allowed.get();
    }

    /** Forgets every recorded event (used between automated tests). */
    public void clear() {
        events.clear();
    }

    private void sweep(Instant now) {
        for (String key : events.keySet()) {
            events.computeIfPresent(key, (k, times) -> {
                prune(times, now);
                return times.isEmpty() ? null : times;
            });
        }
    }

    private void prune(Deque<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
            times.pollFirst();
        }
    }
}
