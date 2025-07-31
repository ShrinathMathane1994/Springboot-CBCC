package com.qa.cbcc.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public class TestCaseDTO {

    private Long tcId;  // ✅ Added Test Case ID
    private String tcName;
    private String description;
    private List<FeatureScenario> featureScenarios;

    // ✅ Timestamp fields
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executionOn;
    private LocalDateTime createdOn;
    private LocalDateTime modifiedOn;
    private String country;
    private String region;
    private String pod;

    // New Getters & Setters
    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getPod() {
        return pod;
    }

    public void setPod(String pod) {
        this.pod = pod;
    }


    // Inner class for feature-scenario mapping
    public static class FeatureScenario {
        private String feature;
        private List<String> scenarios;         // used for creation & execution
        private List<String> scenarioBlocks;    // optional, not used at creation

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

        public List<String> getScenarioBlocks() {
            return scenarioBlocks;
        }

        public void setScenarioBlocks(List<String> scenarioBlocks) {
            this.scenarioBlocks = scenarioBlocks;
        }
    }

    // Getters and setters

    public Long getTcId() {
        return tcId;
    }

    public void setTcId(Long tcId) {
        this.tcId = tcId;
    }

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
