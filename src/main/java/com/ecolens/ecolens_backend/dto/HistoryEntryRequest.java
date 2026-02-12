package com.ecolens.ecolens_backend.dto;

public class HistoryEntryRequest {

    private String userId;
    private String item;
    private String category;
    private Integer ecoScore;
    private Double confidence;

    public HistoryEntryRequest() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getEcoScore() {
        return ecoScore;
    }

    public void setEcoScore(Integer ecoScore) {
        this.ecoScore = ecoScore;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
