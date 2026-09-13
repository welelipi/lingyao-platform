package com.lingyao.platform.repository;

import com.lingyao.platform.entity.TrialRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrialRegistrationRepository extends JpaRepository<TrialRegistration, Long> {

    Optional<TrialRegistration> findByPhone(String phone);

    List<TrialRegistration> findByStatus(TrialRegistration.Status status);

    /**
     * 频次限流查询：24h 内同一手机号注册次数
     */
    @Query("SELECT COUNT(t) FROM TrialRegistration t WHERE t.phone = :phone AND t.createdAt > :since")
    long countByPhoneSince(@Param("phone") String phone, @Param("since") LocalDateTime since);

    /**
     * 频次限流查询：24h 内同一 IP 注册次数
     */
    @Query("SELECT COUNT(t) FROM TrialRegistration t WHERE t.ipAddress = :ip AND t.createdAt > :since")
    long countByIpSince(@Param("ip") String ip, @Param("since") LocalDateTime since);

    /**
     * cron 扫表：找 trial_expires_at 已过期但 status 仍为 ACTIVATED 的记录
     */
    @Query("SELECT t FROM TrialRegistration t WHERE t.status = com.lingyao.platform.entity.TrialRegistration.Status.ACTIVATED AND t.trialExpiresAt < :now")
    List<TrialRegistration> findExpiredTrials(@Param("now") LocalDateTime now);
}
