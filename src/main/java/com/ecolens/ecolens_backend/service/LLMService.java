package com.ecolens.ecolens_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.model.Product;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

@Service
public class LLMService {

    private static final String FALLBACK_MESSAGE = "Explanation not available (no API key / generation failed).";
    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final Environment environment;

    public LLMService(Environment environment) {
        this.environment = environment;
    }

    public String generateExplanation(Product product) {
        ApiKeyResolution apiKeyResolution = resolveApiKey();
        if (apiKeyResolution.key() == null || apiKeyResolution.key().isBlank()) {
            log.warn("OpenAI explanation skipped: no API key detected (checked openai.api.key / OPENAI_API_KEY).");
            return FALLBACK_MESSAGE;
        }

        OpenAIClient client = null;
        try {
            String model = resolveModel();
            log.info("OpenAI explanation generation enabled. Key source={}, model={}.",
                    apiKeyResolution.source(), model);

            client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKeyResolution.key())
                    .build();

            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(model)
                    .addSystemMessage("You are an eco assistant. Return exactly two sentences explanation and one short suggestion line.")
                    .addUserMessage(buildPrompt(product))
                    .temperature(0.2)
                    .maxCompletionTokens(180)
                    .build();

            ChatCompletion completion = client.chat().completions().create(params);
            if (completion.choices().isEmpty()) {
                log.warn("OpenAI explanation generation returned no choices for product={}.", safe(product.getName()));
                return FALLBACK_MESSAGE;
            }

            String content = completion.choices().get(0).message().content().orElse("").trim();
            if (content.isBlank()) {
                log.warn("OpenAI explanation generation returned blank content for product={}.", safe(product.getName()));
                return FALLBACK_MESSAGE;
            }

            log.info("OpenAI explanation generated successfully for product={}.", safe(product.getName()));
            return content;
        } catch (Exception ex) {
            log.error("OpenAI explanation generation failed for product={}: {}: {}",
                    safe(product.getName()), ex.getClass().getSimpleName(), ex.getMessage());
            return FALLBACK_MESSAGE;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private ApiKeyResolution resolveApiKey() {
        String fromProps = environment.getProperty("openai.api.key");
        if (fromProps != null && !fromProps.isBlank()) {
            return new ApiKeyResolution(fromProps, "application-property-openai.api.key");
        }

        String fromEnvProperty = environment.getProperty("OPENAI_API_KEY");
        if (fromEnvProperty != null && !fromEnvProperty.isBlank()) {
            return new ApiKeyResolution(fromEnvProperty, "spring-environment-OPENAI_API_KEY");
        }

        String fromSystemEnv = System.getenv("OPENAI_API_KEY");
        if (fromSystemEnv != null && !fromSystemEnv.isBlank()) {
            return new ApiKeyResolution(fromSystemEnv, "system-env-OPENAI_API_KEY");
        }

        return new ApiKeyResolution(null, "none");
    }

    private String resolveModel() {
        String configuredModel = environment.getProperty("openai.api.model");
        if (configuredModel != null && !configuredModel.isBlank()) {
            return configuredModel;
        }
        return "gpt-4o-mini";
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
