package com.lingyao.platform.repository;

import com.lingyao.platform.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {
    List<NotificationChannel> findByStatusOrderBySortOrderAsc(NotificationChannel.Status status);
    List<NotificationChannel> findByChannelTypeAndStatus(NotificationChannel.ChannelType type, NotificationChannel.Status status);
}
