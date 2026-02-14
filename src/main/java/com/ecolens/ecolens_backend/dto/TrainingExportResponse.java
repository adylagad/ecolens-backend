package com.ecolens.ecolens_backend.dto;

import java.util.ArrayList;
import java.util.List;

public class TrainingExportResponse {

    private String taxonomyVersion;
    private String generatedAt;
    private Integer sampleCount;
    private Boolean includeImages;
    private List<TrainingSampleResponse> samples = new ArrayList<>();

    public TrainingExportResponse() {
    }

    public String getTaxonomyVersion() {
        return taxonomyVersion;
    }

    public void setTaxonomyVersion(String taxonomyVersion) {
        this.taxonomyVersion = taxonomyVersion;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Integer getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(Integer sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Boolean getIncludeImages() {
        return includeImages;
    }

    public void setIncludeImages(Boolean includeImages) {
        this.includeImages = includeImages;
    }

    public List<TrainingSampleResponse> getSamples() {
        return samples;
    }

    public void setSamples(List<TrainingSampleResponse> samples) {
        this.samples = samples;
    }
}
