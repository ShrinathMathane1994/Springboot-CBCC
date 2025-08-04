package com.qa.cbcc.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "test_case_history")
public class TestCaseHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    private String tcName;
    private String description;
    private String featureScenarioJson;
    private String inputFile;
    private String outputFile;
    private LocalDateTime modifiedOn;
    private String changeType;

    private String country;
    private String region;
    private String pod;

    public TestCaseHistory() {}

    public TestCaseHistory(TestCase testCase, String tcName, String description,
                           String featureScenarioJson, String inputFile,
                           String outputFile, LocalDateTime modifiedOn,
                           String changeType, String country, String region, String pod) {
        this.testCase = testCase;
        this.tcName = tcName;
        this.description = description;
        this.featureScenarioJson = featureScenarioJson;
        this.inputFile = inputFile;
        this.outputFile = outputFile;
        this.modifiedOn = modifiedOn;
        this.changeType = changeType;
        this.country = country;
        this.region = region;
        this.pod = pod;
    }

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

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getPod() {
        return pod;
    }

    public void setPod(String pod) {
        this.pod = pod;
    }
} 
