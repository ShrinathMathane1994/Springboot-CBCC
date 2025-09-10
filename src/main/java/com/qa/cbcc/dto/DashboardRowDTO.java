package com.qa.cbcc.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class DashboardRowDTO {

	private Long testCaseId;
    private String testCaseName;
    private String scenarioName;
    private int totalRunsForTC;

    private int executedCount;
    private double executedPercent;

    private int unexecutedCount;
    private double unexecutedPercent;

    private String lastStatus;
    private LocalDateTime lastRunTime;
    private String formattedLastRunTime;
    private Map<String, Object> statusWisePercentageJson;

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
	public String getScenarioName() {
		return scenarioName;
	}
	public void setScenarioName(String scenarioName) {
		this.scenarioName = scenarioName;
	}
	public int getTotalRunsForTC() {
		return totalRunsForTC;
	}
	public void setTotalRunsForTC(int totalRunsForTC) {
		this.totalRunsForTC = totalRunsForTC;
	}
	public int getExecutedCount() {
		return executedCount;
	}
	public void setExecutedCount(int executedCount) {
		this.executedCount = executedCount;
	}
	public double getExecutedPercent() {
		return executedPercent;
	}
	public void setExecutedPercent(double executedPercent) {
		this.executedPercent = executedPercent;
	}
	public int getUnexecutedCount() {
		return unexecutedCount;
	}
	public void setUnexecutedCount(int unexecutedCount) {
		this.unexecutedCount = unexecutedCount;
	}
	public double getUnexecutedPercent() {
		return unexecutedPercent;
	}
	public void setUnexecutedPercent(double unexecutedPercent) {
		this.unexecutedPercent = unexecutedPercent;
	}
	public String getLastStatus() {
		return lastStatus;
	}
	public void setLastStatus(String lastStatus) {
		this.lastStatus = lastStatus;
	}
	public LocalDateTime getLastRunTime() {
		return lastRunTime;
	}
	public void setLastRunTime(LocalDateTime lastRunTime) {
		this.lastRunTime = lastRunTime;
	}
	public String getFormattedLastRunTime() {
		return formattedLastRunTime;
	}
	public void setFormattedLastRunTime(String formattedLastRunTime) {
		this.formattedLastRunTime = formattedLastRunTime;
	}
	public Map<String, Object> getStatusWisePercentageJson() {
		return statusWisePercentageJson;
	}
	public void setStatusWisePercentageJson(Map<String, Object> statusWisePercentageJson) {
		this.statusWisePercentageJson = statusWisePercentageJson;
	}
}
