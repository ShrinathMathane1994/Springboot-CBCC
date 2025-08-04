package com.qa.cbcc.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "test_case_run_history")
public class TestCaseRunHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

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

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public void setTestCase(TestCase testCase) {
        this.testCase = testCase;
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

    public String getOutputLog() {
        return outputLog;
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
}
