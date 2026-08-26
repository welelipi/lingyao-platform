package com.lingyao.platform.repository;

import com.lingyao.platform.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByCode(String code);
    List<Company> findByStatus(Company.CompanyStatus status);
}
