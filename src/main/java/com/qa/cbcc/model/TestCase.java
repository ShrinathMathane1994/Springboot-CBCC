package com.qa.cbcc.model;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "test_cases")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idTC;

    private String tcName;

    private String description;

    private String featureScenarioJson;

    private String inputFile;

    private String outputFile;

    private LocalDateTime createdOn;

    private LocalDateTime modifiedOn;

    private LocalDateTime executionOn;

    private Boolean isActive;  // ✅ Needed for soft delete and query filtering
    
    private String country;
    private String region;
    private String pod;

    // New Getters & Setters
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


    // Getters and Setters
    public Long getIdTC() {
        return idTC;
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

    public LocalDateTime getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDateTime createdOn) {
        this.createdOn = createdOn;
    }

    public LocalDateTime getModifiedOn() {
        return modifiedOn;
    }

    public void setModifiedOn(LocalDateTime modifiedOn) {
        this.modifiedOn = modifiedOn;
    }

    public LocalDateTime getExecutionOn() {
        return executionOn;
    }

    public void setExecutionOn(LocalDateTime executionOn) {
        this.executionOn = executionOn;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
