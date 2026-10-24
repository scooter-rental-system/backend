package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.NotificationDtos.BroadcastRequest;
import com.scooterrentalkandy.spring.dto.NotificationDtos.BroadcastResponse;
import com.scooterrentalkandy.spring.dto.NotificationDtos.NotificationLogResponse;
import com.scooterrentalkandy.spring.dto.NotificationDtos.NotificationResponse;
import com.scooterrentalkandy.spring.dto.NotificationDtos.UnreadCountResponse;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's in-app notifications, and the admin delivery log and customer broadcast. The user always
 * comes from the token; no endpoint accepts a user id, email address or phone number.
 */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/notifications")
    public List<NotificationResponse> inbox(@CurrentUser AuthUser user) {
        return notifications.inbox(user.id());
    }

    @GetMapping("/api/notifications/unread-count")
    public UnreadCountResponse unreadCount(@CurrentUser AuthUser user) {
        return new UnreadCountResponse(notifications.unreadCount(user.id()));
    }

    /** Another user's (or an unknown) notification is 404. Marking an already read notification changes nothing. */
    @PostMapping("/api/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id, @CurrentUser AuthUser user) {
        notifications.markRead(user.id(), id);
    }

    @PostMapping("/api/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@CurrentUser AuthUser user) {
        notifications.markAllRead(user.id());
    }

    /** ADMIN/SUPER_ADMIN (SecurityConfig): the most recent deliveries to every user, without message bodies. */
    @GetMapping("/api/admin/notifications")
    public List<NotificationLogResponse> log() {
        return notifications.recentLog();
    }

    /** ADMIN/SUPER_ADMIN: to every active customer; the recipients are chosen here, never by the client. */
    @PostMapping("/api/admin/notifications/broadcast")
    public BroadcastResponse broadcast(@Valid @RequestBody BroadcastRequest req) {
        return new BroadcastResponse(notifications.broadcastToCustomers(req.subject().trim(), req.message().trim()));
    }
}
