package com.qa.cbcc.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "test_case_run_history")
public class TestCaseRunHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_case_id")
    private Long testCaseId;

    @Column(name = "run_on")
    private LocalDateTime runTime;

    @Column(name = "run_status")
    private String runStatus;

    @Column(name = "output_log", columnDefinition = "TEXT")
    private String outputLog;

    @Column(name = "executed_scenarios", columnDefinition = "TEXT")
    private String executedScenarios;

    @Column(name = "unexecuted_scenarios", columnDefinition = "TEXT")
    private String unexecutedScenarios;

    @Column(name = "raw_log", columnDefinition = "TEXT")
    private String rawCucumberLog;

    @Column(name = "xmlparsed_differences_json", columnDefinition = "TEXT")
    private String xmlParsedDifferencesJson;

    @Column(name = "xml_diff_status")
    private String xmlDiffStatus;

    // --- Getters & Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTestCaseId() {
        return testCaseId;
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

	public void setTestCaseId(Long testCaseId) {
        this.testCaseId = testCaseId;
    }

    public void setOutputLog(String outputLog) {
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

    public String getRawCucumberLog() {
        return rawCucumberLog;
    }

    public void setRawCucumberLog(String rawCucumberLog) {
        this.rawCucumberLog = rawCucumberLog;
    }

	public String getXmlParsedDifferencesJson() {
		return xmlParsedDifferencesJson;
	}

	public void setXmlParsedDifferencesJson(String xmlParsedDifferencesJson) {
		this.xmlParsedDifferencesJson = xmlParsedDifferencesJson;
	}

	public String getXmlDiffStatus() {
		return xmlDiffStatus;
	}

	public void setXmlDiffStatus(String xmlDiffStatus) {
		this.xmlDiffStatus = xmlDiffStatus;
	}

	public String getOutputLog() {
		return outputLog;
	}
}
