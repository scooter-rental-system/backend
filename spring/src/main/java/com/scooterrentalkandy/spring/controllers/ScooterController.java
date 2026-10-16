package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.ScooterDtos.ScooterRequest;
import com.scooterrentalkandy.spring.dto.ScooterDtos.ScooterResponse;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.services.ScooterService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScooterController {

    private final ScooterService service;

    public ScooterController(ScooterService service) {
        this.service = service;
    }

    @GetMapping("/api/scooters")
    public List<ScooterResponse> list() {
        return service.listBookable();
    }

    @GetMapping("/api/scooters/{id}")
    public ScooterResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/api/admin/scooters")
    public List<ScooterResponse> adminList() {
        return service.listAll();
    }

    @PostMapping("/api/admin/scooters")
    @ResponseStatus(HttpStatus.CREATED)
    public ScooterResponse create(@Valid @RequestBody ScooterRequest req) {
        return service.create(req);
    }

    @PutMapping("/api/admin/scooters/{id}")
    public ScooterResponse update(@PathVariable UUID id, @Valid @RequestBody ScooterRequest req) {
        return service.update(id, req);
    }

    @PatchMapping("/api/admin/scooters/{id}/status")
    public ScooterResponse setStatus(@PathVariable UUID id, @RequestParam ScooterStatus value) {
        return service.setStatus(id, value);
    }

    @DeleteMapping("/api/admin/scooters/{id}")
    public ScooterResponse remove(@PathVariable UUID id) {
        return service.remove(id);
    }

    @PostMapping("/api/admin/scooters/{id}/restore")
    public ScooterResponse restore(@PathVariable UUID id) {
        return service.restore(id);
    }
}
