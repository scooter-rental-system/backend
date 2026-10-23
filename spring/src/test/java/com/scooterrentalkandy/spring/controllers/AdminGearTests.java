package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
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
 * The admin gear endpoints and the public gear list over HTTP, through the real security filter chain, JWT
 * handling and embedded PostgreSQL. Users and gear are synthetic and each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class AdminGearTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private GearItemRepository gearItems;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("gearadmin." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Gear Admin " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("A" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private GearItem saveGear(int stock, boolean active) {
        GearItem g = new GearItem();
        g.setName("Stored Gear " + unique());
        g.setCategory(Category.SAFETY);
        g.setDailyRate(new BigDecimal("300.00"));
        g.setTotalQuantity(stock);
        g.setActive(active);
        return gearItems.saveAndFlush(g);
    }

    private MockHttpServletRequestBuilder as(Role role, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(saveUser(role)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String gear(String name, String rate, int stock, boolean active) {
        return """
                {"name": "%s", "category": "CAMPING", "description": "Test gear", "dailyRate": %s,
                 "totalQuantity": %d, "active": %s}""".formatted(name, rate, stock, active);
    }

    // ── Create and update ─────────────────────────────────────────────────────

    @Test
    void adminsCreateGear() throws Exception {
        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            String name = "New Tent " + unique();
            String body = mvc.perform(json(as(role, post("/api/admin/gear")), gear(name, "1500.00", 6, true)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value(name))
                    .andExpect(jsonPath("$.category").value("CAMPING"))
                    .andExpect(jsonPath("$.dailyRate").value(1500.00))
                    .andExpect(jsonPath("$.totalQuantity").value(6))
                    .andExpect(jsonPath("$.active").value(true))
                    .andReturn().getResponse().getContentAsString();

            GearItem saved = gearItems.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow();
            assertThat(saved.getTotalQuantity()).isEqualTo(6);
        }
    }

    @Test
    void stockOfZeroIsAccepted() throws Exception {
        mvc.perform(json(as(Role.ADMIN, post("/api/admin/gear")), gear("Sold Out " + unique(), "500.00", 0, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    @Test
    void adminsUpdateGear() throws Exception {
        GearItem item = saveGear(3, true);
        String renamed = "Renamed " + unique();

        mvc.perform(json(as(Role.SUPER_ADMIN, put("/api/admin/gear/" + item.getGearItemId())),
                        gear(renamed, "450.00", 8, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(item.getGearItemId().toString()))
                .andExpect(jsonPath("$.name").value(renamed))
                .andExpect(jsonPath("$.dailyRate").value(450.00))
                .andExpect(jsonPath("$.totalQuantity").value(8));

        GearItem updated = gearItems.findById(item.getGearItemId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo(renamed);
        assertThat(updated.getTotalQuantity()).isEqualTo(8);
    }

    @Test
    void updatingUnknownGearIs404() throws Exception {
        mvc.perform(json(as(Role.ADMIN, put("/api/admin/gear/" + UUID.randomUUID())),
                        gear("Ghost " + unique(), "300.00", 1, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Gear item not found"));
    }

    @Test
    void invalidGearRequestsAre400() throws Exception {
        mvc.perform(json(as(Role.ADMIN, post("/api/admin/gear")), gear(" ", "300.00", 1, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        mvc.perform(json(as(Role.ADMIN, post("/api/admin/gear")), gear("Negative " + unique(), "300.00", -1, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.totalQuantity").exists());
        mvc.perform(json(as(Role.ADMIN, post("/api/admin/gear")), gear("Negative " + unique(), "-1.00", 1, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dailyRate").exists());
    }

    // ── Active state ──────────────────────────────────────────────────────────

    @Test
    void inactiveGearIsHiddenFromCustomersButListedForAdmins() throws Exception {
        GearItem active = saveGear(4, true);
        GearItem inactive = saveGear(4, false);

        mvc.perform(get("/api/gear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(active.getGearItemId().toString())))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(inactive.getGearItemId().toString()))));
        mvc.perform(as(Role.ADMIN, get("/api/admin/gear")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(inactive.getGearItemId().toString())));
    }

    @Test
    void gearCanBeDeactivatedAndReactivated() throws Exception {
        GearItem item = saveGear(2, true);
        String path = "/api/admin/gear/" + item.getGearItemId();

        mvc.perform(json(as(Role.ADMIN, put(path)), gear(item.getName(), "300.00", 2, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(json(as(Role.ADMIN, put(path)), gear(item.getName(), "300.00", 2, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    // ── Access ────────────────────────────────────────────────────────────────

    @Test
    void customersAndAnonymousUsersCannotManageGear() throws Exception {
        GearItem item = saveGear(2, true);
        String body = gear("Hijack " + unique(), "1.00", 99, true);

        mvc.perform(as(Role.USER, get("/api/admin/gear"))).andExpect(status().isForbidden());
        mvc.perform(json(as(Role.USER, post("/api/admin/gear")), body)).andExpect(status().isForbidden());
        mvc.perform(json(as(Role.USER, put("/api/admin/gear/" + item.getGearItemId())), body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/gear")).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/admin/gear"), body)).andExpect(status().isUnauthorized());
        mvc.perform(json(put("/api/admin/gear/" + item.getGearItemId()), body)).andExpect(status().isUnauthorized());

        assertThat(gearItems.findById(item.getGearItemId()).orElseThrow().getTotalQuantity()).isEqualTo(2);
    }
}
