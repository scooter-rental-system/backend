package com.scooterrentalkandy.spring.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Notification;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(UUID id, String subject, String message, boolean read,
                                       LocalDateTime createdAt) {
    }

    /** The admin delivery log; the message body is left out. */
    public record NotificationLogResponse(UUID id, String recipient, Notification.Channel channel,
                                          String subject, Notification.Status status, LocalDateTime createdAt) {
    }

    public record UnreadCountResponse(long count) {
    }

    /** Limits match the notifications table (subject 255, message 4000). */
    public record BroadcastRequest(@NotBlank @Size(max = 255) String subject,
                                   @NotBlank @Size(max = 4000) String message) {
    }

    public record BroadcastResponse(int recipients) {
    }
}
