package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.GpsDtos.LivePosition;
import com.scooterrentalkandy.spring.dto.GpsDtos.PingRequest;
import com.scooterrentalkandy.spring.dto.GpsDtos.SpeedViolationResponse;
import com.scooterrentalkandy.spring.dto.GpsDtos.Tracking;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.GpsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GpsController {

    private final GpsService gps;
    private final Validator validator;
    private final byte[] deviceKey;

    public GpsController(GpsService gps, Validator validator, AppProperties props) {
        this.gps = gps;
        this.validator = validator;
        this.deviceKey = props.gps().deviceApiKey().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Tracker devices post here with header {@code X-Device-Key}. The key is checked before the payload is
     * validated, so unauthenticated callers get 401 and learn nothing about the payload rules.
     */
    @PostMapping("/api/gps/pings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@RequestHeader(name = "X-Device-Key", required = false) String key,
                       @RequestBody PingRequest req) {
        if (key == null || !MessageDigest.isEqual(deviceKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid device key");
        }
        Set<ConstraintViolation<PingRequest>> invalid = validator.validate(req);
        if (!invalid.isEmpty()) {
            throw ApiException.badRequest("Invalid ping: " + invalid.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().collect(Collectors.joining(", ")));
        }
        gps.recordFromDevice(req);
    }

    @GetMapping("/api/bookings/{id}/tracking")
    public Tracking bookingTracking(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return gps.bookingTracking(id, user);
    }

    @GetMapping("/api/admin/gps/live")
    public List<LivePosition> live() {
        return gps.livePositions();
    }

    @GetMapping("/api/admin/gps/speed-violations")
    public List<SpeedViolationResponse> speedViolations(@RequestParam(defaultValue = "20") int limit) {
        return gps.recentViolations(Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/api/admin/gps/scooters/{id}/trail")
    public Tracking trail(@PathVariable UUID id, @RequestParam(defaultValue = "6") int hours) {
        return gps.scooterTrail(id, Math.min(Math.max(hours, 1), 168));
    }
}
