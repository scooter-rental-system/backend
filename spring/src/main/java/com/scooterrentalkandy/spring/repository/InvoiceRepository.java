package com.scooterrentalkandy.spring.repository;


import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @EntityGraph(attributePaths = {"booking", "booking.user", "lines"})
    List<Invoice> findByBooking_BookingIdOrderByIssuedAtAsc(UUID bookingId);

    @EntityGraph(attributePaths = {"booking", "booking.user"})
    List<Invoice> findTop300ByOrderByIssuedAtDesc();

    @EntityGraph(attributePaths = {"booking", "booking.user", "lines"})
    Optional<Invoice> findWithLinesByInvoiceId(UUID InvoiceId);

    Optional<Invoice> findFirstByBooking_BookingIdAndKind(UUID bookingId, Invoice.Kind kind);
}

