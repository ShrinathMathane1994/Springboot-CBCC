package com.qa.cbcc.dto;

import java.time.LocalDateTime;

public class TestCaseResponseDTO {
    private Long id;
    private String tcName;
    private String description;
    private String featureScenarioJson;
    private String inputFile;
    private String outputFile;
    private LocalDateTime createdOn;
    private LocalDateTime modifiedOn;
    private Boolean isActive;
    private String country;
    private String region;
    private String pod;


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
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTcName() { return tcName; }
    public void setTcName(String tcName) { this.tcName = tcName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getFeatureScenarioJson() { return featureScenarioJson; }
    public void setFeatureScenarioJson(String featureScenarioJson) { this.featureScenarioJson = featureScenarioJson; }

    public String getInputFile() { return inputFile; }
    public void setInputFile(String inputFile) { this.inputFile = inputFile; }

    public String getOutputFile() { return outputFile; }
    public void setOutputFile(String outputFile) { this.outputFile = outputFile; }

    public LocalDateTime getCreatedOn() { return createdOn; }
    public void setCreatedOn(LocalDateTime createdOn) { this.createdOn = createdOn; }

    public LocalDateTime getModifiedOn() { return modifiedOn; }
    public void setModifiedOn(LocalDateTime modifiedOn) { this.modifiedOn = modifiedOn; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
