package com.lingyao.platform;

import com.lingyao.platform.entity.NotificationChannel;
import com.lingyao.platform.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * 凌瑶智数 · 主启动类
 *
 * V2.0.3 关键修复：
 * 1. exclude UserDetailsServiceAutoConfiguration → 关闭 Spring Security 默认 inMemoryUserDetailsManager
 *    （登录流程由自定义 LoginController 接管，不走 Spring Security 默认 UserDetailsService，
 *     避免启动日志 "Using generated security password: ..." 噪声）
 * 2. pushHealthCheck 降级 ERROR → WARN + 加 INACTIVE 默认通道说明（避免吓到运维）
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableJpaAuditing
@EnableAsync
@EnableScheduling   // V2.0.10：启用 @Scheduled（LicenseExpirationScheduler）
public class LingyaoApplication {

    private static final Logger log = LoggerFactory.getLogger(LingyaoApplication.class);

    @Autowired private NotificationChannelRepository channelRepo;

    public static void main(String[] args) {
        SpringApplication.run(LingyaoApplication.class, args);
    }

    /**
     * 推送通道健康检查（V2.0.3 优化）
     * - 没有 ACTIVE 通道时 → WARN 级别（不再 ERROR 吓人）
     * - 表为空 → WARN 提示检查 data-private.sql
     * - 表有 INACTIVE 通道 → 列出 + 提示替换 webhook URL 激活
     */
    @Bean
    public ApplicationRunner pushHealthCheck() {
        return args -> {
            List<NotificationChannel> active = channelRepo.findByStatusOrderBySortOrderAsc(
                    NotificationChannel.Status.ACTIVE);
            if (active.isEmpty()) {
                long totalCount = channelRepo.count();
                if (totalCount == 0) {
                    log.warn("⚠️ notification_channel 表为空，请检查 data-private.sql 是否正常加载");
                } else {
                    log.warn("⚠️ 当前无 ACTIVE 推送通道（数据库共 {} 条通道，均为 INACTIVE）", totalCount);
                    log.warn("   激活方法：超管后台 /admin/ → 推送通道管理 → 替换 webhook URL → 改为 ACTIVE");
                    log.warn("   私有化默认预置：FEISHU / WECHAT_WORK / WECHAT_MP 三条 INACTIVE 占位通道");
                }
            } else {
                log.info("✅ 推送通道健康检查通过 — {} 个激活通道", active.size());
                for (NotificationChannel ch : active) {
                    log.info("   • [{}] {} ({})", ch.getChannelType(), ch.getName(),
                            ch.getWebhookUrl() == null ? "未配置 webhook" : "已配置");
                }
            }
        };
    }
}
