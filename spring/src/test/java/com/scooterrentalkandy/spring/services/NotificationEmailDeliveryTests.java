package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Notification.Channel;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import com.scooterrentalkandy.spring.support.RecordingEmailSender;
import com.scooterrentalkandy.spring.support.RecordingEmailSender.Sent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email delivery with an email provider configured, here the test-only {@link RecordingEmailSender}: email goes
 * out only after the business transaction commits, a rollback sends nothing, and a refused message is recorded as
 * FAILED without touching the committed business state. The transactions here really commit (the data uses unique
 * names), because commit and rollback are what is being tested.
 */
@AutoConfigureMockMvc
@Import(RecordingEmailSender.class)
class NotificationEmailDeliveryTests extends PostgresIntegrationTest {

    @Autowired
    private RecordingEmailSender email;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private NotificationRepository rows;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    private TransactionTemplate transaction;

    /** Committed fixtures, deleted after each test so the shared test database is left as it was. */
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        email.reset();
        transaction = new TransactionTemplate(transactionManager);
    }

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

    private User saveUser(Role role, boolean active) {
        String id = unique();
        User u = new User();
        u.setEmail("deliver." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Deliver " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("D" + id);
        u.setRole(role);
        u.setIsActive(active);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private User customer() {
        return saveUser(Role.USER, true);
    }

    private List<Notification> rowsOf(User user, Channel channel) {
        return rows.findByUser_UserIdAndChannelOrderByCreatedAtDesc(user.getUserId(), channel);
    }

    private long committedInApp(User user) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND channel = 'IN_APP'",
                Long.class, user.getUserId());
    }

    @Test
    void emailIsSentAfterTheTransactionCommitsAndRecordedAsSent() {
        User user = customer();
        // Seen from another connection when the provider is called: the in-app row must already be committed.
        AtomicLong committedWhenSent = new AtomicLong(-1);
        email.onSend(() -> committedWhenSent.set(CompletableFuture.supplyAsync(() -> committedInApp(user)).join()));

        transaction.executeWithoutResult(tx -> {
            notifications.notify(user, "Booking confirmed: BK-1", "Your scooter is ready.");
            assertThat(email.sent()).as("nothing is sent inside the transaction").isEmpty();
        });

        assertThat(email.sentTo(user.getEmail()))
                .containsExactly(new Sent(user.getEmail(), "Booking confirmed: BK-1", "Your scooter is ready."));
        assertThat(committedWhenSent).hasValue(1);
        assertThat(rowsOf(user, Channel.IN_APP)).hasSize(1);
        assertThat(rowsOf(user, Channel.EMAIL)).singleElement().satisfies(n -> {
            assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT);
            assertThat(n.getSubject()).isEqualTo("Booking confirmed: BK-1");
        });
        assertThat(rowsOf(user, Channel.SMS)).isEmpty();
    }

    @Test
    void aRolledBackTransactionSendsNoEmailAndLeavesNoRows() {
        User user = customer();

        transaction.executeWithoutResult(tx -> {
            notifications.notify(user, "Booking confirmed: BK-2", "Never happened.");
            tx.setRollbackOnly();
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            notifications.notify(user, "Booking confirmed: BK-3", "Never happened either.");
            throw new IllegalStateException("business failure after the notification");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(email.sentTo(user.getEmail())).isEmpty();
        assertThat(rowsOf(user, Channel.IN_APP)).isEmpty();
        assertThat(rowsOf(user, Channel.EMAIL)).isEmpty();
    }

    @Test
    void aRefusedEmailIsRecordedAsFailedAndDoesNotRollBackTheBusinessChange() {
        User user = customer();
        Scooter scooter = new Scooter();
        String id = unique();
        scooter.setCode("ND-" + id);
        scooter.setModel("Test Scooter");
        scooter.setPlateNumber("NDP-" + id);
        scooter.setDailyRate(new BigDecimal("2500.00"));
        scooter.setHourlyRate(new BigDecimal("250.00"));
        scooter.setPerKmRate(new BigDecimal("15.00"));
        Scooter savedScooter = scooters.saveAndFlush(scooter);
        createdScooters.add(savedScooter.getScooterId());
        Booking b = new Booking();
        b.setReference("BK-ND" + id);
        b.setUser(user);
        b.setScooter(savedScooter);
        b.setStatus(BookingStatus.PENDING);
        b.setStartTime(LocalDateTime.now().plusDays(1));
        b.setPickupLocation("Test pickup");
        UUID bookingId = bookings.saveAndFlush(b).getBookingId();
        email.failing();

        transaction.executeWithoutResult(tx -> {
            Booking locked = bookings.findById(bookingId).orElseThrow();
            locked.setStatus(BookingStatus.READY_FOR_PICKUP);
            notifications.notify(user, "Booking confirmed: " + locked.getReference(), "Your scooter is ready.");
        });

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(rowsOf(user, Channel.IN_APP)).hasSize(1);
        assertThat(rowsOf(user, Channel.EMAIL)).singleElement()
                .satisfies(n -> assertThat(n.getStatus()).isEqualTo(Notification.Status.FAILED));
        assertThat(email.sent()).isEmpty();
    }

    @Test
    void inactiveUsersAreNotEmailed() {
        User inactive = saveUser(Role.USER, false);

        transaction.executeWithoutResult(tx -> notifications.notify(inactive, "Subject", "Message"));

        assertThat(email.sentTo(inactive.getEmail())).isEmpty();
        assertThat(rowsOf(inactive, Channel.IN_APP)).isEmpty();
        assertThat(rowsOf(inactive, Channel.EMAIL)).isEmpty();
    }

    @Test
    void adminNoticesStayInApp() {
        User admin = saveUser(Role.ADMIN, true);

        transaction.executeWithoutResult(tx -> notifications.notifyAdmins("Service due: S-1", "Due for a service."));

        assertThat(email.sentTo(admin.getEmail())).isEmpty();
        assertThat(rowsOf(admin, Channel.IN_APP)).hasSize(1);
        assertThat(rowsOf(admin, Channel.EMAIL)).isEmpty();
    }

    @Test
    void broadcastEmailsActiveCustomersOnlyAfterCommit() {
        User active = customer();
        User inactive = saveUser(Role.USER, false);

        transaction.executeWithoutResult(tx -> {
            notifications.broadcastToCustomers("Holiday hours", "We open at 9.");
            assertThat(email.sent()).isEmpty();
        });

        assertThat(email.sentTo(active.getEmail())).containsExactly(
                new Sent(active.getEmail(), "Holiday hours", "We open at 9."));
        assertThat(email.sentTo(inactive.getEmail())).isEmpty();
        assertThat(rowsOf(active, Channel.EMAIL)).singleElement()
                .satisfies(n -> assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT));
    }

    // ---------------------------------------------------------------- welcome (SDS 4.1)

    private static String registration(String emailAddress) {
        return "{\"email\":\"" + emailAddress + "\",\"password\":\"secret-pass-1\",\"fullName\":\"Welcome Rider\","
                + "\"phone\":\"+94 70 000 0000\",\"nicNumber\":\"" + TestNics.of("W" + unique()) + "\",\"consent\":true}";
    }

    private User registered(String emailAddress) {
        User user = users.findByEmailIgnoreCase(emailAddress).orElseThrow();
        createdUsers.add(user.getUserId());
        return user;
    }

    @Test
    void registrationSendsTheWelcomeEmailAfterTheAccountIsSaved() throws Exception {
        String address = "welcome." + unique() + "@example.test";

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration(address)))
                .andExpect(status().isCreated());

        User user = registered(address);
        assertThat(email.sentTo(address)).singleElement().satisfies(s -> {
            assertThat(s.subject()).isEqualTo(AuthService.WELCOME_SUBJECT);
            assertThat(s.body()).startsWith("Hi Welcome Rider, your account is ready.");
        });
        assertThat(rowsOf(user, Channel.IN_APP)).singleElement()
                .satisfies(n -> assertThat(n.getSubject()).isEqualTo(AuthService.WELCOME_SUBJECT));
        assertThat(rowsOf(user, Channel.EMAIL)).singleElement()
                .satisfies(n -> assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT));
    }

    @Test
    void registrationSucceedsWhenTheWelcomeEmailIsRefused() throws Exception {
        String address = "welcome." + unique() + "@example.test";
        email.failing();

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration(address)))
                .andExpect(status().isCreated());

        User user = registered(address);
        assertThat(rowsOf(user, Channel.IN_APP)).hasSize(1);
        assertThat(rowsOf(user, Channel.EMAIL)).singleElement()
                .satisfies(n -> assertThat(n.getStatus()).isEqualTo(Notification.Status.FAILED));
    }
}
