package com.ecolens.ecolens_backend.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ecolens.ecolens_backend.model.TrainingSample;

public interface TrainingSampleRepository extends JpaRepository<TrainingSample, Long> {

    List<TrainingSample> findAllByOrderByCapturedAtDesc(Pageable pageable);

    List<TrainingSample> findByUserConfirmedTrueOrderByCapturedAtDesc(Pageable pageable);

    long countByUserConfirmedTrue();
}
