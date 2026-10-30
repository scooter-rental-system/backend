package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.ReportDtos.DailyRevenue;
import com.scooterrentalkandy.spring.dto.ReportDtos.Dashboard;
import com.scooterrentalkandy.spring.dto.ReportDtos.GearUsage;
import com.scooterrentalkandy.spring.dto.ReportDtos.ScooterPerformance;
import com.scooterrentalkandy.spring.dto.ReportDtos.Summary;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.InvoiceRepository;
import com.scooterrentalkandy.spring.repository.MaintenanceRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    /** Bookings that are or were rentals. */
    private static final List<BookingStatus> RENTALS = List.of(BookingStatus.ACTIVE, BookingStatus.COMPLETED);

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final ScooterRepository scooters;
    private final MaintenanceRepository maintenance;
    private final UserRepository users;
    private final Clock clock;
    private final PricingService pricing;
    private final InvoiceRepository invoices;
    private final BillingService billing;
    private final String currency;

    public ReportService(BookingRepository bookings, PaymentRepository payments, ScooterRepository scooters,
                         MaintenanceRepository maintenance, UserRepository users, Clock clock, PricingService pricing,
                         InvoiceRepository invoices, BillingService billing, AppProperties props) {
        this.bookings = bookings;
        this.payments = payments;
        this.scooters = scooters;
        this.maintenance = maintenance;
        this.users = users;
        this.clock = clock;
        this.pricing = pricing;
        this.invoices = invoices;
        this.billing = billing;
        this.currency = props.billing().currency();
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("'to' must be on or after 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw ApiException.badRequest("Report range is limited to one year");
        }
        LocalDateTime fromTs = from.atStartOfDay();
        LocalDateTime toTs = to.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(clock);

        List<Payment> paid = payments.findByStatusInAndPaidAtBetween(List.of(PaymentStatus.SUCCESS), fromTs, toTs);
        BigDecimal revenue = sum(paid);
        BigDecimal refunds = sum(payments.findByStatusAndBookingCancelledAtBetween(PaymentStatus.REFUNDED, fromTs,
                toTs));
        BigDecimal outstanding = payments.findByBalanceDueIsNotNull().stream().map(Payment::getBalanceDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Revenue per day, zero-filled so charts have no gaps.
        Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            perDay.put(d, BigDecimal.ZERO);
        }
        paid.forEach(p -> perDay.merge(p.getPaidAt()
                .atZone(ZoneId.of("Asia/Colombo"))
                .toLocalDate(), p.getAmount(), BigDecimal::add));
        List<DailyRevenue> revenueByDay = perDay.entrySet().stream()
                .map(e -> new DailyRevenue(e.getKey(), e.getValue())).toList();

        List<Booking> created = bookings.findByCreatedAtBetween(fromTs, toTs);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BookingStatus s : BookingStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        created.forEach(b -> byStatus.merge(b.getStatus().name(), 1L, Long::sum));

        // Utilisation: rented scooter-hours inside the window / available scooter-hours (up to now).
        LocalDateTime windowEnd = toTs.isAfter(now) ? now : toTs;
        List<Scooter> fleet = scooters.findByDeletedFalseOrderByCodeAsc();
        Map<UUID, Long> rentedMinutes = new HashMap<>();
        Map<UUID, Long> rentalsByScooter = new HashMap<>();
        for (Booking b : bookings.findRentalsOverlapping(RENTALS, fromTs, toTs)) {
            LocalDateTime s = b.getStartTime().isBefore(fromTs) ? fromTs : b.getStartTime();
            LocalDateTime end = b.getEndTime() != null ? b.getEndTime() : b.getEndedAt() != null ? b.getEndedAt() : now;
            LocalDateTime e = end.isAfter(toTs) ? toTs : end;
            long minutes = Math.max(0, Duration.between(s, e).toMinutes());
            rentedMinutes.merge(b.getScooter().getScooterId(), minutes, Long::sum);
            rentalsByScooter.merge(b.getScooter().getScooterId(), 1L, Long::sum);
        }
        long windowMinutes = Math.max(1, Duration.between(fromTs, windowEnd).toMinutes());
        long totalRented = rentedMinutes.values().stream().mapToLong(Long::longValue).sum();
        double utilization = fleet.isEmpty() ? 0
                : Math.min(100, Math.round(1000.0 * totalRented / (fleet.size() * windowMinutes)) / 10.0);

        Map<UUID, BigDecimal> revenueByScooter = paid.stream()
                .collect(Collectors.groupingBy(p -> p.getBooking().getScooter().getScooterId(),
                        Collectors.reducing(BigDecimal.ZERO, Payment::getAmount, BigDecimal::add)));
        List<ScooterPerformance> perf = new ArrayList<>();
        for (Scooter s : fleet) {
            perf.add(new ScooterPerformance(s.getScooterId(), s.getCode(), s.getModel(),
                    rentalsByScooter.getOrDefault(s.getScooterId(), 0L),
                    Math.round(rentedMinutes.getOrDefault(s.getScooterId(), 0L) / 60.0),
                    revenueByScooter.getOrDefault(s.getScooterId(), BigDecimal.ZERO)));
        }
        perf.sort(Comparator.comparing(ScooterPerformance::revenue).reversed());

        Map<String, Long> gearUnits = new TreeMap<>();
        created.stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED)
                .flatMap(b -> b.getGear().stream())
                .forEach(g -> gearUnits.merge(g.getGearItem().getName(), (long) g.getQuantity(), Long::sum));
        List<GearUsage> gear = gearUnits.entrySet().stream()
                .map(e -> new GearUsage(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(GearUsage::units).reversed())
                .toList();

        return new Summary(from, to, currency, revenue, refunds, outstanding,
                maintenance.sumCostCompletedBetween(from, to), created.size(), byStatus,
                users.countByRoleAndCreatedAtBetween(Role.USER, fromTs, toTs), utilization, revenueByDay, perf, gear);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Long> fleet = new LinkedHashMap<>();
        for (ScooterStatus s : ScooterStatus.values()) {
            fleet.put(s.name(), scooters.countByStatusAndDeletedFalse(s));
        }
        List<Booking> active = bookings.findByStatusOrderByStartTimeAsc(BookingStatus.ACTIVE);
        long balanceDue = active.stream().filter(b -> b.getEndedAt() != null).count();
        long upcoming = active.stream().filter(b -> b.getEndedAt() == null && now.isBefore(b.getStartTime())).count();
        long underWay = active.size() - balanceDue - upcoming;

        LocalDate today = now.toLocalDate();
        BigDecimal revenue30 = sum(payments.findByStatusInAndPaidAtBetween(List.of(PaymentStatus.SUCCESS),
                today.minusDays(29).atStartOfDay(), today.plusDays(1).atStartOfDay()));

        return new Dashboard(fleet, underWay, upcoming, balanceDue, bookings.countByStatus(BookingStatus.PENDING),
                maintenance.countByStatusIn(List.of(MaintenanceRecord.Status.SCHEDULED,
                        MaintenanceRecord.Status.IN_PROGRESS)),
                users.countByRole(Role.USER), revenue30, currency);
    }

    /** The bookings CSV headings, in order (human-readable, with units, for spreadsheets). */
    static final List<String> CSV_COLUMNS = List.of("Booking Reference", "Booking ID", "Customer Name",
            "Customer Email", "Customer NIC", "Scooter Code", "Scooter Plate", "Scooter Model", "Status",
            "Rental Start", "Rental End", "Daily Rate (LKR)", "Extra Hour Rate (LKR)", "Charged Days", "Extra Hours",
            "Time Charge (LKR)", "Total Distance (km)", "Included Distance (km)", "Excess Distance (km)",
            "Excess Rate (LKR/km)", "Distance Charge (LKR)", "Gear", "Gear Charge (LKR)", "Rental Subtotal (LKR)",
            "Approved Repair Reimbursement (LKR)", "Total Amount (LKR)",
            "Amount Paid (LKR)", "Payment Methods", "Payment Status", "Balance Due (LKR)", "Refund Due (LKR)",
            "Created At");

    /** Spreadsheet-friendly local date and time (Sri Lanka time, as stored). */
    private static final java.time.format.DateTimeFormatter CSV_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** RFC 4180 record separator. */
    private static final String CSV_EOL = "\r\n";

    /**
     * All bookings created in the window as CSV (for spreadsheets/accounting), one row per booking. The charge
     * breakdown (days, extra hours, distance allowance, time/distance/gear charges) is filled only for rentals that
     * have ended, from the shared PricingService with the scooter's and gear's rates (rates are not stored on bookings,
     * SDS 2.4); the rental subtotal and the approved repair reimbursement come from the final invoice (0 when none was
     * approved), and the total is the billed amount after that reimbursement; total, paid and balance due are the
     * billed amounts. Payment Methods lists every way money was received, in order (e.g. "QR_TRANSFER + CASH" when
     * the final balance was paid in cash after a QR payment). Text cells are quoted and protected against
     * spreadsheet formula injection; numbers are plain.
     */
    @Transactional(readOnly = true)
    public String bookingsCsv(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder(CSV_COLUMNS.stream().map(ReportService::csv).collect(Collectors.joining(",")))
                .append(CSV_EOL);
        List<Booking> rows = bookings.findByCreatedAtBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).stream()
                .sorted(Comparator.comparing(Booking::getCreatedAt)).toList();
        for (Booking b : rows) {
            Payment p = payments.findByBooking_BookingId(b.getBookingId()).orElse(null);
            Scooter s = b.getScooter();
            LocalDateTime ended = b.getEndTime() != null ? b.getEndTime() : b.getEndedAt();
            BigDecimal km = b.getDistanceKm() != null ? b.getDistanceKm() : b.getPendingDistanceKm();
            BigDecimal total = b.getTotalCost() != null ? b.getTotalCost() : b.getPendingTotalCost();
            PricingService.FinalBill bill = ended == null || km == null ? null
                    : pricing.finalBill(s, PricingService.selectionsOf(b), b.getStartTime(), ended, km);
            // The final invoice is the record of what was billed: subtotal, less the reimbursement, gives the total.
            Invoice finalInvoice = bill == null ? null
                    : invoices.findFirstByBooking_BookingIdAndKind(b.getBookingId(), Invoice.Kind.FINAL).orElse(null);
            BigDecimal subtotal = finalInvoice != null ? finalInvoice.getSubtotal() : bill == null ? null : bill.subtotal();
            BigDecimal reimbursement = finalInvoice != null
                    ? finalInvoice.getSubtotal().subtract(finalInvoice.getTotal()).max(BigDecimal.ZERO).setScale(2)
                    : bill == null ? null : BigDecimal.ZERO.setScale(2);
            String gear = b.getGear().stream()
                    .map(g -> g.getGearItem().getName() + " x" + g.getQuantity())
                    .collect(Collectors.joining("; "));
            sb.append(String.join(",",
                            csv(b.getReference()), csv(String.valueOf(b.getBookingId())),
                            csv(b.getUser().getFullName()), csv(b.getUser().getEmail()),
                            csv(b.getUser().getNicNumber()), csv(s.getCode()), csv(s.getPlateNumber()),
                            csv(s.getModel()), b.getStatus().name(), b.getStartTime().format(CSV_TIME),
                            ended == null ? "" : ended.format(CSV_TIME), plain(s.getDailyRate()),
                            plain(s.getHourlyRate()),
                            bill == null ? "" : String.valueOf(bill.days()),
                            bill == null ? "" : String.valueOf(bill.extraHours()),
                            bill == null ? "" : plain(bill.timeCost()), plain(km == null ? null : km.setScale(2)),
                            bill == null ? "" : plain(bill.includedKm()), bill == null ? "" : plain(bill.excessKm()),
                            plain(s.getPerKmRate()), bill == null ? "" : plain(bill.distanceCost()), csv(gear),
                            bill == null ? "" : plain(bill.gearCost()), plain(subtotal), plain(reimbursement),
                            plain(total),
                            p == null ? "" : plain(p.getAmount()), p == null ? "" : csv(billing.paymentMethods(p).stream().map(Enum::name)
                                    .collect(Collectors.joining(" + "))),
                            p == null ? "" : p.getStatus().name(), p == null ? "" : plain(p.getBalanceDue()),
                            p == null ? "" : plain(p.getRefundDue()), b.getCreatedAt().format(CSV_TIME)))
                    .append(CSV_EOL);
        }
        return sb.toString();
    }

    private static BigDecimal sum(List<Payment> list) {
        return list.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    /**
     * One quoted CSV cell (RFC 4180): quotes are doubled, and commas and line breaks stay inside the quotes. A value
     * that a spreadsheet would run as a formula (starting with = + - @, tab or carriage return) is prefixed with '.
     */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.matches("(?s)^[=+\\-@\\t\\r].*") ? "'" + v : v;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
