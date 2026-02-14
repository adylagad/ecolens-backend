package com.ecolens.ecolens_backend.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sub", jwt.getSubject());
        response.put("email", jwt.getClaimAsString("email"));
        response.put("name", jwt.getClaimAsString("name"));
        response.put("picture", jwt.getClaimAsString("picture"));
        response.put("email_verified", jwt.getClaim("email_verified"));
        response.put("aud", jwt.getAudience());
        response.put("iss", jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
        return ResponseEntity.ok(response);
    }
}
