package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.GpsDtos.LivePosition;
import com.scooterrentalkandy.spring.dto.GpsDtos.LocationRequest;
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

/**
 * Real GPS reports in (from a scooter's tracker or the rider's own browser/device) and live tracking out. Nothing
 * here generates or extrapolates positions.
 */
@RestController
public class GpsController {

    private final GpsService gps;
    private final Validator validator;
    private final byte[] deviceKey;

    public GpsController(GpsService gps, Validator validator, AppProperties props) {
        this.gps = gps;
        this.validator = validator;
        String key = props.gps() == null ? null : props.gps().deviceApiKey();
        this.deviceKey = key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * SDS 4.3: a scooter tracker's report. Authenticated by X-Device-Key (permitted past the JWT filter in
     * SecurityConfig); with no key configured, every report is refused. The key is checked before the body.
     */
    @PostMapping("/api/gps/pings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void devicePing(@RequestHeader(name = "X-Device-Key", required = false) String key,
                           @RequestBody PingRequest req) {
        if (deviceKey == null || key == null
                || !MessageDigest.isEqual(deviceKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid device key");
        }
        Set<ConstraintViolation<PingRequest>> invalid = validator.validate(req);
        if (!invalid.isEmpty()) {
            throw ApiException.badRequest("Invalid ping: " + invalid.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining(", ")));
        }
        gps.recordFromDevice(req);
    }

    /** The rider's browser/device reporting their position during their own rental. */
    @PostMapping("/api/bookings/{id}/location")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void riderLocation(@PathVariable UUID id, @CurrentUser AuthUser user,
                              @Valid @RequestBody LocationRequest req) {
        gps.recordFromRider(id, user, req);
    }

    /** The rider (or an admin) following an active rental. */
    @GetMapping("/api/bookings/{id}/tracking")
    public Tracking bookingTracking(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return gps.bookingTracking(id, user);
    }

    @GetMapping("/api/admin/gps/live")
    public List<LivePosition> live() {
        return gps.livePositions();
    }

    @GetMapping("/api/admin/gps/scooters/{id}/trail")
    public Tracking trail(@PathVariable UUID id, @RequestParam(defaultValue = "6") int hours) {
        return gps.scooterTrail(id, Math.clamp(hours, 1, 168));
    }

    @GetMapping("/api/admin/gps/speed-violations")
    public List<SpeedViolationResponse> speedViolations(@RequestParam(defaultValue = "20") int limit) {
        return gps.recentViolations(Math.clamp(limit, 1, 100));
    }
}
