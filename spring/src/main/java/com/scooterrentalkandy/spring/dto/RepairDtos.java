package com.scooterrentalkandy.spring.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.RepairReport;

public final class RepairDtos {

    private RepairDtos() {
    }

    /** A customer's report for their rental in progress. A claimed amount is only for a self-arranged repair. */
    public record CreateRepairRequest(
            @NotBlank @Size(max = 2000) String description,
            boolean selfArranged,
            @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal claimedAmount) {
    }

    /** An admin's update: the status, a note and, for a self-arranged repair, the approved reimbursement. */
    public record UpdateRepairRequest(
            @NotNull RepairReport.Status status,
            @Size(max = 2000) String adminNote,
            @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal approvedAmount) {
    }

    /** A photo on a report: what the app needs to fetch and show it. No storage key or file path is exposed. */
    public record RepairPhotoResponse(UUID id, String contentType, int sizeBytes) {
    }

    public record RepairResponse(
            UUID id,
            UUID bookingId,
            String bookingReference,
            String customerName,
            String customerPhone,
            String scooterCode,
            String scooterModel,
            String plateNumber,
            String description,
            boolean selfArranged,
            BigDecimal claimedAmount,
            BigDecimal approvedAmount,
            RepairReport.Status status,
            String adminNote,
            LocalDateTime reportedAt,
            LocalDateTime updatedAt,
            List<RepairPhotoResponse> photos) {

        public static RepairResponse from(RepairReport r) {
            var b = r.getBooking();
            var s = b.getScooter();
            return new RepairResponse(r.getRepairReportId(), b.getBookingId(), b.getReference(),
                    b.getUser().getFullName(), b.getUser().getPhone(), s.getCode(), s.getModel(), s.getPlateNumber(),
                    r.getDescription(), r.isSelfArranged(), r.getClaimedAmount(), r.getApprovedAmount(), r.getStatus(),
                    r.getAdminNote(), r.getReportedAt(), r.getUpdatedAt(), r.getPhotos().stream()
                    .map(p -> new RepairPhotoResponse(p.getRepairPhotoId(), p.getContentType(), p.getSizeBytes()))
                    .toList());
        }
    }

    /** Support contact details from configuration; null when not configured. */
    public record SupportContact(String phone, String email) {
    }
}
