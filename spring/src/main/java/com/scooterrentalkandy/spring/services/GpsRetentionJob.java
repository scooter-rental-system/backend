package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import com.scooterrentalkandy.spring.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GPS logs are retained for 6 months, then purged by a scheduled job. Runs whether or not the GPS
 * simulator is on. Logs of a rental still under way are kept until it ends, however old. Archive (legacy_*)
 * tables are not touched.
 */
@Component
public class GpsRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(GpsRetentionJob.class);

    private final GpsService gps;
    private final Clock clock;
    private final int retentionMonths;

    public GpsRetentionJob(GpsService gps, Clock clock, AppProperties props) {
        this.gps = gps;
        this.clock = clock;
        this.retentionMonths = props.gps().retentionMonths() > 0 ? props.gps().retentionMonths() : 6;
    }

    /** The oldest recording time that is kept (whole seconds, so the boundary is exact at any DB precision). */
    public LocalDateTime cutoff() {
        return LocalDateTime.now(clock).minusMonths(retentionMonths).truncatedTo(ChronoUnit.SECONDS);
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Colombo")
    public int purge() {
        return purgeBefore(cutoff());
    }

    /** Deletes logs recorded strictly before {@code cutoff}; a log recorded exactly at it is kept. */
    public int purgeBefore(LocalDateTime cutoff) {
        int deleted = gps.purgeOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Purged {} GPS log(s) older than {} months", deleted, retentionMonths);
        }
        return deleted;
    }
}
