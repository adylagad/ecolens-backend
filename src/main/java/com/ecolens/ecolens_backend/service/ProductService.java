package com.ecolens.ecolens_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.dto.RecognitionResponse;
import com.ecolens.ecolens_backend.model.Product;
import com.ecolens.ecolens_backend.repository.ProductRepository;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final LLMService llmService;

    public ProductService(ProductRepository productRepository, LLMService llmService) {
        this.productRepository = productRepository;
        this.llmService = llmService;
    }

    public RecognitionResponse handleRecognition(String detectedLabel, double confidence) {
        String normalizedLabel = normalizeLabel(detectedLabel);

        Product product = productRepository.findByNameIgnoreCase(normalizedLabel)
                .or(() -> productRepository.findFirstByCategoryIgnoreCase(normalizedLabel))
                .orElseGet(() -> createDefaultProduct(normalizedLabel));

        if (product.getExplanation() == null || product.getExplanation().isBlank()) {
            try {
                String generatedExplanation = llmService.generateExplanation(product);
                if (!llmService.isFallbackExplanation(generatedExplanation)) {
                    product.setExplanation(generatedExplanation);
                    product = productRepository.save(product);
                }
            } catch (Exception ex) {
                log.warn("Explanation generation failed for product={}: {}", safe(product.getName()), ex.getMessage());
            }
        }

        RecognitionResponse response = new RecognitionResponse();
        response.setName(product.getName());
        response.setCategory(product.getCategory());
        response.setEcoScore(product.getEcoScore());
        response.setCo2Gram(product.getCarbonImpact());
        response.setRecyclability(product.getRecyclability());
        response.setAltRecommendation(product.getAlternativeRecommendation());
        response.setExplanation(product.getExplanation() == null ? "" : product.getExplanation());
        response.setConfidence(confidence);
        return response;
    }

    private Product createDefaultProduct(String detectedLabel) {
        String fallbackName = detectedLabel.isBlank() ? "Unknown Product" : detectedLabel;
        return new Product(
                fallbackName,
                "unknown",
                50,
                100.0,
                "Unknown",
                "Consider a reusable alternative",
                ""
        );
    }

    private String normalizeLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
