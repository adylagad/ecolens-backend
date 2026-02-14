package com.ecolens.ecolens_backend.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "training_samples")
public class TrainingSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Lob
    @Column(name = "image_base64")
    private String imageBase64;

    @Column(name = "image_sha256", length = 64)
    private String imageSha256;

    @Column(name = "predicted_label")
    private String predictedLabel;

    @Column(name = "predicted_confidence")
    private Double predictedConfidence;

    @Column(name = "final_label", nullable = false)
    private String finalLabel;

    @Column(name = "taxonomy_leaf", nullable = false)
    private String taxonomyLeaf;

    @Column(name = "taxonomy_parent", nullable = false)
    private String taxonomyParent;

    @Column(name = "source_engine")
    private String sourceEngine;

    @Column(name = "source_runtime")
    private String sourceRuntime;

    @Column(name = "device_platform")
    private String devicePlatform;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "user_confirmed", nullable = false)
    private Boolean userConfirmed;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    public TrainingSample() {
    }

    public Long getId() {
        return id;
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

    public String getImageSha256() {
        return imageSha256;
    }

    public void setImageSha256(String imageSha256) {
        this.imageSha256 = imageSha256;
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

    public String getTaxonomyParent() {
        return taxonomyParent;
    }

    public void setTaxonomyParent(String taxonomyParent) {
        this.taxonomyParent = taxonomyParent;
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

    public LocalDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(LocalDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }
}
