package com.lingyao.platform.scheduler;

import com.lingyao.platform.repository.DeviceTrustRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 设备信任过期清理 Scheduler — V2.0.71 PRD-2.2
 *
 * device_trust 表 30 天信任期到期后不再参与免验（查询侧 expiresAt.isAfter(now) 兜底），
 * 但行本身此前无清理方，会无限膨胀。本调度器每日凌晨统一删除过期行。
 */
@Component
public class DeviceTrustCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeviceTrustCleanupScheduler.class);

    @Autowired private DeviceTrustRepository deviceTrustRepo;

    /** 每日 02:40 清理过期设备信任记录（错开 AuditArchiveScheduler 的 02:30/02:35） */
    @Scheduled(cron = "0 40 2 * * *")
    @Transactional
    public void purgeExpiredDeviceTrusts() {
        int deleted = deviceTrustRepo.deleteExpired(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[DeviceTrustCleanup] 清理过期设备信任记录：{} 条", deleted);
        }
    }
}
