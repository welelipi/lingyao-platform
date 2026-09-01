package com.lingyao.platform.repository;

import com.lingyao.platform.entity.TokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * TokenBlacklist Repository — V2.0.20 P1 阶段 A
 */
@Repository
public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklist, Long> {

    /**
     * 检查 jti 是否被吊销（JwtAuthFilter 每次请求调用）
     */
    boolean existsByJti(String jti);

    /**
     * 清理已过期的黑名单（可由定时任务调用，V2.0.21+ 再补）
     */
    @Modifying
    @Query("DELETE FROM TokenBlacklist t WHERE t.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}