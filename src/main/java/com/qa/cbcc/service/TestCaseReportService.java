package com.qa.cbcc.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Comparison;
import org.xmlunit.diff.ComparisonType;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.cbcc.dto.ScenarioLogGroupDTO;
import com.qa.cbcc.dto.TestCaseRunHistoryDTO;

@Service
public class TestCaseReportService {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseReportService.class);
	// Central config for tags to skip
	private static final Set<String> SKIPPED_TAGS = new LinkedHashSet<>(
			Arrays.asList("CreDtTm", "TmStmpDetls", "EndToEndId"));
	private static final String SKIPPED_TAGS_REGEX = buildSkippedTagsRegex(SKIPPED_TAGS);

	private static String buildSkippedTagsRegex(Set<String> tags) {
		String joined = String.join("|", tags);
		return ".*<(?:/?(?:" + joined + "))(?:\\s[^>]*)?>.*";
	}

	// Configurable placeholders
	private static final Set<String> PLACEHOLDERS = new LinkedHashSet<>(Arrays.asList("UETR"));
	// Add any names
	private static final Pattern PLACEHOLDER_TOKEN = Pattern
			.compile("\\$\\{(" + String.join("|", PLACEHOLDERS) + ")\\}");

	private static final Set<String> SKIPPED_PLACEHOLDERS = new LinkedHashSet<>(Arrays.asList("UETR"));

	private static String buildSkippedPlaceholderRegex(Set<String> keys) {
		if (keys.isEmpty())
			return "(?!x)x";
		return "\\$\\{(?:" + String.join("|", keys) + ")\\}";
	}

	private static final String SKIPPED_PLACEHOLDER_REGEX = buildSkippedPlaceholderRegex(SKIPPED_PLACEHOLDERS);
//	private static final Pattern PLACEHOLDER_TOKEN = Pattern.compile(SKIPPED_PLACEHOLDER_REGEX);
	private static final Pattern PLACEHOLDER_ONLY_LINE = Pattern.compile("^\\s*" + SKIPPED_PLACEHOLDER_REGEX + "\\s*$");

	private static boolean isPlaceholderOnlyLine(String s) {
		return s != null && PLACEHOLDER_ONLY_LINE.matcher(s).matches();
	}

	private static String maskSkippedPlaceholdersInCData(String text) {
		if (text == null)
			return "";

		Matcher m = PLACEHOLDER_TOKEN.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			// Allow placeholder to consume anything, including newlines
			m.appendReplacement(sb, "[\\\\s\\\\S]*?");
		}
		m.appendTail(sb);
		return sb.toString();
	}

	public static boolean linesEqualWithPlaceholders(String expected, String actual) {
		if (expected == null)
			expected = "";
		if (actual == null)
			actual = "";

		expected = expected.replaceAll("\\s+", " ").trim();
		actual = actual.replaceAll("\\s+", " ").trim();

		Matcher m = PLACEHOLDER_TOKEN.matcher(expected);
		StringBuilder regex = new StringBuilder();
		int last = 0;
		while (m.find()) {
			regex.append(Pattern.quote(expected.substring(last, m.start())));
			// All placeholders use the same pattern here
			regex.append("[^<>{}\\s]+"); // or use ".*?" for any string
//			regex.append("(?:[A-Z0-9]{32}|[0-9a-fA-F\\-]{36})");
			last = m.end();
		}
		regex.append(Pattern.quote(expected.substring(last)));

		String expRegex = "^" + regex + "$";
		return Pattern.compile(expRegex, Pattern.DOTALL).matcher(actual).matches();
	}

//	public static boolean linesEqualWithPlaceholders(String expected, String actual) {
//	    Matcher m = PLACEHOLDER_TOKEN.matcher(expected);
//
//	    if (!m.find()) {
//	        // no placeholder → strict equality
//	        return expected.equals(actual);
//	    }
//
//	    StringBuilder regex = new StringBuilder();
//	    int last = 0;
//	    m.reset();
//
//	    while (m.find()) {
//	        // quote everything before placeholder (preserve spaces & text strictly)
//	        regex.append(Pattern.quote(expected.substring(last, m.start())));
//
//	        // placeholder: accept either UUID (36 chars with dashes) or 32-char UETR
//	        regex.append("(?:[A-Z0-9]{32}|[0-9a-fA-F\\-]{36})");
//
//	        last = m.end();
//	    }
//
//	    // append the rest after last placeholder
//	    regex.append(Pattern.quote(expected.substring(last)));
//
//	    String expRegex = "^" + regex + "$";
////	    logger.info("Final Built Regex = " + expRegex);
//
//	    return Pattern.compile(expRegex, Pattern.DOTALL).matcher(actual).matches();
//	}

	// Helper: turns spaces into \s+
	private static void appendWithWhitespaceNormalized(StringBuilder regex, String literal) {
		for (int i = 0; i < literal.length();) {
			if (Character.isWhitespace(literal.charAt(i))) {
				// collapse consecutive whitespace into one regex
				while (i < literal.length() && Character.isWhitespace(literal.charAt(i))) {
					i++;
				}
				regex.append("\\s+");
			} else {
				int j = i;
				while (j < literal.length() && !Character.isWhitespace(literal.charAt(j))) {
					j++;
				}
				regex.append(Pattern.quote(literal.substring(i, j)));
				i = j;
			}
		}
	}

	private Pair<String, String> alignExpectedWithSkippedTags(String expectedXml, String actualXml) {
		if (expectedXml == null)
			expectedXml = "";
		if (actualXml == null)
			actualXml = "";

		// keep trailing empty lines if any
		String[] expectedLines = expectedXml.split("\\r?\\n", -1);
		String[] actualLines = actualXml.split("\\r?\\n", -1);

		List<String> alignedExpected = new ArrayList<>();
		List<String> alignedActual = new ArrayList<>();

		int ei = 0; // cursor over expected

		for (int ai = 0; ai < actualLines.length; ai++) {
			String actLine = actualLines[ai];
			String expLine = (ei < expectedLines.length) ? expectedLines[ei] : "";

			String ta = actLine.trim();
			String te = expLine.trim();

			// --- Case 0: skip-tag handling based on Actual line ---
			Optional<String> skippedTag = SKIPPED_TAGS.stream()
					.filter(tag -> ta.startsWith("<" + tag) || ta.startsWith("</" + tag)).findFirst();

			if (skippedTag.isPresent()) {
				String indent = leadingWhitespace(actLine);
				alignedExpected.add(indent + "<!-- skipped " + skippedTag.get() + " -->");
				alignedActual.add(actLine);
				continue; // do NOT consume expected
			}

			// --- placeholder-only expected line handling with look-ahead (zero-width
			// aware) ---
			if (ei < expectedLines.length && isPlaceholderOnlyLine(expLine)) {

				// Case A: zero-width match — current actual already matches the *next* expected
				// line.
				if (ei + 1 < expectedLines.length && linesEqualWithPlaceholders(expectedLines[ei + 1], actLine)) {
					// Consume expected placeholder ONLY; keep the same actual line for the next
					// iteration.
					alignedExpected.add(expLine);
					alignedActual.add(leadingWhitespace(expLine)); // empty cell keeps columns aligned
					ei++;
					ai--; // reprocess the same actual line next loop
					continue;
				}

				// Case B: placeholder consumes this actual line (value line)
				alignedExpected.add(expLine);
				alignedActual.add(actLine);
				ei++;
				continue;
			}

			// --- Case 1: lines are equal (with placeholders) -> consume both
			if (ei < expectedLines.length && linesEqualWithPlaceholders(expLine, actLine)) {
				alignedExpected.add(expLine);
				alignedActual.add(actLine);
				ei++;
				continue;
			}

			// --- Case 2: Actual has extra content BEFORE Expected's closing tag
			if (ei < expectedLines.length && isClosingTag(te) && !ta.isEmpty()) {
				String indent = leadingWhitespace(actLine);
				alignedExpected.add(indent + "<!-- missing in expected -->");
				alignedActual.add(actLine);
				continue;
			}

			// --- Case 3: Actual has extra content after Expected ended
			if (ei >= expectedLines.length && !ta.isEmpty()) {
				String indent = leadingWhitespace(actLine);
				alignedExpected.add(indent + "<!-- missing in expected -->");
				alignedActual.add(actLine);
				continue;
			}

			// --- Case 4: Extra in Expected (Actual is empty here)
			if (!te.isEmpty() && ta.isEmpty()) {
				String indent = leadingWhitespace(expLine);
				alignedExpected.add(expLine);
				alignedActual.add(indent + "<!-- missing in actual -->");
				ei++;
				continue;
			}

			// --- Case 5: Fallback mismatch
			if (ei < expectedLines.length) {
				alignedExpected.add(expLine);
				alignedActual.add(actLine);
				ei++;
			} else {
				String indent = leadingWhitespace(actLine);
				alignedExpected.add(indent + "<!-- missing in expected -->");
				alignedActual.add(actLine);
			}
		}

		// Any remaining Expected lines after Actual ends → mark missing in actual
		while (ei < expectedLines.length) {
			String expLine = expectedLines[ei++];
			String indent = leadingWhitespace(expLine);
			alignedExpected.add(expLine);
			alignedActual.add(indent + "<!-- missing in actual -->");
		}

		return Pair.of(String.join("\n", alignedExpected), String.join("\n", alignedActual));
	}

	private String buildXmlSideBySide(TestCaseRunHistoryDTO dto) {
		String original = dto.getInputXmlContent() == null ? "" : dto.getInputXmlContent();
		String modified = dto.getOutputXmlContent() == null ? "" : dto.getOutputXmlContent();

		// If both sides are missing
		if (original.isEmpty() && modified.isEmpty()) {
			return noScenarioAligned("No XML content available — both Expected and Actual files are empty or missing.",
					5);
		}
		// If only Expected is missing
		if (original.isEmpty()) {
			return noScenarioAligned("Side-by-side view skipped — Expected XML is missing.", 5);
		}
		// If only Actual is missing
		if (modified.isEmpty()) {
			return noScenarioAligned("Side-by-side view skipped — Actual XML is missing.", 5);
		}

		// normalize CDATA for both
		original = normalizeLargeCDataForDiff(original);
		modified = normalizeLargeCDataForDiff(modified);

		// align both Expected & Actual
		Pair<String, String> aligned = alignExpectedWithSkippedTags(original, modified);
		String[] expectedLines = aligned.getLeft().split("\\r?\\n");
		String[] actualLines = aligned.getRight().split("\\r?\\n");

		int maxLines = Math.max(expectedLines.length, actualLines.length);

		StringBuilder sb = new StringBuilder();
		sb.append("<div class='xml-ssb-wrap'>");

		for (int i = 0; i < maxLines; i++) {
			sb.append("<div class='xml-row'>");

			String leftLine = i < expectedLines.length ? expectedLines[i] : "";
			String rightLine = i < actualLines.length ? actualLines[i] : "";

			boolean leftSkipped = leftLine.trim().startsWith("<!-- skipped");
			boolean rightSkipped = rightLine.trim().matches(SKIPPED_TAGS_REGEX)
					|| rightLine.trim().startsWith("<!-- skipped");

			// ---- Line number (Expected) ----
			sb.append("<div class='").append(leftSkipped ? "line-num skipped" : "line-num").append("'>").append(i + 1)
					.append("</div>");

			// ---- Expected column ----
			if (leftSkipped) {
				sb.append("<div class='expected skipped'>").append(escapeHtml(leftLine)).append("</div>");
			} else if (!linesEqualWithPlaceholders(leftLine, rightLine)) {
				sb.append("<div class='expected'>").append(highlightExpected(leftLine, rightLine)).append("</div>");
			} else {
				sb.append("<div class='expected'>").append(escapeHtml(leftLine)).append("</div>");
			}

			// ---- Line number (Actual) ----
			sb.append("<div class='").append(rightSkipped ? "line-num skipped" : "line-num").append("'>").append(i + 1)
					.append("</div>");

			// ---- Actual column ----
			if (rightSkipped) {
				sb.append("<div class='actual skipped'>").append(escapeHtml(rightLine)).append("</div>");
			} else if (!linesEqualWithPlaceholders(leftLine, rightLine)) {
				sb.append("<div class='actual'>").append(highlightActual(leftLine, rightLine)).append("</div>");
			} else {
				sb.append("<div class='actual'>").append(escapeHtml(rightLine)).append("</div>");
			}

			sb.append("</div>");
		}

		sb.append("</div>");
		return sb.toString();
	}

	public String generateHtmlReport(TestCaseRunHistoryDTO dto) {
		SimpleDateFormat sdf = new SimpleDateFormat("EEEE, dd MMMM yyyy hh:mm a");

		StringBuilder html = new StringBuilder();
		html.append("<html><head><meta charset='UTF-8'><style>")
				// container grid
				.append(".xml-ssb-wrap {").append("display: grid;").append("grid-template-columns: 40px 1fr 40px 1fr;")// actual
				.append("border:1px solid #ccc;").append("border-right:none;") // avoid double borders
				.append("}")

				// expected + actual + line numbers cells
				.append(".xml-ssb-wrap .expected, .xml-ssb-wrap .actual {").append("white-space: pre-wrap;")
				.append("word-break: break-word;") // ✅ don't break words mid-token
				.append("overflow-wrap: anywhere;")// ✅ allow wrap only at safe points
				.append("max-width: 100%;").append("padding:2px 6px;").append("border-bottom:1px solid #eee;")
				.append("border-right:1px solid #ddd;").append("font-family: monospace;").append("}")

				// line numbers styling (light mode)
				.append(".xml-ssb-wrap .line-num {").append("text-align: right;").append("padding:2px 6px;")
				.append("color:#888;").append("background:#f8f8f8;").append("border-right:1px solid #ddd;")
				.append("user-select:none;").append("}")
				.append(".xml-ssb-wrap .expected.skipped, .xml-ssb-wrap .actual.skipped, .xml-ssb-wrap .line-num.skipped {")
				.append("color: gray !important;").append("font-style: italic;")
				.append("background-color: #f2f4f7 !important;").append("}")

				// skipped tags styling
				.append(".xml-ssb-wrap .expected.skipped, .xml-ssb-wrap .actual.skipped {")
				.append("color: gray !important;").append("font-style: italic;")
				.append("background-color: #f2f4f7 !important;").append("}")

				// row structure
				.append(".xml-row {display: contents;}")

				// remove border on last column
				.append(".xml-row>div:last-child {border-right:none;}")

				// base backgrounds
				.append(".expected {background-color:#f9f9f9;}").append(".actual {background-color:#fdfdfd;}")

				// diff highlights
				.append(".diff-green {background-color:#e6ffed;color:#22863a;}")
				.append(".diff-red   {background-color:#ffecec;color:#cb2431;}")

				// dark mode support
				.append("body.dark .xml-ssb-wrap .line-num {").append("color:#aaa;") // lighter gray for numbers
				.append("background:#2a2a2a;") // dark background
				.append("border-right:1px solid #444;").append("}")
				.append("body.dark .xml-ssb-wrap{border-color:#555;}")
				.append("body.dark .xml-row>div{border-bottom:1px solid #444;border-right:1px solid #444;}")
				.append("body.dark .expected{background-color:#242424;color:#e0e0e0;}")
				.append("body.dark .actual{background-color:#242424;color:#e0e0e0;}")
				.append("body.dark .diff-green{background-color:#144d14;color:#b6fcb6;}")
				.append("body.dark .diff-red{background-color:#5a1a1a;color:#ffb3b3;}")
				.append("body.dark .expected.skipped, body.dark .actual.skipped {").append("color:#aaaaaa !important;")
				.append("background-color:#3a3a3a !important;").append("}")
				.append("body.dark .xml-ssb-wrap .expected.skipped, body.dark .xml-ssb-wrap .actual.skipped, body.dark .xml-ssb-wrap .line-num.skipped {")
				.append("color:#aaaaaa !important;").append("background-color:#3a3a3a !important;").append("}")

				.append(".xml-pane{border:1px solid #e6e6e6;border-radius:8px;overflow:auto;max-height:520px;background:#fff;}")
				.append("body.dark .xml-pane{background:#2c2c2c;border-color:#444;}")
				.append(".xml-header{padding:6px 10px;font-weight:600;background:#f2f4f7;border-bottom:1px solid #e6e6e6;position:sticky;top:0;}")
				.append("body.dark .xml-header{background:#333;border-color:#444;}")
				.append(".xml-code{font-family:ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, \"Liberation Mono\", monospace;font-size:12px;line-height:1.45;padding:10px;white-space:pre;}")
				.append(".xml-line{display:flex;}")
				.append(".ln{min-width:48px;text-align:right;padding-right:8px;opacity:.6;user-select:none;}")
				.append(".lc{flex:1;overflow:hidden;}")

				/* FULL-line backgrounds */
				.append(".diff-removed-line{background:#ffdddd;}").append(".diff-added-line{background:#ddffdd;}")
				.append(".same-line{background:transparent;}")

				/* Inline word-level highlights */
				.append(".diff-removed{background:#ffaaaa;border-radius:2px;padding:0 1px;}")
				.append(".diff-added{background:#aaffaa;border-radius:2px;padding:0 1px;}")

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
				.append(".scenario-table { width:100%; border-collapse:collapse; font-size:14px; table-layout:fixed; }.tbl thead th:first-child{width:40%;}.tbl thead th:nth-child(2){width:30%;}.tbl thead th:nth-child(3){width:30%;}")
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
				.append("function toggleDark(){\r\n" + "    document.body.classList.toggle('dark');\r\n"
						+ "    if (window.monaco) {\r\n"
						+ "        monaco.editor.setTheme(document.body.classList.contains('dark') ? 'vs-dark' : 'vs');\r\n"
						+ "    }\r\n" + "}")
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

		html.append(buildSection("Expected Vs Actual", -1, "xmlDomDiff", buildXmlSideBySide(dto), false));
		html.append(buildSection("Semantic XML Differences", -1, "xmlSemantic", buildSemanticXmlDiff(dto), true));

		html.append(buildSection("Raw Cucumber Logs", -1, "rawLogs", buildRawLogs(dto), false));
		html.append(buildSection("Raw Cucumber Summary", -1, "rawSummary", buildRawSummary(dto), false));

		html.append("</body></html>");
		return html.toString();
	}

	private String preprocessXml(String xml) {
		if (xml == null)
			return "";
		// Remove the tag plus any surrounding whitespace/newlines
		return xml.replaceAll("(?m)^[ \\t]*<(CreDtTm|TmStmpDetls|EndToEndId)[^>]*>.*?</\\1>[ \\t]*\\r?\\n?", "");
	}

	private static String leadingWhitespace(String s) {
		int i = 0;
		while (i < s.length() && Character.isWhitespace(s.charAt(i)))
			i++;
		return s.substring(0, i);
	}

	private static boolean isClosingTag(String trimmed) {
		return trimmed.startsWith("</");
	}

	private static String escapeHtml(String s) {
		if (s == null)
			return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private String highlightExpected(String left, String right) {
		if (left.equals(right))
			return escapeHtml(left);

		DiffMatchPatch dmp = new DiffMatchPatch();
		LinkedList<DiffMatchPatch.Diff> diffs = dmp.diffMain(left, right);
		dmp.diffCleanupSemantic(diffs);

		StringBuilder highlighted = new StringBuilder();
		for (DiffMatchPatch.Diff diff : diffs) {
			switch (diff.operation) {
			case EQUAL:
				highlighted.append(escapeHtml(diff.text));
				break;
			case DELETE: // something present in expected but missing in actual
				highlighted.append("<span class='diff-green'>").append(escapeHtml(diff.text)).append("</span>");
				break;
			case INSERT: // ignore inserts (they belong to actual)
				break;
			}
		}
		return highlighted.toString();
	}

	private String highlightActual(String left, String right) {
		if (left.equals(right))
			return escapeHtml(right);

		DiffMatchPatch dmp = new DiffMatchPatch();
		LinkedList<DiffMatchPatch.Diff> diffs = dmp.diffMain(left, right);
		dmp.diffCleanupSemantic(diffs);

		StringBuilder highlighted = new StringBuilder();
		for (DiffMatchPatch.Diff diff : diffs) {
			switch (diff.operation) {
			case EQUAL:
				highlighted.append(escapeHtml(diff.text));
				break;
			case INSERT: // something new in actual
				highlighted.append("<span class='diff-red'>").append(escapeHtml(diff.text)).append("</span>");
				break;
			case DELETE: // ignore deletes (they belong to expected)
				break;
			}
		}
		return highlighted.toString();
	}

	private String normalizePayloadBlock(String xml) {
		if (xml == null || xml.isEmpty()) {
			return xml;
		}

		final int WRAP = 120;
		Pattern p = Pattern.compile("(?s)(<([A-Za-z0-9:_-]+)>\\s*<!\\[CDATA\\[)(.*?)(\\]\\]>\\s*</\\2>)");
		Matcher m = p.matcher(xml);
		StringBuffer out = new StringBuffer();

		while (m.find()) {
			String opening = m.group(1); // <Payload><![CDATA[
			String content = m.group(3).trim(); // inside CDATA
			String closing = m.group(4); // ]]></Payload>

			// Detect indentation from the opening tag line
			String indent = getIndent(opening);

			// Collapse multiple spaces but keep single spaces/newlines
			content = content.replaceAll("[ \\t]{2,}", " ");

			StringBuilder wrapped = new StringBuilder();
			String[] tokens = content.split("(?<=\\s)|(?=\\s)"); // keep spaces as tokens
			int lineLen = 0;

			for (String token : tokens) {
				if (token.equals("\n")) { // preserve explicit line breaks
					wrapped.append("\n").append(indent).append("    "); // indent after newline
					lineLen = 0;
					continue;
				}

				int tokenLen = token.length();
				if (lineLen + tokenLen > WRAP && !token.trim().isEmpty()) {
					// wrap before adding token
					wrapped.append("\n").append(indent).append("    ");
					lineLen = 0;
				}

				wrapped.append(token);
				lineLen += tokenLen;
			}

			// Build replacement with indentation applied
			String replacement = opening + "\n" + indent + "    " + wrapped.toString().trim() + "\n" + indent + closing;

			m.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(out);
		return out.toString();
	}

	private String getIndent(String line) {
		int idx = 0;
		while (idx < line.length() && Character.isWhitespace(line.charAt(idx))) {
			idx++;
		}
		return line.substring(0, idx);
	}

	private String collapsePayloadCData(String xml) {
		Pattern pattern = Pattern.compile("(?s)(<([A-Za-z0-9:_-]+)>\\s*<!\\[CDATA\\[)(.*?)(\\]\\]>\\s*</\\2>)");
		Matcher matcher = pattern.matcher(xml);
		StringBuffer sb = new StringBuffer();

		while (matcher.find()) {
			// Collapse newlines + trim spaces so CDATA stays inline
			String collapsedContent = matcher.group(3).replaceAll("\\s*\\n\\s*", " ");
			String collapsed = matcher.group(1) + collapsedContent + matcher.group(4);
			matcher.appendReplacement(sb, Matcher.quoteReplacement(collapsed));
		}
		matcher.appendTail(sb);

		return sb.toString();
	}

	private String buildXmlSideBySide2(TestCaseRunHistoryDTO dto) {
		final boolean SWAP_COLORS = true; // true = original is red, modified is green

		String expected = dto.getInputXmlContent() == null ? ""
				: normalizeLargeCDataForDiff(dto.getInputXmlContent().trim());

		String actual = dto.getOutputXmlContent() == null ? ""
				: normalizeLargeCDataForDiff(dto.getOutputXmlContent().trim());

		// String expected = alignExpectedWithSkippedTags(expectedRaw, actual);

		// Remove runtime-only tags from Actual XML before diff
		actual = preprocessXml(actual);

		String o = jsEscapeForJsLiteral(expected);
		String m = jsEscapeForJsLiteral(actual);

		StringBuilder sb = new StringBuilder();
		sb.append(
				"<div id='container' style='border:1px solid var(--border-color,#555); border-radius:6px; overflow:hidden;'>")
				.append("<div id='diffEditor' style='width:100%; height:auto;'></div>")
				.append("<script src='https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.34.1/min/vs/loader.min.js'></script>")
				.append("<script>")
				.append("require.config({ paths: { 'vs': 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.34.1/min/vs' }});")
				.append("require(['vs/editor/editor.main'], function() {")
				.append("  function isDarkMode(){return document.body.classList.contains('dark');}")
				.append("  function applyDiffColors(){")
				.append("    var s=document.getElementById('diffColorStyles'); if(s) s.remove();")
				.append("    s=document.createElement('style'); s.id='diffColorStyles';")

				// ===== DARK MODE COLORS =====
				.append("    if(isDarkMode()){").append("      s.innerHTML=`").append(SWAP_COLORS
						// SWAPPED: Original red, Modified green
						? "        .monaco-editor .line-insert,.monaco-editor .line-insert td{background-color:rgba(255,0,0,0.20)!important}\n" // RED
								+ "        .monaco-editor .line-delete,.monaco-editor .line-delete td{background-color:rgba(50,205,50,0.20)!important}\n" // GREEN
								+ "        .monaco-editor .char-insert{background-color:rgba(255,0,0,0.40)!important}\n"
								+ "        .monaco-editor .char-delete{background-color:rgba(50,205,50,0.40)!important}"
						// DEFAULT: Original green, Modified red
						: "        .monaco-editor .line-insert,.monaco-editor .line-insert td{background-color:rgba(50,205,50,0.20)!important}\n"
								+ "        .monaco-editor .line-delete,.monaco-editor .line-delete td{background-color:rgba(255,0,0,0.20)!important}\n"
								+ "        .monaco-editor .char-insert{background-color:rgba(50,205,50,0.40)!important}\n"
								+ "        .monaco-editor .char-delete{background-color:rgba(255,0,0,0.40)!important}")
				.append("`;")

				// ===== LIGHT MODE COLORS =====
				.append("    } else {").append("      s.innerHTML=`").append(SWAP_COLORS
						// SWAPPED: Original red, Modified green
						? "        .monaco-editor .line-insert,.monaco-editor .line-insert td{background-color:rgba(255,0,0,0.35)!important}\n" // RED
								+ "        .monaco-editor .line-delete,.monaco-editor .line-delete td{background-color:rgba(144,238,144,0.35)!important}\n" // GREEN
								+ "        .monaco-editor .char-insert{background-color:rgba(255,0,0,0.60)!important}\n"
								+ "        .monaco-editor .char-delete{background-color:rgba(144,238,144,0.60)!important}"
						// DEFAULT: Original green, Modified red
						: "        .monaco-editor .line-insert,.monaco-editor .line-insert td{background-color:rgba(144,238,144,0.35)!important}\n"
								+ "        .monaco-editor .line-delete,.monaco-editor .line-delete td{background-color:rgba(255,0,0,0.35)!important}\n"
								+ "        .monaco-editor .char-insert{background-color:rgba(144,238,144,0.60)!important}\n"
								+ "        .monaco-editor .char-delete{background-color:rgba(255,0,0,0.60)!important}")
				.append("`;")

				.append("    } document.head.appendChild(s); }")

				// Monaco editor init
				.append("  var theme=isDarkMode()?'vs-dark':'vs';")
				.append("  var originalModel=monaco.editor.createModel(\"").append(o).append("\", 'xml');")
				.append("  var modifiedModel=monaco.editor.createModel(\"").append(m).append("\", 'xml');")
				.append("  window.diffEditor=monaco.editor.createDiffEditor(document.getElementById('diffEditor'),{")
				.append("    readOnly:true, renderSideBySide:true, automaticLayout:true, scrollBeyondLastLine:false,")
				.append("    renderFinalNewline:false, minimap:{enabled:false}, renderIndicators:false,")
				.append("    overviewRulerBorder:false, renderOverviewRuler:false});")
				.append("  diffEditor.setModel({original:originalModel, modified:modifiedModel});")
				.append("  var oe=diffEditor.getOriginalEditor(); var me=diffEditor.getModifiedEditor();")
				.append("  oe.updateOptions({wordWrap:'on', maxTokenizationLineLength:200000, renderWhitespace:'none'});")
				.append("  me.updateOptions({wordWrap:'on', maxTokenizationLineLength:200000, renderWhitespace:'none'});")
				.append("  monaco.editor.setTheme(theme); applyDiffColors();")
				.append("  new MutationObserver(applyDiffColors).observe(document.body,{attributes:true,attributeFilter:['class']});")
				.append("  setTimeout(function(){ var lineCount=me.getModel().getLineCount();")
				.append("    var h=(lineCount*19)+20; document.getElementById('diffEditor').style.height=h+'px'; diffEditor.layout();")
				.append("    document.querySelectorAll('.decorationsOverviewRuler, .overviewRuler, .original.diffOverviewRuler, .modified.diffOverviewRuler')")
				.append("      .forEach(function(r){r.style.display='none'; r.style.width='0'; r.style.overflow='hidden';});")
				.append("    document.querySelectorAll('.monaco-editor .margin,.monaco-editor .glyph-margin').forEach(function(m){m.style.background='transparent'});")
				.append("  },250); });</script>").append("<style>")
				.append(".monaco-diff-editor .monaco-sash.vertical{background-color:#666!important;opacity:1!important;width:4px!important}")
				.append(".monaco-diff-editor .monaco-sash.vertical:hover{background-color:#999!important}")
				.append(".monaco-diff-editor .monaco-sash.horizontal{background-color:#666!important;opacity:1!important;height:4px!important}")
				.append(".monaco-diff-editor .scroll-decoration{display:none!important}")
				.append(".monaco-editor .decorationsOverviewRuler, .monaco-editor .overviewRuler,")
				.append(".monaco-diff-editor .original.diffOverviewRuler, .monaco-diff-editor .modified.diffOverviewRuler ")
				.append("{display:none!important; width:0!important; overflow:hidden!important}")
				.append(".monaco-editor .token.comment color: gray !important; font-style: italic;background-color: rgba(128,128,128,0.15) !important;}")
				.append("</style></div>");

		return sb.toString();
	}

	private String normalizeLargeCDataForDiff(String xml) {
		if (xml == null || xml.isEmpty()) {
			return xml;
		}

		Pattern p = Pattern.compile("(?s)(^[ \\t]*)(<([A-Za-z0-9:_-]+)>\\s*<!\\[CDATA\\[)(.*?)(\\]\\]>\\s*</\\3>)",
				Pattern.MULTILINE);
		Matcher m = p.matcher(xml);
		StringBuffer out = new StringBuffer();

		while (m.find()) {
			String parentIndent = m.group(1);
			String openTag = m.group(2);
			String content = m.group(4);
			String closeTag = m.group(5);

			// ✅ Don’t hard-wrap, just preserve existing newlines & indentation
			StringBuilder wrapped = new StringBuilder();
			String[] lines = content.split("\\r?\\n", -1); // keep blank lines too
			for (String line : lines) {
				wrapped.append(parentIndent).append("    ") // keep indentation
						.append(line).append("\n");
			}

			// Closing tag aligned with parent
			String replacement = parentIndent + openTag + "\n" + wrapped + parentIndent + closeTag;

			m.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(out);

		return out.toString();
	}

	private String normalizeLargeCDataForDiffEachBySepertly(String xml) {
		if (xml == null || xml.isEmpty()) {
			return xml;
		}

		final int WRAP = 120; // wrap length
		Pattern p = Pattern.compile("(?s)(<([A-Za-z0-9:_-]+)>\\s*<!\\[CDATA\\[)(.*?)(\\]\\]>\\s*</\\2>)");
		Matcher m = p.matcher(xml);
		StringBuffer out = new StringBuffer();

		while (m.find()) {
			String content = m.group(3);

			// normalize tabs to spaces
			content = content.replace("\t", " ");

			StringBuilder wrapped = new StringBuilder();
			String[] lines = content.split("\\R");

			for (String line : lines) {
				line = line.trim();
				if (line.isEmpty()) {
					wrapped.append("\n");
					continue;
				}

				String[] tokens = line.split("(?<= )|(?= )"); // keep spaces as separate tokens

				int lineLen = 0;
				for (String token : tokens) {
					if (token.equals(" ")) {
						if (lineLen > 0 && lineLen % WRAP != 0) {
							wrapped.append("\n");
							lineLen = 0;
						}
					} else {
						if (lineLen + token.length() > WRAP) {
							wrapped.append("\n");
							lineLen = 0;
						}
						wrapped.append(token);
						lineLen += token.length();
					}
				}
				wrapped.append("\n");
			}

			// Force newline before closing tag
			String replacement = m.group(1) + wrapped.toString().trim() + "\n" // ensure newline before closing
					+ m.group(4);

			m.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String jsEscapeForJsLiteral(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n").replace("</script>",
				"<\\/script>");
	}

	public String buildSemanticXmlDiff(TestCaseRunHistoryDTO dto) {
		final boolean NEW_IS_RED = true;

		String expected = dto.getInputXmlContent() == null ? "" : dto.getInputXmlContent().trim();
		String actual = dto.getOutputXmlContent() == null ? "" : dto.getOutputXmlContent().trim();

		// Guard clause: missing XML content
		if (expected.isEmpty() || actual.isEmpty()) {
			StringBuilder msg = new StringBuilder("Semantic comparison skipped — ");
			if (expected.isEmpty() && actual.isEmpty()) {
				msg.append("both Expected and Actual XML are empty or missing.");
			} else if (expected.isEmpty()) {
				msg.append("Expected XML is missing, so comparison could not be performed.");
			} else if (actual.isEmpty()) {
				msg.append("Actual XML is missing, so comparison could not be performed.");
			}
			return noScenarioAligned(msg.toString(), 5);
		}

		Diff diff = DiffBuilder.compare(Input.fromString(expected)).withTest(Input.fromString(actual))
				.ignoreWhitespace().checkForSimilar().build();

		Map<String, String> friendly = new LinkedHashMap<>();
		friendly.put("TEXT_VALUE", "Text changed");
		friendly.put("CHILD_NODELIST_LENGTH", "Number of child elements changed");
		friendly.put("ELEMENT_TAG_NAME", "Tag name changed");
		friendly.put("NAMESPACE_URI", "Namespace changed");
		friendly.put("NAMESPACE_PREFIX", "Namespace prefix changed");
		friendly.put("ATTR_VALUE", "Attribute value changed");
		friendly.put("ATTR_NAME_LOOKUP", "Attribute presence/lookup changed");
		friendly.put("ATTR_NAME", "Attribute name changed");
		friendly.put("ATTR_PREFIX", "Attribute prefix changed");
		friendly.put("SCHEMA_LOCATION", "Schema location changed");
		friendly.put("NO_NAMESPACE_SCHEMA_LOCATION", "No-namespace schema location changed");
		friendly.put("XML_VERSION", "XML version changed");
		friendly.put("XML_STANDALONE", "XML standalone flag changed");
		friendly.put("HAS_DOCTYPE_DECLARATION", "Doctype declaration changed");
		friendly.put("DOCTYPE_NAME", "Doctype name changed");
		friendly.put("DOCTYPE_PUBLIC_ID", "Doctype publicId changed");
		friendly.put("DOCTYPE_SYSTEM_ID", "Doctype systemId changed");
		friendly.put("PROCESSING_INSTRUCTION_TARGET", "Processing-instruction target changed");
		friendly.put("PROCESSING_INSTRUCTION_DATA", "Processing-instruction data changed");
		friendly.put("COMMENT_VALUE", "Comment changed");
		friendly.put("CHILD_LOOKUP", "Child lookup changed");
		friendly.put("CHILD_NODELIST_SEQUENCE", "Child order changed");
		friendly.put("ELEMENT_NUM_ATTRIBUTES", "Attribute count changed");

		class Row {
			String typeKey, typeFriendly, topGroup, subPath, left, right;
		}

		// --- Pass 1: collect all differences ---
		List<Difference> all = new ArrayList<>();
		for (Difference d : diff.getDifferences()) {
			all.add(d);
		}

		// --- Pass 2: pick the shallowest XPath for each namespace change ---
		Map<String, Difference> bestNamespaceDiffs = new LinkedHashMap<>();

		for (Difference d : all) {
			Comparison c = d.getComparison();
			ComparisonType type = c.getType();

			if (type == ComparisonType.NAMESPACE_URI || type == ComparisonType.NAMESPACE_PREFIX) {
				String control = String.valueOf(c.getControlDetails().getValue());
				String test = String.valueOf(c.getTestDetails().getValue());
				String key = type.name() + ":" + control + "→" + test;

				String xp = c.getControlDetails().getXPath() != null ? c.getControlDetails().getXPath()
						: c.getTestDetails().getXPath();
				int depth = (xp == null) ? Integer.MAX_VALUE : xp.split("/").length;

				Difference currentBest = bestNamespaceDiffs.get(key);
				if (currentBest == null) {
					bestNamespaceDiffs.put(key, d);
				} else {
					String oldXp = currentBest.getComparison().getControlDetails().getXPath();
					if (oldXp == null)
						oldXp = currentBest.getComparison().getTestDetails().getXPath();
					int oldDepth = (oldXp == null) ? Integer.MAX_VALUE : oldXp.split("/").length;

					if (depth < oldDepth) {
						bestNamespaceDiffs.put(key, d); // replace with shallower diff
					}
				}
			}
		}

		// --- Pass 3: build rows, skipping duplicate/inherited namespace diffs ---
		List<Row> rows = new ArrayList<>();
		for (Difference d : all) {
			Comparison c = d.getComparison();
			ComparisonType type = c.getType();

			if (type == ComparisonType.NAMESPACE_URI || type == ComparisonType.NAMESPACE_PREFIX) {
				String control = String.valueOf(c.getControlDetails().getValue());
				String test = String.valueOf(c.getTestDetails().getValue());
				String key = type.name() + ":" + control + "→" + test;

				// keep only if this is the chosen shallowest difference
				if (bestNamespaceDiffs.get(key) != d) {
					continue;
				}
			}

			Row r = new Row();
			r.typeKey = type.name();
			r.typeFriendly = friendly.getOrDefault(r.typeKey, "Other change");
			r.left = String.valueOf(c.getControlDetails().getValue());
			r.right = String.valueOf(c.getTestDetails().getValue());

			String xp = c.getControlDetails().getXPath() != null ? c.getControlDetails().getXPath()
					: c.getTestDetails().getXPath();
			if (xp == null)
				xp = "/";
			String[] parts = xp.split("(?=\\/[^\\/]+\\[\\d+\\])");
			r.topGroup = parts.length > 1 ? parts[0] + parts[1] : parts[0];
			r.subPath = xp;

			rows.add(r);
		}

		Map<String, Long> typeCounts = rows.stream()
				.collect(Collectors.groupingBy(r -> r.typeFriendly, LinkedHashMap::new, Collectors.counting()));
		Map<String, List<Row>> byGroup = rows.stream()
				.collect(Collectors.groupingBy(r -> r.topGroup, LinkedHashMap::new, Collectors.toList()));
		List<String> parentOrder = new ArrayList<>(byGroup.keySet());

		Map<String, Integer> typeHue = new LinkedHashMap<>();
		for (String t : typeCounts.keySet())
			typeHue.put(t, positiveHue(t));
		Map<String, Integer> tagHue = new LinkedHashMap<>();
		for (String g : parentOrder)
			tagHue.put(g, positiveHue("TAG|" + g));

		// Light theme colors
		final String GREEN_LIGHT_BG = "#e6ffec", GREEN_LIGHT_FG = "#1f3321";
		final String RED_LIGHT_BG = "#ffe6e6", RED_LIGHT_FG = "#331f1f";

		// Dark theme colors
		final String GREEN_DARK_BG = "#1f3321", GREEN_DARK_FG = "#d9f3db";
		final String RED_DARK_BG = "#331f1f", RED_DARK_FG = "#ffd6d6";

		// Expected (left/old) vs Actual (right/new)
		String expectedLightBg = NEW_IS_RED ? GREEN_LIGHT_BG : RED_LIGHT_BG;
		String expectedLightFg = NEW_IS_RED ? GREEN_LIGHT_FG : RED_LIGHT_FG;
		String actualLightBg = NEW_IS_RED ? RED_LIGHT_BG : GREEN_LIGHT_BG;
		String actualLightFg = NEW_IS_RED ? RED_LIGHT_FG : GREEN_LIGHT_FG;

		String expectedDarkBg = NEW_IS_RED ? GREEN_DARK_BG : RED_DARK_BG;
		String expectedDarkFg = NEW_IS_RED ? GREEN_DARK_FG : RED_DARK_FG;
		String actualDarkBg = NEW_IS_RED ? RED_DARK_BG : GREEN_DARK_BG;
		String actualDarkFg = NEW_IS_RED ? RED_DARK_FG : GREEN_DARK_FG;

		StringBuilder html = new StringBuilder();
		html.append("<style>")
				// --- light theme vars ---
				.append(":root{--hdr-bg:#f5f7fa;--hdr-fg:#222;--border:#d0d7de;")
				.append("--tbl-head-bg:#f0f2f5;--tbl-head-fg:#222;").append("--tbl-row-bg:#fff;--tbl-row-alt:#fafafa;")
				.append("--chip-fg:#000;")
				// expected/actual with swapped colors
				.append("--expected-bg:").append(expectedLightBg).append(";").append("--expected-fg:")
				.append(expectedLightFg).append(";").append("--actual-bg:").append(actualLightBg).append(";")
				.append("--actual-fg:").append(actualLightFg).append(";")
				.append("--type-s:55%;--type-l:88%;--type-text-s:45%;--type-text-l:28%;")
				.append("--tag-s:50%;--tag-l:90%;--tag-text-s:40%;--tag-text-l:26%;")
				.append("--grp-tint-light:12%;--type-tint-light:10%;")
				.append("--icon-fg:#4a5568;--icon-bg:transparent;--icon-hover-bg:rgba(0,0,0,0.05);")
				.append("--diff-font:12.5px;}")

				// --- dark theme vars ---
				.append("body.dark{--hdr-bg:#2f333a;--hdr-fg:#ddd;--border:#4a4f55;")
				.append("--tbl-head-bg:#1f2228;--tbl-head-fg:#ddd;")
				.append("--tbl-row-bg:#2a2d33;--tbl-row-alt:#26292f;").append("--chip-fg:#fff;")
				// expected/actual with swapped colors
				.append("--expected-bg:").append(expectedDarkBg).append(";").append("--expected-fg:")
				.append(expectedDarkFg).append(";").append("--actual-bg:").append(actualDarkBg).append(";")
				.append("--actual-fg:").append(actualDarkFg).append(";")
				.append("--type-s:35%;--type-l:26%;--type-text-s:35%;--type-text-l:78%;")
				.append("--tag-s:32%;--tag-l:28%;--tag-text-s:35%;--tag-text-l:80%;")
				.append("--grp-tint-dark:18%;--type-tint-dark:15%;")
				.append("--icon-fg:#cbd5e1;--icon-bg:transparent;--icon-hover-bg:rgba(255,255,255,0.08);")
				.append("--diff-font:12px;}")

				// enforce usage
				.append(".valL{background:var(--expected-bg)!important;color:var(--expected-fg)!important;}")
				.append(".valR{background:var(--actual-bg)!important;color:var(--actual-fg)!important;}")

				// --- styles ---
				.append(".sem-xml{font-family:system-ui,Segoe UI,Roboto,Inter,Arial,sans-serif}")
				.append(".toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:8px 0;border-bottom:1px solid var(--border)}")
				.append(".toolbar .spacer{flex:1}")
				.append(".bar-title{font-weight:600;color:var(--hdr-fg);margin-right:4px;font-size:13px;opacity:.9}")
				.append(".chip{border-radius:999px;padding:4px 10px;font-size:12px;cursor:pointer;font-weight:600;color:var(--chip-fg);border:1px solid rgba(0,0,0,.04);}")
				.append(".chip[data-on='0']{opacity:.45;filter:grayscale(25%)}")
				.append(".type-chip{background:hsl(var(--chip-h), var(--type-s), var(--type-l));}")
				.append(".tag-chip{background:hsl(var(--chip-h), var(--tag-s), var(--tag-l));}")
				.append(".iconbtn{border:none;background:var(--icon-bg);color:var(--icon-fg);font-size:14px;")
				.append("border-radius:6px;cursor:pointer;padding:4px 8px;transition:background 0.2s;")
				.append("display:flex;align-items:center;gap:4px}")
				.append(".iconbtn:hover{background:var(--icon-hover-bg)}")
				.append(".iconbtn svg{width:14px;height:14px;display:inline-block;}")
				.append(".tbl{width:100%;border-collapse:collapse;margin-top:8px}")
				.append(".tbl th,.tbl td{border:1px solid var(--border);padding:6px 8px;vertical-align:top}")
				.append(".tbl thead th{color:var(--tbl-head-fg);background:#f2f4f7;text-align:left}") // background:var(--tbl-head-bg);
				.append("body.dark .tbl thead th{color:var(--tbl-head-fg);background:#333;text-align:left}")
				.append(".grp{cursor:pointer;font-weight:600;color:var(--hdr-fg);background:hsl(var(--chip-h), var(--tag-s), calc(var(--tag-l) - var(--grp-tint-light)));}")
				.append("body.dark .grp{background:hsl(var(--chip-h), var(--tag-s), calc(var(--tag-l) + var(--grp-tint-dark)));}")
				.append(".row td:first-child{background:hsl(var(--chip-h), var(--type-s), calc(var(--type-l) - var(--type-tint-light)));}")
				.append("body.dark .row td:first-child{background:hsl(var(--chip-h), var(--type-s), calc(var(--type-l) + var(--type-tint-dark)));}")
				// Light mode zebra
				.append(".tbl tbody tr:nth-child(even):not(.grp){background:#f4f4f4;} ")// var(--tbl-row-alt)
				// Dark mode zebra
				.append("body.dark .tbl tbody tr:nth-child(even):not(.grp){background:#383838;} ")// var(--tbl-row-alt-dark)
				.append(".path{font-family:monospace;font-size:12px;opacity:.8;color:var(--hdr-fg)}")
				.append(".type-tint{background:hsl(var(--chip-h), var(--type-s), var(--type-l));"
						+ "color:var(--chip-fg);font-weight:700;font-size:smaller;padding:2px 6px;border-radius:6px;display:inline-block}")
				.append(".tag-tint{background:hsl(var(--chip-h), var(--tag-s), var(--tag-l));"
						+ "color:var(--chip-fg);font-weight:700;;font-size:smaller;padding:2px 6px;border-radius:6px;display:inline-block}")
				.append(".valL,.valR{display:inline-block;white-space:pre-wrap;border-radius:6px;padding:3px 7px;font-size:var(--diff-font)}")
				.append(".valL{background:var(--expected-bg);color:var(--expected-fg)}")
				.append(".valR{background:var(--actual-bg);color:var(--actual-fg)}")
				.append(".toggle-icon{display:inline-block;width:0;height:0;margin-right:6px;")
				.append("border-left:8px solid currentColor;") // triangle thickness/boldness
				.append("border-top:5px solid transparent;border-bottom:5px solid transparent;")
				.append("cursor:pointer;vertical-align:middle;transition:transform 0.18s ease;}")
				// rotate caret 90° to point down when expanded
				.append(".grp[data-collapsed='0'] .toggle-icon{transform:rotate(90deg);} ").append("</style>");

		html.append("<div class='sem-xml'>");

		// --- Tag Filter Bar ---
		html.append("<div class='toolbar' id='tagBar'><span class='bar-title'>Parent Tag Filter</span>");
		for (String p : parentOrder) {
			int hue = tagHue.get(p);
			html.append("<span class='chip tag-chip' style='--chip-h:").append(hue)
					.append("' data-kind='tag' data-key=\"").append(escapeHtmlAttr(p)).append("\" data-on='1'>")
					.append(escapeHtml(p)).append("</span>");
		}

		html.append("<span class='spacer'></span>").append("<button class='iconbtn' id='expAll' title='Expand all'>")
				// base caret right
				.append("<svg id='expIcon' viewBox='0 0 24 24'><path fill='currentColor' d='M8 5l8 7-8 7z'/></svg>")
				.append(" Expand</button>").append("<button class='iconbtn' id='colAll' title='Collapse all'>")
				// use same caret, but rotate left
				.append("<svg id='colIcon' viewBox='0 0 24 24' style='transform:rotate(180deg);'><path fill='currentColor' d='M8 5l8 7-8 7z'/></svg>")
				.append(" Collapse</button></div>");

		// --- Type Filter Bar ---
		html.append("<div class='toolbar' id='typeBar'><span class='bar-title'>Type Filter</span>");
		for (Map.Entry<String, Long> e : typeCounts.entrySet()) {
			String label = e.getKey();
			int hue = typeHue.get(label);
			html.append("<span class='chip type-chip' style='--chip-h:").append(hue)
					.append("' data-kind='type' data-key=\"").append(escapeHtmlAttr(label)).append("\" data-on='1'>")
					.append(escapeHtml(label)).append(" <span style='opacity:.8'>")
//							.append(e.getValue()) -- For Count
					.append("</span></span>");
		}
		html.append("</div>");

		// --- Table ---
		html.append(
				"<table class='tbl' id='semTbl'><thead><tr><th>Type</th><th>Expected</th><th>Actual</th></tr></thead><tbody>");
		int gid = 0;
		for (Map.Entry<String, List<Row>> g : byGroup.entrySet()) {
			String groupKey = g.getKey();
			String groupId = "g" + (++gid);
			int ghue = tagHue.get(groupKey);

			html.append("<tr class='grp' data-group='").append(groupId).append("' data-collapsed='0'>")
					.append("<td colspan='3'>").append("<span class='toggle-icon'></span>") // icon only via CSS
					.append("<span class='path'>Element: </span>").append("<span class='tag-tint' style='--chip-h:")
					.append(ghue).append("'>").append(escapeHtml(groupKey)).append("</span>").append("</td></tr>");

			for (Row r : g.getValue()) {
				int thue = typeHue.get(r.typeFriendly);
				html.append("<tr class='row' data-group='").append(groupId).append("' data-type=\"")
						.append(escapeHtmlAttr(r.typeFriendly)).append("\" data-parent=\"")
						.append(escapeHtmlAttr(groupKey)).append("\">")
						.append("<td><span class='type-tint' style='--chip-h:").append(thue).append("'>")
						.append(escapeHtml(r.typeFriendly)).append("</span><div class='path'>")
						.append(escapeHtml(r.subPath)).append("</div></td>").append("<td><span class='valL'>")
						.append(escapeHtml(r.left)).append("</span></td>").append("<td><span class='valR'>")
						.append(escapeHtml(r.right)).append("</span></td></tr>");
			}
		}
		html.append("</tbody></table></div>");

		// --- JS for filter + expand/collapse ---
		html.append("<script>(function(){").append("function applyFilters(){").append(
				"const activeTypes=new Set([...document.querySelectorAll('#typeBar .chip[data-on=\"1\"]')].map(c=>c.getAttribute('data-key')));")
				.append("const activeTags=new Set([...document.querySelectorAll('#tagBar .chip[data-on=\"1\"]')].map(c=>c.getAttribute('data-key')));")
				.append("const parentCounts={}, typeCounts={};")
				// Row filtering
				.append("document.querySelectorAll('#semTbl tbody tr.row').forEach(function(r){")
				.append("const t=r.getAttribute('data-type');const p=r.getAttribute('data-parent');")
				.append("const visible=(activeTypes.has(t)&&activeTags.has(p));")
				.append("r.style.display=visible?'table-row':'none';")
				.append("if(visible){parentCounts[p]=(parentCounts[p]||0)+1;typeCounts[t]=(typeCounts[t]||0)+1;}});")
				// Parent group row visibility
				.append("document.querySelectorAll('#semTbl tbody tr.grp').forEach(function(g){")
				.append("const parentKey=g.querySelector('.tag-tint')?.textContent.trim();")
				.append("const hasVisible=(parentCounts[parentKey]||0)>0;")
				.append("g.style.display=(activeTags.has(parentKey)&&hasVisible)?'table-row':'none';});")
				// Tag counters (always show, even 0)
				.append("document.querySelectorAll('#tagBar .chip').forEach(function(ch){")
				.append("const key=ch.getAttribute('data-key');const count=parentCounts[key]||0;")
				.append("let span=ch.querySelector('.count');")
				.append("if(!span){span=document.createElement('span');span.className='count';span.style.opacity='0.8';span.style.marginLeft='4px';ch.appendChild(span);} ")
				.append("span.textContent='('+count+')';});")
				// Type counters (always show, even 0)
				.append("document.querySelectorAll('#typeBar .chip').forEach(function(ch){")
				.append("const key=ch.getAttribute('data-key');const count=typeCounts[key]||0;")
				.append("let span=ch.querySelector('.count');")
				.append("if(!span){span=document.createElement('span');span.className='count';span.style.opacity='0.8';span.style.marginLeft='4px';ch.appendChild(span);} ")
				.append("span.textContent='('+count+')';});}")
				// Chip toggle (keeps clickable)
				.append("[...document.querySelectorAll('.chip')].forEach(function(ch){ch.addEventListener('click',function(){")
				.append("ch.setAttribute('data-on', ch.getAttribute('data-on')==='1'?'0':'1');applyFilters();});});")
				// Expand All (respect filters + reset arrows)
				.append("document.getElementById('expAll').onclick=function(){").append("applyFilters();")
				.append("document.querySelectorAll('#semTbl tbody tr.grp').forEach(function(g){")
				.append("const id=g.getAttribute('data-group');")
				.append("const rows=[...document.querySelectorAll('#semTbl tbody tr.row[data-group=\"'+id+'\"]')];")
				.append("rows.forEach(r=>{if(r.style.display!=='none')r.style.display='table-row';});")
				.append("g.setAttribute('data-collapsed','0');").append("});};")
				// Collapse All (respect filters + reset arrows)
				.append("document.getElementById('colAll').onclick=function(){")
				.append("document.querySelectorAll('#semTbl tbody tr.grp').forEach(function(g){")
				.append("const id=g.getAttribute('data-group');")
				.append("const rows=[...document.querySelectorAll('#semTbl tbody tr.row[data-group=\"'+id+'\"]')];")
				.append("rows.forEach(r=>{if(r.style.display!=='none')r.style.display='none';});")
				.append("g.setAttribute('data-collapsed','1');").append("});};")
				// Parent row toggle
				.append("document.querySelectorAll('#semTbl tbody tr.grp').forEach(function(g){")
				.append("g.addEventListener('click',function(){").append("const id=g.getAttribute('data-group');")
				.append("const rows=[...document.querySelectorAll('#semTbl tbody tr.row[data-group=\"'+id+'\"]')];")
				.append("const isCollapsed=g.getAttribute('data-collapsed')==='1';").append("if(isCollapsed){") // expand
				.append("rows.forEach(r=>{r.style.display='table-row';});")
				.append("g.setAttribute('data-collapsed','0');").append("} else {") // collapse
				.append("rows.forEach(r=>{r.style.display='none';});").append("g.setAttribute('data-collapsed','1');")
				.append("}});});")
				// Init
				.append("applyFilters();").append("})();</script>");

		return html.toString();
	}

	private static int positiveHue(String s) {
		if (s == null)
			return 0;
		int h = 0;
		for (int i = 0; i < s.length(); i++) {
			h = (31 * h + s.charAt(i)) & 0x7fffffff; // positive
		}
		return h % 360;
	}

//	private static String escapeHtml(String s) {
//		if (s == null)
//			return "";
//		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
//	}

	private static String escapeHtmlAttr(String s) {
		if (s == null)
			return "";
		return escapeHtml(s).replace("\"", "&quot;").replace("'", "&#39;");
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

		// Parse unexecutedScenarios JSON string
		List<Map<String, Object>> unexecuted = java.util.Collections.emptyList();
		try {
			String rawJson = dto.getUnexecutedScenarios();
			if (rawJson != null && !rawJson.trim().isEmpty()) {
				ObjectMapper mapper = new ObjectMapper();
				unexecuted = mapper.readValue(rawJson, new TypeReference<List<Map<String, Object>>>() {
				});
			}
		} catch (Exception e) {
			logger.warn("Failed to parse unexecutedScenarios JSON: {}", e.getMessage());
		}

		// Combined empty check
		if (diffsList.isEmpty() && unexecuted.isEmpty()) {
			return noScenarioAligned("No XML differences or unexecuted scenarios found", 5);
		}

		// Only XML differences empty
		if (diffsList.isEmpty()) {
			return noScenarioAligned("No XML differences detected — Expected and Actual files appear identical.", 5);
		}

		StringBuilder sb = new StringBuilder(
				"<table class='scenario-table'><colgroup><col/><col/><col/><col/><col/></colgroup>")
				.append("<tr><th>Scenario</th><th>Input File</th><th>Output File</th><th>Message</th><th>Differences</th></tr>");

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
				"<table class='scenario-table'><colgroup><col/><col/><col/><col/><col/></colgroup>")
				.append("<tr><th>Scenario</th><th>Type</th><th>Example Header</th><th>Example Values</th><th>Errors</th></tr>");

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
				+ "</colgroup>")
				.append("<tr><th>Scenario</th><th>Type</th><th>Example Header</th><th colspan='2'>Example Values</th></tr>");

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

//	private String buildUnexecutedScenarios(TestCaseRunHistoryDTO dto) {
//		List<Map<String, Object>> unexec = extractScenarioList(dto.getOutputLog(), "unexecutedScenarioDetails");
//
//		// If not found, check for "unexecutedScenarioReasons"
//		if (unexec.isEmpty() && dto.getOutputLog() instanceof Map) {
//			Map<?, ?> map = (Map<?, ?>) dto.getOutputLog();
//			Object reasonsList = map.get("unexecutedScenarioReasons");
//			if (reasonsList instanceof List) {
//				unexec = (List<Map<String, Object>>) reasonsList;
//			}
//		}
//		if (unexec.isEmpty()) {
//			return noScenarioAligned("No unexecuted scenarios", 5);
//		}
//
//		StringBuilder sb = new StringBuilder("<table class='scenario-table'><colgroup>"
//				+ "<col style='width:75%'><col style='width:40%'>" + "</colgroup>")
//				.append("<tr><th>Scenario</th><th>Errors</th></tr>");
//
//		int ui = 0;
//		for (Map<String, Object> s : unexec) {
//			String scenario = escapeHtml(String.valueOf(s.getOrDefault("scenarioName", "N/A")));
//			List<String> errors = safeList(s.get("errors"));
//
//			// Fallback to "reason" if errors list is empty
//			if (errors.isEmpty() && s.containsKey("reason")) {
//				errors = List.of(String.valueOf(s.get("reason")));
//			}
//
//			String errHtml = errors.isEmpty() ? "-"
//					: "<button class='toggle-btn' onclick=\"toggle('unexecErr" + ui + "')\">View</button>"
//							+ "<div id='unexecErr" + ui + "' style='display:none;margin-top:8px'>"
//							+ String.join("<br>", errors) + "</div>";
//
//			sb.append("<tr><td>").append(scenario).append("</td>").append("<td>").append(errHtml).append("</td></tr>");
//			ui++;
//		}
//
//		sb.append("</table>");
//		return sb.toString();
//	}

	private String buildUnexecutedScenarios(TestCaseRunHistoryDTO dto) {
		List<Map<String, Object>> unexec = extractScenarioList(dto.getOutputLog(), "unexecutedScenarioDetails");

		// If not found, check for "unexecutedScenarioReasons"
		if (unexec.isEmpty() && dto.getOutputLog() instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) dto.getOutputLog();
			Object reasonsList = map.get("unexecutedScenarioReasons");
			if (reasonsList instanceof List) {
				unexec = (List<Map<String, Object>>) reasonsList;
			}
		}
		if (unexec.isEmpty()) {
			return noScenarioAligned("No unexecuted scenarios", 5);
		}

		// ✅ Make table like buildFailedScenarios
		StringBuilder sb = new StringBuilder(
				"<table class='scenario-table'><colgroup><col/><col/><col/><col/><col/></colgroup>")
				.append("<tr><th>Scenario</th><th>Type</th><th>Example Header</th><th>Example Values</th><th>Errors</th></tr>");

		int ui = 0;
		for (Map<String, Object> s : unexec) {
			String scenario = escapeHtml(String.valueOf(s.getOrDefault("scenarioName", "N/A")));

			// Extract outline info if available
			List<String> headers = safeList(s.get("exampleHeader"));
			List<String> values = safeList(s.get("exampleValues"));

			// Errors fallback: check reason if errors empty
			List<String> errors = safeList(s.get("errors"));
			if (errors.isEmpty() && s.containsKey("reason")) {
				errors = Collections.singletonList(String.valueOf(s.get("reason")));
			}

			String errHtml = errors.isEmpty() ? "-"
					: "<button class='toggle-btn' onclick=\"toggle('unexecErr" + ui + "')\">View</button>"
							+ "<div id='unexecErr" + ui + "' style='display:none;margin-top:8px'>"
							+ String.join("<br>", errors) + "</div>";

			// ✅ Add ScenarioType, ExampleHeader, ExampleValues
			sb.append("<tr class='row-unexec'><td>").append(scenario).append("</td><td>")
					.append(s.getOrDefault("scenarioType", "-")).append("</td><td>")
					.append(headers.isEmpty() ? "-" : String.join(", ", headers)).append("</td><td>")
					.append(values.isEmpty() ? "-" : String.join(", ", values)).append("</td><td>").append(errHtml)
					.append("</td></tr>");

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
		for (ScenarioLogGroupDTO group : dto.getRawCucumberLogGrouped().getGroupedLogs()) {
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
		if (outputLog instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) outputLog;

			// Check top-level key
			Object list = map.get(key);
			if (list instanceof List) {
				return (List<Map<String, Object>>) list;
			}

			// ✅ Check inside runSummary
			Object runSummary = map.get("runSummary");
			if (runSummary instanceof Map) {
				Map<?, ?> summary = (Map<?, ?>) runSummary;
				Object innerList = summary.get(key);
				if (innerList instanceof List) {
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

}