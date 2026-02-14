package com.ecolens.ecolens_backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.ecolens.ecolens_backend.dto.TrainingExportResponse;
import com.ecolens.ecolens_backend.dto.TrainingSampleRequest;
import com.ecolens.ecolens_backend.dto.TrainingSampleResponse;
import com.ecolens.ecolens_backend.model.TrainingSample;
import com.ecolens.ecolens_backend.repository.TrainingSampleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class TrainingDataService {

    private static final Logger log = LoggerFactory.getLogger(TrainingDataService.class);
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 5000;
    private static final String TAXONOMY_RESOURCE_PATH = "classpath:taxonomy/ecolens-taxonomy-v1.json";

    private final TrainingSampleRepository trainingSampleRepository;
    private final JsonNode taxonomyRoot;
    private final String taxonomyVersion;
    private final Map<String, TaxonomyLeaf> taxonomyLeafById;
    private final Map<String, TaxonomyLeaf> taxonomyLeafByTerm;
    private final TaxonomyLeaf fallbackLeaf;

    public TrainingDataService(
            TrainingSampleRepository trainingSampleRepository,
            ResourceLoader resourceLoader
    ) {
        this.trainingSampleRepository = trainingSampleRepository;
        ObjectMapper objectMapper = new ObjectMapper();

        JsonNode loadedTaxonomy = loadTaxonomy(objectMapper, resourceLoader);
        this.taxonomyRoot = loadedTaxonomy;
        this.taxonomyVersion = safeText(loadedTaxonomy.path("version")).isBlank()
                ? "unknown"
                : safeText(loadedTaxonomy.path("version"));

        Map<String, TaxonomyLeaf> byId = new LinkedHashMap<>();
        Map<String, TaxonomyLeaf> byTerm = new HashMap<>();
        TaxonomyLeaf fallback = new TaxonomyLeaf("unknown_item", "Unknown Item", "unknown_misc", "Unknown / Misc");

        JsonNode groupsNode = loadedTaxonomy.path("groups");
        if (groupsNode.isArray()) {
            for (JsonNode groupNode : groupsNode) {
                String groupId = normalizeLeafId(safeText(groupNode.path("id")));
                String groupLabel = safeText(groupNode.path("label"));
                JsonNode classesNode = groupNode.path("classes");
                if (!classesNode.isArray()) {
                    continue;
                }
                for (JsonNode classNode : classesNode) {
                    String leafId = normalizeLeafId(safeText(classNode.path("id")));
                    if (leafId.isBlank()) {
                        continue;
                    }
                    String leafLabel = safeText(classNode.path("label"));
                    TaxonomyLeaf leaf = new TaxonomyLeaf(
                            leafId,
                            leafLabel.isBlank() ? toDisplayLabel(leafId) : leafLabel,
                            groupId.isBlank() ? "unknown_misc" : groupId,
                            groupLabel.isBlank() ? "Unknown / Misc" : groupLabel
                    );

                    byId.putIfAbsent(leafId, leaf);

                    indexTerm(byTerm, leafLabel, leaf);
                    indexTerm(byTerm, leafId, leaf);

                    JsonNode synonymsNode = classNode.path("synonyms");
                    if (synonymsNode.isArray()) {
                        for (JsonNode synonymNode : synonymsNode) {
                            indexTerm(byTerm, safeText(synonymNode), leaf);
                        }
                    }

                    if ("unknown_item".equals(leafId)) {
                        fallback = leaf;
                    }
                }
            }
        }

        this.taxonomyLeafById = Map.copyOf(byId);
        this.taxonomyLeafByTerm = Map.copyOf(byTerm);
        this.fallbackLeaf = fallback;

        log.info("Training taxonomy loaded: version={}, classes={}", taxonomyVersion, taxonomyLeafById.size());
    }

    public JsonNode getTaxonomy() {
        return taxonomyRoot.deepCopy();
    }

    public TrainingSampleResponse saveSample(TrainingSampleRequest request) {
        String predictedLabel = safeText(request.getPredictedLabel());
        String finalLabel = safeText(request.getFinalLabel());
        if (finalLabel.isBlank()) {
            finalLabel = predictedLabel;
        }
        if (finalLabel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "finalLabel is required (or provide predictedLabel as fallback).");
        }

        TaxonomyLeaf leaf = resolveTaxonomyLeaf(finalLabel, request.getTaxonomyLeaf(), predictedLabel);
        String imageBase64 = sanitizeImageBase64(request.getImageBase64());

        TrainingSample sample = new TrainingSample();
        sample.setUserId(limit(safeText(request.getUserId()), 120, "anonymous"));
        sample.setImageBase64(imageBase64.isBlank() ? null : imageBase64);
        sample.setImageSha256(imageBase64.isBlank() ? null : sha256Hex(imageBase64));
        sample.setPredictedLabel(limit(predictedLabel, 180, null));
        sample.setPredictedConfidence(clampConfidence(request.getPredictedConfidence()));
        sample.setFinalLabel(limit(finalLabel, 180, "Unknown item"));
        sample.setTaxonomyLeaf(leaf.id());
        sample.setTaxonomyParent(leaf.parentId());
        sample.setSourceEngine(limit(safeText(request.getSourceEngine()), 60, "unknown"));
        sample.setSourceRuntime(limit(safeText(request.getSourceRuntime()), 120, "unknown"));
        sample.setDevicePlatform(limit(safeText(request.getDevicePlatform()), 40, "unknown"));
        sample.setAppVersion(limit(safeText(request.getAppVersion()), 60, "unknown"));
        sample.setUserConfirmed(request.getUserConfirmed() == null ? Boolean.TRUE : request.getUserConfirmed());
        sample.setCapturedAt(LocalDateTime.now(Clock.systemUTC()));

        TrainingSample saved = trainingSampleRepository.save(sample);
        return toResponse(saved, false);
    }

    public List<TrainingSampleResponse> listSamples(int requestedLimit, boolean confirmedOnly, boolean includeImages) {
        int limit = sanitizeLimit(requestedLimit);
        Pageable pageable = PageRequest.of(0, limit);
        List<TrainingSample> samples = confirmedOnly
                ? trainingSampleRepository.findByUserConfirmedTrueOrderByCapturedAtDesc(pageable)
                : trainingSampleRepository.findAllByOrderByCapturedAtDesc(pageable);

        return samples.stream()
                .map(sample -> toResponse(sample, includeImages))
                .toList();
    }

    public TrainingExportResponse exportSamples(int requestedLimit, boolean confirmedOnly, boolean includeImages) {
        List<TrainingSampleResponse> samples = listSamples(requestedLimit, confirmedOnly, includeImages);
        TrainingExportResponse response = new TrainingExportResponse();
        response.setTaxonomyVersion(taxonomyVersion);
        response.setGeneratedAt(LocalDateTime.now(Clock.systemUTC()).atOffset(ZoneOffset.UTC).toInstant().toString());
        response.setIncludeImages(includeImages);
        response.setSampleCount(samples.size());
        response.setSamples(samples);
        return response;
    }

    public Map<String, Object> trainingStats() {
        long total = trainingSampleRepository.count();
        long confirmed = trainingSampleRepository.countByUserConfirmedTrue();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("taxonomyVersion", taxonomyVersion);
        stats.put("taxonomyClassCount", taxonomyLeafById.size());
        stats.put("totalSamples", total);
        stats.put("confirmedSamples", confirmed);
        stats.put("unconfirmedSamples", Math.max(0, total - confirmed));
        return stats;
    }

    private JsonNode loadTaxonomy(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(TAXONOMY_RESOURCE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            if (root == null || root.isMissingNode() || !root.isObject()) {
                throw new IllegalStateException("Taxonomy resource is not a valid JSON object.");
            }
            return root;
        } catch (IOException | RuntimeException ex) {
            log.error("Failed to load taxonomy resource {}: {}", TAXONOMY_RESOURCE_PATH, ex.getMessage());
            return fallbackTaxonomy(objectMapper);
        }
    }

    private JsonNode fallbackTaxonomy(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "fallback");
        ArrayNode groups = objectMapper.createArrayNode();

        ObjectNode group = objectMapper.createObjectNode();
        group.put("id", "unknown_misc");
        group.put("label", "Unknown / Misc");
        ArrayNode classes = objectMapper.createArrayNode();

        ObjectNode clazz = objectMapper.createObjectNode();
        clazz.put("id", "unknown_item");
        clazz.put("label", "Unknown Item");
        classes.add(clazz);

        group.set("classes", classes);
        groups.add(group);
        root.set("groups", groups);
        return root;
    }

    private TaxonomyLeaf resolveTaxonomyLeaf(String finalLabel, String taxonomyLeafRequest, String predictedLabel) {
        String explicitLeaf = normalizeLeafId(taxonomyLeafRequest);
        if (!explicitLeaf.isBlank()) {
            TaxonomyLeaf resolved = taxonomyLeafById.get(explicitLeaf);
            if (resolved != null) {
                return resolved;
            }
        }

        String normalizedFinal = normalizeTerm(finalLabel);
        if (!normalizedFinal.isBlank()) {
            TaxonomyLeaf byFinal = taxonomyLeafByTerm.get(normalizedFinal);
            if (byFinal != null) {
                return byFinal;
            }
        }

        String normalizedPredicted = normalizeTerm(predictedLabel);
        if (!normalizedPredicted.isBlank()) {
            TaxonomyLeaf byPredicted = taxonomyLeafByTerm.get(normalizedPredicted);
            if (byPredicted != null) {
                return byPredicted;
            }
        }

        TaxonomyLeaf fuzzyMatch = findBestFuzzyLeaf(normalizedFinal, normalizedPredicted);
        if (fuzzyMatch != null) {
            return fuzzyMatch;
        }

        return fallbackLeaf;
    }

    private TaxonomyLeaf findBestFuzzyLeaf(String normalizedFinal, String normalizedPredicted) {
        String context = (normalizedFinal + " " + normalizedPredicted).trim();
        if (context.isBlank()) {
            return null;
        }

        TaxonomyLeaf best = null;
        int bestScore = 0;

        for (Map.Entry<String, TaxonomyLeaf> entry : taxonomyLeafByTerm.entrySet()) {
            String term = entry.getKey();
            if (term.length() < 4) {
                continue;
            }
            boolean termInContext = context.contains(term);
            boolean overlapFinal = !normalizedFinal.isBlank() && term.contains(normalizedFinal);
            if (!termInContext && !overlapFinal) {
                continue;
            }
            int score = term.length();
            if (score > bestScore) {
                bestScore = score;
                best = entry.getValue();
            }
        }

        return best;
    }

    private void indexTerm(Map<String, TaxonomyLeaf> byTerm, String rawTerm, TaxonomyLeaf leaf) {
        String normalized = normalizeTerm(rawTerm);
        if (normalized.isBlank()) {
            return;
        }
        byTerm.putIfAbsent(normalized, leaf);
    }

    private String sanitizeImageBase64(String raw) {
        String value = safeText(raw);
        if (value.isBlank()) {
            return "";
        }

        int commaIndex = value.indexOf(',');
        if (commaIndex > 0 && value.substring(0, commaIndex).toLowerCase(Locale.ROOT).contains("base64")) {
            value = value.substring(commaIndex + 1).trim();
        }
        return value;
    }

    private Double clampConfidence(Double confidence) {
        if (confidence == null || !Double.isFinite(confidence)) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private int sanitizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private TrainingSampleResponse toResponse(TrainingSample sample, boolean includeImage) {
        TrainingSampleResponse response = new TrainingSampleResponse();
        response.setId(String.valueOf(sample.getId()));
        response.setUserId(sample.getUserId());
        response.setFinalLabel(sample.getFinalLabel());
        response.setTaxonomyLeaf(sample.getTaxonomyLeaf());
        response.setTaxonomyParent(sample.getTaxonomyParent());
        response.setPredictedLabel(sample.getPredictedLabel());
        response.setPredictedConfidence(sample.getPredictedConfidence());
        response.setSourceEngine(sample.getSourceEngine());
        response.setSourceRuntime(sample.getSourceRuntime());
        response.setDevicePlatform(sample.getDevicePlatform());
        response.setAppVersion(sample.getAppVersion());
        response.setUserConfirmed(sample.getUserConfirmed());
        response.setImageSha256(sample.getImageSha256());
        if (sample.getCapturedAt() != null) {
            response.setCapturedAt(sample.getCapturedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        }
        if (includeImage) {
            response.setImageBase64(sample.getImageBase64());
        }
        return response;
    }

    private String limit(String value, int maxLength, String fallback) {
        String sanitized = safeText(value);
        if (sanitized.isBlank()) {
            sanitized = fallback == null ? "" : fallback;
        }
        if (sanitized == null) {
            return null;
        }
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return null;
        }
    }

    private String safeText(JsonNode node) {
        return node == null ? "" : safeText(node.asText(""));
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private String normalizeTerm(String text) {
        if (text == null) {
            return "";
        }
        String normalized = NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private String normalizeLeafId(String text) {
        return normalizeTerm(text).replace(' ', '_');
    }

    private String toDisplayLabel(String leafId) {
        String[] parts = leafId.replace('_', ' ').split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            tokens.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return String.join(" ", tokens);
    }

    private record TaxonomyLeaf(String id, String label, String parentId, String parentLabel) {
    }
}
