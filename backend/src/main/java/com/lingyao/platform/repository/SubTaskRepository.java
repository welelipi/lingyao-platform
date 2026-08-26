package com.lingyao.platform.repository;

import com.lingyao.platform.entity.SubTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubTaskRepository extends JpaRepository<SubTask, Long> {
    Optional<SubTask> findByProductId(Long productId);
    Optional<SubTask> findByTaskCode(String taskCode);
    List<SubTask> findByStatusOrderByIdAsc(SubTask.Status status);
}
