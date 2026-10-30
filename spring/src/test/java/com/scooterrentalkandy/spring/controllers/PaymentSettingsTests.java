package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.PaymentSettings;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.PaymentSettingsRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin payment settings for QR payments and bank transfers (BE-045): only ADMIN and SUPER_ADMIN change them or see
 * the uploaded QR image; signed-in customers see only the enabled methods; nobody signed out sees anything. The QR
 * image is checked like a repair photo. Each test starts with both methods disabled and nothing configured.
 */
@AutoConfigureMockMvc
@Transactional
class PaymentSettingsTests extends PostgresIntegrationTest {

    static final String QR_ON = """
            {"qrEnabled": true, "qrDisplayName": "Scooter Rent Kandy", "qrInstructions": "Scan with any LankaQR app",
             "bankEnabled": false}""";
    static final String BANK_ON = """
            {"qrEnabled": false, "bankEnabled": true, "bankName": "Bank of Ceylon",
             "bankAccountHolder": "Scooter Rent Kandy (Pvt) Ltd", "bankAccountNumber": "0001 2345 6789",
             "bankBranch": "Kandy City", "bankBranchCode": "004", "bankInstructions": "Use the booking reference"}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PaymentSettingsRepository settings;

    @Autowired
    private EntityManager em;

    @Autowired
    private JwtService jwt;

    private User customer;
    private User admin;

    @BeforeEach
    void setUp() {
        PaymentSettings s = settings.findById(PaymentSettings.ID).orElseThrow();
        s.setQrEnabled(false);
        s.setBankEnabled(false);
        s.setQrImageKey(null);
        s.setQrImageType(null);
        s.setQrImageSize(null);
        s.setQrDisplayName(null);
        s.setBankAccountNumber(null);
        settings.saveAndFlush(s);
        customer = saveUser(Role.USER);
        admin = saveUser(Role.ADMIN);
    }

    private User saveUser(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("settings." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Settings User " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("set" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(User user, B request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private ResultActions save(User who, String body) throws Exception {
        return mvc.perform(as(who, put("/api/admin/payment-settings")).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions upload(User who, String name, String type, byte[] bytes) throws Exception {
        return mvc.perform(as(who, multipart("/api/admin/payment-settings/qr-image")
                .file(new MockMultipartFile("image", name, type, bytes))));
    }

    @Test
    void anAdminUploadsTheQrImageAndEnablesQrPayments() throws Exception {
        upload(admin, "qr.png", "image/png", RepairPhotoTests.PNG).andExpect(status().isOk())
                .andExpect(jsonPath("$.qrImageUploaded").value(true))
                .andExpect(jsonPath("$.qrImageKey").doesNotExist());
        save(admin, QR_ON).andExpect(status().isOk())
                .andExpect(jsonPath("$.qrEnabled").value(true))
                .andExpect(jsonPath("$.qrDisplayName").value("Scooter Rent Kandy"))
                .andExpect(jsonPath("$.updatedBy").value(admin.getFullName()));

        mvc.perform(as(customer, get("/api/payments/options"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.qr.displayName").value("Scooter Rent Kandy"))
                .andExpect(jsonPath("$.qr.instructions").value("Scan with any LankaQR app"))
                .andExpect(jsonPath("$.bank").doesNotExist())
                .andExpect(jsonPath("$.paymentHoldMinutes").value(30))
                .andExpect(jsonPath("$.verificationHoldMinutes").value(120));
        mvc.perform(as(customer, get("/api/payments/options/qr-image"))).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(RepairPhotoTests.PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void anAdminConfiguresBankTransferDetails() throws Exception {
        save(admin, BANK_ON).andExpect(status().isOk())
                .andExpect(jsonPath("$.bankEnabled").value(true))
                .andExpect(jsonPath("$.bankAccountNumber").value("0001 2345 6789"));

        mvc.perform(as(customer, get("/api/payments/options"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.qr").doesNotExist())
                .andExpect(jsonPath("$.bank.bankName").value("Bank of Ceylon"))
                .andExpect(jsonPath("$.bank.accountHolder").value("Scooter Rent Kandy (Pvt) Ltd"))
                .andExpect(jsonPath("$.bank.accountNumber").value("0001 2345 6789"))
                .andExpect(jsonPath("$.bank.branch").value("Kandy City"))
                .andExpect(jsonPath("$.bank.branchCode").value("004"))
                .andExpect(jsonPath("$.bank.updatedBy").doesNotExist());
    }

    @Test
    void aSuperAdminCanChangeTheSettingsToo() throws Exception {
        save(saveUser(Role.SUPER_ADMIN), BANK_ON).andExpect(status().isOk());
    }

    @Test
    void aCustomerCannotChangeOrReadTheAdminSettings() throws Exception {
        save(customer, QR_ON).andExpect(status().isForbidden());
        save(customer, BANK_ON).andExpect(status().isForbidden());
        upload(customer, "qr.png", "image/png", RepairPhotoTests.PNG).andExpect(status().isForbidden());
        mvc.perform(as(customer, get("/api/admin/payment-settings"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, get("/api/admin/payment-settings/qr-image"))).andExpect(status().isForbidden());

        em.clear();
        PaymentSettings s = settings.findById(PaymentSettings.ID).orElseThrow();
        assertThat(s.isQrEnabled()).isFalse();
        assertThat(s.isBankEnabled()).isFalse();
        assertThat(s.getQrImageKey()).isNull();
    }

    @Test
    void nothingIsAvailableSignedOut() throws Exception {
        mvc.perform(put("/api/admin/payment-settings").contentType(MediaType.APPLICATION_JSON).content(BANK_ON))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/payment-settings/qr-image")
                        .file(new MockMultipartFile("image", "qr.png", "image/png", RepairPhotoTests.PNG)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/options")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/options/qr-image")).andExpect(status().isUnauthorized());
    }

    @Test
    void aDisabledMethodIsNotOffered() throws Exception {
        upload(admin, "qr.png", "image/png", RepairPhotoTests.PNG).andExpect(status().isOk());
        save(admin, QR_ON.replace("\"qrEnabled\": true", "\"qrEnabled\": false")).andExpect(status().isOk());

        mvc.perform(as(customer, get("/api/payments/options"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.qr").doesNotExist())
                .andExpect(jsonPath("$.bank").doesNotExist());
        mvc.perform(as(customer, get("/api/payments/options/qr-image"))).andExpect(status().isNotFound());
        // The admin still sees what was uploaded.
        mvc.perform(as(admin, get("/api/admin/payment-settings/qr-image"))).andExpect(status().isOk());
    }

    @Test
    void aMethodCanBeEnabledOnlyWithItsDetails() throws Exception {
        save(admin, QR_ON).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Upload the QR image before enabling QR payments"));
        save(admin, BANK_ON.replace("\"0001 2345 6789\"", "null")).andExpect(status().isBadRequest());
        save(admin, BANK_ON.replace("0001 2345 6789", "12345<script>")).andExpect(status().isBadRequest());
        save(admin, BANK_ON.replace("\"004\"", "\"../x\"")).andExpect(status().isBadRequest());
        save(admin, BANK_ON.replace("\"Kandy City\"", "\"" + "K".repeat(101) + "\"")).andExpect(status().isBadRequest());

        em.clear();
        assertThat(settings.findById(PaymentSettings.ID).orElseThrow().isBankEnabled()).isFalse();
    }

    @Test
    void theQrImageMustBeARealImage() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] html = "<!DOCTYPE html><html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] exe = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00};
        upload(admin, "qr.svg", "image/svg+xml", svg).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The QR image must be a JPEG, PNG or WebP image."));
        upload(admin, "qr.png", "image/png", html).andExpect(status().isBadRequest());
        upload(admin, "qr.png", "image/png", exe).andExpect(status().isBadRequest());
        upload(admin, "qr.jpg", "image/jpeg", RepairPhotoTests.PNG).andExpect(status().isBadRequest());
        upload(admin, "qr.png", "image/png", Arrays.copyOf(RepairPhotoTests.PNG, 10)).andExpect(status().isBadRequest());
        upload(admin, "qr.png", "image/png", new byte[5 * 1024 * 1024 + 1]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The QR image must be 5 MB or smaller."));

        em.clear();
        assertThat(settings.findById(PaymentSettings.ID).orElseThrow().getQrImageKey()).isNull();
    }

    @Test
    void replacingTheQrImageServesTheNewOne() throws Exception {
        upload(admin, "first.png", "image/png", RepairPhotoTests.PNG).andExpect(status().isOk());
        upload(admin, "second.webp", "image/webp", RepairPhotoTests.WEBP).andExpect(status().isOk());

        mvc.perform(as(admin, get("/api/admin/payment-settings/qr-image"))).andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(RepairPhotoTests.WEBP));
        em.clear();
        assertThat(settings.findById(PaymentSettings.ID).orElseThrow().getQrImageKey())
                .matches("^[0-9a-f-]{36}\\.webp$");
    }
}
