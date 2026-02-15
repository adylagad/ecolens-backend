package com.ecolens.ecolens_backend.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.ecolens.ecolens_backend.config.ScoringProperties;
import com.ecolens.ecolens_backend.dto.HistoryEntryRequest;
import com.ecolens.ecolens_backend.dto.HistoryEntryResponse;
import com.ecolens.ecolens_backend.dto.HistoryStatsResponse;
import com.ecolens.ecolens_backend.model.ScanHistoryEntry;
import com.ecolens.ecolens_backend.repository.ScanHistoryRepository;

@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final WeekFields ISO_WEEK_FIELDS = WeekFields.ISO;

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

    public void delete(String historyId, String requestedUserId) {
        if (mongoAtlasRuntimeStore.isRuntimeEnabled()) {
            try {
                boolean deleted = mongoAtlasRuntimeStore.deleteHistoryEntryByUserAndId(requestedUserId, historyId);
                if (deleted) {
                    return;
                }
            } catch (Exception ex) {
                log.warn("Mongo runtime delete failed, falling back to JPA: {}", ex.getMessage());
            }
        }

        Long parsedId = tryParseLong(historyId);
        if (parsedId != null) {
            var existing = scanHistoryRepository.findByIdAndUserId(parsedId, requestedUserId);
            if (existing.isPresent()) {
                scanHistoryRepository.delete(existing.get());
                return;
            }
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History entry not found.");
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
        String currentWeekKey = toWeekKey(LocalDateTime.now(Clock.systemUTC()));
        response.setHighImpactThreshold(highImpactThreshold);
        response.setGreenerThreshold(greenerThreshold);
        response.setWeekKey(currentWeekKey);

        if (entries.isEmpty()) {
            response.setAvgScore(null);
            response.setHighImpactCount(0);
            response.setGreenerCount(0);
            response.setAvoidedSingleUseCount(0);
            response.setCurrentStreak(0);
            response.setBestStreak(0);
            return response;
        }

        List<ScanHistoryEntry> sortedNewestFirst = entries.stream()
                .sorted(Comparator.comparing(ScanHistoryEntry::getScannedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();

        double avg = sortedNewestFirst.stream()
                .map(ScanHistoryEntry::getEcoScore)
                .filter(score -> score != null)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        int highImpact = (int) sortedNewestFirst.stream()
                .filter(e -> e.getEcoScore() != null && e.getEcoScore() < highImpactThreshold)
                .count();

        int greener = (int) sortedNewestFirst.stream()
                .filter(e -> e.getEcoScore() != null && e.getEcoScore() >= greenerThreshold)
                .count();

        int weeklyEcoFriendlyCount = (int) sortedNewestFirst.stream()
                .filter(entry -> isEcoFriendly(entry, greenerThreshold))
                .filter(entry -> currentWeekKey.equals(toWeekKey(entry.getScannedAt())))
                .count();

        response.setAvgScore(avg);
        response.setHighImpactCount(highImpact);
        response.setGreenerCount(greener);
        response.setAvoidedSingleUseCount(weeklyEcoFriendlyCount);
        response.setCurrentStreak(computeCurrentStreak(sortedNewestFirst, greenerThreshold));
        response.setBestStreak(computeBestStreak(sortedNewestFirst, greenerThreshold));
        return response;
    }

    private HistoryEntryResponse toResponse(ScanHistoryEntry entry) {
        HistoryEntryResponse response = new HistoryEntryResponse();
        String runtimeId = safe(entry.getRuntimeId(), "");
        String resolvedId;
        if (!runtimeId.isBlank()) {
            resolvedId = runtimeId;
        } else if (entry.getId() != null) {
            resolvedId = String.valueOf(entry.getId());
        } else {
            resolvedId = "mongo-" + (entry.getScannedAt() == null
                    ? System.currentTimeMillis()
                    : entry.getScannedAt().toEpochSecond(ZoneOffset.UTC));
        }
        response.setId(resolvedId);
        response.setUserId(entry.getUserId());
        response.setItem(entry.getItemName());
        response.setCategory(entry.getCategory());
        response.setEcoScore(entry.getEcoScore());
        response.setConfidence(entry.getConfidence());
        response.setTimestamp(entry.getScannedAt() == null
                ? LocalDateTime.now(Clock.systemUTC()).atOffset(ZoneOffset.UTC).toInstant().toString()
                : entry.getScannedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        return response;
    }

    private boolean isEcoFriendly(ScanHistoryEntry entry, int greenerThreshold) {
        return entry != null && entry.getEcoScore() != null && entry.getEcoScore() >= greenerThreshold;
    }

    private int computeCurrentStreak(List<ScanHistoryEntry> entriesNewestFirst, int greenerThreshold) {
        int streak = 0;
        for (ScanHistoryEntry entry : entriesNewestFirst) {
            if (isEcoFriendly(entry, greenerThreshold)) {
                streak += 1;
                continue;
            }
            break;
        }
        return streak;
    }

    private int computeBestStreak(List<ScanHistoryEntry> entriesNewestFirst, int greenerThreshold) {
        int best = 0;
        int current = 0;
        for (ScanHistoryEntry entry : entriesNewestFirst) {
            if (isEcoFriendly(entry, greenerThreshold)) {
                current += 1;
                if (current > best) {
                    best = current;
                }
            } else {
                current = 0;
            }
        }
        return best;
    }

    private String toWeekKey(LocalDateTime dateTime) {
        LocalDateTime value = dateTime == null ? LocalDateTime.now(Clock.systemUTC()) : dateTime;
        int weekBasedYear = value.get(ISO_WEEK_FIELDS.weekBasedYear());
        int weekOfYear = value.get(ISO_WEEK_FIELDS.weekOfWeekBasedYear());
        return "%d-W%d".formatted(weekBasedYear, weekOfYear);
    }

    private Long tryParseLong(String value) {
        String text = safe(value, "");
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
