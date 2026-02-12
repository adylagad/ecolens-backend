package com.ecolens.ecolens_backend.dto;

public class HistoryStatsResponse {

    private Double avgScore;
    private Integer highImpactCount;
    private Integer greenerCount;

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
}
