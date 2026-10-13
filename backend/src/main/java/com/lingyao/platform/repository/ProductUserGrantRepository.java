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
    /** V2.0.78.22：按公司+产品精确查授权列表（替代此前 findAll() 全表扫描后内存过滤） */
    List<ProductUserGrant> findByCompanyIdAndProductId(Long companyId, Long productId);
    /** V2.0.78.22：按公司+产品+状态精确查（统计已授权人数用） */
    List<ProductUserGrant> findByCompanyIdAndProductIdAndStatus(Long companyId, Long productId, ProductUserGrant.Status status);
}
