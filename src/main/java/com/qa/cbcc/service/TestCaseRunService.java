package com.qa.cbcc.service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.TestCaseRepository;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;
import com.qa.cbcc.utils.TestContext;

import io.cucumber.core.cli.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Service
public class TestCaseRunService {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseRunService.class);

	@Autowired
	private FeatureService featureService;
	@Value("${feature.source:local}")
	private String featureSource;
	@Value("${feature.local.path:src/test/resources/features}")
	private String localFeatureDir;
	@Value("${feature.git.clone-dir:features-repo}")
	private String localCloneDir;
	@Value("${feature.git.feature-path:path/to/features}")
	private String gitFeatureSubPath;

	private final TestCaseRepository testCaseRepository;
	private final TestCaseRunHistoryRepository historyRepository;
	private final ObjectMapper objectMapper;
	private final ObjectWriter prettyWriter;

	public TestCaseRunService(TestCaseRepository testCaseRepository, TestCaseRunHistoryRepository historyRepository) {
		this.testCaseRepository = testCaseRepository;
		this.historyRepository = historyRepository;
		this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
		this.prettyWriter = objectMapper.writerWithDefaultPrettyPrinter();
	}

	public List<Map<String, Object>> runFromDTO(List<TestCaseDTO> testCases) throws Exception {
		if (featureSource.equalsIgnoreCase("git")) {
			featureService.syncGitAndParseFeatures();
		}
		String baseFeaturePath = featureSource.equalsIgnoreCase("git")
				? Paths.get(localCloneDir, gitFeatureSubPath).toString()
				: localFeatureDir;

		List<Map<String, Object>> results = new ArrayList<>();

		for (TestCaseDTO testCase : testCases) {
			TestContext.setTestCaseId(testCase.getTcId());
			Set<String> executedScenarioNames = new LinkedHashSet<>();
			Map<String, String> scenarioToFeatureMap = new HashMap<>();

			List<String> missingFeatures = new ArrayList<>();

			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				List<String> blocks = new ArrayList<>();
				String featureFilePath = Paths.get(baseFeaturePath, fs.getFeature()).toString();
				boolean featureExists = Files.exists(Paths.get(featureFilePath));

				if (!featureExists)
					missingFeatures.add(fs.getFeature());

				for (String scenarioName : fs.getScenarios()) {
					if (featureExists) {
						try {
							String block = extractScenarioBlock(featureFilePath, scenarioName);
							if (!block.isBlank()) {
								blocks.add(block);
								scenarioToFeatureMap.put(scenarioName, fs.getFeature());
							}
						} catch (IOException e) {
							logger.error("Error reading feature file: {}", featureFilePath, e);
						}
					}
				}
				fs.setScenarioBlocks(blocks);
			}

			File featureFile = generateTempFeatureFile(testCase);
			String[] argv = new String[] { "--glue", "com.qa.cbcc.stepdefinitions", featureFile.getAbsolutePath(),
					"--plugin", "pretty" };

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream originalOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				Main.run(argv, Thread.currentThread().getContextClassLoader());
			} finally {
				System.out.flush();
				System.setOut(originalOut);
				featureFile.delete();
			}

			String fullOutput = baos.toString();
			logger.info("===== Begin Test Output =====\n{}\n===== End Test Output =====", fullOutput);

			Set<Map<String, Object>> executedScenarios = new LinkedHashSet<>();
			String[] lines = fullOutput.split("\n");

			String currentScenario = null;
			boolean scenarioFailed = false;
			boolean inScenario = false;
			StringBuilder diffBuffer = new StringBuilder();

			for (String line : lines) {
				line = removeAnsiCodes(line).trim();
				if (line.startsWith("Scenario:")) {
					if (currentScenario != null) {
						executedScenarios.add(createScenarioResult(currentScenario, diffBuffer.toString(),
								!scenarioFailed, executedScenarioNames,
								scenarioToFeatureMap.getOrDefault(currentScenario, "unknown")));
					}
					currentScenario = line.replace("Scenario:", "").split("#")[0].trim();
					diffBuffer.setLength(0);
					scenarioFailed = false;
					inScenario = true;
				}
				if (inScenario) {
					if (!line.startsWith("Scenario:")) {
						diffBuffer.append(line).append("\n");
						if (line.contains("AssertionError") || line.contains("expected [")
								|| line.contains("Expected XML files to be equal")) {
							scenarioFailed = true;
						}
					}
				}

			}
			if (currentScenario != null) {
				executedScenarios.add(createScenarioResult(currentScenario, diffBuffer.toString(), !scenarioFailed,
						executedScenarioNames, scenarioToFeatureMap.getOrDefault(currentScenario, "unknown")));
			}

			executedScenarios
					.removeIf(s -> !testCase.getFeatureScenarios().stream().flatMap(fs -> fs.getScenarios().stream())
							.collect(Collectors.toSet()).contains(s.get("scenarioName")));

			boolean anyFailed = executedScenarios.stream().anyMatch(s -> "Failed".equals(s.get("status")));
			boolean allFailed = executedScenarios.isEmpty()
					|| executedScenarios.stream().allMatch(s -> "Failed".equals(s.get("status")));
			String statusByExecution = allFailed ? "Failed" : anyFailed ? "Partially Passed" : "Passed";

			TestCase entity = testCaseRepository.findById(testCase.getTcId()).orElse(null);
			String inputPath = entity != null ? entity.getInputFile() : null;
			String outputPath = entity != null ? entity.getOutputFile() : null;

			List<Map<String, Object>> xmlComparisonDetails = new ArrayList<>();
			Set<String> usedFeatures = executedScenarios.stream().map(s -> (String) s.get("featureFileName"))
					.collect(Collectors.toSet());

			for (String featureName : usedFeatures) {
				Set<Map<String, Object>> relatedScenarios = executedScenarios.stream()
						.filter(s -> featureName.equals(s.get("featureFileName"))).collect(Collectors.toSet());
				Map<String, Object> xmlDetail = buildXmlComparisonDetails(inputPath, outputPath, featureName,
						relatedScenarios, objectMapper);
				int diffCount = (int) Optional.ofNullable(xmlDetail.get("differences")).map(d -> ((List<?>) d).size())
						.orElse(0);
				xmlDetail.put("diffCount", diffCount);
				xmlDetail.put("scenarioName", relatedScenarios.stream().map(s -> (String) s.get("scenarioName"))
						.findFirst().orElse("XML Comparison"));
				xmlComparisonDetails.add(xmlDetail);
			}

			if (executedScenarios.isEmpty() && !missingFeatures.isEmpty()) {
				Map<String, Object> missingDetail = new HashMap<>();
				missingDetail.put("message", "No scenarios executed (feature file(s) missing)");
				missingDetail.put("errorType", "MissingFeatureFile");
				missingDetail.put("missingFiles", missingFeatures);
				xmlComparisonDetails.add(missingDetail);
			}

			String comparisonStatus = executedScenarios.isEmpty() && !missingFeatures.isEmpty() ? null
					: xmlComparisonDetails.stream().anyMatch(m -> !"✅ XML files are equal.".equals(m.get("message")))
							? "Mismatched"
							: "Matched";

			String finalStatus = ("Passed".equals(statusByExecution) && "Mismatched".equals(comparisonStatus))
					? "Discrepancy"
					: statusByExecution;

			LocalDateTime executedOn = LocalDateTime.now();
			TestCaseRunHistory history = new TestCaseRunHistory();
			history.setTestCaseId(testCase.getTcId());
			history.setRunTime(executedOn);
			history.setRunStatus(finalStatus);
			history.setXmlDiffStatus(comparisonStatus);
			history.setXmlParsedDifferencesJson(prettyWriter.writeValueAsString(xmlComparisonDetails));
			long durationStart = System.currentTimeMillis();
			history.setExecutedScenarios(
					executedScenarioNames.isEmpty() ? null : String.join(", ", executedScenarioNames));

			List<String> passedNames = executedScenarios.stream().filter(s -> "Passed".equals(s.get("status")))
					.map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());
			List<String> failedNames = executedScenarios.stream().filter(s -> "Failed".equals(s.get("status")))
					.map(s -> (String) s.get("scenarioName")).collect(Collectors.toList());

			Set<String> allScenarioNames = testCase.getFeatureScenarios().stream()
					.flatMap(fs -> fs.getScenarios().stream()).collect(Collectors.toSet());
			Set<String> executedNamesSet = executedScenarios.stream().map(s -> s.get("scenarioName").toString())
					.collect(Collectors.toSet());
			Set<String> unexecuted = new HashSet<>(allScenarioNames);
			unexecuted.removeAll(executedNamesSet);

			Map<String, Object> runSummary = new LinkedHashMap<>();
			runSummary.put("totalExecutedScenarios", executedScenarios.size());
			runSummary.put("failedScenarioNames", failedNames);
			runSummary.put("passedScenarioNames", passedNames);
			runSummary.put("totalFailedScenarios", failedNames.size());
			runSummary.put("totalPassedScenarios", passedNames.size());
			runSummary.put("durationMillis", System.currentTimeMillis() - durationStart);
			runSummary.put("totalMissingScenarios", unexecuted.size());

			Map<String, Object> outputLogMap = new LinkedHashMap<>();
			outputLogMap.put("runSummary", runSummary);
			history.setOutputLog(prettyWriter.writeValueAsString(outputLogMap));
			history.setRawCucumberLog(formatRawLog(fullOutput));
			history.setUnexecutedScenarios(unexecuted.isEmpty() ? null : String.join(", ", unexecuted));
			historyRepository.save(history);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("testCaseId", testCase.getTcId());
			result.put("testCaseName", testCase.getTcName());
			result.put("runOn", executedOn);
			result.put("tcStatus", finalStatus);
			result.put("xmlComparisonStatus", comparisonStatus);
			result.put("xmlComparisonDetails", xmlComparisonDetails);
			if (!executedScenarios.isEmpty()) {
				result.put("executedScenarios", executedScenarios);
			}
			result.put("diffSummary", computeDiffSummary(xmlComparisonDetails, executedScenarios));

			results.add(result);
			TestContext.clear();
		}

		return results;
	}

	private Map<String, Object> createScenarioResult(String scenarioName, String differences, boolean passed,
			Set<String> executedScenarioNames, String featureFileName) {
		executedScenarioNames.add(scenarioName);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("scenarioName", scenarioName);
		result.put("featureFileName", featureFileName);
		result.put("status", passed ? "Passed" : "Failed");
		if (!passed) {
			List<Map<String, Object>> parsedDiffs = extractXmlDifferences(differences);
			result.put("parsedDifferences", parsedDiffs);
			result.put("diffCount", parsedDiffs.size());
		}
		return result;
	}

	private String extractScenarioBlock(String featureFilePath, String scenarioName) throws IOException {
		List<String> lines = Files.readAllLines(Paths.get(featureFilePath));
		StringBuilder block = new StringBuilder();
		boolean capture = false;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
				String extractedName = trimmed.replace("Scenario:", "").replace("Scenario Outline:", "").split("#")[0]
						.trim();
				if (extractedName.equals(scenarioName)) {
					capture = true;
				} else if (capture) {
					break;
				}
			}
			if (capture) {
				block.append(line).append("\n");
			}
		}
		return block.toString().trim();
	}

	public List<Map<String, Object>> extractXmlDifferences(String diffOutput) {
		List<Map<String, Object>> diffs = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		StringBuilder buffer = new StringBuilder();
		boolean inside = false;

		for (String line : diffOutput.split("\r?\n")) {
			line = line.trim();
			boolean isNewStart = line.matches("(?i)^Expected .+ but was .+ - comparing .+ at .+")
					|| line.contains("Expected XML files to be equal") || line.contains("AssertionError")
					|| line.contains("expected [") || line.contains("Expected child")
					|| line.contains("Expected element") || line.contains("Expected attribute");

			if (isNewStart) {
				if (buffer.length() > 0) {
					Map<String, Object> parsed = parseSingleDiff(buffer.toString(), seen);
					if (parsed != null)
						diffs.add(parsed);
					buffer.setLength(0);
				}
				inside = true;
			}
			if (inside)
				buffer.append(line).append("\n");
		}
		if (buffer.length() > 0) {
			Map<String, Object> parsed = parseSingleDiff(buffer.toString(), seen);
			if (parsed != null)
				diffs.add(parsed);
		}
		return diffs;
	}

	private Map<String, Object> parseSingleDiff(String raw, Set<String> seen) {
		raw = raw.trim();

		// Remove stack traces and footer summary from raw
		raw = raw.replaceAll(
				"(?s)(?m)^at org\\.testng\\..*|^at com\\.qa\\.cbcc\\..*|^at ✽\\..*|^Failed scenarios:.*|^\\d+ Scenarios.*|^\\d+ Steps.*|^\\d+m\\d+\\.\\d+s.*",
				"").replaceAll("\n{2,}", "\n").trim();

		String xpath = extractXPath(raw);
		String node = extractNodeNameFromXPath(xpath);
		String dedupKey = (xpath != null ? xpath : "") + "|" + node + "|" + raw;
		if (!seen.add(dedupKey))
			return null;

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("xpath", xpath);
		if (xpath != null)
			m.put("node", node);

		if (raw.contains("Expected text value")) {
			m.put("differenceType", "Text Mismatch");
			m.put("expected", extractBetween(raw, "Expected text value '", "' but was '"));
			m.put("actual", extractBetween(raw, "but was '", "' - comparing"));
		} else if (raw.contains("Expected attribute value")) {
			m.put("differenceType", "Attribute Mismatch");
			m.put("expected", extractBetween(raw, "Expected attribute value '", "' but was '"));
			m.put("actual", extractBetween(raw, "but was '", "' - comparing"));
			m.put("attribute", extractAttributeName(raw));
		} else if (raw.contains("Expected element tag name")) {
			m.put("differenceType", "Tag Mismatch");
			m.put("expected", extractBetween(raw, "Expected element tag name '", "' but was '"));
			m.put("actual", extractBetween(raw, "but was '", "' - comparing"));
		} else if (raw.contains("Expected child nodelist length")) {
			m.put("differenceType", "Child Count Mismatch");
			m.put("description", raw);
		} else if (raw.contains("Expected child 'null'") || raw.contains("Expected child")) {
			m.put("differenceType", "Missing/Extra Node");
			m.put("description", raw);
		} else if (raw.contains("expected [true] but found [false]")) {
			m.put("differenceType", "Assertion Result");
			m.put("description", raw);
		} else {
			return null;
		}
		return m;
	}

	private String extractBetween(String line, String start, String end) {
		int s = line.indexOf(start);
		int e = line.indexOf(end, s + start.length());
		return (s >= 0 && e >= 0) ? line.substring(s + start.length(), e) : null;
	}

	private String extractXPath(String line) {
		Matcher matcher = Pattern.compile(" at (/[\\w\\[\\]/@\\.\\(\\)]+)").matcher(line);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	private String extractNodeNameFromXPath(String xpath) {
		if (xpath == null || !xpath.contains("/"))
			return xpath;
		String[] parts = xpath.split("/");
		return parts.length > 0 ? parts[parts.length - 1].replaceAll("\\[.*?\\]", "") : xpath;
	}

	private String extractAttributeName(String line) {
		Pattern pattern = Pattern.compile("@([a-zA-Z0-9_-]+)");
		Matcher matcher = pattern.matcher(line);
		return matcher.find() ? matcher.group(1) : null;
	}

	private Map<String, Object> computeDiffSummary(List<Map<String, Object>> xmlComparisonDetails,
			Set<Map<String, Object>> executedScenarios) {
		int xmlDiffCount = xmlComparisonDetails.stream()
				.mapToInt(detail -> ((List<?>) detail.getOrDefault("differences", List.of())).size()).sum();

		int cucumberDiffCount = executedScenarios.stream()
				.mapToInt(s -> ((List<?>) s.getOrDefault("parsedDifferences", List.of())).size()).sum();

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("xmlComparatorDiffCount", xmlDiffCount);
		summary.put("cucumberExecutionDiffCount", cucumberDiffCount);
		return summary;
	}

	public String formatRawLog(String log) {
		String[] lines = log.split("\\r?\\n");
		return Arrays.stream(lines).map(line -> removeAnsiCodes(line).trim()).filter(line -> !line.isEmpty())
				.collect(Collectors.joining("\n"));
	}

	private String removeAnsiCodes(String input) {
		return input.replaceAll("\\u001B\\[[;\\d]*m", "");
	}

	private File generateTempFeatureFile(TestCaseDTO testCase) throws IOException {
		StringBuilder content = new StringBuilder("Feature: ").append(testCase.getTcName()).append("\n\n");
		for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios())
			for (String block : fs.getScenarioBlocks())
				content.append(block).append("\n\n");
		File tempFile = File.createTempFile("testcase_", ".feature");
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
			writer.write(content.toString());
		} catch (IOException e) {
			e.printStackTrace(); // Or better: use a logger
		}
		return tempFile;
	}

	public List<Map<String, Object>> runByIds(List<Long> testCaseIds) {
		List<TestCaseDTO> dtos = testCaseRepository.findByIdTCIn(testCaseIds).stream().map(this::convertToDTO)
				.collect(Collectors.toList());
		try {
			return runFromDTO(dtos);
		} catch (Exception e) {
			logger.error("Test Run failed", e);
			return Collections.emptyList();
		}
	}

	private TestCaseDTO convertToDTO(TestCase tc) {
		TestCaseDTO dto = new TestCaseDTO();
		dto.setTcId(tc.getIdTC());
		dto.setTcName(tc.getTcName());
		dto.setDescription(tc.getDescription());
		try {
			dto.setFeatureScenarios(objectMapper.readValue(tc.getFeatureScenarioJson(),
					new TypeReference<List<TestCaseDTO.FeatureScenario>>() {
					}));
		} catch (Exception e) {
			throw new RuntimeException("Invalid featureScenarioJson", e);
		}
		return dto;
	}

	private Map<String, Object> buildXmlComparisonDetails(String inputFile, String outputFile, String featureFileName,
			Set<Map<String, Object>> executedScenarios, ObjectMapper objectMapper) {
		Map<String, Object> xmlDetail = new LinkedHashMap<>();

		xmlDetail.put("inputFile", inputFile);
		xmlDetail.put("outputFile", outputFile);
		xmlDetail.put("featureFileName", featureFileName);

		String scenarioName = executedScenarios.stream().map(s -> (String) s.get("scenarioName"))
				.filter(n -> n.toLowerCase().contains("xml")).findFirst().orElse("XML Comparison");
		xmlDetail.put("scenarioName", scenarioName);

		if (!isXml(inputFile) || !isXml(outputFile)) {
			xmlDetail.put("message", "⚠️ Skipped: Input/Output file is not XML.");
			xmlDetail.put("differences", List.of());
			return xmlDetail;
		}

		String fullInputPath = Paths.get("src/main/resources", inputFile).toString();
		String fullOutputPath = Paths.get("src/main/resources", outputFile).toString();

		String xmlComparisonResult = com.qa.cbcc.utils.XmlComparator.compareXmlFiles(fullInputPath, fullOutputPath);

		if (xmlComparisonResult.contains("✅ XML files are equal.")) {
			xmlDetail.put("message", "✅ XML files are equal.");
			xmlDetail.put("differences", List.of());
		} else if (xmlComparisonResult.contains("❌ Error comparing XML files")) {
			xmlDetail.put("message", xmlComparisonResult);
			xmlDetail.put("differences", List.of());
		} else {
			List<Map<String, Object>> parsed = extractXmlDifferences(xmlComparisonResult);
			xmlDetail.put("message", "❌ XML files have differences");
			xmlDetail.put("differences", parsed);
		}

		return xmlDetail;
	}

	private boolean isXml(String fileName) {
		return fileName != null && fileName.toLowerCase().endsWith(".xml");
	}

}
