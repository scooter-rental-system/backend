package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CompleteMaintenanceRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CreateMaintenanceRequest;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.MaintenanceRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import org.junit.jupiter.api.Test;

/** Maintenance dates come from the injected clock (and its zone), not the system default. No Spring, no DB. */
class MaintenanceClockTests {

    // 20:00 UTC on 24 Oct is already 25 Oct in Colombo.
    private static final Clock COLOMBO = Clock.fixed(Instant.parse("2026-10-24T20:00:00Z"), ZoneId.of("Asia/Colombo"));
    private static final LocalDate TODAY_IN_COLOMBO = LocalDate.of(2026, 10, 25);

    private final MaintenanceRepository records = mock(MaintenanceRepository.class);
    private final ScooterRepository scooters = mock(ScooterRepository.class);
    private final MaintenanceService service = new MaintenanceService(records, scooters, mock(BookingRepository.class),
            mock(NotificationService.class), COLOMBO);

    private Scooter scooter() {
        Scooter s = new Scooter();
        s.setScooterId(UUID.randomUUID());
        s.setCode("CLK-1");
        s.setModel("Test Scooter");
        s.setStatus(ScooterStatus.MAINTENANCE);
        s.setTotalMileage(new BigDecimal("10.00"));
        return s;
    }

    @Test
    void scheduledAndCompletedDatesUseTheInjectedClock() {
        Scooter scooter = scooter();
        when(scooters.findById(scooter.getScooterId())).thenReturn(Optional.of(scooter));
        when(records.save(any(MaintenanceRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        var created = service.create(new CreateMaintenanceRequest(scooter.getScooterId(),
                MaintenanceRecord.Type.INSPECTION, "Annual inspection", null, false));
        assertThat(created.scheduledDate()).isEqualTo(TODAY_IN_COLOMBO);

        MaintenanceRecord record = new MaintenanceRecord();
        UUID id = UUID.randomUUID();
        record.setMaintenanceRecordId(id);
        record.setScooter(scooter);
        record.setType(MaintenanceRecord.Type.INSPECTION);
        record.setStatus(MaintenanceRecord.Status.IN_PROGRESS);
        record.setDescription("Annual inspection");
        when(records.findScooterIdById(id)).thenReturn(Optional.of(scooter.getScooterId()));
        when(scooters.findByIdForUpdate(scooter.getScooterId())).thenReturn(Optional.of(scooter));
        when(records.findById(id)).thenReturn(Optional.of(record));

        var completed = service.complete(id, new CompleteMaintenanceRequest(null, null, null));
        assertThat(completed.completedDate()).isEqualTo(TODAY_IN_COLOMBO);
    }
}
