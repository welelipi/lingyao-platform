package com.lingyao.platform.repository;

import com.lingyao.platform.entity.ReleaseHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReleaseHistoryRepository extends JpaRepository<ReleaseHistory, Long> {

    /** 最新一条某环境的部署记录（用于"当前版本"展示） */
    Optional<ReleaseHistory> findFirstByEnvOrderByStartedAtDesc(ReleaseHistory.ReleaseEnv env);

    /** 最新一条某环境 SUCCESS 状态的记录 */
    Optional<ReleaseHistory> findFirstByEnvAndStatusOrderByFinishedAtDesc(
            ReleaseHistory.ReleaseEnv env, ReleaseHistory.ReleaseStatus status);

    /** 历史列表（分页，按时间倒序） */
    Page<ReleaseHistory> findAllByOrderByStartedAtDesc(Pageable pageable);

    /** 历史列表（按环境过滤，分页） */
    Page<ReleaseHistory> findByEnvOrderByStartedAtDesc(
            ReleaseHistory.ReleaseEnv env, Pageable pageable);

    /** 检查是否有正在执行的部署（防并发） */
    List<ReleaseHistory> findByStatus(ReleaseHistory.ReleaseStatus status);
}