package com.lingyao.platform.repository;

import com.lingyao.platform.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** 用户最近一次未过期的 reset token（防 spam：同一用户 1 小时内只能申请 1 次）*/
    @Query("SELECT t FROM PasswordResetToken t WHERE t.userId = :userId AND t.usedAt IS NULL AND t.expiresAt > :now ORDER BY t.createdAt DESC")
    java.util.List<PasswordResetToken> findActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}