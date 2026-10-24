package com.scooterrentalkandy.spring.repository;

import java.util.UUID;
import com.scooterrentalkandy.spring.entity.PaymentProviderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentProviderEventRepository extends JpaRepository<PaymentProviderEvent, UUID> {

    boolean existsByProviderAndEventId(String provider, String eventId);
}
