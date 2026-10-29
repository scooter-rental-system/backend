package com.scooterrentalkandy.spring.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.RepairReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepairReportRepository extends JpaRepository<RepairReport, UUID> {

    List<RepairReport> findByBooking_BookingIdOrderByReportedAtDesc(UUID bookingId);

    List<RepairReport> findAllByOrderByReportedAtDesc();

    /**
     * The reimbursement the rental's final bill is reduced by: the sum of the admin-approved amounts of the
     * booking's RESOLVED self-arranged repair reports. A claimed amount, or an approval on a report not yet
     * resolved, does not count. Each report counts once.
     */
    @Query("""
            select coalesce(sum(r.approvedAmount), 0) from RepairReport r
            where r.booking.bookingId = :bookingId and r.selfArranged = true and r.approvedAmount is not null
              and r.status = com.scooterrentalkandy.spring.entity.RepairReport.Status.RESOLVED""")
    BigDecimal approvedReimbursement(@Param("bookingId") UUID bookingId);

    /**
     * Whether the booking has a self-arranged repair report that is not RESOLVED yet: an admin may still approve a
     * reimbursement on it, so the final bill must wait for it.
     */
    boolean existsByBooking_BookingIdAndSelfArrangedTrueAndStatusNot(UUID bookingId, RepairReport.Status status);
}
