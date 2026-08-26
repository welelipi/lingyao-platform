package com.lingyao.platform.repository;

import com.lingyao.platform.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsername(String username);
    Optional<SysUser> findByEmail(String email);
    List<SysUser> findByIsPlatformAdminTrue();
    boolean existsByUsername(String username);
}
