package com.ecolens.ecolens_backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class VoiceSummaryService {

    private static final int MAX_SUMMARY_LENGTH = 480;

    private final Environment environment;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VoiceSummaryService(Environment environment) {
        this.environment = environment;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public VoiceSynthesisResult synthesizeSummary(String text) {
        String normalizedText = normalizeSummaryText(text);
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("Voice summary text is required.");
        }

        ElevenLabsConfig config = resolveConfig();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("ElevenLabs API key is not configured.");
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("text", normalizedText)
                    .put("model_id", config.modelId());
            ObjectNode voiceSettings = payload.putObject("voice_settings");
            voiceSettings.put("stability", 0.42);
            voiceSettings.put("similarity_boost", 0.8);
            voiceSettings.put("style", 0.2);
            voiceSettings.put("use_speaker_boost", true);

            URI uri = URI.create(trimTrailingSlash(config.baseUrl())
                    + "/v1/text-to-speech/"
                    + config.voiceId()
                    + "?output_format=mp3_44100_128");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("xi-api-key", config.apiKey())
                    .header("Accept", "audio/mpeg")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                throw new IllegalStateException("ElevenLabs request failed with status "
                        + response.statusCode() + ". body=" + truncate(body, 200));
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("audio/mpeg");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                contentType = "audio/mpeg";
            }

            return new VoiceSynthesisResult(response.body(), contentType, "elevenlabs");
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Voice synthesis failed: " + ex.getMessage(), ex);
        }
    }

    private ElevenLabsConfig resolveConfig() {
        String apiKey = firstNonBlank(
                environment.getProperty("ELEVENLABS_API_KEY"),
                System.getenv("ELEVENLABS_API_KEY"),
                environment.getProperty("elevenlabs.api.key")
        );
        String voiceId = firstNonBlank(
                environment.getProperty("ELEVENLABS_VOICE_ID"),
                System.getenv("ELEVENLABS_VOICE_ID"),
                environment.getProperty("elevenlabs.voice.id"),
                "EXAVITQu4vr4xnSDxMaL"
        );
        String modelId = firstNonBlank(
                environment.getProperty("ELEVENLABS_MODEL_ID"),
                System.getenv("ELEVENLABS_MODEL_ID"),
                environment.getProperty("elevenlabs.model.id"),
                "eleven_multilingual_v2"
        );
        String baseUrl = firstNonBlank(
                environment.getProperty("ELEVENLABS_BASE_URL"),
                System.getenv("ELEVENLABS_BASE_URL"),
                environment.getProperty("elevenlabs.base-url"),
                "https://api.elevenlabs.io"
        );
        return new ElevenLabsConfig(apiKey, voiceId, modelId, baseUrl);
    }

    private String normalizeSummaryText(String text) {
        String normalized = String.valueOf(text == null ? "" : text)
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() > MAX_SUMMARY_LENGTH) {
            return normalized.substring(0, MAX_SUMMARY_LENGTH);
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String trimTrailingSlash(String value) {
        String out = String.valueOf(value == null ? "" : value).trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private String truncate(String value, int maxLen) {
        String text = value == null ? "" : value;
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private record ElevenLabsConfig(
            String apiKey,
            String voiceId,
            String modelId,
            String baseUrl
    ) {
    }

    public record VoiceSynthesisResult(
            byte[] audioBytes,
            String contentType,
            String provider
    ) {
    }
}
