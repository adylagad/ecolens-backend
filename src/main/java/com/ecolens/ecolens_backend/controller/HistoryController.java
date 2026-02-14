package com.ecolens.ecolens_backend.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public ResponseEntity<HistoryEntryResponse> save(
            @RequestBody HistoryEntryRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(historyService.save(request, resolveAuthenticatedUserId(jwt)));
    }

    @GetMapping
    public ResponseEntity<List<HistoryEntryResponse>> list(
            @RequestParam(name = "highImpactOnly", defaultValue = "false") boolean highImpactOnly,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(historyService.list(highImpactOnly, resolveAuthenticatedUserId(jwt)));
    }

    @GetMapping("/stats")
    public ResponseEntity<HistoryStatsResponse> stats(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(historyService.stats(resolveAuthenticatedUserId(jwt)));
    }

    private String resolveAuthenticatedUserId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication token.");
        }
        String subject = jwt.getSubject();
        if (subject != null && !subject.isBlank()) {
            return subject.trim();
        }
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email.trim().toLowerCase();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unable to resolve authenticated user.");
    }
}
