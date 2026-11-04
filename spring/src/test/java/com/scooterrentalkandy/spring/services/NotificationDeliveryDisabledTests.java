package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Notification.Channel;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The application as it runs without an email provider (the default): in-app notifications work, and no email or
 * SMS is attempted or recorded, so the delivery log never claims a message that was not sent. There is no mock
 * sender to fall back to. The transactions here really commit, so that an after-commit send would show up.
 */
@AutoConfigureMockMvc
class NotificationDeliveryDisabledTests extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private NotificationRepository rows;

    @Autowired
    private UserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    /** Committed fixtures, deleted after each test so the shared test database is left as it was. */
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();

    @AfterEach
    void deleteFixtures() {
        for (UUID user : createdUsers) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
        }
        createdScooters.forEach(id -> jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
        createdUsers.clear();
        createdScooters.clear();
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User customer() {
        String id = unique();
        User u = new User();
        u.setEmail("nodelivery." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("No Delivery " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("X" + id);
        u.setRole(Role.USER);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private List<Notification> rowsOf(User user, Channel channel) {
        return rows.findByUser_UserIdAndChannelOrderByCreatedAtDesc(user.getUserId(), channel);
    }

    @Test
    void thereIsNoMockOrStandInSender() {
        assertThat(context.getBeanNamesForType(EmailSender.class)).isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames()).map(String::toLowerCase))
                .noneMatch(name -> name.contains("mock") && (name.contains("sender") || name.contains("message")));
        for (String removed : new String[] {"MockOutboundMessageSender", "OutboundMessageSender"}) {
            assertThatThrownBy(() -> Class.forName("com.scooterrentalkandy.spring.services." + removed))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void withoutAProviderNotificationsAreInAppOnlyAndNothingClaimsToBeSent() {
        User user = customer();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            notifications.notify(user, "Booking confirmed: BK-1", "Your scooter is ready.");
            notifications.broadcastToCustomers("Holiday hours", "We open at 9.");
        });

        assertThat(rowsOf(user, Channel.IN_APP)).hasSize(2);
        // The user has a phone number: still no SMS, and no email, attempted or recorded.
        assertThat(rowsOf(user, Channel.EMAIL)).isEmpty();
        assertThat(rowsOf(user, Channel.SMS)).isEmpty();
    }

    @Test
    void registrationWelcomesInAppWithoutAnEmailRecord() throws Exception {
        String address = "nodelivery." + unique() + "@example.test";

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"secret-pass-1\","
                                + "\"fullName\":\"Plain Rider\",\"phone\":\"+94 70 000 0000\",\"nicNumber\":\""
                                + TestNics.of("P" + unique()) + "\",\"consent\":true}"))
                .andExpect(status().isCreated());

        User user = users.findByEmailIgnoreCase(address).orElseThrow();
        createdUsers.add(user.getUserId());
        assertThat(rowsOf(user, Channel.IN_APP)).singleElement().satisfies(n -> {
            assertThat(n.getSubject()).isEqualTo(AuthService.WELCOME_SUBJECT);
            assertThat(n.getMessage()).startsWith("Hi Plain Rider, your account is ready.");
            assertThat(n.getReadAt()).isNull();
        });
        assertThat(rowsOf(user, Channel.EMAIL)).isEmpty();
        assertThat(rowsOf(user, Channel.SMS)).isEmpty();
    }
}
