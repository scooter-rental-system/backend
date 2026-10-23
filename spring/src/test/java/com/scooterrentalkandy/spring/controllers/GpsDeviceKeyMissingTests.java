package com.scooterrentalkandy.spring.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** With no device key configured (as in production until GPS_DEVICE_API_KEY is set), device reports are refused. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.gps.device-api-key=")
class GpsDeviceKeyMissingTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void everyDeviceReportIsRefused() throws Exception {
        String body = """
                {"scooterCode": "ANY", "latitude": 7.29, "longitude": 80.63, "speedKmh": 10}""";
        for (String key : new String[] {"", "test-device-key-not-a-secret", "anything"}) {
            mvc.perform(post("/api/gps/pings").contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-Device-Key", key))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/gps/pings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
