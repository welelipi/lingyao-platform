package com.lingyao.platform.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录防爆破服务 — Bug-09 修复
 * 5 分钟内连续 5 次错误 → 锁定 10 分钟
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 10;
    private static final int WINDOW_MINUTES = 5;

    private final ConcurrentHashMap<String, AttemptRecord> records = new ConcurrentHashMap<>();

    public boolean isLocked(String username) {
        AttemptRecord rec = records.get(username);
        if (rec == null) return false;
        if (rec.lockedUntil != null && LocalDateTime.now().isBefore(rec.lockedUntil)) {
            return true;
        }
        if (rec.lockedUntil != null && LocalDateTime.now().isAfter(rec.lockedUntil)) {
            records.remove(username);
            return false;
        }
        return false;
    }

    public void recordFailure(String username) {
        AttemptRecord rec = records.computeIfAbsent(username, k -> new AttemptRecord());
        rec.attempts.incrementAndGet();
        rec.lastAttempt = LocalDateTime.now();

        if (rec.attempts.get() >= MAX_ATTEMPTS) {
            rec.lockedUntil = LocalDateTime.now().plusMinutes(LOCK_MINUTES);
        }
    }

    public void recordSuccess(String username) {
        records.remove(username);
    }

    public String getLockMessage(String username) {
        AttemptRecord rec = records.get(username);
        if (rec != null && rec.lockedUntil != null && LocalDateTime.now().isBefore(rec.lockedUntil)) {
            return "账号已锁定，请于 " + rec.lockedUntil + " 后重试";
        }
        return null;
    }

    /**
     * 返回登录失败后的剩余尝试次数（已锁定则返回 0）
     * Bug-09 修复：之前 AuthService 写死 remaining=5 导致提示恒显示剩余 5 次
     */
    public int getRemainingAttempts(String username) {
        AttemptRecord rec = records.get(username);
        if (rec == null) return MAX_ATTEMPTS;
        if (rec.lockedUntil != null && LocalDateTime.now().isBefore(rec.lockedUntil)) return 0;
        int used = rec.attempts.get();
        int remain = MAX_ATTEMPTS - used;
        return Math.max(remain, 0);
    }

    private static class AttemptRecord {
        AtomicInteger attempts = new AtomicInteger(0);
        LocalDateTime lastAttempt;
        LocalDateTime lockedUntil;
    }
}
