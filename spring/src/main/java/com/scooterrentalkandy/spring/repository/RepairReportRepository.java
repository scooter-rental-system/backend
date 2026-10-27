package com.scooterrentalkandy.spring.repository;

import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.RepairReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairReportRepository extends JpaRepository<RepairReport, UUID> {

    List<RepairReport> findByBooking_BookingIdOrderByReportedAtDesc(UUID bookingId);

    List<RepairReport> findAllByOrderByReportedAtDesc();
}
