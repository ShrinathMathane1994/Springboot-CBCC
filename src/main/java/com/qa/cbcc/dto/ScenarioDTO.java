package com.qa.cbcc.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class ScenarioDTO {
    private String feature;
    private String scenario;
    private String type;
    private String file;
    private String filePath;
    private List<String> tags;
    private List<ExampleDTO> examples = new ArrayList<>();
    
	public String getFeature() {
		return feature;
	}
	public void setFeature(String feature) {
		this.feature = feature;
	}
	public String getScenario() {
		return scenario;
	}
	public void setScenario(String scenario) {
		this.scenario = scenario;
	}
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public String getFile() {
		return file;
	}
	public void setFile(String file) {
		this.file = file;
	}
	public String getFilePath() {
		return filePath;
	}
	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}
	public List<String> getTags() {
		return tags;
	}
	public void setTags(List<String> tags) {
		this.tags = tags;
	}
	public List<ExampleDTO> getExamples() {
		return examples;
	}
	public void setExamples(List<ExampleDTO> examples) {
		this.examples = examples;
	}
}
