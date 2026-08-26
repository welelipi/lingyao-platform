package com.lingyao.platform.repository;

import com.lingyao.platform.entity.CompanyProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyProductRepository extends JpaRepository<CompanyProduct, Long> {
    List<CompanyProduct> findByCompanyIdAndStatus(Long companyId, CompanyProduct.Status status);
    List<CompanyProduct> findByCompanyId(Long companyId);
    Optional<CompanyProduct> findByCompanyIdAndProductId(Long companyId, Long productId);
    Optional<CompanyProduct> findByCompanyIdAndProductIdAndStatus(Long companyId, Long productId, CompanyProduct.Status status);
}
