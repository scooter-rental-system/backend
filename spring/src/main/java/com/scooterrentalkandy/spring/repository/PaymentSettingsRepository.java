package com.scooterrentalkandy.spring.repository;

import java.util.Optional;
import com.scooterrentalkandy.spring.entity.PaymentSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentSettingsRepository extends JpaRepository<PaymentSettings, Short> {

    /** The single settings row, locked so two admins' changes apply one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PaymentSettings s where s.id = 1")
    Optional<PaymentSettings> findForUpdate();

    @Query("select s from PaymentSettings s where s.id = 1")
    Optional<PaymentSettings> findCurrent();
}
