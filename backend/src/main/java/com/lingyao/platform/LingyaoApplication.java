package com.lingyao.platform;

import com.lingyao.platform.entity.NotificationChannel;
import com.lingyao.platform.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;

@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
public class LingyaoApplication {

    private static final Logger log = LoggerFactory.getLogger(LingyaoApplication.class);

    @Autowired private NotificationChannelRepository channelRepo;

    public static void main(String[] args) {
        SpringApplication.run(LingyaoApplication.class, args);
    }

    /**
     * Bug-03 修复：启动时检查推送通道健康状况
     * 没有激活通道时打印 ERROR 级别警告，让运维第一时间感知
     */
    @Bean
    public ApplicationRunner pushHealthCheck() {
        return args -> {
            List<NotificationChannel> active = channelRepo.findByStatusOrderBySortOrderAsc(
                    NotificationChannel.Status.ACTIVE);
            if (active.isEmpty()) {
                log.error("═══════════════════════════════════════════════════════════");
                log.error("🚨 PUSH CHANNEL ALERT — Bug-03");
                log.error("当前无任何激活的推送通道，报名通知将无法送达主人！");
                log.error("请访问超管后台 /admin/ 配置 WECHAT_WORK（企微群机器人）通道");
                log.error("POST http://127.0.0.1:9091/api/admin/channels （需要 admin 登录）");
                log.error("═══════════════════════════════════════════════════════════");
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
