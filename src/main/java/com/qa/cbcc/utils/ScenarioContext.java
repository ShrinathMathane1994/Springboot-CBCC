package com.qa.cbcc.utils;

import java.util.List;

public class ScenarioContext {
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
