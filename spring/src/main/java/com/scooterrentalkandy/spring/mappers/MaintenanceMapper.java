package com.scooterrentalkandy.spring.mappers;


import com.scooterrentalkandy.spring.dto.MaintenanceDtos.MaintenanceResponse;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;

public final class MaintenanceMapper {

    private MaintenanceMapper() {
    }

    public static MaintenanceResponse toResponse(MaintenanceRecord m) {
        var s = m.getScooter();
        return new MaintenanceResponse(m.getId(), s.getScooterId(), s.getCode(), s.getModel(), m.getType(), m.getStatus(),
                m.getDescription(), m.getCost(), m.getScheduledDate(), m.getCompletedDate(), m.getOdometerKm(),
                m.getCreatedAt());
    }
}
