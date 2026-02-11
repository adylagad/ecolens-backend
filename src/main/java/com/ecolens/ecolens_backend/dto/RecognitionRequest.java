package com.ecolens.ecolens_backend.dto;

public class RecognitionRequest {

    private String detectedLabel;
    private Double confidence;
    private String imageBase64;

    public RecognitionRequest() {
    }

    public String getDetectedLabel() {
        return detectedLabel;
    }

    public void setDetectedLabel(String detectedLabel) {
        this.detectedLabel = detectedLabel;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }
}
