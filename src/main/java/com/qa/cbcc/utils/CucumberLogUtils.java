package com.qa.cbcc.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qa.cbcc.dto.GroupedCucumberLogDTO;
import com.qa.cbcc.dto.ScenarioLogGroupDTO;

public class CucumberLogUtils {

	public static GroupedCucumberLogDTO groupRawCucumberLog(List<String> rawLines) {
		List<ScenarioLogGroupDTO> groupedLogs = new ArrayList<>();
		List<String> summary = new ArrayList<>();

		ScenarioLogGroupDTO currentGroup = null;

		for (String line : rawLines) {
		    String trimmedLine = line.trim();

		    if (trimmedLine.isEmpty()) continue; // ❌ skip empty lines
		    if (trimmedLine.matches("^\\d+m\\d+\\.\\d+s$")) {
		        summary.add(formatDuration(trimmedLine));
		        continue;
		    }

		    if (isSummaryLine(trimmedLine)) {
		        summary.add(trimmedLine.replaceAll("\\t", "").trim());
		        continue;
		    }
		    
		    if (trimmedLine.startsWith("@")) {
		        // ❌ Ignore cucumber tags (environment labels)
		        continue;
		    }
		    
		    if (trimmedLine.startsWith("Undefined scenarios:") || trimmedLine.startsWith("file:///")) {
		        // Send undefined scenarios to rawSummary only
		        summary.add(trimmedLine); 
		        continue;
		    }


		    if (trimmedLine.startsWith("Scenario") || trimmedLine.startsWith("Scenario Outline")) {
		        currentGroup = new ScenarioLogGroupDTO();
		        currentGroup.setScenario(trimmedLine);
		        currentGroup.setLog(new ArrayList<>());
		        groupedLogs.add(currentGroup);
		        continue;
		    }

		    if (currentGroup != null) {
		        currentGroup.getLog().add(trimmedLine.replaceAll("\\t", "").trim());
		    }
		}
		GroupedCucumberLogDTO result = new GroupedCucumberLogDTO();
		result.setGroupedLogs(groupedLogs);
		result.setSummary(summary);

		return result;
	}

	private static String formatDuration(String timeStr) {
		return timeStr;
	}
	
	private static String formatDurationToShowInSec(String timeStr) {
		Pattern pattern = Pattern.compile("(\\d+)m(\\d+\\.\\d+)s");
		Matcher matcher = pattern.matcher(timeStr);
		if (matcher.matches()) {
			double minutes = Double.parseDouble(matcher.group(1));
			double seconds = Double.parseDouble(matcher.group(2));
			return String.format("%.3fs", (minutes * 60) + seconds);
		}
		return timeStr; // fallback
	}

	private static boolean isSummaryLine(String line) {
	    return line.matches("^(\\d+ )?Scenarios? \\(.*\\)$") ||     // e.g., "4 Scenarios (2 failed, 2 passed)"
	           line.matches("^(\\d+ )?Steps? \\(.*\\)$") ||         // e.g., "16 Steps (2 failed, 14 passed)"
	           line.startsWith("Failed scenarios:") ||             // header
	           line.startsWith("file://");                          // failed scenario line
	}

}
