package com.ecolens.ecolens_backend.dto;

public class TrainingSampleResponse {

    private String id;
    private String userId;
    private String finalLabel;
    private String taxonomyLeaf;
    private String taxonomyParent;
    private String predictedLabel;
    private Double predictedConfidence;
    private String sourceEngine;
    private String sourceRuntime;
    private String devicePlatform;
    private String appVersion;
    private Boolean userConfirmed;
    private String capturedAt;
    private String imageSha256;
    private String imageBase64;

    public TrainingSampleResponse() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public String getTaxonomyParent() {
        return taxonomyParent;
    }

    public void setTaxonomyParent(String taxonomyParent) {
        this.taxonomyParent = taxonomyParent;
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

    public String getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(String capturedAt) {
        this.capturedAt = capturedAt;
    }

    public String getImageSha256() {
        return imageSha256;
    }

    public void setImageSha256(String imageSha256) {
        this.imageSha256 = imageSha256;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }
}
