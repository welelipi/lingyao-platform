package com.lingyao.platform.repository;

import com.lingyao.platform.entity.Registration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface RegistrationRepository extends JpaRepository<Registration, Long> {
    Page<Registration> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<Registration> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    long countByStatus(String status);
    boolean existsByPhoneAndCreatedAtAfter(String phone, LocalDateTime after);
    boolean existsByEmailAndCreatedAtAfter(String email, LocalDateTime after);
}
