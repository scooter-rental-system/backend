package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin customer endpoints over HTTP, through the real security filter chain, JWT handling and embedded
 * PostgreSQL. Users are synthetic and each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class AdminCustomerControllerTests extends PostgresIntegrationTest {

    private static final String CUSTOMERS = "/api/admin/customers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwt;

    private User saveUser(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User u = new User();
        u.setEmail("customer." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Customer " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("C" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder setActive(UUID id, boolean value) {
        return patch(CUSTOMERS + "/" + id + "/active").param("value", String.valueOf(value));
    }

    // ── Admin access ──────────────────────────────────────────────────────────

    @Test
    void adminAndSuperAdminListCustomersOnly() throws Exception {
        User customer = saveUser(Role.USER);
        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            User admin = saveUser(role);

            mvc.perform(as(admin, get(CUSTOMERS)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id").value(hasItem(customer.getUserId().toString())))
                    .andExpect(jsonPath("$[*].id").value(not(hasItem(admin.getUserId().toString()))))
                    .andExpect(jsonPath("$[*].role").value(not(hasItem("ADMIN"))))
                    .andExpect(jsonPath("$[*].passwordHash").isEmpty())
                    .andExpect(jsonPath("$[*].nicNumber").isEmpty());
        }
    }

    @Test
    void adminGetsOneCustomer() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User customer = saveUser(Role.USER);

        mvc.perform(as(admin, get(CUSTOMERS + "/" + customer.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer.getUserId().toString()))
                .andExpect(jsonPath("$.email").value(customer.getEmail()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.nicNumber").doesNotExist());
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        mvc.perform(as(saveUser(Role.ADMIN), get(CUSTOMERS + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found"));
        mvc.perform(as(saveUser(Role.ADMIN), setActive(UUID.randomUUID(), false)))
                .andExpect(status().isNotFound());
    }

    /** Current behaviour: the single-customer lookup does not check the role, so it also returns admin accounts. */
    @Test
    void getByIdAlsoReturnsAdminAccounts() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User otherAdmin = saveUser(Role.ADMIN);

        mvc.perform(as(admin, get(CUSTOMERS + "/" + otherAdmin.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void adminDeactivatesAndReactivatesACustomer() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User customer = saveUser(Role.USER);

        mvc.perform(as(admin, setActive(customer.getUserId(), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer.getUserId().toString()))
                .andExpect(jsonPath("$.active").value(false));
        assertThat(users.findById(customer.getUserId()).orElseThrow().getIsActive()).isFalse();

        mvc.perform(as(admin, setActive(customer.getUserId(), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(users.findById(customer.getUserId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    void superAdminCanAlsoToggleACustomer() throws Exception {
        User customer = saveUser(Role.USER);

        mvc.perform(as(saveUser(Role.SUPER_ADMIN), setActive(customer.getUserId(), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void adminAccountsCannotBeToggled() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User otherAdmin = saveUser(Role.ADMIN);

        mvc.perform(as(admin, setActive(otherAdmin.getUserId(), false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only customer (USER) accounts can be toggled here"));
        assertThat(users.findById(otherAdmin.getUserId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    void toggleRequiresTheValueParameter() throws Exception {
        User customer = saveUser(Role.USER);

        mvc.perform(as(saveUser(Role.ADMIN), patch(CUSTOMERS + "/" + customer.getUserId() + "/active")))
                .andExpect(status().isBadRequest());
    }

    // ── Boundaries ────────────────────────────────────────────────────────────

    @Test
    void customersCannotUseTheAdminEndpoints() throws Exception {
        User customer = saveUser(Role.USER);
        User other = saveUser(Role.USER);

        mvc.perform(as(customer, get(CUSTOMERS))).andExpect(status().isForbidden());
        mvc.perform(as(customer, get(CUSTOMERS + "/" + other.getUserId()))).andExpect(status().isForbidden());
        mvc.perform(as(customer, setActive(customer.getUserId(), true))).andExpect(status().isForbidden());
        mvc.perform(as(customer, setActive(other.getUserId(), false))).andExpect(status().isForbidden());
        assertThat(users.findById(other.getUserId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    void requestsWithoutATokenAre401() throws Exception {
        UUID id = saveUser(Role.USER).getUserId();

        mvc.perform(get(CUSTOMERS)).andExpect(status().isUnauthorized());
        mvc.perform(get(CUSTOMERS + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(setActive(id, false)).andExpect(status().isUnauthorized());
    }

    // ── Deactivation and existing tokens (BE-027) ─────────────────────────────

    @Test
    void deactivationThroughTheAdminApiEndsTheCustomersExistingToken() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User customer = saveUser(Role.USER);
        String token = jwt.issue(customer);
        MockHttpServletRequestBuilder me = get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        mvc.perform(me).andExpect(status().isOk());

        mvc.perform(as(admin, setActive(customer.getUserId(), false))).andExpect(status().isOk());

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // Reactivating restores access with the same, still unexpired token.
        mvc.perform(as(admin, setActive(customer.getUserId(), true))).andExpect(status().isOk());
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }
}
