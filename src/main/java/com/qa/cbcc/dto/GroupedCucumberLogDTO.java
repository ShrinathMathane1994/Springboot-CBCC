package com.qa.cbcc.dto;

import java.util.List;

public class GroupedCucumberLogDTO {
	private List<ScenarioLogGroupDTO> groupedLogs;
	private List<String> summary;

	public List<ScenarioLogGroupDTO> getGroupedLogs() {
		return groupedLogs;
	}

	public void setGroupedLogs(List<ScenarioLogGroupDTO> groupedLogs) {
		this.groupedLogs = groupedLogs;
	}

	public List<String> getSummary() {
		return summary;
	}

	public void setSummary(List<String> summary) {
		this.summary = summary;
	}
}
