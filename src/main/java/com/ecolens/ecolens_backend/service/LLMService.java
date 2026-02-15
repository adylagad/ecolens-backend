package com.ecolens.ecolens_backend.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class LLMService {

    private static final String FALLBACK_MESSAGE = "Explanation not available (no API key / generation failed).";
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final Pattern VISION_LABEL_PREFIX = Pattern.compile("^(label|item|object|main object)\\s*[:\\-]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> INVALID_VISION_LABELS = Set.of(
            "unknown",
            "unknown item",
            "unknown product",
            "unidentified",
            "n a",
            "na",
            "none",
            "not sure"
    );
    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LLMService(Environment environment) {
        this.environment = environment;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
    }

    public String generateExplanation(Product product) {
        if (!isGeminiProviderEnabled()) {
            log.warn("LLM explanation skipped: llm.provider is not set to 'gemini'.");
            return FALLBACK_MESSAGE;
        }

        ApiKeyResolution apiKeyResolution = resolveApiKey();
        if (apiKeyResolution.key() == null || apiKeyResolution.key().isBlank()) {
            log.warn("Gemini explanation skipped: no API key detected (checked GOOGLE_API_KEY / GEMINI_API_KEY / gemini.api.key).");
            return FALLBACK_MESSAGE;
        }

        try {
            String model = resolveModel();
            log.info("Gemini text generation started: keySource={}, textModel={}.",
                    apiKeyResolution.source(), model);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(buildGeminiUri(model, apiKeyResolution.key()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildGeminiRequestBody(product)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Gemini explanation generation failed for product={}: HTTP {} body={}",
                        safe(product.getName()), response.statusCode(), response.body());
                return FALLBACK_MESSAGE;
            }

            String content = extractGeneratedText(response.body());
            if (content.isBlank()) {
                log.warn("Gemini explanation generation returned blank content for product={}.", safe(product.getName()));
                return FALLBACK_MESSAGE;
            }

            log.info("Gemini explanation generated successfully for product={}.", safe(product.getName()));
            return content;
        } catch (Exception ex) {
            log.error("Gemini explanation generation failed for product={}: {}: {}",
                    safe(product.getName()), ex.getClass().getSimpleName(), ex.getMessage());
            return FALLBACK_MESSAGE;
        }
    }

    public String detectLabelFromImage(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return "";
        }

        if (!isGeminiProviderEnabled()) {
            log.warn("Gemini image detection skipped: llm.provider is not set to 'gemini'.");
            return "";
        }

        ApiKeyResolution apiKeyResolution = resolveApiKey();
        if (apiKeyResolution.key() == null || apiKeyResolution.key().isBlank()) {
            log.warn("Gemini image detection skipped: no API key detected.");
            return "";
        }

        String sanitizedImage = sanitizeImageBase64(imageBase64);
        if (sanitizedImage.isBlank()) {
            log.warn("Gemini image detection skipped: invalid base64 image payload.");
            return "";
        }

        String mimeType = detectMimeType(sanitizedImage);
        List<String> visionModelCandidates = buildVisionModelCandidates();
        log.info("Gemini image detection started: mimeType={}, modelCandidates={}", mimeType, visionModelCandidates);

        for (String model : visionModelCandidates) {
            try {
                String label = detectLabelFromImageWithModel(model, apiKeyResolution.key(), sanitizedImage, mimeType);
                if (!label.isBlank()) {
                    return label;
                }
            } catch (Exception ex) {
                log.warn("Gemini image detection attempt failed: model={} error={} message={}",
                        model, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }

        log.warn("Gemini image detection exhausted all model candidates without a usable label.");
        return "";
    }

    public String getConfiguredTextModel() {
        return resolveModel();
    }

    public String getConfiguredVisionModel() {
        return resolveVisionModel();
    }

    public boolean isFallbackExplanation(String explanation) {
        return explanation == null || explanation.isBlank() || FALLBACK_MESSAGE.equals(explanation);
    }

    private ApiKeyResolution resolveApiKey() {
        String fromEnvGoogleKey = environment.getProperty("GOOGLE_API_KEY");
        if (fromEnvGoogleKey != null && !fromEnvGoogleKey.isBlank()) {
            return new ApiKeyResolution(fromEnvGoogleKey, "spring-environment-GOOGLE_API_KEY");
        }

        String fromSystemGoogleKey = System.getenv("GOOGLE_API_KEY");
        if (fromSystemGoogleKey != null && !fromSystemGoogleKey.isBlank()) {
            return new ApiKeyResolution(fromSystemGoogleKey, "system-env-GOOGLE_API_KEY");
        }

        String fromProps = environment.getProperty("gemini.api.key");
        if (fromProps != null && !fromProps.isBlank()) {
            return new ApiKeyResolution(fromProps, "application-property-gemini.api.key");
        }

        String fromEnvProperty = environment.getProperty("GEMINI_API_KEY");
        if (fromEnvProperty != null && !fromEnvProperty.isBlank()) {
            return new ApiKeyResolution(fromEnvProperty, "spring-environment-GEMINI_API_KEY");
        }

        String fromSystemEnv = System.getenv("GEMINI_API_KEY");
        if (fromSystemEnv != null && !fromSystemEnv.isBlank()) {
            return new ApiKeyResolution(fromSystemEnv, "system-env-GEMINI_API_KEY");
        }

        return new ApiKeyResolution(null, "none");
    }

    private String resolveModel() {
        String envModel = environment.getProperty("GEMINI_MODEL");
        if (envModel != null && !envModel.isBlank()) {
            return envModel;
        }

        String configuredModel = environment.getProperty("gemini.api.model");
        if (configuredModel != null && !configuredModel.isBlank()) {
            return configuredModel;
        }
        return "gemini-2.0-flash";
    }

    private String resolveVisionModel() {
        String envVisionModel = environment.getProperty("GEMINI_VISION_MODEL");
        if (envVisionModel != null && !envVisionModel.isBlank()) {
            return envVisionModel;
        }

        String configuredVisionModel = environment.getProperty("gemini.api.vision.model");
        if (configuredVisionModel != null && !configuredVisionModel.isBlank()) {
            return configuredVisionModel;
        }

        return "gemini-2.5-flash-lite";
    }

    private List<String> buildVisionModelCandidates() {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        String configured = resolveVisionModel();
        if (configured != null && !configured.isBlank()) {
            models.add(configured.trim());
        }
        models.add("gemini-2.5-flash");
        models.add("gemini-2.0-flash");
        models.add("gemini-1.5-flash");
        return new ArrayList<>(models);
    }

    private String detectLabelFromImageWithModel(String model, String apiKey, String imageBase64, String mimeType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildGeminiUri(model, apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildGeminiVisionRequestBody(imageBase64, mimeType)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            String trimmedBody = body.length() > 500 ? body.substring(0, 500) : body;
            log.warn("Gemini image detection failed: model={} mimeType={} status={} body={}",
                    model, mimeType, response.statusCode(), trimmedBody);
            return "";
        }

        String content = extractGeneratedText(response.body());
        String label = normalizeVisionLabel(content);
        if (label.isBlank()) {
            log.warn("Gemini image detection returned empty label after normalization: model={} mimeType={} raw='{}'",
                    model, mimeType, safe(content));
            return "";
        }

        log.info("Gemini image detection succeeded: model={}, mimeType={}, label='{}'",
                model, mimeType, label);
        return label;
    }

    private boolean isGeminiProviderEnabled() {
        String provider = environment.getProperty("llm.provider");
        if (provider == null || provider.isBlank()) {
            return true;
        }
        return "gemini".equalsIgnoreCase(provider.trim());
    }

    private URI buildGeminiUri(String model, String apiKey) {
        String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        String endpoint = GEMINI_BASE_URL + encodedModel + ":generateContent?key=" + encodedKey;
        return URI.create(endpoint);
    }

    private String buildGeminiRequestBody(Product product) throws IOException {
        String prompt = "You are an eco assistant. Return exactly two short sentences explaining the product's eco impact "
                + "followed by one single-line suggestion prefixed with 'Suggestion:'.\n\n"
                + buildPrompt(product);

        JsonNode payload = objectMapper.createObjectNode()
                .set("contents", objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode().set("parts",
                                objectMapper.createArrayNode().add(
                                        objectMapper.createObjectNode().put("text", prompt)
                                ))
                ));
        return objectMapper.writeValueAsString(payload);
    }

    private String buildGeminiVisionRequestBody(String imageBase64, String mimeType) throws IOException {
        String prompt = "You are labeling one object from a camera image for an eco-scanner app.\n"
                + "Identify the single main everyday object.\n"
                + "Return JSON only in this exact format: {\"label\":\"<1-4 word lowercase label>\"}.\n"
                + "Examples: {\"label\":\"running shoe\"}, {\"label\":\"paper coffee cup\"}, {\"label\":\"laptop charger\"}.\n"
                + "If uncertain, still return your best guess in the same JSON format.";

        JsonNode payload = objectMapper.createObjectNode()
                .set("contents", objectMapper.createArrayNode().add(
                        objectMapper.createObjectNode().set("parts",
                                objectMapper.createArrayNode()
                                        .add(objectMapper.createObjectNode().put("text", prompt))
                                        .add(objectMapper.createObjectNode().set("inline_data",
                                                objectMapper.createObjectNode()
                                                        .put("mime_type", mimeType)
                                                        .put("data", imageBase64)
                                        ))
                        )
                ));
        return objectMapper.writeValueAsString(payload);
    }

    private String extractGeneratedText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return "";
        }

        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            String partText = part.path("text").asText("");
            if (!partText.isBlank()) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(partText.trim());
            }
        }
        return text.toString().trim();
    }

    private String normalizeVisionLabel(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }

        String value = rawContent.trim();
        String stripped = value
                .replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "")
                .trim();
        if (!stripped.isBlank()) {
            value = stripped;
        }

        if (value.startsWith("{") && value.endsWith("}")) {
            try {
                JsonNode json = objectMapper.readTree(value);
                String fromLabel = json.path("label").asText("").trim();
                if (!fromLabel.isBlank()) {
                    value = fromLabel;
                } else {
                    String fromName = json.path("name").asText("").trim();
                    String fromItem = json.path("item").asText("").trim();
                    value = !fromName.isBlank() ? fromName : fromItem;
                }
            } catch (IOException ignored) {
                // Fallback to plain-text normalization below.
            }
        }

        int newlineIndex = value.indexOf('\n');
        if (newlineIndex >= 0) {
            value = value.substring(0, newlineIndex).trim();
        }

        value = value.replace("`", "");
        value = VISION_LABEL_PREFIX.matcher(value).replaceFirst("");
        value = value.replaceAll("^\"+|\"+$", "");
        value = value.replaceAll("^'+|'+$", "");
        value = value.toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9\\s\\-/]", " ");
        value = value.replaceAll("\\s+", " ").trim();

        if (value.startsWith("a ")) {
            value = value.substring(2).trim();
        } else if (value.startsWith("an ")) {
            value = value.substring(3).trim();
        } else if (value.startsWith("the ")) {
            value = value.substring(4).trim();
        }

        if (value.length() > 64) {
            value = value.substring(0, 64).trim();
        }

        String[] parts = value.isBlank() ? new String[0] : value.split("\\s+");
        if (parts.length > 4) {
            StringBuilder compact = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) {
                    compact.append(' ');
                }
                compact.append(parts[i]);
            }
            value = compact.toString();
        }

        if (INVALID_VISION_LABELS.contains(value)) {
            return "";
        }
        return value;
    }

    private String buildPrompt(Product product) {
        return String.format(
                "Product name: %s%nCategory: %s%nEco score: %s%nCO2 grams: %s%nRecyclability: %s%nMaterial: %s%nIs reusable: %s%nIs single-use: %s%nRecycled content %%: %s%nLifecycle type: %s%nAlternative: %s",
                safe(product.getName()),
                safe(product.getCategory()),
                safe(product.getEcoScore()),
                safe(product.getCarbonImpact()),
                safe(product.getRecyclability()),
                safe(product.getMaterial()),
                safe(product.getReusable()),
                safe(product.getSingleUse()),
                safe(product.getRecycledContentPercent()),
                safe(product.getLifecycleType()),
                safe(product.getAlternativeRecommendation())
        );
    }

    private String safe(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private String sanitizeImageBase64(String imageBase64) {
        String value = imageBase64.trim();
        int commaIndex = value.indexOf(',');
        if (value.startsWith("data:") && commaIndex >= 0) {
            value = value.substring(commaIndex + 1);
        }

        try {
            Base64.getDecoder().decode(value);
            return value;
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private String detectMimeType(String imageBase64) {
        byte[] decoded = Base64.getDecoder().decode(imageBase64);
        if (decoded.length >= 8
                && (decoded[0] & 0xFF) == 0x89
                && decoded[1] == 0x50
                && decoded[2] == 0x4E
                && decoded[3] == 0x47
                && decoded[4] == 0x0D
                && decoded[5] == 0x0A
                && decoded[6] == 0x1A
                && decoded[7] == 0x0A) {
            return "image/png";
        }

        if (decoded.length >= 3
                && (decoded[0] & 0xFF) == 0xFF
                && (decoded[1] & 0xFF) == 0xD8
                && (decoded[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        if (decoded.length >= 12
                && decoded[0] == 'R'
                && decoded[1] == 'I'
                && decoded[2] == 'F'
                && decoded[3] == 'F'
                && decoded[8] == 'W'
                && decoded[9] == 'E'
                && decoded[10] == 'B'
                && decoded[11] == 'P') {
            return "image/webp";
        }

        if (decoded.length >= 12
                && decoded[4] == 'f'
                && decoded[5] == 't'
                && decoded[6] == 'y'
                && decoded[7] == 'p') {
            String brand = new String(decoded, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || "hevc".equals(brand) || "hevx".equals(brand)) {
                return "image/heic";
            }
            if ("heif".equals(brand) || "mif1".equals(brand) || "msf1".equals(brand)) {
                return "image/heif";
            }
        }

        return "image/jpeg";
    }

    private record ApiKeyResolution(String key, String source) {
    }
}
