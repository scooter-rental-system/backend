package com.scooterrentalkandy.spring.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Ends every still-open link of the user (a newer request, or a completed reset). */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.user.userId = :userId and t.usedAt is null")
    int invalidateOpen(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
