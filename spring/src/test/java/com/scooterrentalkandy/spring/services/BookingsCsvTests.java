package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.BookingGear;
import com.scooterrentalkandy.spring.entity.GearItem;
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
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** The admin bookings CSV: the agreed columns, real values only, RFC 4180 quoting and formula-injection protection. */
@AutoConfigureMockMvc
@Transactional
class BookingsCsvTests extends PostgresIntegrationTest {

    @Autowired
    private ReportService reports;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private GearItemRepository gearItems;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private User saveUser(String name, Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("csv." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName(name);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("csv" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("CSV-" + id);
        s.setModel("Honda Dio");
        s.setPlateNumber("CSVP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    private Booking booking(User customer, Scooter s, BookingStatus status) {
        Booking b = new Booking();
        b.setReference("BK-CSV" + unique());
        b.setUser(customer);
        b.setScooter(s);
        b.setStatus(status);
        b.setStartTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        b.setContractSigned(true);
        b.setPickupLocation("Kandy office");
        return b;
    }

    /** Splits one CSV record into its cells (quoted cells may contain commas, quotes and line breaks). */
    private static List<String> cells(String record) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < record.length() && record.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }

    private static String row(String csv, String reference) {
        int start = csv.indexOf("\"" + reference + "\"");
        assertThat(start).as("row for " + reference).isNotNegative();
        // A record ends at the first newline outside quotes.
        boolean quoted = false;
        int i = start;
        for (; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '\n' && !quoted) {
                break;
            }
        }
        return csv.substring(start, i);
    }

    private String exportToday() {
        LocalDate today = LocalDate.now();
        return reports.bookingsCsv(today.minusDays(1), today.plusDays(1));
    }

    @Test
    void hasTheAgreedHeader() {
        String header = exportToday().lines().findFirst().orElseThrow();
        assertThat(Arrays.asList(header.split(","))).containsExactlyElementsOf(ReportService.CSV_COLUMNS);
        assertThat(ReportService.CSV_COLUMNS).contains("customer_nic", "daily_rate", "extra_hour_rate", "charged_days",
                "extra_hours", "time_charge", "included_km", "excess_km", "excess_km_rate", "distance_charge", "gear",
                "gear_charge", "total_amount", "payment_status");
    }

    @Test
    void aCompletedRentalHasItsFullBreakdown() {
        User customer = saveUser("Nimal Perera", Role.USER);
        Scooter s = saveScooter();
        GearItem tent = new GearItem();
        tent.setName("2-person tent");
        tent.setCategory(Category.CAMPING);
        tent.setDailyRate(new BigDecimal("1500.00"));
        tent.setTotalQuantity(5);
        gearItems.saveAndFlush(tent);

        Booking b = booking(customer, s, BookingStatus.COMPLETED);
        b.setEndTime(b.getStartTime().plusHours(27));
        b.setDistanceKm(new BigDecimal("150.00"));
        // 1 day + 3 extra hours = 3,250; 30 km over 120 at 15 = 450; 1 tent day = 1,500.
        b.setTotalCost(new BigDecimal("5200.00"));
        BookingGear line = new BookingGear();
        line.setGearItem(tent);
        line.setQuantity(1);
        b.addGear(line);
        bookings.saveAndFlush(b);

        List<String> c = cells(row(exportToday(), b.getReference()));
        assertThat(c).hasSize(ReportService.CSV_COLUMNS.size());
        assertThat(value(c, "customer_name")).isEqualTo("Nimal Perera");
        assertThat(value(c, "customer_nic")).isEqualTo(customer.getNicNumber());
        assertThat(value(c, "scooter_model")).isEqualTo("Honda Dio");
        assertThat(value(c, "status")).isEqualTo("COMPLETED");
        assertThat(value(c, "daily_rate")).isEqualTo("2500.00");
        assertThat(value(c, "extra_hour_rate")).isEqualTo("250.00");
        assertThat(value(c, "charged_days")).isEqualTo("1");
        assertThat(value(c, "extra_hours")).isEqualTo("3");
        assertThat(value(c, "time_charge")).isEqualTo("3250.00");
        assertThat(value(c, "total_distance_km")).isEqualTo("150.00");
        assertThat(value(c, "included_km")).isEqualTo("120.00");
        assertThat(value(c, "excess_km")).isEqualTo("30.00");
        assertThat(value(c, "excess_km_rate")).isEqualTo("15.00");
        assertThat(value(c, "distance_charge")).isEqualTo("450.00");
        assertThat(value(c, "gear")).isEqualTo("2-person tent x1");
        assertThat(value(c, "gear_charge")).isEqualTo("1500.00");
        assertThat(value(c, "total_amount")).isEqualTo("5200.00");
    }

    @Test
    void aRentalThatHasNotEndedLeavesTheChargesEmpty() {
        Booking b = booking(saveUser("Open Rider", Role.USER), saveScooter(), BookingStatus.PENDING);
        bookings.saveAndFlush(b);
        List<String> c = cells(row(exportToday(), b.getReference()));
        for (String column : List.of("rental_end", "charged_days", "extra_hours", "time_charge", "included_km",
                "excess_km", "distance_charge", "gear_charge", "total_amount", "amount_paid", "payment_status")) {
            assertThat(value(c, column)).as(column).isEmpty();
        }
        assertThat(value(c, "daily_rate")).isEqualTo("2500.00");
    }

    @Test
    void quotesCommasQuotesAndLineBreaksAndNeutralisesFormulas() {
        assertThat(ReportService.csv("Perera, Nimal")).isEqualTo("\"Perera, Nimal\"");
        assertThat(ReportService.csv("The \"Rider\"")).isEqualTo("\"The \"\"Rider\"\"\"");
        assertThat(ReportService.csv("line one\nline two")).isEqualTo("\"line one\nline two\"");
        assertThat(ReportService.csv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ReportService.csv("+94 70")).startsWith("\"'+");
        assertThat(ReportService.csv("@SUM(A1)")).startsWith("\"'@");
        assertThat(ReportService.csv("\tTAB")).startsWith("\"'\t");
        assertThat(ReportService.csv(null)).isEmpty();

        Booking b = booking(saveUser("=cmd, \"quoted\"\nname", Role.USER), saveScooter(), BookingStatus.PENDING);
        bookings.saveAndFlush(b);
        List<String> c = cells(row(exportToday(), b.getReference()));
        assertThat(c).hasSize(ReportService.CSV_COLUMNS.size());
        assertThat(value(c, "customer_name")).isEqualTo("'=cmd, \"quoted\"\nname");
    }

    @Test
    void onlyAdminsCanExport() throws Exception {
        User customer = saveUser("Not Admin", Role.USER);
        mvc.perform(get("/api/admin/reports/bookings.csv?from=2026-10-01&to=2026-10-31")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(customer)))
                .andExpect(status().isForbidden());
    }

    private static String value(List<String> cells, String column) {
        return cells.get(ReportService.CSV_COLUMNS.indexOf(column));
    }
}
