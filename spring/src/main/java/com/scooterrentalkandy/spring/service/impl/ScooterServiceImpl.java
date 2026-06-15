package com.scooterrentalkandy.spring.service.impl;


import com.scooterrentalkandy.spring.dto.request.ScooterRequestDTO;
import com.scooterrentalkandy.spring.dto.response.ScooterResponseDTO;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.common.enums.ScooterStatus;
import com.scooterrentalkandy.spring.exception.DuplicateResourceException;
import com.scooterrentalkandy.spring.exception.ResourceNotFoundException;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.service.ScooterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScooterServiceImpl implements ScooterService {

    private final ScooterRepository scooterRepository;

    // ── Add Scooter ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ScooterResponseDTO addScooter(ScooterRequestDTO request) {
        log.info("Adding new scooter with registration: {}", request.getRegistrationNo());

        if (scooterRepository.existsByRegistrationNo(request.getRegistrationNo())) {
            throw new DuplicateResourceException(
                    "Scooter with registration number '" + request.getRegistrationNo() + "' already exists"
            );
        }

        Scooter scooter = Scooter.builder()
                .model(request.getModel())
                .registrationNo(request.getRegistrationNo())
                .status(request.getStatus())
                .hourlyRate(request.getHourlyRate())
                .perKmRate(request.getPerKmRate())
                .imageUrl(request.getImageUrl())
                .totalMileage(request.getTotalMileage() != null
                        ? request.getTotalMileage()
                        : BigDecimal.ZERO)
                .lastServiceDate(request.getLastServiceDate())
                .build();

        Scooter saved = scooterRepository.save(scooter);
        log.info("Scooter added successfully with ID: {}", saved.getScooterId());
        return mapToResponse(saved);
    }

    // ── Get By ID ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ScooterResponseDTO getScooterById(UUID scooterId) {
        Scooter scooter = findOrThrow(scooterId);
        return mapToResponse(scooter);
    }

    // ── Get All ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ScooterResponseDTO> getAllScooters() {
        return scooterRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ── Get By Status ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ScooterResponseDTO> getScootersByStatus(ScooterStatus status) {
        return scooterRepository.findByStatus(status)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ── Update Scooter ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ScooterResponseDTO updateScooter(UUID scooterId, ScooterRequestDTO request) {
        log.info("Updating scooter ID: {}", scooterId);
        Scooter scooter = findOrThrow(scooterId);

        // Check registration number conflict only if it changed
        if (!scooter.getRegistrationNo().equals(request.getRegistrationNo())
                && scooterRepository.existsByRegistrationNo(request.getRegistrationNo())) {
            throw new DuplicateResourceException(
                    "Registration number '" + request.getRegistrationNo() + "' is already in use"
            );
        }

        scooter.setModel(request.getModel());
        scooter.setRegistrationNo(request.getRegistrationNo());
        scooter.setStatus(request.getStatus());
        scooter.setHourlyRate(request.getHourlyRate());
        scooter.setPerKmRate(request.getPerKmRate());
        scooter.setImageUrl(request.getImageUrl());
        if (request.getTotalMileage() != null) {
            scooter.setTotalMileage(request.getTotalMileage());
        }
        scooter.setLastServiceDate(request.getLastServiceDate());

        Scooter updated = scooterRepository.save(scooter);
        log.info("Scooter updated successfully: {}", scooterId);
        return mapToResponse(updated);
    }

    // ── Update Status Only ────────────────────────────────────────────────────

    @Override
    @Transactional
    public ScooterResponseDTO updateScooterStatus(UUID scooterId, ScooterStatus status) {
        log.info("Updating status of scooter {} to {}", scooterId, status);
        Scooter scooter = findOrThrow(scooterId);
        scooter.setStatus(status);
        return mapToResponse(scooterRepository.save(scooter));
    }

    // ── Delete Scooter ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteScooter(UUID scooterId) {
        log.info("Deleting scooter ID: {}", scooterId);
        Scooter scooter = findOrThrow(scooterId);
        scooterRepository.delete(scooter);
        log.info("Scooter deleted: {}", scooterId);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Scooter findOrThrow(UUID scooterId) {
        return scooterRepository.findById(scooterId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Scooter not found with ID: " + scooterId));
    }

    private ScooterResponseDTO mapToResponse(Scooter scooter) {
        return ScooterResponseDTO.builder()
                .scooterId(scooter.getScooterId())
                .model(scooter.getModel())
                .registrationNo(scooter.getRegistrationNo())
                .status(scooter.getStatus())
                .hourlyRate(scooter.getHourlyRate())
                .perKmRate(scooter.getPerKmRate())
                .imageUrl(scooter.getImageUrl())
                .totalMileage(scooter.getTotalMileage())
                .lastServiceDate(scooter.getLastServiceDate())
                .build();
    }
}