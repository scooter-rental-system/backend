package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.scooterrentalkandy.spring.support.Rentals;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin bookings CSV: human-readable headings, real values only, RFC 4180 records (CRLF, quoted cells) with
 * formula-injection protection, and a UTF-8 download with a byte-order mark for Excel.
 */
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
        if (status == BookingStatus.ACTIVE || status == BookingStatus.COMPLETED) {
            Rentals.startedAt(b, b.getStartTime()); // a started rental has its actual start
        }
        b.setContractSigned(true);
        b.setPickupLocation("Kandy office");
        return b;
    }

    /** Splits one CSV record into its cells (quoted cells may contain commas, quotes and line breaks). */
    private static List<String> cells(String record) {
        List<String> out = new ArrayList<>();
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

    /** The record that starts at {@code start}: up to the first CRLF outside quotes. */
    private static String recordAt(String csv, int start) {
        boolean quoted = false;
        for (int i = start; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '\r' && !quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                return csv.substring(start, i);
            }
        }
        return csv.substring(start);
    }

    private static List<String> row(String csv, String reference) {
        int start = csv.indexOf("\"" + reference + "\"");
        assertThat(start).as("row for " + reference).isNotNegative();
        return cells(recordAt(csv, start));
    }

    private static String value(List<String> cells, String heading) {
        int i = ReportService.CSV_COLUMNS.indexOf(heading);
        assertThat(i).as(heading).isNotNegative();
        return cells.get(i);
    }

    private String exportToday() {
        LocalDate today = LocalDate.now();
        return reports.bookingsCsv(today.minusDays(1), today.plusDays(1));
    }

    @Test
    void hasHumanReadableHeadingsInTheAgreedOrder() {
        assertThat(cells(recordAt(exportToday(), 0))).containsExactlyElementsOf(ReportService.CSV_COLUMNS);
        assertThat(ReportService.CSV_COLUMNS).containsExactly("Booking Reference", "Booking ID", "Customer Name",
                "Customer Email", "Customer NIC", "Scooter Code", "Scooter Plate", "Scooter Model", "Status",
                "Planned Pickup", "Rental Start", "Rental End", "Daily Rate (LKR)", "Extra Hour Rate (LKR)",
                "Charged Days", "Extra Hours", "Time Charge (LKR)", "Start Odometer (km)", "End Odometer (km)",
                "Billed Distance (km)", "Included Distance (km)",
                "Excess Distance (km)", "Excess Rate (LKR/km)", "Distance Charge (LKR)", "Gear", "Gear Charge (LKR)",
                "Rental Subtotal (LKR)", "Approved Repair Reimbursement (LKR)", "Total Amount (LKR)",
                "Amount Paid (LKR)", "Payment Methods", "Payment Status", "Balance Due (LKR)", "Refund Due (LKR)",
                "Created At");
    }

    @Test
    void endsEveryRecordWithCrlf() {
        Booking b = booking(saveUser("Line Ending", Role.USER), saveScooter(), BookingStatus.PENDING);
        bookings.saveAndFlush(b);
        String csv = exportToday();
        assertThat(csv).startsWith("\"Booking Reference\",").contains("\"Created At\"\r\n").endsWith("\r\n");
        assertThat(csv.replace("\r\n", "")).doesNotContain("\n");
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
        // Planned for 09:00, picked up and started at 10:00: billed from 10:00.
        LocalDateTime started = b.getStartTime().plusHours(1);
        Rentals.started(b, started, saveUser("Csv Handover Admin", Role.ADMIN), new BigDecimal("4000.0"));
        b.setEndOdometerKm(new BigDecimal("4150.0"));
        b.setEndTime(started.plusHours(27));
        b.setDistanceKm(new BigDecimal("150.00"));
        // 1 day + 3 extra hours = 3,250; 30 km over 120 at 15 = 450; 1 tent day = 1,500.
        b.setTotalCost(new BigDecimal("5200.00"));
        BookingGear line = new BookingGear();
        line.setGearItem(tent);
        line.setQuantity(1);
        b.addGear(line);
        bookings.saveAndFlush(b);

        List<String> c = row(exportToday(), b.getReference());
        assertThat(c).hasSize(ReportService.CSV_COLUMNS.size());
        assertThat(value(c, "Customer Name")).isEqualTo("Nimal Perera");
        assertThat(value(c, "Customer NIC")).isEqualTo(customer.getNicNumber());
        assertThat(value(c, "Scooter Model")).isEqualTo("Honda Dio");
        assertThat(value(c, "Status")).isEqualTo("COMPLETED");
        assertThat(value(c, "Planned Pickup")).isEqualTo("2026-10-01 09:00");
        assertThat(value(c, "Rental Start")).isEqualTo("2026-10-01 10:00");
        assertThat(value(c, "Rental End")).isEqualTo("2026-10-02 13:00");
        assertThat(value(c, "Daily Rate (LKR)")).isEqualTo("2500.00");
        assertThat(value(c, "Extra Hour Rate (LKR)")).isEqualTo("250.00");
        assertThat(value(c, "Charged Days")).isEqualTo("1");
        assertThat(value(c, "Extra Hours")).isEqualTo("3");
        assertThat(value(c, "Time Charge (LKR)")).isEqualTo("3250.00");
        assertThat(value(c, "Start Odometer (km)")).isEqualTo("4000.0");
        assertThat(value(c, "End Odometer (km)")).isEqualTo("4150.0");
        assertThat(value(c, "Billed Distance (km)")).isEqualTo("150.00");
        assertThat(value(c, "Included Distance (km)")).isEqualTo("120.00");
        assertThat(value(c, "Excess Distance (km)")).isEqualTo("30.00");
        assertThat(value(c, "Excess Rate (LKR/km)")).isEqualTo("15.00");
        assertThat(value(c, "Distance Charge (LKR)")).isEqualTo("450.00");
        assertThat(value(c, "Gear")).isEqualTo("2-person tent x1");
        assertThat(value(c, "Gear Charge (LKR)")).isEqualTo("1500.00");
        assertThat(value(c, "Total Amount (LKR)")).isEqualTo("5200.00");
        assertThat(value(c, "Created At")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    @Test
    void aRentalThatHasNotEndedLeavesTheChargesEmpty() {
        Booking b = booking(saveUser("Open Rider", Role.USER), saveScooter(), BookingStatus.PENDING);
        bookings.saveAndFlush(b);
        List<String> c = row(exportToday(), b.getReference());
        for (String heading : List.of("Rental Start", "Rental End", "Charged Days", "Extra Hours", "Time Charge (LKR)",
                "Start Odometer (km)", "End Odometer (km)", "Billed Distance (km)",
                "Included Distance (km)", "Excess Distance (km)", "Distance Charge (LKR)", "Gear Charge (LKR)",
                "Total Amount (LKR)", "Amount Paid (LKR)", "Payment Status")) {
            assertThat(value(c, heading)).as(heading).isEmpty();
        }
        assertThat(value(c, "Daily Rate (LKR)")).isEqualTo("2500.00");
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

        Booking b = booking(saveUser("=cmd, \"quoted\"\r\nname", Role.USER), saveScooter(), BookingStatus.PENDING);
        bookings.saveAndFlush(b);
        List<String> c = row(exportToday(), b.getReference());
        assertThat(c).hasSize(ReportService.CSV_COLUMNS.size());
        assertThat(value(c, "Customer Name")).isEqualTo("'=cmd, \"quoted\"\r\nname");
    }

    @Test
    void downloadsAsUtf8CsvWithAByteOrderMarkAndAUsefulName() throws Exception {
        User admin = saveUser("Csv Admin", Role.ADMIN);
        byte[] body = mvc.perform(get("/api/admin/reports/bookings.csv?from=2026-10-27&to=2026-10-27")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bookings-2026-10-27.csv\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(body, StandardCharsets.UTF_8)).startsWith("﻿\"Booking Reference\",");

        mvc.perform(get("/api/admin/reports/bookings.csv?from=2026-10-01&to=2026-10-31")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(admin)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bookings-2026-10-01-to-2026-10-31.csv\""));
    }

    @Test
    void onlyAdminsCanExport() throws Exception {
        User customer = saveUser("Not Admin", Role.USER);
        mvc.perform(get("/api/admin/reports/bookings.csv?from=2026-10-01&to=2026-10-31")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(customer)))
                .andExpect(status().isForbidden());
    }
}
