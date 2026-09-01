package com.lingyao.platform.repository;

import com.lingyao.platform.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * RefreshToken Repository — V2.0.21 P1.4 阶段 B
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 查找指定 hash 的 token */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 查找用户的所有 refresh token（管理后台用）*/
    List<RefreshToken> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 查找用户未过期的 refresh token（撤销时全量操作）*/
    @Query("SELECT t FROM RefreshToken t WHERE t.userId = :userId AND (t.revokedAt IS NULL AND t.usedAt IS NULL AND t.expiresAt > :now)")
    List<RefreshToken> findActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 清理已过期且已撤销/已用的 token（定时任务 V2.0.22+ 接）*/
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :threshold")
    int deleteOldTokens(@Param("threshold") LocalDateTime threshold);
}