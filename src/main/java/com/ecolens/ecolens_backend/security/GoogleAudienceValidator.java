package com.ecolens.ecolens_backend.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class GoogleAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> allowedAudiences;

    public GoogleAudienceValidator(List<String> configuredAudiences) {
        this.allowedAudiences = configuredAudiences == null
                ? Set.of()
                : configuredAudiences.stream()
                        .map(value -> value == null ? "" : value.trim())
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.toSet());
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (allowedAudiences.isEmpty()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Google audience is not configured. Set AUTH_GOOGLE_AUDIENCES.",
                    null));
        }

        boolean hasAllowedAudience = jwt.getAudience().stream().anyMatch(allowedAudiences::contains);
        if (hasAllowedAudience) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Token audience is not allowed.",
                null));
    }
}
