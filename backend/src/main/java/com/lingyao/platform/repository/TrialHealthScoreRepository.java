package com.lingyao.platform.repository;

import com.lingyao.platform.entity.TrialHealthScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrialHealthScoreRepository extends JpaRepository<TrialHealthScore, Long> {

    Optional<TrialHealthScore> findByRegistrationId(Long registrationId);

    Optional<TrialHealthScore> findByCompanyId(Long companyId);

    List<TrialHealthScore> findByBand(TrialHealthScore.Band band);

    /** 当日跑批：所有仍 ACTIVATED 的 trial */
    @Query("SELECT t FROM TrialHealthScore t WHERE t.scoreDate < :today")
    List<TrialHealthScore> findStaleScores(@Param("today") LocalDateTime today);
}
