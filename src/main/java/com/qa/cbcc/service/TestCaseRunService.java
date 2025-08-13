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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

	@Value("${cleanup.temp.feature:true}")
	private boolean cleanupTempFeature;

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
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equalsIgnoreCase(featureFileName)).findFirst();
		} catch (IOException e) {
			logger.error("Error walking feature directory", e);
			return Optional.empty();
		}
	}

	@SuppressWarnings("unchecked")
	private List<String> safeList(Object obj) {
		if (obj instanceof List<?>) {
			return ((List<?>) obj).stream().map(String::valueOf).collect(Collectors.toList());
		}
		return new ArrayList<>();
	}

	public List<Map<String, Object>> runFromDTO(List<TestCaseDTO> testCases) throws Exception {
		GitConfigDTO config = featureService.getGitConfig();

		if (config.getSourceType().equalsIgnoreCase("git")) {
			featureService.syncGitAndParseFeatures();
		}

		String baseFeaturePath = config.getSourceType().equalsIgnoreCase("git")
				? Paths.get(config.getCloneDir(), config.getFeaturePath()).toString()
				: config.getFeaturePath();

		List<Map<String, Object>> results = new ArrayList<>();

		for (TestCaseDTO testCase : testCases) {
			TestContext.setTestCaseId(testCase.getTcId());
			Map<String, String> featureToPathMap = new HashMap<>();
			Map<String, String> scenarioToFeatureMap = new HashMap<>();
			List<String> missingFeatures = new ArrayList<>();

			for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
				List<String> blocks = new ArrayList<>();

				Path featureRoot = Paths.get(baseFeaturePath);
				Optional<Path> featurePathOpt = findFeatureFileRecursive(featureRoot, fs.getFeature());

				boolean featureExists = featurePathOpt.isPresent();
				String featureFilePath = featurePathOpt.map(Path::toString).orElse(null);

				if (!featureExists) {
					missingFeatures.add(fs.getFeature());
				}

				for (String scenarioName : fs.getScenarios()) {
					if (featureExists) {
						try {
							featureToPathMap.put(fs.getFeature(), featureFilePath);
							String block = extractScenarioBlock(featureFilePath, scenarioName);
							if (!block.isBlank()) {
								blocks.add(block);
								scenarioToFeatureMap.put(scenarioName,
										Paths.get(featureFilePath).getFileName().toString());
							}
						} catch (IOException e) {
							logger.error("Error reading feature file: {}", featureFilePath, e);
						}
					}
				}

				fs.setScenarioBlocks(blocks);
			}

			Set<Map<String, Object>> executedScenarios = new LinkedHashSet<>();

			// 1. Generate temporary feature file from TestCaseDTO
	        File featureFile = generateTempFeatureFile(testCase);
	        File jsonReportFile = File.createTempFile("cucumber-report", ".json");

			// Create a temp folder inside project directory if not exists
//			Path tempDir = Paths.get(System.getProperty("user.dir"), "temp");
//			if (!Files.exists(tempDir)) {
//				Files.createDirectories(tempDir);
//			}
//			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
//			File featureFile = tempDir.resolve("testcase_" + testCase.getTcId() + "_" + timestamp + ".feature")
//					.toFile();
//			try (FileWriter writer = new FileWriter(featureFile)) {
//				writer.write(generateTempFeatureContent(testCase));
//			}
//
//			File jsonReportFile = Paths.get(tempDir.toString(), "cucumber-report_" + testCase.getTcId() + ".json")
//					.toFile();

			// Keep mapping for replacement
			Map<String, String> tempPathMapping = new HashMap<>();
			tempPathMapping.put(featureFile.getAbsolutePath(),
					featureToPathMap.values().stream().findFirst().orElse(featureFile.getAbsolutePath()));

			// 2. Setup Cucumber command-line arguments
			String[] argv = new String[] { "--glue", "com.qa.cbcc.stepdefinitions", featureFile.getAbsolutePath(),
					"--plugin", "pretty", "--plugin", "json:" + jsonReportFile.getAbsolutePath() };

			// 3. Capture Cucumber stdout (optional)
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream originalOut = System.out;
			System.setOut(new PrintStream(baos));

			Map<String, Map<String, Pair<List<String>, List<String>>>> exampleMap;
			try {
				Main.run(argv, Thread.currentThread().getContextClassLoader());

				// ✅ Extract from the temp feature file BEFORE deleting it
				exampleMap = extractExamplesFromFeature(featureFile);
			} finally {
				System.out.flush();
				System.setOut(originalOut);
				if (cleanupTempFeature && featureFile.exists()) {
					if (!featureFile.delete()) {
						logger.warn("Could not delete temp feature file: {}", featureFile.getAbsolutePath());
					} else {
						logger.info("Deleted temp feature file: {}", featureFile.getAbsolutePath());
					}
				}

			}

			String fullOutput = baos.toString();

			// ✅ Replace temp paths with actual feature paths in output
			for (Map.Entry<String, String> entry : tempPathMapping.entrySet()) {
				String tempPath = entry.getKey().replace("\\", "/");
				String realPath = entry.getValue().replace("\\", "/");
				fullOutput = fullOutput.replace(tempPath, realPath);
			}

			logger.info("===== Begin Test Output =====\n{}\n===== End Test Output =====", fullOutput);

			// 5. Parse the generated JSON report
			executedScenarios = parseCucumberJson(jsonReportFile, exampleMap, featureToPathMap);

			if (jsonReportFile.exists())
				jsonReportFile.delete();

			// 6. Extract scenario names from executedScenarios
			Set<String> executedScenarioNames = executedScenarios.stream()
					.map(s -> s.get("scenarioName").toString().trim())
					.map(name -> name.replaceAll("\\s*\\[example #[0-9]+\\]$", "")).collect(Collectors.toSet());

			Set<String> declaredScenarioNames = testCase.getFeatureScenarios().stream()
					.flatMap(fs -> fs.getScenarios().stream()).map(String::trim).collect(Collectors.toSet());

			Set<String> unexecuted = declaredScenarioNames.stream()
					.filter(declared -> executedScenarioNames.stream().noneMatch(
							executed -> executed.equals(declared) || executed.startsWith(declared + " [example")))
					.collect(Collectors.toSet());

			List<Map<String, Object>> unexecutedList = new ArrayList<>();
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

			boolean anyFailed = executedScenarios.stream().anyMatch(s -> "Failed".equals(s.get("status")));
			boolean allFailed = executedScenarios.isEmpty()
					|| executedScenarios.stream().allMatch(s -> "Failed".equals(s.get("status")));
			String statusByExecution = allFailed ? "Failed" : anyFailed ? "Partially Passed" : "Passed";

			TestCase entity = testCaseRepository.findById(testCase.getTcId()).orElse(null);
			String inputPath = entity != null ? entity.getInputFile() : null;
			String outputPath = entity != null ? entity.getOutputFile() : null;

			List<Map<String, Object>> xmlComparisonDetails = new ArrayList<>();

			for (Map<String, Object> scenario : executedScenarios) {
				String featureName = (String) scenario.get("featureFileName");
				if (featureName == null)
					continue;

				Map<String, Object> xmlDetail = buildXmlComparisonDetails(inputPath, outputPath,
						Paths.get(featureName).getFileName().toString(), Collections.singleton(scenario), objectMapper);

				xmlDetail.put("scenarioName", scenario.get("scenarioName"));

				String scenarioType = (scenario.containsKey("exampleHeader") && scenario.containsKey("exampleValues"))
						? "Scenario Outline"
						: "Scenario";
				xmlDetail.put("scenarioType", scenarioType);

				if (scenario.containsKey("exampleHeader")) {
					xmlDetail.put("exampleHeader", scenario.get("exampleHeader"));
				}
				if (scenario.containsKey("exampleValues")) {
					xmlDetail.put("exampleValues", scenario.get("exampleValues"));
				}

				int diffCount = (int) Optional.ofNullable(xmlDetail.get("differences")).map(d -> ((List<?>) d).size())
						.orElse(0);
				xmlDetail.put("diffCount", diffCount);

				List<Map<String, Object>> unexecutedForFeature = unexecutedList.stream().filter(e -> {
					String name = (String) e.get("scenarioName");
					return testCase.getFeatureScenarios().stream().filter(fs -> fs.getScenarios().contains(name))
							.map(TestCaseDTO.FeatureScenario::getFeature)
							.anyMatch(f -> f.equals(Paths.get(featureName).getFileName().toString()));
				}).collect(Collectors.toList());

				if (!unexecutedForFeature.isEmpty()) {
					xmlDetail.put("unexecutedScenarios", unexecutedForFeature);
				}

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
			List<Map<String, Object>> passedScenariosDetailed = new ArrayList<>();
			for (Map<String, Object> exec : executedScenarios) {
				if ("Passed".equals(exec.get("status"))) {
					Map<String, Object> passedEntry = new LinkedHashMap<>();
					passedEntry.put("scenarioName", exec.get("scenarioName"));
					passedEntry.put("scenarioType", exec.getOrDefault("scenarioType", "Scenario"));

					if ("Scenario Outline".equals(exec.get("scenarioType"))) {
						passedEntry.put("exampleHeader", exec.getOrDefault("exampleHeader", List.of()));
						passedEntry.put("exampleValues", exec.getOrDefault("exampleValues", List.of()));
					}

					passedScenariosDetailed.add(passedEntry);
				}
			}
			runSummary.put("passedScenarioDetails", passedScenariosDetailed);

			runSummary.put("totalFailedScenarios", failedNames.size());
			runSummary.put("totalPassedScenarios", passedNames.size());
			runSummary.put("durationMillis", System.currentTimeMillis() - durationStart);
			runSummary.put("totalUnexecutedScenarios", unexecuted.size());

			Map<String, Object> outputLogMap = new LinkedHashMap<>();
			outputLogMap.put("runSummary", runSummary);
			outputLogMap.put("unexecutedScenarioDetails", unexecutedList);

			List<Map<String, Object>> failedReasons = new ArrayList<>();
			for (Map<String, Object> exec : executedScenarios) {
				if ("Failed".equals(exec.get("status"))) {
					Map<String, Object> entry = new LinkedHashMap<>();
					List<String> parsedDiffs = (List<String>) exec.getOrDefault("parsedDifferences", List.of());

					entry.put("scenarioName", exec.get("scenarioName"));
					entry.put("scenarioType", exec.getOrDefault("scenarioType", "Scenario"));

					if ("Scenario Outline".equals(exec.get("scenarioType"))) {
						entry.put("exampleHeader", exec.getOrDefault("exampleHeader", List.of()));
						entry.put("exampleValues", exec.getOrDefault("exampleValues", List.of()));
					}

					List<String> errors = safeList(exec.get("errors")).stream().map(err -> {
						for (Map.Entry<String, String> e : tempPathMapping.entrySet()) {
							err = err.replace(e.getKey().replace("\\", "/"), e.getValue().replace("\\", "/"));
						}
						return err;
					}).collect(Collectors.toList());

					entry.put("errors", errors);
					entry.put("parsedDifferences", parsedDiffs);
					entry.put("parsedDiffCount", parsedDiffs.size());
					failedReasons.add(entry);
				}
			}
			outputLogMap.put("failedScenarioDetails", failedReasons);

			history.setOutputLog(prettyWriter.writeValueAsString(outputLogMap));

			List<String> fullOutputLines = Arrays.asList(fullOutput.split("\\R"));
			List<String> trimmedLines = new ArrayList<>();

			for (String line : fullOutputLines) {
				trimmedLines.add(line);
				if (line.matches("^\\d+m\\d+\\.\\d+s$")) {
					break;
				}
			}

			List<String> cleanedLines = cleanRawCucumberLog(trimmedLines);

			while (!cleanedLines.isEmpty() && cleanedLines.get(cleanedLines.size() - 1).trim().isEmpty()) {
				cleanedLines.remove(cleanedLines.size() - 1);
			}

			List<String> finalLines = new ArrayList<>();
			boolean previousBlank = false;
			for (String line : cleanedLines) {
				if (line.trim().isEmpty()) {
					if (!previousBlank) {
						finalLines.add("");
						previousBlank = true;
					}
				} else {
					finalLines.add(line);
					previousBlank = false;
				}
			}

			String cleanedLog = String.join("\n", finalLines);
			history.setRawCucumberLog(cleanedLog);

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

	public Set<Map<String, Object>> parseCucumberJson(File jsonFile,
			Map<String, Map<String, Pair<List<String>, List<String>>>> exampleLookup,
			Map<String, String> featureFilePathMap) throws IOException {
		Set<Map<String, Object>> executedScenarios = new LinkedHashSet<>();
		List<Map<String, Object>> features = objectMapper.readValue(jsonFile, List.class);

		for (Map<String, Object> feature : features) {
			String featureUri = (String) feature.get("uri");
			String featureFileName = new File(featureUri).getName();
			List<Map<String, Object>> elements = (List<Map<String, Object>>) feature.get("elements");

			if (elements == null)
				continue;

			for (Map<String, Object> element : elements) {
				String scenarioName = (String) element.get("name");
//				String id = (String) element.get("id");
				String originalId = (String) element.get("id");
				String id = originalId;

				if (originalId != null && originalId.contains(";;")) {
					int splitIndex = originalId.lastIndexOf(";;");
					String scenarioNamePart = originalId.substring(0, splitIndex); // e.g.,
																					// "compare-two-xml-files-for-equality"
					String exampleIndexPart = originalId.substring(splitIndex + 2); // e.g., "2"

					try {
						int index = Integer.parseInt(exampleIndexPart.trim());
						int adjustedIndex = index - 1;
						id = scenarioNamePart + ";;" + adjustedIndex;
					} catch (NumberFormatException e) {
						// Log or ignore; fallback to original id
					}
				}

				String exampleIndex = "";

				if (id != null && id.contains(";;")) {
					String[] parts = id.split(";;");
					if (parts.length > 1) {
						exampleIndex = parts[1].trim();
					}
				}

				String fullScenarioName = scenarioName;
				if (!exampleIndex.isEmpty()) {
					fullScenarioName += " [example #" + exampleIndex + "]";
				}

				List<Map<String, Object>> steps = (List<Map<String, Object>>) element.get("steps");
				boolean hasFailed = false;

				List<String> errors = new ArrayList<>();
				List<String> parsedDifferences = new ArrayList<>();

				for (Map<String, Object> step : steps) {
					Map<String, Object> result = (Map<String, Object>) step.get("result");
					if (result != null && "failed".equalsIgnoreCase((String) result.get("status"))) {
						hasFailed = true;
						String errorMessage = (String) result.get("error_message");
						if (errorMessage != null) {
							// Remove stack trace
							String trimmedError;
							int cutIndex = errorMessage.indexOf("\tat");
							if (cutIndex != -1) {
								trimmedError = errorMessage.substring(0, cutIndex).trim();
							} else {
								trimmedError = errorMessage.trim();
							}

							// Remove exception prefix
							if (trimmedError.startsWith("java.lang.AssertionError:")) {
								trimmedError = trimmedError.replaceFirst("java\\.lang\\.AssertionError:\\s*", "");
							}

							// Normalize newlines
							trimmedError = trimmedError.replaceAll("[\\r\\n]+", " ").trim();

							// Split by "Differences:"
							String[] parts = trimmedError.split("Differences:", 2);

							// Add main error
							String mainError = parts[0].trim();
							errors.add(mainError);

							// Parse differences if present
//							if (parts.length > 1) {
//								String[] diffParts = parts[1].trim().split("\\)\\s*"); // each diff ends with `)`
//								for (String diff : diffParts) {
//									diff = diff.trim();
//									if (!diff.isEmpty()) {
//										parsedDifferences.add(diff + ")");
//									}
//								}
//							}
							// Parse differences if present
							if (parts.length > 1) {
								String differencesSection = parts[1].trim();

								// Split the section by regex pattern that identifies the start of each
								// difference
								// Most differences start with "Expected ..." so we use that as delimiter
								Pattern pattern = Pattern.compile("(?=\\b[Ee]xpected )");
								Matcher matcher = pattern.matcher(differencesSection);

								int lastIndex = 0;
								while (matcher.find()) {
									if (lastIndex != matcher.start()) {
										String diff = differencesSection.substring(lastIndex, matcher.start()).trim();
										if (!diff.isEmpty()
												&& !diff.matches("(?i)^expected \\[.*\\] but found \\[.*\\]$")) {
											parsedDifferences.add(diff);
										}
									}
									lastIndex = matcher.start();
								}

								// Add the last segment after the final match
								// Add the last segment after the final match
								String lastDiff = differencesSection.substring(lastIndex).trim();
								if (!lastDiff.isEmpty()
										&& !lastDiff.matches("(?i)^expected \\[.*\\] but found \\[.*\\]$")) {
									parsedDifferences.add(lastDiff);
								}
							}

						}
					}
				}

				Map<String, Object> scenarioMap = new LinkedHashMap<>();
				// For Adding Temp feature File Name in response
//				scenarioMap.put("tempfeatureFileName", featureFileName);

				// For Adding actual feature File Name and path in response
				for (Map.Entry<String, String> entry : featureFilePathMap.entrySet()) {
					String actulFileName = entry.getKey(); // e.g., compareXmlLocalSrc.feature
					// String actulFilePath = entry.getValue(); // e.g.,
					// src\test\resources\features\Compare\compareXmlLocalSrc.feature
					scenarioMap.put("featureFileName", actulFileName);
					String fullFeaturePath = featureFilePathMap.get(actulFileName);
					if (fullFeaturePath != null) {
						scenarioMap.put("featureFilePath", fullFeaturePath);
					}
				}

				scenarioMap.put("scenarioName", fullScenarioName);

				// ✅ Inject example header/values
				if (id != null && exampleLookup.containsKey(featureFileName)) {
					Map<String, Pair<List<String>, List<String>>> examples = exampleLookup.get(featureFileName);

					// Fix: Extract scenario ID from Cucumber JSON id to match our map keys
					String lookupId;
					if (id.contains(";;")) {
						// If the string contains ";;", get the substring after the first ';'
						lookupId = id.substring(id.indexOf(';') + 1);
					} else {
						// Otherwise, just use the original string
						lookupId = id;
					}

					if (examples.containsKey(lookupId)) {
						Pair<List<String>, List<String>> example = examples.get(lookupId);
						scenarioMap.put("scenarioType", "Scenario Outline");
						scenarioMap.put("exampleHeader", example.getKey());
						scenarioMap.put("exampleValues", example.getValue()); // ✅ add scenarioType
					} else {
						logger.warn("No matching example found for ID: {} in feature: {}", lookupId, featureFileName);
						scenarioMap.put("scenarioType", "Scenario"); // fallback in case no match
					}
				} else {
					scenarioMap.put("scenarioType", "Scenario"); // ✅ for scenarios without examples
				}
				scenarioMap.put("status", hasFailed ? "Failed" : "Passed");

				if (!errors.isEmpty()) {
					scenarioMap.put("errors", errors);
					if (!parsedDifferences.isEmpty()) {
						scenarioMap.put("parsedDifferences", parsedDifferences); // keeps the list
						scenarioMap.put("parsedDiffCount", parsedDifferences.size()); // adds a count under a different
																						// key
					}
				}
				executedScenarios.add(scenarioMap);
			}
		}
		return executedScenarios;
	}

	public Map<String, Map<String, Pair<List<String>, List<String>>>> extractExamplesFromFeature(File featureFile)
			throws IOException {
		Map<String, Map<String, Pair<List<String>, List<String>>>> exampleMap = new HashMap<>();
		List<String> lines = Files.readAllLines(featureFile.toPath());

		String currentScenarioId = null;
		List<String> headers = new ArrayList<>();
		List<List<String>> valuesList = new ArrayList<>();

		for (String line : lines) {
			line = line.trim();

			if (line.startsWith("Scenario Outline:")) {
				currentScenarioId = slugify(line.substring("Scenario Outline:".length()).trim());
				headers.clear();
				valuesList.clear();
			}

			if (line.startsWith("|")) {
				List<String> cells = Arrays.stream(line.split("\\|")).map(String::trim).filter(s -> !s.isEmpty())
						.collect(Collectors.toList());

				if (headers.isEmpty()) {
					headers.addAll(cells);
				} else {
					valuesList.add(cells);
				}
			}

			if (currentScenarioId != null && !headers.isEmpty() && !valuesList.isEmpty()) {
				Map<String, Pair<List<String>, List<String>>> scenarioExamples = new HashMap<>();
				for (int i = 0; i < valuesList.size(); i++) {
					String key = currentScenarioId + ";;" + (i + 1);
					scenarioExamples.put(key, ImmutablePair.of(headers, valuesList.get(i)));
				}
				exampleMap.put(featureFile.getName(), scenarioExamples);
			}
		}

		return exampleMap;
	}

	private String slugify(String input) {
		return input.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
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

	private String generateTempFeatureContent(TestCaseDTO testCase) {
		StringBuilder content = new StringBuilder("Feature: ").append(testCase.getTcName()).append("\n\n");
		for (TestCaseDTO.FeatureScenario fs : testCase.getFeatureScenarios()) {
			for (String block : fs.getScenarioBlocks()) {
				content.append(block).append("\n\n");
			}
		}
		return content.toString();
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

		// Count only real XML diff lines
		int cucumberDiffCount = executedScenarios.stream()
				.flatMap(s -> ((List<?>) s.getOrDefault("parsedDifferences", List.of())).stream())
				.filter(Objects::nonNull).map(Object::toString).map(String::trim).filter(s -> !s.isEmpty())
				.filter(line -> line.contains("comparing")) // Real XML diff lines include 'comparing'
				.collect(Collectors.toSet()) // Remove duplicates
				.size();

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

	private List<String> cleanRawCucumberLog(List<String> rawLines) {
		List<String> cleanedLog = new ArrayList<>();
		Set<String> seenBlocks = new HashSet<>();

		List<String> currentBlock = new ArrayList<>();
		boolean insideScenarioBlock = false;
		boolean insideSummaryBlock = false;
		List<String> summaryBlock = new ArrayList<>();

		for (String rawLine : rawLines) {
			// Remove ANSI codes
			String line = rawLine.replaceAll("\u001B\\[[;\\d]*m", "");

			// Start of a new scenario
			if (line.trim().startsWith("Scenario") || line.trim().startsWith("Scenario Outline")) {
				addBlockIfUnique(currentBlock, seenBlocks, cleanedLog);
				currentBlock.clear();
				insideScenarioBlock = true;
			}

			// Failed scenarios summary starts
			if (line.trim().startsWith("Failed scenarios:")) {
				addBlockIfUnique(currentBlock, seenBlocks, cleanedLog);
				currentBlock.clear();
				insideScenarioBlock = false;
				insideSummaryBlock = true;
			}

			// Collect lines into summary block
			if (insideSummaryBlock) {
				if (line.trim().isEmpty()) {
					insideSummaryBlock = false;
					addBlockIfUnique(summaryBlock, seenBlocks, cleanedLog);
					summaryBlock.clear();
				} else {
					summaryBlock.add(line);
				}
				continue;
			}

			// Capture scenario-related lines
			if (insideScenarioBlock || line.trim().startsWith("Given ") || line.trim().startsWith("When ")
					|| line.trim().startsWith("Then ") || line.trim().startsWith("And ")
					|| line.contains("java.lang.AssertionError") || line.contains("at org.testng")
					|| line.contains("at ✽.") || line.startsWith("file:///")) {
				currentBlock.add(line);
			} else if (!line.trim().isEmpty()) {
				// Treat as independent non-scenario line
				addBlockIfUnique(Collections.singletonList(line), seenBlocks, cleanedLog);
			}
		}

		// Final flush
		addBlockIfUnique(currentBlock, seenBlocks, cleanedLog);
		addBlockIfUnique(summaryBlock, seenBlocks, cleanedLog);

		return cleanedLog;
	}

	private void addBlockIfUnique(List<String> block, Set<String> seenBlocks, List<String> cleanedLog) {
		if (block == null || block.isEmpty())
			return;

		String blockString = String.join("\n", block).trim();
		if (!blockString.isEmpty() && seenBlocks.add(blockString)) {
			cleanedLog.addAll(block);
			cleanedLog.add(""); // Add spacing between blocks for readability
		}
	}

}
