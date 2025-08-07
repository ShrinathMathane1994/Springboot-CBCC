package com.qa.cbcc.dto;

import java.util.List;

public class ScenarioLogGroupDTO {
    private String scenario;
    private List<String> log;

    public ScenarioLogGroupDTO() {}

    public ScenarioLogGroupDTO(String scenario, List<String> log) {
        this.scenario = scenario;
        this.log = log;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public List<String> getLog() {
        return log;
    }

    public void setLog(List<String> log) {
        this.log = log;
    }
}
