package com.scooterrentalkandy.spring.repository;


import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @EntityGraph(attributePaths = {"booking", "booking.customer", "lines"})
    List<Invoice> findByBookingIdOrderByIssuedAtAsc(UUID bookingId);

    @EntityGraph(attributePaths = {"booking", "booking.customer"})
    List<Invoice> findTop300ByOrderByIssuedAtDesc();

    @EntityGraph(attributePaths = {"booking", "booking.customer", "lines"})
    Optional<Invoice> findWithLinesById(UUID id);

    Optional<Invoice> findFirstByBookingIdAndKind(UUID bookingId, Invoice.Kind kind);
}

