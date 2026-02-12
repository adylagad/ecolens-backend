package com.ecolens.ecolens_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "catalog")
public class CatalogProperties {

    private boolean autoLearnEnabled = true;
    private boolean autoLearnRequireImage = true;
    private double autoLearnMinConfidence = 0.65;
    private Coverage coverage = new Coverage();

    public boolean isAutoLearnEnabled() {
        return autoLearnEnabled;
    }

    public void setAutoLearnEnabled(boolean autoLearnEnabled) {
        this.autoLearnEnabled = autoLearnEnabled;
    }

    public boolean isAutoLearnRequireImage() {
        return autoLearnRequireImage;
    }

    public void setAutoLearnRequireImage(boolean autoLearnRequireImage) {
        this.autoLearnRequireImage = autoLearnRequireImage;
    }

    public double getAutoLearnMinConfidence() {
        return autoLearnMinConfidence;
    }

    public void setAutoLearnMinConfidence(double autoLearnMinConfidence) {
        this.autoLearnMinConfidence = autoLearnMinConfidence;
    }

    public Coverage getCoverage() {
        return coverage;
    }

    public void setCoverage(Coverage coverage) {
        this.coverage = coverage;
    }

    public static class Coverage {
        private double exact = 1.0;
        private double fuzzyMin = 0.65;
        private double autoLearned = 0.6;
        private double none = 0.3;
        private double inferencePenalty = 0.9;

        public double getExact() {
            return exact;
        }

        public void setExact(double exact) {
            this.exact = exact;
        }

        public double getFuzzyMin() {
            return fuzzyMin;
        }

        public void setFuzzyMin(double fuzzyMin) {
            this.fuzzyMin = fuzzyMin;
        }

        public double getAutoLearned() {
            return autoLearned;
        }

        public void setAutoLearned(double autoLearned) {
            this.autoLearned = autoLearned;
        }

        public double getNone() {
            return none;
        }

        public void setNone(double none) {
            this.none = none;
        }

        public double getInferencePenalty() {
            return inferencePenalty;
        }

        public void setInferencePenalty(double inferencePenalty) {
            this.inferencePenalty = inferencePenalty;
        }
    }
}

