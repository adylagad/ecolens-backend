package com.ecolens.ecolens_backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecolens.ecolens_backend.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByNameContainingIgnoreCase(String name);

    List<Product> findByCategoryIgnoreCase(String category);
}
