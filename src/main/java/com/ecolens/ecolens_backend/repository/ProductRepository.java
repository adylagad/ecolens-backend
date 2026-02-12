package com.ecolens.ecolens_backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ecolens.ecolens_backend.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByNameIgnoreCase(String name);

    Optional<Product> findFirstByCategoryIgnoreCase(String category);

    @Query("select min(p.carbonImpact) from Product p where p.carbonImpact is not null")
    Double findMinCarbonImpact();

    @Query("select max(p.carbonImpact) from Product p where p.carbonImpact is not null")
    Double findMaxCarbonImpact();
}
