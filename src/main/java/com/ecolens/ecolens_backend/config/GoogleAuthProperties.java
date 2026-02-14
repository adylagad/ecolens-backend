package com.ecolens.ecolens_backend.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.google")
public class GoogleAuthProperties {

    private List<String> audiences = new ArrayList<>();
    private String jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs";

    public List<String> getAudiences() {
        return audiences;
    }

    public void setAudiences(List<String> audiences) {
        this.audiences = audiences;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }
}
