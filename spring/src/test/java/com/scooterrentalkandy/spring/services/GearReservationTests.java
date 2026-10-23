package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.CreateBookingRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.GearSelectionRequest;
import com.scooterrentalkandy.spring.dto.GearDtos.GearRequest;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.BookingGear;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingGearRepository;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Characterizes the current gear reservation rules through the real BookingService, GearService and embedded
 * PostgreSQL, before gear locking changes (BE-030). Not in a test transaction: each booking commits or rolls back
 * on its own, as in the application, so a failed booking's rollback is observable, and expiry runs its own
 * per-booking transactions. Everything created is deleted after each test. No payment, GPS or notification path
 * is reached.
 */
class GearReservationTests extends PostgresIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private GearService gearService;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private BookingGearRepository bookingGear;

    @Autowired
    private GearItemRepository gearItems;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();
    private final List<UUID> createdGear = new ArrayList<>();

    @AfterEach
    void deleteFixtures() {
        for (UUID user : createdUsers) {
            String bookingsOf = "SELECT booking_id FROM bookings WHERE user_id = ?";
            jdbc.update("DELETE FROM payment_transactions WHERE payment_id IN "
                    + "(SELECT payment_id FROM payments WHERE booking_id IN (" + bookingsOf + "))", user);
            jdbc.update("DELETE FROM payments WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM contracts WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM booking_gear WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
        }
        createdGear.forEach(id -> jdbc.update("DELETE FROM gear_items WHERE gear_item_id = ?", id));
        createdScooters.forEach(id -> jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
    }

    // ── Fixtures (committed) ──────────────────────────────────────────────────

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser() {
        String id = unique();
        User u = new User();
        u.setEmail("gear." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Gear Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("G" + id);
        u.setRole(Role.USER);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("G-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("GP-" + id);
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        Scooter saved = scooters.saveAndFlush(s);
        createdScooters.add(saved.getScooterId());
        return saved;
    }

    private GearItem saveGear(int stock, boolean active) {
        GearItem g = new GearItem();
        g.setName("Test Gear " + unique());
        g.setCategory(Category.CAMPING);
        g.setDailyRate(new BigDecimal("500.00"));
        g.setTotalQuantity(stock);
        g.setActive(active);
        GearItem saved = gearItems.saveAndFlush(g);
        createdGear.add(saved.getGearItemId());
        return saved;
    }

    private GearItem saveGear(int stock) {
        return saveGear(stock, true);
    }

    /** Fixture only: a booking stored directly in the given state with one gear line. */
    private Booking saveBookingWithGear(BookingStatus status, GearItem gear, int quantity, LocalDateTime createdAt) {
        Booking b = new Booking();
        b.setReference("BK-G" + unique());
        b.setUser(saveUser());
        b.setScooter(saveScooter());
        b.setStatus(status);
        b.setStartTime(LocalDateTime.now().plusDays(1));
        b.setPickupLocation("Test pickup");
        b.setCreatedAt(createdAt);
        if (status == BookingStatus.CANCELLED) {
            b.setCancelledAt(LocalDateTime.now().minusMinutes(5));
        }
        if (status == BookingStatus.COMPLETED) {
            // ck_bookings_final_fields: a completed booking carries its settlement.
            b.setEndTime(LocalDateTime.now().minusHours(1));
            b.setDistanceKm(new BigDecimal("12.00"));
            b.setTotalCost(new BigDecimal("680.00"));
            b.setContractSigned(true);
        }
        BookingGear line = new BookingGear();
        line.setGearItem(gear);
        line.setQuantity(quantity);
        b.addGear(line);
        return bookings.saveAndFlush(b);
    }

    private static GearSelectionRequest line(GearItem gear, int quantity) {
        return new GearSelectionRequest(gear.getGearItemId(), quantity);
    }

    private static CreateBookingRequest request(Scooter scooter, LocalDateTime start, GearSelectionRequest... gear) {
        return new CreateBookingRequest(scooter.getScooterId(), start, List.of(gear), "Kandy Lake Round", null);
    }

    private static LocalDateTime tomorrow() {
        return LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES);
    }

    /** A new customer books a new scooter with the given gear, through the real service. */
    private BookingResponse book(GearSelectionRequest... gear) {
        return bookingService.create(saveUser().getUserId(), request(saveScooter(), tomorrow(), gear));
    }

    private ApiException rejected(User customer, CreateBookingRequest request) {
        return catchThrowableOfType(ApiException.class, () -> bookingService.create(customer.getUserId(), request));
    }

    private long reserved(GearItem gear) {
        return bookingGear.sumReservedQuantity(gear.getGearItemId(), BookingStatus.BLOCKING);
    }

    private int available(GearItem gear) {
        return gearService.available(gearItems.findById(gear.getGearItemId()).orElseThrow());
    }

    private long count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    // ── Stock ─────────────────────────────────────────────────────────────────

    @Test
    void theLastUnitCanBeBooked() {
        GearItem tent = saveGear(1);

        BookingResponse booking = book(line(tent, 1));

        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.gear()).singleElement()
                .satisfies(g -> assertThat(g.quantity()).isEqualTo(1));
        assertThat(count("SELECT quantity FROM booking_gear WHERE booking_id = ?", booking.id())).isEqualTo(1);
        assertThat(reserved(tent)).isEqualTo(1);
        assertThat(available(tent)).isZero();
    }

    @Test
    void aSecondBookingForTheLastUnitIsRejected() {
        GearItem tent = saveGear(1);
        book(line(tent, 1));
        User second = saveUser();

        ApiException ex = rejected(second, request(saveScooter(), tomorrow(), line(tent, 1)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("Only 0 x " + tent.getName() + " available right now");
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", second.getUserId())).isZero();
        assertThat(reserved(tent)).isEqualTo(1);
    }

    @Test
    void aRequestAboveStockIsRejected() {
        GearItem helmets = saveGear(2);
        User customer = saveUser();

        ApiException ex = rejected(customer, request(saveScooter(), tomorrow(), line(helmets, 3)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("Only 2 x " + helmets.getName() + " available right now");
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", customer.getUserId())).isZero();
        assertThat(reserved(helmets)).isZero();
    }

    // ── Which booking states hold gear ────────────────────────────────────────

    @Test
    void pendingAndActiveBookingsHoldGear() {
        for (BookingStatus holding : new BookingStatus[] {BookingStatus.PENDING, BookingStatus.ACTIVE}) {
            GearItem tent = saveGear(1);
            saveBookingWithGear(holding, tent, 1, LocalDateTime.now());

            assertThat(reserved(tent)).as(holding.name()).isEqualTo(1);
            assertThat(rejected(saveUser(), request(saveScooter(), tomorrow(), line(tent, 1))).getStatus())
                    .as(holding.name()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void cancelledAndCompletedBookingsDoNotHoldGear() {
        for (BookingStatus released : new BookingStatus[] {BookingStatus.CANCELLED, BookingStatus.COMPLETED}) {
            GearItem tent = saveGear(1);
            saveBookingWithGear(released, tent, 1, LocalDateTime.now().minusDays(1));

            assertThat(reserved(tent)).as(released.name()).isZero();
            assertThat(book(line(tent, 1)).status()).as(released.name()).isEqualTo(BookingStatus.PENDING);
        }
    }

    @Test
    void anExpiredUnpaidBookingReleasesItsGear() {
        GearItem tent = saveGear(1);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30).truncatedTo(ChronoUnit.SECONDS);
        Booking unpaid = saveBookingWithGear(BookingStatus.PENDING, tent, 1, cutoff.minusHours(2));
        assertThat(available(tent)).isZero();

        bookingService.expireUnpaid(cutoff);

        assertThat(bookings.findById(unpaid.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(available(tent)).isEqualTo(1);
        assertThat(book(line(tent, 1)).status()).isEqualTo(BookingStatus.PENDING);
    }

    // ── Duplicate and multiple lines ──────────────────────────────────────────

    @Test
    void duplicateLinesForTheSameGearAreMerged() {
        GearItem helmets = saveGear(3);

        BookingResponse booking = book(line(helmets, 1), line(helmets, 2));

        assertThat(booking.gear()).singleElement().satisfies(g -> assertThat(g.quantity()).isEqualTo(3));
        assertThat(count("SELECT count(*) FROM booking_gear WHERE booking_id = ?", booking.id())).isEqualTo(1);
        assertThat(reserved(helmets)).isEqualTo(3);
    }

    @Test
    void mergedDuplicateLinesAboveStockAreRejected() {
        GearItem helmets = saveGear(2);
        User customer = saveUser();

        ApiException ex = rejected(customer, request(saveScooter(), tomorrow(), line(helmets, 1), line(helmets, 2)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", customer.getUserId())).isZero();
        assertThat(reserved(helmets)).isZero();
    }

    @Test
    void severalGearItemsAreReservedTogether() {
        GearItem tent = saveGear(2);
        GearItem helmets = saveGear(4);
        GearItem stove = saveGear(1);

        BookingResponse booking = book(line(tent, 1), line(helmets, 2), line(stove, 1));

        assertThat(booking.gear()).hasSize(3);
        assertThat(reserved(tent)).isEqualTo(1);
        assertThat(reserved(helmets)).isEqualTo(2);
        assertThat(reserved(stove)).isEqualTo(1);
        assertThat(available(helmets)).isEqualTo(2);
    }

    // ── Errors ────────────────────────────────────────────────────────────────

    @Test
    void inactiveGearIsRejected() {
        GearItem retired = saveGear(5, false);
        User customer = saveUser();

        ApiException ex = rejected(customer, request(saveScooter(), tomorrow(), line(retired, 1)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessage()).isEqualTo(retired.getName() + " is not available for rent");
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", customer.getUserId())).isZero();
    }

    @Test
    void unknownGearIs404() {
        User customer = saveUser();

        ApiException ex = rejected(customer, request(saveScooter(), tomorrow(),
                new GearSelectionRequest(UUID.randomUUID(), 1)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("Gear item not found");
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", customer.getUserId())).isZero();
    }

    @Test
    void oneUnavailableItemLeavesNoPartialBooking() {
        GearItem tent = saveGear(5);
        GearItem stove = saveGear(1);
        User customer = saveUser();

        ApiException ex = rejected(customer, request(saveScooter(), tomorrow(), line(tent, 1), line(stove, 2)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", customer.getUserId())).isZero();
        assertThat(count("SELECT count(*) FROM booking_gear WHERE gear_item_id = ?", tent.getGearItemId())).isZero();
        assertThat(count("SELECT count(*) FROM contracts c JOIN bookings b ON b.booking_id = c.booking_id "
                + "WHERE b.user_id = ?", customer.getUserId())).isZero();
        assertThat(reserved(tent)).isZero();
        assertThat(reserved(stove)).isZero();
    }

    // ── Current semantics, characterized (not endorsed) ───────────────────────

    /** Reservation is global, not by date: a far-future booking holds its gear from the moment it is made. */
    @Test
    void gearIsHeldRegardlessOfTheBookingDates() {
        GearItem tent = saveGear(1);
        bookingService.create(saveUser().getUserId(), request(saveScooter(), tomorrow().plusDays(300), line(tent, 1)));

        ApiException ex = rejected(saveUser(), request(saveScooter(), tomorrow(), line(tent, 1)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reserved(tent)).isEqualTo(1);
    }

    /** BE-031: stock may not be lowered below what is reserved; the reservations and the stock are kept. */
    @Test
    void stockCannotBeLoweredBelowWhatIsReserved() {
        GearItem helmets = saveGear(5);
        book(line(helmets, 4));

        ApiException ex = catchThrowableOfType(ApiException.class, () -> gearService.update(helmets.getGearItemId(),
                new GearRequest(helmets.getName(), helmets.getCategory(), helmets.getDescription(),
                        helmets.getDailyRate(), 2, true)));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(gearItems.findById(helmets.getGearItemId()).orElseThrow().getTotalQuantity()).isEqualTo(5);
        assertThat(reserved(helmets)).isEqualTo(4);
        assertThat(available(helmets)).isEqualTo(1);
    }
}
