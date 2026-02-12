package com.ecolens.ecolens_backend.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecolens.ecolens_backend.dto.HistoryEntryRequest;
import com.ecolens.ecolens_backend.dto.HistoryEntryResponse;
import com.ecolens.ecolens_backend.dto.HistoryStatsResponse;
import com.ecolens.ecolens_backend.service.HistoryService;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @PostMapping
    public ResponseEntity<HistoryEntryResponse> save(@RequestBody HistoryEntryRequest request) {
        return ResponseEntity.ok(historyService.save(request));
    }

    @GetMapping
    public ResponseEntity<List<HistoryEntryResponse>> list(
            @RequestParam(name = "highImpactOnly", defaultValue = "false") boolean highImpactOnly
    ) {
        return ResponseEntity.ok(historyService.list(highImpactOnly));
    }

    @GetMapping("/stats")
    public ResponseEntity<HistoryStatsResponse> stats() {
        return ResponseEntity.ok(historyService.stats());
    }
}
