package com.ecolens.ecolens_backend.service;

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

    private final Environment environment;

    public LLMService(Environment environment) {
        this.environment = environment;
    }

    public String generateExplanation(Product product) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return FALLBACK_MESSAGE;
        }

        OpenAIClient client = null;
        try {
            client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();

            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(resolveModel())
                    .addSystemMessage("You are an eco assistant. Return exactly two sentences explanation and one short suggestion line.")
                    .addUserMessage(buildPrompt(product))
                    .temperature(0.2)
                    .maxCompletionTokens(180)
                    .build();

            ChatCompletion completion = client.chat().completions().create(params);
            if (completion.choices().isEmpty()) {
                return FALLBACK_MESSAGE;
            }

            String content = completion.choices().get(0).message().content().orElse("").trim();
            return content.isBlank() ? FALLBACK_MESSAGE : content;
        } catch (Exception ex) {
            return FALLBACK_MESSAGE;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private String resolveApiKey() {
        String fromProps = environment.getProperty("openai.api.key");
        if (fromProps != null && !fromProps.isBlank()) {
            return fromProps;
        }

        String fromEnvProperty = environment.getProperty("OPENAI_API_KEY");
        if (fromEnvProperty != null && !fromEnvProperty.isBlank()) {
            return fromEnvProperty;
        }

        String fromSystemEnv = System.getenv("OPENAI_API_KEY");
        if (fromSystemEnv != null && !fromSystemEnv.isBlank()) {
            return fromSystemEnv;
        }

        return null;
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
}
