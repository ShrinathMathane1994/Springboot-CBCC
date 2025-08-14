package com.qa.cbcc.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

public class TestCaseRunHistoryDTO {
    private Long id;
    private Long testCaseId;
    private LocalDateTime runTime;
    private String runStatus;
    private Object outputLog;
    private String executedScenarios;
    private String unexecutedScenarios;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> rawCucumberLog;
    private GroupedCucumberLogDTO rawCucumberLogGrouped;
 // ✅ NEW field
    private List<Map<String, Object>> xmlParsedDifferencesJson;
    private String xmlDiffStatus;
    private String inputXmlContent;
    private String outputXmlContent;
    
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTestCaseId() {
        return testCaseId;
    }

    public void setTestCaseId(Long testCaseId) {
        this.testCaseId = testCaseId;
    }

    public LocalDateTime getRunTime() {
        return runTime;
    }

    public void setRunTime(LocalDateTime runTime) {
        this.runTime = runTime;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public void setRunStatus(String runStatus) {
        this.runStatus = runStatus;
    }

    public Object getOutputLog() {
        return outputLog;
    }

    public void setOutputLog(Object outputLog) {
        this.outputLog = outputLog;
    }

    public String getExecutedScenarios() {
        return executedScenarios;
    }

    public void setExecutedScenarios(String executedScenarios) {
        this.executedScenarios = executedScenarios;
    }

    public String getUnexecutedScenarios() {
        return unexecutedScenarios;
    }

    public void setUnexecutedScenarios(String unexecutedScenarios) {
        this.unexecutedScenarios = unexecutedScenarios;
    }

    public List<String> getRawCucumberLog() {
        return rawCucumberLog;
    }

    public void setRawCucumberLog(List<String> rawCucumberLog) {
        this.rawCucumberLog = rawCucumberLog;
    }

    public GroupedCucumberLogDTO getRawCucumberLogGrouped() {
        return rawCucumberLogGrouped;
    }

    public void setRawCucumberLogGrouped(GroupedCucumberLogDTO rawCucumberLogGrouped) {
        this.rawCucumberLogGrouped = rawCucumberLogGrouped;
    }
    
    public List<Map<String, Object>> getXmlParsedDifferencesJson() {
        return xmlParsedDifferencesJson;
    }

    public void setXmlParsedDifferencesJson(List<Map<String, Object>> xmlParsedDifferencesJson) {
        this.xmlParsedDifferencesJson = xmlParsedDifferencesJson;
    }

    public String getXmlDiffStatus() {
        return xmlDiffStatus;
    }

    public void setXmlDiffStatus(String xmlDiffStatus) {
        this.xmlDiffStatus = xmlDiffStatus;
    }

	public String getInputXmlContent() {
		return inputXmlContent;
	}

	public void setInputXmlContent(String inputXmlContent) {
		this.inputXmlContent = inputXmlContent;
	}

	public String getOutputXmlContent() {
		return outputXmlContent;
	}

	public void setOutputXmlContent(String outputXmlContent) {
		this.outputXmlContent = outputXmlContent;
	}
}
