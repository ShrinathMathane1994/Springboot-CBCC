package com.qa.cbcc.dto;

import java.time.LocalDateTime;
import java.util.List;

public class TestCaseRunResultDTO {
    private Long testCaseId;
    private String testCaseName;
    private LocalDateTime executionOn;
    private List<ScenarioResultDTO> executedScenarios;
    private String tcStatus;
    private List<String> executedScenarioNames;
    private List<String> unexecutedScenarioNames;

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

    public List<String> getExecutedScenarioNames() {
        return executedScenarioNames;
    }

    public void setExecutedScenarioNames(List<String> executedScenarioNames) {
        this.executedScenarioNames = executedScenarioNames;
    }

    public List<String> getUnexecutedScenarioNames() {
        return unexecutedScenarioNames;
    }

    public void setUnexecutedScenarioNames(List<String> unexecutedScenarioNames) {
        this.unexecutedScenarioNames = unexecutedScenarioNames;
    }
}
