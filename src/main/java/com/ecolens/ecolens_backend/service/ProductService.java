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

    public RecognitionResponse handleRecognition(String detectedLabel, String imageBase64, double confidence) {
        log.info("Model routing for recognition request: textModel={}, visionModel={}",
                llmService.getConfiguredTextModel(), llmService.getConfiguredVisionModel());

        String labelForLookup = normalizeLabel(detectedLabel);
        boolean hasImage = imageBase64 != null && !imageBase64.isBlank();
        String inputSource;
        if (!labelForLookup.isBlank()) {
            inputSource = "text";
            log.info("Recognition input source=text providedLabel='{}'", labelForLookup);
        } else if (hasImage) {
            inputSource = "image";
            log.info("Recognition input source=image autoDetectRequested=true");
            labelForLookup = normalizeLabel(llmService.detectLabelFromImage(imageBase64));
            log.info("Gemini image detected label='{}'", labelForLookup);
        } else {
            inputSource = "none";
            log.warn("Recognition input source=none: no text label and no image payload.");
        }

        String normalizedLabel = labelForLookup;
        String generationStatus = "skipped_cached_explanation";

        Product product = productRepository.findByNameIgnoreCase(normalizedLabel)
                .or(() -> productRepository.findFirstByCategoryIgnoreCase(normalizedLabel))
                .orElseGet(() -> createDefaultProduct(normalizedLabel));

        if (product.getExplanation() == null || product.getExplanation().isBlank()) {
            generationStatus = "attempted";
            try {
                String generatedExplanation = llmService.generateExplanation(product);
                if (!llmService.isFallbackExplanation(generatedExplanation)) {
                    product.setExplanation(generatedExplanation);
                    product = productRepository.save(product);
                    generationStatus = "attempted_saved";
                } else {
                    generationStatus = "attempted_fallback";
                }
            } catch (Exception ex) {
                generationStatus = "attempted_failed";
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

        log.info("ProductService handled recognition: inputSource={}, label='{}', product='{}', llm={}",
                inputSource, normalizedLabel, safe(product.getName()), generationStatus);

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
