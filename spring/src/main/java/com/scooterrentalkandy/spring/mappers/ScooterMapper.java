package com.scooterrentalkandy.spring.mappers;

import com.scooterrentalkandy.spring.dto.ScooterDtos.ScooterResponse;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;

public final class ScooterMapper {

    private ScooterMapper() {
    }

    /** {@code held}: a pending or active booking holds the scooter, so it is not bookable right now. */
    public static ScooterResponse toResponse(Scooter s, boolean held) {
        return new ScooterResponse(s.getScooterId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getEngineCc(),
                s.getStatus(), s.getDailyRate(), s.getHourlyRate(), s.getPerKmRate(), s.getTotalMileage(), s.getLatitude(),
                s.getLongitude(), s.getImageUrl(), s.getDescription(), s.isDeleted(),
                !held && !s.isDeleted() && s.getStatus() == ScooterStatus.AVAILABLE);
    }

    /**
     * For the public catalogue: without the last-known position, which for a rented scooter is where a customer
     * is. Live positions are served only by the admin GPS endpoints and the rider's own tracking.
     */
    public static ScooterResponse toPublicResponse(Scooter s, boolean held) {
        return new ScooterResponse(s.getScooterId(), s.getCode(), s.getModel(), s.getPlateNumber(), s.getEngineCc(),
                s.getStatus(), s.getDailyRate(), s.getHourlyRate(), s.getPerKmRate(), s.getTotalMileage(), null, null,
                s.getImageUrl(), s.getDescription(), s.isDeleted(),
                !held && !s.isDeleted() && s.getStatus() == ScooterStatus.AVAILABLE);
    }
}

