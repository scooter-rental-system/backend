package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.RepairDtos.CreateRepairRequest;
import com.scooterrentalkandy.spring.dto.RepairDtos.RepairResponse;
import com.scooterrentalkandy.spring.dto.RepairDtos.UpdateRepairRequest;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.RepairPhoto;
import com.scooterrentalkandy.spring.entity.RepairReport;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.RepairReportRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.RepairPhotoValidator.CheckedPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Repair and breakdown reports. A customer reports a problem with their own rental in progress, optionally with up
 * to three photos; admins see every report, arrange assistance and may approve a reimbursement amount for a repair
 * the customer arranged themselves. Nothing is refunded or charged here: an approved amount on a RESOLVED report is
 * deducted from the rental's final bill when the rental ends ({@link #approvedReimbursement}), and cannot change
 * once that bill is calculated. The customer's claimed amount never affects billing.
 */
@Service
public class RepairService {

    static final String NOT_IN_PROGRESS = "A repair can be reported only while the rental is in progress";
    static final String CLAIM_NEEDS_SELF_ARRANGED = "A claimed amount is only for a repair you arranged yourself";
    static final String APPROVAL_NEEDS_SELF_ARRANGED =
            "A reimbursement can only be approved for a repair the customer arranged themselves";
    static final String BILL_ALREADY_CALCULATED = "The final bill for this rental has already been calculated, so a "
            + "change to the approved reimbursement would not be included. Record any refund separately.";

    private static final Logger log = LoggerFactory.getLogger(RepairService.class);

    private final RepairReportRepository repairs;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final NotificationService notifications;
    private final RepairPhotoStorage photoStorage;
    private final Clock clock;

    public RepairService(RepairReportRepository repairs, BookingRepository bookings, UserRepository users,
                         NotificationService notifications, RepairPhotoStorage photoStorage, Clock clock) {
        this.repairs = repairs;
        this.bookings = bookings;
        this.users = users;
        this.notifications = notifications;
        this.photoStorage = photoStorage;
        this.clock = clock;
    }

    /** The content of one photo, for the authorised photo endpoint. */
    public record PhotoContent(String contentType, byte[] bytes) {
    }

    @Transactional
    public RepairResponse report(UUID bookingId, AuthUser user, CreateRepairRequest req) {
        return report(bookingId, user, req, List.of());
    }

    /** A report with optional photos; every photo is checked before anything is stored. */
    @Transactional
    public RepairResponse report(UUID bookingId, AuthUser user, CreateRepairRequest req, List<MultipartFile> photos) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        // Only the rider reports; anyone else is told the booking does not exist.
        if (!b.getUser().getUserId().equals(user.id())) {
            throw ApiException.notFound("Booking");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean inProgress = b.getStatus() == BookingStatus.ACTIVE && b.getEndedAt() == null
                && !now.isBefore(b.getStartTime());
        if (!inProgress) {
            throw ApiException.conflict(NOT_IN_PROGRESS);
        }
        if (!req.selfArranged() && req.claimedAmount() != null) {
            throw ApiException.badRequest(CLAIM_NEEDS_SELF_ARRANGED);
        }
        List<CheckedPhoto> checked = RepairPhotoValidator.check(photos);
        User reporter = users.findById(user.id()).orElseThrow(() -> ApiException.notFound("User"));
        RepairReport r = new RepairReport();
        r.setBooking(b);
        r.setReportedBy(reporter);
        r.setDescription(req.description().trim());
        r.setSelfArranged(req.selfArranged());
        r.setClaimedAmount(req.claimedAmount());
        r.setStatus(RepairReport.Status.REPORTED);
        r.setReportedAt(now);
        r.setUpdatedAt(now);
        attach(r, checked, now);
        repairs.save(r);
        notifications.notifyAdmins("Repair reported: " + b.getReference(),
                b.getUser().getFullName() + " reported a problem with " + b.getScooter().getCode() + " ("
                        + b.getScooter().getPlateNumber() + "): " + excerpt(r.getDescription()));
        return RepairResponse.from(r);
    }

    /** The reports for one booking: its customer or an admin. */
    @Transactional(readOnly = true)
    public List<RepairResponse> forBooking(UUID bookingId, AuthUser user) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!user.isAdmin() && !b.getUser().getUserId().equals(user.id())) {
            throw ApiException.notFound("Booking");
        }
        return repairs.findByBooking_BookingIdOrderByReportedAtDesc(bookingId).stream().map(RepairResponse::from)
                .toList();
    }

    /**
     * One photo of a report: the booking's customer or an admin. Anyone else is told it does not exist, the same
     * as for the reports themselves.
     */
    @Transactional(readOnly = true)
    public PhotoContent photo(UUID reportId, UUID photoId, AuthUser user) {
        RepairReport r = repairs.findById(reportId).orElseThrow(() -> ApiException.notFound("Photo"));
        if (!user.isAdmin() && !r.getBooking().getUser().getUserId().equals(user.id())) {
            throw ApiException.notFound("Photo");
        }
        RepairPhoto p = r.getPhotos().stream().filter(x -> x.getRepairPhotoId().equals(photoId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Photo"));
        return new PhotoContent(p.getContentType(), photoStorage.read(p.getStorageKey()));
    }

    /**
     * The repair reimbursement the booking's final bill is reduced by: the admin-approved amounts of its RESOLVED
     * self-arranged reports (each counted once). Claimed amounts and approvals on unresolved reports do not count.
     */
    @Transactional(readOnly = true)
    public BigDecimal approvedReimbursement(UUID bookingId) {
        return PricingService.money(repairs.approvedReimbursement(bookingId));
    }

    @Transactional(readOnly = true)
    public List<RepairResponse> all() {
        return repairs.findAllByOrderByReportedAtDesc().stream().map(RepairResponse::from).toList();
    }

    @Transactional
    public RepairResponse update(UUID id, UpdateRepairRequest req) {
        RepairReport r = repairs.findById(id).orElseThrow(() -> ApiException.notFound("Repair report"));
        if (req.approvedAmount() != null && !r.isSelfArranged()) {
            throw ApiException.badRequest(APPROVAL_NEEDS_SELF_ARRANGED);
        }
        // Once the rental has ended its final bill is fixed: the counted reimbursement can no longer change.
        Booking rental = r.getBooking();
        boolean billOpen = rental.getStatus() == BookingStatus.ACTIVE && rental.getEndedAt() == null;
        if (!billOpen && counted(r.getStatus(), r.isSelfArranged(), r.getApprovedAmount())
                .compareTo(counted(req.status(), r.isSelfArranged(), req.approvedAmount())) != 0) {
            throw ApiException.conflict(BILL_ALREADY_CALCULATED);
        }
        r.setStatus(req.status());
        r.setAdminNote(req.adminNote() == null || req.adminNote().isBlank() ? null : req.adminNote().trim());
        r.setApprovedAmount(req.approvedAmount());
        r.setUpdatedAt(LocalDateTime.now(clock));
        Booking b = r.getBooking();
        notifications.notify(b.getUser(), "Repair report update: " + b.getReference(),
                "Your repair report is now " + label(r.getStatus()) + "."
                        + (r.getApprovedAmount() != null ? " Approved reimbursement: LKR " + r.getApprovedAmount()
                        + (r.getStatus() == RepairReport.Status.RESOLVED ? ", deducted from your final bill."
                        : " (deducted from your final bill once the report is resolved).") : "")
                        + (r.getAdminNote() != null ? " Note: " + r.getAdminNote() : ""));
        return RepairResponse.from(r);
    }

    /** What a report takes off the final bill with these values (the rule of {@link #approvedReimbursement}). */
    private static BigDecimal counted(RepairReport.Status status, boolean selfArranged, BigDecimal approved) {
        return status == RepairReport.Status.RESOLVED && selfArranged && approved != null
                ? PricingService.money(approved) : BigDecimal.ZERO.setScale(2);
    }

    /**
     * Stores the checked photos under new random keys and links them to the report. If the report is not saved
     * (the transaction rolls back), the stored files are removed again.
     */
    private void attach(RepairReport r, List<CheckedPhoto> checked, LocalDateTime now) {
        List<String> stored = new ArrayList<>();
        if (!checked.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        for (String key : stored) {
                            try {
                                photoStorage.delete(key);
                            } catch (RuntimeException e) {
                                log.warn("Could not remove an unsaved repair photo ({})", e.getClass().getSimpleName());
                            }
                        }
                    }
                }
            });
        }
        short position = 1;
        for (CheckedPhoto photo : checked) {
            String key = RepairPhotoValidator.newKey(photo.type());
            photoStorage.save(key, photo.content());
            stored.add(key);
            RepairPhoto p = new RepairPhoto();
            p.setRepairReport(r);
            p.setPosition(position++);
            p.setStorageKey(key);
            p.setContentType(photo.type().mediaType);
            p.setSizeBytes(photo.content().length);
            p.setCreatedAt(now);
            r.getPhotos().add(p);
        }
    }

    private static String label(RepairReport.Status s) {
        return s.name().toLowerCase().replace('_', ' ');
    }

    private static String excerpt(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
