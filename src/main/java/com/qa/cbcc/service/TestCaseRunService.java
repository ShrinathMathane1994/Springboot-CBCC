package com.qa.cbcc.service;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qa.cbcc.dto.GitConfigDTO;
import com.qa.cbcc.dto.TestCaseDTO;
import com.qa.cbcc.model.TestCase;
import com.qa.cbcc.model.TestCaseRunHistory;
import com.qa.cbcc.repository.TestCaseRepository;
import com.qa.cbcc.repository.TestCaseRunHistoryRepository;
import com.qa.cbcc.utils.TestContext;

import io.cucumber.core.cli.Main;

@Service
public class TestCaseRunService {

	private static final Logger logger = LoggerFactory.getLogger(TestCaseRunService.class);

	@Autowired
	private FeatureService featureService;

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

	private Optional<Path> findFeatureFileRecursive(Path rootDir, String featureFileName) {
	    try (Stream<Path> paths = Files.walk(rootDir)) {
	        return paths
	            .filter(Files::isRegularFile)
	            .filter(path -> path.getFileName().toString().equalsIgnoreCase(featureFileName))
	            .findFirst();
	    } catch (IOException e) {
	        logger.error("Error walking feature directory", e);
	        return Optional.empty();
	    }
	}


	public List<Map<String, Object>> runFromDTO(List<TestCaseDTO> testCases) throws Exception {

		GitConfigDTO config = featureService.getGitConfig();

		if (config.getSourceType().equalsIgnoreCase("git")) {
			featureService.syncGitAndParseFeatures();
		}

		String baseFeaturePath = config.getSourceType().equalsIgnoreCase("git")
				? Paths.get(config.getCloneDir(), config.getFeaturePath()).toString()
				: config.getFeaturePath(); // Local fallback

		List<Map<String, Object>> results = new ArrayList<>();

		for (TestCaseDTO testCase : testCases) {
			TestContext.setTestCaseId(testCase.getTcId());
			Set<String> executedScenarioNames = new LinkedHashSet<>();
			Map<String, String> scenarioToFeatureMap = new HashMap<>();
			List<String> missingFeatures = new ArrayList<>();

			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				List<String> blocks = new ArrayList<>();
//				String featureFilePath = Paths.get(baseFeaturePath, fs.getFeature()).toString();
//				boolean featureExists = Files.exists(Paths.get(featureFilePath));
				
				Path featureRoot = Paths.get(baseFeaturePath);
				Optional<Path> featurePathOpt = findFeatureFileRecursive(featureRoot, fs.getFeature());

				boolean featureExists = featurePathOpt.isPresent();
				String featureFilePath = featurePathOpt.map(Path::toString).orElse(null);

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

			Set<String> failedScenariosFromFooter = extractFailedScenariosFromFooter(fullOutput);
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
								scenarioToFeatureMap.getOrDefault(currentScenario, "unknown"),
								failedScenariosFromFooter));
					}
					currentScenario = line.replace("Scenario:", "").split("#")[0].trim();
					diffBuffer.setLength(0);
					scenarioFailed = false;
					inScenario = true;
				} else if (inScenario) {
					diffBuffer.append(line).append("\n");
				}
			}

			if (currentScenario != null) {
				executedScenarios.add(createScenarioResult(currentScenario, diffBuffer.toString(), !scenarioFailed,
						executedScenarioNames, scenarioToFeatureMap.getOrDefault(currentScenario, "unknown"),
						failedScenariosFromFooter));
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

			Set<String> allScenarioNames = testCase.getFeatureScenarios().stream()
					.flatMap(fs -> fs.getScenarios().stream()).collect(Collectors.toSet());
			Set<String> executedNamesSet = executedScenarios.stream().map(s -> s.get("scenarioName").toString())
					.collect(Collectors.toSet());
			Set<String> unexecuted = new HashSet<>(allScenarioNames);
			unexecuted.removeAll(executedNamesSet);

			List<Map<String, Object>> unexecutedList = new ArrayList<>();
			if (!unexecuted.isEmpty()) {
				for (String scenarioName : unexecuted) {
					Map<String, Object> detail = new LinkedHashMap<>();
					detail.put("scenarioName", scenarioName);

					try {
						Optional<String> featureOpt = testCase.getFeatureScenarios().stream()
								.filter(fs -> fs.getScenarios().contains(scenarioName))
								.map(TestCaseDTO.FeatureScenario::getFeature).findFirst();

						String feature = featureOpt.orElse(null);

						if (feature == null) {
							detail.put("reason", "Scenario declared but not linked to any feature in test case JSON");
						} else if (missingFeatures.contains(feature)) {
							detail.put("reason", "Feature file not found: " + feature);
						} else if (!scenarioToFeatureMap.containsKey(scenarioName)) {
							detail.put("reason", "Scenario block not found in feature file: " + feature);
						} else {
							detail.put("reason", "Unknown reason – check logs for full trace");
						}
					} catch (Exception e) {
						detail.put("reason", "Exception during reason detection");
						detail.put("exception", e.getClass().getSimpleName());
						detail.put("message", e.getMessage());
					}

					unexecutedList.add(detail);
				}
			}

			if (executedScenarios.isEmpty() && !missingFeatures.isEmpty()) {
				Map<String, Object> missingDetail = new HashMap<>();
				missingDetail.put("message", "No scenarios executed (feature file(s) missing)");
				missingDetail.put("errorType", "MissingFeatureFile");
				missingDetail.put("missingFiles", missingFeatures);

				// Group unexecuted reasons per feature
				List<Map<String, Object>> perMissingUnexecuted = unexecutedList.stream().filter(e -> {
					String name = (String) e.get("scenarioName");
					return missingFeatures.stream().anyMatch(f -> testCase.getFeatureScenarios().stream()
							.anyMatch(fs -> fs.getFeature().equals(f) && fs.getScenarios().contains(name)));
				}).collect(Collectors.toList());

				if (!perMissingUnexecuted.isEmpty()) {
					missingDetail.put("unexecutedScenarios", perMissingUnexecuted);
				}

				xmlComparisonDetails.add(missingDetail);
			}

			for (String featureName : usedFeatures) {
				Set<Map<String, Object>> relatedScenarios = executedScenarios.stream()
						.filter(s -> featureName.equals(s.get("featureFileName"))).collect(Collectors.toSet());

				Map<String, Object> xmlDetail = buildXmlComparisonDetails(inputPath, outputPath, featureName,
						relatedScenarios, objectMapper);

				List<Map<String, Object>> unexecutedForFeature = unexecutedList.stream().filter(e -> {
					String name = (String) e.get("scenarioName");
					return testCase.getFeatureScenarios().stream().filter(fs -> fs.getScenarios().contains(name))
							.map(TestCaseDTO.FeatureScenario::getFeature).anyMatch(f -> f.equals(featureName));
				}).collect(Collectors.toList());

				if (!unexecutedForFeature.isEmpty()) {
					xmlDetail.put("unexecutedScenarios", unexecutedForFeature);
				}

				int diffCount = (int) Optional.ofNullable(xmlDetail.get("differences")).map(d -> ((List<?>) d).size())
						.orElse(0);
				xmlDetail.put("diffCount", diffCount);
				xmlDetail.put("scenarioName", relatedScenarios.stream().map(s -> (String) s.get("scenarioName"))
						.findFirst().orElse("XML Comparison"));

				xmlComparisonDetails.add(xmlDetail);
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
			history.setTestCase(entity);
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

			Map<String, Object> runSummary = new LinkedHashMap<>();
			runSummary.put("totalExecutedScenarios", executedScenarios.size());
			runSummary.put("passedScenarioNames", passedNames);
			runSummary.put("totalFailedScenarios", failedNames.size());
			runSummary.put("totalPassedScenarios", passedNames.size());
			runSummary.put("durationMillis", System.currentTimeMillis() - durationStart);
			runSummary.put("totalUnexecutedScenarios", unexecuted.size());

			Map<String, Object> outputLogMap = new LinkedHashMap<>();
			outputLogMap.put("runSummary", runSummary);
			outputLogMap.put("unexecutedScenarioReasons", unexecutedList);

			List<Map<String, Object>> failedReasons = new ArrayList<>();
			for (Map<String, Object> exec : executedScenarios) {
				if ("Failed".equals(exec.get("status"))) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("scenarioName", exec.get("scenarioName"));
					entry.put("parsedDifferences", exec.getOrDefault("parsedDifferences", List.of()));
					failedReasons.add(entry);
				}
			}
			outputLogMap.put("failedScenarioReasons", failedReasons);

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
			result.put("diffSummary", computeDiffSummary(xmlComparisonDetails, executedScenarios));

			if (!executedScenarios.isEmpty()) {
				result.put("executedScenarios", executedScenarios);
			}
			if (!unexecutedList.isEmpty()) {
				result.put("unexecutedScenarios", unexecutedList);
			}

			results.add(result);

			if (entity != null) {
				entity.setLastRunOn(executedOn);
				entity.setLastRunStatus(finalStatus);
				testCaseRepository.save(entity);
			}

			TestContext.clear();
		}

		return results;
	}

	private Map<String, Object> createScenarioResult(String scenarioName, String differences, boolean passed,
			Set<String> executedScenarioNames, String featureFileName, Set<String> failedScenariosFromFooter) {
		executedScenarioNames.add(scenarioName);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("scenarioName", scenarioName);
		result.put("featureFileName", featureFileName);

		List<Map<String, Object>> parsedDiffs = extractXmlDifferences(differences);
		boolean shouldFail = parsedDiffs.stream().anyMatch(diff -> {
			Object type = diff.get("differenceType");
			return type != null && !type.equals("Assertion Result") && !type.equals("False Positive Equality");
		});

		boolean scenarioPass = passed && !shouldFail && !failedScenariosFromFooter.contains(scenarioName);

		result.put("status", scenarioPass ? "Passed" : "Failed");
		if (!scenarioPass) {
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
					|| line.contains("Expected XML files to be equal")
					|| line.contains("Expected XML files to be NOT equal") || line.contains("AssertionError")
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

	private String cleanStackAndFooterLines(String raw) {
		StringBuilder cleaned = new StringBuilder();
		for (String line : raw.split("\r?\n")) {
			line = line.trim();
			if (line.matches("^at .+\\(.+\\.java:\\d+\\)$"))
				continue;
			if (line.matches("^\\d+\\s+(Scenarios|Steps).*"))
				continue;
			if (line.matches("^\\d+m\\d+\\.\\d+s.*"))
				continue;
			if (line.equalsIgnoreCase("Failed scenarios:"))
				continue;
			if (line.startsWith("at ✽."))
				continue;
			if (line.matches("file:///.+\\.feature:\\d+\\s+#.+"))
				continue;
			cleaned.append(line).append("\n");
		}
		return cleaned.toString().trim().replaceAll("\n{2,}", "\n");
	}

	private static final List<DiffPattern> DIFF_PATTERNS = List.of(
			new DiffPattern("Text Mismatch", Pattern.compile("Expected text value '(.*?)' but was '(.*?)' - comparing"),
					(m, raw) -> Map.of("expected", m.group(1), "actual", m.group(2))),
			new DiffPattern("Attribute Mismatch",
					Pattern.compile("Expected attribute value '(.*?)' but was '(.*?)' - comparing"), (m, raw) -> {
						Map<String, Object> data = new LinkedHashMap<>();
						data.put("expected", m.group(1));
						data.put("actual", m.group(2));
						data.put("attribute", extractAttributeName(raw));
						return data;
					}),
			new DiffPattern("Tag Mismatch",
					Pattern.compile("Expected element tag name '(.*?)' but was '(.*?)' - comparing"),
					(m, raw) -> Map.of("expected", m.group(1), "actual", m.group(2))),
			new DiffPattern("Child Count Mismatch",
					Pattern.compile("Expected child nodelist length '.*?' but was '.*?' - comparing.*"),
					(m, raw) -> Map.of("description", raw)),
			new DiffPattern("Missing/Extra Node", Pattern.compile("Expected child '.*?' but was '.*?' - comparing.*"),
					(m, raw) -> Map.of("description", raw)),
			new DiffPattern("False Positive Equality",
					Pattern.compile(
							"Expected XML files to be NOT equal, but got:.*expected \\[true\\] but found \\[false\\]"),
					(m, raw) -> Map.of("description", raw)),
			new DiffPattern("Assertion Result",
					Pattern.compile("^(?!.*NOT equal).*expected \\[true\\] but found \\[false\\]"),
					(m, raw) -> Map.of("description", raw)),
			new DiffPattern("Parsing Error",
					Pattern.compile(".*(invalid xml|parsing error).*", Pattern.CASE_INSENSITIVE),
					(m, raw) -> Map.of("description", raw)),
			new DiffPattern("Namespace Mismatch", Pattern.compile(".*(namespace|xmlns).*", Pattern.CASE_INSENSITIVE),
					(m, raw) -> Map.of("description", raw)));

	private Map<String, Object> parseSingleDiff(String raw, Set<String> seen) {
		raw = cleanStackAndFooterLines(raw);
		String normalized = raw.replaceAll("file://.*#", "").replaceAll("\\s+", " ").trim();

		String xpath = extractXPath(normalized);
		String node = extractNodeNameFromXPath(xpath);
		String dedupKey = (xpath != null ? xpath : "") + "|" + node + "|" + normalized;
		if (!seen.add(dedupKey))
			return null;

		for (DiffPattern dp : DIFF_PATTERNS) {
			Matcher matcher = dp.pattern.matcher(normalized);
			if (matcher.find()) {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("xpath", xpath);
				if (xpath != null)
					result.put("node", node);
				result.put("differenceType", dp.type);
				result.putAll(dp.extractor.apply(matcher, raw));
				return result;
			}
		}

		return null;
	}

	private static class DiffPattern {
		String type;
		Pattern pattern;
		BiFunction<Matcher, String, Map<String, Object>> extractor;

		DiffPattern(String type, Pattern pattern, BiFunction<Matcher, String, Map<String, Object>> extractor) {
			this.type = type;
			this.pattern = pattern;
			this.extractor = extractor;
		}
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

	private static String extractAttributeName(String line) {
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

	private Set<String> extractFailedScenariosFromFooter(String output) {
		Set<String> failedScenarios = new HashSet<>();
		boolean insideFailedBlock = false;

		for (String line : output.split("\n")) {
			String cleanLine = removeAnsiCodes(line).trim();

			if (cleanLine.equalsIgnoreCase("Failed scenarios:")) {
				insideFailedBlock = true;
				continue;
			}

			if (insideFailedBlock) {
				if (cleanLine.isBlank() || cleanLine.matches("^\\d+ Scenarios.*")) {
					insideFailedBlock = false;
					continue;
				}

				// Match line like: "file:///.../testcase_123.feature:3 # Scenario Name"
				Matcher matcher = Pattern.compile(".*feature:\\d+\\s+#\\s+(.*)").matcher(cleanLine);
				if (matcher.find()) {
					String scenarioName = matcher.group(1).trim();
					failedScenarios.add(scenarioName);
				}
			}
		}

		return failedScenarios;
	}

}
