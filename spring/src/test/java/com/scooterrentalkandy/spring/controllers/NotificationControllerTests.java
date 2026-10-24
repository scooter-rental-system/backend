package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Notification.Channel;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.NotificationService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notification API over HTTP, through the real security filter chain, JWT handling, service and embedded
 * PostgreSQL. Each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class NotificationControllerTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private NotificationService service;

    @Autowired
    private JwtService jwt;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role, boolean active) {
        String id = unique();
        User u = new User();
        u.setEmail("notify." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Notify " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("N" + id);
        u.setRole(role);
        u.setIsActive(active);
        return users.saveAndFlush(u);
    }

    private User saveUser(Role role) {
        return saveUser(role, true);
    }

    private Notification stored(User user, Channel channel, String subject, LocalDateTime createdAt,
                                LocalDateTime readAt) {
        Notification n = new Notification();
        n.setUser(user);
        n.setChannel(channel);
        n.setSubject(subject);
        n.setMessage(subject + " body");
        n.setStatus(Notification.Status.SENT);
        n.setCreatedAt(createdAt);
        n.setReadAt(readAt);
        return notifications.saveAndFlush(n);
    }

    private Notification inApp(User user, String subject, LocalDateTime createdAt) {
        return stored(user, Channel.IN_APP, subject, createdAt, null);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private MockHttpServletRequestBuilder broadcast(User admin, String body) {
        return as(admin, post("/api/admin/notifications/broadcast")).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Notification reload(Notification n) {
        return notifications.findById(n.getNotificationId()).orElseThrow();
    }

    private List<Notification> inAppOf(User user) {
        return notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(user.getUserId(), Channel.IN_APP);
    }

    // ---------------------------------------------------------------- customer inbox

    @Test
    void inboxListsOnlyTheCallersInAppNotificationsNewestFirst() throws Exception {
        User me = saveUser(Role.USER);
        User other = saveUser(Role.USER);
        Notification older = inApp(me, "Older", now.minusHours(2));
        Notification newer = stored(me, Channel.IN_APP, "Newer", now.minusMinutes(5), now.minusMinutes(1));
        stored(me, Channel.EMAIL, "Email copy", now, null);
        inApp(other, "Someone else's", now);

        mvc.perform(as(me, get("/api/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(newer.getNotificationId().toString()))
                .andExpect(jsonPath("$[0].subject").value("Newer"))
                .andExpect(jsonPath("$[0].message").value("Newer body"))
                .andExpect(jsonPath("$[0].read").value(true))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[1].id").value(older.getNotificationId().toString()))
                .andExpect(jsonPath("$[1].read").value(false))
                // No delivery details or owner details in the customer view.
                .andExpect(jsonPath("$[0].recipient").doesNotExist())
                .andExpect(jsonPath("$[0].channel").doesNotExist());
    }

    @Test
    void anEmptyInboxIsAnEmptyList() throws Exception {
        mvc.perform(as(saveUser(Role.USER), get("/api/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unreadCountCountsOnlyTheCallersUnreadInAppNotifications() throws Exception {
        User me = saveUser(Role.USER);
        User other = saveUser(Role.USER);
        inApp(me, "One", now.minusMinutes(3));
        inApp(me, "Two", now.minusMinutes(2));
        stored(me, Channel.IN_APP, "Read", now.minusMinutes(1), now);
        stored(me, Channel.EMAIL, "Email copy", now, null);
        inApp(other, "Other", now);

        mvc.perform(as(me, get("/api/notifications/unread-count")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void markingReadIsHarmlessToRepeat() throws Exception {
        User me = saveUser(Role.USER);
        Notification n = inApp(me, "Hello", now.minusMinutes(1));

        mvc.perform(as(me, post("/api/notifications/{id}/read", n.getNotificationId())))
                .andExpect(status().isNoContent());
        LocalDateTime firstRead = reload(n).getReadAt();
        assertThat(firstRead).isNotNull();

        mvc.perform(as(me, post("/api/notifications/{id}/read", n.getNotificationId())))
                .andExpect(status().isNoContent());
        assertThat(reload(n).getReadAt()).isEqualTo(firstRead);
        mvc.perform(as(me, get("/api/notifications/unread-count"))).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void anotherUsersNotificationIsNotFoundAndStaysUnread() throws Exception {
        User owner = saveUser(Role.USER);
        User intruder = saveUser(Role.USER);
        Notification n = inApp(owner, "Private", now);

        mvc.perform(as(intruder, post("/api/notifications/{id}/read", n.getNotificationId())))
                .andExpect(status().isNotFound());
        // An admin has no business marking a customer's notification either.
        mvc.perform(as(saveUser(Role.ADMIN), post("/api/notifications/{id}/read", n.getNotificationId())))
                .andExpect(status().isNotFound());
        assertThat(reload(n).getReadAt()).isNull();
        mvc.perform(as(intruder, get("/api/notifications"))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unknownIdsAndDeliveryRecordsCannotBeMarkedRead() throws Exception {
        User me = saveUser(Role.USER);
        Notification email = stored(me, Channel.EMAIL, "Email copy", now, null);

        mvc.perform(as(me, post("/api/notifications/{id}/read", UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(as(me, post("/api/notifications/{id}/read", email.getNotificationId())))
                .andExpect(status().isNotFound());
        assertThat(reload(email).getReadAt()).isNull();
    }

    @Test
    void markAllReadTouchesOnlyTheCallersUnreadInAppNotifications() throws Exception {
        User me = saveUser(Role.USER);
        User other = saveUser(Role.USER);
        Notification a = inApp(me, "A", now.minusMinutes(2));
        Notification b = inApp(me, "B", now.minusMinutes(1));
        LocalDateTime earlier = now.minusDays(1);
        Notification alreadyRead = stored(me, Channel.IN_APP, "Old", now.minusDays(2), earlier);
        Notification email = stored(me, Channel.EMAIL, "Email copy", now, null);
        Notification others = inApp(other, "Other", now);

        mvc.perform(as(me, post("/api/notifications/read-all"))).andExpect(status().isNoContent());

        assertThat(reload(a).getReadAt()).isNotNull();
        assertThat(reload(b).getReadAt()).isNotNull();
        assertThat(reload(alreadyRead).getReadAt()).isEqualTo(earlier);
        assertThat(reload(email).getReadAt()).isNull();
        assertThat(reload(others).getReadAt()).isNull();
        mvc.perform(as(me, get("/api/notifications/unread-count"))).andExpect(jsonPath("$.count").value(0));
        // Repeating it is harmless.
        mvc.perform(as(me, post("/api/notifications/read-all"))).andExpect(status().isNoContent());
    }

    @Test
    void everyNotificationEndpointNeedsAToken() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/{id}/read", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/notifications/broadcast").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"S\",\"message\":\"M\"}")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- admin log

    @Test
    void customersCannotUseTheAdminEndpoints() throws Exception {
        User customer = saveUser(Role.USER);
        long before = notifications.count();

        mvc.perform(as(customer, get("/api/admin/notifications"))).andExpect(status().isForbidden());
        mvc.perform(broadcast(customer, "{\"subject\":\"S\",\"message\":\"M\"}"))
                .andExpect(status().isForbidden());
        assertThat(notifications.count()).isEqualTo(before);
    }

    @Test
    void adminsSeeTheDeliveryLogWithoutMessageBodies() throws Exception {
        User customer = saveUser(Role.USER);
        Notification n = stored(customer, Channel.IN_APP, "Log entry " + unique(), now.plusYears(50), null);

        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            mvc.perform(as(saveUser(role), get("/api/admin/notifications")))
                    .andExpect(status().isOk())
                    // Newest first: the far-future fixture is on top.
                    .andExpect(jsonPath("$[0].id").value(n.getNotificationId().toString()))
                    .andExpect(jsonPath("$[0].recipient").value(customer.getEmail()))
                    .andExpect(jsonPath("$[0].channel").value("IN_APP"))
                    .andExpect(jsonPath("$[0].subject").value(n.getSubject()))
                    .andExpect(jsonPath("$[0].status").value("SENT"))
                    .andExpect(jsonPath("$[0].createdAt").exists())
                    .andExpect(jsonPath("$[0].message").doesNotExist());
        }
    }

    // ---------------------------------------------------------------- broadcast

    @Test
    void broadcastReachesEveryActiveCustomerInAppAndNobodyElse() throws Exception {
        User active = saveUser(Role.USER);
        User inactive = saveUser(Role.USER, false);
        User admin = saveUser(Role.ADMIN);
        User superAdmin = saveUser(Role.SUPER_ADMIN);
        int activeCustomers = users.findByRoleAndIsActiveTrue(Role.USER).size();

        mvc.perform(broadcast(admin, "{\"subject\":\"  Road closure  \",\"message\":\"Peradeniya Road is closed today.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients").value(activeCustomers));

        assertThat(inAppOf(active)).singleElement().satisfies(n -> {
            assertThat(n.getSubject()).isEqualTo("Road closure");
            assertThat(n.getMessage()).isEqualTo("Peradeniya Road is closed today.");
            assertThat(n.getReadAt()).isNull();
        });
        assertThat(inAppOf(inactive)).isEmpty();
        assertThat(inAppOf(admin)).isEmpty();
        assertThat(inAppOf(superAdmin)).isEmpty();
        mvc.perform(as(superAdmin, get("/api/admin/notifications"))).andExpect(status().isOk());
    }

    @Test
    void broadcastIgnoresClientSuppliedRecipients() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User otherAdmin = saveUser(Role.ADMIN);

        mvc.perform(broadcast(admin, "{\"subject\":\"S\",\"message\":\"M\",\"userId\":\""
                        + otherAdmin.getUserId() + "\",\"email\":\"attacker@example.test\","
                        + "\"recipients\":[\"" + otherAdmin.getUserId() + "\"]}"))
                .andExpect(status().isOk());
        assertThat(inAppOf(otherAdmin)).isEmpty();
    }

    @Test
    void invalidBroadcastsAreRejectedWithoutNotifyingAnyone() throws Exception {
        User admin = saveUser(Role.ADMIN);
        long before = notifications.count();
        String subject256 = "s".repeat(256);
        String message4001 = "m".repeat(4001);

        for (String body : List.of(
                "{\"subject\":\"\",\"message\":\"M\"}",
                "{\"subject\":\"   \",\"message\":\"M\"}",
                "{\"subject\":\"S\",\"message\":\"\"}",
                "{\"message\":\"M\"}",
                "{\"subject\":\"S\"}",
                "{\"subject\":\"" + subject256 + "\",\"message\":\"M\"}",
                "{\"subject\":\"S\",\"message\":\"" + message4001 + "\"}")) {
            mvc.perform(broadcast(admin, body)).andExpect(status().isBadRequest());
        }
        assertThat(notifications.count()).isEqualTo(before);
    }

    @Test
    void broadcastAcceptsTheDatabaseLimitsExactly() throws Exception {
        User customer = saveUser(Role.USER);

        mvc.perform(broadcast(saveUser(Role.ADMIN), "{\"subject\":\"" + "s".repeat(255) + "\",\"message\":\""
                        + "m".repeat(4000) + "\"}"))
                .andExpect(status().isOk());
        assertThat(inAppOf(customer)).singleElement()
                .satisfies(n -> assertThat(n.getMessage()).hasSize(4000));
    }

    // ---------------------------------------------------------------- inactive users

    @Test
    void inactiveUsersReceiveNoNewNotifications() {
        User inactive = saveUser(Role.USER, false);
        long before = notifications.count();

        service.notify(inactive, "Booking confirmed", "Should not be delivered");

        assertThat(notifications.count()).isEqualTo(before);
    }

    @Test
    void inactiveAdminsReceiveNoAdminNotifications() {
        User active = saveUser(Role.ADMIN);
        User inactive = saveUser(Role.SUPER_ADMIN, false);
        User superAdmin = saveUser(Role.SUPER_ADMIN);

        service.notifyAdmins("Service due: S-1", "Due for a service.");

        assertThat(inAppOf(active)).hasSize(1);
        assertThat(inAppOf(superAdmin)).hasSize(1);
        assertThat(inAppOf(inactive)).isEmpty();
    }
}
