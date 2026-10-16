package com.scooterrentalkandy.spring.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;

public final class ScooterDtos {

    private ScooterDtos() {
    }

    public record ScooterRequest(
            @NotBlank String code,
            @NotBlank String model,
            @NotBlank String plateNumber,
            Integer engineCc,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal hourlyRate,
            @NotNull @DecimalMin("0.00") BigDecimal perKmRate,
            @NotNull @DecimalMin("0.00") BigDecimal totalMileage,
            String imageUrl,
            String description) {
    }

    public record ScooterResponse(
            UUID id,
            String code,
            String model,
            String plateNumber,
            Integer engineCc,
            ScooterStatus status,
            BigDecimal hourlyRate,
            BigDecimal perKmRate,
            BigDecimal totalMileage,
            Double latitude,
            Double longitude,
            String imageUrl,
            String description,
            boolean deleted,
            /** Bookable right now: AVAILABLE and not held by a pending or active booking. */
            boolean available) {
    }
}

