package com.lingyao.platform.repository;

import com.lingyao.platform.entity.ProductUserGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductUserGrantRepository extends JpaRepository<ProductUserGrant, Long> {
    List<ProductUserGrant> findByCompanyIdAndUserId(Long companyId, Long userId);
    List<ProductUserGrant> findByCompanyIdAndUserIdAndStatus(Long companyId, Long userId, ProductUserGrant.Status status);
    List<ProductUserGrant> findByUserIdAndStatus(Long userId, ProductUserGrant.Status status);
    Optional<ProductUserGrant> findByCompanyIdAndProductIdAndUserId(Long companyId, Long productId, Long userId);
    boolean existsByCompanyIdAndProductIdAndUserIdAndStatus(Long companyId, Long productId, Long userId, ProductUserGrant.Status status);
}
