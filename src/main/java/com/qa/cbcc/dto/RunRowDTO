package com.qa.cbcc.dto;

import java.time.LocalDateTime;

public class RunRowDTO {
    private Long runId;
    private Long testCaseId;
    private LocalDateTime runTime;
    private String formattedRunTime; // new
    private String runStatus;

    // executed/unexecuted for this run (0 or 1)
    private int executedCount;
    private double executedPercent;   // 100.00 or 0.00

    private int unexecutedCount;
    private double unexecutedPercent; // 100.00 or 0.00

    // percentage of this particular status across all runs for the test case (two decimals)
    private double statusPercent; // new

    private String xmlDiffStatus;  // optional keep

    // getters & setters

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public Long getTestCaseId() { return testCaseId; }
    public void setTestCaseId(Long testCaseId) { this.testCaseId = testCaseId; }

    public LocalDateTime getRunTime() { return runTime; }
    public void setRunTime(LocalDateTime runTime) { this.runTime = runTime; }

    public String getFormattedRunTime() { return formattedRunTime; }
    public void setFormattedRunTime(String formattedRunTime) { this.formattedRunTime = formattedRunTime; }

    public String getRunStatus() { return runStatus; }
    public void setRunStatus(String runStatus) { this.runStatus = runStatus; }

    public int getExecutedCount() { return executedCount; }
    public void setExecutedCount(int executedCount) { this.executedCount = executedCount; }

    public double getExecutedPercent() { return executedPercent; }
    public void setExecutedPercent(double executedPercent) { this.executedPercent = executedPercent; }

    public int getUnexecutedCount() { return unexecutedCount; }
    public void setUnexecutedCount(int unexecutedCount) { this.unexecutedCount = unexecutedCount; }

    public double getUnexecutedPercent() { return unexecutedPercent; }
    public void setUnexecutedPercent(double unexecutedPercent) { this.unexecutedPercent = unexecutedPercent; }

    public double getStatusPercent() { return statusPercent; }
    public void setStatusPercent(double statusPercent) { this.statusPercent = statusPercent; }

    public String getXmlDiffStatus() { return xmlDiffStatus; }
    public void setXmlDiffStatus(String xmlDiffStatus) { this.xmlDiffStatus = xmlDiffStatus; }
}
