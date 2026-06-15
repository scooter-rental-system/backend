package com.scooterrentalkandy.spring.service;

import com.scooterrentalkandy.spring.dto.request.ScooterRequestDTO;
import com.scooterrentalkandy.spring.dto.response.ScooterResponseDTO;
import com.scooterrentalkandy.spring.common.enums.ScooterStatus;

import java.util.List;
import java.util.UUID;

public interface ScooterService {

    /** Add a new scooter to the fleet (Admin only). */
    ScooterResponseDTO addScooter(ScooterRequestDTO request);

    /** Get details of a single scooter by its ID. */
    ScooterResponseDTO getScooterById(UUID scooterId);

    /** Get all scooters in the fleet (Admin view). */
    List<ScooterResponseDTO> getAllScooters();

    /** Get all scooters filtered by status (e.g. AVAILABLE for browse page). */
    List<ScooterResponseDTO> getScootersByStatus(ScooterStatus status);

    /** Update scooter details (Admin only). */
    ScooterResponseDTO updateScooter(UUID scooterId, ScooterRequestDTO request);

    /** Update only the fleet status of a scooter (Admin only). */
    ScooterResponseDTO updateScooterStatus(UUID scooterId, ScooterStatus status);

    /** Soft-delete / remove scooter from fleet (Admin only). */
    void deleteScooter(UUID scooterId);
}