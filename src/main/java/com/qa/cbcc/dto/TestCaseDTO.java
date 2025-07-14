package com.qa.cbcc.dto;

import java.util.List;
import java.util.Map;

public class TestCaseDTO {
    private String tcName;
    private String description;

    // List of feature files with scenarios
    private List<FeatureScenario> featureScenarios;

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

    // Getters & Setters
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
}
