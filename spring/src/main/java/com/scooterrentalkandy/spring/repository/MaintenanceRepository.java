package com.scooterrentalkandy.spring.repository;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRepository extends JpaRepository<MaintenanceRecord, UUID> {

    @EntityGraph(attributePaths = "scooter")
    List<MaintenanceRecord> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "scooter")
    List<MaintenanceRecord> findByScooter_ScooterIdOrderByCreatedAtDesc(UUID scooterId);

    Optional<MaintenanceRecord> findFirstByScooter_ScooterIdAndTypeAndStatusOrderByCompletedDateDesc(
            UUID scooterId, MaintenanceRecord.Type type, MaintenanceRecord.Status status);

    boolean existsByScooter_ScooterIdAndTypeAndStatusIn(UUID scooterId, MaintenanceRecord.Type type,
                                                Collection<MaintenanceRecord.Status> statuses);

    boolean existsByScooter_ScooterIdAndStatus(UUID scooterId, MaintenanceRecord.Status status);

    @Query("""
            select coalesce(sum(m.cost), 0) from MaintenanceRecord m
            where m.status = 'DONE' and m.completedDate between :from and :to
            """)
    BigDecimal sumCostCompletedBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByStatusIn(Collection<MaintenanceRecord.Status> statuses);

    /** The record's scooter, without loading the record: the scooter is locked before the record is read. */
    @Query("select m.scooter.scooterId from MaintenanceRecord m where m.maintenanceRecordId = :id")
    Optional<UUID> findScooterIdById(@Param("id") UUID id);
}
