package com.scooterrentalkandy.spring.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Without the {@code demo} profile (the normal application) viva demo mode does not exist at all. */
@AutoConfigureMockMvc
@Transactional
class DemoModeAbsentTests extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwt;

    private User saveUser(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("nodemo." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("No Demo " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("nodemo" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    @Test
    void noDemoBeanIsLoaded() {
        assertThat(context.getBeanNamesForType(DemoMode.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoLifecycleService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoController.class)).isEmpty();
    }

    @Test
    void theDemoEndpointsDoNotExist() throws Exception {
        User admin = saveUser(Role.ADMIN);
        // 404 for a signed-in user (not 401), so the app's demo check never signs anyone out.
        mvc.perform(as(saveUser(Role.USER), get("/api/demo/status"))).andExpect(status().isNotFound());
        for (String action : List.of("activate", "end", "close")) {
            mvc.perform(as(admin, post("/api/admin/demo/bookings/" + UUID.randomUUID() + "/" + action)))
                    .andExpect(status().isNotFound());
        }
    }
}
