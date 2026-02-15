package com.ecolens.ecolens_backend.dto;

public class HistoryStatsResponse {

    private Double avgScore;
    private Integer highImpactCount;
    private Integer greenerCount;
    private Integer highImpactThreshold;
    private Integer greenerThreshold;
    private String weekKey;
    private Integer avoidedSingleUseCount;
    private Integer currentStreak;
    private Integer bestStreak;

    public HistoryStatsResponse() {
    }

    public Double getAvgScore() {
        return avgScore;
    }

    public void setAvgScore(Double avgScore) {
        this.avgScore = avgScore;
    }

    public Integer getHighImpactCount() {
        return highImpactCount;
    }

    public void setHighImpactCount(Integer highImpactCount) {
        this.highImpactCount = highImpactCount;
    }

    public Integer getGreenerCount() {
        return greenerCount;
    }

    public void setGreenerCount(Integer greenerCount) {
        this.greenerCount = greenerCount;
    }

    public Integer getHighImpactThreshold() {
        return highImpactThreshold;
    }

    public void setHighImpactThreshold(Integer highImpactThreshold) {
        this.highImpactThreshold = highImpactThreshold;
    }

    public Integer getGreenerThreshold() {
        return greenerThreshold;
    }

    public void setGreenerThreshold(Integer greenerThreshold) {
        this.greenerThreshold = greenerThreshold;
    }

    public String getWeekKey() {
        return weekKey;
    }

    public void setWeekKey(String weekKey) {
        this.weekKey = weekKey;
    }

    public Integer getAvoidedSingleUseCount() {
        return avoidedSingleUseCount;
    }

    public void setAvoidedSingleUseCount(Integer avoidedSingleUseCount) {
        this.avoidedSingleUseCount = avoidedSingleUseCount;
    }

    public Integer getCurrentStreak() {
        return currentStreak;
    }

    public void setCurrentStreak(Integer currentStreak) {
        this.currentStreak = currentStreak;
    }

    public Integer getBestStreak() {
        return bestStreak;
    }

    public void setBestStreak(Integer bestStreak) {
        this.bestStreak = bestStreak;
    }
}
