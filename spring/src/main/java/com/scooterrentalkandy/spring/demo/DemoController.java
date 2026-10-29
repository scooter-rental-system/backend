package com.scooterrentalkandy.spring.demo;

import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Viva demo endpoints, present only under the {@code demo} profile (see {@link DemoMode}); without it these paths do
 * not exist. The lifecycle actions are admin-only: by the /api/admin/** URL rule and again per method.
 */
@RestController
@Profile(DemoMode.PROFILES)
public class DemoController {

    public record DemoStatus(boolean demo, String message) {
    }

    private final DemoLifecycleService demo;

    public DemoController(DemoLifecycleService demo) {
        this.demo = demo;
    }

    /** Any signed-in user: lets the app show the demo banner. Nothing about the configuration is returned. */
    @GetMapping("/api/demo/status")
    public DemoStatus status() {
        return new DemoStatus(true, "Viva demo mode: no real payments are processed.");
    }

    @PostMapping("/api/admin/demo/bookings/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public BookingResponse activate(@PathVariable UUID id, @CurrentUser AuthUser admin) {
        return demo.activate(id, admin);
    }

    @PostMapping("/api/admin/demo/bookings/{id}/end")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public BookingResponse end(@PathVariable UUID id, @CurrentUser AuthUser admin) {
        return demo.end(id, admin);
    }

    @PostMapping("/api/admin/demo/bookings/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public BookingResponse close(@PathVariable UUID id, @CurrentUser AuthUser admin) {
        return demo.close(id, admin);
    }
}
