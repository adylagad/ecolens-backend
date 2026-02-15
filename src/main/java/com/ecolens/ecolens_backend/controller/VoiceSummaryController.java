package com.ecolens.ecolens_backend.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.ecolens.ecolens_backend.dto.VoiceSummaryRequest;
import com.ecolens.ecolens_backend.dto.VoiceSummaryResponse;
import com.ecolens.ecolens_backend.service.VoiceSummaryService;
import com.ecolens.ecolens_backend.service.VoiceSummaryService.VoiceSynthesisResult;

@RestController
@RequestMapping("/api/voice")
public class VoiceSummaryController {

    private static final Logger log = LoggerFactory.getLogger(VoiceSummaryController.class);
    private static final Duration AUDIO_TTL = Duration.ofMinutes(10);

    private final VoiceSummaryService voiceSummaryService;
    private final ConcurrentHashMap<String, CachedAudio> audioCache;

    public VoiceSummaryController(VoiceSummaryService voiceSummaryService) {
        this.voiceSummaryService = voiceSummaryService;
        this.audioCache = new ConcurrentHashMap<>();
    }

    @PostMapping("/summary")
    public ResponseEntity<?> generateSummary(@RequestBody VoiceSummaryRequest request) {
        String text = request == null ? "" : String.valueOf(request.getText() == null ? "" : request.getText()).trim();
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "text is required."));
        }

        cleanupExpired();
        try {
            VoiceSynthesisResult result = voiceSummaryService.synthesizeSummary(text);
            String audioId = UUID.randomUUID().toString().replace("-", "");
            audioCache.put(audioId, new CachedAudio(result.audioBytes(), result.contentType(), Instant.now().plus(AUDIO_TTL)));

            String audioUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/voice/audio/{audioId}")
                    .buildAndExpand(audioId)
                    .toUriString();

            return ResponseEntity.ok(new VoiceSummaryResponse(audioUrl, result.provider(), result.contentType()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("Voice summary unavailable: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Voice summary is not configured on backend."));
        } catch (Exception ex) {
            log.error("Voice summary generation failed: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", "Voice summary generation failed."));
        }
    }

    @GetMapping("/audio/{audioId}")
    public ResponseEntity<byte[]> getAudio(@PathVariable("audioId") String audioId) {
        cleanupExpired();
        CachedAudio cached = audioCache.remove(String.valueOf(audioId == null ? "" : audioId).trim());
        if (cached == null || cached.expiresAt().isBefore(Instant.now())) {
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            mediaType = MediaType.parseMediaType(cached.contentType());
        } catch (Exception ignored) {
            // Keep fallback media type.
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setCacheControl(CacheControl.noStore().getHeaderValue());
        headers.setPragma("no-cache");
        headers.setExpires(0);
        headers.setContentLength(cached.bytes().length);
        return new ResponseEntity<>(cached.bytes(), headers, HttpStatus.OK);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        audioCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record CachedAudio(
            byte[] bytes,
            String contentType,
            Instant expiresAt
    ) {
    }
}
