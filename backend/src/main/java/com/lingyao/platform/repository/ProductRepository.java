package com.lingyao.platform.repository;

import com.lingyao.platform.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByCode(String code);
    List<Product> findByStatusOrderBySortOrderAsc(Product.Status status);
}
