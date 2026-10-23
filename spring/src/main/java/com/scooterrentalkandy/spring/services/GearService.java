package com.scooterrentalkandy.spring.services;


import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.GearDtos.GearRequest;
import com.scooterrentalkandy.spring.dto.GearDtos.GearResponse;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.GearMapper;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.repository.BookingGearRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GearService {

    private final GearItemRepository gear;
    private final BookingGearRepository bookingGear;

    public GearService(GearItemRepository gear, BookingGearRepository bookingGear) {
        this.gear = gear;
        this.bookingGear = bookingGear;
    }

    @Transactional(readOnly = true)
    public List<GearResponse> listActive() {
        return gear.findByActiveTrueOrderByCategoryAscNameAsc().stream()
                .map(g -> GearMapper.toResponse(g, available(g)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GearResponse> listAll() {
        return gear.findAllByOrderByCategoryAscNameAsc().stream().map(g -> GearMapper.toResponse(g, null)).toList();
    }

    /** Units available now (SDS 2.2 stock): owned units minus those on pending or active rentals. */
    public int available(GearItem item) {
        long reserved = bookingGear.sumReservedQuantity(item.getGearItemId(), BookingStatus.BLOCKING);
        return (int) Math.max(0, item.getTotalQuantity() - reserved);
    }

    public GearItem find(UUID id) {
        return gear.findById(id).orElseThrow(() -> ApiException.notFound("Gear item"));
    }

    /**
     * The gear item, row-locked until the caller's transaction ends, so that its availability can be checked
     * and reserved without another booking reserving the same units in between. Requires a read-write
     * transaction.
     */
    public GearItem findForBooking(UUID id) {
        return gear.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Gear item"));
    }

    @Transactional
    public GearResponse create(GearRequest req) {
        GearItem g = new GearItem();
        apply(g, req);
        return GearMapper.toResponse(gear.save(g), null);
    }

    @Transactional
    public GearResponse update(UUID id, GearRequest req) {
        GearItem g = find(id);
        apply(g, req);
        return GearMapper.toResponse(g, null);
    }

    private void apply(GearItem g, GearRequest req) {
        g.setName(req.name().trim());
        g.setCategory(req.category());
        g.setDescription(req.description());
        g.setDailyRate(req.dailyRate());
        g.setTotalQuantity(req.totalQuantity());
        g.setActive(req.active());
    }
}
