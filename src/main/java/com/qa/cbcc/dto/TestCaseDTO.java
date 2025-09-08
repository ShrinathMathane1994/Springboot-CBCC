package com.qa.cbcc.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.qa.cbcc.service.TestCaseRunService.ScenarioBlock;

public class TestCaseDTO {

	private Long tcId; // ✅ Added Test Case ID
	private String tcName;
	private String description;
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private List<FeatureScenario> featureScenarios;

	// ✅ Timestamp fields
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime executionOn;
	private LocalDateTime createdOn;
	private LocalDateTime modifiedOn;
	private String executionStatus; // <- New field for tracking last status

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

	// 1A) Add just below your imports or near top of TestCaseDTO
	public enum ScenarioType {
		SCENARIO("Scenario"), SCENARIO_OUTLINE("Scenario Outline");

		private final String label;

		ScenarioType(String label) {
			this.label = label;
		}

		@JsonValue
		public String getLabel() {
			return label;
		}

		@JsonCreator
		public static ScenarioType from(String value) {
			if (value == null)
				return null;
			String v = value.trim();
			for (ScenarioType t : values()) {
				if (t.label.equalsIgnoreCase(v))
					return t; // "Scenario", "Scenario Outline"
			}
			// allow enum names too as a fallback
			return ScenarioType.valueOf(v.toUpperCase().replace(' ', '_'));
		}
	}

	public static class ExampleTable {
		private List<String> headers; // e.g. ["country","amount","currency"]
		private List<Map<String, String>> rows; // e.g. [{"country":"UK","amount":"10","currency":"GBP"}]

		public List<String> getHeaders() {
			return headers;
		}

		public void setHeaders(List<String> headers) {
			this.headers = headers;
		}

		public List<Map<String, String>> getRows() {
			return rows;
		}

		public void setRows(List<Map<String, String>> rows) {
			this.rows = rows;
		}
	}

	public static class ScenarioSelection {
		private String scenarioName; // scenario title
		private ScenarioType type; // SCENARIO or SCENARIO_OUTLINE
		private ExampleTable selectedExamples; // only for OUTLINE

		public String getScenarioName() {
			return scenarioName;
		}

		public void setScenarioName(String scenarioName) {
			this.scenarioName = scenarioName;
		}

		public ScenarioType getType() {
			return type;
		}

		public void setType(ScenarioType type) {
			this.type = type;
		}

		public ExampleTable getSelectedExamples() {
			return selectedExamples;
		}

		public void setSelectedExamples(ExampleTable selectedExamples) {
			this.selectedExamples = selectedExamples;
		}
	}

	// Inner class for feature-scenario mapping
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class FeatureScenario {
		private String feature;
		private List<String> scenarios; // used for creation & execution
		private List<ScenarioBlock> scenarioBlocks;
		// ✅ Tags
		private List<String> featureTags; // Tags applied at Feature level
		private Map<String, List<String>> scenarioTagsByName; // scenario title -> @tags
		private Map<String, List<String>> exampleTagsByName; // scenario title -> @tags (for its Examples)
		private List<String> backgroundBlock;
		private List<ScenarioSelection> selections;

		public String getFeature() {
			return feature;
		}

		public void setFeature(String feature) {
			this.feature = feature;
		}

		public List<String> getScenarios() {
			return scenarios;
		}

		public void setScenarios(List<String> scenarios) {
			this.scenarios = scenarios;
		}

		public List<ScenarioBlock> getScenarioBlocks() {
			return scenarioBlocks;
		}

		public void setScenarioBlocks(List<ScenarioBlock> scenarioBlocks) {
			this.scenarioBlocks = scenarioBlocks;
		}

		public List<String> getFeatureTags() {
			return featureTags;
		}

		public void setFeatureTags(List<String> featureTags) {
			this.featureTags = featureTags;
		}

		// getters/setters
		public Map<String, List<String>> getScenarioTagsByName() {
			return scenarioTagsByName;
		}

		public void setScenarioTagsByName(Map<String, List<String>> m) {
			this.scenarioTagsByName = m;
		}

		public Map<String, List<String>> getExampleTagsByName() {
			return exampleTagsByName;
		}

		public void setExampleTagsByName(Map<String, List<String>> m) {
			this.exampleTagsByName = m;
		}

		public List<String> getBackgroundBlock() {
			return backgroundBlock;
		}

		public void setBackgroundBlock(List<String> backgroundBlock) {
			this.backgroundBlock = backgroundBlock;
		}

		public List<ScenarioSelection> getSelections() {
			return selections;
		}

		public void setSelections(List<ScenarioSelection> selections) {
			this.selections = selections;
		}

	}

	// Getters and setters

	public Long getTcId() {
		return tcId;
	}

	public void setTcId(Long tcId) {
		this.tcId = tcId;
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

	public List<FeatureScenario> getFeatureScenarios() {
		return featureScenarios;
	}

	public void setFeatureScenarios(List<FeatureScenario> featureScenarios) {
		this.featureScenarios = featureScenarios;
	}

	public LocalDateTime getExecutionOn() {
		return executionOn;
	}

	public void setExecutionOn(LocalDateTime executionOn) {
		this.executionOn = executionOn;
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

	public String getExecutionStatus() {
		return executionStatus;
	}

	public void setExecutionStatus(String executionStatus) {
		this.executionStatus = executionStatus;
	}
}
