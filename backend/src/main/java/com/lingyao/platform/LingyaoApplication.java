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
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

/**
 * 凌瑶智数 · 主启动类
 *
 * V2.0.3 关键修复：
 * 1. exclude UserDetailsServiceAutoConfiguration → 关闭 Spring Security 默认 inMemoryUserDetailsManager
 *    （登录流程由自定义 LoginController 接管，不走 Spring Security 默认 UserDetailsService，
 *     避免启动日志 "Using generated security password: ..." 噪声）
 * 2. pushHealthCheck 降级 ERROR → WARN + 加 INACTIVE 默认通道说明（避免吓到运维）
 *
 * V2.0.14 关键修复（C47 W5 P1-SSO · PRD-1.2）：
 * 3. fail-fast 校验 LINGYAO_JWT_SECRET — 非 dev profile 缺 secret 或默认值必须报错退出
 *    防止 6 仓 fallback 默认值硬编码导致 SSO 验签错位（dev profile 允许 fallback + WARN）
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableJpaAuditing
@EnableAsync
@EnableScheduling   // V2.0.10：启用 @Scheduled（LicenseExpirationScheduler）
public class LingyaoApplication {

    private static final Logger log = LoggerFactory.getLogger(LingyaoApplication.class);

    @Autowired private NotificationChannelRepository channelRepo;
    @Autowired private Environment env;

    public static void main(String[] args) {
        SpringApplication.run(LingyaoApplication.class, args);
    }

    /**
     * V2.0.14 PRD-1.2：fail-fast 校验 LINGYAO_JWT_SECRET
     * - dev profile：secret 为空/默认值仅 WARN（保持 dev 友好）
     * - 非 dev profile（private/staging/prod）：secret 空或默认值必须抛 IllegalStateException 退出
     */
    @PostConstruct
    public void validateJwtSecret() {
        String secret = System.getenv("LINGYAO_JWT_SECRET");
        String[] profiles = env.getActiveProfiles();
        boolean isDev = Arrays.asList(profiles).contains("dev");

        if (secret == null || secret.isBlank() || secret.startsWith("lingyao-platform-default")) {
            if (!isDev) {
                throw new IllegalStateException(
                    "LINGYAO_JWT_SECRET 未配置或仍为默认值（activeProfiles=" + Arrays.toString(profiles) + "）。"
                  + "生产 / staging / private profile 必须通过部署平台显式注入 ≥32 字节非默认密钥。"
                );
            }
            log.warn("[DEV ONLY] LINGYAO_JWT_SECRET 使用 fallback 默认值，生产 profile 必须显式注入");
        }
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
