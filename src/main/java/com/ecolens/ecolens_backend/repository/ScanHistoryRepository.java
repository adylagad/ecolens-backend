package com.ecolens.ecolens_backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecolens.ecolens_backend.model.ScanHistoryEntry;

public interface ScanHistoryRepository extends JpaRepository<ScanHistoryEntry, Long> {

    List<ScanHistoryEntry> findAllByOrderByScannedAtDesc();

    List<ScanHistoryEntry> findByEcoScoreLessThanOrderByScannedAtDesc(Integer threshold);

    List<ScanHistoryEntry> findAllByUserIdOrderByScannedAtDesc(String userId);

    List<ScanHistoryEntry> findByUserIdAndEcoScoreLessThanOrderByScannedAtDesc(String userId, Integer threshold);

    List<ScanHistoryEntry> findAllByUserId(String userId);

    Optional<ScanHistoryEntry> findByIdAndUserId(Long id, String userId);
}
