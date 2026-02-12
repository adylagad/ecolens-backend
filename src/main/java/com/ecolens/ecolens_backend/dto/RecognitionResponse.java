package com.ecolens.ecolens_backend.dto;

import java.util.List;

public class RecognitionResponse {

    private String name;
    private String category;
    private Integer ecoScore;
    private Integer catalogEcoScore;
    private Integer co2Score;
    private Double co2Gram;
    private String recyclability;
    private String altRecommendation;
    private String explanation;
    private Double confidence;
    private Double catalogContribution;
    private Double co2Contribution;
    private Integer featureAdjustment;
    private Double preBoostScore;
    private Integer greenerAlternativeBoost;
    private Boolean greenerAlternativeBoostApplied;
    private String scoringVersion;
    private List<ScoreFactor> scoreFactors;
    private String catalogMatchStrategy;
    private Double catalogCoverage;
    private Boolean catalogAutoLearned;

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

    public Integer getCatalogEcoScore() {
        return catalogEcoScore;
    }

    public void setCatalogEcoScore(Integer catalogEcoScore) {
        this.catalogEcoScore = catalogEcoScore;
    }

    public Integer getCo2Score() {
        return co2Score;
    }

    public void setCo2Score(Integer co2Score) {
        this.co2Score = co2Score;
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

    public Double getCatalogContribution() {
        return catalogContribution;
    }

    public void setCatalogContribution(Double catalogContribution) {
        this.catalogContribution = catalogContribution;
    }

    public Double getCo2Contribution() {
        return co2Contribution;
    }

    public void setCo2Contribution(Double co2Contribution) {
        this.co2Contribution = co2Contribution;
    }

    public Integer getFeatureAdjustment() {
        return featureAdjustment;
    }

    public void setFeatureAdjustment(Integer featureAdjustment) {
        this.featureAdjustment = featureAdjustment;
    }

    public Double getPreBoostScore() {
        return preBoostScore;
    }

    public void setPreBoostScore(Double preBoostScore) {
        this.preBoostScore = preBoostScore;
    }

    public Integer getGreenerAlternativeBoost() {
        return greenerAlternativeBoost;
    }

    public void setGreenerAlternativeBoost(Integer greenerAlternativeBoost) {
        this.greenerAlternativeBoost = greenerAlternativeBoost;
    }

    public Boolean getGreenerAlternativeBoostApplied() {
        return greenerAlternativeBoostApplied;
    }

    public void setGreenerAlternativeBoostApplied(Boolean greenerAlternativeBoostApplied) {
        this.greenerAlternativeBoostApplied = greenerAlternativeBoostApplied;
    }

    public String getScoringVersion() {
        return scoringVersion;
    }

    public void setScoringVersion(String scoringVersion) {
        this.scoringVersion = scoringVersion;
    }

    public List<ScoreFactor> getScoreFactors() {
        return scoreFactors;
    }

    public void setScoreFactors(List<ScoreFactor> scoreFactors) {
        this.scoreFactors = scoreFactors;
    }

    public String getCatalogMatchStrategy() {
        return catalogMatchStrategy;
    }

    public void setCatalogMatchStrategy(String catalogMatchStrategy) {
        this.catalogMatchStrategy = catalogMatchStrategy;
    }

    public Double getCatalogCoverage() {
        return catalogCoverage;
    }

    public void setCatalogCoverage(Double catalogCoverage) {
        this.catalogCoverage = catalogCoverage;
    }

    public Boolean getCatalogAutoLearned() {
        return catalogAutoLearned;
    }

    public void setCatalogAutoLearned(Boolean catalogAutoLearned) {
        this.catalogAutoLearned = catalogAutoLearned;
    }
}
