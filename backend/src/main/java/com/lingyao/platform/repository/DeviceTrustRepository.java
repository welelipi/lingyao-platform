package com.lingyao.platform.repository;

import com.lingyao.platform.entity.DeviceTrust;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface DeviceTrustRepository extends JpaRepository<DeviceTrust, Long> {

    Optional<DeviceTrust> findByUserIdAndDeviceFp(Long userId, String deviceFp);

    /** V2.0.69：信任期续期（重复验证过的新设备再次出现时刷新过期时间） */
    @Modifying
    @Query("UPDATE DeviceTrust d SET d.expiresAt = :expiresAt WHERE d.id = :id")
    void renew(@Param("id") Long id, @Param("expiresAt") LocalDateTime expiresAt);

    /** V2.0.69：清理过期信任记录（登录时顺手调，惰性清理） */
    @Modifying
    @Query("DELETE FROM DeviceTrust d WHERE d.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
