package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CompleteMaintenanceRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CreateMaintenanceRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.MaintenanceResponse;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.MaintenanceMapper;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord.Status;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.MaintenanceRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceService {

    /** Kilometres between routine services. */
    public static final int SERVICE_INTERVAL_KM = 3000;

    private final MaintenanceRepository records;
    private final ScooterRepository scooters;
    private final NotificationService notifications;

    public MaintenanceService(MaintenanceRepository records, ScooterRepository scooters,
                              NotificationService notifications) {
        this.records = records;
        this.scooters = scooters;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> list(UUID scooterId) {
        var list = scooterId == null ? records.findAllByOrderByCreatedAtDesc()
                : records.findByScooter_ScooterIdOrderByCreatedAtDesc(scooterId);
        return list.stream().map(MaintenanceMapper::toResponse).toList();
    }

    @Transactional
    public MaintenanceResponse create(CreateMaintenanceRequest req) {
        Scooter scooter = scooters.findById(req.scooterId()).orElseThrow(() -> ApiException.notFound("Scooter"));
        MaintenanceRecord m = new MaintenanceRecord();
        m.setScooter(scooter);
        m.setType(req.type());
        m.setDescription(req.description());
        m.setScheduledDate(req.scheduledDate() == null ? LocalDate.now() : req.scheduledDate());
        m.setStatus(Status.SCHEDULED);
        records.save(m);
        if (req.startNow()) {
            start(m);
        }
        return MaintenanceMapper.toResponse(m);
    }

    @Transactional
    public MaintenanceResponse start(UUID id) {
        MaintenanceRecord m = find(id);
        if (m.getStatus() != Status.SCHEDULED) {
            throw ApiException.conflict("Only scheduled work can be started");
        }
        start(m);
        return MaintenanceMapper.toResponse(m);
    }

    @Transactional
    public MaintenanceResponse complete(UUID id, CompleteMaintenanceRequest req) {
        MaintenanceRecord m = find(id);
        if (m.getStatus() == Status.DONE || m.getStatus() == Status.CANCELLED) {
            throw ApiException.conflict("Maintenance record is already closed");
        }
        Scooter s = m.getScooter();
        m.setStatus(Status.DONE);
        m.setCompletedDate(LocalDate.now());
        m.setCost(req.cost());
        if (req.notes() != null && !req.notes().isBlank()) {
            m.setDescription(m.getDescription() + "\n\nCompletion notes: " + req.notes());
        }
        // The record keeps a whole-km reading; the scooter's total mileage (SDS 2.2) is decimal.
        int current = s.getTotalMileage().intValue();
        int odo = req.odometerKm() != null ? req.odometerKm() : current;
        if (odo < current) {
            throw ApiException.badRequest("Odometer cannot go below the current reading of " + current);
        }
        m.setOdometerKm(odo);
        if (BigDecimal.valueOf(odo).compareTo(s.getTotalMileage()) > 0) {
            s.setTotalMileage(BigDecimal.valueOf(odo).setScale(2));
        }
        releaseScooterIfIdle(s, m.getMaintenanceRecordId());
        return MaintenanceMapper.toResponse(m);
    }

    @Transactional
    public MaintenanceResponse cancel(UUID id) {
        MaintenanceRecord m = find(id);
        if (m.getStatus() == Status.DONE) {
            throw ApiException.conflict("Completed work cannot be cancelled");
        }
        m.setStatus(Status.CANCELLED);
        releaseScooterIfIdle(m.getScooter(), m.getMaintenanceRecordId());
        return MaintenanceMapper.toResponse(m);
    }

    /**
     * Called after a rental return. Schedules a routine service once the scooter has done
     * {@link #SERVICE_INTERVAL_KM} since its last one, unless one is already pending.
     */
    @Transactional
    public void scheduleServiceIfDue(Scooter scooter) {
        int lastServiceOdo = records.findFirstByScooter_ScooterIdAndTypeAndStatusOrderByCompletedDateDesc(
                        scooter.getScooterId(), MaintenanceRecord.Type.SERVICE, Status.DONE)
                .map(MaintenanceRecord::getOdometerKm)
                .orElse(0);
        boolean pending = records.existsByScooter_ScooterIdAndTypeAndStatusIn(scooter.getScooterId(),
                MaintenanceRecord.Type.SERVICE, List.of(Status.SCHEDULED, Status.IN_PROGRESS));
        if (pending || scooter.getTotalMileage().intValue() - lastServiceOdo < SERVICE_INTERVAL_KM) {
            return;
        }
        MaintenanceRecord m = new MaintenanceRecord();
        m.setScooter(scooter);
        m.setType(MaintenanceRecord.Type.SERVICE);
        m.setStatus(Status.SCHEDULED);
        m.setScheduledDate(LocalDate.now());
        m.setDescription("Routine " + SERVICE_INTERVAL_KM + " km service (auto-scheduled at "
                + scooter.getTotalMileage() + " km)");
        records.save(m);
        notifications.notifyAdmins("Service due: " + scooter.getCode(),
                scooter.getModel() + " " + scooter.getPlateNumber() + " is at " + scooter.getTotalMileage()
                        + " km and is due for a routine service.");
    }

    private void start(MaintenanceRecord m) {
        Scooter s = m.getScooter();
        if (s.getStatus() == ScooterStatus.RENTED) {
            throw ApiException.conflict("Scooter is out on a rental; start work after it is returned");
        }
        m.setStatus(Status.IN_PROGRESS);
        if (!s.isDeleted()) {
            s.setStatus(ScooterStatus.MAINTENANCE);
        }
    }

    private void releaseScooterIfIdle(Scooter s, UUID closedRecordId) {
        boolean otherWork = records.findByScooter_ScooterIdOrderByCreatedAtDesc(s.getScooterId()).stream()
                .anyMatch(r -> !r.getMaintenanceRecordId().equals(closedRecordId) && r.getStatus() == Status.IN_PROGRESS);
        if (!otherWork && s.getStatus() == ScooterStatus.MAINTENANCE) {
            s.setStatus(ScooterStatus.AVAILABLE);
        }
    }

    private MaintenanceRecord find(UUID id) {
        return records.findById(id).orElseThrow(() -> ApiException.notFound("Maintenance record"));
    }
}
