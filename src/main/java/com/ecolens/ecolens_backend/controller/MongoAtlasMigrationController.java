package com.ecolens.ecolens_backend.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecolens.ecolens_backend.service.MongoAtlasMigrationService;

@RestController
@RequestMapping("/api/admin/mongodb")
public class MongoAtlasMigrationController {

    private final MongoAtlasMigrationService mongoAtlasMigrationService;

    public MongoAtlasMigrationController(MongoAtlasMigrationService mongoAtlasMigrationService) {
        this.mongoAtlasMigrationService = mongoAtlasMigrationService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(mongoAtlasMigrationService.status());
    }

    @PostMapping("/migrate")
    public ResponseEntity<Map<String, Object>> migrate() {
        return ResponseEntity.ok(mongoAtlasMigrationService.migrateNow());
    }

    @PostMapping("/runtime-check")
    public ResponseEntity<Map<String, Object>> runtimeCheck() {
        return ResponseEntity.ok(mongoAtlasMigrationService.runtimeCheck());
    }
}
