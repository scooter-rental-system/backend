package com.scooterrentalkandy.spring.dto.request;


import com.scooterrentalkandy.spring.common.enums.ScooterStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScooterRequestDTO {

    @NotBlank(message = "Model is required")
    @Size(max = 150, message = "Model name must not exceed 150 characters")
    private String model;

    @NotBlank(message = "Registration number is required")
    @Size(max = 30, message = "Registration number must not exceed 30 characters")
    private String registrationNo;

    @NotNull(message = "Status is required")
    private ScooterStatus status;

    @NotNull(message = "Hourly rate is required")
    @DecimalMin(value = "0.01", message = "Hourly rate must be greater than 0")
    @Digits(integer = 8, fraction = 2, message = "Invalid hourly rate format")
    private BigDecimal hourlyRate;

    @NotNull(message = "Per km rate is required")
    @DecimalMin(value = "0.00", message = "Per km rate must be 0 or greater")
    @Digits(integer = 8, fraction = 2, message = "Invalid per km rate format")
    private BigDecimal perKmRate;

    @Size(max = 500, message = "Image URL must not exceed 500 characters")
    private String imageUrl;

    private BigDecimal totalMileage;

    private LocalDate lastServiceDate;
}