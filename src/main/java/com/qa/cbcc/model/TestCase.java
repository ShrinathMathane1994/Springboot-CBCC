package com.qa.cbcc.model;

import jakarta.persistence.*;

@Entity
@Table(name = "test_cases")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tcName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "feature_scenario_json", columnDefinition = "TEXT")
    private String featureScenarioJson;

    private String inputFile;
    private String outputFile;
    
	public Long getId() {
		return id;
	}
	public void setId(Long id) {
		this.id = id;
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
}

