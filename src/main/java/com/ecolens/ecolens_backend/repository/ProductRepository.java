package com.ecolens.ecolens_backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecolens.ecolens_backend.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findFirstByNameIgnoreCase(String name);

    Optional<Product> findFirstByCategoryIgnoreCase(String category);
}
