package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.Contract;
import com.scooterrentalkandy.spring.repository.ContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractService {

    public static final String TERMS_VERSION = "2026.4";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    // Template wording is a starting point for the business owner to review with a lawyer.
    private static final String TEMPLATE = """
            SCOOTER RENTAL AGREEMENT  (No. %s, terms v%s)

            Between Scooter Rent Kandy ("the Company") and %s ("the Renter"),
            ID/Passport: %s, Driving licence: %s.

            1. VEHICLE. The Company rents to the Renter scooter %s (%s), plate %s, for pickup from %s
               at %s. Gear: %s. The rental starts when the Company hands the scooter over, recording its
               odometer reading, and the Renter then confirms the start; it runs until the scooter is
               returned and the Company records the odometer reading again.

            2. CHARGES. Scooter time: LKR %s for each complete 24 hours; time after the complete days is
               charged at LKR %s per started hour when it is 8 hours or less, or as one more day when it is
               more than 8 hours (minimum one hour). Distance: the rental includes 120 km, and each further
               charged day adds another 120 km; only kilometres above that allowance are charged, at LKR %s
               per km, the distance being the difference between the odometer readings at handover and
               return. Gear: each item's daily rate for each complete 24 hours; up to 12 further hours are
               included and more than 12 hours is one more day (minimum one day). Time is charged from the
               confirmed start of the rental, not from the planned pickup time. The rental has no fixed end:
               an initial charge of LKR %s (the one-hour minimum and one day of gear) is taken when booking.
               When the rental ends, the final total is calculated and the difference is charged to, or
               refunded to, the same payment method.

            3. LICENCE AND RIDERS. The Renter confirms they hold a licence valid for riding this vehicle
               in Sri Lanka (including an International Driving Permit or Sri Lankan recognition permit
               where required). Only the Renter may ride the scooter. A helmet must be worn at all times.

            4. CARE OF THE VEHICLE. The Renter will obey traffic laws, not ride under the influence of
               alcohol or drugs, not carry more than one passenger, and keep the scooter locked when parked.

            5. END OF RENTAL. The rental ends when the Renter returns the scooter and the Company records
               the odometer reading. Charges accrue until then.

            6. DAMAGE AND LOSS. The Renter is responsible for damage, traffic fines and loss of the scooter
               or rented gear during the rental.

            7. TRACKING. During the rental the scooter's position is logged by its GPS tracker and, with the
               Renter's permission, by the Renter's browser while the booking page is open. Location data is
               used to show the approximate route, to monitor speeding (over 60 km/h), for fleet safety and
               theft recovery, and to support the Renter during the rental. It is not used to charge for
               distance.

            8. CANCELLATION. A paid booking can be cancelled for a full refund until the rental starts (once
               the scooter has been handed over, at the rental counter only). Once the rental has started it
               cannot be cancelled; it is ended and billed as in clause 2.
            """;

    private final ContractRepository contracts;
    private final PricingService pricing;

    public ContractService(ContractRepository contracts, PricingService pricing) {
        this.contracts = contracts;
        this.pricing = pricing;
    }

    @Transactional
    public Contract createFor(Booking booking) {
        Contract c = new Contract();
        c.setBooking(booking);
        c.setContractNumber(References.next("CT"));
        c.setTermsVersion(TERMS_VERSION);
        c.setTermsText(render(c.getContractNumber(), booking));
        return contracts.save(c);
    }

    @Transactional
    public Contract sign(Booking booking, String signedName, String ip) {
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("Contract can only be signed before payment");
        }
        Contract c = forBooking(booking.getBookingId());
        if (c.isSigned()) {
            return c;
        }
        String expected = booking.getUser().getFullName().trim();
        if (!expected.equalsIgnoreCase(signedName.trim())) {
            throw ApiException.badRequest("Type your full name exactly as on your account: " + expected);
        }
        c.setSignedName(signedName.trim());
        c.setSignedIp(ip);
        c.setSignedAt(LocalDateTime.now());
        // SDS 2.2: the booking's contractSigned flag is the status of record.
        booking.setContractSigned(true);
        return c;
    }

    @Transactional(readOnly = true)
    public Contract forBooking(UUID bookingId) {
        return contracts.findByBooking_BookingId(bookingId).orElseThrow(() -> ApiException.notFound("Contract"));
    }

    private String render(String number, Booking b) {
        var customer = b.getUser();
        var scooter = b.getScooter();
        return TEMPLATE.formatted(
                number, TERMS_VERSION,
                customer.getFullName(), orDash(customer.getIdDocumentNumber()), orDash(customer.getDrivingLicenseNo()),
                scooter.getCode(), scooter.getModel(), scooter.getPlateNumber(), b.getStartTime().format(WHEN),
                b.getPickupLocation() == null ? "Kandy office" : b.getPickupLocation(), gearText(b),
                plain(scooter.getDailyRate()), plain(scooter.getHourlyRate()), plain(scooter.getPerKmRate()),
                plain(pricing.initialCharge(scooter, PricingService.selectionsOf(b)).total()));
    }

    private static String gearText(Booking b) {
        if (b.getGear().isEmpty()) {
            return "none";
        }
        return String.join("; ", b.getGear().stream()
                .map(g -> g.getGearItem().getName() + " x" + g.getQuantity() + " at LKR "
                        + plain(g.getGearItem().getDailyRate()) + "/day")
                .toList());
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String plain(BigDecimal amount) {
        return String.format("%,.2f", amount);
    }
}
