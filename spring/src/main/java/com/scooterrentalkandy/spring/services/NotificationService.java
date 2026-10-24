package com.scooterrentalkandy.spring.services;


import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.NotificationDtos;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.NotificationMapper;
import com.scooterrentalkandy.spring.entity.Notification.Channel;
import com.scooterrentalkandy.spring.entity.Notification.Status;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final OutboundMessageSender sender;
    private final UserRepository users;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, OutboundMessageSender sender,
                               UserRepository users, Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Records an in-app notification and sends it by email (and SMS if a phone number is on file). An inactive
     * user gets nothing.
     */
    @Transactional
    public void notify(User user, String subject, String message) {
        if (!isActive(user)) {
            return;
        }
        save(user, Channel.IN_APP, subject, message, Status.SENT);
        boolean emailed = sender.sendEmail(user.getEmail(), subject, message);
        save(user, Channel.EMAIL, subject, message, emailed ? Status.SENT : Status.FAILED);
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            boolean texted = sender.sendSms(user.getPhone(), subject + ": " + message);
            save(user, Channel.SMS, subject, message, texted ? Status.SENT : Status.FAILED);
        }
    }

    /** Notifies every active admin, in-app only. */
    @Transactional
    public void notifyAdmins(String subject, String message) {
        users.findByRoleInAndIsActiveTrue(Role.ADMINS)
                .forEach(admin -> save(admin, Channel.IN_APP, subject, message, Status.SENT));
    }

    /**
     * Urgent alert to every active admin: in-app plus SMS (SDS 7.1: speed-violation alerts by SNS; SMS goes through
     * the outbound sender, which is the mock SNS in development).
     */
    @Transactional
    public void alertAdmins(String subject, String message) {
        users.findByRoleInAndIsActiveTrue(Role.ADMINS).forEach(admin -> {
            save(admin, Channel.IN_APP, subject, message, Status.SENT);
            if (admin.getPhone() != null && !admin.getPhone().isBlank()) {
                boolean texted = sender.sendSms(admin.getPhone(), subject + ": " + message);
                save(admin, Channel.SMS, subject, message, texted ? Status.SENT : Status.FAILED);
            }
        });
    }

    /** Every active customer (role USER); returns how many were notified. */
    @Transactional
    public int broadcastToCustomers(String subject, String message) {
        List<User> customers = users.findByRoleAndIsActiveTrue(Role.USER);
        customers.forEach(c -> notify(c, subject, message));
        return customers.size();
    }

    /** The user's 200 most recent in-app notifications, newest first. */
    @Transactional(readOnly = true)
    public List<NotificationDtos.NotificationResponse> inbox(UUID userId) {
        return repository.findTop200ByUser_UserIdAndChannelOrderByCreatedAtDesc(userId, Channel.IN_APP).stream()
                .map(NotificationMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUser_UserIdAndChannelAndReadAtIsNull(userId, Channel.IN_APP);
    }

    /** Only the owner's in-app notification; anything else is 404 so other users' ids are not revealed. */
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification n = repository.findByNotificationIdAndUser_UserIdAndChannel(notificationId, userId,
                        Channel.IN_APP)
                .orElseThrow(() -> ApiException.notFound("Notification"));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now(clock));
        }
    }

    @Transactional
    public void markAllRead(UUID userId) {
        repository.markAllRead(userId, Channel.IN_APP, LocalDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public List<NotificationDtos.NotificationLogResponse> recentLog() {
        return repository.findTop200ByOrderByCreatedAtDesc().stream()
                .map(NotificationMapper::toLogResponse).toList();
    }

    private static boolean isActive(User user) {
        return Boolean.TRUE.equals(user.getIsActive());
    }

    private void save(User user, Channel channel, String subject, String message, Status status) {
        Notification n = new Notification();
        n.setUser(user);
        n.setChannel(channel);
        n.setSubject(subject);
        n.setMessage(message);
        n.setStatus(status);
        n.setCreatedAt(LocalDateTime.now(clock));
        repository.save(n);
    }
}
