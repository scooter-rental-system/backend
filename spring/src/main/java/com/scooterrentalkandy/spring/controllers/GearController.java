package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.GearDtos.GearRequest;
import com.scooterrentalkandy.spring.dto.GearDtos.GearResponse;
import com.scooterrentalkandy.spring.services.GearService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GearController {

    private final GearService service;

    public GearController(GearService service) {
        this.service = service;
    }

    @GetMapping("/api/gear")
    public List<GearResponse> list() {
        return service.listActive();
    }

    @GetMapping("/api/admin/gear")
    public List<GearResponse> adminList() {
        return service.listAll();
    }

    @PostMapping("/api/admin/gear")
    @ResponseStatus(HttpStatus.CREATED)
    public GearResponse create(@Valid @RequestBody GearRequest req) {
        return service.create(req);
    }

    @PutMapping("/api/admin/gear/{id}")
    public GearResponse update(@PathVariable UUID id, @Valid @RequestBody GearRequest req) {
        return service.update(id, req);
    }
}

