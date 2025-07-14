package com.qa.cbcc.dto;

import java.time.LocalDateTime;
import java.util.List;

public class TestCaseDTO {
    private String tcName;
    private String description;

    private List<FeatureScenario> featureScenarios;

    // ✅ New timestamp fields
    private LocalDateTime executionOn;
    private LocalDateTime createdOn;
    private LocalDateTime modifiedOn;

    // Inner class for feature-scenario mapping
    public static class FeatureScenario {
        private String feature;
        private List<String> scenarios;

        public String getFeature() {
            return feature;
        }

        public void setFeature(String feature) {
            this.feature = feature;
        }

        public List<String> getScenarios() {
            return scenarios;
        }

        public void setScenarios(List<String> scenarios) {
            this.scenarios = scenarios;
        }
    }

    // Getters and setters
    public String getTcName() {
        return tcName;
    }

    public void setTcName(String tcName) {
        this.tcName = tcName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<FeatureScenario> getFeatureScenarios() {
        return featureScenarios;
    }

    public void setFeatureScenarios(List<FeatureScenario> featureScenarios) {
        this.featureScenarios = featureScenarios;
    }

    public LocalDateTime getExecutionOn() {
        return executionOn;
    }

    public void setExecutionOn(LocalDateTime executionOn) {
        this.executionOn = executionOn;
    }

    public LocalDateTime getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDateTime createdOn) {
        this.createdOn = createdOn;
    }

    public LocalDateTime getModifiedOn() {
        return modifiedOn;
    }

    public void setModifiedOn(LocalDateTime modifiedOn) {
        this.modifiedOn = modifiedOn;
    }
}
