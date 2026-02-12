package com.ecolens.ecolens_backend.service;

import java.util.ArrayList;
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

import com.ecolens.ecolens_backend.config.ScoringProperties;
import com.ecolens.ecolens_backend.dto.RecognitionResponse;
import com.ecolens.ecolens_backend.dto.ScoreFactor;
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
    private final ScoringProperties scoringProperties;

    public ProductService(ProductRepository productRepository, LLMService llmService, ScoringProperties scoringProperties) {
        this.productRepository = productRepository;
        this.llmService = llmService;
        this.scoringProperties = scoringProperties;
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

        RatingDecision ratingDecision = rateProduct(product);

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
        response.setEcoScore(ratingDecision.ecoScore());
        response.setCatalogEcoScore(ratingDecision.catalogEcoScore());
        response.setCo2Score(ratingDecision.co2Score());
        response.setCo2Gram(ratingDecision.co2Gram());
        response.setRecyclability(ratingDecision.recyclability());
        response.setAltRecommendation(ratingDecision.altRecommendation());
        response.setCatalogContribution(ratingDecision.catalogContribution());
        response.setCo2Contribution(ratingDecision.co2Contribution());
        response.setFeatureAdjustment(ratingDecision.featureAdjustment());
        response.setPreBoostScore(ratingDecision.preBoostScore());
        response.setGreenerAlternativeBoost(ratingDecision.greenerAlternativeBoost());
        response.setGreenerAlternativeBoostApplied(ratingDecision.greenerAlternative());
        response.setScoringVersion(scoringProperties.getVersion());
        response.setScoreFactors(ratingDecision.scoreFactors());
        String explanation = product.getExplanation() == null ? "" : product.getExplanation();
        if (llmService.isFallbackExplanation(explanation) || explanation.isBlank()) {
            explanation = ratingDecision.summary();
        }
        response.setExplanation(explanation);
        response.setConfidence(confidence);

        log.info("ProductService handled recognition: inputSource={}, label='{}', product='{}', llm={}, ratedEcoScore={}, greenerAlternative={}",
                inputSource, normalizedLabel, safe(product.getName()), generationStatus,
                ratingDecision.ecoScore(), ratingDecision.greenerAlternative());

        return response;
    }

    private Product createDefaultProduct(String detectedLabel) {
        String fallbackName = detectedLabel.isBlank() ? "Unknown Product" : detectedLabel;
        return new Product(
                fallbackName,
                "unknown",
                scoringProperties.getDefaultCatalogEcoScore(),
                scoringProperties.getDefaultCarbonImpactGram(),
                "Unknown",
                "Consider a reusable alternative",
                ""
        );
    }

    private RatingDecision rateProduct(Product product) {
        int catalogEcoScore = product.getEcoScore() == null
                ? scoringProperties.getDefaultCatalogEcoScore()
                : product.getEcoScore();
        double co2 = product.getCarbonImpact() == null
                ? scoringProperties.getDefaultCarbonImpactGram()
                : product.getCarbonImpact();
        int co2Score = computeCo2Score(co2);
        double catalogContribution = scoringProperties.getCatalogWeight() * catalogEcoScore;
        double co2Contribution = scoringProperties.getCo2Weight() * co2Score;
        String recyclability = safe(product.getRecyclability());
        String category = normalizeLabel(product.getCategory());
        String name = normalizeLabel(product.getName());
        String combined = (category + " " + name).trim();

        boolean singleUse = containsAny(combined, "single use", "single-use", "disposable", "plastic bottle", "plastic bag");
        boolean reusable = containsAny(combined, "reusable", "refillable", "cloth bag", "steel bottle", "led");
        FeatureAdjustmentResult featureAdjustmentResult = computeFeatureAdjustment(
                singleUse, reusable, combined, normalizeLabel(recyclability)
        );
        int featureAdjustment = featureAdjustmentResult.total();
        List<ScoreFactor> scoreFactors = new ArrayList<>();

        scoreFactors.add(new ScoreFactor(
                "catalog_weight",
                "Catalog eco score contribution",
                roundTwoDecimals(catalogContribution),
                "catalogEcoScore=" + catalogEcoScore + ", weight=" + scoringProperties.getCatalogWeight()
        ));
        scoreFactors.add(new ScoreFactor(
                "co2_weight",
                "CO2 score contribution",
                roundTwoDecimals(co2Contribution),
                "co2Score=" + co2Score + ", co2Gram=" + roundTwoDecimals(co2) + ", weight=" + scoringProperties.getCo2Weight()
        ));
        scoreFactors.addAll(featureAdjustmentResult.factors());

        double score = catalogContribution
                + co2Contribution
                + featureAdjustment;

        boolean greenerAlternative = reusable || score >= scoringProperties.getGreenerAlternativeThreshold();
        int greenerBoost = 0;
        if (greenerAlternative) {
            greenerBoost = scoringProperties.getGreenerAlternativeBoost();
            score += greenerBoost;
            scoreFactors.add(new ScoreFactor(
                    "greener_boost",
                    "Greener alternative boost",
                    (double) greenerBoost,
                    "threshold=" + scoringProperties.getGreenerAlternativeThreshold()
            ));
        }
        int ecoScore = clamp((int) Math.round(score), scoringProperties.getMinScore(), scoringProperties.getMaxScore());

        String recommendation;
        String summary;
        if (greenerAlternative) {
            recommendation = "Great choice. This is already a greener alternative.";
            summary = "This item is a greener alternative with a strong eco profile. Keep using reusable or refillable options.";
        } else if (ecoScore < scoringProperties.getHighImpactThreshold()) {
            recommendation = "Consider switching to reusable/refillable alternatives when possible.";
            summary = "This item has a relatively high environmental impact due to material or single-use pattern.";
        } else if (ecoScore < scoringProperties.getModerateImpactThreshold()) {
            recommendation = "Try a lower-impact alternative or improve recycling habits.";
            summary = "This item has a moderate impact and can be improved with better reuse or recycling choices.";
        } else {
            recommendation = "Good choice overall. Look for refill/reuse opportunities to improve further.";
            summary = "This item has a relatively good eco profile compared with common alternatives.";
        }

        return new RatingDecision(
                ecoScore,
                catalogEcoScore,
                co2Score,
                co2,
                recyclability,
                recommendation,
                summary,
                greenerAlternative,
                roundTwoDecimals(catalogContribution),
                roundTwoDecimals(co2Contribution),
                featureAdjustment,
                roundTwoDecimals(catalogContribution + co2Contribution + featureAdjustment),
                greenerBoost,
                scoreFactors
        );
    }

    private FeatureAdjustmentResult computeFeatureAdjustment(
            boolean singleUse,
            boolean reusable,
            String combined,
            String recyclabilityNormalized
    ) {
        ScoringProperties.Adjustments adjustments = scoringProperties.getAdjustments();
        int adjustment = 0;
        List<ScoreFactor> factors = new ArrayList<>();

        if (singleUse) {
            adjustment += adjustments.getSingleUsePenalty();
            factors.add(new ScoreFactor("single_use_penalty", "Single-use penalty",
                    (double) adjustments.getSingleUsePenalty(), "single-use/disposable pattern"));
        }
        if (reusable) {
            adjustment += adjustments.getReusableBonus();
            factors.add(new ScoreFactor("reusable_bonus", "Reusable bonus",
                    (double) adjustments.getReusableBonus(), "reusable/refillable pattern"));
        }
        if (containsAny(combined, "plastic")) {
            adjustment += adjustments.getPlasticPenalty();
            factors.add(new ScoreFactor("plastic_penalty", "Plastic material penalty",
                    (double) adjustments.getPlasticPenalty(), "material contains plastic"));
        }
        if (containsAny(combined, "paper")) {
            adjustment += adjustments.getPaperPenalty();
            factors.add(new ScoreFactor("paper_penalty", "Paper material adjustment",
                    (double) adjustments.getPaperPenalty(), "material contains paper"));
        }
        if (containsAny(combined, "aluminum", "glass")) {
            adjustment += adjustments.getAluminumGlassBonus();
            factors.add(new ScoreFactor("aluminum_glass_bonus", "Aluminum/Glass bonus",
                    (double) adjustments.getAluminumGlassBonus(), "material contains aluminum/glass"));
        }
        if (containsAny(combined, "cloth", "recycled")) {
            adjustment += adjustments.getClothRecycledBonus();
            factors.add(new ScoreFactor("cloth_recycled_bonus", "Cloth/Recycled bonus",
                    (double) adjustments.getClothRecycledBonus(), "material contains cloth/recycled"));
        }
        if (containsAny(recyclabilityNormalized, "high")) {
            adjustment += adjustments.getRecyclabilityHighBonus();
            factors.add(new ScoreFactor("recyclability_high_bonus", "High recyclability bonus",
                    (double) adjustments.getRecyclabilityHighBonus(), "recyclability=high"));
        } else if (containsAny(recyclabilityNormalized, "medium")) {
            adjustment += adjustments.getRecyclabilityMediumBonus();
            factors.add(new ScoreFactor("recyclability_medium_bonus", "Medium recyclability bonus",
                    (double) adjustments.getRecyclabilityMediumBonus(), "recyclability=medium"));
        } else if (containsAny(recyclabilityNormalized, "low", "unknown")) {
            adjustment += adjustments.getRecyclabilityLowPenalty();
            factors.add(new ScoreFactor("recyclability_low_penalty", "Low recyclability penalty",
                    (double) adjustments.getRecyclabilityLowPenalty(), "recyclability=low/unknown"));
        } else if (containsAny(recyclabilityNormalized, "organic")) {
            adjustment += adjustments.getRecyclabilityOrganicBonus();
            factors.add(new ScoreFactor("recyclability_organic_bonus", "Organic recyclability bonus",
                    (double) adjustments.getRecyclabilityOrganicBonus(), "recyclability=organic"));
        }

        return new FeatureAdjustmentResult(adjustment, factors);
    }

    private int computeCo2Score(double co2Gram) {
        Double minCo2 = productRepository.findMinCarbonImpact();
        Double maxCo2 = productRepository.findMaxCarbonImpact();
        if (minCo2 == null || maxCo2 == null || maxCo2 <= minCo2) {
            return scoringProperties.getDefaultCo2Score();
        }

        double normalized = (co2Gram - minCo2) / (maxCo2 - minCo2);
        normalized = Math.max(0.0, Math.min(1.0, normalized));
        double inverseNormalized = 1.0 - normalized;
        int range = scoringProperties.getMaxScore() - scoringProperties.getMinScore();
        int co2Score = (int) Math.round(scoringProperties.getMinScore() + (inverseNormalized * range));
        return clamp(co2Score, scoringProperties.getMinScore(), scoringProperties.getMaxScore());
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

    private boolean containsAny(String value, String... phrases) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String phrase : phrases) {
            if (value.contains(normalizeLabel(phrase))) {
                return true;
            }
        }
        return false;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record RatingDecision(
            int ecoScore,
            int catalogEcoScore,
            int co2Score,
            double co2Gram,
            String recyclability,
            String altRecommendation,
            String summary,
            boolean greenerAlternative,
            double catalogContribution,
            double co2Contribution,
            int featureAdjustment,
            double preBoostScore,
            int greenerAlternativeBoost,
            List<ScoreFactor> scoreFactors
    ) {
    }

    private record FeatureAdjustmentResult(int total, List<ScoreFactor> factors) {
    }
}
