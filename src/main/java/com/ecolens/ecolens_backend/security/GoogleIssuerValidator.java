package com.ecolens.ecolens_backend.security;

import java.util.Set;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class GoogleIssuerValidator implements OAuth2TokenValidator<Jwt> {

    private static final Set<String> ALLOWED_ISSUERS = Set.of(
            "https://accounts.google.com",
            "accounts.google.com");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object issuer = jwt.getIssuer();
        String issuerText = issuer == null ? "" : issuer.toString().trim();
        if (ALLOWED_ISSUERS.contains(issuerText)) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Token issuer is not Google.",
                null));
    }
}
