package com.ecolens.ecolens_backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecolens.ecolens_backend.dto.TrainingExportResponse;
import com.ecolens.ecolens_backend.dto.TrainingSampleRequest;
import com.ecolens.ecolens_backend.dto.TrainingSampleResponse;
import com.ecolens.ecolens_backend.service.TrainingDataService;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/training")
public class TrainingController {

    private final TrainingDataService trainingDataService;

    public TrainingController(TrainingDataService trainingDataService) {
        this.trainingDataService = trainingDataService;
    }

    @GetMapping("/taxonomy")
    public ResponseEntity<JsonNode> taxonomy() {
        return ResponseEntity.ok(trainingDataService.getTaxonomy());
    }

    @PostMapping("/samples")
    public ResponseEntity<TrainingSampleResponse> saveSample(@RequestBody TrainingSampleRequest request) {
        return ResponseEntity.ok(trainingDataService.saveSample(request));
    }

    @GetMapping("/samples")
    public ResponseEntity<List<TrainingSampleResponse>> listSamples(
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            @RequestParam(name = "confirmedOnly", defaultValue = "true") boolean confirmedOnly,
            @RequestParam(name = "includeImages", defaultValue = "false") boolean includeImages
    ) {
        return ResponseEntity.ok(trainingDataService.listSamples(limit, confirmedOnly, includeImages));
    }

    @GetMapping("/export")
    public ResponseEntity<TrainingExportResponse> exportSamples(
            @RequestParam(name = "limit", defaultValue = "1000") int limit,
            @RequestParam(name = "confirmedOnly", defaultValue = "true") boolean confirmedOnly,
            @RequestParam(name = "includeImages", defaultValue = "true") boolean includeImages
    ) {
        return ResponseEntity.ok(trainingDataService.exportSamples(limit, confirmedOnly, includeImages));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(trainingDataService.trainingStats());
    }
}
