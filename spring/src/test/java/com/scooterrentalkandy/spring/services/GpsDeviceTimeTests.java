package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneId;
import com.scooterrentalkandy.spring.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** A tracker's reading time (PingRequest.recordedAt) in the server's zone, here Kandy's (UTC+05:30). */
class GpsDeviceTimeTests {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    @Test
    void aUtcReadingIsConvertedToServerTime() {
        assertThat(GpsService.deviceTime("2026-11-06T06:53:00Z", COLOMBO))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 12, 23));
        assertThat(GpsService.deviceTime("2026-11-06T06:53:00.250+00:00", COLOMBO))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 12, 23, 0, 250_000_000));
    }

    @Test
    void anyOffsetIsConvertedToServerTime() {
        assertThat(GpsService.deviceTime("2026-11-06T12:23:00+05:30", COLOMBO))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 12, 23));
        assertThat(GpsService.deviceTime("2026-11-05T22:53:00-08:00", COLOMBO))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 12, 23));
    }

    @Test
    void aTimeWithoutAnOffsetIsServerTime() {
        assertThat(GpsService.deviceTime(" 2026-11-06T12:23:00 ", COLOMBO))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 12, 23));
    }

    @Test
    void noTimeMeansTheServerTakesItsOwn() {
        assertThat(GpsService.deviceTime(null, COLOMBO)).isNull();
        assertThat(GpsService.deviceTime("  ", COLOMBO)).isNull();
    }

    @Test
    void anUnreadableTimeIs400() {
        for (String bad : new String[] {"06/11/2026 12:23", "2026-11-06", "1762411980", "2026-13-06T12:23:00Z"}) {
            assertThatThrownBy(() -> GpsService.deviceTime(bad, COLOMBO))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                    .hasMessage("recordedAt must be an ISO-8601 date-time, such as 2026-11-06T06:53:00Z");
        }
    }
}
