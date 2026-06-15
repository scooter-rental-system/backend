package com.scooterrentalkandy.spring.controller;


import com.scooterrentalkandy.spring.dto.request.ScooterRequestDTO;
import com.scooterrentalkandy.spring.dto.response.ScooterResponseDTO;
import com.scooterrentalkandy.spring.common.enums.ScooterStatus;
import com.scooterrentalkandy.spring.service.ScooterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scooters")
@RequiredArgsConstructor
public class ScooterController {

    private final ScooterService scooterService;

    // ── POST /api/v1/scooters ─────────────────────────────────────────────────
    // Admin: Add a new scooter to the fleet
    @PostMapping
    public ResponseEntity<ScooterResponseDTO> addScooter(
            @Valid @RequestBody ScooterRequestDTO request) {
        ScooterResponseDTO response = scooterService.addScooter(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── GET /api/v1/scooters ──────────────────────────────────────────────────
    // Admin: Get all scooters
    // User:  Get available scooters → /api/v1/scooters?status=AVAILABLE
    @GetMapping
    public ResponseEntity<List<ScooterResponseDTO>> getScooters(
            @RequestParam(required = false) ScooterStatus status) {
        if (status != null) {
            return ResponseEntity.ok(scooterService.getScootersByStatus(status));
        }
        return ResponseEntity.ok(scooterService.getAllScooters());
    }

    // ── GET /api/v1/scooters/{id} ─────────────────────────────────────────────
    // Get a single scooter by ID
    @GetMapping("/{scooterId}")
    public ResponseEntity<ScooterResponseDTO> getScooterById(
            @PathVariable UUID scooterId) {
        return ResponseEntity.ok(scooterService.getScooterById(scooterId));
    }

    // ── PUT /api/v1/scooters/{id} ─────────────────────────────────────────────
    // Admin: Full update of scooter details
    @PutMapping("/{scooterId}")
    public ResponseEntity<ScooterResponseDTO> updateScooter(
            @PathVariable UUID scooterId,
            @Valid @RequestBody ScooterRequestDTO request) {
        return ResponseEntity.ok(scooterService.updateScooter(scooterId, request));
    }

    // ── PATCH /api/v1/scooters/{id}/status ───────────────────────────────────
    // Admin: Update only the fleet status (AVAILABLE / RENTED / MAINTENANCE)
    @PatchMapping("/{scooterId}/status")
    public ResponseEntity<ScooterResponseDTO> updateStatus(
            @PathVariable UUID scooterId,
            @RequestParam ScooterStatus status) {
        return ResponseEntity.ok(scooterService.updateScooterStatus(scooterId, status));
    }

    // ── DELETE /api/v1/scooters/{id} ──────────────────────────────────────────
    // Admin: Remove a scooter from the fleet
    @DeleteMapping("/{scooterId}")
    public ResponseEntity<Void> deleteScooter(@PathVariable UUID scooterId) {
        scooterService.deleteScooter(scooterId);
        return ResponseEntity.noContent().build();
    }
}