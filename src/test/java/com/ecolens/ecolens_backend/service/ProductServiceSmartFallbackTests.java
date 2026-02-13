package com.ecolens.ecolens_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.ecolens.ecolens_backend.dto.RecognitionResponse;

@SpringBootTest
class ProductServiceSmartFallbackTests {

    @Autowired
    private ProductService productService;

    @Test
    void treeLabelGetsNaturePositiveScoreAndRecommendation() {
        RecognitionResponse response = productService.handleRecognition("tree", null, 0.95);

        assertThat(response.getCatalogMatchStrategy()).isEqualTo("none");
        assertThat(response.getEcoScore()).isGreaterThanOrEqualTo(85);
        assertThat(response.getCo2Gram()).isLessThanOrEqualTo(12.0);
        assertThat(response.getAltRecommendation().toLowerCase()).contains("eco-positive");
        assertThat(response.getExplanation().toLowerCase()).contains("no greener replacement");
        assertThat(response.getGreenerAlternativeBoostApplied()).isTrue();
    }

    @Test
    void nonExactNatureLikeItemUsesRuleBasedExplanation() {
        RecognitionResponse response = productService.handleRecognition("mystery seedling", null, 0.92);

        assertThat(response.getCatalogMatchStrategy()).isEqualTo("none");
        assertThat(response.getExplanation().toLowerCase()).contains("natural living item");
        assertThat(response.getAltRecommendation().toLowerCase()).contains("eco-positive");
    }
}
