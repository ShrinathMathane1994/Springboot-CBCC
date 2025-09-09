package com.qa.cbcc.service;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
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
import com.qa.cbcc.dto.ScenarioExampleRunDTO;
import com.qa.cbcc.dto.ScenarioLogGroupDTO;
import com.qa.cbcc.dto.TestCaseRunHistoryDTO;

@Service
public class TestCaseReportService {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseReportService.class);
	ObjectMapper objectMapper;

	// Central config for tags to skip
	private static final Set<String> SKIPPED_TAGS;
	private static final String SKIPPED_TAGS_REGEX;
	private static final Set<String> PLACEHOLDERS;
	private static final Pattern PLACEHOLDER_TOKEN;
	// New flag: when true, apply skipped.tags even inside <Payload>/CDATA
	private static final boolean SKIP_INTERNAL_TAGS;
	private static final Set<String> EXPLICIT_INTERNAL_SKIPPED_TAGS;

	static {
		Properties props = new Properties();
		try (InputStream in = Thread.currentThread().getContextClassLoader()
				.getResourceAsStream("sem-diff.properties")) {
			if (in != null) {
				props.load(in);
			}
		} catch (IOException e) {
			logger.warn("Could not load sem-diff.properties from classpath: {}", e.getMessage());
		}

		// fallback defaults if missing
		String skipped = props.getProperty("skipped.tags", "CreDtTm,TmStmpDetls,EndToEndId");
		String placeholders = props.getProperty("placeholders", "UETR");

		SKIPPED_TAGS = Arrays.stream(skipped.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));

		PLACEHOLDERS = Arrays.stream(placeholders.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));

		SKIPPED_TAGS_REGEX = buildSkippedTagsRegex(SKIPPED_TAGS);

		String joined = PLACEHOLDERS.stream().map(Pattern::quote).collect(Collectors.joining("|"));
		if (!joined.isEmpty()) {
			PLACEHOLDER_TOKEN = Pattern.compile("\\$\\{(" + joined + ")\\}");
		} else {
			// never matches
			PLACEHOLDER_TOKEN = Pattern.compile("(?!)");
		}

		// Read skip-internal configuration: accept "skip.internal.tags" or
		// "skipInternalTags"
		String skipInternalVal = props.getProperty("skip.internal.tags");
		if (skipInternalVal == null) {
			skipInternalVal = props.getProperty("skipInternalTags");
		}
		boolean skipInternal = false;
		if (skipInternalVal != null && !skipInternalVal.trim().isEmpty()) {
			String v = skipInternalVal.trim().toLowerCase();
			skipInternal = v.equals("true") || v.equals("yes") || v.equals("1");
		}
		SKIP_INTERNAL_TAGS = skipInternal;

		// Read explicit internal skip list: tags that should be skipped even when
		// skip.internal.tags=false
		String explicitInternal = props.getProperty("explicit.skip.internal.tags", "").trim();
		if (explicitInternal.isEmpty()) {
			EXPLICIT_INTERNAL_SKIPPED_TAGS = new LinkedHashSet<>();
		} else {
			EXPLICIT_INTERNAL_SKIPPED_TAGS = Arrays.stream(explicitInternal.split("[,;]")).map(String::trim)
					.filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
		}

		logger.info("sem-diff: skipped.tags={}, placeholders={}, skip.internal.tags={}, explicit.skip.internal.tags={}",
				SKIPPED_TAGS, PLACEHOLDERS, SKIP_INTERNAL_TAGS, EXPLICIT_INTERNAL_SKIPPED_TAGS);
	}

	private static String buildSkippedTagsRegex(Set<String> tags) {
		if (tags == null || tags.isEmpty()) {
			return "";
		}
		String joined = tags.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
				.map(Pattern::quote).collect(Collectors.joining("|"));
		String openClose = "(?s)<(?:[^:\\s>]+:)?" + "(?:" + joined + ")\\b[^>]*>.*?</(?:[^:\\s>]+:)?" + "(?:" + joined
				+ ")\\s*>";
		String selfClose = "(?s)<(?:[^:\\s>]+:)?" + "(?:" + joined + ")\\b[^>]*/\\s*>";
		return "(?:" + openClose + "|" + selfClose + ")";
	}

	private static final Pattern PLACEHOLDER_ONLY_LINE = Pattern.compile("^\\s*" + PLACEHOLDER_TOKEN + "\\s*$");

	private static boolean isPlaceholderOnlyLine(String s) {
		return s != null && PLACEHOLDER_ONLY_LINE.matcher(s).matches();
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

//	private Pair<String, String> alignExpectedWithSkippedTags(String expectedXml, String actualXml) {
//		if (expectedXml == null)
//			expectedXml = "";
//		if (actualXml == null)
//			actualXml = "";
//
//		// keep trailing empty lines if any
//		String[] expectedLines = expectedXml.split("\\r?\\n", -1);
//		String[] actualLines = actualXml.split("\\r?\\n", -1);
//
//		List<String> alignedExpected = new ArrayList<>();
//		List<String> alignedActual = new ArrayList<>();
//
//		int ei = 0; // cursor over expected
//
//		for (int ai = 0; ai < actualLines.length; ai++) {
//			String actLine = actualLines[ai];
//			String expLine = (ei < expectedLines.length) ? expectedLines[ei] : "";
//
//			String ta = actLine.trim();
//			String te = expLine.trim();
//
//			// --- Case 0: skip-tag handling based on Actual line ---
//			Optional<String> skippedTag = SKIPPED_TAGS.stream()
//					.filter(tag -> ta.startsWith("<" + tag) || ta.startsWith("</" + tag)).findFirst();
//
//			if (skippedTag.isPresent()) {
//				String indent = leadingWhitespace(actLine);
//				alignedExpected.add(indent + "<!-- skipped " + skippedTag.get() + " -->");
//				alignedActual.add(actLine);
//				continue; // do NOT consume expected
//			}
//
//			// --- placeholder-only expected line handling with look-ahead (zero-width
//			// aware) ---
//			if (ei < expectedLines.length && isPlaceholderOnlyLine(expLine)) {
//
//				// Case A: zero-width match — current actual already matches the *next* expected
//				// line.
//				if (ei + 1 < expectedLines.length && linesEqualWithPlaceholders(expectedLines[ei + 1], actLine)) {
//					// Consume expected placeholder ONLY; keep the same actual line for the next
//					// iteration.
//					alignedExpected.add(expLine);
//					alignedActual.add(leadingWhitespace(expLine)); // empty cell keeps columns aligned
//					ei++;
//					ai--; // reprocess the same actual line next loop
//					continue;
//				}
//
//				// Case B: placeholder consumes this actual line (value line)
//				alignedExpected.add(expLine);
//				alignedActual.add(actLine);
//				ei++;
//				continue;
//			}
//
//			// --- Case 1: lines are equal (with placeholders) -> consume both
//			if (ei < expectedLines.length && linesEqualWithPlaceholders(expLine, actLine)) {
//				alignedExpected.add(expLine);
//				alignedActual.add(actLine);
//				ei++;
//				continue;
//			}
//
//			// --- Case 2: Actual has extra content BEFORE Expected's closing tag
//			if (ei < expectedLines.length && isClosingTag(te) && !ta.isEmpty()) {
//				String indent = leadingWhitespace(actLine);
//				alignedExpected.add(indent + "<!-- missing in expected -->");
//				alignedActual.add(actLine);
//				continue;
//			}
//
//			// --- Case 3: Actual has extra content after Expected ended
//			if (ei >= expectedLines.length && !ta.isEmpty()) {
//				String indent = leadingWhitespace(actLine);
//				alignedExpected.add(indent + "<!-- missing in expected -->");
//				alignedActual.add(actLine);
//				continue;
//			}
//
//			// --- Case 4: Extra in Expected (Actual is empty here)
//			if (!te.isEmpty() && ta.isEmpty()) {
//				String indent = leadingWhitespace(expLine);
//				alignedExpected.add(expLine);
//				alignedActual.add(indent + "<!-- missing in actual -->");
//				ei++;
//				continue;
//			}
//
//			// --- Case 5: Fallback mismatch
//			if (ei < expectedLines.length) {
//				alignedExpected.add(expLine);
//				alignedActual.add(actLine);
//				ei++;
//			} else {
//				String indent = leadingWhitespace(actLine);
//				alignedExpected.add(indent + "<!-- missing in expected -->");
//				alignedActual.add(actLine);
//			}
//		}
//
//		// Any remaining Expected lines after Actual ends → mark missing in actual
//		while (ei < expectedLines.length) {
//			String expLine = expectedLines[ei++];
//			String indent = leadingWhitespace(expLine);
//			alignedExpected.add(expLine);
//			alignedActual.add(indent + "<!-- missing in actual -->");
//		}
//
//		return Pair.of(String.join("\n", alignedExpected), String.join("\n", alignedActual));
//	}

	// Detect <Payload> opening tag (any namespace/prefix or attributes allowed)
	private boolean containsPayloadStart(String line) {
		if (line == null)
			return false;
		return line.toLowerCase().matches(".*<\\s*(?:[a-z0-9_\\-]+:)?payload(?:\\s+[^>]*)?>.*");
	}

	// Detect </Payload> closing tag (namespace/prefix tolerant)
	private boolean containsPayloadEnd(String line) {
		if (line == null)
			return false;
		return line.toLowerCase().matches(".*<\\s*/\\s*(?:[a-z0-9_\\-]+:)?payload\\s*>.*");
	}

	// Detect CDATA start
	private boolean containsCdataStart(String line) {
		return line != null && line.contains("<![CDATA[");
	}

	// Detect CDATA end
	private boolean containsCdataEnd(String line) {
		return line != null && line.contains("]]>");
	}

	private Pair<String, String> alignExpectedWithSkippedTags(String expectedXml, String actualXml) {
		if (expectedXml == null)
			expectedXml = "";
		if (actualXml == null)
			actualXml = "";

		String[] expectedLines = expectedXml.split("\\r?\\n", -1);
		String[] actualLines = actualXml.split("\\r?\\n", -1);

		List<String> alignedExpected = new ArrayList<>();
		List<String> alignedActual = new ArrayList<>();

		int ei = 0;

		int expectedPayloadDepth = 0;
		int actualPayloadDepth = 0;
		boolean expectedInCdata = false;
		boolean actualInCdata = false;

		for (int ai = 0; ai < actualLines.length; ai++) {
			String actLine = actualLines[ai];
			String expLine = (ei < expectedLines.length) ? expectedLines[ei] : "";

			String ta = actLine == null ? "" : actLine.trim();
			String te = expLine == null ? "" : expLine.trim();

			// --- update payload/CDATA states ---
			if (containsPayloadStart(expLine))
				expectedPayloadDepth++;
			if (containsCdataStart(expLine))
				expectedInCdata = true;
			if (containsCdataEnd(expLine))
				expectedInCdata = false;
			if (containsPayloadEnd(expLine) && expectedPayloadDepth > 0)
				expectedPayloadDepth--;

			if (containsPayloadStart(actLine))
				actualPayloadDepth++;
			if (containsCdataStart(actLine))
				actualInCdata = true;
			if (containsCdataEnd(actLine))
				actualInCdata = false;
			if (containsPayloadEnd(actLine) && actualPayloadDepth > 0)
				actualPayloadDepth--;

			boolean actualIsInsidePayload = (actualPayloadDepth > 0) || actualInCdata;
			boolean expectedIsInsidePayload = (expectedPayloadDepth > 0) || expectedInCdata;

			// --- Case 0: skip-tag handling ---
			boolean allowSkipByLocation = SKIP_INTERNAL_TAGS || (!actualIsInsidePayload && !expectedIsInsidePayload);
			Optional<String> skippedTag = Optional.empty();
			if (SKIPPED_TAGS != null && !SKIPPED_TAGS.isEmpty()) {
				String low = ta.toLowerCase();
				for (String tag : SKIPPED_TAGS) {
					if (tag == null || tag.trim().isEmpty())
						continue;
					String t = tag.toLowerCase();
					String open = "<" + t;
					String close = "</" + t;
					// skip if: allowed by location OR explicitly listed for internal skipping
					boolean forcedSkip = EXPLICIT_INTERNAL_SKIPPED_TAGS != null
							&& EXPLICIT_INTERNAL_SKIPPED_TAGS.contains(tag);
					if ((allowSkipByLocation || forcedSkip) && (low.startsWith(open) || low.startsWith(close))) {
						skippedTag = Optional.of(tag);
						break;
					}
				}
			}

			if (skippedTag.isPresent()) {
				String indent = leadingWhitespace(actLine);
				alignedExpected.add(indent + "<!-- skipped " + skippedTag.get() + " -->");
				alignedActual.add(actLine);
				continue; // don’t consume expected
			}

			// --- rest is unchanged ---
			if (ei < expectedLines.length && isPlaceholderOnlyLine(expLine)) {
				if (ei + 1 < expectedLines.length && linesEqualWithPlaceholders(expectedLines[ei + 1], actLine)) {
					alignedExpected.add(expLine);
					alignedActual.add(leadingWhitespace(expLine));
					ei++;
					ai--;
					continue;
				}
				alignedExpected.add(expLine);
				alignedActual.add(actLine);
				ei++;
				continue;
			}

			if (ei < expectedLines.length && linesEqualWithPlaceholders(te, ta)) {
				alignedExpected.add(expLine);
				alignedActual.add(actLine);
				ei++;
				continue;
			}

			if (ei < expectedLines.length && isClosingTag(te) && !ta.isEmpty()) {
				String indent = leadingWhitespace(actLine);
				alignedExpected.add(indent + "<!-- missing in expected -->");
				alignedActual.add(actLine);
				continue;
			}

			if (ei >= expectedLines.length && !ta.isEmpty()) {
				String indent = leadingWhitespace(actLine);
				alignedExpected.add(indent + "<!-- missing in expected -->");
				alignedActual.add(actLine);
				continue;
			}

			if (!te.isEmpty() && ta.isEmpty()) {
				String indent = leadingWhitespace(expLine);
				alignedExpected.add(expLine);
				alignedActual.add(indent + "<!-- missing in actual -->");
				ei++;
				continue;
			}

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

	private String removeBlankOnlyLines(String xml) {
		if (xml == null || xml.isEmpty())
			return "";

		// Split preserving line order
		String[] lines = xml.split("\\r?\\n", -1);

		List<String> kept = new ArrayList<>(lines.length);
		for (String line : lines) {
			// Keep the line only if it contains at least one non-whitespace character
			if (line != null && !line.trim().isEmpty()) {
				kept.add(line);
			}
		}

		// If everything was blank, return empty string, otherwise join using \n
		if (kept.isEmpty())
			return "";

		return String.join("\n", kept);
	}

	// Example-wise version
	private String buildXmlSideBySide(ScenarioExampleRunDTO row) {
		String original = row.getInputXml() == null ? "" : row.getInputXml();
		String modified = row.getOutputXml() == null ? "" : row.getOutputXml();

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

		// --- Remove blank-only lines at start, end and between nodes ---
		original = removeBlankOnlyLines(original);
		modified = removeBlankOnlyLines(modified);

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

	// render header list as "col1, col2, col3" (escaped)
	private String renderExampleHeader(ScenarioExampleRunDTO ex) {
		try {
			List<String> header = ex.getExampleHeader();
			if (header == null || header.isEmpty())
				return "-";
			return escapeHtml(String.join(", ", header));
		} catch (Exception ignored) {
			return "-";
		}
	}

	/**
	 * Render example values in a compact form. - If exampleValues is a Map ->
	 * render values in header order if header present, otherwise render keyless
	 * "value1, value2" from map.values() - If exampleValues is a List -> render
	 * comma-separated list - If exampleValues is a String or primitive -> return it
	 */
	private String renderExampleValues(ScenarioExampleRunDTO ex) {
		Object valuesObj = ex.getExampleValues();
		List<String> header = ex.getExampleHeader(); // may be null

		if (valuesObj == null) {
			return "-";
		}

		try {
			if (valuesObj instanceof Map) {
				// Map: if header exists render values in that order, otherwise iterate map
				// entries but only show values
				@SuppressWarnings("unchecked")
				Map<String, Object> map = (Map<String, Object>) valuesObj;
				if (header != null && !header.isEmpty()) {
					List<String> parts = new ArrayList<>();
					for (String key : header) {
						Object v = map.get(key);
						parts.add(v == null ? "-" : String.valueOf(v));
					}
					return escapeHtml(String.join(", ", parts));
				} else {
					List<String> parts = map.values().stream().map(o -> o == null ? "-" : String.valueOf(o))
							.collect(Collectors.toList());
					return escapeHtml(String.join(", ", parts));
				}
			} else if (valuesObj instanceof List) {
				@SuppressWarnings("unchecked")
				List<Object> list = (List<Object>) valuesObj;
				List<String> parts = list.stream().map(o -> o == null ? "-" : String.valueOf(o))
						.collect(Collectors.toList());
				return escapeHtml(String.join(", ", parts));
			} else {
				// primitive / string
				return escapeHtml(String.valueOf(valuesObj));
			}
		} catch (Exception exn) {
			// fallback
			return escapeHtml(String.valueOf(valuesObj));
		}
	}

	// Helper: render run status (Passed/Failed/Partially Passed/Unexecuted/etc.)
	private String renderStatusBadge(String statusRaw) {
		if (statusRaw == null)
			statusRaw = "N/A";
		String s = statusRaw.trim().toLowerCase();
		String cls = "badge-pill outline";
		String icon = "";
		String text = escapeHtml(statusRaw);

		if ("passed".equalsIgnoreCase(s) || "pass".equalsIgnoreCase(s)) {
			cls = "badge-pill compact success";
			icon = "<span class='icon-left'>✓</span> ";
		} else if ("failed".equalsIgnoreCase(s) || "fail".equalsIgnoreCase(s) || "failed".equals(s)) {
			cls = "badge-pill compact fail";
			icon = "<span class='icon-left'>&#10006;</span> ";
		} else if (s.contains("part") || s.contains("partial")) {
			cls = "badge-pill compact warn";
			icon = "<span class='icon-left'>!</span> ";
		} else if ("unexecuted".equalsIgnoreCase(s) || "n/a".equalsIgnoreCase(s) || "na".equalsIgnoreCase(s)) {
			cls = "badge-pill compact outline";
			icon = "";
		} else {
			cls = "badge-pill compact outline";
		}

		return "<span class='" + cls + "'>" + icon + "<span style='font-weight:600;'>" + text + "</span></span>";
	}

	private String renderXmlBadge(String xmlStatusRaw) {
		if (xmlStatusRaw == null)
			xmlStatusRaw = "N/A";
		String s = xmlStatusRaw.trim().toLowerCase();
		String cls = "badge-pill outline";
		String text = escapeHtml(xmlStatusRaw);

		if ("matched".equalsIgnoreCase(s) || "match".equalsIgnoreCase(s) || "equal".equalsIgnoreCase(s)
				|| "✅ xml files are equal.".equalsIgnoreCase(s)) {
			cls = "badge-pill compact success";
		} else if ("mismatched".equalsIgnoreCase(s) || "mismatch".equalsIgnoreCase(s) || "mismatched".equals(s)) {
			cls = "badge-pill compact fail";
		} else if ("partially unexecuted".equalsIgnoreCase(s) || s.contains("part")) {
			cls = "badge-pill compact warn";
		} else if ("n/a".equalsIgnoreCase(s) || "na".equalsIgnoreCase(s) || "n/a".equals(s)) {
			cls = "badge-pill compact outline";
		} else {
			cls = "badge-pill compact outline";
		}

		return "<span class='" + cls + "'>" + text + "</span>";
	}

	private String cssAndJs() {
		StringBuilder s = new StringBuilder();
		s.append("<html><head><meta charset='UTF-8'><style>")
				// container grid
				.append(".xml-ssb-wrap { display: grid; grid-template-columns: 40px 1fr 40px 1fr; border:1px solid #ccc; box-sizing:border-box; }")

				// expected + actual + line numbers cells
				.append(".xml-ssb-wrap .expected, .xml-ssb-wrap .actual { white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; max-width: 100%; padding:2px 6px; border-bottom:1px solid #eee; border-right:1px solid #ddd; font-family: monospace; }")

				// line numbers styling (light mode)
				.append(".xml-ssb-wrap .line-num { text-align: right; padding:2px 6px; color:#888; background:#f8f8f8; border-right:1px solid #ddd; user-select:none; }")
				.append(".xml-ssb-wrap .expected.skipped, .xml-ssb-wrap .actual.skipped, .xml-ssb-wrap .line-num.skipped { color: gray !important; font-style: italic; background-color: #f2f4f7 !important; }")

				// skipped tags styling
				.append(".xml-ssb-wrap .expected.skipped, .xml-ssb-wrap .actual.skipped { color: gray !important; font-style: italic; background-color: #f2f4f7 !important; }")

				// row structure
				.append(".xml-row { display: contents; }")

				// remove border on last column
				.append(".xml-row>div:last-child { border-right:none; }")

				// base backgrounds
				.append(".expected { background-color:#f9f9f9; } .actual { background-color:#fdfdfd; }")

				// diff highlights
				.append(".diff-green { background-color:#e6ffed;color:#22863a; }")
				.append(".diff-red   { background-color:#ffecec;color:#cb2431; }")

				// dark mode support
				.append("body.dark .xml-ssb-wrap .line-num { color:#aaa; background:#2a2a2a; border-right:1px solid #444; }")
				.append("body.dark .xml-ssb-wrap{ border-color:#555; }")
				.append("body.dark .xml-row>div{ border-bottom:1px solid #444; border-right:1px solid #444; }")
				.append("body.dark .expected{ background-color:#242424; color:#e0e0e0; }")
				.append("body.dark .actual{ background-color:#242424; color:#e0e0e0; }")
				.append("body.dark .diff-green{ background-color:#144d14; color:#b6fcb6; }")
				.append("body.dark .diff-red{ background-color:#5a1a1a; color:#ffb3b3; }")
				.append("body.dark .expected.skipped, body.dark .actual.skipped { color:#aaaaaa !important; background-color:#3a3a3a !important; }")
				.append("body.dark .xml-ssb-wrap .expected.skipped, body.dark .xml-ssb-wrap .actual.skipped, body.dark .xml-ssb-wrap .line-num.skipped { color:#aaaaaa !important; background-color:#3a3a3a !important; }")

				.append(".xml-pane{ border:1px solid #e6e6e6; border-radius:8px; overflow:auto; max-height:520px; background:#fff; }")
				.append("body.dark .xml-pane{ background:#2c2c2c; border-color:#444; }")
				.append(".xml-header{ padding:6px 10px; font-weight:600; background:#f2f4f7; border-bottom:1px solid #e6e6e6; position:sticky; top:0; }")
				.append("body.dark .xml-header{ background:#333; border-color:#444; }")
				.append(".xml-code{ font-family:ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, \"Liberation Mono\", monospace; font-size:12px; line-height:1.45; padding:10px; white-space:pre; }")
				.append(".xml-line{ display:flex; }")
				.append(".ln{ min-width:48px; text-align:right; padding-right:8px; opacity:.6; user-select:none; }")
				.append(".lc{ flex:1; overflow:hidden; }")

				/* FULL-line backgrounds */
				.append(".diff-removed-line{ background:#ffdddd; }").append(".diff-added-line{ background:#ddffdd; }")
				.append(".same-line{ background:transparent; }")

				/* Inline word-level highlights */
				.append(".diff-removed{ background:#ffaaaa; border-radius:2px; padding:0 1px; }")
				.append(".diff-added{ background:#aaffaa; border-radius:2px; padding:0 1px; }")

				// base page + header
				.append("body { font-family: Arial, sans-serif; margin: 20px; background: #f6f8fa; color:#222; }")
				.append("body.dark { background: #1e1e1e; color: #ddd; }")
				.append("h1 { text-align:center; font-size:28px; margin-bottom:6px; position: relative; }")
				.append("h1 .icon { font-size:30px; margin-right:8px; vertical-align:middle }")
				.append(".pdf-icon, .dark-toggle { position:absolute; top:0; cursor:pointer; font-size:20px; }")
				.append(".pdf-icon { right:30px; color:#e74c3c; } .dark-toggle { right:0; color:#4cafef; }")
				.append(".badge { padding:4px 8px; border-radius:6px; font-size:13px; color:white; display:inline-block; }")
				.append(".badge-pass { background:#28a745 } .badge-fail { background:#dc3545 } .badge-warn { background:#ffb703; color:#222 }")
				.append(".badge-xml-pass { background:#2ecc71 } .badge-xml-fail { background:#e74c3c }")

				// section base + tighten default margins
				.append(".section { border-radius:8px; background:white; box-shadow:0 2px 6px rgba(0,0,0,0.06); margin:8px 0; overflow:hidden; }")
				.append("body.dark .section { background:#2c2c2c; }")

				// header is flex row: title truncates OR wraps (we allow wrapping now)
				.append(".section-header { display:flex; justify-content:space-between; align-items:center; gap:12px; padding:8px 12px; background:#fbfbfb; cursor:default; }")
				.append("body.dark .section-header { background:#333; }")

				// title now allowed to wrap so long names show fully; we keep min-width:0 to
				// allow flex shrink
				.append(".section-title { font-weight:600; flex:1 1 auto; min-width:0; white-space:normal; word-break:break-word; overflow-wrap:anywhere; }")

				// status + button controls area kept on one line (no wrap) and compact
				.append(".section-controls { flex:0 0 auto; display:flex; gap:8px; flex-wrap:nowrap; align-items:center; }")
				.append(".section-subtitle { font-size:12px; color:#999; margin-right:6px; white-space:nowrap; }")

				// smaller content spacing; will be overridden by inline styles if present but
				// default is tighter
				.append(".section-content { padding:10px 12px; display:none;}")

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

				// Buttons and small utilities
				.append(".toggle-btn, .view-btn { padding:6px 12px; background:#1976d2; color:#fff; border:none; border-radius:4px; cursor:pointer; font-size:13px; transition:background 0.15s; white-space:nowrap; }")
				.append(".toggle-btn:hover, .view-btn:hover { background:#0b5ed7; }")
				.append(".diff-badge { margin-left:6px; padding:3px 6px; font-size:11px; border-radius:10px; color:#fff; vertical-align:middle; }")
				.append(".diff-badge.fail { background:#dc3545; } .diff-badge.pass { background:#28a745; }")

				.append(".log-box { background:#f7f7f9; border:1px solid #e8e8e8; padding:8px; border-radius:6px; font-family:monospace; white-space:pre-wrap; margin:6px 0; }")
				.append("body.dark .log-box { background:#2a2a2a; border:1px solid #444; }")

				.append(".row-pass { background:#eafaf0 } .row-fail { background:#fff2f2 }")
				.append(".inner-row { display:none; }")
				.append(".inner-table { width:100%; border-collapse:collapse; margin:0; table-layout:auto; }")
				.append(".inner-table th, .inner-table td { border:1px solid #ccc; padding:6px 8px; vertical-align:top; }")

				// overrides to neutralize inline margins & gaps emitted by generateHtmlReport
				.append(".section > div[id^='xmlDiffEx'], .section > div[id^='ssbEx'], .section > div[id^='semEx'] { margin:6px 0 !important; padding:6px 0 !important; }")
				.append("/* reduce visual gap within the examples container (if you use inline gap:12px this helps) */")
				.append(".examples-group { display:flex; flex-direction:column; gap:8px; margin-top:6px; }")

				// ----------------------------
				// NEW: compact pill badge styles
				// ----------------------------
				.append("/* Compact pill badges used in example header */")
				.append(".badge-pill { padding: 3px 8px; font-size:12px; border-radius:999px; min-height:20px; line-height:1; box-shadow:none; border:1px solid rgba(0,0,0,0.06); display:inline-flex; align-items:center; gap:6px; }")
				.append(".badge-pill.compact { padding:2px 6px; font-size:11px; min-height:18px; gap:4px; }")
				.append(".badge-pill .icon-left { width:14px; height:14px; font-size:11px; display:inline-flex; align-items:center; justify-content:center; border-radius:50%; }")
				.append(".badge-pill.small { padding:2px 6px; font-size:11px; min-height:18px; }")
				.append(".badge-pill.success { background: linear-gradient(180deg,#2ecc71,#28a745); color:#fff; }")
				.append(".badge-pill.fail    { background: linear-gradient(180deg,#ff6b6b,#dc3545); color:#fff; }")
				.append(".badge-pill.warn    { background: linear-gradient(180deg,#ffd166,#ffb703); color:#111; }")
				.append(".badge-pill.outline { background: transparent; color: inherit; border: 1px solid rgba(255,255,255,0.06); box-shadow:none; }")
				.append("body.dark .badge-pill.outline { border-color: rgba(255,255,255,0.08); }")
				.append(".section-controls > * { display:inline-flex; align-items:center; }")

				// -------------------------------------------------
				// LIGHT MODE REFINEMENTS (to match dark-mode polish)
				// Only apply where body is NOT .dark
				// -------------------------------------------------
				.append("body:not(.dark) { background: #f4f6f8; color: #222; }")
				// card look for light
				.append("body:not(.dark) .section { background: #fbfcfd; border: 1px solid #e3e6ea; box-shadow: 0 1px 2px rgba(16,24,40,0.04); }")
				.append("body:not(.dark) .section-header { background: linear-gradient(180deg,#f7f9fb,#f2f6fa); border-bottom: 1px solid #e8edf2; }")
				// Slightly smaller header padding in light
				.append("body:not(.dark) .section-header { padding:10px 14px; }")
				// tables in light look slightly raised
				.append("body:not(.dark) .scenario-table th { background:#f6f8fb; border-bottom:2px solid #e6e9ed; }")
				.append("body:not(.dark) .scenario-table td { background: #ffffff; }")
				.append("body:not(.dark) .scenario-table tr:nth-child(even) td { background: #fbfbfd; }")
				.append("body:not(.dark) .scenario-table tr:hover td { background: #f0f7ff; transition: background 0.15s ease; }")
				// badges: slightly toned down in light mode
				.append("body:not(.dark) .badge-pass { background: #37b24d; }")
				.append("body:not(.dark) .badge-fail { background: #d64545; }")
				.append("body:not(.dark) .badge-warn { background: #ffb703; color:#111; }")
				// pill badges outline variant for light small icons
				.append("body:not(.dark) .badge-pill.outline { border-color: rgba(16,24,40,0.06); }")
				// make example header controls smaller to blend
				.append("body:not(.dark) .section-controls .toggle-btn { padding:6px 10px; font-size:12px; }")
				.append("body:not(.dark) .toggle-btn, body:not(.dark) .view-btn { box-shadow: 0 1px 2px rgba(16,24,40,0.04); }")
				// compact the example card title slightly in light mode
				.append("body:not(.dark) .example-card .section-title { font-size:13px; }")
				.append("body:not(.dark) .example-card { padding:8px; }")

				// Accessibility helper: reduce motion
				.append("@media (prefers-reduced-motion: reduce) { * { transition: none !important; animation: none !important; } }")

				.append("</style>")

				// JS (updated toggle only)
				.append("<script>").append("function toggle(id){").append("var el=document.getElementById(id);")
				.append("if(!el){console.warn('toggle: element not found:', id); return;}")
				.append("var cs = window.getComputedStyle(el);").append("var isHidden = (cs.display === 'none');")
				.append("var tag = (el.tagName || '').toLowerCase();")
				.append("if(tag === 'tr' || el.classList.contains('inner-row')){")
				.append("  el.style.display = isHidden ? 'table-row' : 'none';").append("} else {")
				.append("  el.style.display = isHidden ? 'block' : 'none';").append("}").append("}")
				.append("function toggleDark(){ document.body.classList.toggle('dark'); if (window.monaco) { monaco.editor.setTheme(document.body.classList.contains('dark') ? 'vs-dark' : 'vs'); } }")
				.append("</script></head><body>");

		return s.toString();
	}

	@SuppressWarnings("unchecked")
	public String generateHtmlReport(TestCaseRunHistoryDTO dto, ScenarioExampleRunDTO scDTO,
			List<ScenarioExampleRunDTO> exampleRows) {
		SimpleDateFormat sdf = new SimpleDateFormat("EEEE, dd MMMM yyyy hh:mm a");

		StringBuilder html = new StringBuilder();

		// Preserve original CSS/JS (use the updated cssAndJs() you applied earlier)
		html.append(cssAndJs());

		// Small extra helpers (re-using existing toggle function from cssAndJs)
		html.append("<style>")
				// keep example container compact
				.append(".examples-group{ display:flex; flex-direction:column; gap:8px; margin-top:6px; }")
				.append(".example-card { margin:6px 0; padding:8px; border:1px solid #e6e6e6; border-radius:6px; background:transparent; }")
				.append("</style>");

		// Header
		html.append("<h1><span class='icon'>📋</span>Test Case Execution Report")
				.append("<span class='pdf-icon' onclick='expandAllAndPrint()'>🖨️</span>")
				.append("<span class='dark-toggle' onclick='toggleDark()'>🌙</span></h1>");

		String runStatus = dto.getRunStatus() == null ? "N/A" : dto.getRunStatus();
		String runBadgeClass = runStatus.toLowerCase().contains("fail") ? "badge-fail"
				: runStatus.toLowerCase().contains("error") ? "badge-fail"
						: runStatus.toLowerCase().contains("unex") ? "badge-warn"
								: runStatus.toLowerCase().contains("part") ? "badge-warn" : "badge-pass";
		html.append("<p style='text-align:center;margin:6px 0'><b>TC Status:</b> <span class='badge ")
				.append(runBadgeClass).append("'>").append(escapeHtml(runStatus)).append("</span></p>");

		String xmlStatus = dto.getXmlDiffStatus() == null ? "N/A" : dto.getXmlDiffStatus();
		String xmlBadgeClass = "Matched".equalsIgnoreCase(xmlStatus) ? "badge-xml-pass" : "badge-xml-fail";
		html.append("<p style='text-align:center;margin:6px 0'><b>XML Difference:</b> <span class='badge ")
				.append(xmlBadgeClass).append("'>").append(escapeHtml(xmlStatus)).append("</span></p>");

		String runTimeFormatted = (dto.getRunTime() != null) ? sdf.format(Timestamp.valueOf(dto.getRunTime())) : "N/A";
		html.append("<p style='text-align:center;margin:6px 0'><b>Executed On:</b> ")
				.append(escapeHtml(runTimeFormatted)).append("</p>");

		// Counts
		int passedCount = 0, failedCount = 0, unexecutedCount = 0;
		if (dto.getOutputLog() instanceof Map) {
			Map<String, Object> output = (Map<String, Object>) dto.getOutputLog();
			Object runSummaryObj = output.get("runSummary");
			if (runSummaryObj instanceof Map) {
				Map<String, Object> summary = (Map<String, Object>) runSummaryObj;
				try {
					passedCount = Integer.parseInt(summary.getOrDefault("totalPassedScenarios", 0).toString());
				} catch (Exception ignore) {
				}
				try {
					failedCount = Integer.parseInt(summary.getOrDefault("totalFailedScenarios", 0).toString());
				} catch (Exception ignore) {
				}
				try {
					unexecutedCount = Integer.parseInt(summary.getOrDefault("totalUnexecutedScenarios", 0).toString());
				} catch (Exception ignore) {
				}
			}
		}

		// Top-level lists (unchanged)
		html.append(buildSection("Passed Scenarios", passedCount, "passedScenarios", buildPassedScenarios(dto),
				passedCount == 0));
		html.append(buildSection("Failed Scenarios", failedCount, "failedScenarios", buildFailedScenarios(dto),
				failedCount == 0));
		html.append(buildSection("Unexecuted Scenarios", unexecutedCount, "unexecutedScenarios",
				buildUnexecutedScenarios(dto), unexecutedCount == 0));

		// === Examples grouped under single XML Differences section ===
		if (exampleRows == null || exampleRows.isEmpty()) {
			// fallback to history-level output
			html.append(buildSection("XML Differences", -1, "xmlDiff", buildXmlDifferencesFixed(dto), false));
			html.append(buildSection("Expected Vs Actual", -1, "xmlDomDiff", buildXmlSideBySide(dto), false));
			html.append(buildSection("Semantic XML Differences", -1, "xmlSemantic", buildSemanticXmlDiff(dto), true));
		} else {
			StringBuilder xmlGroup = new StringBuilder();
			xmlGroup.append("<div class='examples-group'>");

			int idx = 0;
			for (ScenarioExampleRunDTO ex : exampleRows) {
			    String title = ex.getScenarioName() == null ? ("example#" + (idx + 1)) : ex.getScenarioName();
			    String status = ex.getStatus() == null ? "N/A" : ex.getStatus();
			    String xmlStatusSce = ex.getXmlDiffStatus() == null ? "N/A" : ex.getXmlDiffStatus();

			    xmlGroup.append("<div class='section example-card' id='exampleCard").append(idx).append("'>");
			    xmlGroup.append("<div class='section-header'>");

			    // left: title
			    xmlGroup.append("<div class='section-title' style='font-size:14px;'>")
			            .append(escapeHtml(title))
			            .append("</div>");

			    // right: controls (badges + buttons)
			    xmlGroup.append("<div class='section-controls'>");

			    // xml badge (example-wise xml result)
			    xmlGroup.append("<div class='section-subtitle'>")
			            .append(renderXmlBadge(xmlStatusSce))
			            .append("</div>");

			    // toggle buttons (target child panels)
			    xmlGroup.append("<div style='display:flex;gap:6px;'>")
			            .append("<button class='toggle-btn' onclick=\"toggle('xmlDiffEx").append(idx).append("')\">Toggle XML Differences</button>")
			            .append("<button class='toggle-btn' onclick=\"toggle('ssbEx").append(idx).append("')\">Toggle Expected Vs Actual</button>")
			            .append("<button class='toggle-btn' onclick=\"toggle('semEx").append(idx).append("')\">Toggle Semantic XML Differences</button>")
			            .append("</div>");

			    xmlGroup.append("</div>"); // end section-controls
			    xmlGroup.append("</div>"); // end section-header

			    // === XML Differences panel (child) ===
			    try {
			        String xmlDiffHtml = buildXmlDifferencesFixed(ex, dto);
			        String xmlContainerId = "xmlDiffEx" + idx;
			        if (xmlDiffHtml == null || xmlDiffHtml.trim().isEmpty()) {
			            xmlGroup.append("<div id='").append(xmlContainerId)
			                    .append("' class='section-content' style='display:block;margin:6px 0;padding:6px 0;'>")
			                    .append("<div class='log-box'>No XML differences or unexecuted scenarios found</div>")
			                    .append("</div>");
			        } else {
			            xmlGroup.append("<div id='").append(xmlContainerId)
			                    .append("' class='section-content' style='display:block;margin:6px 0;padding:6px 0;'>")
			                    .append(xmlDiffHtml)
			                    .append("</div>");
			        }
			    } catch (Exception e) {
			        logger.warn("buildXmlDifferencesFixed threw for example {}: {}", idx, e.getMessage());
			        xmlGroup.append("<div class='log-box'>Error rendering XML Differences.</div>");
			    }

			    // === Expected Vs Actual panel (child) ===
			    xmlGroup.append("<div id='ssbEx").append(idx)
			            .append("' class='section-content' style='display:none;margin:6px 0;padding:6px 0;'>")
			            .append("<div style='font-weight:600;margin-bottom:6px;'>Expected Vs Actual</div>");
			    try {
			        String ssbHtml = buildXmlSideBySide(ex);
			        xmlGroup.append((ssbHtml == null || ssbHtml.trim().isEmpty())
			                ? "<div class='log-box'>No XML content available — both Expected and Actual files are empty or missing.</div>"
			                : ssbHtml);
			    } catch (Exception e) {
			        logger.warn("buildXmlSideBySide threw for example {}: {}", idx, e.getMessage());
			        xmlGroup.append("<div class='log-box'>Error rendering Expected Vs Actual.</div>");
			    }
			    xmlGroup.append("</div>");

			    // === Semantic panel (child) ===
			    xmlGroup.append("<div id='semEx").append(idx)
			            .append("' class='section-content' style='display:none;margin:6px 0;padding:6px 0;'>")
			            .append("<div style='font-weight:600;margin-bottom:6px;'>Semantic XML Differences</div>");
			    try {
			        String semHtml = buildSemanticXmlDiff(ex);
			        xmlGroup.append((semHtml == null || semHtml.trim().isEmpty())
			                ? "<div class='log-box'>Semantic comparison skipped — both Expected and Actual XML are empty or missing.</div>"
			                : semHtml);
			    } catch (Exception e) {
			        logger.warn("buildSemanticXmlDiff threw for example {}: {}", idx, e.getMessage());
			        xmlGroup.append("<div class='log-box'>Error rendering Semantic XML Differences.</div>");
			    }
			    xmlGroup.append("</div>");

			    // close example-card (all three child panels are inside the card)
			    xmlGroup.append("</div>");
			    idx++;
			}

			xmlGroup.append("</div>"); // end examples-group
			html.append(buildSection("XML Differences", -1, "xmlDiffPerExample", xmlGroup.toString(), false));
		}

		// Raw logs / summary
		html.append(buildSection("Raw Cucumber Logs", -1, "rawLogs", buildRawLogs(dto), false));
		html.append(buildSection("Raw Cucumber Summary", -1, "rawSummary", buildRawSummary(dto), false));

		html.append("</body></html>");
		return html.toString();
	}

	@SuppressWarnings("unchecked")
	public String generateHtmlReport3(TestCaseRunHistoryDTO dto, ScenarioExampleRunDTO scDTO,
			List<ScenarioExampleRunDTO> exampleRows) {
		SimpleDateFormat sdf = new SimpleDateFormat("EEEE, dd MMMM yyyy hh:mm a");

		StringBuilder html = new StringBuilder();
		html.append(cssAndJs());
		// Header
		html.append("<h1><span class='icon'>📋</span>Test Case Execution Report")
				.append("<span class='pdf-icon' onclick='expandAllAndPrint()'>🖨️</span>")
				.append("<span class='dark-toggle' onclick='toggleDark()'>🌙</span></h1>");

		String runStatus = dto.getRunStatus() == null ? "N/A" : dto.getRunStatus();
		String runBadgeClass = runStatus.toLowerCase().contains("fail") ? "badge-fail"
				: runStatus.toLowerCase().contains("error") ? "badge-fail"
						: runStatus.toLowerCase().contains("unex") ? "badge-warn"
								: runStatus.toLowerCase().contains("part") ? "badge-warn" : "badge-pass";
		html.append("<p style='text-align:center;margin:6px 0'><b>TC Status:</b> <span class='badge ")
				.append(runBadgeClass).append("'>").append(escapeHtml(runStatus)).append("</span></p>");

		String xmlStatus = dto.getXmlDiffStatus() == null ? "N/A" : dto.getXmlDiffStatus();
		String xmlBadgeClass = "Matched".equalsIgnoreCase(xmlStatus) ? "badge-xml-pass" : "badge-xml-fail";
		html.append("<p style='text-align:center;margin:6px 0'><b>XML Difference:</b> <span class='badge ")
				.append(xmlBadgeClass).append("'>").append(escapeHtml(xmlStatus)).append("</span></p>");

		String runTimeFormatted = (dto.getRunTime() != null) ? sdf.format(java.sql.Timestamp.valueOf(dto.getRunTime()))
				: "N/A";
		html.append("<p style='text-align:center;margin:6px 0'><b>Executed On:</b> ")
				.append(escapeHtml(runTimeFormatted)).append("</p>");

		// Counts (unchanged logic)
		int passedCount = 0, failedCount = 0, unexecutedCount = 0;
		if (dto.getOutputLog() instanceof Map) {
			Map<String, Object> output = (Map<String, Object>) dto.getOutputLog();
			Object runSummaryObj = output.get("runSummary");
			if (runSummaryObj instanceof Map) {
				Map<String, Object> summary = (Map<String, Object>) runSummaryObj;
				try {
					passedCount = Integer.parseInt(summary.getOrDefault("totalPassedScenarios", 0).toString());
				} catch (Exception ignore) {
				}
				try {
					failedCount = Integer.parseInt(summary.getOrDefault("totalFailedScenarios", 0).toString());
				} catch (Exception ignore) {
				}
				try {
					unexecutedCount = Integer.parseInt(summary.getOrDefault("totalUnexecutedScenarios", 0).toString());
				} catch (Exception ignore) {
				}
			}
		}

		// Keep top-level Passed/Failed/Unexecuted sections (same builders you already
		// have)
		html.append(buildSection("Passed Scenarios", passedCount, "passedScenarios", buildPassedScenarios(dto),
				passedCount == 0));
		html.append(buildSection("Failed Scenarios", failedCount, "failedScenarios", buildFailedScenarios(dto),
				failedCount == 0));
		html.append(buildSection("Unexecuted Scenarios", unexecutedCount, "unexecutedScenarios",
				buildUnexecutedScenarios(dto), unexecutedCount == 0));

		// === New behavior: prefer per-example display when exampleRows available ===
		if (exampleRows == null || exampleRows.isEmpty()) {
			// fallback to history-level output (unchanged)
			html.append(buildSection("XML Differences", -1, "xmlDiff", buildXmlDifferencesFixed(dto), false));
			html.append(buildSection("Expected Vs Actual", -1, "xmlDomDiff", buildXmlSideBySide(dto), false));
			html.append(buildSection("Semantic XML Differences", -1, "xmlSemantic", buildSemanticXmlDiff(dto), true));
		} else {
			// Build per-example differences table fragment
			StringBuilder diffTableBuilder = new StringBuilder();
			diffTableBuilder.append("<div style='margin-top:10px;'>");
			diffTableBuilder
					.append("<table class='scenario-table'><colgroup><col/><col/><col/><col/><col/></colgroup>");
			diffTableBuilder.append("<thead><tr>").append("<th>Scenario</th>").append("<th>Scenario Type</th>")
					.append("<th>Example Header</th>").append("<th>Example Values</th>").append("<th>Differences</th>")
					.append("</tr></thead><tbody>");

			int idx = 0;
			for (ScenarioExampleRunDTO ex : exampleRows) {
				// defensive parse of differences
				List<Map<String, Object>> diffs = new ArrayList<>();
				Object diffsObj = ex.getDifferences();
				if (diffsObj instanceof List) {
					for (Object o : (List<?>) diffsObj) {
						if (o instanceof Map)
							diffs.add((Map<String, Object>) o);
					}
				}
				int diffCount = diffs.size();

				String scenario = ex.getScenarioName() == null ? "-" : ex.getScenarioName();
				String scenarioType = ex.getScenarioType() == null ? "-" : ex.getScenarioType();
				String headerRendered = renderExampleHeader(ex); // lists -> comma
				String valuesRendered = renderExampleValues(ex); // values-only CSV or list

				diffTableBuilder.append("<tr class='")
						.append("Passed".equalsIgnoreCase(ex.getStatus()) ? "row-pass" : "row-fail").append("'><td>")
						.append(escapeHtml(scenario)).append("</td>").append("<td>").append(escapeHtml(scenarioType))
						.append("</td>").append("<td>").append(headerRendered).append("</td>").append("<td>")
						.append(valuesRendered).append("</td>").append("<td style='white-space:nowrap'>");

				if (diffCount > 0) {
					diffTableBuilder.append("<button class='view-btn' onclick=\"toggle('diffInner").append(idx)
							.append("')\">View</button>").append("<span class='diff-badge fail'>").append(diffCount)
							.append("</span>");
				} else {
					diffTableBuilder.append("-");
				}
				diffTableBuilder.append("</td></tr>");

				// inner details
				if (diffCount > 0) {
					diffTableBuilder.append("<tr id='diffInner").append(idx)
							.append("' class='inner-row' style='display:none'><td colspan='5'>")
							.append("<table class='inner-table'><thead><tr><th>XPath</th><th>Node</th><th>Type</th><th>Details</th></tr></thead><tbody>");
					for (Map<String, Object> d : diffs) {
						String xpath = String.valueOf(d.getOrDefault("xpath", "-"));
						String node = String.valueOf(d.getOrDefault("node", "-"));
						String type = String.valueOf(d.getOrDefault("differenceType", d.getOrDefault("type", "-")));
						String details = formatMismatchDetails(d);
						diffTableBuilder.append("<tr><td>").append(escapeHtml(xpath)).append("</td><td>")
								.append(escapeHtml(node)).append("</td><td>").append(escapeHtml(type))
								.append("</td><td>").append(escapeHtml(details)).append("</td></tr>");
					}
					diffTableBuilder.append("</tbody></table></td></tr>");
				}

				idx++;
			}

			diffTableBuilder.append("</tbody></table></div>");
			html.append(buildSection("XML Differences", -1, "xmlDiffPerExample", diffTableBuilder.toString(), false));

			// Expected Vs Actual - per example (wrap each fragment in section content)
			StringBuilder ssbFrag = new StringBuilder();
			ssbFrag.append("<div style='margin-top:10px;'>");
			idx = 0;
			for (ScenarioExampleRunDTO ex : exampleRows) {
				String label = ex.getScenarioName() == null ? ("example#" + (idx + 1)) : ex.getScenarioName();
				ssbFrag.append("<div style='margin-top:10px;padding:8px;border:1px solid #eee;border-radius:6px;'>");
				ssbFrag.append("<div style='display:flex;justify-content:space-between;align-items:center'>")
						.append("<div style='font-size: 14px'>").append(escapeHtml(label)).append("</div>")
						.append("<div><button class='view-btn' onclick=\"toggle('ssb").append(idx)
						.append("')\">Toggle Side-by-side</button></div>").append("</div>");

				ssbFrag.append("<div id='ssb").append(idx).append("' style='display:none;margin-top:8px;'>");
				ssbFrag.append(buildXmlSideBySide(ex)); // fragment expected
				ssbFrag.append("</div></div>");
				idx++;
			}
			ssbFrag.append("</div>");
			html.append(buildSection("Expected Vs Actual", -1, "xmlDomDiffPerExample", ssbFrag.toString(), true));

			// --- Per-example Semantic Differences (grouped inside a section) ---
			StringBuilder semBuilder = new StringBuilder();
			semBuilder.append("<div style='margin-top:10px;'>");
			idx = 0;
			for (ScenarioExampleRunDTO ex : exampleRows) {
				String label = ex.getScenarioName() == null ? ("example#" + (idx + 1)) : ex.getScenarioName();
				semBuilder.append("<div style='margin-top:10px;padding:8px;border:1px solid #eee;border-radius:6px;'>");
				semBuilder.append("<div style='font-size: 14px;margin-block: 8px'>").append(escapeHtml(label))
						.append("</div>");
				semBuilder.append(buildSemanticXmlDiff(ex)); // must return fragment-only HTML
				semBuilder.append("</div>");
				idx++;
			}
			semBuilder.append("</div>");
			html.append(
					buildSection("Semantic XML Differences", -1, "xmlSemanticPerExample", semBuilder.toString(), true)); // Size
		} // end per-example block

		// Raw logs / summary (unchanged)
		html.append(buildSection("Raw Cucumber Logs", -1, "rawLogs", buildRawLogs(dto), false));
		html.append(buildSection("Raw Cucumber Summary", -1, "rawSummary", buildRawSummary(dto), false));

		html.append("</body></html>");
		return html.toString();
	}

//	private String formatMismatchDetails(Map<String, Object> d) {
//		if (d == null)
//			return "-";
//		if (d.get("description") != null)
//			return String.valueOf(d.get("description"));
//		if (d.get("message") != null)
//			return String.valueOf(d.get("message"));
//		StringBuilder sb = new StringBuilder();
//		for (Map.Entry<String, Object> e : d.entrySet()) {
//			sb.append(e.getKey()).append(": ").append(String.valueOf(e.getValue())).append("; ");
//		}
//		return sb.toString();
//	}

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

		if (rows.isEmpty()) {
			StringBuilder html = new StringBuilder();
			html.append("<style>").append(".sem-xml{font-family:system-ui,Segoe UI,Roboto,Inter,Arial,sans-serif;}")
					.append(".same-box{border:1px solid #ccc;"
							+ "background:#f6ffed;color:#1a531b;font-size:14px;font-weight:500;}")
					.append("body.dark .same-box{background:#1a2a1a;color:#b7f7b7;border-color:#335533;}")
					.append("</style>");

			html.append("<div class='sem-xml'>")
					.append("<div class='same-box'>✅ No semantic differences found — XMLs are identical.</div>")
					.append("</div>");
			return html.toString();
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

	// Example-wise version
	private String buildSemanticXmlDiffOld(ScenarioExampleRunDTO row) {
		final boolean NEW_IS_RED = true;

		// Use row id for stable unique prefix, fallback to identityHashCode
		String uid = (row != null && row.getId() != null) ? "sem" + row.getId()
				: "sem" + Integer.toHexString(System.identityHashCode(row));

		String expected = row.getInputXml() == null ? "" : row.getInputXml().trim();
		String actual = row.getOutputXml() == null ? "" : row.getOutputXml().trim();

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

		// --- Pass 1: collect differences ---
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
						bestNamespaceDiffs.put(key, d);
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
				if (bestNamespaceDiffs.get(key) != d) {
					continue;
				}
			}

			Row r = new Row();
			r.typeKey = type.name();
			r.typeFriendly = friendly.containsKey(r.typeKey) ? friendly.get(r.typeKey) : "Other change";
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

		if (rows.isEmpty()) {
			StringBuilder html = new StringBuilder();
			html.append("<style>")
					.append(".sem-xml{font-family:system-ui,Segoe UI,Roboto,Inter,Arial,sans-serif;padding:12px;}")
					.append(".same-box{border:1px solid #ccc;border-radius:6px;padding:12px;"
							+ "background:#f6ffed;color:#1a531b;font-size:14px;font-weight:500;}")
					.append("body.dark .same-box{background:#1a2a1a;color:#b7f7b7;border-color:#335533;}")
					.append("</style>");
			html.append("<div class='sem-xml'>")
					.append("<div class='same-box'>✅ No semantic differences found — XMLs are identical.</div>")
					.append("</div>");
			return html.toString();
		}

		// grouping & counts
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

		// colors (kept)
		final String GREEN_LIGHT_BG = "#e6ffec", GREEN_LIGHT_FG = "#1f3321";
		final String RED_LIGHT_BG = "#ffe6e6", RED_LIGHT_FG = "#331f1f";
		final String GREEN_DARK_BG = "#1f3321", GREEN_DARK_FG = "#d9f3db";
		final String RED_DARK_BG = "#331f1f", RED_DARK_FG = "#ffd6d6";

		String expectedLightBg = NEW_IS_RED ? GREEN_LIGHT_BG : RED_LIGHT_BG;
		String expectedLightFg = NEW_IS_RED ? GREEN_LIGHT_FG : RED_LIGHT_FG;
		String actualLightBg = NEW_IS_RED ? RED_LIGHT_BG : GREEN_LIGHT_BG;
		String actualLightFg = NEW_IS_RED ? RED_LIGHT_FG : GREEN_LIGHT_FG;

		String expectedDarkBg = NEW_IS_RED ? GREEN_DARK_BG : RED_DARK_BG;
		String expectedDarkFg = NEW_IS_RED ? GREEN_DARK_FG : RED_DARK_FG;
		String actualDarkBg = NEW_IS_RED ? RED_DARK_BG : GREEN_DARK_BG;
		String actualDarkFg = NEW_IS_RED ? RED_DARK_FG : GREEN_DARK_FG;

		StringBuilder html = new StringBuilder();

		// CSS block (kept) - no id collisions because JS selectors below are namespaced
		html.append("<style>").append(":root{--hdr-bg:#f5f7fa;--hdr-fg:#222;--border:#d0d7de;")
				.append("--tbl-head-bg:#f0f2f5;--tbl-head-fg:#222;--tbl-row-bg:#fff;--tbl-row-alt:#fafafa;")
				.append("--chip-fg:#000;").append("--expected-bg:").append(expectedLightBg).append(";--expected-fg:")
				.append(expectedLightFg).append(";").append("--actual-bg:").append(actualLightBg)
				.append(";--actual-fg:").append(actualLightFg).append(";")
				.append("--type-s:55%;--type-l:88%;--type-text-s:45%;--type-text-l:28%;")
				.append("--tag-s:50%;--tag-l:90%;--tag-text-s:40%;--tag-text-l:26%;")
				.append("--grp-tint-light:12%;--type-tint-light:10%;")
				.append("--icon-fg:#4a5568;--icon-bg:transparent;--icon-hover-bg:rgba(0,0,0,0.05);")
				.append("--diff-font:12.5px;}").append("body.dark{--hdr-bg:#2f333a;--hdr-fg:#ddd;--border:#4a4f55;")
				.append("--tbl-head-bg:#1f2228;--tbl-head-fg:#ddd;--tbl-row-bg:#2a2d33;--tbl-row-alt:#26292f;--chip-fg:#fff;")
				.append("--expected-bg:").append(expectedDarkBg).append(";--expected-fg:").append(expectedDarkFg)
				.append(";").append("--actual-bg:").append(actualDarkBg).append(";--actual-fg:").append(actualDarkFg)
				.append(";").append("--type-s:35%;--type-l:26%;--type-text-s:35%;--type-text-l:78%;")
				.append("--tag-s:32%;--tag-l:28%;--tag-text-s:35%;--tag-text-l:80%;")
				.append("--grp-tint-dark:18%;--type-tint-dark:15%;--icon-fg:#cbd5e1;--icon-bg:transparent;--icon-hover-bg:rgba(255,255,255,0.08);--diff-font:12px;}")
				.append(".valL{background:var(--expected-bg)!important;color:var(--expected-fg)!important;}")
				.append(".valR{background:var(--actual-bg)!important;color:var(--actual-fg)!important;}")
				.append(".sem-xml{font-family:system-ui,Segoe UI,Roboto,Inter,Arial,sans-serif}")
				.append(".toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:8px 0;border-bottom:1px solid var(--border)}")
				.append(".toolbar .spacer{flex:1}")
				.append(".bar-title{font-weight:600;color:var(--hdr-fg);margin-right:4px;font-size:13px;opacity:.9}")
				.append(".chip{border-radius:999px;padding:4px 10px;font-size:12px;cursor:pointer;font-weight:600;color:var(--chip-fg);border:1px solid rgba(0,0,0,.04);}")
				.append(".chip[data-on='0']{opacity:.45;filter:grayscale(25%)}")
				.append(".type-chip{background:hsl(var(--chip-h), var(--type-s), var(--type-l));}")
				.append(".tag-chip{background:hsl(var(--chip-h), var(--tag-s), var(--tag-l));}")
				.append(".iconbtn{border:none;background:var(--icon-bg);color:var(--icon-fg);font-size:14px;border-radius:6px;cursor:pointer;padding:4px 8px;transition:background 0.2s;display:flex;align-items:center;gap:4px}")
				.append(".iconbtn:hover{background:var(--icon-hover-bg)}")
				.append(".iconbtn svg{width:14px;height:14px;display:inline-block;}")
				.append(".tbl{width:100%;border-collapse:collapse;margin-top:8px}")
				.append(".tbl th,.tbl td{border:1px solid var(--border);padding:6px 8px;vertical-align:top}")
				.append(".tbl thead th{color:var(--tbl-head-fg);background:#f2f4f7;text-align:left}")
				.append("body.dark .tbl thead th{color:var(--tbl-head-fg);background:#333;text-align:left}")
				.append(".grp{cursor:pointer;font-weight:600;color:var(--hdr-fg);background:hsl(var(--chip-h), var(--tag-s), calc(var(--tag-l) - var(--grp-tint-light)));}")
				.append("body.dark .grp{background:hsl(var(--chip-h), var(--tag-s), calc(var(--tag-l) + var(--grp-tint-dark)));}")
				.append(".row td:first-child{background:hsl(var(--chip-h), var(--type-s), calc(var(--type-l) - var(--type-tint-light)));}")
				.append("body.dark .row td:first-child{background:hsl(var(--chip-h), var(--type-s), calc(var(--type-l) + var(--type-tint-dark)));}")
				.append(".tbl tbody tr:nth-child(even):not(.grp){background:#f4f4f4;} ")
				.append("body.dark .tbl tbody tr:nth-child(even):not(.grp){background:#383838;} ")
				.append(".path{font-family:monospace;font-size:12px;opacity:.8;color:var(--hdr-fg)}")
				.append(".type-tint{background:hsl(var(--chip-h), var(--type-s), var(--type-l));color:var(--chip-fg);font-weight:700;font-size:smaller;padding:2px 6px;border-radius:6px;display:inline-block}")
				.append(".tag-tint{background:hsl(var(--chip-h), var(--tag-s), var(--tag-l));color:var(--chip-fg);font-weight:700;;font-size:smaller;padding:2px 6px;border-radius:6px;display:inline-block}")
				.append(".valL,.valR{display:inline-block;white-space:pre-wrap;border-radius:6px;padding:3px 7px;font-size:var(--diff-font)}")
				.append(".valL{background:var(--expected-bg);color:var(--expected-fg)}")
				.append(".valR{background:var(--actual-bg);color:var(--actual-fg)}")
				.append(".toggle-icon{display:inline-block;width:0;height:0;margin-right:6px;border-left:8px solid currentColor;border-top:5px solid transparent;border-bottom:5px solid transparent;cursor:pointer;vertical-align:middle;transition:transform 0.18s ease;}")
				.append(".grp[data-collapsed='0'] .toggle-icon{transform:rotate(90deg);} ").append("</style>");

		// Build HTML with namespaced IDs
		html.append("<div class='sem-xml' id='").append(uid).append("'>");

		// Tag Filter Bar
		html.append("<div class='toolbar' id='").append(uid)
				.append("-tagBar'><span class='bar-title'>Parent Tag Filter</span>");
		for (String p : parentOrder) {
			int hue = tagHue.get(p);
			html.append("<span class='chip tag-chip' style='--chip-h:").append(hue)
					.append("' data-kind='tag' data-key=\"").append(escapeHtmlAttr(p)).append("\" data-on='1'>")
					.append(escapeHtml(p)).append("</span>");
		}
		html.append("<span class='spacer'></span>").append("<button class='iconbtn' id='").append(uid)
				.append("-expAll' title='Expand all'>")
				.append("<svg viewBox='0 0 24 24'><path fill='currentColor' d='M8 5l8 7-8 7z'/></svg> Expand</button>")
				.append("<button class='iconbtn' id='").append(uid).append("-colAll' title='Collapse all'>")
				.append("<svg viewBox='0 0 24 24' style='transform:rotate(180deg);'><path fill='currentColor' d='M8 5l8 7-8 7z'/></svg> Collapse</button>")
				.append("</div>");

		// Type Filter Bar
		html.append("<div class='toolbar' id='").append(uid)
				.append("-typeBar'><span class='bar-title'>Type Filter</span>");
		for (Map.Entry<String, Long> e : typeCounts.entrySet()) {
			String label = e.getKey();
			int hue = typeHue.get(label);
			html.append("<span class='chip type-chip' style='--chip-h:").append(hue)
					.append("' data-kind='type' data-key=\"").append(escapeHtmlAttr(label)).append("\" data-on='1'>")
					.append(escapeHtml(label)).append(" <span style='opacity:.8'></span></span>");
		}
		html.append("</div>");

		// Table (namespaced id)
		String tableId = uid + "-semTbl";
		html.append("<table class='tbl' id='").append(tableId)
				.append("'><thead><tr><th>Type</th><th>Expected</th><th>Actual</th></tr></thead><tbody>");
		int gid = 0;
		for (Map.Entry<String, List<Row>> g : byGroup.entrySet()) {
			String groupKey = g.getKey();
			String groupId = uid + "-g" + (++gid);
			int ghue = tagHue.get(groupKey);

			html.append("<tr class='grp' data-group='").append(groupId).append("' data-collapsed='0'>")
					.append("<td colspan='3'><span class='toggle-icon'></span>")
					.append("<span class='path'>Element: </span>").append("<span class='tag-tint' style='--chip-h:")
					.append(ghue).append("'>").append(escapeHtml(groupKey)).append("</span></td></tr>");

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

		// Namespaced JS for filtering/expand/collapse (operates only within this UID)
		html.append("<script>(function(){")
				// helper to query inside specific container
				.append("var root=document.getElementById('").append(uid).append("');")
				.append("function q(sel){return Array.prototype.slice.call(root.querySelectorAll(sel));}")
				.append("function applyFilters(){").append("var activeTypes=new Set(q('#").append(uid)
				.append("-typeBar .chip[data-on=\"1\"]').map(function(c){return c.getAttribute('data-key');}));")
				.append("var activeTags=new Set(q('#").append(uid)
				.append("-tagBar .chip[data-on=\"1\"]').map(function(c){return c.getAttribute('data-key');}));")
				.append("var parentCounts={}, typeCounts={};").append("q('#").append(tableId)
				.append(" tbody tr.row').forEach(function(r){")
				.append("var t=r.getAttribute('data-type'); var p=r.getAttribute('data-parent');")
				.append("var visible=(activeTypes.has(t) && activeTags.has(p));")
				.append("r.style.display = visible ? 'table-row' : 'none';")
				.append("if(visible){ parentCounts[p]=(parentCounts[p]||0)+1; typeCounts[t]=(typeCounts[t]||0)+1; }")
				.append("});")
				// parent group visibility
				.append("q('#").append(tableId).append(" tbody tr.grp').forEach(function(g){")
				.append("var parentKey=(g.querySelector('.tag-tint')||{}).textContent; parentKey=parentKey?parentKey.trim():'';")
				.append("var hasVisible=(parentCounts[parentKey]||0)>0;")
				.append("g.style.display = (activeTags.has(parentKey) && hasVisible) ? 'table-row' : 'none';")
				.append("});")
				// update tag counts
				.append("q('#").append(uid)
				.append("-tagBar .chip').forEach(function(ch){ var key=ch.getAttribute('data-key'); var count=parentCounts[key]||0; var span=ch.querySelector('.count'); if(!span){ span=document.createElement('span'); span.className='count'; span.style.opacity='0.8'; span.style.marginLeft='4px'; ch.appendChild(span);} span.textContent='('+count+')'; });")
				// update type counts
				.append("q('#").append(uid)
				.append("-typeBar .chip').forEach(function(ch){ var key=ch.getAttribute('data-key'); var count=typeCounts[key]||0; var span=ch.querySelector('.count'); if(!span){ span=document.createElement('span'); span.className='count'; span.style.opacity='0.8'; span.style.marginLeft='4px'; ch.appendChild(span);} span.textContent='('+count+')'; });")
				.append("}") // end applyFilters

				// chip toggle
				.append("q('#").append(uid)
				.append(" .chip').forEach(function(ch){ ch.addEventListener('click', function(){ ch.setAttribute('data-on', ch.getAttribute('data-on')==='1'?'0':'1'); applyFilters(); }); });")

				// expand all
				.append("document.getElementById('").append(uid)
				.append("-expAll').onclick=function(){ applyFilters(); q('#").append(tableId)
				.append(" tbody tr.grp').forEach(function(g){ var id=g.getAttribute('data-group'); var rows=q('#")
				.append(tableId)
				.append(" tbody tr.row[data-group=\"'+id+'\"]'); rows.forEach(function(r){ if(r.style.display!=='none') r.style.display='table-row'; }); g.setAttribute('data-collapsed','0'); }); };")

				// collapse all
				.append("document.getElementById('").append(uid).append("-colAll').onclick=function(){ q('#")
				.append(tableId)
				.append(" tbody tr.grp').forEach(function(g){ var id=g.getAttribute('data-group'); var rows=q('#")
				.append(tableId)
				.append(" tbody tr.row[data-group=\"'+id+'\"]'); rows.forEach(function(r){ if(r.style.display!=='none') r.style.display='none'; }); g.setAttribute('data-collapsed','1'); }); };")

				// parent row toggle
				.append("q('#").append(tableId)
				.append(" tbody tr.grp').forEach(function(g){ g.addEventListener('click', function(){ var id=g.getAttribute('data-group'); var rows=q('#")
				.append(tableId)
				.append(" tbody tr.row[data-group=\"'+id+'\"]'); var isCollapsed=g.getAttribute('data-collapsed')==='1'; if(isCollapsed){ rows.forEach(function(r){ r.style.display='table-row'; }); g.setAttribute('data-collapsed','0'); } else { rows.forEach(function(r){ r.style.display='none'; }); g.setAttribute('data-collapsed','1'); } }); });");

		// init
		html.append("applyFilters();");
		html.append("})();</script>");

		return html.toString();
	}

	private String buildSemanticXmlDiff(ScenarioExampleRunDTO row) {
		final boolean NEW_IS_RED = true;

		// Use row id for stable unique prefix, fallback to identityHashCode
		String uid = (row != null && row.getId() != null) ? "sem" + row.getId()
				: "sem" + Integer.toHexString(System.identityHashCode(row));

		// raw xml (may be null)
		String expectedRaw = row.getInputXml() == null ? "" : row.getInputXml().trim();
		String actualRaw = row.getOutputXml() == null ? "" : row.getOutputXml().trim();

		// Guard clause: missing XML content
		if (expectedRaw.isEmpty() || actualRaw.isEmpty()) {
			StringBuilder msg = new StringBuilder("Semantic comparison skipped — ");
			if (expectedRaw.isEmpty() && actualRaw.isEmpty()) {
				msg.append("both Expected and Actual XML are empty or missing.");
			} else if (expectedRaw.isEmpty()) {
				msg.append("Expected XML is missing, so comparison could not be performed.");
			} else if (actualRaw.isEmpty()) {
				msg.append("Actual XML is missing, so comparison could not be performed.");
			}
			return noScenarioAligned(msg.toString(), 5);
		}

		// === NEW: remove all SKIPPED_TAGS from both XML strings BEFORE comparing ===
		// This removes elements like:
		// <CreDtTm>...</CreDtTm>
		// <ns:CreDtTm attr="...">...</ns:CreDtTm>
		// <EndToEndId/>
		// It uses the SKIPPED_TAGS set defined at class-level.
		String expected = expectedRaw;
		String actual = actualRaw;
		try {
			if (SKIPPED_TAGS != null && !SKIPPED_TAGS.isEmpty()) {
				for (String tag : SKIPPED_TAGS) {
					if (tag == null || tag.trim().isEmpty())
						continue;
					// remove full element with content (multi-line, namespace prefix tolerant)
					// (?s) makes '.' match newlines
					String openCloseRegex = "(?s)<(?:[^:\\s>]+:)?" + Pattern.quote(tag)
							+ "\\b[^>]*>.*?</(?:[^:\\s>]+:)?" + Pattern.quote(tag) + "\\s*>";
					expected = expected.replaceAll(openCloseRegex, "");
					actual = actual.replaceAll(openCloseRegex, "");

					// remove self-closing forms: <ns:EndToEndId .../> or <EndToEndId/>
					String selfCloseRegex = "(?s)<(?:[^:\\s>]+:)?" + Pattern.quote(tag) + "\\b[^>]*/\\s*>"; // allow
																											// attributes
																											// and
																											// whitespace
																											// before />
					expected = expected.replaceAll(selfCloseRegex, "");
					actual = actual.replaceAll(selfCloseRegex, "");
				}
			}
		} catch (Exception ex) {
			// defensive: if something goes wrong with regex processing, fall back to raw
			// inputs
			expected = expectedRaw;
			actual = actualRaw;
		}

		// Trim again after removals
		expected = expected.trim();
		actual = actual.trim();

		// If removing skipped tags makes one side empty, proceed — diff will handle it.
		// Build the diff from the sanitized XMLs.
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

		// --- Pass 1: collect differences ---
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
						bestNamespaceDiffs.put(key, d);
					}
				}
			}
		}

		// prepare placeholder pattern (class-level PLACEHOLDER_TOKEN assumed)
		Pattern placeholderPattern = PLACEHOLDER_TOKEN;

		// --- Pass 3: build rows, skipping duplicate/inherited namespace diffs and
		// honoring placeholders ---
		List<Row> rows = new ArrayList<>();
		for (Difference d : all) {
			Comparison c = d.getComparison();
			ComparisonType type = c.getType();

			if (type == ComparisonType.NAMESPACE_URI || type == ComparisonType.NAMESPACE_PREFIX) {
				String control = String.valueOf(c.getControlDetails().getValue());
				String test = String.valueOf(c.getTestDetails().getValue());
				String key = type.name() + ":" + control + "→" + test;
				if (bestNamespaceDiffs.get(key) != d) {
					continue;
				}
			}

			Row r = new Row();
			r.typeKey = type.name();
			r.typeFriendly = friendly.containsKey(r.typeKey) ? friendly.get(r.typeKey) : "Other change";
			r.left = String.valueOf(c.getControlDetails().getValue());
			r.right = String.valueOf(c.getTestDetails().getValue());

			String xp = c.getControlDetails().getXPath() != null ? c.getControlDetails().getXPath()
					: c.getTestDetails().getXPath();
			if (xp == null)
				xp = "/";
			String[] parts = xp.split("(?=\\/[^\\/]+\\[\\d+\\])");
			r.topGroup = parts.length > 1 ? parts[0] + parts[1] : parts[0];
			r.subPath = xp;

			// --- skip differences involving placeholder tokens (existing behavior) ---
			boolean leftHasPlaceholder = r.left != null && placeholderPattern.matcher(r.left).find();
			boolean rightHasPlaceholder = r.right != null && placeholderPattern.matcher(r.right).find();
			if (leftHasPlaceholder || rightHasPlaceholder) {
				continue;
			}

			rows.add(r);
		}

		if (rows.isEmpty()) {
			StringBuilder html = new StringBuilder();
			html.append("<style>")
					.append(".sem-xml{font-family:system-ui,Segoe UI,Roboto,Inter,Arial,sans-serif;padding:12px;}")
					.append(".same-box{border:1px solid #ccc;border-radius:6px;padding:12px;"
							+ "background:#f6ffed;color:#1a531b;font-size:14px;font-weight:500;}")
					.append("body.dark .same-box{background:#1a2a1a;color:#b7f7b7;border-color:#335533;}")
					.append("</style>");
			html.append("<div class='sem-xml'>")
					.append("<div class='same-box'>✅ No semantic differences found — XMLs are identical.</div>")
					.append("</div>");
			return html.toString();
		}

		// grouping & counts
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

		// colors (kept)
		final String GREEN_LIGHT_BG = "#e6ffec", GREEN_LIGHT_FG = "#1f3321";
		final String RED_LIGHT_BG = "#ffe6e6", RED_LIGHT_FG = "#331f1f";
		final String GREEN_DARK_BG = "#1f3321", GREEN_DARK_FG = "#d9f3db";
		final String RED_DARK_BG = "#331f1f", RED_DARK_FG = "#ffd6d6";

		String expectedLightBg = NEW_IS_RED ? GREEN_LIGHT_BG : RED_LIGHT_BG;
		String expectedLightFg = NEW_IS_RED ? GREEN_LIGHT_FG : RED_LIGHT_FG;
		String actualLightBg = NEW_IS_RED ? RED_LIGHT_BG : GREEN_LIGHT_BG;
		String actualLightFg = NEW_IS_RED ? RED_LIGHT_FG : GREEN_LIGHT_FG;

		String expectedDarkBg = NEW_IS_RED ? GREEN_DARK_BG : RED_DARK_BG;
		String expectedDarkFg = NEW_IS_RED ? GREEN_DARK_FG : RED_DARK_FG;
		String actualDarkBg = NEW_IS_RED ? RED_DARK_BG : GREEN_DARK_BG;
		String actualDarkFg = NEW_IS_RED ? RED_DARK_FG : GREEN_DARK_FG;

		StringBuilder html = new StringBuilder();

		// CSS block (kept) - no id collisions because JS selectors below are namespaced
		html.append("<style>").append(":root{--hdr-bg:#f5f7fa;--hdr-fg:#222;--border:#d0d7de;")
				.append("--tbl-head-bg:#f0f2f5;--tbl-head-fg:#222;--tbl-row-bg:#fff;--tbl-row-alt:#fafafa;")
				.append("--chip-fg:#000;").append("--expected-bg:").append(expectedLightBg).append(";--expected-fg:")
				.append(expectedLightFg).append(";").append("--actual-bg:").append(actualLightBg)
				.append(";--actual-fg:").append(actualLightFg).append(";")
				.append("--type-s:55%;--type-l:88%;--type-text-s:45%;--type-text-l:28%;")
				.append("--tag-s:50%;--tag-l:90%;--tag-text-s:40%;--tag-text-l:26%;")
				.append("--grp-tint-light:12%;--type-tint-light:10%;")
				.append("--icon-fg:#4a5568;--icon-bg:transparent;--icon-hover-bg:rgba(0,0,0,0.05);")
				.append("--diff-font:12.5px;}").append("body.dark{--hdr-bg:#2f333a;--hdr-fg:#ddd;--border:#4a4f55;")
				.append("--tbl-head-bg:#1f2228;--tbl-head-fg:#ddd;--tbl-row-bg:#2a2d33;--tbl-row-alt:#26292f;--chip-fg:#fff;")
				.append("--expected-bg:").append(expectedDarkBg).append(";--expected-fg:").append(expectedDarkFg)
				.append(";").append("--actual-bg:").append(actualDarkBg).append(";--actual-fg:").append(actualDarkFg)
				.append(";").append("--type-s:35%;--type-l:26%;--type-text-s:35%;--type-text-l:78%;")
				.append("--tag-s:32%;--tag-l:28%;--tag-text-s:35%;--tag-text-l:80%;")
				.append("--grp-tint-dark:18%;--type-tint-dark:15%;--icon-fg:#cbd5e1;--icon-bg:transparent;--icon-hover-bg:rgba(255,255,255,0.08);--diff-font:12px;}")
				.append(".valL{background:var(--expected-bg)!important;color:var(--expected-fg)!important;}")

				.append(".valR{background:var(--actual-bg)!important;color:var(--actual-fg)!important;}")

				.append(".sem-xml{font-family:system-ui,Segoe UI,Roboto,Inter,Arial,sans-serif}")
				.append(".toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:8px 0;border-bottom:1px solid var(--border)}")
				.append(".toolbar .spacer{flex:1}")
				.append(".bar-title{font-weight:600;color:var(--hdr-fg);margin-right:4px;font-size:13px;opacity:.9}")
				.append(".chip{border-radius:999px;padding:4px 10px;font-size:12px;cursor:pointer;font-weight:600;color:var(--chip-fg);border:1px solid rgba(0,0,0,.04);}")
				.append(".chip[data-on='0']{opacity:.45;filter:grayscale(25%)}")
				.append(".type-chip{background:hsl(var(--chip-h), var(--type-s), var(--type-l));}")
				.append(".tag-chip{background:hsl(var(--chip-h), var(--tag-s), var(--tag-l));}")
				.append(".iconbtn{border:none;background:var(--icon-bg);color:var(--icon-fg);font-size:14px;border-radius:6px;cursor:pointer;padding:4px 8px;transition:background 0.2s;display:flex;align-items:center;gap:4px}")
				.append(".iconbtn:hover{background:var(--icon-hover-bg)}")
				.append(".iconbtn svg{width:14px;height:14px;display:inline-block;}")
				.append(".tbl{width:100%;border-collapse:collapse;margin-top:8px}")
				.append(".tbl th,.tbl td{border:1px solid var(--border);padding:6px 8px;vertical-align:top}")
				.append(".tbl thead th{color:var(--tbl-head-fg);background:#f2f4f7;text-align:left}")
				.append("body.dark .tbl thead th{color:var(--tbl-head-fg);background:#333;text-align:left}")
				.append(".grp{cursor:pointer;font-weight:600;color:var(--hdr-fg);background:hsl(var(--chip-h), var(--tag-s), calc(var(--tag-l) - var(--grp-tint-light)));}")
				.append("body.dark .grp{background:hsl(var(--chip-h), var(--tag-s), calc(var(--tag-l) + var(--grp-tint-dark)));}")
				.append(".row td:first-child{background:hsl(var(--chip-h), var(--type-s), calc(var(--type-l) - var(--type-tint-light)));}")
				.append("body.dark .row td:first-child{background:hsl(var(--chip-h), var(--type-s), calc(var(--type-l) + var(--type-tint-dark)));}")
				.append(".tbl tbody tr:nth-child(even):not(.grp){background:#f4f4f4;} ")
				.append("body.dark .tbl tbody tr:nth-child(even):not(.grp){background:#383838;} ")
				.append(".path{font-family:monospace;font-size:12px;opacity:.8;color:var(--hdr-fg)}")
				.append(".type-tint{background:hsl(var(--chip-h), var(--type-s), var(--type-l));color:var(--chip-fg);font-weight:700;font-size:smaller;padding:2px 6px;border-radius:6px;display:inline-block}")
				.append(".tag-tint{background:hsl(var(--chip-h), var(--tag-s), var(--tag-l));color:var(--chip-fg);font-weight:700;;font-size:smaller;padding:2px 6px;border-radius:6px;display:inline-block}")
				.append(".valL,.valR{display:inline-block;white-space:pre-wrap;border-radius:6px;padding:3px 7px;font-size:var(--diff-font)}")
				.append(".valL{background:var(--expected-bg);color:var(--expected-fg)}")
				.append(".valR{background:var(--actual-bg);color:var(--actual-fg)}")
				.append(".toggle-icon{display:inline-block;width:0;height:0;margin-right:6px;border-left:8px solid currentColor;border-top:5px solid transparent;border-bottom:5px solid transparent;cursor:pointer;vertical-align:middle;transition:transform 0.18s ease;}")
				.append(".grp[data-collapsed='0'] .toggle-icon{transform:rotate(90deg);} ").append("</style>");

		// Build HTML with namespaced IDs
		html.append("<div class='sem-xml' id='").append(uid).append("'>");

		// Tag Filter Bar
		html.append("<div class='toolbar' id='").append(uid)
				.append("-tagBar'><span class='bar-title'>Parent Tag Filter</span>");
		for (String p : parentOrder) {
			int hue = tagHue.get(p);
			html.append("<span class='chip tag-chip' style='--chip-h:").append(hue)
					.append("' data-kind='tag' data-key=\"").append(escapeHtmlAttr(p)).append("\" data-on='1'>")
					.append(escapeHtml(p)).append("</span>");
		}
		html.append("<span class='spacer'></span>").append("<button class='iconbtn' id='").append(uid)
				.append("-expAll' title='Expand all'>")
				.append("<svg viewBox='0 0 24 24'><path fill='currentColor' d='M8 5l8 7-8 7z'/></svg> Expand</button>")
				.append("<button class='iconbtn' id='").append(uid).append("-colAll' title='Collapse all'>")
				.append("<svg viewBox='0 0 24 24' style='transform:rotate(180deg);'><path fill='currentColor' d='M8 5l8 7-8 7z'/></svg> Collapse</button>")
				.append("</div>");

		// Type Filter Bar
		html.append("<div class='toolbar' id='").append(uid)
				.append("-typeBar'><span class='bar-title'>Type Filter</span>");
		for (Map.Entry<String, Long> e : typeCounts.entrySet()) {
			String label = e.getKey();
			int hue = typeHue.get(label);
			html.append("<span class='chip type-chip' style='--chip-h:").append(hue)
					.append("' data-kind='type' data-key=\"").append(escapeHtmlAttr(label)).append("\" data-on='1'>")
					.append(escapeHtml(label)).append(" <span style='opacity:.8'></span></span>");
		}
		html.append("</div>");

		// Table (namespaced id)
		String tableId = uid + "-semTbl";
		html.append("<table class='tbl' id='").append(tableId)
				.append("'><thead><tr><th>Type</th><th>Expected</th><th>Actual</th></tr></thead><tbody>");
		int gid = 0;
		for (Map.Entry<String, List<Row>> g : byGroup.entrySet()) {
			String groupKey = g.getKey();
			String groupId = uid + "-g" + (++gid);
			int ghue = tagHue.get(groupKey);

			html.append("<tr class='grp' data-group='").append(groupId).append("' data-collapsed='0'>")
					.append("<td colspan='3'><span class='toggle-icon'></span>")
					.append("<span class='path'>Element: </span>").append("<span class='tag-tint' style='--chip-h:")
					.append(ghue).append("'>").append(escapeHtml(groupKey)).append("</span></td></tr>");

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

		// Namespaced JS for filtering/expand/collapse (operates only within this UID)
		html.append("<script>(function(){")
				// helper to query inside specific container
				.append("var root=document.getElementById('").append(uid).append("');")
				.append("function q(sel){return Array.prototype.slice.call(root.querySelectorAll(sel));}")
				.append("function applyFilters(){").append("var activeTypes=new Set(q('#").append(uid)
				.append("-typeBar .chip[data-on=\"1\"]').map(function(c){return c.getAttribute('data-key');}));")
				.append("var activeTags=new Set(q('#").append(uid)
				.append("-tagBar .chip[data-on=\"1\"]').map(function(c){return c.getAttribute('data-key');}));")
				.append("var parentCounts={}, typeCounts={};").append("q('#").append(tableId)
				.append(" tbody tr.row').forEach(function(r){")
				.append("var t=r.getAttribute('data-type'); var p=r.getAttribute('data-parent');")
				.append("var visible=(activeTypes.has(t) && activeTags.has(p));")
				.append("r.style.display = visible ? 'table-row' : 'none';")
				.append("if(visible){ parentCounts[p]=(parentCounts[p]||0)+1; typeCounts[t]=(typeCounts[t]||0)+1; }")
				.append("});")
				// parent group visibility
				.append("q('#").append(tableId).append(" tbody tr.grp').forEach(function(g){")
				.append("var parentKey=(g.querySelector('.tag-tint')||{}).textContent; parentKey=parentKey?parentKey.trim():'';")
				.append("var hasVisible=(parentCounts[parentKey]||0)>0;")
				.append("g.style.display = (activeTags.has(parentKey) && hasVisible) ? 'table-row' : 'none';")
				.append("});")
				// update tag counts
				.append("q('#").append(uid)
				.append("-tagBar .chip').forEach(function(ch){ var key=ch.getAttribute('data-key'); var count=parentCounts[key]||0; var span=ch.querySelector('.count'); if(!span){ span=document.createElement('span'); span.className='count'; span.style.opacity='0.8'; span.style.marginLeft='4px'; ch.appendChild(span);} span.textContent='('+count+')'; });")
				// update type counts
				.append("q('#").append(uid)
				.append("-typeBar .chip').forEach(function(ch){ var key=ch.getAttribute('data-key'); var count=typeCounts[key]||0; var span=ch.querySelector('.count'); if(!span){ span=document.createElement('span'); span.className='count'; span.style.opacity='0.8'; span.style.marginLeft='4px'; ch.appendChild(span);} span.textContent='('+count+')'; });")
				.append("}") // end applyFilters

				// chip toggle
				.append("q('#").append(uid)
				.append(" .chip').forEach(function(ch){ ch.addEventListener('click', function(){ ch.setAttribute('data-on', ch.getAttribute('data-on')==='1'?'0':'1'); applyFilters(); }); });")

				// expand all
				.append("document.getElementById('").append(uid)
				.append("-expAll').onclick=function(){ applyFilters(); q('#").append(tableId)
				.append(" tbody tr.grp').forEach(function(g){ var id=g.getAttribute('data-group'); var rows=q('#")
				.append(tableId)
				.append(" tbody tr.row[data-group=\"'+id+'\"]'); rows.forEach(function(r){ if(r.style.display!=='none') r.style.display='table-row'; }); g.setAttribute('data-collapsed','0'); }); };")

				// collapse all
				.append("document.getElementById('").append(uid).append("-colAll').onclick=function(){ q('#")
				.append(tableId)
				.append(" tbody tr.grp').forEach(function(g){ var id=g.getAttribute('data-group'); var rows=q('#")
				.append(tableId)
				.append(" tbody tr.row[data-group=\"'+id+'\"]'); rows.forEach(function(r){ if(r.style.display!=='none') r.style.display='none'; }); g.setAttribute('data-collapsed','1'); }); };")

				// parent row toggle
				.append("q('#").append(tableId)
				.append(" tbody tr.grp').forEach(function(g){ g.addEventListener('click', function(){ var id=g.getAttribute('data-group'); var rows=q('#")
				.append(tableId)
				.append(" tbody tr.row[data-group=\"'+id+'\"]'); var isCollapsed=g.getAttribute('data-collapsed')==='1'; if(isCollapsed){ rows.forEach(function(r){ r.style.display='table-row'; }); g.setAttribute('data-collapsed','0'); } else { rows.forEach(function(r){ r.style.display='none'; }); g.setAttribute('data-collapsed','1'); } }); });");

		// init
		html.append("applyFilters();");
		html.append("})();</script>");

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
		List<Map<String, Object>> unexecuted = Collections.emptyList();
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

	@SuppressWarnings("unchecked")
	private String buildXmlDifferencesFixed(ScenarioExampleRunDTO dto, TestCaseRunHistoryDTO dto2) {
		// Defensive: dto may be null
		if (dto == null) {
			return noScenarioAligned("No example provided", 5);
		}

		// If differences property isn't a list, still allow showing headers/values
		List<Map<String, Object>> differences = new ArrayList<>();
		Object diffsObj = dto.getDifferences();
		if (diffsObj instanceof List) {
			for (Object o : (List<?>) diffsObj) {
				if (o instanceof Map) {
					// noinspection unchecked
					differences.add((Map<String, Object>) o);
				}
			}
		}

		// parse unexecutedScenarios JSON from dto2 defensively (kept from your earlier
		// implementation)
		List<Map<String, Object>> unexecuted = Collections.emptyList();
		try {
			String rawJson = dto2 == null ? null : dto2.getUnexecutedScenarios();
			if (rawJson != null && !rawJson.trim().isEmpty()) {
				ObjectMapper mapper = new ObjectMapper();
				unexecuted = mapper.readValue(rawJson, new TypeReference<List<Map<String, Object>>>() {
				});
			}
		} catch (Exception e) {
			logger.warn("Failed to parse unexecutedScenarios JSON: {}", e.getMessage());
		}

		// Combined empty check
		if (differences.isEmpty() && (unexecuted == null || unexecuted.isEmpty())) {
			return noScenarioAligned("No XML differences or unexecuted scenarios found", 5);
		}

		// Build the table with the requested columns:
		// Scenario | Scenario Type | Example Header | Example Values | Differences
		StringBuilder sb = new StringBuilder();
		sb.append("<table class='scenario-table'><colgroup><col/><col/><col/><col/><col/></colgroup>")
				.append("<thead><tr>").append("<th>Scenario</th>").append("<th>Scenario Type</th>")
				.append("<th>Example Header</th>").append("<th>Example Values</th>").append("<th>Differences</th>")
				.append("</tr></thead><tbody>");

		// Single row from given dto
		String scenarioName = dto.getScenarioName() == null ? "-" : dto.getScenarioName();
		String scenarioType = dto.getScenarioType() == null ? "-" : dto.getScenarioType();

		// Example header: use your helper (renders "col1, col2, col3")
		String exampleHeader = renderExampleHeader(dto);

		// Example values: use your helper (renders just values in header order or list)
		String exampleValues = renderExampleValues(dto);

		int diffCount = differences.size();

		sb.append("<tr class='").append("Passed".equalsIgnoreCase(dto.getStatus()) ? "row-pass" : "row-fail")
				.append("'><td>").append(escapeHtml(scenarioName)).append("</td>").append("<td>")
				.append(escapeHtml(scenarioType)).append("</td>").append("<td>").append(exampleHeader).append("</td>")
				.append("<td>").append(exampleValues).append("</td>").append("<td style='white-space:nowrap'>");

		if (diffCount > 0) {
			String innerId = "diffInner-single-" + (dto.getId() == null ? "0" : dto.getId());
			sb.append("<button class='view-btn' onclick=\"toggle('").append(innerId).append("')\">View</button>")
					.append("<span class='diff-badge fail' style='margin-left:6px;'>").append(diffCount)
					.append("</span>");

			// inner details (hidden)
			sb.append("</td></tr>"); // close the row first (we'll add the inner row next)
			sb.append("<tr id='").append(innerId).append("' class='inner-row' style='display:none'><td colspan='5'>")
					.append("<table class='inner-table'><thead><tr><th>XPath</th><th>Node</th><th>Type</th><th>Details</th></tr></thead><tbody>");
			for (Map<String, Object> d : differences) {
				if (d == null)
					continue;
				String xpath = escapeHtml(String.valueOf(d.getOrDefault("xpath", "-")));
				String node = escapeHtml(String.valueOf(d.getOrDefault("node", d.getOrDefault("name", "-"))));
				String type = escapeHtml(String.valueOf(d.getOrDefault("differenceType", d.getOrDefault("type", "-"))));
				String details = formatMismatchDetails(d);
				sb.append("<tr><td>").append(xpath).append("</td>").append("<td>").append(node).append("</td>")
						.append("<td>").append(type).append("</td>").append("<td>").append(escapeHtml(details))
						.append("</td></tr>");
			}
			sb.append("</tbody></table></td></tr>");
		} else {
			sb.append("-</td></tr>");
		}

		sb.append("</tbody></table>");
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
				"<table class='scenario-table'><colgroup>" + "<col/><col/><col/><col/><col/>" + "</colgroup>")
				.append("<tr><th>Scenario</th><th>Type</th><th>Example Header</th><th>Example Values</th><th>Errors</th></tr>");

		int fi = 0;
		for (Map<String, Object> s : failed) {
			String scenario = escapeHtml(String.valueOf(s.getOrDefault("scenarioName", "N/A")));

			List<String> headers = safeList(s.get("exampleHeader"));
			List<String> values = safeList(s.get("exampleValues"));
			List<String> errors = safeList(s.get("errors"));

			String errBtn = errors.isEmpty() ? "-"
					: "<button class='view-btn' onclick=\"toggle('failErr" + fi + "')\">View</button>";

			// summary row
			sb.append("<tr class='row-fail'><td>").append(scenario).append("</td><td>")
					.append(s.getOrDefault("scenarioType", "N/A")).append("</td><td>")
					.append(headers.isEmpty() ? "-" : String.join(", ", headers)).append("</td><td>")
					.append(values.isEmpty() ? "-" : String.join(", ", values)).append("</td><td>").append(errBtn)
					.append("</td></tr>");

			// expandable error row
			if (!errors.isEmpty()) {
				sb.append("<tr id='failErr").append(fi).append("' class='inner-row'><td colspan='5'>")
						.append("<div class='log-box'>").append(escapeHtml(String.join("\n", errors)))
						.append("</div></td></tr>");
			}

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
			return noScenarioAligned("No unexecuted scenarios recorder", 5);
		}

		StringBuilder sb = new StringBuilder(
				"<table class='scenario-table'><colgroup>" + "<col/><col/><col/><col/><col/>" + "</colgroup>")
				.append("<tr><th>Scenario</th><th>Type</th><th>Example Header</th><th>Example Values</th><th>Errors</th></tr>");

		int ui = 0;
		for (Map<String, Object> s : unexec) {
			String scenario = escapeHtml(String.valueOf(s.getOrDefault("scenarioName", "N/A")));

			List<String> headers = safeList(s.get("exampleHeader"));
			List<String> values = safeList(s.get("exampleValues"));

			List<String> errors = safeList(s.get("errors"));
			if (errors.isEmpty() && s.containsKey("reason")) {
				errors = Collections.singletonList(String.valueOf(s.get("reason")));
			}

			String errBtn = errors.isEmpty() ? "-"
					: "<button class='view-btn' onclick=\"toggle('unexecErr" + ui + "')\">View</button>";

			// summary row
			sb.append("<tr><td>").append(scenario).append("</td><td>").append(s.getOrDefault("scenarioType", "-"))
					.append("</td><td>").append(headers.isEmpty() ? "-" : String.join(", ", headers))
					.append("</td><td>").append(values.isEmpty() ? "-" : String.join(", ", values)).append("</td><td>")
					.append(errBtn).append("</td></tr>");

			// expandable error row
			if (!errors.isEmpty()) {
				sb.append("<tr id='unexecErr").append(ui).append("' class='inner-row'><td colspan='5'>")
						.append("<div class='log-box'>").append(escapeHtml(String.join("\n", errors)))
						.append("</div></td></tr>");
			}
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

//	private String buildRawLogs(TestCaseRunHistoryDTO dto) {
//		if (dto.getRawCucumberLogGrouped() == null || dto.getRawCucumberLogGrouped().getGroupedLogs() == null
//				|| dto.getRawCucumberLogGrouped().getGroupedLogs().isEmpty()) {
//			return noScenarioAligned("No raw cucumber logs", 5);
//		}
//
//		StringBuilder sb = new StringBuilder("<table class='scenario-table'><colgroup>"
//				+ "<col style='width:35%'><col style='width:15%'><col style='width:15%'><col style='width:25%'><col style='width:10%'>"
//				+ "</colgroup>").append("<tr><th colspan='3'>Scenario</th><th colspan='2'>Logs</th></tr>");
//
//		int li = 0;
//		for (ScenarioLogGroupDTO group : dto.getRawCucumberLogGrouped().getGroupedLogs()) {
//			String scenarioName = escapeHtml(group.getScenario());
//			sb.append("<tr><td colspan='3'>").append(scenarioName)
//					.append("</td><td colspan='2' style='white-space:nowrap'>")
//					.append("<button class='view-btn' onclick=\"toggle('log").append(li)
//					.append("')\">View</button></td></tr>");
//			sb.append("<tr id='log").append(li).append("' class='inner-row'><td colspan='5'>")
//					.append("<div class='log-box'>").append(escapeHtml(String.join("\n", group.getLog())))
//					.append("</div></td></tr>");
//			li++;
//		}
//		sb.append("</table>");
//		return sb.toString();
//	}

	private String buildRawLogs(TestCaseRunHistoryDTO dto) {
		if (dto.getRawCucumberLogGrouped() == null || dto.getRawCucumberLogGrouped().getGroupedLogs() == null
				|| dto.getRawCucumberLogGrouped().getGroupedLogs().isEmpty()) {
			return noScenarioAligned("No raw cucumber logs", 5);
		}

		StringBuilder sb = new StringBuilder();

		// Namespaced CSS
		sb.append("<style>")
				// Light mode (default, materialistic look but square corners)
				.append(".rawlogs-scenario-table {width:100%; border-collapse:collapse; margin-bottom:12px; font-size:14px; box-shadow:0 1px 3px rgba(0,0,0,0.1);} ")
				.append(".rawlogs-scenario-table th,.rawlogs-scenario-table td {border:1px solid #e0e0e0; padding:8px; text-align:left; color:#212121; background:#fff;} ")
				.append(".rawlogs-log-table {width:100%; border-collapse:collapse; font-size:14px; table-layout:fixed;} ")
				.append(".rawlogs-log-table th,.rawlogs-log-table td {border:1px solid #e0e0e0; padding:8px; vertical-align:top; text-align:left; word-wrap:break-word; white-space:pre-wrap; color:#212121; background:#fff;} ")
				.append(".rawlogs-scenario-table th, .rawlogs-log-table th {background:#f2f4f7; font-weight:600; box-shadow:inset 0 -1px 0 #e0e0e0;} ")

				// Log wrapper
				.append(".rawlogs-log-wrapper {display:flex; align-items:flex-start;} ")
				.append(".rawlogs-log-text {flex:1; display:-webkit-box; -webkit-line-clamp:5; -webkit-box-orient:vertical; overflow:hidden; text-overflow:ellipsis;} ")
				.append(".rawlogs-log-text.expanded {-webkit-line-clamp:unset;} ")

				// Toggle icon & button
				.append(".rawlogs-toggle-icon {color:#1976d2; font-size:12px; cursor:pointer; margin-left:6px; user-select:none;} ")
				.append(".rawlogs-view-btn {padding:4px 12px; background:#1976d2; color:#fff; border:none; border-radius:4px; cursor:pointer; font-size:13px; transition:background 0.3s;} ")
				.append(".rawlogs-view-btn:hover {background:#1565c0;} ")

				// Dark mode overrides
				.append("body.dark .rawlogs-scenario-table th, ").append("body.dark .rawlogs-scenario-table td, ")
				.append("body.dark .rawlogs-log-table th, ")
				.append("body.dark .rawlogs-log-table td {border:1px solid #fff; color:#f1f1f1; background:#2a2a2a;} ")
				.append("body.dark .rawlogs-scenario-table th, body.dark .rawlogs-log-table th {background:#333; color:#f8f8f8; border:1px solid #fff;} ")
				.append("body.dark .rawlogs-toggle-icon {color:#4ea3ff;} ").append("</style>");

		// Namespaced JS
		sb.append("<script>").append("function rawlogsToggleLog(elem){")
				.append("  const logDiv = elem.previousElementSibling;")
				.append("  if(logDiv.classList.contains('expanded')){")
				.append("    logDiv.classList.remove('expanded'); elem.innerHTML='&#9654;';").append("  } else {")
				.append("    logDiv.classList.add('expanded'); elem.innerHTML='&#9660;';").append("  }").append("}")
				.append("function rawlogsToggleRow(id){").append("  var row=document.getElementById(id);")
				.append("  if(!row) return;")
				.append("  row.style.display=(row.style.display==='none'||row.style.display==='')?'table-row':'none';")
				.append("}").append("</script>");

		// Outer table
		sb.append("<table class='rawlogs-scenario-table'>").append("<colgroup>").append("<col style='width:50%'>") // Scenario
																													// column
				.append("<col style='width:50%'>") // Logs column
				.append("</colgroup>").append("<tr><th>Scenario</th><th>Logs</th></tr>");

		int li = 0;
		for (ScenarioLogGroupDTO group : dto.getRawCucumberLogGrouped().getGroupedLogs()) {
			String scenarioName = escapeHtml(group.getScenario());

			// Scenario row (Scenario name + View button)
			sb.append("<tr class='rawlogs-scenario-row'>").append("<td>").append(scenarioName).append("</td>")
					.append("<td>").append("<button class='rawlogs-view-btn' onclick=\"rawlogsToggleRow('rawlog")
					.append(li).append("')\">View</button>").append("</td>").append("</tr>");

			// Expandable logs row (spans both columns)
			sb.append("<tr id='rawlog").append(li).append("' class='rawlogs-inner-row' style='display:none;'>")
					.append("<td colspan='2'>");

			// Inner log table (Steps 50% / Logs 50%)
			sb.append("<table class='rawlogs-log-table'>").append("<colgroup>").append("<col style='width:50%'>")
					.append("<col style='width:50%'>").append("</colgroup>")
					.append("<tr><th>Steps</th><th>Logs / Errors</th></tr>");

			String currentStep = null;
			StringBuilder currentLog = new StringBuilder();

			for (String line : group.getLog()) {
				String safeLine = escapeHtml(line);
				boolean isStep = safeLine.trim().matches("^(Given|When|Then|And|But)\\b.*");

				if (isStep) {
					// flush previous row
					if (currentStep != null) {
						sb.append("<tr><td>").append(currentStep).append("</td><td>");
						String logContent = currentLog.length() > 0 ? currentLog.toString() : "-";

						sb.append("<div class='rawlogs-log-wrapper'>").append("<div class='rawlogs-log-text")
								.append(logContent.length() > 300 ? "" : " expanded").append("'>").append(logContent)
								.append("</div>");

						if (logContent.length() > 300) {
							sb.append("<span class='rawlogs-toggle-icon' onclick=\"toggleLog(this)\">&#9654;</span>");
						}
						sb.append("</div></td></tr>");
					}

					// Step text → only keep the "Given / When / Then ..." part
					currentStep = safeLine.replaceAll("\\s*#.*$", "");
					currentLog.setLength(0);

					// Extract method reference part (after the '#') → goes into details column
					String methodRef = safeLine.contains("#") ? safeLine.substring(safeLine.indexOf("#")) : "";
					if (!methodRef.isEmpty()) {
						currentLog.append(methodRef);
					}
				} else {
					if (currentLog.length() > 0)
						currentLog.append("\n");
					currentLog.append(safeLine);
				}
			}

			if (currentStep != null) {
				flushRawLogsStepRow(sb, currentStep, currentLog.toString());
			}

			sb.append("</table></td></tr>");
			li++;
		}

		sb.append("</table>");
		return sb.toString();
	}

// helper: safe & namespaced
	private void flushRawLogsStepRow(StringBuilder sb, String step, String logContent) {
		String log = (logContent != null && !logContent.isEmpty()) ? logContent : "-";
		boolean expandable = log.length() > 300;

		sb.append("<tr><td>").append(step).append("</td><td>").append("<div class='rawlogs-log-wrapper'>");

		if (expandable) {
			sb.append("<div class='rawlogs-log-text'>").append(log).append("</div>")
					.append("<span class='rawlogs-toggle-icon' onclick=\"rawlogsToggleLog(this)\">&#9654;</span>");
		} else {
			sb.append("<div class='rawlogs-log-text expanded'>").append(log).append("</div>");
		}

		sb.append("</div></td></tr>");
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