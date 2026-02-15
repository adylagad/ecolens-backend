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

import com.ecolens.ecolens_backend.config.CatalogProperties;
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
    private static final double DEFAULT_METADATA_INFERENCE_CONFIDENCE_MULTIPLIER = 0.84;
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
            Map.entry("food container", "food packaging"),
            Map.entry("blue pen", "pen"),
            Map.entry("black pen", "pen"),
            Map.entry("red pen", "pen"),
            Map.entry("ballpoint pen", "pen"),
            Map.entry("gel pen", "pen"),
            Map.entry("fountain pen", "pen"),
            Map.entry("marker pen", "marker"),
            Map.entry("highlighter pen", "highlighter"),
            Map.entry("shoes", "footwear"),
            Map.entry("shoe", "footwear"),
            Map.entry("sneaker", "footwear"),
            Map.entry("sneakers", "footwear"),
            Map.entry("running shoe", "footwear"),
            Map.entry("running shoes", "footwear"),
            Map.entry("boots", "footwear"),
            Map.entry("boot", "footwear"),
            Map.entry("sandals", "footwear"),
            Map.entry("slippers", "footwear")
    );
    private static final List<MetadataInferenceRule> METADATA_INFERENCE_RULES = List.of(
            new MetadataInferenceRule("plastic_bottle", List.of("plastic bottle", "water bottle"),
                    "plastic", Boolean.FALSE, Boolean.TRUE, 0, "single_use", "Low", 0.82),
            new MetadataInferenceRule("reusable_bottle", List.of("reusable bottle", "steel bottle", "insulated bottle"),
                    "stainless steel", Boolean.TRUE, Boolean.FALSE, 35, "reusable", "High", 0.9),
            new MetadataInferenceRule("paper_cup", List.of("paper cup", "coffee cup", "disposable cup"),
                    "paper lined", Boolean.FALSE, Boolean.TRUE, 20, "single_use", "Medium", 0.84),
            new MetadataInferenceRule("food_packaging_single_use", List.of("food packaging", "food container", "takeaway container"),
                    "mixed plastic", Boolean.FALSE, Boolean.TRUE, 0, "single_use", "Low", 0.82),
            new MetadataInferenceRule("glass_container", List.of("glass bottle", "glass container"),
                    "glass", Boolean.TRUE, Boolean.FALSE, 35, "reusable", "High", 0.9),
            new MetadataInferenceRule("plastic_bag", List.of("plastic bag", "grocery bag"),
                    "plastic", Boolean.FALSE, Boolean.TRUE, 0, "single_use", "Low", 0.82),
            new MetadataInferenceRule("cloth_bag", List.of("cloth bag", "jute bag", "shopping bag"),
                    "cloth", Boolean.TRUE, Boolean.FALSE, 60, "reusable", "High", 0.9),
            new MetadataInferenceRule("disposable_utensils", List.of("plastic straw", "disposable cutlery", "plastic fork"),
                    "plastic", Boolean.FALSE, Boolean.TRUE, 0, "single_use", "Low", 0.82),
            new MetadataInferenceRule("reusable_utensils", List.of("metal straw", "reusable cutlery"),
                    "stainless steel", Boolean.TRUE, Boolean.FALSE, 30, "reusable", "High", 0.9),
            new MetadataInferenceRule("fast_fashion", List.of("fast fashion", "polyester shirt"),
                    "polyester", Boolean.FALSE, Boolean.FALSE, 0, "fast_fashion", "Low", 0.8),
            new MetadataInferenceRule("slow_fashion", List.of("second hand", "denim jacket"),
                    "denim", Boolean.TRUE, Boolean.FALSE, 0, "long_life", "Medium", 0.88),
            new MetadataInferenceRule("footwear", List.of("footwear", "shoe", "shoes", "sneaker", "running shoe", "boot", "sandals", "slippers"),
                    "mixed textile and rubber", Boolean.TRUE, Boolean.FALSE, 5, "long_life", "Medium", 0.88),
            new MetadataInferenceRule("stationery_pen", List.of("pen", "marker", "highlighter", "pencil", "stylus"),
                    "plastic and ink", Boolean.TRUE, Boolean.FALSE, 0, "long_life", "Medium", 0.9),
            new MetadataInferenceRule("nature_positive_living_item",
                    List.of("tree", "sapling", "seedling", "houseplant", "potted plant", "flower", "shrub", "plant"),
                    "organic", Boolean.FALSE, Boolean.FALSE, 0, "living_natural", "Organic", 0.96)
    );

    private final ProductRepository productRepository;
    private final MongoAtlasRuntimeStore mongoAtlasRuntimeStore;
    private final LLMService llmService;
    private final ScoringProperties scoringProperties;
    private final CatalogProperties catalogProperties;

    public ProductService(
            ProductRepository productRepository,
            MongoAtlasRuntimeStore mongoAtlasRuntimeStore,
            LLMService llmService,
            ScoringProperties scoringProperties,
            CatalogProperties catalogProperties
    ) {
        this.productRepository = productRepository;
        this.mongoAtlasRuntimeStore = mongoAtlasRuntimeStore;
        this.llmService = llmService;
        this.scoringProperties = scoringProperties;
        this.catalogProperties = catalogProperties;
    }

    public RecognitionResponse handleRecognition(String detectedLabel, String imageBase64, double confidence) {
        log.info("Model routing for recognition request: textModel={}, visionModel={}",
                llmService.getConfiguredTextModel(), llmService.getConfiguredVisionModel());

        String providedLabel = canonicalizeLabel(normalizeLabel(detectedLabel));
        String labelForLookup = providedLabel;
        boolean hasImage = imageBase64 != null && !imageBase64.isBlank();
        String inputSource;
        if (hasImage) {
            inputSource = "image";
            log.info("Recognition input source=image autoDetectRequested=true labelHint='{}'", providedLabel);
            String detectedFromImage = canonicalizeLabel(normalizeLabel(llmService.detectLabelFromImage(imageBase64)));
            if (!detectedFromImage.isBlank()) {
                labelForLookup = detectedFromImage;
                log.info("Gemini image detected label='{}'", labelForLookup);
            } else if (!providedLabel.isBlank()) {
                labelForLookup = providedLabel;
                inputSource = "image_with_label_hint";
                log.info("Gemini image label empty. Falling back to provided label hint='{}'", labelForLookup);
            } else {
                labelForLookup = "";
                log.warn("Gemini image label empty and no provided label hint.");
            }
        } else if (!providedLabel.isBlank()) {
            inputSource = "text";
            log.info("Recognition input source=text providedLabel='{}'", labelForLookup);
        } else {
            inputSource = "none";
            log.warn("Recognition input source=none: no text label and no image payload.");
        }

        String normalizedLabel = labelForLookup;
        String generationStatus = "skipped_cached_explanation";

        ProductMatchResult productMatchResult = findBestProduct(normalizedLabel);
        Product product = productMatchResult.product().orElseGet(() -> createDefaultProduct(normalizedLabel));
        if (!normalizedLabel.isBlank()) {
            String productNameNormalized = normalizeLabel(product.getName());
            if (isMissingText(productNameNormalized) || "unknown product".equals(productNameNormalized)) {
                product.setName(toDisplayLabel(normalizedLabel));
            }
            String productCategoryNormalized = normalizeLabel(product.getCategory());
            if (isMissingText(productCategoryNormalized) || "unknown".equals(productCategoryNormalized)) {
                product.setCategory(normalizedLabel);
            }
        }

        MetadataResolution metadataResolution = resolveMetadata(product, normalizedLabel);
        boolean autoLearned = false;
        if (shouldAutoLearnProduct(productMatchResult, normalizedLabel, confidence, hasImage)) {
            product = upsertAutoLearnedProduct(normalizedLabel, metadataResolution);
            metadataResolution = resolveMetadata(product, normalizedLabel);
            productMatchResult = new ProductMatchResult(Optional.of(product), "auto_learned", 0.0);
            autoLearned = true;
        }

        RatingDecision ratingDecision = rateProduct(product, metadataResolution);

        boolean shouldAttemptLlmExplanation =
                "exact".equals(productMatchResult.strategy()) && !metadataResolution.inferred();
        if (product.getExplanation() == null || product.getExplanation().isBlank()) {
            if (shouldAttemptLlmExplanation) {
                generationStatus = "attempted";
                try {
                    String generatedExplanation = llmService.generateExplanation(product);
                    if (!llmService.isFallbackExplanation(generatedExplanation)) {
                        product.setExplanation(generatedExplanation);
                        product = saveProduct(product);
                        generationStatus = "attempted_saved";
                    } else {
                        generationStatus = "attempted_fallback";
                    }
                } catch (Exception ex) {
                    generationStatus = "attempted_failed";
                    log.warn("Explanation generation failed for product={}: {}", safe(product.getName()), ex.getMessage());
                }
            } else {
                generationStatus = "skipped_rule_based_explanation";
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
        double catalogCoverage = computeCatalogCoverage(productMatchResult, metadataResolution);
        response.setCatalogMatchStrategy(productMatchResult.strategy());
        response.setCatalogCoverage(roundThreeDecimals(catalogCoverage));
        response.setCatalogAutoLearned(autoLearned);
        String explanation = product.getExplanation() == null ? "" : product.getExplanation();
        if (llmService.isFallbackExplanation(explanation) || explanation.isBlank()) {
            explanation = ratingDecision.summary();
        }
        response.setExplanation(explanation);
        double adjustedConfidence = deriveConfidence(
                confidence,
                inputSource,
                normalizedLabel,
                productMatchResult,
                metadataResolution
        );
        response.setConfidence(roundThreeDecimals(adjustedConfidence));

        if (metadataResolution.inferred()) {
            log.info("Metadata inference applied: rule={}, fields={}, confidence={}=>{}",
                    metadataResolution.ruleCode(),
                    metadataResolution.inferredFields(),
                    roundThreeDecimals(clampDouble(confidence, 0.0, 1.0)),
                    roundThreeDecimals(adjustedConfidence));
        }
        log.info("Catalog coverage: strategy={}, coverage={}, autoLearned={}",
                productMatchResult.strategy(), roundThreeDecimals(catalogCoverage), autoLearned);

        log.info("ProductService handled recognition: inputSource={}, label='{}', product='{}', llm={}, ratedEcoScore={}, greenerAlternative={}",
                inputSource, normalizedLabel, safe(product.getName()), generationStatus,
                ratingDecision.ecoScore(), ratingDecision.greenerAlternative());

        return response;
    }

    private Product createDefaultProduct(String detectedLabel) {
        String fallbackName = detectedLabel.isBlank() ? "Unknown Product" : toDisplayLabel(detectedLabel);
        String fallbackCategory = detectedLabel.isBlank() ? "unknown" : detectedLabel;
        boolean naturePositiveLabel = isNaturePositiveLabel(detectedLabel);
        int fallbackEcoScore = naturePositiveLabel
                ? clamp(
                Math.max(scoringProperties.getGreenerAlternativeThreshold(), scoringProperties.getModerateImpactThreshold()),
                scoringProperties.getMinScore(),
                scoringProperties.getMaxScore())
                : scoringProperties.getDefaultCatalogEcoScore();
        double fallbackCo2 = naturePositiveLabel
                ? Math.max(1.0, Math.min(12.0, scoringProperties.getDefaultCarbonImpactGram() * 0.05))
                : scoringProperties.getDefaultCarbonImpactGram();
        String fallbackRecyclability = naturePositiveLabel ? "Organic" : "Unknown";
        String fallbackAlternative = naturePositiveLabel
                ? "Already eco-positive. Keep protecting and maintaining this natural item."
                : "Consider a reusable alternative";
        String fallbackMaterial = naturePositiveLabel ? "organic" : "";
        Boolean fallbackReusable = naturePositiveLabel ? Boolean.FALSE : null;
        Boolean fallbackSingleUse = naturePositiveLabel ? Boolean.FALSE : null;
        Integer fallbackRecycledContent = naturePositiveLabel ? 0 : null;
        String fallbackLifecycle = naturePositiveLabel ? "living_natural" : "";
        return new Product(
                fallbackName,
                fallbackCategory,
                fallbackEcoScore,
                fallbackCo2,
                fallbackRecyclability,
                fallbackAlternative,
                "",
                fallbackMaterial,
                fallbackReusable,
                fallbackSingleUse,
                fallbackRecycledContent,
                fallbackLifecycle
        );
    }

    private boolean shouldAutoLearnProduct(
            ProductMatchResult productMatchResult,
            String normalizedLabel,
            double confidence,
            boolean hasImage
    ) {
        if (!catalogProperties.isAutoLearnEnabled()) {
            return false;
        }
        if (productMatchResult.product().isPresent()) {
            return false;
        }
        if (catalogProperties.isAutoLearnRequireImage() && !hasImage) {
            return false;
        }
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return false;
        }
        if (isMissingText(normalizedLabel) || "unknown product".equals(normalizedLabel)) {
            return false;
        }
        return clampDouble(confidence, 0.0, 1.0) >= clampDouble(catalogProperties.getAutoLearnMinConfidence(), 0.0, 1.0);
    }

    private Product upsertAutoLearnedProduct(String normalizedLabel, MetadataResolution metadataResolution) {
        String category = normalizedLabel;
        String displayName = toDisplayLabel(normalizedLabel);

        Optional<Product> existing = findByNameIgnoreCase(displayName);
        if (existing.isEmpty()) {
            existing = findFirstByCategoryIgnoreCase(category);
        }
        if (existing.isPresent()) {
            return existing.get();
        }

        boolean inferredMaterial = metadataResolution.inferredFields().contains("material");
        boolean inferredReusable = metadataResolution.inferredFields().contains("isReusable");
        boolean inferredSingleUse = metadataResolution.inferredFields().contains("isSingleUse");
        boolean inferredRecycledContent = metadataResolution.inferredFields().contains("recycledContentPercent");
        boolean inferredLifecycle = metadataResolution.inferredFields().contains("lifecycleType");
        boolean inferredRecyclability = metadataResolution.inferredFields().contains("recyclability");
        boolean naturePositiveLabel = isNaturePositiveLabel(normalizedLabel);

        int learnedEcoScore = naturePositiveLabel
                ? clamp(
                Math.max(scoringProperties.getGreenerAlternativeThreshold(), scoringProperties.getModerateImpactThreshold()),
                scoringProperties.getMinScore(),
                scoringProperties.getMaxScore())
                : scoringProperties.getDefaultCatalogEcoScore();
        double learnedCo2 = naturePositiveLabel
                ? Math.max(1.0, Math.min(12.0, scoringProperties.getDefaultCarbonImpactGram() * 0.05))
                : scoringProperties.getDefaultCarbonImpactGram();
        String learnedRecyclability = inferredRecyclability
                ? metadataResolution.recyclability()
                : naturePositiveLabel ? "Organic" : "Unknown";
        String learnedAlternative = naturePositiveLabel
                ? "Already eco-positive. Keep protecting and maintaining this natural item."
                : Boolean.TRUE.equals(inferredReusable ? metadataResolution.reusable() : null)
                ? "Great choice. Keep using reusable options."
                : "Consider switching to reusable/refillable alternatives.";
        String learnedMaterial = inferredMaterial
                ? metadataResolution.material()
                : naturePositiveLabel ? "organic" : "";
        Boolean learnedReusable = null;
        if (inferredReusable) {
            learnedReusable = metadataResolution.reusable();
        } else if (naturePositiveLabel) {
            learnedReusable = Boolean.FALSE;
        }

        Boolean learnedSingleUse = null;
        if (inferredSingleUse) {
            learnedSingleUse = metadataResolution.singleUse();
        } else if (naturePositiveLabel) {
            learnedSingleUse = Boolean.FALSE;
        }
        Integer learnedRecycledContent = inferredRecycledContent
                ? metadataResolution.recycledContentPercent()
                : 0;
        String learnedLifecycle = inferredLifecycle
                ? metadataResolution.lifecycleType()
                : naturePositiveLabel ? "living_natural" : "";

        Product learned = new Product(
                displayName,
                category,
                learnedEcoScore,
                learnedCo2,
                learnedRecyclability,
                learnedAlternative,
                "",
                learnedMaterial,
                learnedReusable,
                learnedSingleUse,
                learnedRecycledContent,
                learnedLifecycle
        );
        Product saved = saveProduct(learned);
        log.info("Catalog auto-learned new product: label='{}', savedName='{}', category='{}'",
                normalizedLabel, saved.getName(), saved.getCategory());
        return saved;
    }

    private MetadataResolution resolveMetadata(Product product, String normalizedLabel) {
        String combined = normalizeLabel((safe(product.getCategory()) + " " + safe(product.getName())).trim());
        String materialNormalized = normalizeLabel(product.getMaterial());
        String lifecycleNormalized = normalizeLabel(product.getLifecycleType());
        String recyclabilityRaw = safe(product.getRecyclability());

        boolean materialMissing = isMissingText(materialNormalized);
        boolean lifecycleMissing = isMissingText(lifecycleNormalized);
        boolean reusableMissing = product.getReusable() == null;
        boolean singleUseMissing = product.getSingleUse() == null;
        boolean recycledContentMissing = product.getRecycledContentPercent() == null;
        boolean recyclabilityMissing = isMissingText(normalizeLabel(recyclabilityRaw));

        boolean anyMissing = materialMissing || lifecycleMissing || reusableMissing
                || singleUseMissing || recycledContentMissing || recyclabilityMissing;

        Optional<MetadataInferenceRule> ruleOptional = anyMissing
                ? matchInferenceRule(normalizedLabel, combined)
                : Optional.empty();

        String resolvedMaterial = materialNormalized;
        String resolvedLifecycle = lifecycleNormalized;
        boolean resolvedReusable = resolveReusable(product, lifecycleNormalized, combined);
        boolean resolvedSingleUse = resolveSingleUse(product, lifecycleNormalized, combined);
        int resolvedRecycledContent = product.getRecycledContentPercent() == null
                ? -1
                : clamp(product.getRecycledContentPercent(), 0, 100);
        String resolvedRecyclability = recyclabilityRaw;
        List<String> inferredFields = new ArrayList<>();
        String ruleCode = "none";
        double confidenceMultiplier = 1.0;

        if (ruleOptional.isPresent()) {
            MetadataInferenceRule rule = ruleOptional.get();
            ruleCode = rule.code();
            confidenceMultiplier = clampDouble(rule.confidenceMultiplier(), 0.0, 1.0);
            if (materialMissing && !isMissingText(normalizeLabel(rule.material()))) {
                resolvedMaterial = normalizeLabel(rule.material());
                inferredFields.add("material");
            }
            if (lifecycleMissing && !isMissingText(normalizeLabel(rule.lifecycleType()))) {
                resolvedLifecycle = normalizeLabel(rule.lifecycleType());
                inferredFields.add("lifecycleType");
            }
            if (reusableMissing && rule.reusable() != null) {
                resolvedReusable = rule.reusable();
                inferredFields.add("isReusable");
            }
            if (singleUseMissing && rule.singleUse() != null) {
                resolvedSingleUse = rule.singleUse();
                inferredFields.add("isSingleUse");
            }
            if (recycledContentMissing && rule.recycledContentPercent() != null) {
                resolvedRecycledContent = clamp(rule.recycledContentPercent(), 0, 100);
                inferredFields.add("recycledContentPercent");
            }
            if (recyclabilityMissing && !isMissingText(normalizeLabel(rule.recyclability()))) {
                resolvedRecyclability = rule.recyclability();
                inferredFields.add("recyclability");
            }
        }

        if (resolvedRecycledContent < 0) {
            resolvedRecycledContent = 0;
        }
        if (isMissingText(resolvedMaterial)) {
            resolvedMaterial = normalizeLabel(combined);
        }
        if (isMissingText(resolvedLifecycle)) {
            resolvedLifecycle = normalizeLabel(product.getLifecycleType());
        }
        if (isMissingText(normalizeLabel(resolvedRecyclability))) {
            resolvedRecyclability = "Unknown";
        }

        return new MetadataResolution(
                resolvedMaterial,
                resolvedLifecycle,
                resolvedReusable,
                resolvedSingleUse,
                resolvedRecycledContent,
                resolvedRecyclability,
                !inferredFields.isEmpty(),
                inferredFields,
                inferredFields.isEmpty() ? 1.0 : confidenceMultiplier,
                ruleCode
        );
    }

    private RatingDecision rateProduct(Product product, MetadataResolution metadataResolution) {
        int catalogEcoScore = product.getEcoScore() == null
                ? scoringProperties.getDefaultCatalogEcoScore()
                : product.getEcoScore();
        double co2 = product.getCarbonImpact() == null
                ? scoringProperties.getDefaultCarbonImpactGram()
                : product.getCarbonImpact();
        Co2ScoreResult co2ScoreResult = computeCo2Score(co2);
        int co2Score = co2ScoreResult.score();
        double catalogContribution = scoringProperties.getCatalogWeight() * catalogEcoScore;
        double co2Contribution = scoringProperties.getCo2Weight() * co2Score;
        String recyclability = metadataResolution.recyclability();
        String recyclabilityNormalized = normalizeLabel(recyclability);
        String category = normalizeLabel(product.getCategory());
        String name = normalizeLabel(product.getName());
        String combined = (category + " " + name).trim();
        String material = metadataResolution.material();
        String lifecycleType = metadataResolution.lifecycleType();

        boolean reusable = metadataResolution.reusable();
        boolean singleUse = metadataResolution.singleUse();
        FeatureAdjustmentResult featureAdjustmentResult = computeFeatureAdjustment(
                singleUse, reusable, material, combined, recyclabilityNormalized, lifecycleType, metadataResolution.recycledContentPercent()
        );
        int featureAdjustment = featureAdjustmentResult.total();
        List<ScoreFactor> scoreFactors = new ArrayList<>();
        boolean naturePositiveItem = isNaturePositiveItem(combined, material, lifecycleType);

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
                        + ", " + co2ScoreResult.detail()
        ));
        if (metadataResolution.inferred()) {
            scoreFactors.add(new ScoreFactor(
                    "metadata_inference",
                    "Metadata inferred from label/category",
                    0.0,
                    "rule=" + metadataResolution.ruleCode()
                            + ", fields=" + String.join("|", metadataResolution.inferredFields())
                            + ", confidenceMultiplier=" + metadataResolution.confidenceMultiplier()
            ));
        }
        if (naturePositiveItem) {
            scoreFactors.add(new ScoreFactor(
                    "nature_positive_context",
                    "Nature-positive context detected",
                    0.0,
                    "label/material/lifecycle indicates a living natural item"
            ));
        }
        scoreFactors.addAll(featureAdjustmentResult.factors());

        double score = catalogContribution
                + co2Contribution
                + featureAdjustment;

        boolean greenerAlternative =
                reusable || score >= scoringProperties.getGreenerAlternativeThreshold() || naturePositiveItem;
        int greenerBoost = 0;
        if (greenerAlternative) {
            greenerBoost = scoringProperties.getGreenerAlternativeBoost();
            score += greenerBoost;
            scoreFactors.add(new ScoreFactor(
                    "greener_boost",
                    "Greener alternative boost",
                    (double) greenerBoost,
                    naturePositiveItem
                            ? "reason=nature_positive_context"
                            : "threshold=" + scoringProperties.getGreenerAlternativeThreshold()
            ));
        }
        int ecoScore = clamp((int) Math.round(score), scoringProperties.getMinScore(), scoringProperties.getMaxScore());

        String recommendation;
        String summary;
        if (naturePositiveItem) {
            recommendation = "Already eco-positive. Protect and maintain this natural item.";
            summary = "This appears to be a natural living item with inherently positive environmental impact. "
                    + "No greener replacement is needed.";
        } else if (greenerAlternative) {
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
            String material,
            String combined,
            String recyclabilityNormalized,
            String lifecycleType,
            Integer recycledContentPercent
    ) {
        ScoringProperties.Adjustments adjustments = scoringProperties.getAdjustments();
        ScoringProperties.FeatureThresholds thresholds = scoringProperties.getFeatureThresholds();
        int adjustment = 0;
        List<ScoreFactor> factors = new ArrayList<>();
        String materialContext = material == null || material.isBlank() ? combined : material;
        String materialSource = material == null || material.isBlank() ? "fallback_name_category" : "catalog_material";
        int recycledContent = recycledContentPercent == null ? -1 : clamp(recycledContentPercent, 0, 100);

        if (singleUse) {
            adjustment += adjustments.getSingleUsePenalty();
            factors.add(new ScoreFactor("single_use_penalty", "Single-use penalty",
                    (double) adjustments.getSingleUsePenalty(), "lifecycle indicates single-use"));
        }
        if (reusable) {
            adjustment += adjustments.getReusableBonus();
            factors.add(new ScoreFactor("reusable_bonus", "Reusable bonus",
                    (double) adjustments.getReusableBonus(), "isReusable=true or lifecycle indicates reusable"));
        }
        if (containsAny(lifecycleType, "refillable")) {
            adjustment += adjustments.getRefillableLifecycleBonus();
            factors.add(new ScoreFactor("refillable_lifecycle_bonus", "Refillable lifecycle bonus",
                    (double) adjustments.getRefillableLifecycleBonus(), "lifecycleType=refillable"));
        }
        if (containsAny(lifecycleType, "long life", "long_life", "durable")) {
            adjustment += adjustments.getLongLifeLifecycleBonus();
            factors.add(new ScoreFactor("long_life_lifecycle_bonus", "Long-life lifecycle bonus",
                    (double) adjustments.getLongLifeLifecycleBonus(), "lifecycleType=long_life/durable"));
        }
        if (containsAny(lifecycleType, "biodegradable", "compostable")) {
            adjustment += adjustments.getBiodegradableLifecycleBonus();
            factors.add(new ScoreFactor("biodegradable_lifecycle_bonus", "Biodegradable lifecycle bonus",
                    (double) adjustments.getBiodegradableLifecycleBonus(), "lifecycleType=biodegradable/compostable"));
        }
        if (containsAny(lifecycleType, "living natural", "living_natural", "nature positive", "natural living")) {
            adjustment += adjustments.getNaturePositiveBonus();
            factors.add(new ScoreFactor("nature_positive_bonus", "Natural item bonus",
                    (double) adjustments.getNaturePositiveBonus(), "lifecycleType=living_natural"));
        }
        if (containsAny(materialContext, "plastic", "polystyrene", "polyester")) {
            adjustment += adjustments.getPlasticPenalty();
            factors.add(new ScoreFactor("plastic_penalty", "Plastic material penalty",
                    (double) adjustments.getPlasticPenalty(), "source=" + materialSource + ", material contains plastic"));
        }
        if (containsAny(materialContext, "paper", "carton")) {
            adjustment += adjustments.getPaperPenalty();
            factors.add(new ScoreFactor("paper_penalty", "Paper material adjustment",
                    (double) adjustments.getPaperPenalty(), "source=" + materialSource + ", material contains paper/carton"));
        }
        if (containsAny(materialContext, "aluminum", "glass")) {
            adjustment += adjustments.getAluminumGlassBonus();
            factors.add(new ScoreFactor("aluminum_glass_bonus", "Aluminum/Glass bonus",
                    (double) adjustments.getAluminumGlassBonus(), "source=" + materialSource + ", material contains aluminum/glass"));
        }
        if (containsAny(materialContext, "cloth", "jute", "bamboo", "beeswax")) {
            adjustment += adjustments.getClothRecycledBonus();
            factors.add(new ScoreFactor("cloth_recycled_bonus", "Cloth/Recycled bonus",
                    (double) adjustments.getClothRecycledBonus(), "source=" + materialSource + ", material contains cloth/jute/bamboo/beeswax"));
        }
        if (recycledContent >= thresholds.getRecycledContentHighPercent()) {
            adjustment += adjustments.getRecycledContentHighBonus();
            factors.add(new ScoreFactor("recycled_content_high_bonus", "High recycled-content bonus",
                    (double) adjustments.getRecycledContentHighBonus(),
                    "recycledContentPercent=" + recycledContent + ", threshold=" + thresholds.getRecycledContentHighPercent()));
        } else if (recycledContent >= thresholds.getRecycledContentMediumPercent()) {
            adjustment += adjustments.getRecycledContentMediumBonus();
            factors.add(new ScoreFactor("recycled_content_medium_bonus", "Medium recycled-content bonus",
                    (double) adjustments.getRecycledContentMediumBonus(),
                    "recycledContentPercent=" + recycledContent + ", threshold=" + thresholds.getRecycledContentMediumPercent()));
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

    private Co2ScoreResult computeCo2Score(double co2Gram) {
        List<Double> distribution = findAllCarbonImpactsOrdered();
        if (distribution == null || distribution.isEmpty()) {
            return new Co2ScoreResult(scoringProperties.getDefaultCo2Score(), "method=default;reason=missing_distribution");
        }

        double percentileRank = computePercentileRank(co2Gram, distribution);
        ScoringProperties.Co2Normalization normalization = scoringProperties.getCo2Normalization();
        double lowerPercentile = clampDouble(normalization.getLowerPercentile(), 0.0, 1.0);
        double upperPercentile = clampDouble(normalization.getUpperPercentile(), 0.0, 1.0);
        if (upperPercentile <= lowerPercentile) {
            lowerPercentile = 0.0;
            upperPercentile = 1.0;
        }

        double normalized = (percentileRank - lowerPercentile) / (upperPercentile - lowerPercentile);
        normalized = clampDouble(normalized, 0.0, 1.0);
        double inverseNormalized = 1.0 - normalized;
        int range = scoringProperties.getMaxScore() - scoringProperties.getMinScore();
        int co2Score = (int) Math.round(scoringProperties.getMinScore() + (inverseNormalized * range));
        String detail = "method=percentile_rank, percentileRank=" + roundThreeDecimals(percentileRank)
                + ", bounds=[" + roundThreeDecimals(lowerPercentile) + "," + roundThreeDecimals(upperPercentile) + "]"
                + ", sampleSize=" + distribution.size();
        return new Co2ScoreResult(clamp(co2Score, scoringProperties.getMinScore(), scoringProperties.getMaxScore()), detail);
    }

    private double computePercentileRank(double value, List<Double> sortedValues) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0.5;
        }
        if (sortedValues.size() == 1) {
            return 0.5;
        }

        int lowerBound = firstIndexGreaterOrEqual(sortedValues, value);
        int upperBound = firstIndexGreater(sortedValues, value);
        double rank = lowerBound;
        if (upperBound > lowerBound) {
            rank = lowerBound + ((upperBound - lowerBound - 1) / 2.0);
        }
        return clampDouble(rank / (sortedValues.size() - 1.0), 0.0, 1.0);
    }

    private int firstIndexGreaterOrEqual(List<Double> values, double target) {
        int low = 0;
        int high = values.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (values.get(mid) >= target) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private int firstIndexGreater(List<Double> values, double target) {
        int low = 0;
        int high = values.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (values.get(mid) > target) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private boolean resolveSingleUse(Product product, String lifecycleType, String combined) {
        if (product.getSingleUse() != null) {
            return product.getSingleUse();
        }
        if (containsAny(lifecycleType, "single use", "single_use", "single-use", "disposable")) {
            return true;
        }
        return containsAny(combined, "single use", "single-use", "disposable", "plastic bottle", "plastic bag");
    }

    private boolean resolveReusable(Product product, String lifecycleType, String combined) {
        if (product.getReusable() != null) {
            return product.getReusable();
        }
        if (containsAny(lifecycleType, "reusable", "refillable", "long life", "long_life", "durable")) {
            return true;
        }
        return containsAny(combined, "reusable", "refillable", "cloth bag", "steel bottle", "led");
    }

    private Optional<MetadataInferenceRule> matchInferenceRule(String normalizedLabel, String combined) {
        String inferenceContext = (safe(normalizedLabel) + " " + safe(combined)).trim();
        for (MetadataInferenceRule rule : METADATA_INFERENCE_RULES) {
            for (String phrase : rule.matchPhrases()) {
                String normalizedPhrase = normalizeLabel(phrase);
                if (!normalizedPhrase.isBlank() && inferenceContext.contains(normalizedPhrase)) {
                    return Optional.of(rule);
                }
            }
        }
        return Optional.empty();
    }

    private double applyMetadataConfidencePenalty(double confidence, MetadataResolution metadataResolution) {
        double normalizedConfidence = clampDouble(confidence, 0.0, 1.0);
        if (!metadataResolution.inferred()) {
            return normalizedConfidence;
        }
        double fieldPenalty = 1.0 - Math.min(0.18, metadataResolution.inferredFields().size() * 0.03);
        double multiplier = metadataResolution.confidenceMultiplier() <= 0.0
                ? DEFAULT_METADATA_INFERENCE_CONFIDENCE_MULTIPLIER
                : metadataResolution.confidenceMultiplier();
        return clampDouble(normalizedConfidence * multiplier * fieldPenalty, 0.0, 1.0);
    }

    private double deriveConfidence(
            double requestConfidence,
            String inputSource,
            String normalizedLabel,
            ProductMatchResult productMatchResult,
            MetadataResolution metadataResolution
    ) {
        double normalizedRequest = clampDouble(requestConfidence, 0.0, 1.0);
        double strategyConfidence;
        switch (productMatchResult.strategy()) {
            case "exact":
                strategyConfidence = 0.96;
                break;
            case "fuzzy":
                strategyConfidence = clampDouble(Math.max(0.4, productMatchResult.score()), 0.0, 1.0);
                break;
            case "auto_learned":
                strategyConfidence = 0.55;
                break;
            default:
                strategyConfidence = 0.28;
                break;
        }

        double blended = (normalizedRequest * 0.35) + (strategyConfidence * 0.65);
        if (inputSource != null && inputSource.startsWith("image") && (normalizedLabel == null || normalizedLabel.isBlank())) {
            blended = Math.min(blended, 0.3);
        }
        if ("none".equals(inputSource)) {
            blended = Math.min(blended, 0.25);
        }
        return applyMetadataConfidencePenalty(blended, metadataResolution);
    }

    private double computeCatalogCoverage(ProductMatchResult productMatchResult, MetadataResolution metadataResolution) {
        CatalogProperties.Coverage coverage = catalogProperties.getCoverage();
        double value;
        switch (productMatchResult.strategy()) {
            case "exact":
                value = coverage.getExact();
                break;
            case "fuzzy":
                value = Math.max(coverage.getFuzzyMin(), productMatchResult.score());
                break;
            case "auto_learned":
                value = coverage.getAutoLearned();
                break;
            default:
                value = coverage.getNone();
                break;
        }
        if (metadataResolution.inferred() && !"exact".equals(productMatchResult.strategy())) {
            value *= coverage.getInferencePenalty();
        }
        return clampDouble(value, 0.0, 1.0);
    }

    private String toDisplayLabel(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return "Unknown Product";
        }
        String[] parts = normalizedLabel.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
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

    private boolean isMissingText(String value) {
        String normalized = normalizeLabel(value);
        return normalized.isBlank()
                || "unknown".equals(normalized)
                || "n a".equals(normalized)
                || "na".equals(normalized)
                || "none".equals(normalized);
    }

    private ProductMatchResult findBestProduct(String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            return new ProductMatchResult(Optional.empty(), "none", 0.0);
        }

        List<Product> products = findAllProducts();
        for (Product product : products) {
            String normalizedName = normalizeLabel(product.getName());
            String normalizedCategory = normalizeLabel(product.getCategory());
            if (normalizedLabel.equals(normalizedName) || normalizedLabel.equals(normalizedCategory)) {
                log.info("Product match strategy=normalized_exact label='{}' product='{}'",
                        normalizedLabel, safe(product.getName()));
                return new ProductMatchResult(Optional.of(product), "exact", 1.0);
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
            return new ProductMatchResult(Optional.of(best), "fuzzy", bestScore);
        }

        log.info("Product match strategy=none label='{}' bestScore={}",
                normalizedLabel, String.format("%.3f", bestScore));
        return new ProductMatchResult(Optional.empty(), "none", bestScore);
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

    private boolean isNaturePositiveLabel(String value) {
        String normalized = normalizeLabel(value);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAny(normalized,
                "plastic", "bottle", "cup", "bag", "straw", "container", "packaging", "disposable", "artificial")) {
            return false;
        }
        return containsAny(normalized, "tree", "sapling", "seedling", "houseplant", "potted plant", "flower", "shrub", "plant");
    }

    private boolean isNaturePositiveItem(String combined, String material, String lifecycleType) {
        if (containsAny(lifecycleType, "living natural", "living_natural", "nature positive", "natural living")) {
            return true;
        }
        return isNaturePositiveLabel(combined)
                || (containsAny(material, "organic", "plant", "leaf", "tree")
                && containsAny(combined, "tree", "plant", "sapling", "flower", "shrub"));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Product saveProduct(Product product) {
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                return mongoAtlasRuntimeStore.saveProduct(product);
            } catch (Exception ex) {
                log.warn("Mongo runtime product save failed, falling back to JPA: {}", ex.getMessage());
            }
        }
        return productRepository.save(product);
    }

    private Optional<Product> findByNameIgnoreCase(String name) {
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                return mongoAtlasRuntimeStore.findProductByNameIgnoreCase(name);
            } catch (Exception ex) {
                log.warn("Mongo runtime product name lookup failed, falling back to JPA: {}", ex.getMessage());
            }
        }
        return productRepository.findByNameIgnoreCase(name);
    }

    private Optional<Product> findFirstByCategoryIgnoreCase(String category) {
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                return mongoAtlasRuntimeStore.findFirstProductByCategoryIgnoreCase(category);
            } catch (Exception ex) {
                log.warn("Mongo runtime product category lookup failed, falling back to JPA: {}", ex.getMessage());
            }
        }
        return productRepository.findFirstByCategoryIgnoreCase(category);
    }

    private List<Double> findAllCarbonImpactsOrdered() {
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                return mongoAtlasRuntimeStore.findAllProductCarbonImpactsOrdered();
            } catch (Exception ex) {
                log.warn("Mongo runtime carbon distribution read failed, falling back to JPA: {}", ex.getMessage());
            }
        }
        return productRepository.findAllCarbonImpactsOrdered();
    }

    private List<Product> findAllProducts() {
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                return mongoAtlasRuntimeStore.findAllProducts();
            } catch (Exception ex) {
                log.warn("Mongo runtime product list read failed, falling back to JPA: {}", ex.getMessage());
            }
        }
        return productRepository.findAll();
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double roundThreeDecimals(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
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

    private record Co2ScoreResult(int score, String detail) {
    }

    private record ProductMatchResult(
            Optional<Product> product,
            String strategy,
            double score
    ) {
    }

    private record MetadataResolution(
            String material,
            String lifecycleType,
            boolean reusable,
            boolean singleUse,
            int recycledContentPercent,
            String recyclability,
            boolean inferred,
            List<String> inferredFields,
            double confidenceMultiplier,
            String ruleCode
    ) {
    }

    private record MetadataInferenceRule(
            String code,
            List<String> matchPhrases,
            String material,
            Boolean reusable,
            Boolean singleUse,
            Integer recycledContentPercent,
            String lifecycleType,
            String recyclability,
            double confidenceMultiplier
    ) {
    }
}
