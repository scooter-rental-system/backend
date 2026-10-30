package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.InvoiceLine;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.BillingService;
import com.scooterrentalkandy.spring.services.PricingService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decision E2 over the API: scooters carry a daily rate and an extra-hour rate, admins must set both, the catalogue
 * and the quote show both, and a multi-day final invoice lists the days and the extra hours separately.
 */
@AutoConfigureMockMvc
@Transactional
class ScooterPricingApiTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private GearItemRepository gearItems;

    @Autowired
    private BillingService billing;

    @Autowired
    private PricingService pricing;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("pricing." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Pricing Rider");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("pricing" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("PR-" + id);
        s.setModel("Pricing Dio");
        s.setPlateNumber("PRP-" + id);
        s.setDailyRate(new BigDecimal("3200.00"));
        s.setHourlyRate(new BigDecimal("320.00"));
        s.setPerKmRate(new BigDecimal("20.00"));
        return scooters.saveAndFlush(s);
    }

    private static String scooterJson(String code, String dailyRateField) {
        return """
                {"code": "%s", "model": "Admin Dio", "plateNumber": "ADP-%s", "engineCc": 110, %s
                 "hourlyRate": 300, "perKmRate": 18, "totalMileage": 0, "imageUrl": "", "description": ""}
                """.formatted(code, code, dailyRateField);
    }

    @Test
    void adminsMustSetAPositiveDailyRateAndGetItBack() throws Exception {
        User admin = saveUser(Role.ADMIN);
        String bearer = "Bearer " + jwt.issue(admin);
        String code = "AD-" + unique();

        mvc.perform(post("/api/admin/scooters").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(scooterJson(code, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dailyRate").exists());
        mvc.perform(post("/api/admin/scooters").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(scooterJson(code, "\"dailyRate\": 0,")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.dailyRate").exists());
        mvc.perform(post("/api/admin/scooters").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(scooterJson(code, "\"dailyRate\": 3000,")))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.dailyRate").value(3000))
                .andExpect(jsonPath("$.hourlyRate").value(300));
    }

    @Test
    void theCatalogueAndTheQuoteShowTheDailyAndExtraHourRates() throws Exception {
        Scooter s = saveScooter();
        mvc.perform(get("/api/scooters/" + s.getScooterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyRate").value(3200.00))
                .andExpect(jsonPath("$.hourlyRate").value(320.00));
        mvc.perform(post("/api/bookings/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scooterId\": \"%s\", \"gear\": []}".formatted(s.getScooterId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyRate").value(3200.00))
                .andExpect(jsonPath("$.hourlyRate").value(320.00))
                .andExpect(jsonPath("$.initialCharge").value(320.00));
    }

    /**
     * Regression for the 500 seen in manual testing: a customer books a scooter with gear through the real endpoint,
     * and the response maps the scooter's daily and extra-hour rates. The 500 came from a server still running the
     * pre-V16 classes while newer ones were compiled into its class folder (NoSuchMethodError on
     * Scooter.getDailyRate in BookingMapper); this checks the whole path on one consistent build.
     */
    @Test
    void aCustomerBooksAScooterWithGearAndSeesBothRates() throws Exception {
        User customer = saveUser(Role.USER);
        Scooter s = saveScooter();
        GearItem tent = new GearItem();
        tent.setName("Tent " + unique());
        tent.setCategory(Category.CAMPING);
        tent.setDailyRate(new BigDecimal("1500.00"));
        tent.setTotalQuantity(3);
        gearItems.saveAndFlush(tent);
        String start = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES).toString();

        mvc.perform(post("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy office",
                                 "gear": [{"gearItemId": "%s", "quantity": 2}], "notes": ""}"""
                                .formatted(s.getScooterId(), start, tent.getGearItemId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.scooter.dailyRate").value(3200.00))
                .andExpect(jsonPath("$.scooter.hourlyRate").value(320.00))
                .andExpect(jsonPath("$.gear[0].quantity").value(2))
                // The one-hour minimum (320) plus one day of two tents (3,000).
                .andExpect(jsonPath("$.initialCharge").value(3320.00));
    }

    @Test
    void aMultiDayInvoiceListsTheDaysAndTheExtraHours() {
        User customer = saveUser(Role.USER);
        Scooter s = saveScooter();
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 9, 0);
        LocalDateTime end = start.plusHours(51);
        PricingService.FinalBill bill = pricing.finalBill(s, List.of(), start, end, new BigDecimal("260"));

        Booking b = new Booking();
        b.setReference("BK-P" + unique());
        b.setUser(customer);
        b.setScooter(s);
        b.setStatus(BookingStatus.COMPLETED);
        b.setStartTime(start);
        b.setEndTime(end);
        b.setDistanceKm(bill.distanceKm());
        b.setTotalCost(bill.total());
        b.setContractSigned(true);
        b.setPickupLocation("Test pickup");
        bookings.saveAndFlush(b);

        Invoice inv = billing.issueFinalInvoice(b, bill);

        // 51 hours = 2 days x 3,200 + 3 extra hours x 320; 260 km - 240 km included = 20 km x 20.
        assertThat(inv.getTotal()).isEqualByComparingTo("7760.00");
        assertThat(inv.getLines()).extracting(InvoiceLine::getDescription)
                .containsExactly("Scooter Pricing Dio (" + s.getPlateNumber() + "): 2 days",
                        "Scooter Pricing Dio (" + s.getPlateNumber() + "): 3 extra hours",
                        "Distance from GPS: 260.00 km, 240.00 km included; excess km");
        assertThat(inv.getLines().get(0).getAmount()).isEqualByComparingTo("6400.00");
        assertThat(inv.getLines().get(1).getAmount()).isEqualByComparingTo("960.00");
        assertThat(inv.getLines().get(2).getQuantity()).isEqualByComparingTo("20.00");
        assertThat(inv.getLines().get(2).getAmount()).isEqualByComparingTo("400.00");
    }
}
