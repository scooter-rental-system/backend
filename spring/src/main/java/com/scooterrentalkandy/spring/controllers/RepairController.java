package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.RepairDtos.CreateRepairRequest;
import com.scooterrentalkandy.spring.dto.RepairDtos.RepairResponse;
import com.scooterrentalkandy.spring.dto.RepairDtos.SupportContact;
import com.scooterrentalkandy.spring.dto.RepairDtos.UpdateRepairRequest;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.RepairService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repair and breakdown reports: customers report on their own rental in progress; /api/admin/** is admin-only
 * (SecurityConfig). The support contact comes from configuration (SUPPORT_PHONE, SUPPORT_EMAIL) and is null when unset.
 */
@RestController
public class RepairController {

    private final RepairService repairs;
    private final SupportContact support;

    public RepairController(RepairService repairs, @Value("${app.support.phone:}") String supportPhone,
                            @Value("${app.support.email:}") String supportEmail) {
        this.repairs = repairs;
        this.support = new SupportContact(blankToNull(supportPhone), blankToNull(supportEmail));
    }

    @PostMapping("/api/bookings/{id}/repairs")
    @ResponseStatus(HttpStatus.CREATED)
    public RepairResponse report(@CurrentUser AuthUser user, @PathVariable UUID id,
                                 @Valid @RequestBody CreateRepairRequest req) {
        return repairs.report(id, user, req);
    }

    @GetMapping("/api/bookings/{id}/repairs")
    public List<RepairResponse> forBooking(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return repairs.forBooking(id, user);
    }

    @GetMapping("/api/admin/repairs")
    public List<RepairResponse> all() {
        return repairs.all();
    }

    @PutMapping("/api/admin/repairs/{id}")
    public RepairResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRepairRequest req) {
        return repairs.update(id, req);
    }

    /** Signed-in users only (the default rule); shown next to the repair report form. */
    @GetMapping("/api/support/contact")
    public SupportContact supportContact() {
        return support;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
