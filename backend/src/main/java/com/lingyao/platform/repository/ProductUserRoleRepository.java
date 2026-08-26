package com.lingyao.platform.repository;

import com.lingyao.platform.entity.ProductUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductUserRoleRepository extends JpaRepository<ProductUserRole, Long> {
    Optional<ProductUserRole> findByCompanyIdAndProductIdAndUserId(Long companyId, Long productId, Long userId);
    List<ProductUserRole> findByCompanyIdAndUserId(Long companyId, Long userId);
    List<ProductUserRole> findByCompanyIdAndProductId(Long companyId, Long productId);
    List<ProductUserRole> findByUserId(Long userId);
}
