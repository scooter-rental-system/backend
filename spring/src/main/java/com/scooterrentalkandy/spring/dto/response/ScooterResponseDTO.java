package com.scooterrentalkandy.spring.dto.response;


import com.scooterrentalkandy.spring.common.enums.ScooterStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScooterResponseDTO {

    private UUID scooterId;
    private String model;
    private String registrationNo;
    private ScooterStatus status;
    private BigDecimal hourlyRate;
    private BigDecimal perKmRate;
    private String imageUrl;
    private BigDecimal totalMileage;
    private LocalDate lastServiceDate;
}