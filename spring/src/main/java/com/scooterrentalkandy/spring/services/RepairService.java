package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.RepairDtos.CreateRepairRequest;
import com.scooterrentalkandy.spring.dto.RepairDtos.RepairResponse;
import com.scooterrentalkandy.spring.dto.RepairDtos.UpdateRepairRequest;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.RepairReport;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.RepairReportRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repair and breakdown reports. A customer reports a problem with their own rental in progress; admins see every
 * report, arrange assistance and may approve a reimbursement amount for a repair the customer arranged themselves.
 * The approved amount is a record only: nothing is refunded or charged here.
 */
@Service
public class RepairService {

    static final String NOT_IN_PROGRESS = "A repair can be reported only while the rental is in progress";
    static final String CLAIM_NEEDS_SELF_ARRANGED = "A claimed amount is only for a repair you arranged yourself";
    static final String APPROVAL_NEEDS_SELF_ARRANGED =
            "A reimbursement can only be approved for a repair the customer arranged themselves";

    private final RepairReportRepository repairs;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final NotificationService notifications;
    private final Clock clock;

    public RepairService(RepairReportRepository repairs, BookingRepository bookings, UserRepository users,
                         NotificationService notifications, Clock clock) {
        this.repairs = repairs;
        this.bookings = bookings;
        this.users = users;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public RepairResponse report(UUID bookingId, AuthUser user, CreateRepairRequest req) {
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
        r.setStatus(req.status());
        r.setAdminNote(req.adminNote() == null || req.adminNote().isBlank() ? null : req.adminNote().trim());
        r.setApprovedAmount(req.approvedAmount());
        r.setUpdatedAt(LocalDateTime.now(clock));
        Booking b = r.getBooking();
        notifications.notify(b.getUser(), "Repair report update: " + b.getReference(),
                "Your repair report is now " + label(r.getStatus()) + "."
                        + (r.getApprovedAmount() != null ? " Approved reimbursement: LKR " + r.getApprovedAmount() + "."
                        : "")
                        + (r.getAdminNote() != null ? " Note: " + r.getAdminNote() : ""));
        return RepairResponse.from(r);
    }

    private static String label(RepairReport.Status s) {
        return s.name().toLowerCase().replace('_', ' ');
    }

    private static String excerpt(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
