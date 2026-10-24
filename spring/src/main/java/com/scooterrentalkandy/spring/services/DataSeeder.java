package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.BookingGear;
import com.scooterrentalkandy.spring.entity.enums.*;
import com.scooterrentalkandy.spring.entity.Contract;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.MaintenanceRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.services.PricingService.FinalBill;
import com.scooterrentalkandy.spring.services.PricingService.GearSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo data for local development. Runs only on an empty database. */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    public static final String ADMIN_EMAIL = "admin@scooterrentkandy.lk";
    public static final String ADMIN_PASSWORD = "Admin@12345";
    public static final String DEMO_EMAIL = "demo@example.com";
    public static final String DEMO_PASSWORD = "Demo@12345";

    private final UserRepository users;
    private final ScooterRepository scooters;
    private final GearItemRepository gear;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final MaintenanceRepository maintenance;
    private final PricingService pricing;
    private final ContractService contracts;
    private final BillingService billing;
    private final GpsService gps;
    private final PasswordEncoder encoder;

    public DataSeeder(UserRepository users, ScooterRepository scooters, GearItemRepository gear,
                      BookingRepository bookings, PaymentRepository payments, PaymentTransactionRepository transactions,
                      MaintenanceRepository maintenance,
                      PricingService pricing, ContractService contracts, BillingService billing, GpsService gps,
                      PasswordEncoder encoder) {
        this.users = users;
        this.scooters = scooters;
        this.gear = gear;
        this.bookings = bookings;
        this.payments = payments;
        this.transactions = transactions;
        this.maintenance = maintenance;
        this.pricing = pricing;
        this.contracts = contracts;
        this.billing = billing;
        this.gps = gps;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        log.info("Seeding demo data (admin: {} / {}, customer: {} / {})", ADMIN_EMAIL, ADMIN_PASSWORD, DEMO_EMAIL,
                DEMO_PASSWORD);

        // Demo NICs are synthetic placeholders, not real identity numbers.
        user(ADMIN_EMAIL, ADMIN_PASSWORD, "Kandy Admin", "+94 81 222 3344", "000000000001", Role.ADMIN, null, null,
                "Sri Lanka");
        User demo = user(DEMO_EMAIL, DEMO_PASSWORD, "Alex Traveller", "+94 77 123 4567", "000000000002", Role.USER,
                "N1234567", "IDP-UK-998877", "United Kingdom");
        User nimal = user("nimal@example.com", DEMO_PASSWORD, "Nimal Perera", "+94 71 555 0101", "000000000003",
                Role.USER, "199012345678", "B1234567", "Sri Lanka");

        // Demo rates in LKR per hour and per km.
        Scooter dio = scooter("SRK-01", "Honda Dio", "CP BHA-1234", 110, "250", "15", 4200,
                "Light and nimble, perfect for city rides around Kandy Lake.");
        Scooter ntorq = scooter("SRK-02", "TVS Ntorq 125", "CP BHB-5678", 125, "320", "20", 2950,
                "Sporty 125cc with Bluetooth console. Great for the hill roads.");
        Scooter ray = scooter("SRK-03", "Yamaha Ray ZR", "CP BHC-9012", 125, "300", "18", 7800,
                "Comfortable for two; large under-seat storage.");
        scooter("SRK-04", "Suzuki Access 125", "CP BHD-3456", 125, "300", "18", 1200,
                "Smooth and fuel-efficient. Ideal for longer trips to Ella or Nuwara Eliya.");
        scooter("SRK-05", "Honda Dio", "CP BHE-7890", 110, "250", "15", 9100,
                "Easy to ride; our most popular first-timer scooter.");
        Scooter vespa = scooter("SRK-06", "Vespa VXL 125", "CP BHF-2468", 125, "450", "25", 3300,
                "Classic Italian style for the Kandy-to-Knuckles photo trip.");

        GearItem tent = gearItem("2-person tent", Category.CAMPING, "Lightweight dome tent, 2.1 kg", "1500", 6);
        GearItem bag = gearItem("Sleeping bag", Category.CAMPING, "Rated to 10°C, compression sack", "600", 10);
        gearItem("Camping stove + gas", Category.CAMPING, "Compact burner with one gas canister", "700", 5);
        gearItem("Camping mat", Category.CAMPING, "Foam roll mat", "250", 10);
        GearItem helmet = gearItem("Extra helmet", Category.SAFETY, "Open-face helmet for a passenger", "300", 12);
        gearItem("Rain poncho", Category.SAFETY, "Monsoon-ready full poncho", "150", 20);
        gearItem("Saddle bags", Category.LUGGAGE, "Pair of 20 L waterproof side bags", "500", 6);
        gearItem("Phone holder + USB charger",Category.ACCESSORY, "Handlebar mount with charger", "200", 10);

        LocalDate today = LocalDate.now();
        // History so reports and the dashboard have something to show.
        completedRental(nimal, dio, today.minusDays(20).atTime(9, 30), 54, "142.60", List.of());
        completedRental(demo, ntorq, today.minusDays(12).atTime(10, 0), 101, "318.25",
                List.of(new GearSelection(tent, 1), new GearSelection(bag, 2)));
        // A rental under way. Its scooter has no position until a real device or the rider reports one.
        paidRental(demo, ray, today.minusDays(1).atTime(9, 15), List.of(new GearSelection(helmet, 1)));
        // A paid rental that starts in three days (can still be cancelled for a full refund).
        paidRental(nimal, vespa, today.plusDays(3).atTime(9, 0), List.of());

        MaintenanceRecord m = new MaintenanceRecord();
        m.setScooter(scooters.findByCodeIgnoreCase("SRK-05").orElseThrow());
        m.setType(MaintenanceRecord.Type.SERVICE);
        m.setStatus(MaintenanceRecord.Status.SCHEDULED);
        m.setScheduledDate(today.plusDays(1));
        m.setDescription("Routine 3000 km service: oil, brake pads, chain check");
        maintenance.save(m);
    }

    private User user(String email, String password, String name, String phone, String nic, Role role, String idDoc,
                      String licence, String country) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(name);
        u.setPhone(phone);
        u.setNicNumber(nic);
        u.setRole(role);
        u.setIdDocumentNumber(idDoc);
        u.setDrivingLicenseNo(licence);
        u.setCountry(country);
        return users.save(u);
    }

    private Scooter scooter(String code, String model, String plate, int cc, String hourly, String perKm, int odo,
                            String description) {
        Scooter s = new Scooter();
        s.setCode(code);
        s.setModel(model);
        s.setPlateNumber(plate);
        s.setEngineCc(cc);
        s.setHourlyRate(new BigDecimal(hourly));
        s.setPerKmRate(new BigDecimal(perKm));
        s.setTotalMileage(BigDecimal.valueOf(odo).setScale(2));
        s.setStatus(ScooterStatus.AVAILABLE);
        s.setDescription(description);
        return scooters.save(s);
    }

    private GearItem gearItem(String name, Category category, String description, String rate, int qty) {
        GearItem g = new GearItem();
        g.setName(name);
        g.setCategory(category);
        g.setDescription(description);
        g.setDailyRate(new BigDecimal(rate));
        g.setTotalQuantity(qty);
        g.setActive(true);
        return gear.save(g);
    }

    /** Booked, signed and paid (initial charge); ACTIVE from {@code start}, scooter RENTED. */
    private Booking paidRental(User customer, Scooter scooter, LocalDateTime start, List<GearSelection> gearSel) {
        Booking b = new Booking();
        b.setReference(References.next("BK"));
        b.setUser(customer);
        b.setScooter(scooter);
        b.setStartTime(start);
        b.setStatus(BookingStatus.ACTIVE);
        b.setPickupLocation("Kandy office, Dalada Veediya");
        b.setCreatedAt(start.minusDays(2));
        for (GearSelection g : gearSel) {
            BookingGear line = new BookingGear();
            line.setGearItem(g.item());
            line.setQuantity(g.quantity());
            b.addGear(line);
        }
        bookings.save(b);

        Contract c = contracts.createFor(b);
        c.setSignedName(customer.getFullName());
        c.setSignedAt(b.getCreatedAt().plusMinutes(3));
        c.setSignedIp("127.0.0.1");
        b.setContractSigned(true);

        Payment p = new Payment();
        p.setBooking(b);
        p.setAmount(pricing.initialCharge(scooter, gearSel).total());
        p.setMethod(PaymentMethod.CARD);
        p.setStatus(PaymentStatus.SUCCESS);
        p.setProvider("mock");
        p.setStripePaymentId("pi_mock_seed_" + b.getReference());
        p.setPaymentMethodRef("pm_card_visa");
        p.setCardLast4("4242");
        p.setPaidAt(b.getCreatedAt().plusMinutes(5));
        p.setCreatedAt(b.getCreatedAt().plusMinutes(4));
        payments.save(p);
        transaction(p, PaymentTransaction.Kind.INITIAL, p.getStripePaymentId(), p.getAmount(), p.getPaidAt());
        scooter.setStatus(ScooterStatus.RENTED);
        return b;
    }

    private void transaction(Payment p, PaymentTransaction.Kind kind, String ref, BigDecimal amount,
                             LocalDateTime at) {
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(kind);
        t.setProviderRef(ref);
        t.setAmount(amount);
        t.setStatus(PaymentTransaction.Status.SUCCESS);
        t.setCreatedAt(at);
        transactions.save(t);
    }

    private void completedRental(User customer, Scooter scooter, LocalDateTime start, int hours, String km,
                                 List<GearSelection> gearSel) {
        Booking b = paidRental(customer, scooter, start, gearSel);
        LocalDateTime end = start.plusHours(hours);
        FinalBill bill = pricing.finalBill(scooter, gearSel, start, end, new BigDecimal(km));
        b.setEndTime(end);
        b.setDistanceKm(bill.distanceKm());
        b.setTotalCost(bill.total());
        b.setStatus(BookingStatus.COMPLETED);
        b.setDropLocation(gps.dropLocation(b.getBookingId(), scooter));
        billing.issueFinalInvoice(b, bill).setIssuedAt(end);
        Payment p = payments.findByBooking_BookingId(b.getBookingId()).orElseThrow();
        BigDecimal balance = bill.total().subtract(p.getAmount());
        if (balance.signum() > 0) {
            transaction(p, PaymentTransaction.Kind.BALANCE, "pi_mock_seed_balance_" + b.getReference(), balance, end);
        }
        p.setAmount(bill.total());
        p.setPaidAt(end);
        scooter.setStatus(ScooterStatus.AVAILABLE);
    }
}

