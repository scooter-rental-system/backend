package com.scooterrentalkandy.spring.dto;


import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;

public final class MaintenanceDtos {

    private MaintenanceDtos() {
    }

    public record CreateMaintenanceRequest(
            @NotNull UUID scooterId,
            @NotNull MaintenanceRecord.Type type,
            @NotBlank String description,
            LocalDate scheduledDate,
            /** Start work immediately: takes the scooter out of service. */
            boolean startNow) {
    }

    public record CompleteMaintenanceRequest(
            @DecimalMin("0.00") BigDecimal cost,
            Integer odometerKm,
            String notes) {
    }

    public record MaintenanceResponse(
            UUID id,
            UUID scooterId,
            String scooterCode,
            String scooterModel,
            MaintenanceRecord.Type type,
            MaintenanceRecord.Status status,
            String description,
            BigDecimal cost,
            LocalDate scheduledDate,
            LocalDate completedDate,
            Integer odometerKm,
            LocalDateTime createdAt) {
    }
}
