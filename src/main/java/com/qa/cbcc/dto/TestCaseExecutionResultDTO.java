package com.qa.cbcc.dto;

import java.time.LocalDateTime;
import java.util.List;

public class TestCaseExecutionResultDTO {
    private Long testCaseId;
    private String testCaseName;
    private LocalDateTime executionOn;
    private List<ScenarioResultDTO> executedScenarios;
    private String tcStatus;

    // Getters and setters

    public Long getTestCaseId() {
        return testCaseId;
    }

    public void setTestCaseId(Long testCaseId) {
        this.testCaseId = testCaseId;
    }

    public String getTestCaseName() {
        return testCaseName;
    }

    public void setTestCaseName(String testCaseName) {
        this.testCaseName = testCaseName;
    }

    public LocalDateTime getExecutionOn() {
        return executionOn;
    }

    public void setExecutionOn(LocalDateTime executionOn) {
        this.executionOn = executionOn;
    }

    public List<ScenarioResultDTO> getExecutedScenarios() {
        return executedScenarios;
    }

    public void setExecutedScenarios(List<ScenarioResultDTO> executedScenarios) {
        this.executedScenarios = executedScenarios;
    }

    public String getTcStatus() {
        return tcStatus;
    }

    public void setTcStatus(String tcStatus) {
        this.tcStatus = tcStatus;
    }
}
