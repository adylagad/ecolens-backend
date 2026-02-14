package com.ecolens.ecolens_backend.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.config.ScoringProperties;
import com.ecolens.ecolens_backend.dto.HistoryEntryRequest;
import com.ecolens.ecolens_backend.dto.HistoryEntryResponse;
import com.ecolens.ecolens_backend.dto.HistoryStatsResponse;
import com.ecolens.ecolens_backend.model.ScanHistoryEntry;
import com.ecolens.ecolens_backend.repository.ScanHistoryRepository;

@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final ScanHistoryRepository scanHistoryRepository;
    private final MongoAtlasRuntimeStore mongoAtlasRuntimeStore;
    private final ScoringProperties scoringProperties;

    public HistoryService(
            ScanHistoryRepository scanHistoryRepository,
            MongoAtlasRuntimeStore mongoAtlasRuntimeStore,
            ScoringProperties scoringProperties
    ) {
        this.scanHistoryRepository = scanHistoryRepository;
        this.mongoAtlasRuntimeStore = mongoAtlasRuntimeStore;
        this.scoringProperties = scoringProperties;
    }

    public HistoryEntryResponse save(HistoryEntryRequest request, String requestedUserId) {
        ScanHistoryEntry entry = new ScanHistoryEntry(
                requestedUserId,
                safe(request.getItem(), "Unknown item"),
                safe(request.getCategory(), "unknown"),
                request.getEcoScore() == null ? 0 : request.getEcoScore(),
                request.getConfidence() == null ? 0.0 : request.getConfidence(),
                LocalDateTime.now(Clock.systemUTC())
        );
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                ScanHistoryEntry savedMongo = mongoAtlasRuntimeStore.saveHistoryEntry(entry);
                return toResponse(savedMongo);
            } catch (Exception ex) {
                log.warn("Mongo runtime save failed, falling back to JPA: {}", ex.getMessage());
            }
        }
        return toResponse(scanHistoryRepository.save(entry));
    }

    public List<HistoryEntryResponse> list(boolean highImpactOnly, String requestedUserId) {
        int highImpactThreshold = scoringProperties.getHighImpactThreshold();
        List<ScanHistoryEntry> entries;
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                entries = highImpactOnly
                        ? mongoAtlasRuntimeStore.findHistoryByUserHighImpact(requestedUserId, highImpactThreshold)
                        : mongoAtlasRuntimeStore.findHistoryByUser(requestedUserId);
            } catch (Exception ex) {
                log.warn("Mongo runtime list failed, falling back to JPA: {}", ex.getMessage());
                entries = highImpactOnly
                        ? scanHistoryRepository.findByUserIdAndEcoScoreLessThanOrderByScannedAtDesc(requestedUserId, highImpactThreshold)
                        : scanHistoryRepository.findAllByUserIdOrderByScannedAtDesc(requestedUserId);
            }
        } else {
            entries = highImpactOnly
                    ? scanHistoryRepository.findByUserIdAndEcoScoreLessThanOrderByScannedAtDesc(requestedUserId, highImpactThreshold)
                    : scanHistoryRepository.findAllByUserIdOrderByScannedAtDesc(requestedUserId);
        }

        return entries.stream().map(this::toResponse).toList();
    }

    public HistoryStatsResponse stats(String requestedUserId) {
        List<ScanHistoryEntry> entries;
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                entries = mongoAtlasRuntimeStore.findHistoryByUser(requestedUserId);
            } catch (Exception ex) {
                log.warn("Mongo runtime stats read failed, falling back to JPA: {}", ex.getMessage());
                entries = scanHistoryRepository.findAllByUserId(requestedUserId);
            }
        } else {
            entries = scanHistoryRepository.findAllByUserId(requestedUserId);
        }

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
        String resolvedId = entry.getId() == null
                ? "mongo-" + (entry.getScannedAt() == null ? System.currentTimeMillis() : entry.getScannedAt().toEpochSecond(ZoneOffset.UTC))
                : String.valueOf(entry.getId());
        response.setId(resolvedId);
        response.setUserId(entry.getUserId());
        response.setItem(entry.getItemName());
        response.setCategory(entry.getCategory());
        response.setEcoScore(entry.getEcoScore());
        response.setConfidence(entry.getConfidence());
        response.setTimestamp(entry.getScannedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        return response;
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
