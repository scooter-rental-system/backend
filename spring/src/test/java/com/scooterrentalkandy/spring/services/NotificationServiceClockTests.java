package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Notification.Channel;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/** Notification times come from the injected clock, never the system clock. */
class NotificationServiceClockTests {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-03-01T04:30:00Z"), COLOMBO);
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 3, 1, 10, 0);

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private NotificationService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new NotificationService(repository, users, emptyProvider(), mock(ApplicationEventPublisher.class),
                FIXED);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmailSender> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    private static User user(Role role) {
        User u = new User();
        u.setUserId(UUID.randomUUID());
        u.setEmail("clock@example.test");
        u.setRole(role);
        u.setIsActive(true);
        return u;
    }

    @Test
    void newNotificationsAreStampedWithTheInjectedClock() {
        when(users.findByRoleInAndIsActiveTrue(Role.ADMINS)).thenReturn(List.of(user(Role.ADMIN)));

        service.notifyAdmins("Service due: S-1", "Due for a service.");

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.getValue().getChannel()).isEqualTo(Channel.IN_APP);
    }

    @Test
    void markReadUsesTheInjectedClockAndKeepsTheFirstReadTime() {
        User owner = user(Role.USER);
        Notification unread = new Notification();
        unread.setUser(owner);
        UUID id = UUID.randomUUID();
        when(repository.findByNotificationIdAndUser_UserIdAndChannel(id, owner.getUserId(), Channel.IN_APP))
                .thenReturn(Optional.of(unread));

        service.markRead(owner.getUserId(), id);
        assertThat(unread.getReadAt()).isEqualTo(FIXED_NOW);

        LocalDateTime earlier = FIXED_NOW.minusDays(1);
        unread.setReadAt(earlier);
        service.markRead(owner.getUserId(), id);
        assertThat(unread.getReadAt()).isEqualTo(earlier);
    }

    @Test
    void markAllReadUsesTheInjectedClock() {
        UUID userId = UUID.randomUUID();

        service.markAllRead(userId);

        verify(repository).markAllRead(eq(userId), eq(Channel.IN_APP), eq(FIXED_NOW));
    }
}
