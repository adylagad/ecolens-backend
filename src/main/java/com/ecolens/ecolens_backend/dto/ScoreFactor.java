package com.ecolens.ecolens_backend.dto;

public class ScoreFactor {

    private String code;
    private String label;
    private Double delta;
    private String detail;

    public ScoreFactor() {
    }

    public ScoreFactor(String code, String label, Double delta, String detail) {
        this.code = code;
        this.label = label;
        this.delta = delta;
        this.detail = detail;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Double getDelta() {
        return delta;
    }

    public void setDelta(Double delta) {
        this.delta = delta;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
