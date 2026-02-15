package com.ecolens.ecolens_backend.dto;

public class VoiceSummaryResponse {

    private String audioUrl;
    private String provider;
    private String contentType;

    public VoiceSummaryResponse() {
    }

    public VoiceSummaryResponse(String audioUrl, String provider, String contentType) {
        this.audioUrl = audioUrl;
        this.provider = provider;
        this.contentType = contentType;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
