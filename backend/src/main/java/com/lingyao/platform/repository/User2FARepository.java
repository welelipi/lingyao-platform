package com.lingyao.platform.repository;

import com.lingyao.platform.entity.User2FA;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * User2FA Repository — V2.0.26 P2.4 · 2FA 预留
 *
 * 预留接口，V2.0.27+ 完善。
 */
public interface User2FARepository extends JpaRepository<User2FA, Long> {

    Optional<User2FA> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    boolean existsByUserIdAndEnabledTrue(Long userId);
}
