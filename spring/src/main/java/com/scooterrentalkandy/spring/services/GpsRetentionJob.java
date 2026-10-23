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
 * SDS 8.3: GPS logs are kept for 6 months, then purged by a scheduled job. Logs of a rental still under way are
 * kept whatever their age (its distance is not billed yet). Runs only when scheduling is enabled.
 */
@Component
public class GpsRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(GpsRetentionJob.class);

    static final int DEFAULT_RETENTION_MONTHS = 6;

    private final GpsService gps;
    private final Clock clock;
    private final int retentionMonths;

    public GpsRetentionJob(GpsService gps, Clock clock, AppProperties props) {
        this.gps = gps;
        this.clock = clock;
        int configured = props.gps() == null ? 0 : props.gps().retentionMonths();
        this.retentionMonths = configured > 0 ? configured : DEFAULT_RETENTION_MONTHS;
    }

    /** Logs recorded before this are purged; one recorded exactly at it is kept. */
    LocalDateTime cutoff() {
        return LocalDateTime.now(clock).minusMonths(retentionMonths).truncatedTo(ChronoUnit.SECONDS);
    }

    /** 03:15 Colombo time, daily. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Colombo")
    public void purge() {
        int deleted = gps.purgeOlderThan(cutoff());
        if (deleted > 0) {
            log.info("Purged {} GPS log(s) older than {} months", deleted, retentionMonths);
        }
    }
}
