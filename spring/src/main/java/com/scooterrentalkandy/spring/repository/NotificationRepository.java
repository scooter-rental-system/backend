
package com.scooterrentalkandy.spring.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Notification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUser_UserIdAndChannelOrderByCreatedAtDesc(UUID userId, Notification.Channel channel);

    List<Notification> findTop200ByUser_UserIdAndChannelOrderByCreatedAtDesc(UUID userId,
                                                                             Notification.Channel channel);

    Optional<Notification> findByNotificationIdAndUser_UserIdAndChannel(UUID notificationId, UUID userId,
                                                                        Notification.Channel channel);

    long countByUser_UserIdAndChannelAndReadAtIsNull(UUID userId, Notification.Channel channel);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :readAt "
            + "where n.user.userId = :userId and n.channel = :channel and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("channel") Notification.Channel channel,
                    @Param("readAt") LocalDateTime readAt);

    @EntityGraph(attributePaths = "user")
    List<Notification> findTop200ByOrderByCreatedAtDesc();
}
