package com.scooterrentalkandy.spring.services;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.ScooterDtos.ScooterRequest;
import com.scooterrentalkandy.spring.dto.ScooterDtos.ScooterResponse;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.ScooterMapper;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScooterService {

    private final ScooterRepository scooters;
    private final BookingRepository bookings;

    public ScooterService(ScooterRepository scooters, BookingRepository bookings) {
        this.scooters = scooters;
        this.bookings = bookings;
    }

    /** The fleet customers can browse (SDS 5.2), each flagged with whether it can be booked right now. */
    @Transactional(readOnly = true)
    public List<ScooterResponse> listBookable() {
        Set<UUID> held = heldScooterIds();
        return scooters.findByDeletedFalseOrderByCodeAsc().stream()
                .map(s -> ScooterMapper.toResponse(s, held.contains(s.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ScooterResponse> listAll() {
        Set<UUID> held = heldScooterIds();
        return scooters.findAllByOrderByCodeAsc().stream()
                .map(s -> ScooterMapper.toResponse(s, held.contains(s.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ScooterResponse get(UUID id) {
        return response(find(id));
    }

    @Transactional
    public ScooterResponse create(ScooterRequest req) {
        if (scooters.existsByCodeIgnoreCase(req.code())) {
            throw ApiException.conflict("Scooter code already in use");
        }
        if (scooters.existsByPlateNumberIgnoreCase(req.plateNumber())) {
            throw ApiException.conflict("Plate number already registered");
        }
        Scooter s = new Scooter();
        apply(s, req);
        s.setStatus(ScooterStatus.AVAILABLE);
        return response(scooters.save(s));
    }

    @Transactional
    public ScooterResponse update(UUID id, ScooterRequest req) {
        Scooter s = find(id);
        apply(s, req);
        return response(s);
    }

    @Transactional
    public ScooterResponse setStatus(UUID id, ScooterStatus status) {
        Scooter s = find(id);
        if (s.getStatus() == ScooterStatus.RENTED && status != ScooterStatus.RENTED) {
            throw ApiException.conflict("Scooter is out on a rental; it is released when the rental ends");
        }
        if (status == ScooterStatus.RENTED) {
            throw ApiException.badRequest("Scooters become RENTED only through a paid booking");
        }
        s.setStatus(status);
        return response(s);
    }

    /** SDS 8.3: a scooter leaving the fleet is soft-deleted, keeping its booking history. */
    @Transactional
    public ScooterResponse remove(UUID id) {
        Scooter s = find(id);
        if (s.getStatus() == ScooterStatus.RENTED) {
            throw ApiException.conflict("Scooter is out on a rental; it is released when the rental ends");
        }
        s.setDeleted(true);
        return response(s);
    }

    @Transactional
    public ScooterResponse restore(UUID id) {
        Scooter s = find(id);
        s.setDeleted(false);
        return response(s);
    }

    public Scooter find(UUID id) {
        return scooters.findById(id).orElseThrow(() -> ApiException.notFound("Scooter"));
    }

    private ScooterResponse response(Scooter s) {
        return ScooterMapper.toResponse(s, bookings.existsByScooterIdAndStatusIn(s.getId(), BookingStatus.BLOCKING));
    }

    private Set<UUID> heldScooterIds() {
        return Set.copyOf(bookings.findScooterIdsWithStatus(BookingStatus.BLOCKING));
    }

    private void apply(Scooter s, ScooterRequest req) {
        s.setCode(req.code().trim().toUpperCase());
        s.setModel(req.model().trim());
        s.setPlateNumber(req.plateNumber().trim().toUpperCase());
        s.setEngineCc(req.engineCc());
        s.setHourlyRate(req.hourlyRate());
        s.setPerKmRate(req.perKmRate());
        s.setTotalMileage(req.totalMileage().setScale(2, java.math.RoundingMode.HALF_UP));
        s.setImageUrl(req.imageUrl());
        s.setDescription(req.description());
    }
}

