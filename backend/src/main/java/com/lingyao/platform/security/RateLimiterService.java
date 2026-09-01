package com.lingyao.platform.security;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RateLimiterService — V2.0.24 P2.2 阶段 C
 *
 * 纯 Java 滑动窗口计数器（无外部依赖）
 *
 * 算法：固定窗口（每 windowSeconds 重置）
 * - 优点：实现简单，无外部依赖
 * - 缺点：窗口边界可被 burst 利用（防 brute-force 够用，不防高精度 DDoS）
 *
 * V2.0.25+ 改进：替换为 token bucket / leaky bucket（Redis 后端支持分布式）
 *
 * 限流 key 规则：
 * - "endpoint:ip"        → 未登录接口（login / forgot-password）
 * - "endpoint:userId"    → 已登录接口（refresh / change-password / logout）
 *
 * 默认策略（V2.0.24）：
 * - POST /api/auth/login             → 每 IP 5次/分钟（防爆破）
 * - POST /api/auth/forgot-password  → 每 IP 3次/小时（防 spam，叠加 1 分钟 debounce）
 * - POST /api/auth/refresh          → 每 user 60次/小时（防滥用）
 * - POST /api/auth/change-password  → 每 user 3次/小时（防异常改密）
 */
@Service
public class RateLimiterService {

    /** key = endpoint:userOrIp → Bucket{count, windowStartMs} */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 检查是否允许本次请求（true = 允许，false = 限流）
     *
     * @param endpoint    端点标识（如 "login" / "refresh"）
     * @param key         IP 或 userId
     * @param maxRequests 窗口内最大请求数
     * @param windowMs    窗口大小（毫秒）
     */
    public boolean allow(String endpoint, String key, int maxRequests, long windowMs) {
        String compositeKey = endpoint + ":" + key;
        long now = System.currentTimeMillis();

        Bucket bucket = buckets.computeIfAbsent(compositeKey, k -> new Bucket(now));
        synchronized (bucket) {
            // 窗口过期 → 重置
            if (now - bucket.windowStartMs >= windowMs) {
                bucket.windowStartMs = now;
                bucket.count.set(0);
            }
            int currentCount = bucket.count.incrementAndGet();
            return currentCount <= maxRequests;
        }
    }

    /**
     * 获取当前 key 在 endpoint 下的剩余配额（用于响应头 X-RateLimit-Remaining）
     */
    public int getRemaining(String endpoint, String key, int maxRequests, long windowMs) {
        String compositeKey = endpoint + ":" + key;
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.get(compositeKey);
        if (bucket == null || now - bucket.windowStartMs >= windowMs) {
            return maxRequests;
        }
        return Math.max(0, maxRequests - bucket.count.get());
    }

    /**
     * 获取当前 key 在 endpoint 下的窗口重置时间戳（毫秒）
     */
    public long getResetMs(String endpoint, String key, long windowMs) {
        String compositeKey = endpoint + ":" + key;
        Bucket bucket = buckets.get(compositeKey);
        if (bucket == null) return 0;
        return bucket.windowStartMs + windowMs;
    }

    /**
     * 清空所有限流计数（运维 / 重启用）
     */
    public void reset() {
        buckets.clear();
    }

    /** 内部：限流桶 */
    private static class Bucket {
        long windowStartMs;
        final AtomicInteger count = new AtomicInteger(0);

        Bucket(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}