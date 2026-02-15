package com.ecolens.ecolens_backend.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecolens.ecolens_backend.dto.RecognitionRequest;
import com.ecolens.ecolens_backend.dto.RecognitionResponse;
import com.ecolens.ecolens_backend.dto.ScoreFactor;
import com.ecolens.ecolens_backend.service.ProductService;

@RestController
@RequestMapping("/api")
public class RecognitionController {

    private static final Logger log = LoggerFactory.getLogger(RecognitionController.class);
    private final ProductService productService;

    public RecognitionController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/recognize")
    public ResponseEntity<RecognitionResponse> recognize(@RequestBody RecognitionRequest request) {
        double confidence = request.getConfidence() == null ? 0.0 : request.getConfidence();
        try {
            RecognitionResponse response = productService.handleRecognition(
                    request.getDetectedLabel(),
                    request.getImageBase64(),
                    confidence
            );
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            log.error("Recognition failed. Returning safe fallback response. label='{}', hasImage={}: {}",
                    request.getDetectedLabel(),
                    request.getImageBase64() != null && !request.getImageBase64().isBlank(),
                    ex.getMessage(),
                    ex);
            return ResponseEntity.ok(buildSafeFallback(request, confidence));
        }
    }

    private RecognitionResponse buildSafeFallback(RecognitionRequest request, double confidence) {
        String normalizedLabel = request.getDetectedLabel() == null
                ? ""
                : request.getDetectedLabel().trim();
        boolean hasImage = request.getImageBase64() != null && !request.getImageBase64().isBlank();

        RecognitionResponse response = new RecognitionResponse();
        response.setName(normalizedLabel.isBlank() ? "Unknown Item" : normalizedLabel);
        response.setCategory("unknown");
        response.setEcoScore(52);
        response.setCatalogEcoScore(50);
        response.setCo2Score(50);
        response.setCo2Gram(108.0);
        response.setRecyclability("Unknown");
        response.setAltRecommendation("Try manual label, or rescan with better lighting and framing.");
        response.setExplanation("Backend returned a safe fallback result because recognition failed unexpectedly. Confirm item label.");
        response.setConfidence(clampDouble(confidence * 0.5, 0.15, 0.45));
        response.setCatalogContribution(22.5);
        response.setCo2Contribution(27.5);
        response.setFeatureAdjustment(0);
        response.setPreBoostScore(50.0);
        response.setGreenerAlternativeBoost(0);
        response.setGreenerAlternativeBoostApplied(false);
        response.setScoringVersion("fallback-safe-v1");
        response.setCatalogMatchStrategy("error_fallback");
        response.setCatalogCoverage(0.2);
        response.setCatalogAutoLearned(false);
        response.setScoreFactors(List.of(
                new ScoreFactor(
                        "backend_error_fallback",
                        "Backend safe fallback",
                        0.0,
                        hasImage
                                ? "Image payload received but recognition path failed."
                                : "Text label payload received but recognition path failed."
                )
        ));
        return response;
    }

    private double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
