package com.ecolens.ecolens_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "scoring")
public class ScoringProperties {

    private String version = "v2-config-weighted";
    private int minScore = 0;
    private int maxScore = 100;
    private int defaultCatalogEcoScore = 50;
    private int defaultCo2Score = 50;
    private double defaultCarbonImpactGram = 100.0;
    private double catalogWeight = 0.45;
    private double co2Weight = 0.55;
    private int greenerAlternativeBoost = 6;
    private int greenerAlternativeThreshold = 90;
    private int highImpactThreshold = 40;
    private int moderateImpactThreshold = 70;
    private Adjustments adjustments = new Adjustments();

    public int getMinScore() {
        return minScore;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setMinScore(int minScore) {
        this.minScore = minScore;
    }

    public int getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(int maxScore) {
        this.maxScore = maxScore;
    }

    public int getDefaultCatalogEcoScore() {
        return defaultCatalogEcoScore;
    }

    public void setDefaultCatalogEcoScore(int defaultCatalogEcoScore) {
        this.defaultCatalogEcoScore = defaultCatalogEcoScore;
    }

    public int getDefaultCo2Score() {
        return defaultCo2Score;
    }

    public void setDefaultCo2Score(int defaultCo2Score) {
        this.defaultCo2Score = defaultCo2Score;
    }

    public double getDefaultCarbonImpactGram() {
        return defaultCarbonImpactGram;
    }

    public void setDefaultCarbonImpactGram(double defaultCarbonImpactGram) {
        this.defaultCarbonImpactGram = defaultCarbonImpactGram;
    }

    public double getCatalogWeight() {
        return catalogWeight;
    }

    public void setCatalogWeight(double catalogWeight) {
        this.catalogWeight = catalogWeight;
    }

    public double getCo2Weight() {
        return co2Weight;
    }

    public void setCo2Weight(double co2Weight) {
        this.co2Weight = co2Weight;
    }

    public int getGreenerAlternativeBoost() {
        return greenerAlternativeBoost;
    }

    public void setGreenerAlternativeBoost(int greenerAlternativeBoost) {
        this.greenerAlternativeBoost = greenerAlternativeBoost;
    }

    public int getGreenerAlternativeThreshold() {
        return greenerAlternativeThreshold;
    }

    public void setGreenerAlternativeThreshold(int greenerAlternativeThreshold) {
        this.greenerAlternativeThreshold = greenerAlternativeThreshold;
    }

    public int getHighImpactThreshold() {
        return highImpactThreshold;
    }

    public void setHighImpactThreshold(int highImpactThreshold) {
        this.highImpactThreshold = highImpactThreshold;
    }

    public int getModerateImpactThreshold() {
        return moderateImpactThreshold;
    }

    public void setModerateImpactThreshold(int moderateImpactThreshold) {
        this.moderateImpactThreshold = moderateImpactThreshold;
    }

    public Adjustments getAdjustments() {
        return adjustments;
    }

    public void setAdjustments(Adjustments adjustments) {
        this.adjustments = adjustments;
    }

    public static class Adjustments {
        private int singleUsePenalty = -18;
        private int reusableBonus = 18;
        private int plasticPenalty = -10;
        private int paperPenalty = -2;
        private int aluminumGlassBonus = 5;
        private int clothRecycledBonus = 10;
        private int recyclabilityHighBonus = 10;
        private int recyclabilityMediumBonus = 3;
        private int recyclabilityLowPenalty = -8;
        private int recyclabilityOrganicBonus = 6;

        public int getSingleUsePenalty() {
            return singleUsePenalty;
        }

        public void setSingleUsePenalty(int singleUsePenalty) {
            this.singleUsePenalty = singleUsePenalty;
        }

        public int getReusableBonus() {
            return reusableBonus;
        }

        public void setReusableBonus(int reusableBonus) {
            this.reusableBonus = reusableBonus;
        }

        public int getPlasticPenalty() {
            return plasticPenalty;
        }

        public void setPlasticPenalty(int plasticPenalty) {
            this.plasticPenalty = plasticPenalty;
        }

        public int getPaperPenalty() {
            return paperPenalty;
        }

        public void setPaperPenalty(int paperPenalty) {
            this.paperPenalty = paperPenalty;
        }

        public int getAluminumGlassBonus() {
            return aluminumGlassBonus;
        }

        public void setAluminumGlassBonus(int aluminumGlassBonus) {
            this.aluminumGlassBonus = aluminumGlassBonus;
        }

        public int getClothRecycledBonus() {
            return clothRecycledBonus;
        }

        public void setClothRecycledBonus(int clothRecycledBonus) {
            this.clothRecycledBonus = clothRecycledBonus;
        }

        public int getRecyclabilityHighBonus() {
            return recyclabilityHighBonus;
        }

        public void setRecyclabilityHighBonus(int recyclabilityHighBonus) {
            this.recyclabilityHighBonus = recyclabilityHighBonus;
        }

        public int getRecyclabilityMediumBonus() {
            return recyclabilityMediumBonus;
        }

        public void setRecyclabilityMediumBonus(int recyclabilityMediumBonus) {
            this.recyclabilityMediumBonus = recyclabilityMediumBonus;
        }

        public int getRecyclabilityLowPenalty() {
            return recyclabilityLowPenalty;
        }

        public void setRecyclabilityLowPenalty(int recyclabilityLowPenalty) {
            this.recyclabilityLowPenalty = recyclabilityLowPenalty;
        }

        public int getRecyclabilityOrganicBonus() {
            return recyclabilityOrganicBonus;
        }

        public void setRecyclabilityOrganicBonus(int recyclabilityOrganicBonus) {
            this.recyclabilityOrganicBonus = recyclabilityOrganicBonus;
        }
    }
}
