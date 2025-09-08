package com.qa.cbcc.dto;

import java.util.List;
import java.util.Map;

/**
 * Lightweight DTO to return per-scenario / per-example rows.
 * By default inputXml / outputXml are null — include them only when client requests it.
 */
public class ScenarioExampleRunDTO {

    private Long id;
    private String featureFileName;
    private String featureFilePath;
    private String scenarioName;
    private String scenarioType; // "Scenario" / "Scenario Outline"
    private List<String> exampleHeader;            // parsed from exampleHeaderJson
    private Map<String, Object> exampleValues;     // parsed from exampleValuesJson
    private String status;
    private Long durationMs;

    // Differences & Errors
    private List<Map<String, Object>> differences; // parsed JSON differences
    private List<String> errors;                   // parsed JSON errors
    private String xmlDiffStatus;
    // Big fields — include only when requested
    private String inputXml;   // Expected
    private String outputXml;  // Actual

    // --- Getters / Setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFeatureFileName() { return featureFileName; }
    public void setFeatureFileName(String featureFileName) { this.featureFileName = featureFileName; }

    public String getFeatureFilePath() { return featureFilePath; }
    public void setFeatureFilePath(String featureFilePath) { this.featureFilePath = featureFilePath; }

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }

    public List<String> getExampleHeader() { return exampleHeader; }
    public void setExampleHeader(List<String> exampleHeader) { this.exampleHeader = exampleHeader; }

    public Map<String, Object> getExampleValues() { return exampleValues; }
    public void setExampleValues(Map<String, Object> exampleValues) { this.exampleValues = exampleValues; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public List<Map<String, Object>> getDifferences() { return differences; }
    public void setDifferences(List<Map<String, Object>> differences) { this.differences = differences; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
    
    public String getXmlDiffStatus() { return xmlDiffStatus; }
    public void setXmlDiffStatus(String xmlDiffStatus) { this.xmlDiffStatus = xmlDiffStatus; }

    public String getInputXml() { return inputXml; }
    public void setInputXml(String inputXml) { this.inputXml = inputXml; }

    public String getOutputXml() { return outputXml; }
    public void setOutputXml(String outputXml) { this.outputXml = outputXml; }
}
