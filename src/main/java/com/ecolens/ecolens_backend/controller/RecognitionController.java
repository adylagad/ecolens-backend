package com.ecolens.ecolens_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecolens.ecolens_backend.dto.RecognitionRequest;
import com.ecolens.ecolens_backend.dto.RecognitionResponse;
import com.ecolens.ecolens_backend.model.Product;
import com.ecolens.ecolens_backend.repository.ProductRepository;
import com.ecolens.ecolens_backend.service.LLMService;

@RestController
@RequestMapping("/api")
public class RecognitionController {

    private final ProductRepository productRepository;
    private final LLMService llmService;

    public RecognitionController(ProductRepository productRepository, LLMService llmService) {
        this.productRepository = productRepository;
        this.llmService = llmService;
    }

    @PostMapping("/recognize")
    public ResponseEntity<RecognitionResponse> recognize(@RequestBody RecognitionRequest request) {
        String detectedLabel = normalizeLabel(request.getDetectedLabel());

        Product product = productRepository.findFirstByNameIgnoreCase(detectedLabel)
                .or(() -> productRepository.findFirstByCategoryIgnoreCase(detectedLabel))
                .orElseGet(() -> createDefaultProduct(detectedLabel));

        if (product.getExplanation() == null || product.getExplanation().isBlank()) {
            String generatedExplanation = llmService.generateExplanation(product);
            product.setExplanation(generatedExplanation);
            product = productRepository.save(product);
        }

        RecognitionResponse response = new RecognitionResponse();
        response.setName(product.getName());
        response.setCategory(product.getCategory());
        response.setEcoScore(product.getEcoScore());
        response.setCo2Gram(product.getCarbonImpact());
        response.setRecyclability(product.getRecyclability());
        response.setAltRecommendation(product.getAlternativeRecommendation());
        response.setExplanation(product.getExplanation());
        response.setConfidence(request.getConfidence());

        return ResponseEntity.ok(response);
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
}
