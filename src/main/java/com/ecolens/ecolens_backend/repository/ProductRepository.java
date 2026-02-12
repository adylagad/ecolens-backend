package com.ecolens.ecolens_backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ecolens.ecolens_backend.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByNameIgnoreCase(String name);

    Optional<Product> findFirstByCategoryIgnoreCase(String category);

    @Query("select p.carbonImpact from Product p where p.carbonImpact is not null order by p.carbonImpact asc")
    List<Double> findAllCarbonImpactsOrdered();
}
