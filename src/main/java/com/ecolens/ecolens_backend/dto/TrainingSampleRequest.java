package com.ecolens.ecolens_backend.dto;

public class TrainingSampleRequest {

    private String userId;
    private String imageBase64;
    private String predictedLabel;
    private Double predictedConfidence;
    private String finalLabel;
    private String taxonomyLeaf;
    private String sourceEngine;
    private String sourceRuntime;
    private String devicePlatform;
    private String appVersion;
    private Boolean userConfirmed;

    public TrainingSampleRequest() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    public String getPredictedLabel() {
        return predictedLabel;
    }

    public void setPredictedLabel(String predictedLabel) {
        this.predictedLabel = predictedLabel;
    }

    public Double getPredictedConfidence() {
        return predictedConfidence;
    }

    public void setPredictedConfidence(Double predictedConfidence) {
        this.predictedConfidence = predictedConfidence;
    }

    public String getFinalLabel() {
        return finalLabel;
    }

    public void setFinalLabel(String finalLabel) {
        this.finalLabel = finalLabel;
    }

    public String getTaxonomyLeaf() {
        return taxonomyLeaf;
    }

    public void setTaxonomyLeaf(String taxonomyLeaf) {
        this.taxonomyLeaf = taxonomyLeaf;
    }

    public String getSourceEngine() {
        return sourceEngine;
    }

    public void setSourceEngine(String sourceEngine) {
        this.sourceEngine = sourceEngine;
    }

    public String getSourceRuntime() {
        return sourceRuntime;
    }

    public void setSourceRuntime(String sourceRuntime) {
        this.sourceRuntime = sourceRuntime;
    }

    public String getDevicePlatform() {
        return devicePlatform;
    }

    public void setDevicePlatform(String devicePlatform) {
        this.devicePlatform = devicePlatform;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public Boolean getUserConfirmed() {
        return userConfirmed;
    }

    public void setUserConfirmed(Boolean userConfirmed) {
        this.userConfirmed = userConfirmed;
    }
}
