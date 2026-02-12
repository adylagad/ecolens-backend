package com.ecolens.ecolens_backend.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.dto.RecognitionResponse;
import com.ecolens.ecolens_backend.model.Product;
import com.ecolens.ecolens_backend.repository.ProductRepository;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final double FUZZY_MATCH_THRESHOLD = 0.45;
    private static final Map<String, String> LABEL_ALIASES = Map.ofEntries(
            Map.entry("paper coffee cup", "paper cup"),
            Map.entry("disposable coffee cup", "paper cup"),
            Map.entry("single use plastic bottle", "plastic bottle"),
            Map.entry("plastic water bottle", "plastic bottle"),
            Map.entry("water bottle", "plastic bottle"),
            Map.entry("metal water bottle", "reusable bottle"),
            Map.entry("steel bottle", "reusable bottle"),
            Map.entry("coffee mug", "coffee cup"),
            Map.entry("reusable coffee cup", "coffee cup"),
            Map.entry("takeaway container", "food packaging"),
            Map.entry("food container", "food packaging")
    );

    private final ProductRepository productRepository;
    private final LLMService llmService;

    public ProductService(ProductRepository productRepository, LLMService llmService) {
        this.productRepository = productRepository;
        this.llmService = llmService;
    }

    public RecognitionResponse handleRecognition(String detectedLabel, String imageBase64, double confidence) {
        log.info("Model routing for recognition request: textModel={}, visionModel={}",
                llmService.getConfiguredTextModel(), llmService.getConfiguredVisionModel());

        String labelForLookup = canonicalizeLabel(normalizeLabel(detectedLabel));
        boolean hasImage = imageBase64 != null && !imageBase64.isBlank();
        String inputSource;
        if (!labelForLookup.isBlank()) {
            inputSource = "text";
            log.info("Recognition input source=text providedLabel='{}'", labelForLookup);
        } else if (hasImage) {
            inputSource = "image";
            log.info("Recognition input source=image autoDetectRequested=true");
            labelForLookup = canonicalizeLabel(normalizeLabel(llmService.detectLabelFromImage(imageBase64)));
            log.info("Gemini image detected label='{}'", labelForLookup);
        } else {
            inputSource = "none";
            log.warn("Recognition input source=none: no text label and no image payload.");
        }

        String normalizedLabel = labelForLookup;
        String generationStatus = "skipped_cached_explanation";

        Product product = findBestProduct(normalizedLabel)
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
        String value = NON_ALPHANUMERIC.matcher(label.toLowerCase()).replaceAll(" ").trim();
        return value.replaceAll("\\s+", " ");
    }

    private String canonicalizeLabel(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return "";
        }
        return LABEL_ALIASES.getOrDefault(normalizedLabel, normalizedLabel);
    }

    private Optional<Product> findBestProduct(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return Optional.empty();
        }

        List<Product> products = productRepository.findAll();
        for (Product product : products) {
            String normalizedName = normalizeLabel(product.getName());
            String normalizedCategory = normalizeLabel(product.getCategory());
            if (normalizedLabel.equals(normalizedName) || normalizedLabel.equals(normalizedCategory)) {
                log.info("Product match strategy=normalized_exact label='{}' product='{}'",
                        normalizedLabel, safe(product.getName()));
                return Optional.of(product);
            }
        }

        Product best = null;
        double bestScore = 0.0;
        for (Product product : products) {
            String normalizedName = normalizeLabel(product.getName());
            String normalizedCategory = normalizeLabel(product.getCategory());
            String combined = (normalizedName + " " + normalizedCategory).trim();
            double score = Math.max(
                    fuzzySimilarity(normalizedLabel, normalizedName),
                    Math.max(
                            fuzzySimilarity(normalizedLabel, normalizedCategory),
                            fuzzySimilarity(normalizedLabel, combined)
                    )
            );
            if (score > bestScore) {
                bestScore = score;
                best = product;
            }
        }

        if (best != null && bestScore >= FUZZY_MATCH_THRESHOLD) {
            log.info("Product match strategy=fuzzy label='{}' product='{}' score={}",
                    normalizedLabel, safe(best.getName()), String.format("%.3f", bestScore));
            return Optional.of(best);
        }

        log.info("Product match strategy=none label='{}' bestScore={}",
                normalizedLabel, String.format("%.3f", bestScore));
        return Optional.empty();
    }

    private double fuzzySimilarity(String input, String candidate) {
        if (input == null || candidate == null || input.isBlank() || candidate.isBlank()) {
            return 0.0;
        }

        if (input.equals(candidate)) {
            return 1.0;
        }

        if (candidate.contains(input) || input.contains(candidate)) {
            return 0.9;
        }

        Set<String> inputTokens = tokens(input);
        Set<String> candidateTokens = tokens(candidate);
        if (inputTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0.0;
        }

        long intersection = inputTokens.stream().filter(candidateTokens::contains).count();
        long union = inputTokens.size() + candidateTokens.size() - intersection;
        if (union == 0) {
            return 0.0;
        }
        return (double) intersection / union;
    }

    private Set<String> tokens(String value) {
        return Arrays.stream(value.split("\\s+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
