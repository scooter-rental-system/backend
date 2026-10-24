package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.scooterrentalkandy.spring.entity.enums.*;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.MaintenanceRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo data for local development. Runs only on an empty database. No bookings or payments are seeded: a
 * booking is paid only when the payment provider confirms it, so demo history would be fake financial records.
 */
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
    private final MaintenanceRepository maintenance;
    private final PasswordEncoder encoder;

    public DataSeeder(UserRepository users, ScooterRepository scooters, GearItemRepository gear,
                      MaintenanceRepository maintenance, PasswordEncoder encoder) {
        this.users = users;
        this.scooters = scooters;
        this.gear = gear;
        this.maintenance = maintenance;
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
        user(DEMO_EMAIL, DEMO_PASSWORD, "Alex Traveller", "+94 77 123 4567", "000000000002", Role.USER,
                "N1234567", "IDP-UK-998877", "United Kingdom");
        user("nimal@example.com", DEMO_PASSWORD, "Nimal Perera", "+94 71 555 0101", "000000000003",
                Role.USER, "199012345678", "B1234567", "Sri Lanka");

        // Demo rates in LKR per hour and per km.
        scooter("SRK-01", "Honda Dio", "CP BHA-1234", 110, "250", "15", 4200,
                "Light and nimble, perfect for city rides around Kandy Lake.");
        scooter("SRK-02", "TVS Ntorq 125", "CP BHB-5678", 125, "320", "20", 2950,
                "Sporty 125cc with Bluetooth console. Great for the hill roads.");
        scooter("SRK-03", "Yamaha Ray ZR", "CP BHC-9012", 125, "300", "18", 7800,
                "Comfortable for two; large under-seat storage.");
        scooter("SRK-04", "Suzuki Access 125", "CP BHD-3456", 125, "300", "18", 1200,
                "Smooth and fuel-efficient. Ideal for longer trips to Ella or Nuwara Eliya.");
        scooter("SRK-05", "Honda Dio", "CP BHE-7890", 110, "250", "15", 9100,
                "Easy to ride; our most popular first-timer scooter.");
        scooter("SRK-06", "Vespa VXL 125", "CP BHF-2468", 125, "450", "25", 3300,
                "Classic Italian style for the Kandy-to-Knuckles photo trip.");

        gearItem("2-person tent", Category.CAMPING, "Lightweight dome tent, 2.1 kg", "1500", 6);
        gearItem("Sleeping bag", Category.CAMPING, "Rated to 10°C, compression sack", "600", 10);
        gearItem("Camping stove + gas", Category.CAMPING, "Compact burner with one gas canister", "700", 5);
        gearItem("Camping mat", Category.CAMPING, "Foam roll mat", "250", 10);
        gearItem("Extra helmet", Category.SAFETY, "Open-face helmet for a passenger", "300", 12);
        gearItem("Rain poncho", Category.SAFETY, "Monsoon-ready full poncho", "150", 20);
        gearItem("Saddle bags", Category.LUGGAGE, "Pair of 20 L waterproof side bags", "500", 6);
        gearItem("Phone holder + USB charger",Category.ACCESSORY, "Handlebar mount with charger", "200", 10);

        LocalDate today = LocalDate.now();
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
}
