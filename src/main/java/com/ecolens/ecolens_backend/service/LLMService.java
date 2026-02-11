package com.ecolens.ecolens_backend.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

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
            log.info("Gemini explanation generation enabled. Key source={}, model={}.",
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

    private String buildPrompt(Product product) {
        return String.format(
                "Product name: %s%nCategory: %s%nEco score: %s%nCO2 grams: %s%nRecyclability: %s%nAlternative: %s",
                safe(product.getName()),
                safe(product.getCategory()),
                safe(product.getEcoScore()),
                safe(product.getCarbonImpact()),
                safe(product.getRecyclability()),
                safe(product.getAlternativeRecommendation())
        );
    }

    private String safe(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private record ApiKeyResolution(String key, String source) {
    }
}
