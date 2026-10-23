package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CompleteMaintenanceRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CreateMaintenanceRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.MaintenanceResponse;
import com.scooterrentalkandy.spring.services.MaintenanceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/maintenance")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping
    public List<MaintenanceResponse> list(@RequestParam(required = false) UUID scooterId) {
        return service.list(scooterId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse create(@Valid @RequestBody CreateMaintenanceRequest req) {
        return service.create(req);
    }

    @PostMapping("/{id}/start")
    public MaintenanceResponse start(@PathVariable UUID id) {
        return service.start(id);
    }

    @PostMapping("/{id}/complete")
    public MaintenanceResponse complete(@PathVariable UUID id, @Valid @RequestBody CompleteMaintenanceRequest req) {
        return service.complete(id, req);
    }

    @PostMapping("/{id}/cancel")
    public MaintenanceResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
