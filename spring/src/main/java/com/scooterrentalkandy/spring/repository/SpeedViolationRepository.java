package com.scooterrentalkandy.spring.repository;


import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.SpeedViolation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpeedViolationRepository extends JpaRepository<SpeedViolation, UUID> {

    @EntityGraph(attributePaths = {"scooter", "booking", "booking.customer"})
    List<SpeedViolation> findAllByOrderByRecordedAtDescCreatedAtDesc(Pageable page);

    List<SpeedViolation> findByScooterIdOrderByRecordedAtAsc(UUID scooterId);
}
