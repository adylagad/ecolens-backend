package com.ecolens.ecolens_backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecolens.ecolens_backend.model.ScanHistoryEntry;

public interface ScanHistoryRepository extends JpaRepository<ScanHistoryEntry, Long> {

    List<ScanHistoryEntry> findAllByOrderByScannedAtDesc();

    List<ScanHistoryEntry> findByEcoScoreLessThanOrderByScannedAtDesc(Integer threshold);
}
