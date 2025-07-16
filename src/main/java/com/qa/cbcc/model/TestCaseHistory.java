package com.qa.cbcc.model;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "test_case_history")
public class TestCaseHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long testCaseId;

    private String tcName;

    private String description;

    private String featureScenarioJson;

    private String inputFile;

    private String outputFile;

    private LocalDateTime modifiedOn;

    private String changeType; // CREATE, UPDATE, DELETE

    // Constructors
    public TestCaseHistory() {}

    public TestCaseHistory(Long testCaseId, String tcName, String description,
                           String featureScenarioJson, String inputFile,
                           String outputFile, LocalDateTime modifiedOn, String changeType) {
        this.testCaseId = testCaseId;
        this.tcName = tcName;
        this.description = description;
        this.featureScenarioJson = featureScenarioJson;
        this.inputFile = inputFile;
        this.outputFile = outputFile;
        this.modifiedOn = modifiedOn;
        this.changeType = changeType;
    }

    // Getters and Setters
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

    public String getTcName() {
        return tcName;
    }

    public void setTcName(String tcName) {
        this.tcName = tcName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFeatureScenarioJson() {
        return featureScenarioJson;
    }

    public void setFeatureScenarioJson(String featureScenarioJson) {
        this.featureScenarioJson = featureScenarioJson;
    }

    public String getInputFile() {
        return inputFile;
    }

    public void setInputFile(String inputFile) {
        this.inputFile = inputFile;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile;
    }

    public LocalDateTime getModifiedOn() {
        return modifiedOn;
    }

    public void setModifiedOn(LocalDateTime modifiedOn) {
        this.modifiedOn = modifiedOn;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }
}
