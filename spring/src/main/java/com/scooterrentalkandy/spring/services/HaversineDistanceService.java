package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import com.scooterrentalkandy.spring.dto.GpsDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MOCK for the Google Maps Distance Matrix API: sums straight-line (great-circle) distances between consecutive
 * GPS logs. Select a real implementation with {@code app.maps.provider}.
 */
@Service
@ConditionalOnProperty(name = "app.maps.provider", havingValue = "local", matchIfMissing = true)
public class HaversineDistanceService implements DistanceService {

    @Override
    public BigDecimal totalKm(List<GpsDtos.Point> points) {
        double km = 0;
        for (int i = 1; i < points.size(); i++) {
            GpsDtos.Point a = points.get(i - 1);
            GpsDtos.Point b = points.get(i);
            km += GpsService.distanceKm(a.latitude(), a.longitude(), b.latitude(), b.longitude());
        }
        return BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP);
    }
}
