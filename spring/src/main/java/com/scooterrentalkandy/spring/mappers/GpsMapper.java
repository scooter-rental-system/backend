package com.scooterrentalkandy.spring.mappers;


import com.scooterrentalkandy.spring.dto.GpsDtos.LivePosition;
import com.scooterrentalkandy.spring.dto.GpsDtos.Point;
import com.scooterrentalkandy.spring.dto.GpsDtos.SpeedViolationResponse;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GpsPing;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.SpeedViolation;

public final class GpsMapper {

    private GpsMapper() {
    }

    public static Point toPoint(GpsPing p) {
        return new Point(p.getLatitude().doubleValue(), p.getLongitude().doubleValue(), p.getSpeedKmh().doubleValue(),
                p.getRecordedAt());
    }

    public static SpeedViolationResponse toResponse(SpeedViolation v) {
        var s = v.getScooter();
        var b = v.getBooking();
        return new SpeedViolationResponse(v.getSpeedViolationId(), s.getCode(), s.getModel(), s.getPlateNumber(),
                b == null ? null : b.getReference(), b == null ? null : b.getUser().getFullName(),
                v.getSpeedKmh(), v.getLatitude(), v.getLongitude(), v.getRecordedAt());
    }

    /** {@code activeRental}: the scooter's rental under way, or null. */
    public static LivePosition toLivePosition(Scooter s, Booking activeRental, double distanceFromBaseKm,
                                              boolean stale) {
        return new LivePosition(s.getScooterId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getStatus(),
                s.getLatitude(), s.getLongitude(), s.getLastSeenAt(),
                activeRental == null ? null : activeRental.getReference(),
                activeRental == null ? null : activeRental.getUser().getFullName(), distanceFromBaseKm, stale);
    }
}
