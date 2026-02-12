package com.ecolens.ecolens_backend.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.config.ScoringProperties;
import com.ecolens.ecolens_backend.dto.HistoryEntryRequest;
import com.ecolens.ecolens_backend.dto.HistoryEntryResponse;
import com.ecolens.ecolens_backend.dto.HistoryStatsResponse;
import com.ecolens.ecolens_backend.model.ScanHistoryEntry;
import com.ecolens.ecolens_backend.repository.ScanHistoryRepository;

@Service
public class HistoryService {

    private final ScanHistoryRepository scanHistoryRepository;
    private final ScoringProperties scoringProperties;

    public HistoryService(ScanHistoryRepository scanHistoryRepository, ScoringProperties scoringProperties) {
        this.scanHistoryRepository = scanHistoryRepository;
        this.scoringProperties = scoringProperties;
    }

    public HistoryEntryResponse save(HistoryEntryRequest request, String requestedUserId) {
        String userId = resolveUserId(request.getUserId(), requestedUserId);
        ScanHistoryEntry entry = new ScanHistoryEntry(
                userId,
                safe(request.getItem(), "Unknown item"),
                safe(request.getCategory(), "unknown"),
                request.getEcoScore() == null ? 0 : request.getEcoScore(),
                request.getConfidence() == null ? 0.0 : request.getConfidence(),
                LocalDateTime.now(Clock.systemUTC())
        );
        ScanHistoryEntry saved = scanHistoryRepository.save(entry);
        return toResponse(saved);
    }

    public List<HistoryEntryResponse> list(boolean highImpactOnly, String requestedUserId) {
        String userId = resolveUserId(null, requestedUserId);
        int highImpactThreshold = scoringProperties.getHighImpactThreshold();
        List<ScanHistoryEntry> entries = highImpactOnly
                ? scanHistoryRepository.findByUserIdAndEcoScoreLessThanOrderByScannedAtDesc(userId, highImpactThreshold)
                : scanHistoryRepository.findAllByUserIdOrderByScannedAtDesc(userId);

        return entries.stream().map(this::toResponse).toList();
    }

    public HistoryStatsResponse stats(String requestedUserId) {
        String userId = resolveUserId(null, requestedUserId);
        List<ScanHistoryEntry> entries = scanHistoryRepository.findAllByUserId(userId);
        HistoryStatsResponse response = new HistoryStatsResponse();
        int highImpactThreshold = scoringProperties.getHighImpactThreshold();
        int greenerThreshold = scoringProperties.getHistoryGreenerThreshold();
        response.setHighImpactThreshold(highImpactThreshold);
        response.setGreenerThreshold(greenerThreshold);

        if (entries.isEmpty()) {
            response.setAvgScore(null);
            response.setHighImpactCount(0);
            response.setGreenerCount(0);
            return response;
        }

        double avg = entries.stream()
                .map(ScanHistoryEntry::getEcoScore)
                .filter(score -> score != null)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        int highImpact = (int) entries.stream()
                .filter(e -> e.getEcoScore() != null && e.getEcoScore() < highImpactThreshold)
                .count();

        int greener = (int) entries.stream()
                .filter(e -> e.getEcoScore() != null && e.getEcoScore() >= greenerThreshold)
                .count();

        response.setAvgScore(avg);
        response.setHighImpactCount(highImpact);
        response.setGreenerCount(greener);
        return response;
    }

    private HistoryEntryResponse toResponse(ScanHistoryEntry entry) {
        HistoryEntryResponse response = new HistoryEntryResponse();
        response.setId(String.valueOf(entry.getId()));
        response.setUserId(entry.getUserId());
        response.setItem(entry.getItemName());
        response.setCategory(entry.getCategory());
        response.setEcoScore(entry.getEcoScore());
        response.setConfidence(entry.getConfidence());
        response.setTimestamp(entry.getScannedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        return response;
    }

    private String resolveUserId(String requestBodyUserId, String queryUserId) {
        if (requestBodyUserId != null && !requestBodyUserId.isBlank()) {
            return requestBodyUserId.trim();
        }
        if (queryUserId != null && !queryUserId.isBlank()) {
            return queryUserId.trim();
        }
        return "anonymous";
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
