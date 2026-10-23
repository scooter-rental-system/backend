package com.scooterrentalkandy.spring.dto;

import com.scooterrentalkandy.spring.entity.enums.Category;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.GearItem;

public final class GearDtos {

    private GearDtos() {
    }

    /** SDS Camping Gear: dailyRate > 0, stock >= 0; lengths follow the gear_items columns. */
    public record GearRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Category category,
            @Size(max = 1000) String description,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal dailyRate,
            @Min(0) int totalQuantity,
            boolean active) {
    }

    /** {@code available}: units free right now (public list only; null in the admin list). */
    public record GearResponse(
            UUID id,
            String name,
            Category category,
            String description,
            BigDecimal dailyRate,
            int totalQuantity,
            Integer available,
            boolean active) {
    }
}
