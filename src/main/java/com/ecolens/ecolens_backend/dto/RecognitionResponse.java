package com.ecolens.ecolens_backend.dto;

public class RecognitionResponse {

    private String name;
    private String category;
    private Integer ecoScore;
    private Double co2Gram;
    private String recyclability;
    private String altRecommendation;
    private String explanation;
    private Double confidence;

    public RecognitionResponse() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Double getCo2Gram() {
        return co2Gram;
    }

    public void setCo2Gram(Double co2Gram) {
        this.co2Gram = co2Gram;
    }

    public String getRecyclability() {
        return recyclability;
    }

    public void setRecyclability(String recyclability) {
        this.recyclability = recyclability;
    }

    public String getAltRecommendation() {
        return altRecommendation;
    }

    public void setAltRecommendation(String altRecommendation) {
        this.altRecommendation = altRecommendation;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
