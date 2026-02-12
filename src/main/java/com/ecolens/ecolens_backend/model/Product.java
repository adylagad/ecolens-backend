package com.ecolens.ecolens_backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @Column(name = "eco_score")
    private Integer ecoScore;

    @Column(name = "co2_gram")
    private Double carbonImpact;

    @Column(nullable = false)
    private String recyclability;

    @Column(name = "alt_recommendation")
    private String alternativeRecommendation;

    @Column(nullable = true, length = 2000)
    private String explanation;

    @Column(name = "material")
    private String material;

    @Column(name = "is_reusable")
    private Boolean reusable;

    @Column(name = "is_single_use")
    private Boolean singleUse;

    @Column(name = "recycled_content_percent")
    private Integer recycledContentPercent;

    @Column(name = "lifecycle_type")
    private String lifecycleType;

    public Product() {
    }

    public Product(String name, String category, Integer ecoScore, Double carbonImpact, String recyclability,
                   String alternativeRecommendation, String explanation, String material, Boolean reusable,
                   Boolean singleUse, Integer recycledContentPercent, String lifecycleType) {
        this.name = name;
        this.category = category;
        this.ecoScore = ecoScore;
        this.carbonImpact = carbonImpact;
        this.recyclability = recyclability;
        this.alternativeRecommendation = alternativeRecommendation;
        this.explanation = explanation;
        this.material = material;
        this.reusable = reusable;
        this.singleUse = singleUse;
        this.recycledContentPercent = recycledContentPercent;
        this.lifecycleType = lifecycleType;
    }

    public Long getId() {
        return id;
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

    public Double getCarbonImpact() {
        return carbonImpact;
    }

    public void setCarbonImpact(Double carbonImpact) {
        this.carbonImpact = carbonImpact;
    }

    public String getRecyclability() {
        return recyclability;
    }

    public void setRecyclability(String recyclability) {
        this.recyclability = recyclability;
    }

    public String getAlternativeRecommendation() {
        return alternativeRecommendation;
    }

    public void setAlternativeRecommendation(String alternativeRecommendation) {
        this.alternativeRecommendation = alternativeRecommendation;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public Boolean getReusable() {
        return reusable;
    }

    public void setReusable(Boolean reusable) {
        this.reusable = reusable;
    }

    public Boolean getSingleUse() {
        return singleUse;
    }

    public void setSingleUse(Boolean singleUse) {
        this.singleUse = singleUse;
    }

    public Integer getRecycledContentPercent() {
        return recycledContentPercent;
    }

    public void setRecycledContentPercent(Integer recycledContentPercent) {
        this.recycledContentPercent = recycledContentPercent;
    }

    public String getLifecycleType() {
        return lifecycleType;
    }

    public void setLifecycleType(String lifecycleType) {
        this.lifecycleType = lifecycleType;
    }
}
