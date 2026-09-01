package com.lingyao.platform.repository;

import com.lingyao.platform.entity.SubTaskApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubTaskApprovalRepository extends JpaRepository<SubTaskApproval, Long> {

    /** 公司 + 状态过滤的审批列表（前端集中审批 Tab 用） */
    List<SubTaskApproval> findByCompanyIdAndStatusOrderByCreatedAtDesc(Long companyId, SubTaskApproval.Status status);

    /** 平台超管用：按状态过滤所有公司的审批 */
    List<SubTaskApproval> findByStatusOrderByCreatedAtDesc(SubTaskApproval.Status status);
}