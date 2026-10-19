package com.scooterrentalkandy.spring.mappers;


import com.scooterrentalkandy.spring.dto.NotificationDtos.NotificationLogResponse;
import com.scooterrentalkandy.spring.dto.NotificationDtos.NotificationResponse;
import com.scooterrentalkandy.spring.entity.Notification;

public final class NotificationMapper {

    private NotificationMapper() {
    }

    public static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getNotificationId(), n.getSubject(), n.getMessage(), n.getReadAt() != null,
                n.getCreatedAt());
    }

    public static NotificationLogResponse toLogResponse(Notification n) {
        return new NotificationLogResponse(n.getNotificationId(), n.getUser().getEmail(), n.getChannel(), n.getSubject(),
                n.getStatus(), n.getCreatedAt());
    }
}

