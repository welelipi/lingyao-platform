package com.lingyao.platform.repository;

import com.lingyao.platform.entity.LicenseReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LicenseReminderLogRepository extends JpaRepository<LicenseReminderLog, Long> {

    /** 检查"今天是否已发过"（按 company_id + reminder_type + notice_days 去重） */
    List<LicenseReminderLog> findByCompanyIdAndReminderTypeAndNoticeDaysAndSentAtBetween(
            Long companyId,
            LicenseReminderLog.ReminderType reminderType,
            Integer noticeDays,
            LocalDateTime startOfDay,
            LocalDateTime endOfDay);

    /** 查询某公司的所有提醒历史（按时间倒序） */
    List<LicenseReminderLog> findByCompanyIdOrderBySentAtDesc(Long companyId);
}