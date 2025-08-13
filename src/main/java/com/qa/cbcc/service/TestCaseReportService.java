package com.qa.cbcc.service;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.qa.cbcc.dto.TestCaseRunHistoryDTO;

@Service
public class TestCaseReportService {

	public String generateHtmlReport(TestCaseRunHistoryDTO dto) {
		SimpleDateFormat sdf = new SimpleDateFormat("EEEE, dd MMMM yyyy hh:mm a");

		StringBuilder html = new StringBuilder();
		html.append("<html><head><meta charset='UTF-8'><style>")
				// Light & dark mode
				.append("body { font-family: Arial, sans-serif; margin: 20px; background: #f6f8fa; color:#222; }")
				.append("body.dark { background: #1e1e1e; color: #ddd; }")
				.append("h1 { text-align:center; font-size:28px; margin-bottom:6px; position: relative; }")
				.append("h1 .icon { font-size:30px; margin-right:8px; vertical-align:middle }")
				.append(".pdf-icon, .dark-toggle { position:absolute; top:0; cursor:pointer; font-size:20px; }")
				.append(".pdf-icon { right:30px; color:#e74c3c; } .dark-toggle { right:0; color:#4cafef; }")
				.append(".badge { padding:4px 8px; border-radius:6px; font-size:13px; color:white; display:inline-block; }")
				.append(".badge-pass { background:#28a745 } .badge-fail { background:#dc3545 } .badge-warn { background:#ffb703; color:#222 }")
				.append(".badge-xml-pass { background:#2ecc71 } .badge-xml-fail { background:#e74c3c }")
				.append(".section { border-radius:8px; background:white; box-shadow:0 2px 6px rgba(0,0,0,0.06); margin:16px 0; overflow:hidden; }")
				.append("body.dark .section { background:#2c2c2c; }")
				.append(".section-header { display:flex; justify-content:space-between; align-items:center; padding:10px 14px; background:#fbfbfb; cursor:pointer }")
				.append("body.dark .section-header { background:#333; }").append(".section-title { font-weight:600; }")
				.append(".chev { transition: transform .25s ease; font-size:14px; }")
				.append(".section-content { padding:14px; display:none; }")

				// Unified table style with fixed column widths
				.append(".scenario-table { width:100%; border-collapse:collapse; font-size:14px; table-layout:fixed; }")
				.append(".scenario-table th, .scenario-table td { border:1px solid #e6e6e6; padding:8px 10px; text-align:left; vertical-align:top; overflow-wrap:break-word; }")

				// Light mode table colors
				.append(".scenario-table th { background: #f2f4f7; color: #222; }")
				.append(".scenario-table td { background: #fff; color: #222; }")
				.append(".inner-table th { background: #f9f9f9; color: #222; }")
				.append(".inner-table td { background: #fff; color: #222; }")

				// Dark mode table colors
				.append("body.dark .scenario-table th { background: #333; color: #ddd; }")
				.append("body.dark .scenario-table td { background: #2c2c2c; color: #ddd; }")
				.append("body.dark .inner-table th { background: #444; color: #ddd; }")
				.append("body.dark .inner-table td { background: #2c2c2c; color: #ddd; }")

				// Alternating row colors
				.append(".scenario-table tr:nth-child(even) td { background: #fafafa; }")
				.append("body.dark .scenario-table tr:nth-child(even) td { background: #3a3a3a; }")
				.append(".inner-table tr:nth-child(even) td { background: #f4f4f4; }")
				.append("body.dark .inner-table tr:nth-child(even) td { background: #383838; }")

				// Column widths
				.append(".scenario-table colgroup col:nth-child(1) { width:35%; }")
				.append(".scenario-table colgroup col:nth-child(2) { width:15%; }")
				.append(".scenario-table colgroup col:nth-child(3) { width:15%; }")
				.append(".scenario-table colgroup col:nth-child(4) { width:25%; }")
				.append(".scenario-table colgroup col:nth-child(5) { width:10%; }")

				// Empty row styling
				.append(".empty-row td { background: #fafafa; }")
				.append("body.dark .empty-row td { background: #3a3a3a; }")

				.append(".row-pass { background:#eafaf0 } .row-fail { background:#fff2f2 }")
				.append(".inner-row { display:none; }")
				.append(".inner-table { width:100%; border-collapse:collapse; margin:0; table-layout:auto; }")
				.append(".inner-table th, .inner-table td { border:1px solid #ccc; padding:6px 8px; vertical-align:top; }")
				.append(".view-btn { background:#0d6efd; color:#fff; padding:3px 8px; border:none; border-radius:4px; cursor:pointer; font-size:12px; }")
				.append(".view-btn:hover { background:#0b5ed7; }")
				.append(".diff-badge { margin-left:6px; padding:3px 6px; font-size:11px; border-radius:10px; color:#fff; vertical-align:middle; }")
				.append(".diff-badge.fail { background:#dc3545; } .diff-badge.pass { background:#28a745; }")
				.append(".log-box { background:#f7f7f9; border:1px solid #e8e8e8; padding:10px; border-radius:6px; font-family:monospace; white-space:pre-wrap }")
				.append("body.dark .log-box { background:#2a2a2a; border:1px solid #444; }")
				.append(".toggle-btn { background:#0d6efd; color:#fff; padding:4px 6px; border-radius:6px; border:none; cursor:pointer; font-size:12px }")
				.append("</style>")

				// JS
				.append("<script>").append("function toggle(id){var el=document.getElementById(id);if(!el)return;")
				.append("var isHidden=(el.style.display==='none'||el.style.display==='');")
				.append("var tag=(el.tagName||'').toLowerCase();")
				.append("if(tag==='tr'||el.classList.contains('inner-row')){el.style.display=isHidden?'table-row':'none';}")
				.append("else{el.style.display=isHidden?'block':'none';}}")
				.append("function toggleDark(){document.body.classList.toggle('dark');}")
				.append("</script></head><body>");

		// Header
		html.append("<h1><span class='icon'>📋</span>Test Case Execution Report")
				.append("<span class='pdf-icon' onclick='expandAllAndPrint()'>🖨️</span>")
				.append("<span class='dark-toggle' onclick='toggleDark()'>🌙</span></h1>");

		String runStatus = dto.getRunStatus() == null ? "N/A" : dto.getRunStatus();
		String runBadgeClass = runStatus.toLowerCase().contains("fail") ? "badge-fail"
				: runStatus.toLowerCase().contains("part") ? "badge-warn" : "badge-pass";
		html.append("<p style='text-align:center;margin:6px 0'><b>TC Status:</b> <span class='badge ")
				.append(runBadgeClass).append("'>").append(runStatus).append("</span></p>");

		String xmlStatus = dto.getXmlDiffStatus() == null ? "N/A" : dto.getXmlDiffStatus();
		String xmlBadgeClass = "Matched".equalsIgnoreCase(xmlStatus) ? "badge-xml-pass" : "badge-xml-fail";
		html.append("<p style='text-align:center;margin:6px 0'><b>XML Difference:</b> <span class='badge ")
				.append(xmlBadgeClass).append("'>").append(xmlStatus).append("</span></p>");

		String runTimeFormatted = (dto.getRunTime() != null) ? sdf.format(java.sql.Timestamp.valueOf(dto.getRunTime()))
				: "N/A";
		html.append("<p style='text-align:center;margin:6px 0'><b>Executed On:</b> ").append(runTimeFormatted)
				.append("</p>");

		// Counts
		int passedCount = 0, failedCount = 0, unexecutedCount = 0;
		if (dto.getOutputLog() instanceof Map) {
			Map<String, Object> output = (Map<String, Object>) dto.getOutputLog();
			Object runSummaryObj = output.get("runSummary");
			if (runSummaryObj instanceof Map) {
				Map<String, Object> summary = (Map<String, Object>) runSummaryObj;
				passedCount = Integer.parseInt(summary.getOrDefault("totalPassedScenarios", 0).toString());
				failedCount = Integer.parseInt(summary.getOrDefault("totalFailedScenarios", 0).toString());
				unexecutedCount = Integer.parseInt(summary.getOrDefault("totalUnexecutedScenarios", 0).toString());
			}
		}

		html.append(buildSection("Passed Scenarios", passedCount, "passedScenarios", buildPassedScenarios(dto),
				passedCount == 0));
		html.append(buildSection("Failed Scenarios", failedCount, "failedScenarios", buildFailedScenarios(dto),
				failedCount == 0));
		html.append(buildSection("Unexecuted Scenarios", unexecutedCount, "unexecutedScenarios",
				buildUnexecutedScenarios(dto), unexecutedCount == 0));
		html.append(buildSection("XML Differences", -1, "xmlDiff", buildXmlDifferencesFixed(dto), false));
		html.append(buildSection("Raw Cucumber Logs", -1, "rawLogs", buildRawLogs(dto), false));
		html.append(buildSection("Raw Cucumber Summary", -1, "rawSummary", buildRawSummary(dto), false));

		html.append("</body></html>");
		return html.toString();
	}

	private String buildSection(String title, int count, String id, String content, boolean collapsed) {
		String badgeColorClass = "";
		if (count >= 0) {
			String lower = title.toLowerCase();
			if (lower.contains("fail"))
				badgeColorClass = "badge-fail";
			else if (lower.contains("pass"))
				badgeColorClass = "badge-pass";
			else if (lower.contains("unexecuted"))
				badgeColorClass = "badge-warn";
			else
				badgeColorClass = "badge-warn";
		}

		return "<div class='section'>" + "<div class='section-header' onclick=\"toggle('" + id + "')\">"
				+ "<div class='section-title'>" + title
				+ (count >= 0 ? " <span class='badge " + badgeColorClass + "'>" + count + "</span>" : "") + "</div>"
				+ "<div><span id='" + id + "-chev' class='chev' style='transform:"
				+ (collapsed ? "rotate(0deg)" : "rotate(180deg)") + "'>▼</span></div>" + "</div>" + "<div id='" + id
				+ "' class='section-content' style='display:" + (collapsed ? "none" : "block") + "'>" + content
				+ "</div>" + "</div>";
	}

	private String buildXmlDifferencesFixed(TestCaseRunHistoryDTO dto) {
		if (!(dto.getXmlParsedDifferencesJson() instanceof List<?>)) {
			return noScenarioAligned("No XML differences data available", 5);
		}

		List<?> diffsList = (List<?>) dto.getXmlParsedDifferencesJson();
		if (diffsList.isEmpty()) {
			return noScenarioAligned("No XML differences found", 5);
		}

		StringBuilder sb = new StringBuilder(
				"<table class='scenario-table'><colgroup><col/><col/><col/><col/><col/></colgroup>").append(
						"<tr><th>Scenario</th><th>Input File</th><th>Output File</th><th>Message</th><th>Differences</th></tr>");

		int index = 0;
		for (Object obj : diffsList) {
			if (!(obj instanceof Map))
				continue;
			Map<String, Object> diff = (Map<String, Object>) obj;

			String scenarioName = String.valueOf(diff.getOrDefault("scenarioName", "N/A"));
			String inputFile = filenameFromPath(String.valueOf(diff.getOrDefault("inputFile", "-")));
			String outputFile = filenameFromPath(String.valueOf(diff.getOrDefault("outputFile", "-")));
			String message = String.valueOf(diff.getOrDefault("message", "-"));

			List<Map<String, Object>> differences = diff.get("differences") instanceof List
					? (List<Map<String, Object>>) diff.get("differences")
					: java.util.Collections.emptyList();

			int diffCount = differences.size();

			sb.append("<tr><td>").append(escapeHtml(scenarioName)).append("</td>").append("<td>")
					.append(escapeHtml(inputFile)).append("</td>").append("<td>").append(escapeHtml(outputFile))
					.append("</td>").append("<td>").append(escapeHtml(message)).append("</td>")
					.append("<td style='white-space:nowrap'>");

			if (diffCount > 0) {
				String countBadgeClass = "diff-badge fail";
				sb.append("<button class='view-btn' onclick=\"toggle('diffInner").append(index)
						.append("')\">View</button>").append("<span class='").append(countBadgeClass).append("'>")
						.append(diffCount).append("</span>");
			} else {
				sb.append("-");
			}
			sb.append("</td></tr>");

			// Inner details table
			if (diffCount > 0) {
				sb.append("<tr id='diffInner").append(index).append("' class='inner-row'><td colspan='5'>")
						.append("<table class='inner-table'>")
						.append("<tr><th>XPath</th><th>Node</th><th>Type</th><th>Details</th></tr>");
				for (Map<String, Object> d : differences) {
					String details = formatMismatchDetails(d);
					sb.append("<tr><td>").append(escapeHtml(String.valueOf(d.getOrDefault("xpath", "-"))))
							.append("</td><td>").append(escapeHtml(String.valueOf(d.getOrDefault("node", "-"))))
							.append("</td><td>")
							.append(escapeHtml(String.valueOf(d.getOrDefault("differenceType", "-"))))
							.append("</td><td>").append(escapeHtml(details)).append("</td></tr>");
				}
				sb.append("</table></td></tr>");
			}
			index++;
		}
		sb.append("</table>");
		return sb.toString();
	}

	private String formatMismatchDetails(Map<String, Object> d) {
		String expectedVal = d.containsKey("expected") && d.get("expected") != null ? String.valueOf(d.get("expected"))
				: "-";
		String actualVal = d.containsKey("actual") && d.get("actual") != null ? String.valueOf(d.get("actual")) : "-";

		String type = String.valueOf(d.getOrDefault("differenceType", "-"));

		// If we have both expected and actual, always show them in the same format
		if (!"-".equals(expectedVal) || !"-".equals(actualVal)) {
			return "Expected: " + expectedVal + " | Actual: " + actualVal;
		}

		// Fallback to description if expected/actual not available
		if (d.containsKey("description") && d.get("description") != null) {
			return String.valueOf(d.get("description"));
		}

		return "-";
	}

	private String noScenarioAligned(String message, int colCount) {
		return "<table class='scenario-table'><colgroup>"
				+ "<col style='width:35%'><col style='width:15%'><col style='width:15%'><col style='width:25%'><col style='width:10%'>"
				+ "</colgroup><tbody><tr class='empty-row'>" + "<td colspan='" + colCount
				+ "' style='text-align:left; font-style:italic; padding:8px 10px;'>" + escapeHtml(message)
				+ "</td></tr></tbody></table>";
	}

	private String buildFailedScenarios(TestCaseRunHistoryDTO dto) {
		List<Map<String, Object>> failed = extractScenarioList(dto.getOutputLog(), "failedScenarioDetails");
		if (failed.isEmpty()) {
			return noScenarioAligned("No failed scenarios 🎉", 5);
		}

		StringBuilder sb = new StringBuilder(
				"<table class='scenario-table'><colgroup><col/><col/><col/><col/><col/></colgroup>").append(
						"<tr><th>Scenario</th><th>Type</th><th>Example Header</th><th>Example Values</th><th>Errors</th></tr>");

		int fi = 0;
		for (Map<String, Object> s : failed) {
			List<String> headers = safeList(s.get("exampleHeader"));
			List<String> values = safeList(s.get("exampleValues"));
			List<String> errors = safeList(s.get("errors"));
			String errHtml = errors.isEmpty() ? "-"
					: "<button class='toggle-btn' onclick=\"toggle('err" + fi + "')\">View</button>" + "<div id='err"
							+ fi + "' style='display:none;margin-top:8px'>" + String.join("<br>", errors) + "</div>";

			sb.append("<tr class='row-fail'><td>").append(s.getOrDefault("scenarioName", "N/A")).append("</td><td>")
					.append(s.getOrDefault("scenarioType", "N/A")).append("</td><td>")
					.append(headers.isEmpty() ? "-" : String.join(", ", headers)).append("</td><td>")
					.append(values.isEmpty() ? "-" : String.join(", ", values)).append("</td><td>").append(errHtml)
					.append("</td></tr>");
			fi++;
		}
		sb.append("</table>");
		return sb.toString();
	}

	private String buildPassedScenarios(TestCaseRunHistoryDTO dto) {
		List<Map<String, Object>> passed = extractScenarioList(dto.getOutputLog(), "passedScenarioDetails");
		if (passed.isEmpty()) {
			return noScenarioAligned("No passed scenarios recorded", 5);
		}

		StringBuilder sb = new StringBuilder("<table class='scenario-table'><colgroup>"
				+ "<col style='width:35%'><col style='width:15%'><col style='width:15%'><col style='width:25%'><col style='width:10%'>"
				+ "</colgroup>").append(
						"<tr><th>Scenario</th><th>Type</th><th>Example Header</th><th colspan='2'>Example Values</th></tr>");

		for (Map<String, Object> s : passed) {
			List<String> headers = safeList(s.get("exampleHeader"));
			List<String> values = safeList(s.get("exampleValues"));

			sb.append("<tr class='row-pass'><td>").append(s.getOrDefault("scenarioName", "Unnamed Scenario"))
					.append("</td><td>").append(s.getOrDefault("scenarioType", "Unknown")).append("</td><td>")
					.append(headers.isEmpty() ? "-" : String.join(", ", headers)).append("</td><td colspan='2'>")
					.append(values.isEmpty() ? "-" : String.join(", ", values)).append("</td></tr>");
		}
		sb.append("</table>");
		return sb.toString();
	}

	private String buildUnexecutedScenarios(TestCaseRunHistoryDTO dto) {
	    List<Map<String, Object>> unexec = extractScenarioList(dto.getOutputLog(), "unexecutedScenarioDetails");

	    // If not found, check for "unexecutedScenarioReasons"
	    if (unexec.isEmpty() && dto.getOutputLog() instanceof Map<?, ?> map) {
	        Object reasonsList = map.get("unexecutedScenarioReasons");
	        if (reasonsList instanceof List<?>) {
	            unexec = (List<Map<String, Object>>) reasonsList;
	        }
	    }

	    if (unexec.isEmpty()) {
	        return noScenarioAligned("No unexecuted scenarios", 2);
	    }

	    StringBuilder sb = new StringBuilder(
	        "<table class='scenario-table'><colgroup>" +
	        "<col style='width:60%'><col style='width:40%'>" +
	        "</colgroup>")
	        .append("<tr><th>Scenario</th><th>Errors</th></tr>");

	    int ui = 0;
	    for (Map<String, Object> s : unexec) {
	        String scenario = escapeHtml(String.valueOf(s.getOrDefault("scenarioName", "N/A")));
	        List<String> errors = safeList(s.get("errors"));

	        // Fallback to "reason" if errors list is empty
	        if (errors.isEmpty() && s.containsKey("reason")) {
	            errors = List.of(String.valueOf(s.get("reason")));
	        }

	        String errHtml = errors.isEmpty() ? "-"
	            : "<button class='toggle-btn' onclick=\"toggle('unexecErr" + ui + "')\">View</button>" +
	              "<div id='unexecErr" + ui + "' style='display:none;margin-top:8px'>" +
	              String.join("<br>", errors) + "</div>";

	        sb.append("<tr><td>").append(scenario).append("</td>")
	          .append("<td>").append(errHtml).append("</td></tr>");
	        ui++;
	    }

	    sb.append("</table>");
	    return sb.toString();
	}

	private String buildRawSummary(TestCaseRunHistoryDTO dto) {
		if (dto.getRawCucumberLogGrouped() == null || dto.getRawCucumberLogGrouped().getSummary() == null)
			return noScenarioAligned("No raw cucumber summary", 5);

		List<String> summary = dto.getRawCucumberLogGrouped().getSummary();

		int failedScenarios = 0, passedScenarios = 0, failedSteps = 0, passedSteps = 0;
		String execTime = "";
		for (String line : summary) {
			if (line.contains("Scenarios")) {
				failedScenarios = extractNumber(line, "failed");
				passedScenarios = extractNumber(line, "passed");
			}
			if (line.contains("Steps")) {
				failedSteps = extractNumber(line, "failed");
				passedSteps = extractNumber(line, "passed");
			}
			if (line.matches(".*\\d+\\.\\d+s.*")) {
				execTime = line.trim();
			}
		}

		String quickDetails = (passedScenarios + failedScenarios) + " scenarios (" + passedScenarios + " passed, "
				+ failedScenarios + " failed), " + (passedSteps + failedSteps) + " steps (" + passedSteps + " passed, "
				+ failedSteps + " failed)";

		StringBuilder sb = new StringBuilder();
		sb.append("<table class='scenario-table'><colgroup>").append("<col/><col/><col/><col/><col/>") // keep 5-column
																										// layout
				.append("</colgroup>").append("<tr>").append("<th colspan='5' style='text-align:left'>")
				.append("<span class='badge badge-fail'>❌ Failed: ").append(failedScenarios).append("</span> ")
				.append("<span class='badge badge-pass'>✅ Passed: ").append(passedScenarios).append("</span> ")
				.append("<span class='badge badge-warn'>⏱️ ").append(execTime).append("</span>").append("</th></tr>");

		sb.append("<tr>").append("<th>Scenarios</th>").append("<th>Steps</th>").append("<th>Execution Time</th>")
				.append("<th colspan='2'>Details</th>").append("</tr>");

		sb.append("<tr>").append("<td>✅ ").append(passedScenarios).append(" | ❌ ").append(failedScenarios)
				.append("</td>").append("<td>✅ ").append(passedSteps).append(" | ❌ ").append(failedSteps)
				.append("</td>").append("<td>").append(execTime).append("</td>").append("<td colspan='2'>")
				.append(escapeHtml(quickDetails)).append("</td>").append("</tr>");

		// Collapsible raw block inside full width
		sb.append("<tr><td colspan='5'>").append("<details><summary>View Raw Summary</summary>")
				.append("<div class='log-box'>").append(escapeHtml(String.join("\n", summary)))
				.append("</div></details>").append("</td></tr>");

		sb.append("</table>");
		return sb.toString();
	}

	private int extractNumber(String text, String keyword) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+" + keyword).matcher(text);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private String buildRawLogs(TestCaseRunHistoryDTO dto) {
		if (dto.getRawCucumberLogGrouped() == null || dto.getRawCucumberLogGrouped().getGroupedLogs() == null
				|| dto.getRawCucumberLogGrouped().getGroupedLogs().isEmpty()) {
			return noScenarioAligned("No raw cucumber logs", 5);
		}

		StringBuilder sb = new StringBuilder("<table class='scenario-table'><colgroup>"
				+ "<col style='width:35%'><col style='width:15%'><col style='width:15%'><col style='width:25%'><col style='width:10%'>"
				+ "</colgroup>").append("<tr><th colspan='3'>Scenario</th><th colspan='2'>Logs</th></tr>");

		int li = 0;
		for (var group : dto.getRawCucumberLogGrouped().getGroupedLogs()) {
			String scenarioName = escapeHtml(group.getScenario());
			sb.append("<tr><td colspan='3'>").append(scenarioName)
					.append("</td><td colspan='2' style='white-space:nowrap'>")
					.append("<button class='view-btn' onclick=\"toggle('log").append(li)
					.append("')\">View</button></td></tr>");
			sb.append("<tr id='log").append(li).append("' class='inner-row'><td colspan='5'>")
					.append("<div class='log-box'>").append(escapeHtml(String.join("\n", group.getLog())))
					.append("</div></td></tr>");
			li++;
		}
		sb.append("</table>");
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private List<String> safeList(Object obj) {
		return (obj instanceof List<?>) ? (List<String>) obj : java.util.Collections.emptyList();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> extractScenarioList(Object outputLog, String key) {
		if (outputLog instanceof Map<?, ?> map) {
			// Check top-level key
			Object list = map.get(key);
			if (list instanceof List<?>) {
				return (List<Map<String, Object>>) list;
			}
			// ✅ Check inside runSummary
			Object runSummary = map.get("runSummary");
			if (runSummary instanceof Map<?, ?> summary) {
				Object innerList = summary.get(key);
				if (innerList instanceof List<?>) {
					return (List<Map<String, Object>>) innerList;
				}
			}
		}
		return Collections.emptyList();
	}

	private String filenameFromPath(String p) {
		if (p == null)
			return "-";
		String s = p.replace('\\', '/');
		int idx = s.lastIndexOf('/');
		return idx >= 0 ? s.substring(idx + 1) : s;
	}

	private String escapeHtml(String s) {
		if (s == null)
			return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}