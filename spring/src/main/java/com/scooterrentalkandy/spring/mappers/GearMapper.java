package com.scooterrentalkandy.spring.mappers;


import com.scooterrentalkandy.spring.dto.GearDtos.GearResponse;
import com.scooterrentalkandy.spring.entity.GearItem;

public final class GearMapper {

    private GearMapper() {
    }

    /** {@code available}: units free right now, or null where it is not shown (admin list). */
    public static GearResponse toResponse(GearItem g, Integer available) {
        return new GearResponse(g.getGearItemId(), g.getName(), g.getCategory(), g.getDescription(), g.getDailyRate(),
                g.getTotalQuantity(), available, g.isActive());
    }
}
