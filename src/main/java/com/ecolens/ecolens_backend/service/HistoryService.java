package com.ecolens.ecolens_backend.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ecolens.ecolens_backend.dto.HistoryEntryRequest;
import com.ecolens.ecolens_backend.dto.HistoryEntryResponse;
import com.ecolens.ecolens_backend.dto.HistoryStatsResponse;
import com.ecolens.ecolens_backend.model.ScanHistoryEntry;
import com.ecolens.ecolens_backend.repository.ScanHistoryRepository;

@Service
public class HistoryService {

    private final ScanHistoryRepository scanHistoryRepository;

    public HistoryService(ScanHistoryRepository scanHistoryRepository) {
        this.scanHistoryRepository = scanHistoryRepository;
    }

    public HistoryEntryResponse save(HistoryEntryRequest request) {
        ScanHistoryEntry entry = new ScanHistoryEntry(
                safe(request.getItem(), "Unknown item"),
                safe(request.getCategory(), "unknown"),
                request.getEcoScore() == null ? 0 : request.getEcoScore(),
                request.getConfidence() == null ? 0.0 : request.getConfidence(),
                LocalDateTime.now()
        );
        ScanHistoryEntry saved = scanHistoryRepository.save(entry);
        return toResponse(saved);
    }

    public List<HistoryEntryResponse> list(boolean highImpactOnly) {
        List<ScanHistoryEntry> entries = highImpactOnly
                ? scanHistoryRepository.findByEcoScoreLessThanOrderByScannedAtDesc(40)
                : scanHistoryRepository.findAllByOrderByScannedAtDesc();

        return entries.stream().map(this::toResponse).toList();
    }

    public HistoryStatsResponse stats() {
        List<ScanHistoryEntry> entries = scanHistoryRepository.findAll();
        HistoryStatsResponse response = new HistoryStatsResponse();

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
                .filter(e -> e.getEcoScore() != null && e.getEcoScore() < 40)
                .count();

        int greener = (int) entries.stream()
                .filter(e -> e.getEcoScore() != null && e.getEcoScore() >= 85)
                .count();

        response.setAvgScore(avg);
        response.setHighImpactCount(highImpact);
        response.setGreenerCount(greener);
        return response;
    }

    private HistoryEntryResponse toResponse(ScanHistoryEntry entry) {
        HistoryEntryResponse response = new HistoryEntryResponse();
        response.setId(String.valueOf(entry.getId()));
        response.setItem(entry.getItemName());
        response.setCategory(entry.getCategory());
        response.setEcoScore(entry.getEcoScore());
        response.setConfidence(entry.getConfidence());
        response.setTimestamp(entry.getScannedAt().toString());
        return response;
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
