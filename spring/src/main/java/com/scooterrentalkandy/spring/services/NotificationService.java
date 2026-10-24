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
import com.scooterrentalkandy.spring.services.NotificationEmailDispatcher.EmailRequested;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications are database rows and always available. Email goes out only when a real provider is
 * configured (an EmailSender bean exists), and only after the calling transaction commits (see
 * NotificationEmailDispatcher). There is no SMS provider, so no SMS is sent or recorded.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final UserRepository users;
    private final ObjectProvider<EmailSender> emailSender;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, UserRepository users,
                               ObjectProvider<EmailSender> emailSender, ApplicationEventPublisher events,
                               Clock clock) {
        this.repository = repository;
        this.users = users;
        this.emailSender = emailSender;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Records an in-app notification and, when email is configured, requests the same message by email once the
     * calling transaction commits. An inactive user gets nothing.
     */
    @Transactional
    public void notify(User user, String subject, String message) {
        if (!isActive(user)) {
            return;
        }
        save(user, Channel.IN_APP, subject, message, Status.SENT);
        if (emailSender.getIfAvailable() != null) {
            events.publishEvent(new EmailRequested(user.getUserId(), user.getEmail(), subject, message));
        }
    }

    /** Notifies every active admin, in-app only. */
    @Transactional
    public void notifyAdmins(String subject, String message) {
        users.findByRoleInAndIsActiveTrue(Role.ADMINS)
                .forEach(admin -> save(admin, Channel.IN_APP, subject, message, Status.SENT));
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
