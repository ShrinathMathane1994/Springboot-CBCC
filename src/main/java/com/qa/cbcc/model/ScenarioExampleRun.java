package com.qa.cbcc.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scenario_example_run")
public class ScenarioExampleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // header (execution)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_execution_id", nullable = false)
    private TestCaseRunHistory execution;

    // test case ref
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @Column(name = "feature_file_name", length = 512)
    private String featureFileName;

    @Column(name = "feature_file_path", columnDefinition = "TEXT")
    private String featureFilePath;

    @Column(name = "scenario_name", length = 2000)
    private String scenarioName;

    @Column(name = "scenario_type", length = 64)
    private String scenarioType; // "Scenario" / "Scenario Outline"

    @Column(name = "example_header_json", columnDefinition = "TEXT")
    private String exampleHeaderJson;

    @Column(name = "example_values_json", columnDefinition = "TEXT")
    private String exampleValuesJson;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "input_xml", columnDefinition = "TEXT")
    private String inputXml;

    @Column(name = "output_xml", columnDefinition = "TEXT")
    private String outputXml;

    @Column(name = "differences_json", columnDefinition = "TEXT")
    private String differencesJson;

    @Column(name = "errors_json", columnDefinition = "TEXT")
    private String errorsJson;
    
    @Column(name = "xml_diff_status")
    private String xmlDiffStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // --- Getters / Setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TestCaseRunHistory getExecution() { return execution; }
    public void setExecution(TestCaseRunHistory execution) { this.execution = execution; }

    public TestCase getTestCase() { return testCase; }
    public void setTestCase(TestCase testCase) { this.testCase = testCase; }

    public String getFeatureFileName() { return featureFileName; }
    public void setFeatureFileName(String featureFileName) { this.featureFileName = featureFileName; }

    public String getFeatureFilePath() { return featureFilePath; }
    public void setFeatureFilePath(String featureFilePath) { this.featureFilePath = featureFilePath; }

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }

    public String getExampleHeaderJson() { return exampleHeaderJson; }
    public void setExampleHeaderJson(String exampleHeaderJson) { this.exampleHeaderJson = exampleHeaderJson; }

    public String getExampleValuesJson() { return exampleValuesJson; }
    public void setExampleValuesJson(String exampleValuesJson) { this.exampleValuesJson = exampleValuesJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getInputXml() { return inputXml; }
    public void setInputXml(String inputXml) { this.inputXml = inputXml; }

    public String getOutputXml() { return outputXml; }
    public void setOutputXml(String outputXml) { this.outputXml = outputXml; }

    public String getDifferencesJson() { return differencesJson; }
    public void setDifferencesJson(String differencesJson) { this.differencesJson = differencesJson; }
    
    public String getXmlDiffStatus() {
        return xmlDiffStatus;
    }
    public void setXmlDiffStatus(String xmlDiffStatus) {
        this.xmlDiffStatus = xmlDiffStatus;
    }

    public String getErrorsJson() { return errorsJson; }
    public void setErrorsJson(String errorsJson) { this.errorsJson = errorsJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
