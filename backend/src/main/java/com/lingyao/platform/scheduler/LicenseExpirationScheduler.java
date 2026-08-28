package com.lingyao.platform.scheduler;

import com.lingyao.platform.service.LicenseExpirationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 许可证过期提醒调度器 — V2.0.10
 *
 * 两个 cron：
 * 1. 早 9:00 — 检查即将过期公司（30/15/7/1 天），推送飞书通知
 * 2. 晚 23:00 — 自动把过期公司的 status 从 ACTIVE 改为 EXPIRED
 *
 * 注意：
 * - 时区统一东八区（Asia/Shanghai），由 application-private.yml 的 hibernate.jdbc.time_zone 设置
 * - Spring Boot 启动时 @Scheduled 自动注册（需要 @EnableScheduling，已在 Application 启用）
 * - 早 9:00 与晚 23:00 是业务时间，业务可调；调后无需重启其他组件
 */
@Component
public class LicenseExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(LicenseExpirationScheduler.class);

    @Autowired
    private LicenseExpirationService licenseExpirationService;

    /**
     * 应用启动完成后立即执行一次（冷启动补偿）
     * 用于：服务器长时间运行后，跨越夜间 cron 边界时，启动后立即补跑一次
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        log.info("🚀 应用启动完成，立即执行一次过期检查（冷启动补偿）");
        try {
            licenseExpirationService.morningCheck();
        } catch (Exception e) {
            log.error("❌ 冷启动过期检查失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 早 9:00 提醒：检查即将过期公司（提前 30/15/7/1 天）
     * cron 格式：秒 分 时 日 月 星期
     */
    @Scheduled(cron = "0 0 9 * * *")
    public void morningCheck() {
        log.info("⏰ 早 9:00 cron 触发，开始许可证过期提醒");
        try {
            licenseExpirationService.morningCheck();
        } catch (Exception e) {
            log.error("❌ 早 9:00 提醒失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 晚 23:00 处理：自动 ACTIVE → EXPIRED
     */
    @Scheduled(cron = "0 0 23 * * *")
    public void eveningExpire() {
        log.info("⏰ 晚 23:00 cron 触发，开始过期处理");
        try {
            licenseExpirationService.eveningExpire();
        } catch (Exception e) {
            log.error("❌ 晚 23:00 过期处理失败：{}", e.getMessage(), e);
        }
    }
}